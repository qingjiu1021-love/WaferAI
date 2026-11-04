"""Persist model predictions without changing imported maps or yield statistics."""

import hashlib
import threading
from datetime import datetime, timezone

import numpy as np
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from .ml.inference import LABELS_ZH, ModelUnavailable
from .ml.preprocessing import CLASS_NAMES, validate_map
from .models import Prediction, Wafer
from .schemas import ClassScore, PredictionResult, WaferPrediction
from .wafer_store import ImportFailure, WaferStore


class PredictionService:
    def __init__(self, store: WaferStore, predictor):
        self.store, self.predictor = store, predictor
        self._lock = threading.Lock()

    @staticmethod
    def _response(row: Prediction) -> WaferPrediction:
        ordered = sorted(CLASS_NAMES, key=lambda label: row.scores[label], reverse=True)
        return WaferPrediction(
            id=row.id, wafer_id=row.wafer_id, model_version=row.model_version,
            model_sha256=row.model_sha256, preprocessing_id=row.preprocessing_id,
            label=row.label, label_zh=LABELS_ZH[row.label], score=row.score,
            scores=[ClassScore(label=label, label_zh=LABELS_ZH[label], score=row.scores[label]) for label in ordered],
            inference_ms=row.inference_ms, device=row.device, created_at=row.created_at,
        )

    @staticmethod
    def _find(session, wafer_id, identity, map_hash):
        return session.scalar(select(Prediction).where(
            Prediction.wafer_id == wafer_id, Prediction.model_sha256 == identity["model_sha256"],
            Prediction.input_sha256 == map_hash,
        ))

    def predict(self, wafer_id: int) -> PredictionResult:
        with self._lock, Session(self.store.engine) as session:
            wafer = session.get(Wafer, wafer_id)
            if wafer is None:
                raise ImportFailure(404, "WAFER_NOT_FOUND", "没有找到这张晶圆。")
            path = (self.store.root / wafer.map_path).resolve()
            if not path.is_relative_to(self.store.maps_dir):
                raise ImportFailure(409, "WAFER_DATA_CHANGED", "晶圆数据文件位置异常，请检查保存的数据。")
            try:
                matrix = validate_map(np.load(path, allow_pickle=False))
            except (OSError, ValueError, TypeError):
                raise ImportFailure(409, "WAFER_DATA_CHANGED", "晶圆矩阵无法读取，请检查保存的数据。") from None
            rows, cols = matrix.shape
            map_hash = hashlib.sha256(f"{rows}x{cols}:".encode() + matrix.tobytes()).hexdigest()
            if map_hash != wafer.map_sha256 or (rows, cols) != (wafer.rows_count, wafer.cols_count):
                raise ImportFailure(409, "WAFER_DATA_CHANGED", "晶圆矩阵与导入记录不一致，请检查保存的数据。")
            try:
                identity = self.predictor.identity()
                existing = self._find(session, wafer_id, identity, map_hash)
                if existing is not None:
                    return PredictionResult(already_predicted=True, prediction=self._response(existing))
                result = self.predictor.predict(matrix)
            except ModelUnavailable as error:
                raise ImportFailure(503, "MODEL_UNAVAILABLE", str(error)) from error
            except ValueError:
                raise ImportFailure(422, "MAP_NOT_SUPPORTED", "该晶圆无法生成有效的模型输入。") from None
            row = Prediction(
                wafer_id=wafer_id, model_version=identity["model_version"], model_sha256=identity["model_sha256"],
                preprocessing_id=identity["preprocessing_id"], input_sha256=map_hash,
                label=result["label"], score=result["score"], scores=result["scores"],
                inference_ms=result["inference_ms"], device=result["device"],
                created_at=datetime.now(timezone.utc).isoformat(timespec="seconds"),
            )
            session.add(row)
            try:
                session.flush()
                response = PredictionResult(already_predicted=False, prediction=self._response(row))
                session.commit()
                return response
            except IntegrityError:
                session.rollback()
                existing = self._find(session, wafer_id, identity, map_hash)
                if existing is None:
                    raise
                return PredictionResult(already_predicted=True, prediction=self._response(existing))

    def latest(self, wafer_id: int) -> WaferPrediction:
        with Session(self.store.engine) as session:
            if session.get(Wafer, wafer_id) is None:
                raise ImportFailure(404, "WAFER_NOT_FOUND", "没有找到这张晶圆。")
            prediction = session.scalar(select(Prediction).where(Prediction.wafer_id == wafer_id).order_by(Prediction.id.desc()).limit(1))
            if prediction is None:
                raise ImportFailure(404, "PREDICTION_NOT_FOUND", "这张晶圆还没有识别结果。")
            return self._response(prediction)

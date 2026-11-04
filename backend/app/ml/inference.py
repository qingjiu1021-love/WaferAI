"""Lazy-loaded CPU inference using the frozen, locally prepared CNN."""

from __future__ import annotations

import hashlib
import json
import pickle
import threading
import time
from pathlib import Path

import numpy as np

from .preprocessing import CLASS_NAMES, PREPROCESSING_ID, prepare_map

LABELS_ZH = {
    "none": "无特定缺陷模式", "Center": "中心型", "Donut": "环圈型",
    "Edge-Loc": "边缘局部型", "Edge-Ring": "边缘环型", "Loc": "局部型",
    "Random": "随机型", "Scratch": "划痕型", "Near-full": "近全片型",
}


class ModelUnavailable(Exception):
    pass


class ModelPredictor:
    def __init__(self, model_dir: Path):
        self.model_dir = model_dir.resolve()
        self._model = None
        self._identity = None
        self._lock = threading.RLock()

    def identity(self) -> dict:
        with self._lock:
            if self._model is None:
                self._load()
            return dict(self._identity)

    def _load(self):
        try:
            info = json.loads((self.model_dir / "model-info.json").read_text(encoding="utf-8"))
            model_path = self.model_dir / "best.pt"
            model_hash = hashlib.sha256(model_path.read_bytes()).hexdigest()
            if model_hash != info["model_sha256"]:
                raise ValueError("Serving checkpoint hash mismatch.")
            import torch
            from .model import MODEL_NAME, WaferCnn

            checkpoint = torch.load(model_path, map_location="cpu", weights_only=True)
            if (checkpoint.get("format_version") != 1 or checkpoint.get("model_name") != MODEL_NAME
                    or checkpoint.get("classes") != list(CLASS_NAMES)
                    or checkpoint.get("preprocessing_id") != PREPROCESSING_ID):
                raise ValueError("Serving checkpoint is incompatible.")
            model = WaferCnn()
            model.load_state_dict(checkpoint["state_dict"], strict=True)
            model.eval()
            torch.set_num_threads(2)
            self._identity = {"model_version": info["model_version"], "model_sha256": model_hash,
                              "preprocessing_id": PREPROCESSING_ID}
            self._model = model
        except (OSError, ValueError, KeyError, RuntimeError, ImportError, EOFError, pickle.UnpicklingError) as error:
            raise ModelUnavailable("无法加载识别模型，请检查后端模型文件及训练环境。") from error

    def predict(self, matrix: np.ndarray) -> dict:
        import torch
        from .model import encode_bins

        with self._lock:
            identity = self.identity()
            started = time.perf_counter()
            model_input = prepare_map(matrix)
            tensor = torch.from_numpy(model_input.copy()).unsqueeze(0)
            with torch.inference_mode():
                logits = self._model(encode_bins(tensor))
                scores = torch.softmax(logits.float(), dim=1)[0].cpu().numpy()
            if not np.isfinite(scores).all():
                raise ModelUnavailable("模型输出异常，请检查模型文件。")
            index = int(scores.argmax())
            return {**identity, "label": CLASS_NAMES[index], "score": float(scores[index]),
                    "scores": {label: float(score) for label, score in zip(CLASS_NAMES, scores)},
                    "inference_ms": (time.perf_counter() - started) * 1000, "device": "cpu"}

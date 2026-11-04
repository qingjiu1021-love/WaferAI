"""Synthetic predictors exercise persistence; random synthetic weights test inference plumbing."""

import gc
import hashlib
import json
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np
import torch
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.ml.inference import ModelPredictor, ModelUnavailable
from app.ml.model import MODEL_NAME, WaferCnn
from app.ml.preprocessing import CLASS_NAMES, PREPROCESSING_ID
from app.models import Prediction, Wafer
from app.prediction_service import PredictionService
from app.wafer_store import ImportFailure, WaferStore

TEST_ROOT = Path(__file__).resolve().parents[1] / ".test-work"


class SyntheticPredictor:
    def __init__(self, model_hash="a" * 64):
        self.model_hash, self.calls, self.fail = model_hash, 0, False

    def identity(self):
        return {"model_version": "synthetic-unit-test", "model_sha256": self.model_hash,
                "preprocessing_id": PREPROCESSING_ID}

    def predict(self, matrix):
        self.calls += 1
        if self.fail:
            raise ModelUnavailable("synthetic failure")
        return {**self.identity(), "label": "Center", "score": 0.6,
                "scores": {label: 0.6 if label == "Center" else 0.05 for label in CLASS_NAMES},
                "inference_ms": 1.0, "device": "synthetic-test"}


class PredictionTests(unittest.TestCase):
    def setUp(self):
        TEST_ROOT.mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="waferai-predictions-", dir=TEST_ROOT)
        self.root = Path(self.temporary.name).resolve()
        self.assertTrue(self.root.is_relative_to(TEST_ROOT.resolve()))
        self.store = WaferStore(self.root / "storage")
        content = json.dumps({"lot_code": "SYNTHETIC", "wafer_code": "W1", "map": [[0, 1, 2], [1, 2, 1]]}).encode()
        self.wafer = self.store.import_json(content, "synthetic.json").wafer
        self.predictor = SyntheticPredictor()
        self.service = PredictionService(self.store, self.predictor)

    def tearDown(self):
        self.store.close()
        gc.collect()
        self.assertTrue(self.root.is_relative_to(TEST_ROOT.resolve()))
        self.temporary.cleanup()

    def count(self):
        with Session(self.store.engine) as session:
            return session.scalar(select(func.count()).select_from(Prediction))

    def test_prediction_is_saved_cached_and_does_not_change_yield(self):
        first = self.service.predict(self.wafer.id)
        second = PredictionService(self.store, self.predictor).predict(self.wafer.id)
        self.assertFalse(first.already_predicted)
        self.assertTrue(second.already_predicted)
        self.assertEqual(first.prediction.id, second.prediction.id)
        self.assertEqual(self.count(), 1)
        self.assertEqual(self.predictor.calls, 1)
        self.assertEqual(self.service.latest(self.wafer.id), first.prediction)
        after = self.store.get_wafer(self.wafer.id)
        self.assertEqual(after.map, self.wafer.map)
        self.assertEqual(after.yield_rate, self.wafer.yield_rate)
        self.assertEqual(first.prediction.score_type, "softmax_uncalibrated")

    def test_model_change_creates_a_separate_traceable_prediction(self):
        previous = self.service.predict(self.wafer.id)
        updated = PredictionService(self.store, SyntheticPredictor("b" * 64)).predict(self.wafer.id)
        self.assertNotEqual(previous.prediction.id, updated.prediction.id)
        self.assertEqual(self.count(), 2)
        self.assertEqual(self.service.latest(self.wafer.id).model_sha256, "b" * 64)

    def test_failed_inference_creates_no_record(self):
        self.predictor.fail = True
        with self.assertRaises(ImportFailure) as error:
            self.service.predict(self.wafer.id)
        self.assertEqual(error.exception.status_code, 503)
        self.assertEqual(self.count(), 0)

    def test_missing_wafer_and_missing_prediction_are_distinct(self):
        with self.assertRaises(ImportFailure) as error:
            self.service.predict(999)
        self.assertEqual(error.exception.code, "WAFER_NOT_FOUND")
        with self.assertRaises(ImportFailure) as error:
            self.service.latest(self.wafer.id)
        self.assertEqual(error.exception.code, "PREDICTION_NOT_FOUND")

    def test_changed_map_is_rejected_even_if_a_cached_prediction_exists(self):
        self.service.predict(self.wafer.id)
        with Session(self.store.engine) as session:
            wafer = session.get(Wafer, self.wafer.id)
            path = self.store.root / wafer.map_path
        np.save(path, np.array([[0, 2, 2], [1, 2, 1]], dtype=np.uint8), allow_pickle=False)
        with self.assertRaises(ImportFailure) as error:
            self.service.predict(self.wafer.id)
        self.assertEqual(error.exception.code, "WAFER_DATA_CHANGED")
        self.assertEqual(self.predictor.calls, 1)

    def test_concurrent_requests_save_one_prediction(self):
        with ThreadPoolExecutor(max_workers=4) as pool:
            results = list(pool.map(lambda _: self.service.predict(self.wafer.id), range(4)))
        self.assertEqual(len({result.prediction.id for result in results}), 1)
        self.assertEqual(self.count(), 1)
        self.assertEqual(self.predictor.calls, 1)

    def test_cpu_engine_scores_and_metadata_use_the_loaded_model(self):
        model_dir = self.root / "synthetic-model"
        model_dir.mkdir()
        checkpoint = {"format_version": 1, "model_name": MODEL_NAME, "classes": list(CLASS_NAMES),
                      "preprocessing_id": PREPROCESSING_ID, "state_dict": WaferCnn().state_dict()}
        model_path = model_dir / "best.pt"
        torch.save(checkpoint, model_path)
        info = {"model_version": "synthetic-test", "model_sha256": hashlib.sha256(model_path.read_bytes()).hexdigest()}
        (model_dir / "model-info.json").write_text(json.dumps(info), encoding="utf-8")
        matrix = np.array(self.wafer.map, dtype=np.uint8)
        snapshot = matrix.copy()
        result = ModelPredictor(model_dir).predict(matrix)
        self.assertEqual(set(result["scores"]), set(CLASS_NAMES))
        self.assertAlmostEqual(sum(result["scores"].values()), 1.0, places=5)
        self.assertEqual(result["label"], max(result["scores"], key=result["scores"].get))
        self.assertEqual(result["score"], max(result["scores"].values()))
        self.assertEqual(result["model_sha256"], info["model_sha256"])
        np.testing.assert_array_equal(matrix, snapshot)
        info["model_sha256"] = "invalid"
        (model_dir / "model-info.json").write_text(json.dumps(info), encoding="utf-8")
        with self.assertRaises(ModelUnavailable):
            ModelPredictor(model_dir).identity()


if __name__ == "__main__":
    unittest.main()

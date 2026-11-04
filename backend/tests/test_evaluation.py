"""Synthetic fixtures verify held-out evaluation, compatibility and result reuse."""

import gc
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np
import torch

from app.ml.evaluation import evaluate_checkpoint
from app.ml.model import MODEL_NAME, WaferCnn
from app.ml.preprocessing import CLASS_NAMES, PREPROCESSING_ID

TEST_ROOT = Path(__file__).resolve().parents[1] / ".test-work"


class EvaluationTests(unittest.TestCase):
    def setUp(self):
        TEST_ROOT.mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="waferai-evaluation-", dir=TEST_ROOT)
        self.root = Path(self.temporary.name).resolve()
        self.assertTrue(self.root.is_relative_to(TEST_ROOT.resolve()))
        self.data = self.root / "synthetic-data"
        self.data.mkdir()
        maps = np.full((27, 64, 64), 255, dtype=np.uint8)
        maps[18:] = np.random.default_rng(42).integers(0, 3, size=(9, 64, 64), dtype=np.uint8)
        np.save(self.data / "maps.npy", maps, allow_pickle=False)
        np.save(self.data / "labels.npy", np.tile(np.arange(9, dtype=np.int64), 3), allow_pickle=False)
        np.savez(self.data / "splits.npz", train=np.arange(9), validation=np.arange(9, 18), test=np.arange(18, 27))
        hashes = {name: hashlib.sha256((self.data / name).read_bytes()).hexdigest()
                  for name in ("maps.npy", "labels.npy", "splits.npz")}
        report = {"complete": True, "format_version": 1, "classes": list(CLASS_NAMES),
                  "preprocessing": {"id": PREPROCESSING_ID}, "input_overlap_count": 0,
                  "split": {"lot_overlap_count": 0}, "artifact_sha256": hashes}
        report_path = self.data / "preparation.json"
        report_path.write_text(json.dumps(report), encoding="utf-8")
        self.payload = {"format_version": 1, "model_name": MODEL_NAME, "classes": list(CLASS_NAMES),
                        "preprocessing_id": PREPROCESSING_ID, "epoch": 3,
                        "config": {"preparation_sha256": hashlib.sha256(report_path.read_bytes()).hexdigest(),
                                   "split_sha256": hashes["splits.npz"], "mixed_precision": False},
                        "state_dict": WaferCnn().state_dict()}
        self.checkpoint = self.root / "synthetic-checkpoint.pt"
        torch.save(self.payload, self.checkpoint)
        self.output = self.root / "evaluation"

    def tearDown(self):
        gc.collect()
        self.assertTrue(self.root.is_relative_to(TEST_ROOT.resolve()))
        self.temporary.cleanup()

    def test_only_test_inputs_are_used_and_repeated_command_reuses_results(self):
        # Train/validation maps contain illegal bins: encoding either would fail.
        result = evaluate_checkpoint(self.checkpoint, self.data, self.output, device_name="cpu", batch_size=9)
        self.assertEqual(result["metrics"]["samples"], 9)
        self.assertEqual(np.asarray(result["confusion_matrix"]).sum(), 9)
        self.assertEqual([item["support"] for item in result["metrics"]["per_class"]], [1] * 9)
        self.assertTrue(result["test_evaluated"])
        for name in ("selection.json", "test-metrics.json", "test-confusion.csv", "test-per-class.csv", "test-report.md"):
            self.assertTrue((self.output / name).is_file())
        before = (self.output / "test-metrics.json").read_bytes()
        with patch("app.ml.evaluation.validate", side_effect=AssertionError("should reuse saved result")):
            reused = evaluate_checkpoint(self.checkpoint, self.data, self.output, device_name="cpu")
        self.assertEqual(result, reused)
        self.assertEqual(before, (self.output / "test-metrics.json").read_bytes())

    def test_another_checkpoint_cannot_overwrite_existing_test_results(self):
        evaluate_checkpoint(self.checkpoint, self.data, self.output, device_name="cpu", batch_size=9)
        before = (self.output / "test-metrics.json").read_bytes()
        self.payload["epoch"] = 4
        torch.save(self.payload, self.checkpoint)
        with self.assertRaises(FileExistsError):
            evaluate_checkpoint(self.checkpoint, self.data, self.output, device_name="cpu")
        self.assertEqual(before, (self.output / "test-metrics.json").read_bytes())

    def test_incompatible_class_order_is_rejected(self):
        self.payload["classes"] = list(reversed(CLASS_NAMES))
        torch.save(self.payload, self.checkpoint)
        with self.assertRaisesRegex(ValueError, "incompatible"):
            evaluate_checkpoint(self.checkpoint, self.data, self.output, device_name="cpu")

    def test_dataset_from_another_training_run_is_rejected(self):
        self.payload["config"]["preparation_sha256"] = "different-dataset"
        torch.save(self.payload, self.checkpoint)
        with self.assertRaisesRegex(ValueError, "does not match"):
            evaluate_checkpoint(self.checkpoint, self.data, self.output, device_name="cpu")


if __name__ == "__main__":
    unittest.main()

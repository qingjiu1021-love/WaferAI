"""Synthetic data only: verify metrics, CUDA gradients, checkpoint loading and test isolation."""

import gc
import hashlib
import json
import tempfile
import unittest
from pathlib import Path

import numpy as np
import torch
from sklearn.metrics import accuracy_score, balanced_accuracy_score, confusion_matrix, f1_score

from app.ml.model import WaferCnn, encode_bins
from app.ml.preprocessing import CLASS_NAMES, PREPROCESSING_ID
from app.ml.training import (TrainingConfig, class_weights, confusion_metrics,
                             load_prepared_data, train, train_epoch)

TEST_ROOT = Path(__file__).resolve().parents[1] / ".test-work"


class TrainingTests(unittest.TestCase):
    def setUp(self):
        TEST_ROOT.mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="waferai-training-", dir=TEST_ROOT)
        self.root = Path(self.temporary.name).resolve()
        self.assertTrue(self.root.is_relative_to(TEST_ROOT.resolve()))

    def tearDown(self):
        gc.collect()
        self.assertTrue(self.root.is_relative_to(TEST_ROOT.resolve()))
        self.temporary.cleanup()

    def fixture(self):
        """Poison held-out inputs so accidental use in training/evaluation raises."""
        directory = self.root / "synthetic-dataset"
        directory.mkdir()
        maps = np.random.default_rng(42).integers(0, 3, size=(36, 64, 64), dtype=np.uint8)
        maps[27:] = 255
        np.save(directory / "maps.npy", maps, allow_pickle=False)
        np.save(directory / "labels.npy", np.tile(np.arange(9, dtype=np.int64), 4), allow_pickle=False)
        np.savez(directory / "splits.npz", train=np.arange(18), validation=np.arange(18, 27), test=np.arange(27, 36))
        report = {"complete": True, "format_version": 1, "classes": list(CLASS_NAMES),
                  "preprocessing": {"id": PREPROCESSING_ID}, "input_overlap_count": 0,
                  "split": {"lot_overlap_count": 0}, "source_sha256": "synthetic-test-fixture-not-real-data",
                  "artifact_sha256": {name: hashlib.sha256((directory / name).read_bytes()).hexdigest()
                                      for name in ("maps.npy", "labels.npy", "splits.npz")}}
        (directory / "preparation.json").write_text(json.dumps(report), encoding="utf-8")
        return directory

    def test_one_hot_bins_and_spatial_logits(self):
        maps = torch.zeros((3, 64, 64), dtype=torch.uint8)
        maps[1], maps[2] = 1, 2
        encoded = encode_bins(maps)
        self.assertEqual(tuple(encoded.shape), (3, 3, 64, 64))
        self.assertTrue(torch.all(encoded.sum(dim=1) == 1))
        self.assertTrue(torch.all(encoded[1, 1] == 1))
        self.assertTrue(torch.all(encoded[2, 2] == 1))
        with self.assertRaises(ValueError):
            encode_bins(maps.float())
        model = WaferCnn().eval()
        with torch.inference_mode():
            logits = model(encoded)
        self.assertEqual(tuple(logits.shape), (3, 9))
        self.assertTrue(torch.isfinite(logits).all())

    def test_rare_training_class_gets_more_weight(self):
        weights = class_weights(np.concatenate((np.zeros(1000, dtype=int), np.arange(9))))
        self.assertGreater(weights[8].item(), weights[0].item())
        self.assertAlmostEqual(weights.mean().item(), 1.0, places=6)
        with self.assertRaises(ValueError):
            class_weights(np.zeros(4, dtype=int))

    def test_metrics_match_sklearn_including_unpredicted_classes(self):
        targets = np.tile(np.arange(9), 4)
        predictions = targets.copy()
        predictions[::3] = 0
        predictions[predictions == 8] = 1
        report = confusion_metrics(confusion_matrix(targets, predictions, labels=np.arange(9)))
        self.assertAlmostEqual(report["macro_f1"], f1_score(targets, predictions, labels=np.arange(9), average="macro", zero_division=0))
        self.assertAlmostEqual(report["accuracy"], accuracy_score(targets, predictions))
        self.assertAlmostEqual(report["balanced_accuracy"], balanced_accuracy_score(targets, predictions))
        self.assertEqual(report["per_class"][8]["precision"], 0.0)
        self.assertEqual(report["samples"], 36)

    def test_changed_dataset_is_rejected(self):
        directory = self.fixture()
        with (directory / "labels.npy").open("ab") as handle:
            handle.write(b"changed")
        with self.assertRaisesRegex(ValueError, "changed after preparation"):
            load_prepared_data(directory)

    def test_cpu_trial_saves_loadable_checkpoint_without_using_test_inputs(self):
        directory = self.fixture()
        output = self.root / "synthetic-run"
        summary = train(TrainingConfig(data=directory, output=output, epochs=1, batch_size=9, device="cpu"))
        self.assertFalse(summary["test_evaluated"])
        self.assertEqual(summary["completed_epochs"], 1)
        checkpoint = torch.load(output / "best.pt", map_location="cpu", weights_only=True)
        self.assertEqual(checkpoint["classes"], list(CLASS_NAMES))
        self.assertEqual(checkpoint["config"]["training_class_counts"], [2] * 9)
        self.assertEqual(checkpoint["validation_metrics"]["samples"], 9)
        restored = WaferCnn().eval()
        restored.load_state_dict(checkpoint["state_dict"], strict=True)
        with torch.inference_mode():
            logits = restored(encode_bins(torch.zeros((1, 64, 64), dtype=torch.uint8)))
        self.assertTrue(torch.isfinite(logits).all())
        self.assertTrue((output / "history.csv").is_file())

    @unittest.skipUnless(torch.cuda.is_available(), "CUDA not available")
    def test_cuda_amp_backward_updates_parameters(self):
        device = torch.device("cuda")
        model = WaferCnn().to(device)
        before = model.features[0].weight.detach().clone()
        optimizer = torch.optim.AdamW(model.parameters(), lr=0.001)
        criterion = torch.nn.CrossEntropyLoss(weight=torch.ones(9, device=device))
        scaler = torch.amp.GradScaler("cuda")
        loader = torch.utils.data.DataLoader(
            torch.utils.data.TensorDataset(
                torch.randint(0, 3, (9, 64, 64), dtype=torch.uint8), torch.arange(9)
            ), batch_size=9,
        )
        result = train_epoch(model, loader, optimizer, criterion, scaler, device, True, 1, 1)
        torch.cuda.synchronize()
        self.assertTrue(np.isfinite(result["loss"]))
        self.assertEqual(result["samples"], 9)
        self.assertFalse(torch.equal(before, model.features[0].weight.detach()))


if __name__ == "__main__":
    unittest.main()

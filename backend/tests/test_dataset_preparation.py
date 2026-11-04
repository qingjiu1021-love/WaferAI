"""Synthetic fixtures only: verify preprocessing and prevent evaluation leakage."""

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

import numpy as np
import pandas as pd

from app.ml.dataset_preparation import decode_label, deduplicate, grouped_split, prepare_dataset
from app.ml.preprocessing import CLASS_NAMES, prepare_map

TEST_ROOT = Path(__file__).resolve().parents[1] / ".test-work"


class DatasetPreparationTests(unittest.TestCase):
    def test_empty_label_is_distinct_from_none_class(self):
        self.assertIsNone(decode_label(np.array([])))
        self.assertEqual(decode_label(np.array([["none"]])), "none")
        with self.assertRaises(ValueError):
            decode_label(np.array([["unknown"]]))

    def test_resize_preserves_bins_aspect_and_original(self):
        original = np.array([[1, 2, 1, 1], [2, 1, 1, 2]], dtype=np.uint8)
        snapshot = original.copy()
        result = prepare_map(original)
        np.testing.assert_array_equal(original, snapshot)
        self.assertEqual(result.shape, (64, 64))
        self.assertEqual(result.dtype, np.uint8)
        self.assertEqual(set(np.unique(result)), {0, 1, 2})
        self.assertFalse(result[:16].any())
        self.assertFalse(result[48:].any())
        self.assertTrue((result[16:48] != 0).all())
        for invalid in ([], [[0]], [[True]], [[1.0]], [[-1]], [[3]]):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                prepare_map(invalid)

    def test_duplicate_and_conflicting_labels_are_excluded(self):
        records = [{"label": label, "input_sha256": digest, "status": "valid", "reason": ""}
                   for label, digest in (("none", "a"), ("none", "a"),
                                         ("Center", "b"), ("Loc", "b"), ("Donut", "c"))]
        kept = deduplicate(records)
        self.assertEqual([item["input_sha256"] for item in kept], ["a", "c"])
        self.assertEqual(records[1]["reason"], "duplicate_input")
        self.assertEqual(records[2]["reason"], "conflicting_labels_for_same_input")
        self.assertEqual(records[3]["status"], "excluded")

    def test_group_split_is_disjoint_complete_and_reproducible(self):
        labels = np.tile(np.arange(len(CLASS_NAMES)), 21)
        lots = np.repeat([f"synthetic-lot-{number}" for number in range(21)], len(CLASS_NAMES))
        first, _ = grouped_split(labels, lots)
        second, _ = grouped_split(labels, lots)
        used_lots = set()
        all_indices = []
        for name, indices in first.items():
            np.testing.assert_array_equal(indices, second[name])
            self.assertEqual(set(labels[indices]), set(range(9)))
            split_lots = set(lots[indices])
            self.assertFalse(used_lots & split_lots)
            used_lots.update(split_lots)
            all_indices.extend(indices.tolist())
        self.assertEqual(sorted(all_indices), list(range(len(labels))))

    def test_does_not_fall_back_to_splitting_one_lot_across_sets(self):
        with self.assertRaisesRegex(ValueError, "three distinct lots"):
            grouped_split(np.arange(9), np.array(["one-synthetic-lot"] * 9))

    def test_synthetic_end_to_end_keeps_source_and_records_exclusions(self):
        TEST_ROOT.mkdir(exist_ok=True)
        temporary = tempfile.TemporaryDirectory(prefix="waferai-preparation-", dir=TEST_ROOT)
        root = Path(temporary.name).resolve()
        self.assertTrue(root.is_relative_to(TEST_ROOT.resolve()))
        try:
            rows = []
            for lot in range(14):
                for class_id, label in enumerate(CLASS_NAMES):
                    code = lot * 9 + class_id
                    matrix = np.ones((64, 64), dtype=np.uint8)
                    matrix.flat[:20] = [1 + ((code >> bit) & 1) for bit in range(20)]
                    rows.append({"waferMap": matrix, "lotName": f"synthetic-{lot}",
                                 "waferIndex": class_id, "failureType": np.array([[label]]),
                                 "trianTestLabel": np.array([["Training"]])})
            rows.append(dict(rows[0]))
            conflict = np.full((64, 64), 2, dtype=np.uint8)
            rows.extend([dict(rows[0], waferMap=conflict, failureType=np.array([[label]]))
                         for label in ("Center", "Scratch")])
            rows.append(dict(rows[0], waferMap=np.zeros((2, 2), dtype=np.uint8)))
            rows.append(dict(rows[0], failureType=np.array([])))
            source = root / "synthetic-fixture.pkl"
            pd.DataFrame(rows).to_pickle(source)
            before_hash = hashlib.sha256(source.read_bytes()).hexdigest()
            destination = root / "processed"
            summary = prepare_dataset(source, destination)
            self.assertEqual(summary["kept_rows"], 126)
            self.assertEqual(summary["unlabelled_rows"], 1)
            self.assertEqual(summary["exclusion_counts"]["duplicate_input"], 1)
            self.assertEqual(summary["exclusion_counts"]["conflicting_labels_for_same_input"], 2)
            self.assertEqual(summary["exclusion_counts"]["map_has_no_dies"], 1)
            self.assertEqual(before_hash, hashlib.sha256(source.read_bytes()).hexdigest())
            maps = np.load(destination / "maps.npy", allow_pickle=False)
            labels = np.load(destination / "labels.npy", allow_pickle=False)
            self.assertEqual(maps.shape, (126, 64, 64))
            self.assertEqual(labels.shape, (126,))
            self.assertEqual(len({image.tobytes() for image in maps}), 126)
            with np.load(destination / "splits.npz", allow_pickle=False) as splits:
                self.assertEqual(sum(len(splits[name]) for name in splits.files), 126)
            report = json.loads((destination / "preparation.json").read_text(encoding="utf-8"))
            self.assertTrue(report["complete"])
            self.assertEqual(report["split"]["lot_overlap_count"], 0)
            with self.assertRaises(FileExistsError):
                prepare_dataset(source, destination)
        finally:
            self.assertTrue(root.is_relative_to(TEST_ROOT.resolve()))
            temporary.cleanup()


if __name__ == "__main__":
    unittest.main()

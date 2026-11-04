"""Synthetic fixtures verify weighted batch yield and query isolation."""

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np
from sqlalchemy import insert
from sqlalchemy.orm import Session

from app.models import Lot, Prediction, Wafer
from app.wafer_store import ImportFailure, WaferStore

TEST_ROOT = Path(__file__).resolve().parents[1] / ".test-work"


class LotTests(unittest.TestCase):
    def setUp(self):
        TEST_ROOT.mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="waferai-lots-", dir=TEST_ROOT)
        self.root = Path(self.temporary.name).resolve()
        self.assertTrue(self.root.is_relative_to(TEST_ROOT.resolve()))
        self.store = WaferStore(self.root)

    def tearDown(self):
        self.store.close()
        self.assertTrue(self.root.is_relative_to(TEST_ROOT.resolve()))
        self.temporary.cleanup()

    def add(self, code, wafer, matrix):
        content = json.dumps({"lot_code": code, "wafer_code": wafer, "map": matrix}).encode()
        return self.store.import_json(content, "synthetic-batch-test.json").wafer

    def test_weighted_yield_excludes_outside_and_does_not_average_wafer_rates(self):
        first = self.add("SYNTHETIC-A", "small", [[0, 1]])
        self.add("SYNTHETIC-A", "large", [[2, 2, 2], [2, 2, 2], [2, 2, 2]])
        with patch("app.wafer_store.np.load", side_effect=AssertionError("aggregation must not load map files")):
            summary = self.store.get_lot(first.lot_id)
        self.assertEqual((summary.wafer_count, summary.good_count, summary.bad_count, summary.total_count), (2, 1, 9, 10))
        self.assertAlmostEqual(summary.yield_rate, 0.1)
        self.assertNotAlmostEqual(summary.yield_rate, 0.5)

    def test_duplicate_import_does_not_increase_batch_totals(self):
        first = self.add("SYNTHETIC-A", "W1", [[1, 2]])
        self.add("SYNTHETIC-A", "W1", [[1, 2]])
        summary = self.store.get_lot(first.lot_id)
        self.assertEqual((summary.wafer_count, summary.total_count), (1, 2))

    def test_multiple_model_predictions_do_not_multiply_chip_counts(self):
        first = self.add("SYNTHETIC-A", "W1", [[1, 2]])
        with Session(self.store.engine) as session:
            for digest in ("a" * 64, "b" * 64):
                session.add(Prediction(wafer_id=first.id, model_version="synthetic-test", model_sha256=digest,
                                       preprocessing_id="synthetic", input_sha256="c" * 64, label="none", score=1,
                                       scores={"none": 1}, inference_ms=0, device="synthetic", created_at="2026-01-01T00:00:00+00:00"))
            session.commit()
        summary = self.store.get_lot(first.lot_id)
        self.assertEqual((summary.wafer_count, summary.good_count, summary.bad_count), (1, 1, 1))

    def test_empty_lot_has_undefined_yield_and_sources_stay_separate(self):
        first = self.add("SHARED-CODE", "W1", [[1]])
        with Session(self.store.engine) as session:
            empty = Lot(source="another_synthetic_source", lot_code="SHARED-CODE", created_at="2026-01-01T00:00:00+00:00")
            session.add(empty)
            session.commit()
            empty_id = empty.id
        summary = self.store.get_lot(empty_id)
        self.assertEqual((summary.wafer_count, summary.total_count), (0, 0))
        self.assertIsNone(summary.yield_rate)
        self.assertIsNone(summary.last_imported_at)
        self.assertEqual(len(self.store.list_lots()), 2)
        self.assertEqual(self.store.get_lot(first.lot_id).good_count, 1)

    def test_lot_filter_and_pagination_do_not_mix_batches(self):
        first = self.add("SYNTHETIC-A", "W1", [[1]])
        second = self.add("SYNTHETIC-A", "W2", [[2]])
        third = self.add("SYNTHETIC-B", "W1", [[1]])
        self.assertEqual([item.id for item in self.store.list_wafers(1, 0, first.lot_id)], [second.id])
        self.assertEqual([item.id for item in self.store.list_wafers(1, 1, first.lot_id)], [first.id])
        self.assertEqual([item.id for item in self.store.list_wafers(20, 0, third.lot_id)], [third.id])
        self.assertEqual(len(self.store.list_wafers()), 3)
        page1, page2 = self.store.list_lots(1, 0), self.store.list_lots(1, 1)
        self.assertEqual((len(page1), len(page2)), (1, 1))
        self.assertNotEqual(page1[0].id, page2[0].id)

    def test_missing_lot_returns_clear_error(self):
        for operation in (lambda: self.store.get_lot(999), lambda: self.store.list_wafers(lot_id=999)):
            with self.assertRaises(ImportFailure) as error:
                operation()
            self.assertEqual((error.exception.status_code, error.exception.code), (404, "LOT_NOT_FOUND"))

    def test_chip_totals_support_values_larger_than_32_bit(self):
        first = self.add("SYNTHETIC-LARGE-COUNTER", "W0", np.ones((512, 512), dtype=np.uint8).tolist())
        # Synthetic wafers share the same valid matrix file to keep this test small.
        with Session(self.store.engine) as session:
            prototype = session.get(Wafer, first.id)
            values = [{"lot_id": first.lot_id, "import_id": prototype.import_id, "wafer_code": f"W{index}",
                       "rows_count": 512, "cols_count": 512, "map_path": prototype.map_path,
                       "map_sha256": prototype.map_sha256, "good_count": 512 * 512, "bad_count": 0,
                       "created_at": prototype.created_at} for index in range(1, 8193)]
            session.execute(insert(Wafer), values)
            session.commit()
        summary = self.store.get_lot(first.lot_id)
        self.assertGreater(summary.good_count, 2 ** 31 - 1)
        self.assertEqual(summary.good_count, 8193 * 512 * 512)
        self.assertEqual(summary.yield_rate, 1.0)


if __name__ == "__main__":
    unittest.main()

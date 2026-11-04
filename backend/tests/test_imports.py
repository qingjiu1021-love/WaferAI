import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.models import ImportRecord, Lot, Wafer
from app.schemas import MAX_UPLOAD_BYTES
from app.wafer_store import ImportFailure, WaferStore

TEST_ROOT = Path(__file__).resolve().parents[1] / ".test-work"
SAMPLE = Path(__file__).resolve().parents[2] / "data" / "samples" / "demo-wafer-001.json"


class ImportTests(unittest.TestCase):
    def setUp(self):
        TEST_ROOT.mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="waferai-import-", dir=TEST_ROOT)
        self.root = Path(self.temporary.name).resolve()
        self.assertTrue(self.root.is_relative_to(TEST_ROOT.resolve()))
        self.store = WaferStore(self.root)

    def tearDown(self):
        self.store.close()
        self.assertTrue(self.root.is_relative_to(TEST_ROOT.resolve()))
        self.temporary.cleanup()

    def counts(self):
        with Session(self.store.engine) as session:
            return tuple(session.scalar(select(func.count()).select_from(model)) for model in (Lot, ImportRecord, Wafer))

    def test_demo_counts_and_persistence(self):
        result = self.store.import_json(SAMPLE.read_bytes(), SAMPLE.name)
        self.assertFalse(result.already_imported)
        self.assertEqual((result.wafer.good_count, result.wafer.bad_count, result.wafer.total_count), (42, 7, 49))
        self.assertAlmostEqual(result.wafer.yield_rate, 6 / 7)
        self.store.close()
        self.store = WaferStore(self.root)
        reopened = self.store.get_wafer(result.wafer.id)
        self.assertEqual(reopened.map, json.loads(SAMPLE.read_text())["map"])
        self.assertEqual(self.counts(), (1, 1, 1))

    def test_duplicate_is_idempotent(self):
        first = self.store.import_json(SAMPLE.read_bytes(), SAMPLE.name)
        second = self.store.import_json(SAMPLE.read_bytes(), SAMPLE.name)
        self.assertTrue(second.already_imported)
        self.assertEqual(first.wafer.id, second.wafer.id)
        self.assertEqual(self.counts(), (1, 1, 1))
        self.assertEqual(len(list(self.store.maps_dir.glob("*.npy"))), 1)

    def test_conflicting_number_does_not_overwrite(self):
        original = self.store.import_json(SAMPLE.read_bytes(), SAMPLE.name)
        changed = json.loads(SAMPLE.read_text())
        changed["map"][0][4] = 2
        with self.assertRaises(ImportFailure) as error:
            self.store.import_json(json.dumps(changed).encode(), SAMPLE.name)
        self.assertEqual(error.exception.status_code, 409)
        self.assertEqual(self.store.get_wafer(original.wafer.id).good_count, 42)
        self.assertEqual(self.counts(), (1, 1, 1))

    def test_invalid_maps_leave_no_records(self):
        for matrix in ([[0, 0]], [[1, 2], [1]], [[True]], [[1.0]], [[3]], [[-1]], [], [[]]):
            with self.subTest(matrix=matrix):
                content = json.dumps({"lot_code": "A", "wafer_code": "W", "map": matrix}).encode()
                with self.assertRaises(ImportFailure) as error:
                    self.store.import_json(content, "invalid.json")
                self.assertEqual(error.exception.status_code, 422)
                self.assertEqual(self.counts(), (0, 0, 0))

    def test_file_write_failure_rolls_back_metadata(self):
        with patch("app.wafer_store.np.save", side_effect=OSError("simulated disk error")):
            with self.assertRaises(OSError):
                self.store.import_json(SAMPLE.read_bytes(), SAMPLE.name)
        self.assertEqual(self.counts(), (0, 0, 0))
        self.assertEqual(list(self.store.maps_dir.iterdir()), [])

    def test_size_limit_and_invalid_json(self):
        for content, status in ((b" " * (MAX_UPLOAD_BYTES + 1), 413), (b"not json", 422)):
            with self.assertRaises(ImportFailure) as error:
                self.store.import_json(content, "invalid.json")
            self.assertEqual(error.exception.status_code, status)

    def test_rectangular_map_excludes_non_die_area(self):
        content = json.dumps({"lot_code": "A", "wafer_code": "W", "map": [[0, 1, 2], [0, 1, 1]]}).encode()
        result = self.store.import_json(content, "rectangle.json").wafer
        self.assertEqual((result.rows, result.cols, result.total_count), (2, 3, 4))
        self.assertEqual(result.yield_rate, 0.75)
        self.assertEqual(result.map, [[0, 1, 2], [0, 1, 1]])

    def test_utf8_bom_is_supported(self):
        result = self.store.import_json(b"\xef\xbb\xbf" + SAMPLE.read_bytes(), SAMPLE.name)
        self.assertEqual(result.wafer.good_count, 42)


if __name__ == "__main__":
    unittest.main()

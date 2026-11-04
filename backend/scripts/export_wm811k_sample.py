"""Export one Center-labelled WM-811K map without changing its matrix."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd

BACKEND_ROOT = Path(__file__).resolve().parents[1]
PROJECT_ROOT = BACKEND_ROOT.parent
sys.path.insert(0, str(BACKEND_ROOT))

from app.schemas import MAX_UPLOAD_BYTES, WaferImport


def scalar_text(value: object) -> str:
    values = np.asarray(value).reshape(-1)
    if values.size != 1:
        raise ValueError("Expected one source identifier.")
    item = values[0]
    if pd.isna(item):
        raise ValueError("Source identifier is missing.")
    if isinstance(item, (int, float, np.integer, np.floating)):
        number = float(item)
        if math.isfinite(number) and number.is_integer():
            return str(int(number))
    return str(item).strip()


def export_sample(source: Path, output: Path) -> None:
    if not source.is_file():
        raise FileNotFoundError(f"Dataset file not found: {source}")

    print("Loading WM-811K...", flush=True)
    frame = pd.read_pickle(source)
    required = {"waferMap", "lotName", "waferIndex", "failureType", "trianTestLabel"}
    if not required.issubset(frame.columns):
        raise ValueError(f"Missing columns: {sorted(required - set(frame.columns))}")

    # Read the original label array, keeping empty labels separate from 'none'.
    position = None
    for candidate, value in enumerate(frame["failureType"]):
        labels = np.asarray(value).reshape(-1)
        if labels.size == 1 and str(labels[0]).strip() == "Center":
            position = candidate
            break
    if position is None:
        raise ValueError("No Center-labelled wafer was found.")

    row = frame.iloc[position]
    matrix = np.asarray(row["waferMap"])
    if matrix.ndim != 2 or not np.issubdtype(matrix.dtype, np.integer):
        raise ValueError("The source map must be a two-dimensional integer matrix.")

    lot_name = scalar_text(row["lotName"])
    wafer_index = scalar_text(row["waferIndex"])
    sample = WaferImport.model_validate(
        {
            "lot_code": f"WM811K-{lot_name}",
            "wafer_code": f"W{wafer_index}",
            "map": matrix.tolist(),
        }
    )
    payload = sample.model_dump(mode="json")
    encoded = (json.dumps(payload, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    if len(encoded) > MAX_UPLOAD_BYTES:
        raise ValueError("The exported JSON exceeds the current App upload limit.")

    good = int(np.count_nonzero(matrix == 1))
    bad = int(np.count_nonzero(matrix == 2))
    outside = int(np.count_nonzero(matrix == 0))
    yield_rate = good / (good + bad)

    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        if json.loads(output.read_text(encoding="utf-8-sig")) != payload:
            raise FileExistsError(f"A different file already exists; not overwritten: {output}")
        print("The same sample already exists; keeping the JSON file.")
    else:
        with output.open("xb") as handle:
            handle.write(encoded)

    original_split = np.asarray(row["trianTestLabel"]).reshape(-1)
    metadata = {
        "dataset": "WM-811K",
        "dataset_url": "https://www.kaggle.com/datasets/qingyi/wm811k-wafer-map",
        "source_file": str(source),
        "source_file_bytes": source.stat().st_size,
        "source_row_position_zero_based": position,
        "source_dataframe_index": str(frame.index[position]),
        "source_lot_name": lot_name,
        "source_wafer_index": wafer_index,
        "source_failure_type": "Center",
        "source_trianTestLabel": [str(item) for item in original_split],
        "label_origin": "dataset_annotation_not_model_prediction",
        "selection": "first_Center_label_in_original_row_order_for_import_demo",
        "matrix_modified": False,
        "rows": int(matrix.shape[0]),
        "cols": int(matrix.shape[1]),
        "good_count": good,
        "bad_count": bad,
        "outside_count": outside,
        "yield_rate": yield_rate,
        "json_file": str(output),
        "json_sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
        "exported_at_utc": datetime.now(timezone.utc).isoformat(),
    }
    metadata_path = output.parent / "metadata" / f"{output.stem}.meta.json"
    metadata_path.parent.mkdir(parents=True, exist_ok=True)
    metadata_path.write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    print(f"Rows in dataset: {len(frame)}")
    print("Source label: Center (dataset annotation)")
    print(f"Lot code: {sample.lot_code}")
    print(f"Wafer code: {sample.wafer_code}")
    print(f"Map: {matrix.shape[0]} x {matrix.shape[1]}")
    print(f"Good: {good}; Bad: {bad}; Outside: {outside}")
    print(f"Yield: {yield_rate:.2%} (from original map)")
    print(f"Saved JSON: {output}")
    print(f"Saved source info: {metadata_path}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source", type=Path,
        default=PROJECT_ROOT / "data" / "raw" / "wm811k" / "LSWMD.pkl",
    )
    parser.add_argument(
        "--output", type=Path,
        default=PROJECT_ROOT / "data" / "samples" / "wm811k-center-001.json",
    )
    args = parser.parse_args()
    export_sample(args.source.resolve(), args.output.resolve())


if __name__ == "__main__":
    main()

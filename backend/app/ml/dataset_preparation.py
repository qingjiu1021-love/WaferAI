"""Create a traceable, deduplicated, lot-grouped WM-811K dataset."""

from __future__ import annotations

import csv
import gc
import hashlib
import itertools
import json
import platform
import uuid
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd
import sklearn
from sklearn.model_selection import StratifiedGroupKFold

from .preprocessing import CLASS_NAMES, INPUT_SIZE, PREPROCESSING_ID, prepare_map, validate_map


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(16 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def decode_label(value: object) -> str | None:
    values = np.asarray(value).reshape(-1)
    if values.size == 0:
        return None
    if values.size != 1 or str(values[0]) not in CLASS_NAMES:
        raise ValueError(f"Unexpected failureType: {value!r}")
    return str(values[0])


def source_text(value: object, allow_empty: bool = False) -> str:
    values = np.asarray(value).reshape(-1)
    if allow_empty and values.size == 0:
        return ""
    if values.size != 1 or pd.isna(values[0]) or not str(values[0]).strip():
        raise ValueError("missing_or_invalid_source_identifier")
    return str(values[0]).strip()


def deduplicate(records: list[dict]) -> list[dict]:
    """Deduplicate final inputs before any split, excluding ambiguous inputs."""
    groups = defaultdict(list)
    for record in records:
        if record["status"] == "valid":
            groups[record["input_sha256"]].append(record)
    for duplicates in groups.values():
        if len({item["label"] for item in duplicates}) > 1:
            for item in duplicates:
                item.update(status="excluded", reason="conflicting_labels_for_same_input")
        else:
            for item in duplicates[1:]:
                item.update(status="excluded", reason="duplicate_input")
    return [item for item in records if item["status"] == "valid"]


def grouped_split(labels: np.ndarray, lots: np.ndarray, seed: int = 42) -> tuple[dict, dict]:
    """About 5/7 train, 1/7 validation, 1/7 test, using only groups and labels."""
    classes = len(CLASS_NAMES)
    total = np.bincount(labels, minlength=classes)
    class_lots = {name: len(set(lots[labels == index])) for index, name in enumerate(CLASS_NAMES)}
    if np.any(total == 0) or min(class_lots.values()) < 3:
        raise ValueError(f"Each class needs at least three distinct lots after cleaning: {class_lots}")
    if len(set(lots)) < 7:
        raise ValueError("At least seven distinct lots are required.")

    splitter = StratifiedGroupKFold(n_splits=7, shuffle=True, random_state=seed)
    folds = [held_out for _, held_out in splitter.split(np.zeros(len(labels)), labels, groups=lots)]
    counts = [np.bincount(labels[indices], minlength=classes) for indices in folds]
    candidates = []
    for validation_fold, test_fold in itertools.combinations(range(7), 2):
        validation_count, test_count = counts[validation_fold], counts[test_fold]
        train_count = total - validation_count - test_count
        if min(train_count.min(), validation_count.min(), test_count.min()) == 0:
            continue
        # Balance uses class counts, never model predictions or evaluation scores.
        score = float(np.mean(np.abs(validation_count / total - 1 / 7))
                      + np.mean(np.abs(test_count / total - 1 / 7))
                      + abs(validation_count.sum() / len(labels) - 1 / 7)
                      + abs(test_count.sum() / len(labels) - 1 / 7))
        candidates.append((score, validation_fold, test_fold))
    if not candidates:
        raise ValueError("Cannot include all nine classes in all three lot-disjoint splits. Review the audit.")

    _, validation_fold, test_fold = min(candidates)
    validation, test = folds[validation_fold], folds[test_fold]
    train = np.flatnonzero(~np.isin(np.arange(len(labels)), np.concatenate((validation, test))))
    splits = {"train": train, "validation": validation, "test": test}
    group_sets = [set(lots[indices]) for indices in splits.values()]
    if any(left & right for left, right in itertools.combinations(group_sets, 2)):
        raise RuntimeError("Lot overlap detected.")
    combined = np.concatenate(list(splits.values()))
    if len(combined) != len(labels) or not np.array_equal(np.sort(combined), np.arange(len(labels))):
        raise RuntimeError("Split coverage is incomplete or contains duplicates.")
    return splits, {"method": "StratifiedGroupKFold_7_folds", "seed": seed,
                    "validation_fold": validation_fold, "test_fold": test_fold,
                    "class_lot_counts": class_lots, "lot_overlap_count": 0}


def prepare_dataset(source: Path, destination: Path, seed: int = 42) -> dict:
    source, destination = source.resolve(), destination.resolve()
    if not source.is_file():
        raise FileNotFoundError(source)
    if destination.exists():
        raise FileExistsError(f"Keeping existing dataset unchanged: {destination}. Use --output for a new version.")
    print("[1/5] Computing source SHA-256...", flush=True)
    source_hash = file_sha256(source)
    print("[2/5] Reading WM-811K...", flush=True)
    raw = pd.read_pickle(source)
    required = {"waferMap", "lotName", "waferIndex", "failureType", "trianTestLabel"}
    if not required.issubset(raw.columns):
        raise ValueError(f"Missing columns: {sorted(required - set(raw.columns))}")
    raw_count = len(raw)
    decoded = [decode_label(value) for value in raw["failureType"]]
    original_counts = Counter(label for label in decoded if label is not None)
    positions = [index for index, label in enumerate(decoded) if label is not None]
    frame = raw.iloc[positions].copy()
    frame["_source_position"] = positions
    frame["_label"] = [decoded[index] for index in positions]
    del decoded, positions, raw
    gc.collect()

    print(f"[3/5] Auditing {len(frame)} labelled maps...", flush=True)
    records = []
    columns = ["waferMap", "lotName", "waferIndex", "trianTestLabel", "_source_position", "_label"]
    for candidate_index, values in enumerate(frame[columns].itertuples(index=True, name=None)):
        source_index, wafer_map, lot, wafer_index, original_split, source_position, label = values
        record = {"candidate_index": candidate_index, "source_position": source_position,
                  "source_dataframe_index": str(source_index), "label": label,
                  "label_id": CLASS_NAMES.index(label), "lot_name": "", "wafer_index": "",
                  "original_trianTestLabel": "", "rows": "", "cols": "",
                  "raw_map_sha256": "", "input_sha256": "", "status": "valid", "reason": "",
                  "processed_index": "", "split": ""}
        try:
            record["lot_name"] = source_text(lot)
            record["wafer_index"] = source_text(wafer_index)
            record["original_trianTestLabel"] = source_text(original_split, allow_empty=True)
            matrix = validate_map(wafer_map)
            model_input = prepare_map(matrix)
            record["rows"], record["cols"] = matrix.shape
            record["raw_map_sha256"] = hashlib.sha256(
                np.asarray(matrix.shape, dtype="<i8").tobytes() + matrix.tobytes()
            ).hexdigest()
            record["input_sha256"] = hashlib.sha256(model_input.tobytes()).hexdigest()
        except (ValueError, TypeError) as error:
            record.update(status="excluded", reason=str(error))
        records.append(record)
        if (candidate_index + 1) % 25000 == 0:
            print(f"  Audited {candidate_index + 1}/{len(frame)}", flush=True)

    kept = deduplicate(records)
    print(f"[4/5] Splitting {len(kept)} unique inputs by lot...", flush=True)
    labels = np.asarray([item["label_id"] for item in kept], dtype=np.int64)
    lots = np.asarray([item["lot_name"] for item in kept])
    splits, split_info = grouped_split(labels, lots, seed)
    for index, record in enumerate(kept):
        record["processed_index"] = index
    for split_name, indices in splits.items():
        for index in indices:
            kept[index]["split"] = split_name
    if len({item["input_sha256"] for item in kept}) != len(kept):
        raise RuntimeError("Duplicate inputs remain after cleaning.")

    destination.parent.mkdir(parents=True, exist_ok=True)
    staging = destination.parent / f".{destination.name}.partial-{uuid.uuid4().hex[:12]}"
    staging.mkdir()
    print("[5/5] Writing uint8 maps and frozen split indices...", flush=True)
    maps = np.lib.format.open_memmap(staging / "maps.npy", mode="w+", dtype=np.uint8,
                                     shape=(len(kept), INPUT_SIZE, INPUT_SIZE))
    for index, record in enumerate(kept):
        maps[index] = prepare_map(frame.iloc[record["candidate_index"]]["waferMap"])
        if (index + 1) % 25000 == 0:
            print(f"  Saved {index + 1}/{len(kept)}", flush=True)
    maps.flush()
    del maps, frame
    gc.collect()
    np.save(staging / "labels.npy", labels, allow_pickle=False)
    np.savez(staging / "splits.npz", **splits)
    with (staging / "audit.csv").open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(records[0]))
        writer.writeheader()
        writer.writerows(records)
    with (staging / "manifest.csv").open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(kept[0]))
        writer.writeheader()
        writer.writerows(kept)

    split_summary = {}
    for name, indices in splits.items():
        split_summary[name] = {
            "samples": len(indices), "lots": len(set(lots[indices])),
            "class_counts": {label: int(np.count_nonzero(labels[indices] == number))
                             for number, label in enumerate(CLASS_NAMES)},
        }
    summary = {
        "format_version": 1, "complete": True,
        "created_at_utc": datetime.now(timezone.utc).isoformat(),
        "source_file": str(source), "source_sha256": source_hash,
        "source_bytes": source.stat().st_size, "source_rows": raw_count,
        "dataset_url": "https://www.kaggle.com/datasets/qingyi/wm811k-wafer-map",
        "mirror_license_displayed_at_download": "CC0: Public Domain",
        "license_note": "Kaggle mirror metadata; original distribution rights not independently verified.",
        "classes": list(CLASS_NAMES), "source_label_counts": dict(original_counts),
        "unlabelled_rows": raw_count - len(records), "labelled_rows": len(records),
        "kept_rows": len(kept), "excluded_rows": len(records) - len(kept),
        "exclusion_counts": dict(Counter(item["reason"] for item in records if item["status"] == "excluded")),
        "preprocessing": {"id": PREPROCESSING_ID, "size": INPUT_SIZE,
                          "stored_dtype": "uint8", "bin_values": [0, 1, 2],
                          "training_encoding": "one_hot_3_channels"},
        "deduplication": "unique final 64x64 inputs; keep first consistent label, exclude all label conflicts",
        "split": split_info, "split_summary": split_summary, "input_overlap_count": 0,
        "original_split_policy": "Original trianTestLabel preserved in manifest; new lot-grouped split used.",
        "augmentation_applied": False,
        "evaluation_note": "Cleaned subset with a new grouped split; not directly comparable to uncleaned benchmark scores.",
        "versions": {"python": platform.python_version(), "numpy": np.__version__,
                     "pandas": pd.__version__, "scikit_learn": sklearn.__version__},
    }
    summary["artifact_sha256"] = {name: file_sha256(staging / name) for name in
                                  ("maps.npy", "labels.npy", "splits.npz", "manifest.csv", "audit.csv")}
    (staging / "preparation.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    staging.rename(destination)
    print("DONE", flush=True)
    print(f"Source rows: {raw_count}; Labelled: {len(records)}; Unlabelled: {raw_count - len(records)}")
    print(f"Kept: {len(kept)}; Excluded: {len(records) - len(kept)}")
    for name, info in split_summary.items():
        print(f"{name}: {info['samples']} samples, {info['lots']} lots, 9 classes")
    print("Lot overlap: 0; Input overlap: 0")
    print(f"Saved: {destination}")
    return summary

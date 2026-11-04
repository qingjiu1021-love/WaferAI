"""Package a small, lot-disjoint WM-811K subset for supervised training on Android."""
import csv
import gzip
import hashlib
import json
import struct
from pathlib import Path

import numpy as np


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "data" / "processed" / "wm811k-v1"
DEST = ROOT / "android" / "app" / "src" / "main" / "assets" / "models"
CLASSES = ["none", "Center", "Donut", "Edge-Loc", "Edge-Ring", "Loc", "Random", "Scratch", "Near-full"]


def main():
    with (SOURCE / "preparation.json").open(encoding="utf-8") as file:
        preparation = json.load(file)
    assert preparation["complete"] and preparation["classes"] == CLASSES
    maps = np.load(SOURCE / "maps.npy", mmap_mode="r", allow_pickle=False)
    labels = np.load(SOURCE / "labels.npy", allow_pickle=False)
    rng = np.random.default_rng(3301)
    selected = {}
    with np.load(SOURCE / "splits.npz", allow_pickle=False) as splits:
        for split, cap in (("train", 40), ("validation", 12)):
            indices = splits[split]
            selected[split] = np.concatenate([
                rng.permutation(indices[labels[indices] == label])[:cap]
                for label in range(len(CLASSES))
            ])

    chosen = {int(index) for indices in selected.values() for index in indices}
    lots = {}
    hashes = {}
    with (SOURCE / "manifest.csv").open(encoding="utf-8", newline="") as file:
        for record in csv.DictReader(file):
            index = int(record["processed_index"])
            if index in chosen:
                lots[index] = record["lot_name"]
                hashes[index] = record["input_sha256"]
    train = selected["train"]
    validation = selected["validation"]
    assert not ({lots[int(i)] for i in train} & {lots[int(i)] for i in validation})
    assert not ({hashes[int(i)] for i in train} & {hashes[int(i)] for i in validation})

    buffer = bytearray(struct.pack(">4sHH", b"WAT1", len(train), len(validation)))
    for index in (*train, *validation):
        sample = maps[index].astype(np.uint8, copy=False).tobytes()
        assert hashlib.sha256(sample).hexdigest() == hashes[int(index)]
        buffer.extend(bytes([labels[index]]))
        buffer.extend(sample)
    # AAPT treats a .gz suffix specially. Use a .bin asset with an explicit gzip reader.
    (DEST / "phone-training-wm811k.bin").write_bytes(gzip.compress(buffer, mtime=0))
    metadata = {
        "dataset_url": preparation["dataset_url"],
        "mirror_license": preparation["mirror_license_displayed_at_download"],
        "reference": "https://doi.org/10.1109/TSM.2014.2364237",
        "dataset_source_sha256": preparation["source_sha256"],
        "subset_sha256": hashlib.sha256(buffer).hexdigest(),
        "train_samples": len(train),
        "validation_samples": len(validation),
        "validation_lots": sorted({lots[int(i)] for i in validation}),
        "test_set_used": False,
        "sampling_seed": 3301,
    }
    (DEST / "phone-training-wm811k.json").write_text(
        json.dumps(metadata, indent=2) + "\n", encoding="utf-8"
    )
    print(f"Packaged {len(train)} train and {len(validation)} validation maps.")


if __name__ == "__main__":
    main()

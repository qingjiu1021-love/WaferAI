"""Prepare a cleaned WM-811K dataset; this command does not train a model."""

import argparse
import sys
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND_ROOT))

from app.ml.dataset_preparation import prepare_dataset


def main():
    project = BACKEND_ROOT.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=project / "data/raw/wm811k/LSWMD.pkl")
    parser.add_argument("--output", type=Path, default=project / "data/processed/wm811k-v1")
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()
    prepare_dataset(args.source, args.output, args.seed)


if __name__ == "__main__":
    main()

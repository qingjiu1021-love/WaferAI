"""Evaluate one fixed CNN checkpoint on the held-out test set."""

import argparse
import sys
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND_ROOT))

from app.ml.evaluation import evaluate_checkpoint


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--data", type=Path, default=BACKEND_ROOT.parent / "data/processed/wm811k-v1")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--device", choices=("cuda", "cpu"), default="cuda")
    parser.add_argument("--batch-size", type=int, default=128)
    args = parser.parse_args()
    evaluate_checkpoint(args.checkpoint, args.data, args.output, args.device, args.batch_size)


if __name__ == "__main__":
    main()

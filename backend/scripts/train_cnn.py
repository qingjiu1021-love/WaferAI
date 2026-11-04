"""Train WaferCnnV1 with fixed train/validation splits; reserve the test set."""

import argparse
import sys
from datetime import datetime
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND_ROOT))

from app.ml.training import TrainingConfig, train


def main():
    project = BACKEND_ROOT.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, default=project / "data/processed/wm811k-v1")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--epochs", type=int, default=15)
    parser.add_argument("--batch-size", type=int, default=128)
    parser.add_argument("--learning-rate", type=float, default=0.001)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--patience", type=int, default=4)
    parser.add_argument("--device", choices=("cuda", "cpu"), default="cuda")
    args = parser.parse_args()
    prefix = "trial" if args.epochs == 1 else "cnn"
    output = args.output or project / "artifacts" / "cnn-runs" / f"{prefix}-{datetime.now():%Y%m%d-%H%M%S}"
    train(TrainingConfig(data=args.data.resolve(), output=output.resolve(), epochs=args.epochs,
                         batch_size=args.batch_size, learning_rate=args.learning_rate,
                         seed=args.seed, patience=args.patience, device=args.device))


if __name__ == "__main__":
    main()

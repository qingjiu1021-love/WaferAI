"""Copy the evaluated checkpoint to a fixed local serving directory."""

import argparse
import hashlib
import json
import shutil
import uuid
from datetime import datetime, timezone
from pathlib import Path


def prepare(run: Path, output: Path, version: str = "cnn-v1"):
    run, output = run.resolve(), output.resolve()
    evaluation = json.loads((run / "test-evaluation/test-metrics.json").read_text(encoding="utf-8"))
    checkpoint = run / "best.pt"
    model_hash = hashlib.sha256(checkpoint.read_bytes()).hexdigest()
    if (not evaluation.get("test_evaluated") or evaluation.get("evaluation_scope") != "held_out_test"
            or evaluation["fingerprint"]["checkpoint_sha256"] != model_hash):
        raise ValueError("The checkpoint must match the preserved independent test result.")
    if output.exists():
        previous = json.loads((output / "model-info.json").read_text(encoding="utf-8"))
        if (previous["model_sha256"] == model_hash and previous["model_version"] == version
                and hashlib.sha256((output / "best.pt").read_bytes()).hexdigest() == model_hash):
            print(f"Serving model is already prepared: {output}")
            return
        raise FileExistsError(f"Keeping existing serving model unchanged: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    staging = output.parent / f".{output.name}.partial-{uuid.uuid4().hex[:12]}"
    staging.mkdir()
    shutil.copy2(checkpoint, staging / "best.pt")
    if hashlib.sha256((staging / "best.pt").read_bytes()).hexdigest() != model_hash:
        raise RuntimeError("Checkpoint copy failed verification.")
    metadata = {"model_version": version, "model_sha256": model_hash,
                "model_name": evaluation["model_name"], "classes": evaluation["classes"],
                "preprocessing_id": evaluation["preprocessing_id"],
                "source_run": str(run), "checkpoint_epoch": evaluation["checkpoint_epoch"],
                "test_metrics": evaluation["metrics"], "test_fingerprint": evaluation["fingerprint"],
                "test_device": evaluation["device"], "test_mixed_precision": evaluation["mixed_precision"],
                "serving_device": "cpu", "serving_precision": "float32",
                "prepared_at_utc": datetime.now(timezone.utc).isoformat()}
    (staging / "model-info.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    staging.rename(output)
    print(f"Serving model ready: {output}")
    print(f"Model SHA-256: {model_hash}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[2] / "artifacts/serving/cnn-v1")
    parser.add_argument("--version", default="cnn-v1")
    args = parser.parse_args()
    prepare(args.run, args.output, args.version)

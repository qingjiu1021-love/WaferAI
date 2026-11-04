"""Export the frozen CNN without retraining; verify conversion parity, not accuracy."""
from pathlib import Path
import hashlib
import json
import sys
import numpy as np
import torch
import onnx
import onnxruntime as ort

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))
from app.ml.model import WaferCnn, encode_bins
from app.ml.preprocessing import prepare_map, CLASS_NAMES, PREPROCESSING_ID

ROOT = BACKEND.parent
SOURCE = ROOT / "artifacts/serving/cnn-v1"
DEST = ROOT / "android/app/src/main/assets/models"
FIXTURES = ROOT / "android/app/src/androidTest/assets"

def main():
    torch.set_num_threads(2)
    info = json.loads((SOURCE / "model-info.json").read_text(encoding="utf-8"))
    checkpoint_path = SOURCE / "best.pt"
    assert hashlib.sha256(checkpoint_path.read_bytes()).hexdigest() == info["model_sha256"]
    checkpoint = torch.load(checkpoint_path, map_location="cpu", weights_only=True)
    assert checkpoint["classes"] == list(CLASS_NAMES) and checkpoint["preprocessing_id"] == PREPROCESSING_ID
    model = WaferCnn().eval()
    model.load_state_dict(checkpoint["state_dict"], strict=True)
    DEST.mkdir(parents=True, exist_ok=True)
    FIXTURES.mkdir(parents=True, exist_ok=True)
    target = DEST / "wafer-cnn-v1.onnx"
    torch.onnx.export(model, torch.zeros(1, 3, 64, 64), target,
                      input_names=["bins_one_hot"], output_names=["logits"],
                      opset_version=17, dynamo=False)
    onnx.checker.check_model(onnx.load(target))
    session = ort.InferenceSession(str(target), providers=["CPUExecutionProvider"])
    maps = [json.loads((ROOT / "data/samples/wm811k-center-001.json").read_text(encoding="utf-8"))["map"]]
    rng = np.random.default_rng(42)
    for height, width in [(1,1),(1,512),(512,1),(3,128),(65,64),(64,65),(53,58),(127,129),(30,100),(7,19)]:
        matrix = rng.integers(0, 3, size=(height,width), dtype=np.uint8)
        matrix[height//2,width//2] = 1
        maps.append(matrix.tolist())
    fixtures, maximum = [], 0.0
    for matrix in maps:
        prepared = prepare_map(matrix)
        tensor = encode_bins(torch.from_numpy(prepared.copy()).unsqueeze(0))
        with torch.inference_mode():
            expected = torch.softmax(model(tensor), dim=1).numpy()[0]
        logits = session.run(None, {"bins_one_hot": tensor.numpy()})[0][0]
        actual = np.exp(logits - logits.max()); actual /= actual.sum()
        error = float(np.max(np.abs(actual - expected)))
        assert error < 3e-5 and expected.argmax() == actual.argmax()
        maximum = max(maximum, error)
        fixtures.append({"map": matrix, "prepared_sha256": hashlib.sha256(prepared.tobytes()).hexdigest(),
                         "scores": expected.tolist()})
    mobile_info = {"model_version":"cnn-v1-mobile", "source_checkpoint_sha256": info["model_sha256"],
                   "model_sha256":hashlib.sha256(target.read_bytes()).hexdigest(), "classes": list(CLASS_NAMES),
                   "preprocessing_id":PREPROCESSING_ID, "opset":17, "runtime":"onnxruntime-1.23.2",
                   "conversion_checks":len(fixtures), "maximum_probability_error":maximum,
                   "original_test_metrics":info["test_metrics"]}
    (DEST / "model-info.json").write_text(json.dumps(mobile_info, ensure_ascii=False, indent=2), encoding="utf-8")
    (FIXTURES / "mobile-parity.json").write_text(json.dumps(fixtures), encoding="utf-8")
    report = ROOT / "artifacts/mobile-conversion.json"
    report.write_text(json.dumps(mobile_info, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Exported {target.stat().st_size} bytes; {len(fixtures)} conversion checks; max error {maximum:.3g}")

if __name__ == "__main__":
    main()

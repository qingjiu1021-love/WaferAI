"""Evaluate a fixed checkpoint on the held-out test indices and preserve the result."""

from __future__ import annotations

import csv
import json
import platform
import time
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import torch
from torch.utils.data import DataLoader

from .model import MODEL_NAME, WaferCnn
from .preprocessing import CLASS_NAMES, PREPROCESSING_ID
from .training import MapDataset, load_prepared_data, sha256_file, validate, write_json


def print_result(result: dict, output: Path, cached: bool = False) -> None:
    print("CACHED TEST RESULT" if cached else "TEST EVALUATION DONE", flush=True)
    print(f"Checkpoint epoch: {result['checkpoint_epoch']}; Test samples: {result['metrics']['samples']}")
    metrics = result["metrics"]
    print(f"Test accuracy: {metrics['accuracy']:.4f}")
    print(f"Test macro-F1: {metrics['macro_f1']:.4f}")
    print(f"Test balanced accuracy: {metrics['balanced_accuracy']:.4f}")
    print(f"{'Class':<12} {'Precision':>9} {'Recall':>8} {'F1':>8} {'Support':>8}")
    for item in metrics["per_class"]:
        print(f"{item['class']:<12} {item['precision']:>9.4f} {item['recall']:>8.4f} "
              f"{item['f1']:>8.4f} {item['support']:>8}")
    print(f"Saved: {output}")


def write_tables(output: Path, result: dict) -> None:
    with (output / "test-confusion.csv").open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["true_label / predicted_label", *CLASS_NAMES])
        for label, row in zip(CLASS_NAMES, result["confusion_matrix"]):
            writer.writerow([label, *row])
    with (output / "test-per-class.csv").open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=["class", "precision", "recall", "f1", "support"])
        writer.writeheader()
        writer.writerows(result["metrics"]["per_class"])
    metrics = result["metrics"]
    lines = [
        "# WaferAI CNN 独立测试结果", "",
        f"模型：{result['model_name']}；模型轮次：{result['checkpoint_epoch']}。",
        "模型依据验证集 macro-F1 选定，本报告只记录冻结模型在预留测试集上的表现。", "",
        f"- 测试样本数：{metrics['samples']}",
        f"- Accuracy：{metrics['accuracy']:.2%}",
        f"- Macro-F1：{metrics['macro_f1']:.4f}",
        f"- Balanced accuracy：{metrics['balanced_accuracy']:.4f}", "",
        "| 类别 | Precision | Recall | F1 | 样本数 |",
        "|---|---:|---:|---:|---:|",
    ]
    lines.extend(f"| {item['class']} | {item['precision']:.4f} | {item['recall']:.4f} | "
                 f"{item['f1']:.4f} | {item['support']} |" for item in metrics["per_class"])
    lines.extend([
        "", "测试集来自清洗后的 WM-811K 子集，按批次留出；不代表新产线实测数据上的效果。",
        "类别数量不均衡，应结合各类指标和混淆矩阵解释总体结果。芯片良率独立由原始矩阵计算。", "",
        f"评估时间（UTC）：{result['evaluated_at_utc']}",
        f"模型 SHA-256：`{result['fingerprint']['checkpoint_sha256']}`",
        f"数据准备报告 SHA-256：`{result['fingerprint']['preparation_sha256']}`",
        f"划分 SHA-256：`{result['fingerprint']['split_sha256']}`", "",
    ])
    (output / "test-report.md").write_text("\n".join(lines), encoding="utf-8")


def evaluate_checkpoint(checkpoint_path: Path, data: Path, output: Path | None = None,
                        device_name: str = "cuda", batch_size: int = 128) -> dict:
    checkpoint_path, data = checkpoint_path.resolve(), data.resolve()
    output = (output or checkpoint_path.parent / "test-evaluation").resolve()
    if batch_size < 1 or device_name not in ("cuda", "cpu"):
        raise ValueError("Use a positive batch size and device cuda or cpu.")
    maps, labels, splits, preparation = load_prepared_data(data)
    fingerprint = {"checkpoint_sha256": sha256_file(checkpoint_path),
                   "preparation_sha256": sha256_file(data / "preparation.json"),
                   "split_sha256": preparation["artifact_sha256"]["splits.npz"]}
    result_path = output / "test-metrics.json"
    if result_path.exists():
        previous = json.loads(result_path.read_text(encoding="utf-8"))
        if previous.get("fingerprint") != fingerprint or previous.get("evaluation_scope") != "held_out_test":
            raise FileExistsError("Existing test results belong to another model or dataset; they will not be overwritten.")
        print_result(previous, output, cached=True)
        return previous

    checkpoint = torch.load(checkpoint_path, map_location="cpu", weights_only=True)
    if (checkpoint.get("format_version") != 1 or checkpoint.get("model_name") != MODEL_NAME
            or checkpoint.get("classes") != list(CLASS_NAMES)
            or checkpoint.get("preprocessing_id") != PREPROCESSING_ID):
        raise ValueError("Checkpoint architecture, classes or preprocessing is incompatible.")
    settings = checkpoint.get("config", {})
    if (settings.get("preparation_sha256") != fingerprint["preparation_sha256"]
            or settings.get("split_sha256") != fingerprint["split_sha256"]):
        raise ValueError("Test dataset does not match the frozen training dataset.")
    if device_name == "cuda" and not torch.cuda.is_available():
        raise RuntimeError("CUDA is unavailable. Check the PyTorch GPU test.")
    device = torch.device(device_name)
    torch.set_num_threads(4)
    if device.type == "cuda":
        torch.backends.cudnn.benchmark = False
        torch.backends.cudnn.deterministic = True
    amp = device.type == "cuda" and bool(settings.get("mixed_precision", False))
    model = WaferCnn()
    model.load_state_dict(checkpoint["state_dict"], strict=True)
    model.to(device).eval()

    output.mkdir(parents=True, exist_ok=True)
    selection_path = output / "selection.json"
    if selection_path.exists():
        previous_selection = json.loads(selection_path.read_text(encoding="utf-8"))
        if previous_selection.get("fingerprint") != fingerprint:
            raise FileExistsError("Another checkpoint is already selected in this output directory.")
    else:
        selection = {"fingerprint": fingerprint, "checkpoint": str(checkpoint_path),
                     "checkpoint_epoch": int(checkpoint["epoch"]),
                     "selection_basis": "validation_macro_f1",
                     "selected_at_utc": datetime.now(timezone.utc).isoformat()}
        with selection_path.open("x", encoding="utf-8") as handle:
            json.dump(selection, handle, ensure_ascii=False, indent=2)
    loader = DataLoader(MapDataset(maps, labels, splits["test"]), batch_size=batch_size,
                        shuffle=False, num_workers=0, pin_memory=device.type == "cuda")
    print(f"Evaluating fixed checkpoint epoch {checkpoint['epoch']} on {len(loader.dataset)} held-out test samples...", flush=True)
    started = time.perf_counter()
    metrics, confusion = validate(model, loader, device, amp)
    if metrics["samples"] != len(splits["test"]):
        raise RuntimeError("Not all held-out test samples were evaluated.")
    if sha256_file(checkpoint_path) != fingerprint["checkpoint_sha256"]:
        raise RuntimeError("Checkpoint changed during evaluation.")
    result = {"format_version": 1, "evaluation_scope": "held_out_test", "test_evaluated": True,
              "fingerprint": fingerprint, "checkpoint": str(checkpoint_path),
              "checkpoint_epoch": int(checkpoint["epoch"]), "model_name": MODEL_NAME,
              "classes": list(CLASS_NAMES), "preprocessing_id": PREPROCESSING_ID,
              "selection_basis": "validation_macro_f1", "metrics": metrics,
              "confusion_matrix": confusion.tolist(),
              "device": str(device), "gpu": torch.cuda.get_device_name(0) if device.type == "cuda" else None,
              "mixed_precision": amp, "batch_size": batch_size,
              "versions": {"python": platform.python_version(), "torch": str(torch.__version__), "numpy": np.__version__},
              "evaluated_at_utc": datetime.now(timezone.utc).isoformat(),
              "elapsed_seconds": time.perf_counter() - started}
    write_tables(output, result)
    temporary_path = output / "test-metrics.tmp.json"
    write_json(temporary_path, result)
    temporary_path.replace(result_path)
    print_result(result, output)
    return result

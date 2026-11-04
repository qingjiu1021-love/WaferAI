"""Train only on train indices and select checkpoints using validation macro-F1."""

from __future__ import annotations

import csv
import hashlib
import json
import platform
import random
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F
from torch.utils.data import DataLoader, Dataset

from .model import MODEL_NAME, WaferCnn, encode_bins
from .preprocessing import CLASS_NAMES, INPUT_SIZE, PREPROCESSING_ID


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(16 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")


class MapDataset(Dataset):
    def __init__(self, maps: np.ndarray, labels: np.ndarray, indices: np.ndarray):
        self.maps, self.labels, self.indices = maps, labels, indices

    def __len__(self):
        return len(self.indices)

    def __getitem__(self, item):
        index = int(self.indices[item])
        # A small copy avoids returning a writable tensor backed by a read-only mmap.
        return torch.from_numpy(np.array(self.maps[index], copy=True)), int(self.labels[index])


def load_prepared_data(directory: Path):
    report = json.loads((directory / "preparation.json").read_text(encoding="utf-8"))
    if (not report.get("complete") or report.get("format_version") != 1
            or report.get("classes") != list(CLASS_NAMES)
            or report.get("preprocessing", {}).get("id") != PREPROCESSING_ID
            or report.get("split", {}).get("lot_overlap_count") != 0
            or report.get("input_overlap_count") != 0):
        raise ValueError("Dataset preparation metadata is incomplete or incompatible.")
    print("Verifying frozen dataset hashes...", flush=True)
    for name in ("maps.npy", "labels.npy", "splits.npz"):
        if sha256_file(directory / name) != report["artifact_sha256"][name]:
            raise ValueError(f"Dataset file changed after preparation: {name}")
    maps = np.load(directory / "maps.npy", mmap_mode="r", allow_pickle=False)
    labels = np.load(directory / "labels.npy", allow_pickle=False)
    with np.load(directory / "splits.npz", allow_pickle=False) as saved:
        splits = {name: saved[name].copy() for name in ("train", "validation", "test")}
    if maps.dtype != np.uint8 or maps.shape != (len(labels), INPUT_SIZE, INPUT_SIZE):
        raise ValueError("Unexpected map shape or dtype.")
    if labels.dtype != np.int64 or labels.ndim != 1 or np.any((labels < 0) | (labels >= len(CLASS_NAMES))):
        raise ValueError("Unexpected class labels.")
    indices = np.concatenate(list(splits.values()))
    if any(value.ndim != 1 or value.dtype.kind not in "iu" or len(value) == 0 for value in splits.values()):
        raise ValueError("Split indices must be nonempty integer arrays.")
    if len(indices) != len(labels) or not np.array_equal(np.sort(indices), np.arange(len(labels))):
        raise ValueError("Split indices overlap, miss samples, or are out of bounds.")
    for name in ("train", "validation"):
        if len(np.unique(labels[splits[name]])) != len(CLASS_NAMES):
            raise ValueError(f"All nine classes must be present in {name}.")
    return maps, labels, splits, report


def class_weights(training_labels: np.ndarray) -> torch.Tensor:
    counts = np.bincount(training_labels, minlength=len(CLASS_NAMES))
    if np.any(counts == 0):
        raise ValueError("Training data must contain all nine classes.")
    weights = np.sqrt(counts.sum() / (len(CLASS_NAMES) * counts.astype(np.float64)))
    return torch.tensor(weights / weights.mean(), dtype=torch.float32)


def confusion_metrics(confusion: np.ndarray) -> dict:
    support = confusion.sum(axis=1)
    predicted = confusion.sum(axis=0)
    correct = confusion.diagonal()
    precision = np.divide(correct, predicted, out=np.zeros(len(CLASS_NAMES)), where=predicted != 0)
    recall = np.divide(correct, support, out=np.zeros(len(CLASS_NAMES)), where=support != 0)
    f1 = np.divide(2 * precision * recall, precision + recall,
                   out=np.zeros(len(CLASS_NAMES)), where=(precision + recall) != 0)
    return {"samples": int(confusion.sum()),
            "accuracy": float(correct.sum() / max(int(confusion.sum()), 1)),
            "macro_f1": float(f1.mean()), "balanced_accuracy": float(recall.mean()),
            "per_class": [{"class": name, "precision": float(precision[index]),
                           "recall": float(recall[index]), "f1": float(f1[index]),
                           "support": int(support[index])} for index, name in enumerate(CLASS_NAMES)]}


def train_epoch(model, loader, optimizer, criterion, scaler, device, amp, epoch, epochs):
    model.train()
    weighted_sum, weight_sum, correct, seen = 0.0, 0.0, 0, 0
    print(f"Epoch {epoch}/{epochs}: training {len(loader.dataset)} samples, {len(loader)} batches", flush=True)
    for batch_index, (maps, targets) in enumerate(loader, start=1):
        targets = targets.to(device, non_blocking=True)
        inputs = encode_bins(maps.to(device, non_blocking=True))
        optimizer.zero_grad(set_to_none=True)
        with torch.autocast(device_type=device.type, enabled=amp):
            logits = model(inputs)
            loss = criterion(logits, targets)
        if not torch.isfinite(loss).item():
            raise RuntimeError("Non-finite training loss; no checkpoint will be saved for this epoch.")
        scaler.scale(loss).backward()
        scaler.unscale_(optimizer)
        nn.utils.clip_grad_norm_(model.parameters(), max_norm=1.0)
        scaler.step(optimizer)
        scaler.update()
        batch_weight = float(criterion.weight[targets].sum().item())
        weighted_sum += loss.item() * batch_weight
        weight_sum += batch_weight
        correct += int((logits.argmax(dim=1) == targets).sum().item())
        seen += len(targets)
        if batch_index % 100 == 0 or batch_index == len(loader):
            print(f"  Train {batch_index}/{len(loader)}; loss={weighted_sum / weight_sum:.4f}", flush=True)
    return {"loss": weighted_sum / weight_sum, "accuracy": correct / seen, "samples": seen}


@torch.inference_mode()
def validate(model, loader, device, amp):
    model.eval()
    confusion = np.zeros((len(CLASS_NAMES), len(CLASS_NAMES)), dtype=np.int64)
    loss_sum = 0.0
    for maps, targets in loader:
        targets = targets.to(device, non_blocking=True)
        inputs = encode_bins(maps.to(device, non_blocking=True))
        with torch.autocast(device_type=device.type, enabled=amp):
            logits = model(inputs)
            loss = F.cross_entropy(logits, targets, reduction="sum")
        loss_sum += float(loss.item())
        cells = targets * len(CLASS_NAMES) + logits.argmax(dim=1)
        confusion += torch.bincount(cells, minlength=len(CLASS_NAMES) ** 2).reshape(confusion.shape).cpu().numpy()
    metrics = confusion_metrics(confusion)
    metrics["loss"] = loss_sum / max(metrics["samples"], 1)
    if not np.isfinite(metrics["loss"]):
        raise RuntimeError("Non-finite validation loss.")
    return metrics, confusion


@dataclass
class TrainingConfig:
    data: Path
    output: Path
    epochs: int = 15
    batch_size: int = 128
    learning_rate: float = 0.001
    seed: int = 42
    patience: int = 4
    device: str = "cuda"


def train(config: TrainingConfig) -> dict:
    if config.epochs < 1 or config.batch_size < 1 or config.learning_rate <= 0 or config.patience < 1:
        raise ValueError("Epochs, batch size, learning rate and patience must be positive.")
    if config.device == "cuda" and not torch.cuda.is_available():
        raise RuntimeError("CUDA is unavailable. Check the PyTorch GPU test before training.")
    device = torch.device(config.device)
    amp = device.type == "cuda"
    random.seed(config.seed)
    np.random.seed(config.seed)
    torch.manual_seed(config.seed)
    torch.set_num_threads(4)
    if amp:
        torch.cuda.manual_seed_all(config.seed)
        torch.backends.cudnn.benchmark = False
        torch.backends.cudnn.deterministic = True
    maps, labels, splits, preparation = load_prepared_data(config.data)
    weights = class_weights(labels[splits["train"]])
    generator = torch.Generator().manual_seed(config.seed)
    # Windows + 16 GB RAM: one loading process, no copies from worker spawning.
    train_loader = DataLoader(MapDataset(maps, labels, splits["train"]), batch_size=config.batch_size,
                              shuffle=True, generator=generator, num_workers=0, pin_memory=amp)
    validation_loader = DataLoader(MapDataset(maps, labels, splits["validation"]), batch_size=config.batch_size,
                                   shuffle=False, num_workers=0, pin_memory=amp)
    model = WaferCnn().to(device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=config.learning_rate, weight_decay=0.0001)
    criterion = nn.CrossEntropyLoss(weight=weights.to(device))
    scaler = torch.amp.GradScaler("cuda", enabled=amp)
    scheduler = torch.optim.lr_scheduler.ReduceLROnPlateau(optimizer, mode="max", factor=0.5, patience=2)
    config.output.mkdir(parents=True, exist_ok=False)
    settings = {
        "data": str(config.data.resolve()), "output": str(config.output.resolve()),
        "epochs": config.epochs, "batch_size": config.batch_size, "learning_rate": config.learning_rate,
        "seed": config.seed, "early_stopping_patience": config.patience,
        "device": str(device), "gpu": torch.cuda.get_device_name(0) if amp else None,
        "mixed_precision": amp, "num_workers": 0, "augmentation": "none_for_first_baseline",
        "class_weighting": "inverse_sqrt_of_training_counts_mean_normalized",
        "training_class_weights": weights.tolist(),
        "training_class_counts": np.bincount(labels[splits["train"]], minlength=len(CLASS_NAMES)).tolist(),
        "checkpoint_selection": "validation_macro_f1", "test_evaluated": False,
        "purpose": "one_epoch_trial" if config.epochs == 1 else "training",
        "dataset_source_sha256": preparation["source_sha256"],
        "preparation_sha256": sha256_file(config.data / "preparation.json"),
        "split_sha256": preparation["artifact_sha256"]["splits.npz"],
        "model": MODEL_NAME, "parameters": sum(parameter.numel() for parameter in model.parameters()),
        "classes": list(CLASS_NAMES), "preprocessing_id": PREPROCESSING_ID,
        "versions": {"python": platform.python_version(), "torch": str(torch.__version__),
                     "numpy": np.__version__, "cuda_runtime": torch.version.cuda},
        "started_at_utc": datetime.now(timezone.utc).isoformat(),
    }
    write_json(config.output / "config.json", settings)
    print(f"Device: {settings['gpu'] or 'CPU'}; AMP: {amp}; Parameters: {settings['parameters']}", flush=True)
    print(f"Training: {len(train_loader.dataset)}; Validation: {len(validation_loader.dataset)}", flush=True)
    print("Checkpoint selection: validation macro-F1. Test set is reserved.", flush=True)
    history_path = config.output / "history.csv"
    fields = ["epoch", "learning_rate", "train_loss", "train_accuracy", "validation_loss",
              "validation_accuracy", "validation_macro_f1", "validation_balanced_accuracy", "seconds"]
    best, best_epoch, stale, completed = -1.0, 0, 0, 0
    started = time.perf_counter()
    for epoch in range(1, config.epochs + 1):
        epoch_started = time.perf_counter()
        learning_rate = optimizer.param_groups[0]["lr"]
        training = train_epoch(model, train_loader, optimizer, criterion, scaler, device, amp, epoch, config.epochs)
        print("  Validating...", flush=True)
        metrics, confusion = validate(model, validation_loader, device, amp)
        result = {"epoch": epoch, "learning_rate": learning_rate, "train_loss": training["loss"],
                  "train_accuracy": training["accuracy"], "validation_loss": metrics["loss"],
                  "validation_accuracy": metrics["accuracy"], "validation_macro_f1": metrics["macro_f1"],
                  "validation_balanced_accuracy": metrics["balanced_accuracy"],
                  "seconds": time.perf_counter() - epoch_started}
        with history_path.open("a", encoding="utf-8", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=fields)
            if epoch == 1:
                writer.writeheader()
            writer.writerow(result)
        checkpoint = {"format_version": 1, "model_name": MODEL_NAME, "classes": list(CLASS_NAMES),
                      "preprocessing_id": PREPROCESSING_ID, "epoch": epoch, "config": settings,
                      "validation_metrics": metrics,
                      "state_dict": {key: value.detach().cpu() for key, value in model.state_dict().items()}}
        torch.save(checkpoint, config.output / "last.pt")
        if metrics["macro_f1"] > best + 0.0001:
            best, best_epoch, stale = metrics["macro_f1"], epoch, 0
            torch.save(checkpoint, config.output / "best.pt")
            write_json(config.output / "best-validation.json", {"epoch": epoch, **metrics})
            with (config.output / "best-validation-confusion.csv").open("w", encoding="utf-8-sig", newline="") as handle:
                writer = csv.writer(handle)
                writer.writerow(["true_label / predicted_label", *CLASS_NAMES])
                for label, counts in zip(CLASS_NAMES, confusion):
                    writer.writerow([label, *counts.tolist()])
        else:
            stale += 1
        completed = epoch
        print(f"Epoch {epoch}/{config.epochs} complete: val_macro_f1={metrics['macro_f1']:.4f}; "
              f"val_accuracy={metrics['accuracy']:.4f}; seconds={result['seconds']:.1f}", flush=True)
        scheduler.step(metrics["macro_f1"])
        if stale >= config.patience:
            print("Early stopping: validation macro-F1 has not improved.", flush=True)
            break
    summary = {"completed_epochs": completed, "requested_epochs": config.epochs,
               "best_epoch": best_epoch, "best_validation_macro_f1": best,
               "test_evaluated": False, "purpose": settings["purpose"],
               "early_stopped": completed < config.epochs,
               "elapsed_seconds": time.perf_counter() - started,
               "output": str(config.output.resolve())}
    write_json(config.output / "training-summary.json", summary)
    print("TRAINING DONE (validation only)", flush=True)
    print(f"Best epoch: {best_epoch}; Best validation macro-F1: {best:.4f}")
    print(f"Saved: {config.output.resolve()}")
    return summary

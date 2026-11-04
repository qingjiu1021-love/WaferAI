"""Small spatial CNN for categorical wafer maps."""

import torch
from torch import nn
from torch.nn import functional as F

from .preprocessing import CLASS_NAMES, INPUT_SIZE

MODEL_NAME = "WaferCnnV1"


def encode_bins(maps: torch.Tensor) -> torch.Tensor:
    if maps.ndim != 3 or tuple(maps.shape[1:]) != (INPUT_SIZE, INPUT_SIZE):
        raise ValueError("Expected N x 64 x 64 categorical maps.")
    if maps.is_floating_point() or maps.dtype == torch.bool:
        raise ValueError("Expected integer bin values, not floating point or bool.")
    return F.one_hot(maps.long(), num_classes=3).permute(0, 3, 1, 2).contiguous().float()


class WaferCnn(nn.Module):
    def __init__(self):
        super().__init__()
        blocks = []
        channels = 3
        for output_channels in (16, 32, 64):
            blocks.extend([
                nn.Conv2d(channels, output_channels, kernel_size=3, padding=1, bias=False),
                nn.BatchNorm2d(output_channels), nn.ReLU(inplace=True), nn.MaxPool2d(2),
            ])
            channels = output_channels
        self.features = nn.Sequential(*blocks, nn.AdaptiveAvgPool2d((4, 4)))
        self.classifier = nn.Sequential(
            nn.Flatten(), nn.Linear(64 * 4 * 4, 128), nn.ReLU(inplace=True),
            nn.Dropout(0.3), nn.Linear(128, len(CLASS_NAMES)),
        )

    def forward(self, inputs: torch.Tensor) -> torch.Tensor:
        return self.classifier(self.features(inputs))

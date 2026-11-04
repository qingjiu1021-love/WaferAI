"""Shared, deterministic preprocessing for training and future inference."""

import numpy as np

CLASS_NAMES = (
    "none", "Center", "Donut", "Edge-Loc", "Edge-Ring", "Loc", "Random",
    "Scratch", "Near-full",
)
INPUT_SIZE = 64
PREPROCESSING_ID = "nearest_pixel_center_aspect_pad64_v1"


def validate_map(value: object) -> np.ndarray:
    matrix = np.asarray(value)
    if matrix.ndim != 2 or min(matrix.shape, default=0) == 0:
        raise ValueError("map_not_nonempty_2d")
    if not np.issubdtype(matrix.dtype, np.integer):
        raise ValueError("map_not_integer")
    if matrix.min() < 0 or matrix.max() > 2:
        raise ValueError("map_has_invalid_bins")
    if not np.any(matrix != 0):
        raise ValueError("map_has_no_dies")
    return np.ascontiguousarray(matrix, dtype=np.uint8)


def prepare_map(value: object) -> np.ndarray:
    matrix = validate_map(value)
    height, width = matrix.shape
    scale = INPUT_SIZE / max(height, width)
    new_height = max(1, min(INPUT_SIZE, round(height * scale)))
    new_width = max(1, min(INPUT_SIZE, round(width * scale)))
    # Nearest neighbour at pixel centres preserves the categorical bins 0/1/2.
    rows = np.minimum(((np.arange(new_height) + 0.5) * height / new_height).astype(int), height - 1)
    cols = np.minimum(((np.arange(new_width) + 0.5) * width / new_width).astype(int), width - 1)
    result = np.zeros((INPUT_SIZE, INPUT_SIZE), dtype=np.uint8)
    top, left = (INPUT_SIZE - new_height) // 2, (INPUT_SIZE - new_width) // 2
    result[top:top + new_height, left:left + new_width] = matrix[np.ix_(rows, cols)]
    if not np.any(result != 0):
        raise ValueError("map_has_no_dies_after_resize")
    return result

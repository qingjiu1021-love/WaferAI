from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator

MAX_UPLOAD_BYTES = 2 * 1024 * 1024
MAX_MAP_SIDE = 512
Code = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=64)]
Bin = Annotated[int, Field(strict=True, ge=0, le=2)]


class WaferImport(BaseModel):
    model_config = ConfigDict(extra="forbid")
    lot_code: Code
    wafer_code: Code
    map: list[list[Bin]] = Field(min_length=1, max_length=MAX_MAP_SIDE)

    @model_validator(mode="after")
    def validate_matrix(self) -> "WaferImport":
        columns = len(self.map[0])
        if not 1 <= columns <= MAX_MAP_SIDE:
            raise ValueError("晶圆矩阵列数应在 1～512 之间。")
        if any(len(row) != columns for row in self.map):
            raise ValueError("矩阵各行的列数必须相同。")
        if not any(value != 0 for row in self.map for value in row):
            raise ValueError("矩阵中没有有效芯片，不能计算良率。")
        return self


class WaferSummary(BaseModel):
    id: int
    import_id: int
    lot_id: int
    lot_code: str
    wafer_code: str
    filename: str
    rows: int
    cols: int
    good_count: int
    bad_count: int
    total_count: int
    yield_rate: float
    created_at: str


class WaferDetail(WaferSummary):
    map: list[list[int]]


class ImportResult(BaseModel):
    already_imported: bool
    wafer: WaferDetail


class ClassScore(BaseModel):
    label: str
    label_zh: str
    score: float = Field(ge=0, le=1)


class WaferPrediction(BaseModel):
    id: int
    wafer_id: int
    model_version: str
    model_sha256: str
    preprocessing_id: str
    label: str
    label_zh: str
    score: float = Field(ge=0, le=1)
    score_type: Literal["softmax_uncalibrated"] = "softmax_uncalibrated"
    scores: list[ClassScore] = Field(min_length=9, max_length=9)
    inference_ms: float = Field(ge=0)
    device: str
    created_at: str


class PredictionResult(BaseModel):
    already_predicted: bool
    prediction: WaferPrediction


class LotSummary(BaseModel):
    id: int
    source: str
    lot_code: str
    wafer_count: int = Field(ge=0)
    good_count: int = Field(ge=0)
    bad_count: int = Field(ge=0)
    total_count: int = Field(ge=0)
    yield_rate: float | None = Field(default=None, ge=0, le=1)
    last_imported_at: str | None = None

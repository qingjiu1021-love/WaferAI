from sqlalchemy import JSON, CheckConstraint, Float, ForeignKey, Index, Integer, String, Text, UniqueConstraint
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class ImportRecord(Base):
    __tablename__ = "import_records"
    id: Mapped[int] = mapped_column(primary_key=True)
    original_name: Mapped[str] = mapped_column(String(255))
    file_format: Mapped[str] = mapped_column(String(10), default="json")
    file_sha256: Mapped[str] = mapped_column(String(64), index=True)
    source: Mapped[str] = mapped_column(String(30), default="user_file")
    created_at: Mapped[str] = mapped_column(String(40))


class Lot(Base):
    __tablename__ = "lots"
    __table_args__ = (UniqueConstraint("source", "lot_code"),)
    id: Mapped[int] = mapped_column(primary_key=True)
    source: Mapped[str] = mapped_column(String(30))
    lot_code: Mapped[str] = mapped_column(String(64))
    created_at: Mapped[str] = mapped_column(String(40))


class Wafer(Base):
    __tablename__ = "wafers"
    __table_args__ = (
        UniqueConstraint("lot_id", "wafer_code"),
        CheckConstraint("rows_count > 0 AND cols_count > 0"),
        CheckConstraint("good_count >= 0 AND bad_count >= 0"),
        CheckConstraint("good_count + bad_count > 0"),
        CheckConstraint("good_count + bad_count <= rows_count * cols_count"),
        Index("ix_wafers_lot", "lot_id"),
        Index("ix_wafers_import", "import_id"),
        Index("ix_wafers_hash", "map_sha256"),
    )
    id: Mapped[int] = mapped_column(primary_key=True)
    import_id: Mapped[int] = mapped_column(ForeignKey("import_records.id", ondelete="RESTRICT"))
    lot_id: Mapped[int] = mapped_column(ForeignKey("lots.id", ondelete="RESTRICT"))
    wafer_code: Mapped[str] = mapped_column(String(64))
    rows_count: Mapped[int] = mapped_column(Integer)
    cols_count: Mapped[int] = mapped_column(Integer)
    map_path: Mapped[str] = mapped_column(Text)
    map_sha256: Mapped[str] = mapped_column(String(64))
    good_count: Mapped[int] = mapped_column(Integer)
    bad_count: Mapped[int] = mapped_column(Integer)
    created_at: Mapped[str] = mapped_column(String(40))


class Prediction(Base):
    __tablename__ = "predictions"
    __table_args__ = (
        UniqueConstraint("wafer_id", "model_sha256", "input_sha256"),
        CheckConstraint("score >= 0 AND score <= 1"),
        CheckConstraint("inference_ms >= 0"),
        Index("ix_predictions_wafer", "wafer_id"),
    )
    id: Mapped[int] = mapped_column(primary_key=True)
    wafer_id: Mapped[int] = mapped_column(ForeignKey("wafers.id", ondelete="RESTRICT"))
    model_version: Mapped[str] = mapped_column(String(64))
    model_sha256: Mapped[str] = mapped_column(String(64))
    preprocessing_id: Mapped[str] = mapped_column(String(100))
    input_sha256: Mapped[str] = mapped_column(String(64))
    label: Mapped[str] = mapped_column(String(30))
    score: Mapped[float] = mapped_column(Float)
    scores: Mapped[dict] = mapped_column(JSON)
    inference_ms: Mapped[float] = mapped_column(Float)
    device: Mapped[str] = mapped_column(String(20))
    created_at: Mapped[str] = mapped_column(String(40))

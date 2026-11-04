import hashlib
import json
import uuid
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
from pydantic import ValidationError
from sqlalchemy import create_engine, event, func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from .models import Base, ImportRecord, Lot, Wafer
from .schemas import ImportResult, LotSummary, MAX_UPLOAD_BYTES, WaferDetail, WaferImport, WaferSummary


class ImportFailure(Exception):
    def __init__(self, status_code: int, code: str, message: str):
        self.status_code = status_code
        self.code = code
        self.message = message


class WaferStore:
    def __init__(self, storage_dir: Path):
        self.root = storage_dir.resolve()
        self.maps_dir = self.root / "maps"
        self.root.mkdir(parents=True, exist_ok=True)
        self.maps_dir.mkdir(exist_ok=True)
        self.engine = create_engine(
            f"sqlite:///{(self.root / 'waferai.db').as_posix()}",
            connect_args={"check_same_thread": False, "timeout": 5},
        )

        @event.listens_for(self.engine, "connect")
        def configure_sqlite(connection, _):
            connection.execute("PRAGMA foreign_keys=ON")
            connection.execute("PRAGMA busy_timeout=5000")

        Base.metadata.create_all(self.engine)
        with self.engine.connect() as connection:
            connection.exec_driver_sql("PRAGMA journal_mode=WAL")

    def close(self):
        self.engine.dispose()

    def import_json(self, content: bytes, filename: str) -> ImportResult:
        if len(content) > MAX_UPLOAD_BYTES:
            raise ImportFailure(413, "FILE_TOO_LARGE", "文件不能超过 2 MB。")
        if not filename.lower().endswith(".json"):
            raise ImportFailure(422, "INVALID_FORMAT", "请选择 .json 格式的晶圆数据文件。")
        try:
            raw = json.loads(content.decode("utf-8-sig"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            raise ImportFailure(422, "INVALID_JSON", "无法读取 JSON，请检查文件编码和格式。") from None
        try:
            payload = WaferImport.model_validate(raw)
        except ValidationError as error:
            first = error.errors(include_input=False)[0]
            if first["type"] == "value_error":
                message = str(first.get("ctx", {}).get("error", "晶圆矩阵不合法。"))
            else:
                message = "请检查批次号、晶圆编号和矩阵；矩阵只能包含整数 0、1、2。"
            raise ImportFailure(422, "INVALID_WAFER_DATA", message) from None

        matrix = np.array(payload.map, dtype=np.uint8)
        rows, cols = matrix.shape
        map_hash = hashlib.sha256(f"{rows}x{cols}:".encode() + matrix.tobytes()).hexdigest()
        now = datetime.now(timezone.utc).isoformat(timespec="seconds")
        final_path = None
        temporary_path = None
        try:
            with Session(self.engine) as session:
                lot = session.scalar(select(Lot).where(Lot.source == "user_file", Lot.lot_code == payload.lot_code))
                if lot is not None:
                    existing = session.scalar(select(Wafer).where(Wafer.lot_id == lot.id, Wafer.wafer_code == payload.wafer_code))
                    if existing is not None:
                        if existing.map_sha256 != map_hash:
                            raise ImportFailure(409, "WAFER_CONFLICT", "该批次的晶圆编号已存在，但矩阵不同。请检查编号。")
                        return ImportResult(already_imported=True, wafer=self._detail(session, existing))
                else:
                    lot = Lot(source="user_file", lot_code=payload.lot_code, created_at=now)
                    session.add(lot)
                    session.flush()

                safe_name = filename.replace("\\", "/").rsplit("/", 1)[-1][:255]
                record = ImportRecord(original_name=safe_name, file_format="json", file_sha256=hashlib.sha256(content).hexdigest(), source="user_file", created_at=now)
                session.add(record)
                session.flush()
                map_name = f"{uuid.uuid4().hex}.npy"
                final_path = self.maps_dir / map_name
                temporary_path = self.maps_dir / f"{map_name}.tmp"
                with temporary_path.open("xb") as output:
                    np.save(output, matrix, allow_pickle=False)
                temporary_path.replace(final_path)
                wafer = Wafer(
                    import_id=record.id, lot_id=lot.id, wafer_code=payload.wafer_code,
                    rows_count=rows, cols_count=cols, map_path=f"maps/{map_name}",
                    map_sha256=map_hash, good_count=int(np.count_nonzero(matrix == 1)),
                    bad_count=int(np.count_nonzero(matrix == 2)), created_at=now,
                )
                session.add(wafer)
                session.flush()
                result = ImportResult(already_imported=False, wafer=self._detail(session, wafer))
                session.commit()
                return result
        except Exception as error:
            # Files created by this failed transaction are safe to discard.
            if temporary_path is not None:
                temporary_path.unlink(missing_ok=True)
            if final_path is not None:
                final_path.unlink(missing_ok=True)
            if isinstance(error, IntegrityError):
                raise ImportFailure(409, "WAFER_CONFLICT", "晶圆编号发生冲突，请重试或检查编号。") from None
            raise

    def _summary(self, session: Session, wafer: Wafer) -> WaferSummary:
        total = wafer.good_count + wafer.bad_count
        return WaferSummary(
            id=wafer.id, import_id=wafer.import_id, lot_id=wafer.lot_id,
            lot_code=session.get(Lot, wafer.lot_id).lot_code,
            wafer_code=wafer.wafer_code,
            filename=session.get(ImportRecord, wafer.import_id).original_name,
            rows=wafer.rows_count, cols=wafer.cols_count,
            good_count=wafer.good_count, bad_count=wafer.bad_count,
            total_count=total, yield_rate=wafer.good_count / total,
            created_at=wafer.created_at,
        )

    def _detail(self, session: Session, wafer: Wafer) -> WaferDetail:
        matrix = np.load(self.root / wafer.map_path, allow_pickle=False)
        return WaferDetail(**self._summary(session, wafer).model_dump(), map=matrix.tolist())

    def get_wafer(self, wafer_id: int) -> WaferDetail:
        with Session(self.engine) as session:
            wafer = session.get(Wafer, wafer_id)
            if wafer is None:
                raise ImportFailure(404, "WAFER_NOT_FOUND", "没有找到这张晶圆。")
            return self._detail(session, wafer)

    def list_wafers(self, limit: int = 20, offset: int = 0, lot_id: int | None = None) -> list[WaferSummary]:
        with Session(self.engine) as session:
            query = select(Wafer)
            if lot_id is not None:
                if session.get(Lot, lot_id) is None:
                    raise ImportFailure(404, "LOT_NOT_FOUND", "没有找到这个批次。")
                query = query.where(Wafer.lot_id == lot_id)
            wafers = session.scalars(query.order_by(Wafer.id.desc()).limit(limit).offset(offset))
            return [self._summary(session, wafer) for wafer in wafers]

    @staticmethod
    def _lot_query():
        return select(
            Lot.id, Lot.source, Lot.lot_code,
            func.count(Wafer.id).label("wafer_count"),
            func.coalesce(func.sum(Wafer.good_count), 0).label("good_count"),
            func.coalesce(func.sum(Wafer.bad_count), 0).label("bad_count"),
            func.max(Wafer.created_at).label("last_imported_at"),
        ).outerjoin(Wafer, Wafer.lot_id == Lot.id).group_by(Lot.id, Lot.source, Lot.lot_code)

    @staticmethod
    def _lot_summary(row) -> LotSummary:
        good, bad = int(row.good_count), int(row.bad_count)
        total = good + bad
        return LotSummary(
            id=row.id, source=row.source, lot_code=row.lot_code,
            wafer_count=int(row.wafer_count), good_count=good, bad_count=bad,
            total_count=total, yield_rate=good / total if total else None,
            last_imported_at=row.last_imported_at,
        )

    def list_lots(self, limit: int = 20, offset: int = 0) -> list[LotSummary]:
        with Session(self.engine) as session:
            query = self._lot_query().order_by(func.max(Wafer.created_at).desc(), Lot.id.desc()).limit(limit).offset(offset)
            return [self._lot_summary(row) for row in session.execute(query)]

    def get_lot(self, lot_id: int) -> LotSummary:
        with Session(self.engine) as session:
            row = session.execute(self._lot_query().where(Lot.id == lot_id)).first()
            if row is None:
                raise ImportFailure(404, "LOT_NOT_FOUND", "没有找到这个批次。")
            return self._lot_summary(row)

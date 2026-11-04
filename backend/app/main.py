from typing import Literal
from contextlib import asynccontextmanager
from pathlib import Path
import os

from fastapi import FastAPI, File, Query, Request, Response, UploadFile
from fastapi.responses import JSONResponse
from pydantic import BaseModel
from starlette.concurrency import run_in_threadpool

from .schemas import ImportResult, LotSummary, MAX_UPLOAD_BYTES, PredictionResult, WaferDetail, WaferPrediction, WaferSummary
from .ml.inference import ModelPredictor
from .prediction_service import PredictionService
from .wafer_store import ImportFailure, WaferStore
from .accounts.routes import create_service, install_accounts


@asynccontextmanager
async def lifespan(application: FastAPI):
    project = Path(__file__).resolve().parents[2]
    application.state.store = WaferStore(project / "storage")
    create_service(application)
    model_dir = Path(os.environ.get("WAFERAI_MODEL_DIR", str(project / "artifacts/serving/cnn-v1")))
    application.state.predictions = PredictionService(application.state.store, ModelPredictor(model_dir))
    try:
        yield
    finally:
        application.state.store.close()
        application.state.accounts.close()

app = FastAPI(title="WaferAI API", version="0.4.0", lifespan=lifespan)
install_accounts(app)


class HealthResponse(BaseModel):
    status: Literal["ok"] = "ok"
    service: Literal["WaferAI"] = "WaferAI"
    api_version: Literal["v1"] = "v1"


@app.get("/api/v1/health", response_model=HealthResponse, tags=["system"])
def health() -> HealthResponse:
    """Report API availability; database and model checks are added separately."""
    return HealthResponse()


@app.exception_handler(ImportFailure)
async def import_error_handler(_: Request, error: ImportFailure):
    return JSONResponse(status_code=error.status_code, content={"error": {"code": error.code, "message": error.message}})


@app.post("/api/v1/imports", response_model=ImportResult, status_code=201, tags=["wafers"])
async def import_wafer(request: Request, response: Response, file: UploadFile = File(...)):
    try:
        content = await file.read(MAX_UPLOAD_BYTES + 1)
    finally:
        await file.close()
    result = await run_in_threadpool(request.app.state.store.import_json, content, file.filename or "")
    if result.already_imported:
        response.status_code = 200
    return result


@app.get("/api/v1/wafers", response_model=list[WaferSummary], tags=["wafers"])
def list_wafers(request: Request, limit: int = Query(20, ge=1, le=100), offset: int = Query(0, ge=0),
                lot_id: int | None = Query(None, ge=1)):
    return request.app.state.store.list_wafers(limit, offset, lot_id)


@app.get("/api/v1/lots", response_model=list[LotSummary], tags=["lots"])
def list_lots(request: Request, limit: int = Query(20, ge=1, le=100), offset: int = Query(0, ge=0)):
    return request.app.state.store.list_lots(limit, offset)


@app.get("/api/v1/lots/{lot_id}", response_model=LotSummary, tags=["lots"])
def lot_detail(lot_id: int, request: Request):
    return request.app.state.store.get_lot(lot_id)


@app.get("/api/v1/wafers/{wafer_id}", response_model=WaferDetail, tags=["wafers"])
def wafer_detail(wafer_id: int, request: Request):
    return request.app.state.store.get_wafer(wafer_id)


@app.post("/api/v1/wafers/{wafer_id}/predictions", response_model=PredictionResult, status_code=201, tags=["predictions"])
def predict_wafer(wafer_id: int, request: Request, response: Response):
    result = request.app.state.predictions.predict(wafer_id)
    if result.already_predicted:
        response.status_code = 200
    return result


@app.get("/api/v1/wafers/{wafer_id}/predictions/latest", response_model=WaferPrediction, tags=["predictions"])
def latest_prediction(wafer_id: int, request: Request):
    return request.app.state.predictions.latest(wafer_id)

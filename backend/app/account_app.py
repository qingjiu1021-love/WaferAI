"""Run account and help services without loading the ML training environment."""
from contextlib import asynccontextmanager
from fastapi import FastAPI
from .accounts.routes import create_service, install_accounts


@asynccontextmanager
async def lifespan(app):
    create_service(app)
    try:
        yield
    finally:
        app.state.accounts.close()


app = FastAPI(title="WaferAI Account & Help", version="4.1.0", lifespan=lifespan)
install_accounts(app)


@app.get("/api/v1/health")
def health():
    return {"status": "ok", "service": "WaferAI Account"}

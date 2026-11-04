@echo off
setlocal
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
    echo Python environment is missing. Follow the project README to set it up.
    pause
    exit /b 1
)
echo Starting WaferAI at http://127.0.0.1:8000
echo Press Ctrl+C to stop.
".venv\Scripts\python.exe" -m uvicorn app.main:app --host 127.0.0.1 --port 8000
if errorlevel 1 pause

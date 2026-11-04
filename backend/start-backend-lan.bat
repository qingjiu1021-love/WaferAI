@echo off
setlocal
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
    echo Python environment is missing. Follow the project README to set it up.
    pause
    exit /b 1
)
echo Starting WaferAI for phones on the same local network, port 8000.
echo If the local server is running, stop it with Ctrl+C before using this launcher.
echo In the phone app, use this computer's IPv4 address, not 0.0.0.0.
echo Press Ctrl+C to stop.
".venv\Scripts\python.exe" -m uvicorn app.main:app --host 0.0.0.0 --port 8000
if errorlevel 1 pause

@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
    echo 没有找到项目 Python 环境，请先按 README 配置后端。
    pause
    exit /b 1
)
".venv\Scripts\python.exe" scripts\connect_phone.py --install
echo.
pause

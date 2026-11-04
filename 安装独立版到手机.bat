@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
if not exist "backend\.venv\Scripts\python.exe" (
    echo 没有找到电脑上的项目 Python 环境，也可以直接把独立版 APK 传到手机安装。
    pause
    exit /b 1
)
"backend\.venv\Scripts\python.exe" backend\scripts\install_standalone.py
echo.
pause

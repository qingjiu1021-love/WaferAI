@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
echo 将构建并检查当前版本，然后同步源码和安装包到 GitHub。
echo 发布前请先在 android\app\build.gradle.kts 增加 versionName 和 versionCode。
py -3.12 scripts\publish_github.py --publish
if errorlevel 1 echo 发布未完成，请查看上方提示。已有正式版本不会被覆盖。
pause

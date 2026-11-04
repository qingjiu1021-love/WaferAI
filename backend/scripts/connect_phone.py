"""Prepare the user's authorized USB phone for the local WaferAI demo."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request


PROJECT = Path(__file__).resolve().parents[2]


def find_adb() -> Path:
    candidates: list[Path] = []
    properties = PROJECT / "android/local.properties"
    if properties.exists():
        for line in properties.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                sdk = line.partition("=")[2].replace("\\:", ":").replace("\\\\", "\\")
                candidates.append(Path(sdk) / "platform-tools/adb.exe")
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(key):
            candidates.append(Path(os.environ[key]) / "platform-tools/adb.exe")
    found = shutil.which("adb")
    if found:
        candidates.append(Path(found))
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    raise RuntimeError("没有找到 Android SDK 的 adb，请先在 Android Studio 配置 SDK。")


def run(adb: Path, *arguments: str, timeout: int = 30) -> str:
    result = subprocess.run([str(adb), *arguments], capture_output=True, text=True,
                            encoding="utf-8", errors="replace", timeout=timeout)
    if result.returncode:
        raise RuntimeError((result.stderr or result.stdout).strip() or "手机连接操作失败。")
    return result.stdout.strip()


def select_phone(adb: Path, serial: str | None) -> str:
    listing = run(adb, "devices", "-l")
    devices = {parts[0]: parts[1] for line in listing.splitlines()
               if len(parts := line.split()) >= 2 and not line.startswith("List ")}
    if not serial:
        try:
            serial = run(adb, "-d", "get-serialno")
        except RuntimeError as error:
            raise RuntimeError(
                "请连接一台安卓手机，打开 USB 调试，并在手机上允许这台电脑。"
                "如果接了多台手机，请暂时断开其他手机。\n当前设备：\n" + listing
            ) from error
    if serial.startswith("emulator-"):
        raise RuntimeError("此工具用于真实手机；模拟器请在 App 服务设置选择“模拟器连接”。")
    state = devices.get(serial)
    if state != "device":
        raise RuntimeError(f"手机尚未就绪（{state or '未找到'}）。请检查数据线并在手机上允许 USB 调试。")
    return serial


def check_service() -> None:
    try:
        with urllib.request.urlopen("http://127.0.0.1:8000/api/v1/health", timeout=5) as response:
            health = json.load(response)
        if health.get("status") != "ok" or health.get("service") != "WaferAI" or health.get("api_version") != "v1":
            raise ValueError("Unexpected service")
    except Exception as error:
        raise RuntimeError("请先运行 backend\\start-backend.bat，确认电脑上的 WaferAI 服务已启动。") from error


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="Explicit phone serial when multiple devices are attached")
    parser.add_argument("--install", action="store_true", help="Install the project's latest debug APK")
    parser.add_argument("--check", action="store_true", help="Read-only phone and service check")
    args = parser.parse_args()
    try:
        adb = find_adb()
        serial = select_phone(adb, args.serial)
        check_service()
        apk = PROJECT / "android/app/build/outputs/apk/debug/app-debug.apk"
        if args.install and not apk.is_file():
            raise RuntimeError("尚未生成安装包，请先在 Android Studio 构建 app。")
        if args.check:
            print(f"手机与电脑服务检查通过：{serial}。未修改连接或安装应用。")
            return 0
        mappings = [line.split()[-2:] for line in run(adb, "-s", serial, "reverse", "--list").splitlines()]
        existing = next((pair for pair in mappings if len(pair) == 2 and pair[0] == "tcp:8000"), None)
        if existing and existing[1] != "tcp:8000":
            raise RuntimeError("手机的 8000 端口已有其他连接，请检查现有调试配置后重试。")
        if args.install:
            print("正在安装 WaferAI，保留原应用数据…", flush=True)
            print(run(adb, "-s", serial, "install", "-r", str(apk), timeout=120))
        if not existing:
            run(adb, "-s", serial, "reverse", "--no-rebind", "tcp:8000", "tcp:8000")
        confirmed = run(adb, "-s", serial, "reverse", "--list")
        if not any(line.split()[-2:] == ["tcp:8000", "tcp:8000"] for line in confirmed.splitlines()):
            raise RuntimeError("未能确认 USB 连接，请重新插线后重试。")
        if args.install:
            run(adb, "-s", serial, "shell", "am", "start", "-n", "com.example.waferai/.MainActivity")
        print("USB 连接已准备好。请在手机 App 打开“服务设置”，选择“USB 连接”，点击“检查并保存”。")
        print("电脑分析服务和数据线需要保持连接；重新插线后请再次运行本工具。")
        return 0
    except (RuntimeError, OSError, subprocess.SubprocessError) as error:
        print(f"未完成：{error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    raise SystemExit(main())

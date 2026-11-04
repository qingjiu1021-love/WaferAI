"""Install the standalone APK on one authorized USB phone, without a backend."""
from pathlib import Path
import argparse
import subprocess
import sys
from connect_phone import find_adb, select_phone, run

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial")
    args=parser.parse_args()
    try:
        adb=find_adb(); serial=select_phone(adb,args.serial)
        apk=Path(__file__).resolve().parents[2]/"android/app/build/outputs/apk/debug/app-debug.apk"
        if not apk.is_file(): raise RuntimeError("未找到安装包，请先构建 Android 项目，或手动将独立版 APK 传到手机安装。")
        print("正在安装 WaferAI 独立版，保留已有应用数据…",flush=True)
        print(run(adb,"-s",serial,"install","-r",str(apk),timeout=120))
        run(adb,"-s",serial,"shell","am","start","-n","com.example.waferai/.MainActivity")
        print("安装完成，可以断开 USB。本地识别、统计和报告不需要电脑，在线解析只需手机联网。")
        return 0
    except (RuntimeError,OSError,subprocess.SubprocessError) as error:
        print(f"未完成：{error}",file=sys.stderr); return 1

if __name__=="__main__":
    sys.stdout.reconfigure(encoding="utf-8"); sys.stderr.reconfigure(encoding="utf-8")
    raise SystemExit(main())

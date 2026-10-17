#!/usr/bin/env python3
"""执行 SDK Debug 黑盒截图探针；复用正常系统授权，不开会、不注入令牌。"""

import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import time

spec = importlib.util.spec_from_file_location("meeting_device", Path(__file__).with_name("meeting-device.py"))
device = importlib.util.module_from_spec(spec)
spec.loader.exec_module(device)


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    cli.add_argument("--adb-command", default=os.environ.get("CZUR_ADB_COMMAND", "adb"))
    cli.add_argument("--output", required=True, help="本机证据目录；只包含探针测试图与 JSON")
    cli.add_argument("--timeout", type=int, default=60)
    cli.add_argument("--manual-permissions", action="store_true")
    args = cli.parse_args()
    adb = device.Adb(args.adb_command, args.serial)
    tool = device.MeetingDevice(adb, output=args.output, auto_permissions=not args.manual_permissions)
    tool.stage = "screenCaptureApi"
    # 不打断已运行的真实会议；此独立探针只验基础截图公开 API。
    if tool.log_state().get("phase") in ("RUNNING", "STOPPING", "STOP_PENDING"):
        raise device.ToolError("请先正常结束当前会议，再运行独立截图探针")
    adb.run("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    started = adb.run("shell", "am", "start", "-W", "-n",
                      device.PACKAGE + "/.ScreenCaptureProbeActivity")
    if "Error" in started:
        raise device.ToolError("请安装包含截图探针的 SDK Debug APK")
    deadline = time.monotonic() + args.timeout
    report = None
    while time.monotonic() < deadline:
        raw = adb.run("exec-out", "run-as", device.PACKAGE, "cat",
                      "files/screen-api-probe/result.json", allow_failure=True)
        try:
            report = json.loads(raw)
            break
        except json.JSONDecodeError:
            pass
        try:
            tool.approve_permission(tool.dump())
        except device.UiSnapshotUnavailable:
            pass
        time.sleep(0.3)
    if report is None:
        raise device.ToolError("截图探针尚未完成；请检查系统授权或 App 日志")
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    (output / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    for name in ("raw-excluded.png", report.get("checks", {}).get("processedFile"),
                 report.get("checks", {}).get("rawFile")):
        if name:
            with (output / Path(name).name).open("wb") as stream:
                subprocess.run(adb.command + ["exec-out", "run-as", device.PACKAGE, "cat",
                    "files/screen-api-probe/" + Path(name).name], stdout=stream, check=True, timeout=30)
    print(json.dumps(report, ensure_ascii=False))
    return 0 if report.get("status") == "PASS" else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (device.ToolError, subprocess.SubprocessError) as failure:
        print(json.dumps({"status": "FAIL", "message": str(failure)}, ensure_ascii=False), file=sys.stderr)
        sys.exit(1)

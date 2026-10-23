#!/usr/bin/env python3
"""开发者在 PC 上构建地址已注入的 LAN Debug APK；不操作设备或启动服务。

默认构建真实 SDK＋UI 组合，必须显式提供匹配的两份 AAR；Mock 只在明确选择时启用。
后端默认 HTTPS:8443，结果页默认 HTTP:8090/index.html，可分别覆盖端口或完整 URL。
运行前须已有可达服务，以及与所选主机匹配的 TLS 证书；本工具只转发公开 Debug CA。
"""

import argparse
from pathlib import Path
import shlex
import socket
import subprocess
import sys

ANDROID_PROJECT = Path(__file__).resolve().parents[2] / "android-reference-app"


def detect_lan_host():
    """让本机路由选择出口 IPv4；UDP connect 只选源地址，不发送请求或探测远端。

    目标使用文档保留地址。多网卡或无默认路由时，开发者可用 --host 明确指定
    与设备互通的地址；自动结果仍需与已准备的 TLS IP SAN 一致。
    """
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as route:
        route.connect(("192.0.2.1", 9))
        return route.getsockname()[0]


def existing_file(value):
    """把构建输入解析为绝对路径；不复制 AAR／CA，也不读取或打印文件内容。"""
    path = Path(value).expanduser().resolve()
    if not path.is_file():
        raise argparse.ArgumentTypeError("构建输入文件不存在: " + str(path))
    return path


def parser():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--host", help="设备可达的PC局域网IPv4或主机名；默认由本机路由选择")
    cli.add_argument("--backend-port", type=int, default=8443)
    cli.add_argument("--web-port", type=int, default=8090)
    cli.add_argument("--backend-url", help="完整后端URL；提供时不再拼接对应host／port")
    cli.add_argument("--web-url", help="完整结果页URL；原样传递，保留路径／query／fragment")
    cli.add_argument("--device-aar", type=existing_file, help="匹配版本的设备 AAR，必填；业务 AAR 由工程内 meeting-android 构建")
    cli.add_argument("--debug-ca", type=existing_file, help="可选的公开开发CA，仅用于Debug构建")
    cli.add_argument("--dry-run", action="store_true", help="打印实际Gradle参数，不运行Gradle")
    return cli


def build_command(args):
    """组合既有 Gradle 参数，不新增应用运行时配置入口。

    完整 URL 具有更高优先级，仅缺少的地址由 host 与各自端口生成；两份完整 URL
    均已提供时不做路由探测。显式 -P 地址按 App 构建约定覆盖本地 properties 文件。
    返回 argv 数组，由 subprocess 直接调用，不经 shell 展开。
    """
    if args.device_aar is None:
        raise ValueError("需要 --device-aar；业务 AAR 由工程内 meeting-android 构建")
    host = args.host
    if not (args.backend_url and args.web_url):
        host = host or detect_lan_host()
    backend = args.backend_url or f"https://{host}:{args.backend_port}"
    web = args.web_url or f"http://{host}:{args.web_port}/index.html"
    command = [str(ANDROID_PROJECT / "gradlew"), ":app:assembleDebug",
               "-PreferenceBackendUrl=" + backend, "-PreferenceResultWebUrl=" + web]
    for value, key in ((args.device_aar, "deviceSdkAar"),
                       (args.debug_ca, "referenceDebugCaCertificate")):
        if value is not None:
            command.append("-P" + key + "=" + str(value))
    command.append("--console=plain")
    return command


def main(argv=None):
    cli = parser()
    args = cli.parse_args(argv)
    try:
        command = build_command(args)
    except (OSError, ValueError) as error:
        cli.error(str(error) + "；自动选址不可用时请显式传入 --host")
    print("构建目录: " + str(ANDROID_PROJECT), flush=True)
    print(shlex.join(command), flush=True)
    if args.dry_run:
        return 0
    return subprocess.run(command, cwd=ANDROID_PROJECT, check=False).returncode


if __name__ == "__main__":
    sys.exit(main())

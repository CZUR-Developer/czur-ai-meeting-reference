#!/usr/bin/env python3
"""参考 App 的真实 UI 启动工具：仅用 Python 标准库和可替换的 adb runner。

配置、登录、开会和系统授权均操作页面现有控件；不注入 token、不创建旁路会议。
坐标只从当前层级中的 resource-id 对应 bounds 计算，不依赖设备分辨率。
"""

import argparse
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

PACKAGE = "com.czur.aimeeting.reference"


class ToolError(RuntimeError):
    """当前流程未完成；命令行返回非零，并保留可定位的阶段信息。"""


def by_id(root, name):
    """匹配公开页面 ID，返回实际 UI 节点；系统 ID 可传完整名称。"""
    resource_id = name if ":id/" in name else PACKAGE + ":id/" + name
    return next((node for node in root.iter("node")
                 if node.get("resource-id") == resource_id), None)


def center(node):
    """使用本次 UI dump 的矩形，不缓存位置或为某台设备写固定坐标。"""
    values = re.findall(r"-?\d+", node.get("bounds", ""))
    if len(values) != 4:
        raise ToolError("控件没有有效 bounds: " + node.get("resource-id", ""))
    left, top, right, bottom = map(int, values)
    if right <= left or bottom <= top:
        raise ToolError("控件目前不可见: " + node.get("resource-id", ""))
    return str((left + right) // 2), str((top + bottom) // 2)


def redact(value, secrets):
    """输出前移除本次密码；原始日志与未脱敏层级不写到主机证据目录。"""
    text = str(value)
    for secret in secrets:
        if secret:
            text = text.replace(secret, "[REDACTED]")
    return text


def redacted_xml(root, secrets):
    copy = ET.fromstring(ET.tostring(root, encoding="unicode"))
    for node in copy.iter("node"):
        sensitive = node.get("password") == "true" or any(
            word in node.get("resource-id", "").lower()
            for word in ("password", "token", "secret"))
        for name, value in list(node.attrib.items()):
            node.set(name, "[REDACTED]" if sensitive and name in ("text", "content-desc")
                     else redact(value, secrets))
    return ET.tostring(copy, encoding="unicode")


class Adb:
    """runner 接收标准 adb 参数并把原始 stdout 写到 stdout，支持 stdin 文本。

    --adb-command 可指向本机 adb 或调用团队设备技能的薄包装；此脚本不会导入
    私有技能、调用 shell=True 或把输入密码放入子进程的 argv。
    """

    def __init__(self, command, serial, secrets=()):
        self.command = shlex.split(command) + (["-s", serial] if serial else [])
        self.secrets = secrets

    def run(self, *arguments, stdin=None, allow_failure=False):
        result = subprocess.run(self.command + list(arguments), input=stdin,
                                text=True, capture_output=True, timeout=30)
        if result.returncode and not allow_failure:
            raise ToolError("adb 操作失败: " + redact(result.stderr.strip(), self.secrets))
        return result.stdout.strip()


class MeetingDevice:
    """串行执行正常 UI 流程；JSON 是操作回执，不作为第二份会议状态。"""

    def __init__(self, adb, timeout=45, output=None, secrets=(), auto_permissions=True):
        self.adb, self.timeout = adb, timeout
        self.output = Path(output) if output else None
        self.secrets, self.auto_permissions = secrets, auto_permissions
        self.stage = "prepare"
        self.last_ui = ET.Element("hierarchy")
        if self.output:
            self.output.mkdir(parents=True, exist_ok=True)

    def emit(self, status, **details):
        event = {"stage": self.stage, "status": status, **details}
        line = redact(json.dumps(event, ensure_ascii=False), self.secrets)
        print(line, flush=True)
        if self.output:
            with (self.output / "events.jsonl").open("a", encoding="utf-8") as stream:
                stream.write(line + "\n")

    def dump(self):
        # uiautomator 的临时 XML 只用于读回，本次操作完成即删除；主机仅存脱敏副本。
        remote = "/data/local/tmp/czur-meeting-ui-" + uuid.uuid4().hex + ".xml"
        try:
            self.adb.run("shell", "uiautomator", "dump", "--compressed", remote)
            xml = self.adb.run("exec-out", "cat", remote)
            self.last_ui = ET.fromstring(xml[xml.index("<?xml"):] if "<?xml" in xml else xml)
            return self.last_ui
        finally:
            self.adb.run("shell", "rm", "-f", remote, allow_failure=True)

    def evidence(self):
        if self.output:
            (self.output / (self.stage + "-ui.xml")).write_text(
                redacted_xml(self.last_ui, self.secrets), encoding="utf-8")

    def wait(self, predicate, description):
        deadline = time.monotonic() + self.timeout
        while time.monotonic() < deadline:
            root = self.dump()
            result = predicate(root)
            if result is not None and result is not False:
                return result
            time.sleep(0.4)
        self.evidence()
        raise ToolError("等待超时: " + description + "；当前页面: " + self.page_description())

    def page_description(self):
        nodes = [node for node in self.last_ui.iter("node") if node.get("text")
                 or node.get("clickable") == "true"]
        return redact(json.dumps([{name: node.get(name, "") for name in
                                  ("resource-id", "text", "enabled")}
                                 for node in nodes if node.get("password") != "true"][:12],
                                 ensure_ascii=False), self.secrets)

    def tap(self, node):
        if node.get("enabled") == "false":
            raise ToolError("当前能力不可用: " + node.get("resource-id", ""))
        self.adb.run("shell", "input", "tap", *center(node))

    def tap_id(self, name):
        self.tap(self.wait(lambda root: by_id(root, name), "控件 " + name))

    def hide_keyboard(self):
        shown = self.adb.run("shell", "dumpsys", "input_method")
        if "mInputShown=true" in shown or "mIsInputViewShown=true" in shown:
            self.adb.run("shell", "input", "keyevent", "KEYCODE_BACK")

    def fill(self, name, value):
        # 标准 input text 依赖 Android 键盘映射；简单工具明确 ASCII 边界，不装输入法。
        if not value.isascii() or any(c in value for c in "\r\n") or "%s" in value:
            raise ToolError("标准 adb 输入仅支持单行 ASCII，且不能包含字面量 %s；请手动填写 " + name)
        self.tap_id(name)
        self.adb.run("shell", "input", "keycombination", "113", "29")  # Ctrl+A，Android 14。
        self.adb.run("shell", "input", "keyevent", "KEYCODE_DEL")
        # shell 的 read 从 stdin 获取值，变量展开不再执行元字符，密码不进入 adb argv。
        self.adb.run("shell", 'IFS= read -r czur_ui_input; input text "$czur_ui_input"',
                     stdin=value.replace(" ", "%s") + "\n")
        self.hide_keyboard()

    def open(self):
        self.stage = "open"
        self.adb.run("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        self.adb.run("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")
        self.wait(lambda root: by_id(root, "settings_button")
                  if by_id(root, "settings_button") is not None else by_id(root, "stop_meeting"),
                  "参考 App 页面")
        self.evidence()
        self.emit("PASS")

    def configure(self, backend, web):
        self.stage = "configure"
        self.tap_id("settings_button")
        self.fill("backend_url", backend)
        self.fill("web_url", web)
        root = self.dump()
        for name, expected in (("backend_url", backend), ("web_url", web)):
            node = by_id(root, name)
            if node is None or node.get("text") != expected:
                raise ToolError("输入未匹配页面: " + name)
        self.tap_id("connection_save")
        self.wait(lambda ui: by_id(ui, "settings_button"), "连接配置保存返回")
        self.evidence()
        self.emit("PASS", backendUrl=backend, webUrl=web)

    def login(self, username, password):
        self.stage = "login"
        # 已登录时这个同一按钮先退出，再打开空白登录框；每次明确使用本次账号。
        self.tap_id("account_button")
        self.fill("login_username", username)
        self.fill("login_password", password)
        self.tap_id("login_submit")

        def logged_in(root):
            start = by_id(root, "start_meeting")
            return start is not None and start.get("enabled") == "true" \
                and by_id(root, "login_submit") is None

        self.wait(logged_in, "真实登录成功及部署能力加载（开始按钮可用）")
        self.evidence()
        self.emit("PASS", capabilitiesLoaded=True)

    def check(self, name, desired):
        node = self.wait(lambda root: by_id(root, name), "选项 " + name)
        if (node.get("checked") == "true") != desired:
            self.tap(node)
        self.wait(lambda root: (by_id(root, name) is not None and
                  (by_id(root, name).get("checked") == "true") == desired), "选项生效 " + name)

    def language(self, name, label):
        if label is None:
            return
        self.tap_id(name)
        row = self.wait(lambda root: next((node for node in root.iter("node")
                        if node.get("resource-id") == "android:id/text1"
                        and node.get("text") == label), None), "语言列表项 " + label)
        self.tap(row)
        self.wait(lambda root: by_id(root, name), "语言列表关闭")

    def options(self, mode, presentation, summary, source, target):
        self.stage = "options"
        if mode == "translation" and presentation:
            raise ToolError("参考业务中互译模式没有演示文稿选项，请选择字幕模式")
        self.check("translation_mode" if mode == "translation" else "transcript_mode", True)
        self.language("source_language", source)
        if mode == "translation":
            self.language("target_language", target)
        self.check("summary_enabled", summary)
        if mode == "transcript":
            self.check("presentation_capture", presentation)
        self.evidence()
        self.emit("PASS", mode=mode, presentation=presentation, summary=summary)

    def log_state(self):
        pid = self.adb.run("shell", "pidof", PACKAGE, allow_failure=True).split()
        if not pid:
            return {"processRunning": False, "meetingId": None, "phase": None,
                    "audioMode": None, "screenCaptureMode": None}
        # 只读当前 App PID 的日志；不清 logcat，不保存原始日志或会议正文。
        raw = self.adb.run("logcat", "-d", "--pid=" + pid[0], "-v", "brief",
                           "AiMeetingReference:D", "AiMeetingSdk:I", "*:S")
        starts = list(re.finditer(r"组合会议已启动 meetingId=(\S+)", raw))
        latest = raw[starts[-1].start():] if starts else raw
        meeting_id = starts[-1].group(1) if starts else None
        def last(pattern, source=latest):
            values = re.findall(pattern, source)
            return values[-1] if values else None
        same_meeting = "\n".join(line for line in raw.splitlines()
                                 if meeting_id and "meetingId=" + meeting_id in line)
        return {"processRunning": True, "meetingId": meeting_id,
                "phase": last(r"组合会议状态 phase=(\w+)"),
                "audioMode": last(r"本场实际音频来源 mode=(\w+)", same_meeting),
                "screenCaptureMode": last(r"SDK演示采集已开始 mode=(\w+)", same_meeting),
                "submittedFrameCountInLogWindow": len(re.findall("演示截图已提交组合会话", latest))}

    def approve_permission(self, root):
        """只在 Start 后确认标准录音／整屏采集弹窗；其他弹窗明确交由人工。"""
        for prefix in ("com.android.permissioncontroller", "com.google.android.permissioncontroller",
                       "com.android.packageinstaller"):
            node = by_id(root, prefix + ":id/permission_allow_foreground_only_button")
            if node is None:
                node = by_id(root, prefix + ":id/permission_allow_button")
            if node is not None and self.auto_permissions:
                self.tap(node)
                self.emit("PROGRESS", permission="microphone", action="confirmedSystemDialog")
                return True
        title = by_id(root, "android:id/alertTitle")
        message = by_id(root, "android:id/message")
        text = " ".join(node.get("text", "") for node in (title, message) if node is not None)
        projection = any(word in text.lower() for word in
                         ("录制或投放", "recording or casting", "record or cast", "share your screen"))
        button = by_id(root, "android:id/button1")
        if projection and button is not None and self.auto_permissions:
            self.tap(button)
            self.emit("PROGRESS", permission="screenCapture", action="confirmedSystemDialog")
            return True
        return False

    def start(self):
        self.stage = "start"
        before = self.log_state().get("meetingId")
        self.tap_id("start_meeting")
        deadline = time.monotonic() + self.timeout
        while time.monotonic() < deadline:
            state = self.log_state()
            # 音量动画和连续字幕可能让 uiautomator 无法等到 idle；App 的实际 RUNNING
            # 事件足够确认正常按钮已进入会议，无须为了找 Stop 控件继续等动态画面。
            if state.get("meetingId") and state["meetingId"] != before and state.get("phase") == "RUNNING":
                self.evidence()
                self.emit("PASS", confirmedBy="App RUNNING log", **state)
                return
            root = self.dump()
            if self.approve_permission(root):
                continue
            time.sleep(0.4)
        self.evidence()
        raise ToolError("开始会议未完成；请确认业务提示或系统授权弹窗: " + self.page_description())

    def stop(self):
        self.stage = "stop"
        before = self.log_state()
        if not before.get("meetingId") or before.get("phase") == "READY":
            raise ToolError("未找到当前可停止会议，请先运行 status 核对")
        self.adb.run("shell", "am", "broadcast", "-a", PACKAGE + ".DEBUG_STOP", "-p", PACKAGE)
        deadline = time.monotonic() + self.timeout
        while time.monotonic() < deadline:
            state = self.log_state()
            if state.get("meetingId") == before["meetingId"] and state.get("phase") == "READY":
                self.emit("PASS", **state)
                return
            time.sleep(0.4)
        raise ToolError("未观察到同场 READY；DEBUG_STOP 仅 Debug APK 开放，请核对状态和结果页面")

    def status(self):
        self.stage = "status"
        root = self.dump()
        state = self.log_state()
        state["ui"] = {name: {key: node.get(key, "") for key in
                             ("text", "enabled", "checked")}
                       for name in ("account_button", "status", "start_meeting", "stop_meeting",
                                    "transcript_mode", "translation_mode", "presentation_capture")
                       if (node := by_id(root, name)) is not None}
        self.evidence()
        self.emit("PASS", **state)


def parser():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("command", choices=("open", "configure", "login", "options", "start",
                                         "setup-start", "stop", "status", "reverse", "remove-reverse"))
    cli.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    cli.add_argument("--adb-command", default=os.environ.get("CZUR_ADB_COMMAND", "adb"))
    cli.add_argument("--backend-url")
    cli.add_argument("--web-url")
    cli.add_argument("--username", default=os.environ.get("CZUR_MEETING_USERNAME"))
    cli.add_argument("--password-env", default="CZUR_MEETING_PASSWORD")
    cli.add_argument("--mode", choices=("transcript", "translation"), default="transcript")
    cli.add_argument("--presentation", action="store_true")
    cli.add_argument("--summary", action="store_true")
    cli.add_argument("--source-language", help="与页面一致的语言名称，例如 中文 或 English")
    cli.add_argument("--target-language")
    cli.add_argument("--manual-permissions", action="store_true", help="保留系统弹窗，由人手动确认")
    cli.add_argument("--timeout", type=float, default=45, help="各阶段实际状态等待秒数")
    cli.add_argument("--output", help="可选主机证据目录，仅存 JSON 回执和脱敏 XML")
    cli.add_argument("--device-port", type=int, default=8080)
    cli.add_argument("--host-port", type=int, default=8080)
    return cli


def main():
    cli = parser()
    args = cli.parse_args()
    password = os.environ.get(args.password_env, "")
    if args.command in ("configure", "setup-start") and not (args.backend_url and args.web_url):
        cli.error("该命令需要 --backend-url 和 --web-url")
    if args.command in ("login", "setup-start") and not (args.username and password):
        cli.error("请提供 --username 或 CZUR_MEETING_USERNAME，并设置密码环境变量 " + args.password_env)
    tool = MeetingDevice(Adb(args.adb_command, args.serial, (password,)), args.timeout,
                         args.output, (password,), not args.manual_permissions)
    try:
        if args.command == "setup-start":
            tool.open()
            tool.configure(args.backend_url, args.web_url)
            tool.login(args.username, password)
            tool.options(args.mode, args.presentation, args.summary, args.source_language, args.target_language)
            tool.start()
        elif args.command == "configure":
            tool.configure(args.backend_url, args.web_url)
        elif args.command == "login":
            tool.login(args.username, password)
        elif args.command == "options":
            tool.options(args.mode, args.presentation, args.summary, args.source_language, args.target_language)
        elif args.command in ("reverse", "remove-reverse"):
            tool.stage = args.command
            arguments = ("reverse", "tcp:" + str(args.device_port), "tcp:" + str(args.host_port)) \
                if args.command == "reverse" else ("reverse", "--remove", "tcp:" + str(args.device_port))
            tool.adb.run(*arguments)
            tool.emit("PASS", devicePort=args.device_port, hostPort=args.host_port)
        else:
            getattr(tool, args.command)()
    except (ToolError, OSError, subprocess.TimeoutExpired, ET.ParseError) as failure:
        tool.evidence()
        tool.emit("FAIL", message=redact(failure, (password,)))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

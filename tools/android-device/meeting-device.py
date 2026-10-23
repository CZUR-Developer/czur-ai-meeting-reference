#!/usr/bin/env python3
"""参考 App 的真实 UI 启动工具：仅用 Python 标准库和可替换的 adb runner。

部署地址随 APK 构建注入；登录、开会和系统授权操作现有控件，不注入 token 或旁路会议。
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


class UiSnapshotUnavailable(ToolError):
    """uiautomator 暂未给出 XML；不代表已点击的会议按钮执行失败。"""


def by_id(root, name):
    """匹配公开页面 ID，返回实际 UI 节点；系统 ID 可传完整名称。"""
    resource_id = name if ":id/" in name else PACKAGE + ":id/" + name
    return next((node for node in root.iter("node")
                 if node.get("resource-id") == resource_id), None)


def by_description(root, description):
    """浮窗按钮沿用可访问性文案；从实际层级读取位置，不写死屏幕坐标。"""
    return next((node for node in root.iter("node")
                 if node.get("content-desc") == description and node.get("clickable") == "true"), None)


def overlay_windows(raw):
    """只提取本App实际APPLICATION_OVERLAY窗口，Activity Dialog不计作浮窗。"""
    found = []
    for block in re.split(r"(?m)^\s*Window #\d+ Window", raw)[1:]:
        if "package=" + PACKAGE not in block or not re.search(r"ty=(?:APPLICATION_OVERLAY|2038)\b", block):
            continue
        size = re.search(r"mAttrs=\{\([^)]*\)\((\d+)x(\d+)\)", block)
        if size:
            found.append({"type": "APPLICATION_OVERLAY", "width": int(size[1]), "height": int(size[2])})
    return found


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
            if "<hierarchy" not in xml and "<?xml" not in xml:
                raise UiSnapshotUnavailable("uiautomator 暂未返回 UI XML")
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
        self.fill_once(name, value)
        node = by_id(self.dump(), name)
        # 中文输入法可能把 adb 的按键流合成为全角标点／候选词，即使字段为 textUri。
        # 密码不读回；其他可见字段先核对，必要时只切换一次现有输入法语言再重填。
        # 不安装输入法、不写系统语言设置，也不通过私有接口绕过实际登录页面。
        if node is not None and node.get("password") != "true" and node.get("text") != value:
            self.adb.run("shell", "input", "keyevent", "KEYCODE_LANGUAGE_SWITCH")
            self.fill_once(name, value)
            node = by_id(self.dump(), name)
            if node is None or node.get("text") != value:
                raise ToolError("输入未匹配页面，请确认输入法使用英文模式: " + name)

    def fill_once(self, name, value):
        """通过当前输入控件替换文本；输入值只走 stdin，错误消息不包含字段内容。"""
        self.tap_id(name)
        self.adb.run("shell", "input", "keycombination", "113", "29")  # Ctrl+A，Android 14。
        self.adb.run("shell", "input", "keyevent", "KEYCODE_DEL")
        # shell 的 read 从 stdin 获取值，变量展开不再执行元字符，密码不进入 adb argv。
        self.adb.run("shell", 'IFS= read -r czur_ui_input; input text "$czur_ui_input"',
                     stdin=value.replace(" ", "%s") + "\n")
        self.hide_keyboard()

    def open(self):
        """回到已有会前／会中页；结果页只通过正常 Activity 导航退出。

        CLEAR_TOP/SINGLE_TOP 把现有主页面带到前台，不清 App 数据、不重置登录，
        也不停止后台会议。若结果页仍在展示，使用其公开返回按钮继续导航。
        """
        self.stage = "open"
        self.adb.run("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        self.adb.run("shell", "am", "start", "--activity-clear-top", "--activity-single-top",
                     "-n", PACKAGE + "/.MainActivity")

        def main_page(root):
            start = by_id(root, "start_meeting")
            if start is not None:
                return start
            stop = by_id(root, "stop_meeting")
            if stop is not None:
                return stop
            # 默认会中已经退出Activity；再次点图标复用同场浮窗，Mini也保持。
            floating_stop = by_description(root, "停止会议")
            return floating_stop if floating_stop is not None else by_description(root, "展开会议窗口")

        def navigation_target(root):
            page = main_page(root)
            if page is not None:
                return page
            back = by_id(root, "result_back")
            if back is not None:
                return back
            # 不抢焦点的浮窗可能不在uiautomator的活动页面层级中。
            if overlay_windows(self.adb.run("shell", "dumpsys", "window", "windows")) and self.log_state().get("phase") == "RUNNING":
                return ET.Element("overlay")
            return None

        target = self.wait(navigation_target, "参考 App 会前／会中页或结果返回入口")
        if target.get("resource-id") == PACKAGE + ":id/result_back":
            self.tap(target)
            self.wait(main_page, "返回参考 App 会前／会中页")
        self.evidence()
        self.emit("PASS")

    def login(self, username, password):
        self.stage = "login"
        self.tap_id("account_button")

        def account_entry(root):
            # 未登录时直接显示表单；已登录时，系统 AlertDialog 先展示账号菜单。
            # 按实际层级的系统 ID 和完整文字匹配切换项，不依赖行序号或点击退出。
            username_field = by_id(root, "login_username")
            if username_field is not None:
                return username_field
            return next((node for node in root.iter("node")
                         if node.get("resource-id") == "android:id/text1"
                         and node.get("text") == "切换账号"), None)

        entry = self.wait(account_entry, "登录表单或账号切换菜单")
        if entry.get("resource-id") == "android:id/text1":
            self.tap(entry)
            self.wait(lambda root: by_id(root, "login_username"), "切换账号登录表单")
        # 切换成功才由原登录流程替换身份；取消／失败仍保留原会话，工具不提前退出。
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

    def optional_check(self, name, desired):
        """按后端能力声明处理可选开关；未声明且未请求时是正常降级。

        参考 App 会根据服务端能力动态隐藏总结、翻译等开关。自动化工具不能把
        “控件不存在”当成 UI 超时，否则合作伙伴不提供该能力时无法验证基础会议。
        请求一个未声明的能力仍然失败，避免把用户意图静默改掉。
        """
        node = by_id(self.dump(), name)
        if node is None:
            if desired:
                raise ToolError("服务未声明所请求的可选能力: " + name)
            return False
        self.check(name, desired)
        return True

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
        summary_available = self.optional_check("summary_enabled", summary)
        if mode == "transcript":
            self.check("presentation_capture", presentation)
        self.evidence()
        self.emit("PASS", mode=mode, presentation=presentation, summary=summary,
                  summaryAvailable=summary_available)

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
                "submittedFrameCountInLogWindow": len(re.findall(r"演示截图已(?:提交组合会话|由业务接受)", latest))}

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
            try:
                root = self.dump()
            except UiSnapshotUnavailable:
                # 开始已点击一次。连续字幕／音量动画期间可能暂时没有层级快照，
                # 沿原 deadline 回到下一轮优先读取真实 RUNNING 日志，不重新点击。
                # 其他 ADB 错误、已有 XML 标记但结构损坏和业务失败仍走原失败出口。
                time.sleep(0.4)
                continue
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

    def overlay_controls(self):
        """Debug只读入口返回本次挂载按钮坐标，后续仍用adb input真实触摸。"""
        raw = self.adb.run("shell", "am", "broadcast", "-a", PACKAGE + ".DEBUG_OVERLAY", "-p", PACKAGE)
        payload = re.search(r'data="(\{.*\})"', raw)
        if not payload:
            raise ToolError("未取得浮窗按钮坐标；请安装提供DEBUG_OVERLAY的Debug包，Release请手动点击")
        return json.loads(payload.group(1))["controls"]

    def wait_overlay_control(self, description):
        deadline = time.monotonic() + self.timeout
        while time.monotonic() < deadline:
            controls = self.overlay_controls()
            if description in controls:
                left, top, right, bottom = controls[description]
                return ET.Element("node", {"bounds": f"[{left},{top}][{right},{bottom}]"})
            time.sleep(.2)
        raise ToolError("当前浮窗没有可操作按钮: " + description)

    def overlay(self, action):
        """操作真实浮窗按钮；形态切换验证后继入口与同场ID，停止等待同场READY。"""
        self.stage = "overlay"
        before = self.log_state()
        actions = {"collapse": ("收起窗口", "展开会议窗口"), "restore": ("展开会议窗口", "收起窗口"),
                   "large": ("大窗口", "普通窗口"), "normal": ("普通窗口", "大窗口"),
                   "stop": ("停止会议", None)}
        if action != "status":
            description, expected = actions[action]
            self.tap(self.wait_overlay_control(description))
            if expected:
                self.wait_overlay_control(expected)
            else:
                deadline = time.monotonic() + self.timeout
                while time.monotonic() < deadline:
                    current = self.log_state()
                    if current.get("meetingId") == before.get("meetingId") and current.get("phase") == "READY":
                        break
                    time.sleep(.4)
                else:
                    raise ToolError("真实停止按钮已点击，尚未观察到同场READY")
        current = self.log_state()
        if action != "status" and current.get("meetingId") != before.get("meetingId"):
            raise ToolError("窗口操作前后会议ID不一致")
        windows = overlay_windows(self.adb.run("shell", "dumpsys", "window", "windows"))
        if action == "stop" and windows:
            raise ToolError("会议已READY，仍有本App悬浮窗")
        if action != "stop" and not windows:
            raise ToolError("没有本App实际APPLICATION_OVERLAY窗口")
        self.evidence()
        self.emit("PASS", action=action, windows=windows, **current)

    def classify_meeting_state(self, state):
        """把进程/日志/UI拆成可判定的会议状态，避免进程存在就冒充会议在跑。"""
        if not state.get("processRunning"):
            return "UNKNOWN"
        phase = state.get("phase")
        meeting_id = state.get("meetingId")
        ui = state.get("ui") or {}
        if phase in ("PREPARING", "RUNNING", "STOPPING", "STOP_ACCEPTED") or "stop_meeting" in ui:
            return "MEETING_RUNNING"
        if phase == "READY":
            return "MEETING_READY"
        if meeting_id is None and "start_meeting" in ui:
            return "APP_RUNNING_NO_MEETING"
        return "UNKNOWN"

    def status(self):
        self.stage = "status"
        state = self.log_state()
        state["ui"] = {}
        if state.get("processRunning"):
            try:
                root = self.dump()
                state["ui"] = {name: {key: node.get(key, "") for key in
                                      ("text", "enabled", "checked")}
                               for name in ("account_button", "status", "start_meeting", "stop_meeting",
                                            "transcript_mode", "translation_mode", "presentation_capture")
                               if (node := by_id(root, name)) is not None}
            except UiSnapshotUnavailable:
                # 动态界面偶尔取不到层级；日志里的会议状态仍然有效，不把它误判成失败。
                state["ui"] = {}
        state["meetingState"] = self.classify_meeting_state(state)
        if state.get("processRunning") and state["ui"]:
            self.evidence()
        # status 的 status 字段直接给会议状态，避免进程存在被自动化误读成会议 PASS。
        self.emit(state["meetingState"], **state)


def parser():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("command", choices=("open", "login", "options", "start",
                                         "setup-start", "stop", "status", "overlay", "reverse", "remove-reverse"))
    cli.add_argument("--overlay-action", choices=("status", "collapse", "restore", "large", "normal", "stop"), default="status")
    cli.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    cli.add_argument("--adb-command", default=os.environ.get("CZUR_ADB_COMMAND", "adb"))
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


def main(argv=None):
    cli = parser()
    args = cli.parse_args(argv)
    password = os.environ.get(args.password_env, "")
    if args.command in ("login", "setup-start") and not (args.username and password):
        cli.error("请提供 --username 或 CZUR_MEETING_USERNAME，并设置密码环境变量 " + args.password_env)
    tool = MeetingDevice(Adb(args.adb_command, args.serial, (password,)), args.timeout,
                         args.output, (password,), not args.manual_permissions)
    try:
        if args.command == "setup-start":
            tool.open()
            tool.login(args.username, password)
            tool.options(args.mode, args.presentation, args.summary, args.source_language, args.target_language)
            tool.start()
        elif args.command == "login":
            tool.login(args.username, password)
        elif args.command == "options":
            tool.options(args.mode, args.presentation, args.summary, args.source_language, args.target_language)
        elif args.command == "overlay":
            tool.overlay(args.overlay_action)
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

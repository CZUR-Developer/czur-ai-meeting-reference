"""正常选择器、UI 接线和回执测试；不连接设备，也不建立模型／后端。"""

import importlib.util
from pathlib import Path
import unittest
from unittest.mock import Mock, call, patch
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location("meeting_device", Path(__file__).with_name("meeting-device.py"))
device = importlib.util.module_from_spec(spec)
spec.loader.exec_module(device)


def hierarchy(*nodes):
    root = ET.Element("hierarchy")
    for name, attributes in nodes:
        ET.SubElement(root, "node", {"resource-id": device.PACKAGE + ":id/" + name,
                                    "bounds": "[20,40][220,100]", "enabled": "true", **attributes})
    return root


class MeetingDeviceTests(unittest.TestCase):
    def test_overlay_debug_snapshot_uses_current_bounds_for_real_touch(self):
        adb = Mock()
        adb.run.return_value = 'Broadcast completed: result=-1, data="{"controls":{"收起窗口":[100,200,140,240]}}"'
        tool = device.MeetingDevice(adb)
        tool.tap(tool.wait_overlay_control("收起窗口"))
        adb.run.assert_any_call("shell", "input", "tap", "120", "220")

    def test_overlay_requires_debug_receipt_instead_of_using_old_coordinates(self):
        adb = Mock()
        adb.run.return_value = 'Broadcast completed: result=0'
        with self.assertRaises(device.ToolError):
            device.MeetingDevice(adb).overlay_controls()

    def test_overlay_selector_accepts_a_leaf_and_ignores_nonclickable_labels(self):
        root = hierarchy(("", {"content-desc": "停止会议", "clickable": "true"}))
        self.assertIsNotNone(device.by_description(root, "停止会议"))
        self.assertIsNone(device.by_description(root, "普通窗口"))

    def test_open_accepts_a_running_overlay_after_the_setup_activity_exits(self):
        tool = device.MeetingDevice(Mock())
        tool.dump = Mock(return_value=hierarchy(("", {"content-desc": "停止会议", "clickable": "true"})))
        with patch("builtins.print"):
            tool.open()
        self.assertEqual(tool.dump.call_count, 1)

    def test_window_receipt_counts_only_this_apps_real_overlay(self):
        raw = f"""  Window #0 Window{{main}}:
    package={device.PACKAGE}
    mAttrs={{(0,0)(1800x1008) ty=BASE_APPLICATION}}
  Window #1 Window{{panel}}:
    package={device.PACKAGE}
    mAttrs={{(60,968)(1800x222) ty=APPLICATION_OVERLAY}}
  Window #2 Window{{other}}:
    package=another.app
    mAttrs={{(0,0)(200x100) ty=APPLICATION_OVERLAY}}
"""
        self.assertEqual(device.overlay_windows(raw), [{"type": "APPLICATION_OVERLAY", "width": 1800, "height": 222}])

    def test_resource_id_and_live_bounds(self):
        root = hierarchy(("start_meeting", {}))
        self.assertEqual(device.center(device.by_id(root, "start_meeting")), ("120", "70"))
        self.assertIsNone(device.by_id(root, "stop_meeting"))

    def test_open_accepts_leaf_button(self):
        adb = Mock()
        tool = device.MeetingDevice(adb)
        tool.dump = Mock(return_value=hierarchy(("start_meeting", {})))
        with patch("builtins.print"):
            tool.open()
        adb.run.assert_any_call("shell", "am", "start", "--activity-clear-top", "--activity-single-top",
                                "-n", device.PACKAGE + "/.MainActivity")

    def test_open_returns_from_result_page_through_its_normal_back_button(self):
        adb = Mock()
        tool = device.MeetingDevice(adb)
        tool.dump = Mock(side_effect=[hierarchy(("result_back", {})),
                                     hierarchy(("start_meeting", {}))])
        with patch("builtins.print"):
            tool.open()
        adb.run.assert_any_call("shell", "input", "tap", "120", "70")
        self.assertEqual(tool.dump.call_count, 2)

    def test_open_accepts_the_current_meeting_without_resetting_it(self):
        adb = Mock()
        tool = device.MeetingDevice(adb)
        tool.dump = Mock(return_value=hierarchy(("stop_meeting", {})))
        with patch("builtins.print"):
            tool.open()
        self.assertEqual(adb.run.call_count, 2)

    def test_setup_start_uses_the_built_apk_without_a_configuration_step(self):
        with patch.object(device, "MeetingDevice") as factory, \
                patch.dict(device.os.environ, {"CZUR_MEETING_PASSWORD": "test-password"}):
            self.assertEqual(device.main(["setup-start", "--username", "meeting-user"]), 0)
        self.assertEqual(factory.return_value.method_calls, [
            call.open(), call.login("meeting-user", "test-password"),
            call.options("transcript", False, False, None, None), call.start(),
        ])

    def test_setup_start_accepts_missing_credentials_for_an_existing_session(self):
        # 已登录时不应为了复用会话再要一次密码；缺凭据只在未登录页面才报错。
        with patch.object(device, "MeetingDevice") as factory, \
                patch.dict(device.os.environ, {"CZUR_MEETING_USERNAME": "",
                                               "CZUR_MEETING_PASSWORD": ""}):
            self.assertEqual(device.main(["setup-start"]), 0)
        self.assertIn(call.login("", ""), factory.return_value.method_calls)

    def test_device_tool_has_no_address_configuration_command_or_flags(self):
        with patch("sys.stderr"):
            for arguments in (["configure"], ["open", "--backend-url", "https://example.invalid"],
                              ["open", "--web-url", "https://example.invalid/index.html"]):
                with self.assertRaises(SystemExit):
                    device.parser().parse_args(arguments)

    def test_password_input_uses_stdin_and_replaces_existing_field(self):
        adb = Mock()
        adb.run.return_value = "mInputShown=false"
        tool = device.MeetingDevice(adb)
        tool.dump = Mock(return_value=hierarchy(("login_password", {"password": "true"})))
        tool.fill("login_password", "sample-secret!")
        calls = adb.run.call_args_list
        self.assertTrue(any(call.args[:4] == ("shell", "input", "keycombination", "113") for call in calls))
        self.assertTrue(any(call.kwargs.get("stdin") == "sample-secret!\n" for call in calls))
        self.assertFalse(any("sample-secret!" in str(call.args) for call in calls))

    def test_saved_xml_redacts_password_and_known_secret(self):
        root = hierarchy(("login_password", {"text": "sample-secret!", "password": "true"}),
                         ("status", {"text": "sample-secret!"}))
        saved = device.redacted_xml(root, ("sample-secret!",))
        self.assertNotIn("sample-secret!", saved)
        self.assertIn("[REDACTED]", saved)
        self.assertEqual(device.by_id(root, "login_password").get("text"), "sample-secret!")

    def test_visible_text_retries_once_after_input_method_composes_it(self):
        adb = Mock()
        tool = device.MeetingDevice(adb)
        tool.fill_once = Mock()
        account = "meeting.user@example.invalid"
        tool.dump = Mock(side_effect=[hierarchy(("login_username", {"text": "meeting。user＠example。invalid"})),
                                      hierarchy(("login_username", {"text": account}))])
        tool.fill("login_username", account)
        adb.run.assert_called_once_with("shell", "input", "keyevent", "KEYCODE_LANGUAGE_SWITCH")
        self.assertEqual(tool.fill_once.call_count, 2)

    def test_status_reports_absent_process_as_unknown_not_pass(self):
        tool = device.MeetingDevice(Mock())
        tool.log_state = Mock(return_value={"processRunning": False, "meetingId": None, "phase": None})
        tool.emit = Mock()
        tool.status()
        self.assertEqual("UNKNOWN", tool.emit.call_args.args[0])
        self.assertEqual("UNKNOWN", tool.emit.call_args.kwargs["meetingState"])

    def test_logged_in_login_reuses_the_session_without_tapping_the_account_button(self):
        adb = Mock()
        tool = device.MeetingDevice(adb)
        tool.tap_id, tool.fill = Mock(), Mock()
        tool.emit = Mock()
        # 已登录时账号按钮文字是“退出登录”；脚本不得点击，也不等待已移除的切换菜单。
        tool.dump = Mock(return_value=hierarchy(("account_button", {"text": "退出登录"}),
                                                ("account_name", {"text": "meeting-user"})))
        with patch("builtins.print"):
            tool.login("requested-user", "sample-secret!")
        adb.run.assert_not_called()
        tool.tap_id.assert_not_called()
        tool.fill.assert_not_called()
        self.assertEqual(tool.dump.call_count, 1)
        tool.emit.assert_called_once_with("PASS", alreadyLoggedIn=True, account="meeting-user")

    def test_setup_start_second_run_does_not_log_out_or_wait_for_a_switch_menu(self):
        adb = Mock()
        tool = device.MeetingDevice(adb)
        # 账号按钮与开始按钮使用不同 bounds，便于断言没有任何触摸落在账号按钮上。
        tool.dump = Mock(return_value=hierarchy(
            ("account_button", {"text": "退出登录", "bounds": "[500,500][700,600]"}),
            ("account_name", {"text": "meeting-user"}),
            ("transcript_mode", {"checked": "true"}),
            ("presentation_capture", {"checked": "false"}),
            ("start_meeting", {})))
        tool.log_state = Mock(side_effect=[
            {"processRunning": True, "meetingId": None, "phase": None},
            {"processRunning": True, "meetingId": "new-meeting", "phase": "RUNNING"}])
        tool.emit = Mock()
        with patch("builtins.print"), patch.object(device.time, "sleep"):
            tool.open()
            tool.login("requested-user", "sample-secret!")
            tool.options("transcript", False, False, None, None)
            tool.start()
        taps = [call.args for call in adb.run.call_args_list
                if call.args[:3] == ("shell", "input", "tap")]
        self.assertEqual(taps, [("shell", "input", "tap", "120", "70")])  # 只点开始按钮
        self.assertIn(call("PASS", alreadyLoggedIn=True, account="meeting-user"),
                      tool.emit.call_args_list)
        self.assertIn(call("PASS", confirmedBy="App RUNNING log",
                           processRunning=True, meetingId="new-meeting", phase="RUNNING"),
                      tool.emit.call_args_list)

    def test_logged_out_login_opens_the_form_and_submits_the_requested_user(self):
        adb = Mock()
        tool = device.MeetingDevice(adb)
        tool.tap_id, tool.fill = Mock(), Mock()
        tool.dump = Mock(side_effect=[hierarchy(("account_button", {"text": "登录"})),
                                     hierarchy(("login_username", {})),
                                     hierarchy(("start_meeting", {}))])
        with patch("builtins.print"):
            tool.login("first-user", "sample-secret!")
        # 未登录时点一次账号按钮打开参考表单，之后只提交，不碰任何账号菜单。
        adb.run.assert_called_once_with("shell", "input", "tap", "120", "70")
        self.assertEqual([call.args for call in tool.tap_id.call_args_list],
                         [("login_submit",)])
        tool.fill.assert_any_call("login_username", "first-user")
        tool.fill.assert_any_call("login_password", "sample-secret!")

    def test_logged_out_login_without_credentials_fails_before_tapping(self):
        adb = Mock()
        tool = device.MeetingDevice(adb)
        tool.dump = Mock(return_value=hierarchy(("account_button", {"text": "登录"})))
        with self.assertRaises(device.ToolError) as failure:
            tool.login(None, "")
        self.assertIn("CZUR_MEETING_PASSWORD", str(failure.exception))
        adb.run.assert_not_called()

    def test_same_meeting_log_modes_before_started_and_latest_phase(self):
        adb = Mock()
        adb.run.side_effect = ["1234", "\n".join((
            "组合会议已启动 meetingId=old mode=LIVE_TRANSCRIPT",
            "本场实际音频来源 mode=CZUR_BROKER meetingId=old",
            "组合会议状态 phase=READY savedEventCount=1",
            "SDK演示采集已开始 mode=SYSTEM_CAPTURE excludeWindows=true meetingId=new",
            "组合会议已启动 meetingId=new mode=LIVE_TRANSCRIPT",
            "本场实际音频来源 mode=MIC meetingId=new",
            "组合会议状态 phase=RUNNING savedEventCount=2",
            "演示截图已由业务接受 offsetMs=10000"))]
        result = device.MeetingDevice(adb).log_state()
        self.assertEqual(result["meetingId"], "new")
        self.assertEqual(result["phase"], "RUNNING")
        self.assertEqual(result["audioMode"], "MIC")
        self.assertEqual(result["screenCaptureMode"], "SYSTEM_CAPTURE")
        self.assertEqual(result["submittedFrameCountInLogWindow"], 1)

    def test_start_no_xml_snapshot_then_real_running_log_does_not_tap_again(self):
        adb = Mock()
        adb.run.return_value = "ERROR: could not get idle state."
        tool = device.MeetingDevice(adb)
        tool.tap_id = Mock()
        running = {"processRunning": True, "meetingId": "new-meeting",
                   "phase": "RUNNING", "audioMode": "CZUR_BROKER"}
        tool.log_state = Mock(side_effect=[{"meetingId": "old-meeting", "phase": "READY"},
                                           {"meetingId": None, "phase": None}, running])
        tool.dump = Mock(wraps=tool.dump)
        tool.emit = Mock()
        with patch.object(device.time, "sleep") as pause:
            tool.start()
        tool.tap_id.assert_called_once_with("start_meeting")
        tool.dump.assert_called_once()
        self.assertEqual(tool.log_state.call_count, 3)
        pause.assert_called_once_with(0.4)
        tool.emit.assert_called_once_with("PASS", confirmedBy="App RUNNING log", **running)

    def test_standard_projection_confirmation_and_manual_mode(self):
        root = ET.fromstring('<hierarchy><node resource-id="android:id/alertTitle" '
                             'text="要开始录制或投放内容吗？"/><node resource-id="android:id/button1" '
                             'enabled="true" bounds="[20,40][220,100]"/></hierarchy>')
        tool = device.MeetingDevice(Mock())
        with patch("builtins.print"):
            self.assertTrue(tool.approve_permission(root))
        tool.adb.run.assert_called_once_with("shell", "input", "tap", "120", "70")
        tool.auto_permissions = False
        self.assertFalse(tool.approve_permission(root))

    def test_unprovided_optional_capability_is_a_normal_downgrade(self):
        tool = device.MeetingDevice(Mock())
        tool.dump = Mock(return_value=hierarchy(("transcript_mode", {"checked": "true"}),
                                                 ("presentation_capture", {"checked": "false"})))
        self.assertFalse(tool.optional_check("summary_enabled", False))
        with self.assertRaises(device.ToolError):
            tool.optional_check("summary_enabled", True)


    def status_tool(self, state, root=None):
        tool = device.MeetingDevice(Mock())
        tool.log_state = Mock(return_value=state)
        tool.dump = Mock(return_value=root if root is not None else hierarchy(("start_meeting", {})))
        tool.emit = Mock()
        tool.evidence = Mock()
        return tool

    def test_status_running_process_without_meeting_is_not_pass_meeting(self):
        tool = self.status_tool({"processRunning": True, "meetingId": None, "phase": None})
        tool.status()
        self.assertEqual("APP_RUNNING_NO_MEETING", tool.emit.call_args.kwargs["meetingState"])

    def test_status_running_meeting(self):
        tool = self.status_tool({"processRunning": True, "meetingId": "mtg_1", "phase": "RUNNING"})
        tool.status()
        self.assertEqual("MEETING_RUNNING", tool.emit.call_args.kwargs["meetingState"])

    def test_status_ready_meeting(self):
        tool = self.status_tool({"processRunning": True, "meetingId": "mtg_1", "phase": "READY"})
        tool.status()
        self.assertEqual("MEETING_READY", tool.emit.call_args.kwargs["meetingState"])

    def test_status_unrecognized_state_is_unknown(self):
        tool = self.status_tool({"processRunning": True, "meetingId": "mtg_1", "phase": None},
                                root=hierarchy(("status", {})))
        tool.status()
        self.assertEqual("UNKNOWN", tool.emit.call_args.kwargs["meetingState"])

    def test_stop_without_meeting_is_explicit_error(self):
        tool = device.MeetingDevice(Mock())
        tool.log_state = Mock(return_value={"processRunning": True, "meetingId": None, "phase": None})
        with self.assertRaises(device.ToolError):
            tool.stop()


if __name__ == "__main__":
    unittest.main()

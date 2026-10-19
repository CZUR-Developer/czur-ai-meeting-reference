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

    def test_status_does_not_report_an_absent_process_as_pass(self):
        tool = device.MeetingDevice(Mock())
        tool.dump = Mock(return_value=hierarchy())
        tool.log_state = Mock(return_value={"processRunning": False})
        with self.assertRaises(device.ToolError):
            tool.status()

    def test_logged_in_switch_still_submits_requested_user(self):
        adb = Mock()
        tool = device.MeetingDevice(adb)
        tool.tap_id, tool.fill = Mock(), Mock()
        # 故意把退出项放前面：脚本必须依据本次层级里的完整文字点击切换，不能点首项。
        menu = ET.fromstring('<hierarchy><node resource-id="android:id/text1" text="退出登录" '
                             'enabled="true" bounds="[20,40][220,100]"/>'
                             '<node resource-id="android:id/text1" text="切换账号" '
                             'enabled="true" bounds="[20,120][220,180]"/></hierarchy>')
        tool.dump = Mock(side_effect=[menu, hierarchy(("login_username", {})),
                                     hierarchy(("start_meeting", {}))])
        with patch("builtins.print"):
            tool.login("requested-user", "sample-secret!")
        adb.run.assert_called_once_with("shell", "input", "tap", "120", "150")
        self.assertEqual([call.args for call in tool.tap_id.call_args_list],
                         [("account_button",), ("login_submit",)])
        tool.fill.assert_any_call("login_username", "requested-user")
        tool.fill.assert_any_call("login_password", "sample-secret!")

    def test_first_login_uses_the_form_without_tapping_an_account_menu(self):
        adb = Mock()
        tool = device.MeetingDevice(adb)
        tool.tap_id, tool.fill = Mock(), Mock()
        tool.dump = Mock(side_effect=[hierarchy(("login_username", {})),
                                     hierarchy(("start_meeting", {}))])
        with patch("builtins.print"):
            tool.login("first-user", "sample-secret!")
        adb.run.assert_not_called()
        self.assertEqual([call.args for call in tool.tap_id.call_args_list],
                         [("account_button",), ("login_submit",)])
        tool.fill.assert_any_call("login_username", "first-user")
        tool.fill.assert_any_call("login_password", "sample-secret!")

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


if __name__ == "__main__":
    unittest.main()

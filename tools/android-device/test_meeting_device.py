"""正常选择器、UI 接线和回执测试；不连接设备，也不建立模型／后端。"""

import importlib.util
from pathlib import Path
import unittest
from unittest.mock import Mock, patch
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
    def test_resource_id_and_live_bounds(self):
        root = hierarchy(("start_meeting", {}))
        self.assertEqual(device.center(device.by_id(root, "start_meeting")), ("120", "70"))
        self.assertIsNone(device.by_id(root, "stop_meeting"))

    def test_open_accepts_leaf_button(self):
        adb = Mock()
        tool = device.MeetingDevice(adb)
        tool.dump = Mock(return_value=hierarchy(("settings_button", {})))
        with patch("builtins.print"):
            tool.open()
        adb.run.assert_any_call("shell", "am", "start", "-n", device.PACKAGE + "/.MainActivity")

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

    def test_logged_in_switch_still_submits_requested_user(self):
        adb = Mock()
        tool = device.MeetingDevice(adb)
        tool.tap_id, tool.fill = Mock(), Mock()
        tool.dump = Mock(return_value=hierarchy(("start_meeting", {})))
        with patch("builtins.print"):
            tool.login("requested-user", "sample-secret!")
        self.assertEqual([call.args for call in tool.tap_id.call_args_list],
                         [("account_button",), ("login_submit",)])
        tool.fill.assert_any_call("login_username", "requested-user")
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
            "演示截图已提交组合会话 offsetMs=10000"))]
        result = device.MeetingDevice(adb).log_state()
        self.assertEqual(result["meetingId"], "new")
        self.assertEqual(result["phase"], "RUNNING")
        self.assertEqual(result["audioMode"], "MIC")
        self.assertEqual(result["screenCaptureMode"], "SYSTEM_CAPTURE")
        self.assertEqual(result["submittedFrameCountInLogWindow"], 1)

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


if __name__ == "__main__":
    unittest.main()

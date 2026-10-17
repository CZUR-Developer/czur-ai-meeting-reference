"""LAN构建参数测试；不调用Gradle、ADB或真实网络。"""

import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location("build_lan_apk", Path(__file__).with_name("build-lan-apk.py"))
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class LanBuildTests(unittest.TestCase):
    def test_route_selection_only_reads_the_local_socket_address(self):
        route = Mock()
        route.getsockname.return_value = ("192.0.2.10", 12345)
        with patch.object(builder.socket, "socket") as factory:
            factory.return_value.__enter__.return_value = route
            self.assertEqual(builder.detect_lan_host(), "192.0.2.10")
        route.connect.assert_called_once_with(("192.0.2.1", 9))
        route.send.assert_not_called()
        route.sendto.assert_not_called()

    def test_auto_host_keeps_backend_and_web_ports_separate(self):
        args = builder.parser().parse_args(["--runtime", "mock", "--web-port", "8091"])
        with patch.object(builder, "detect_lan_host", return_value="192.0.2.10"):
            command = builder.build_command(args)
        self.assertIn("-PreferenceBackendUrl=https://192.0.2.10:8443", command)
        self.assertIn("-PreferenceResultWebUrl=http://192.0.2.10:8091/index.html", command)

    def test_explicit_host_avoids_route_detection(self):
        args = builder.parser().parse_args(["--runtime", "mock", "--host", "pc.example.invalid",
                                            "--backend-port", "9443"])
        with patch.object(builder, "detect_lan_host") as detect:
            command = builder.build_command(args)
        detect.assert_not_called()
        self.assertIn("-PreferenceBackendUrl=https://pc.example.invalid:9443", command)

    def test_full_urls_are_passed_unchanged_including_query_and_fragment(self):
        backend = "https://api.example.invalid/meeting"
        web = "https://results.example.invalid/index.html?theme=partner#meeting"
        args = builder.parser().parse_args(["--runtime", "mock", "--backend-url", backend,
                                            "--web-url", web])
        with patch.object(builder, "detect_lan_host") as detect:
            command = builder.build_command(args)
        detect.assert_not_called()
        self.assertIn("-PreferenceBackendUrl=" + backend, command)
        self.assertIn("-PreferenceResultWebUrl=" + web, command)

    def test_sdk_never_silently_falls_back_without_matching_aars(self):
        with self.assertRaisesRegex(ValueError, "--sdk-aar.*--ui-aar"):
            builder.build_command(builder.parser().parse_args(["--host", "192.0.2.10"]))

    def test_matching_aars_and_ca_use_existing_gradle_inputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            inputs = [Path(temporary) / name for name in ("sdk.aar", "ui.aar", "ca.pem")]
            for path in inputs:
                path.touch()
            args = builder.parser().parse_args(["--host", "192.0.2.10", "--sdk-aar", str(inputs[0]),
                                                "--ui-aar", str(inputs[1]), "--debug-ca", str(inputs[2])])
            command = builder.build_command(args)
            self.assertIn("-PmeetingRuntime=sdk", command)
            for path, key in zip(inputs, ("meetingSdkAar", "meetingUiAar", "referenceDebugCaCertificate")):
                self.assertIn("-P" + key + "=" + str(path), command)

    def test_dry_run_does_not_execute_gradle(self):
        with patch.object(builder.subprocess, "run") as run, patch("builtins.print"):
            self.assertEqual(builder.main(["--runtime", "mock", "--host", "192.0.2.10", "--dry-run"]), 0)
        run.assert_not_called()

    def test_build_runs_argv_directly_in_the_android_project(self):
        with patch.object(builder.subprocess, "run", return_value=Mock(returncode=0)) as run, \
                patch("builtins.print"):
            self.assertEqual(builder.main(["--runtime", "mock", "--host", "192.0.2.10"]), 0)
        command = run.call_args.args[0]
        self.assertEqual(command[:2], [str(builder.ANDROID_PROJECT / "gradlew"), ":app:assembleDebug"])
        self.assertEqual(run.call_args.kwargs, {"cwd": builder.ANDROID_PROJECT, "check": False})


if __name__ == "__main__":
    unittest.main()

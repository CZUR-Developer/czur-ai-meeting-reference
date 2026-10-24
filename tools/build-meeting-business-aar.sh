#!/usr/bin/env bash
# 内部同步脚本（不是伙伴构建入口）：设备 AAR → 业务 AAR → Starry App libs。
# 伙伴路径是直接执行 `./gradlew :meeting-android:assembleRelease`（设备 AAR 固定在
# meeting-android/libs 下，Gradle 不再接受 -PdeviceSdkAar）。
# 用法：tools/build-meeting-business-aar.sh --device-aar <设备 AAR> [--starry-dir <starry 仓>]
set -euo pipefail

device_aar=""
starry_dir="/mnt/DataDisk/Data/Code/starry"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --device-aar) device_aar="$2"; shift 2 ;;
    --starry-dir) starry_dir="$2"; shift 2 ;;
    *) echo "未知参数 $1" >&2; exit 2 ;;
  esac
done
[[ -f "$device_aar" ]] || { echo "设备 AAR 不存在：$device_aar" >&2; exit 2; }
[[ -d "$starry_dir/apps/transcription/libs" ]] || { echo "Starry libs 目录不存在" >&2; exit 2; }

root="$(cd "$(dirname "$0")/.." && pwd)"
device_aar_target="$root/android-reference-app/meeting-android/libs/ai-meeting-device.aar"
# Gradle 只认 libs 下这份；先落盘再校验，避免"看起来换了 AAR、实际打的还是旧包"。
cp "$device_aar" "$device_aar_target"
printf '设备 AAR 已就位: %s\n' "$(sha256sum "$device_aar_target" | cut -d' ' -f1)"
(cd "$root/android-reference-app" && ./gradlew :meeting-android:assembleRelease)
cp "$root/android-reference-app/meeting-android/build/outputs/aar/meeting-android-release.aar" \
   "$starry_dir/apps/transcription/libs/ai-meeting-business-0.5.0.aar"
sha256sum "$starry_dir/apps/transcription/libs/ai-meeting-business-0.5.0.aar"

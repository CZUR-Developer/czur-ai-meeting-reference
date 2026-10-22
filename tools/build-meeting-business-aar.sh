#!/usr/bin/env bash
# 设备 AAR → 业务 AAR → Starry App libs。
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
(cd "$root/android-reference-app" && ./gradlew :meeting-android:assembleRelease -PdeviceSdkAar="$device_aar")
cp "$root/android-reference-app/meeting-android/build/outputs/aar/meeting-android-release.aar" \
   "$starry_dir/apps/transcription/libs/ai-meeting-business-0.5.0.aar"
sha256sum "$starry_dir/apps/transcription/libs/ai-meeting-business-0.5.0.aar"

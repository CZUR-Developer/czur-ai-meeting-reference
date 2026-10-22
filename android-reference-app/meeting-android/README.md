# meeting-android（业务 AAR）

## 定位

- **设备 AAR**（starry 仓 `base/aiMeetingSdk`）闭源，只提供 MIC／Broker 采集、投屏截图、native 和 AIDL。
- **本模块**开源，承接模型 WebSocket／HTTP、总结／翻译调用和会议门面，是宿主唯一需要依赖的 AAR。
- 依赖设备 AAR、`meeting-client-core` 和 `model-runtime-contract`；业务 AAR 自带设备类、core、契约和 `.so`。
- 参考 App 的 sdk 模式用本模块的工程依赖；Starry App 使用预编译的 `apps/transcription/libs/ai-meeting-business-0.5.0.aar`。

## 构建

```bash
# 1. starry 仓先构建设备 AAR
./gradlew :base:aiMeetingSdk:assembleRelease

# 2. 本仓构建业务 AAR（设备 AAR 路径必填）
./gradlew :meeting-android:assembleRelease \
  -PdeviceSdkAar=/absolute/path/ai-meeting-sdk-0.5.0-release.aar

# 产物
meeting-android/build/outputs/aar/meeting-android-release.aar
```

AGP 不允许 Android library 直接依赖本地 `.aar` 再产出 AAR；构建时会把设备 AAR 解包成
`classes.jar` 和 `jni/` 再打包，因此业务 AAR 是自包含的。

## 可修改范围

| 部分 | 位置 | 能否修改 |
| --- | --- | --- |
| 模型传输、地址、鉴权、重连 | 本模块 `AndroidModelRuntime` 等 | 可以，改完重编业务 AAR |
| 总结／翻译调用、会议门面 | 本模块 | 可以 |
| 设备采音、投屏截图、native | 设备 AAR | 闭源，不可改 |
| 公共契约／SPI | `model-runtime-contract` | ABI 边界，改需配套新 AAR |

## 版本与依赖

- 契约 0.4.0、SDK 0.5.0；设备 AAR 与业务 AAR 成对升级。
- OkHttp 与宿主对齐：Starry 5.3.2，参考 App 5.3.2；裸 AAR 不传递 POM，宿主需显式提供 OkHttp 和协程。

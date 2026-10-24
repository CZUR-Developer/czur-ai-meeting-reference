# meeting-android（业务 AAR）

## 定位

- **设备 AAR**（starry 仓 `base/aiMeetingSdk`）闭源，只提供 MIC／Broker 采集、投屏截图、native 和 AIDL。
- **本模块**开源，承接模型 WebSocket／HTTP、总结／翻译调用和会议门面，是宿主唯一需要依赖的 AAR。
- 依赖设备 AAR、`meeting-client-core` 和 `model-runtime-contract`；业务 AAR 自带设备类、core、契约和 `.so`。
- 参考 App 的 sdk 模式用本模块的工程依赖；Starry App 使用预编译的 `apps/transcription/libs/ai-meeting-business-0.5.0.aar`。

## 构建

### 合作伙伴路径

设备 AAR 已随参考仓放在本模块 `libs/ai-meeting-device.aar`，构建不需要任何参数，
也不需要访问 Starry 私有仓：

```bash
./gradlew :meeting-android:assembleRelease

# 产物
meeting-android/build/outputs/aar/meeting-android-release.aar
```

要换成更新版本的设备 AAR，直接替换 `libs/ai-meeting-device.aar` 即可；
Gradle 只读这个路径，不接受 `-PdeviceSdkAar` 之类的构建参数。

### 内部维护路径

本仓维护者需要先从 Starry 仓构建设备 AAR，再执行上面的业务 AAR 构建；
`tools/build-meeting-business-aar.sh` 只做“设备 AAR → 业务 AAR → Starry libs”的内部同步，
不是伙伴构建入口。

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

- 契约 0.4.0、SDK 0.5.0；设备 AAR 与业务 AAR 成对升级。设备 AAR 固定放在
  `libs/ai-meeting-device.aar`，版本 0.5.0，SHA-256 `150f5a52294036b7e273a91d4d59afa7df212021bb6825e9199384023cd28053`，ABI：实际打包 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`，产品保证只有 ARM 两种。
- 业务 AAR 构造时校验设备 AAR 的 `AiMeetingSdkVersion.VALUE` 与自身编译版本；混用不同
  设备 AAR 会在初始化直接失败，而不是等到运行中出现 JNI／ABI 错误。
- OkHttp 与宿主对齐：Starry 5.3.2，参考 App 5.3.2；裸 AAR 不传递 POM，宿主需显式提供
  OkHttp 5.3.2 和 kotlinx-coroutines-android 1.11.0。

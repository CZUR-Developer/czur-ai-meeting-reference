# Android reference application

Open this directory in Android Studio when developing the reference meeting
application. The app consumes the public audio-source SDK and canonical
meeting protocols; it does not contain CZUR launcher or production-only UI
integration.

工程采用 Android Studio 默认结构：`app/` 是唯一 APK 模块，源码位于
`app/src/main/`，测试分别位于 `app/src/test/` 和 `app/src/androidTest/`。
应用通过 `system-audio-contract` 的公开接口读取音频；在普通 Android 设备上
可使用标准 MIC 或 Mock，Starry 设备再由厂商替换为签名 SDK 实现。

```bash
./gradlew :app:assembleDebug
./gradlew :app:test
```

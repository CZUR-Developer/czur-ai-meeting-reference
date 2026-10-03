# Android reference application

Open this directory in Android Studio when developing the reference meeting
application. The app consumes the public audio-source SDK and canonical
meeting protocols; it does not contain CZUR launcher or production-only UI
integration.

工程采用 Android Studio 结构：`app/` 是参考 APK，`model-runtime-contract/`
是模型 Adapter、会话和标准字幕事件的最小公共端口。P4-1 的 App 使用
`MockModelAdapter` 演示同一片段的初稿、修订稿、最终稿；点击“开始会议”后，
未完成文字以 80% 透明度显示，最终文字以 100% 显示。

当前 Mock 不采集 MIC、不连接后端；音频源、真实模型和后端会议接入分别在后续步骤完成。

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

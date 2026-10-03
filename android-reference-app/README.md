# Android reference application

Open this directory in Android Studio when developing the reference meeting
application. The app consumes the public audio-source SDK and canonical
meeting protocols; it does not contain CZUR launcher or production-only UI
integration.

工程采用 Android Studio 结构：`app/` 是参考 APK，`model-runtime-contract/`
是模型 Adapter、会话和标准字幕事件的最小公共端口。P4-1 的 App 使用
`MockModelAdapter` 演示同一片段的初稿、修订稿、最终稿；点击“开始会议”后，
未完成文字以 80% 透明度显示，最终文字以 100% 显示。

当前 Mock 不采集 MIC、不连接真实模型；音频源和真实模型在后续步骤接入。
点击开始会先用后端地址和本地 `reference-dev` 演示 Token 创建会议，
再把三次标准字幕修订异步送到后端。地址应填设备可达的局域网地址，不能在设备上
填写开发机的 `localhost`；Token 取自后端部署时自行设置的
`REFERENCE_DEV_IDENTITY_BEARER_TOKEN`，不写入 APK、日志或持久化配置。
三次字幕都保存后，“停止会议并查看结果”按钮才可点击。结果页地址单独填写
已部署的静态 Web `index.html` 地址；Stop 返回已受理后，App 在内置 WebView
打开含 `meetingId` 与单次生成的 `genTime` 的结果链接。Starry 设备无需外部浏览器。

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

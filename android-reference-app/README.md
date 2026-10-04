# Android reference application

Open this directory in Android Studio when developing the reference meeting
application. The app consumes the public audio-source SDK and canonical
meeting protocols; it does not contain CZUR launcher or production-only UI
integration.

工程采用 Android Studio 结构：`app/` 是参考 APK，`model-runtime-contract/`
是模型 Adapter、会话材料、生命周期和标准字幕事件的唯一公共源码。
默认 `mock` 模式不需要私有 AAR，可干净构建并演示同一片段的初稿、修订稿、最终稿；
未完成文字以 80% 透明度显示，最终文字以 100% 显示。

`sdk` 模式把 `starry` 独立 SDK Module 产出的版本化 AAR 从仓外注入。SDK 使用普通
Android 麦克风和 WebSocket 直连模型；本仓公开的 `TingwuModelAdapter` 编码
`StartTranscription`/`StopTranscription`，把模型 partial/final 消息归一化为标准字幕。
真实模式在创建并启动会议后调用独立的
`POST /v1/meetings/{meetingId}:modelSession` 领取材料：`adapterId`、完整的临时签名
`endpoint`、`expiresAt`、`initParams.taskId`，可选 `token`。Create 的 `realtime`
仍属于旧后端音频入口。`endpoint` 与
`token` 不会写入日志或结果。SDK 模式首次开会会请求麦克风权限。

P5-2 增加 `sample-json-ws` 第二格式样例：它的模型消息使用顶层
`type/segment/revision`，与听悟的 `header/payload` 格式不同；两者在
`ModelAdapterRegistry` 中构建期注册，后端 `modelSession.adapterId` 决定本场选用哪一个。
伙伴适配其他格式时新增 Adapter 并在注册表登记其实时能力，通用会议页面和 SDK
不用修改。参考 App 读取 Create 响应的 `capabilityDecisions`；未请求、模型不支持或
本地 Adapter 未登记的翻译/总结能力会在本场功能提示中关闭。会前选择和对应功能入口
在 P5-4～P5-6 完成；当前页面不会把能力提示伪装成可操作功能。

不要将闭源 AAR、真实模型地址或会话材料提交到本仓。`sdk` 构建明确要求外部 AAR；
缺失时配置阶段报错，不会退到 Mock。

```bash
# 干净检出默认 Mock
./gradlew :app:assembleDebug :app:testDebugUnitTest

# 从 starry 构建或交付渠道取得对应版本 AAR 后，在本机注入
./gradlew :app:assembleDebug :app:testDebugUnitTest \
  -PmeetingRuntime=sdk \
  -PmeetingSdkAar=/absolute/path/ai-meeting-sdk-0.1.0-release.aar
```

裸 AAR 不传递依赖元数据，`sdk` 模式显式添加 SDK 所需的 OkHttp 运行依赖。
SDK 从公共 `model-runtime-contract-0.2.0.jar` 编译，参考 App 只打包本工程
`model-runtime-contract` 的同版类，避免重复类。SDK 模式的最终验收还需要设备说话
产生真实字幕，并确认音频送达模型端点、后端只接收标准事件。

P5-4 的会前入口先填写设备可达的后端地址，以及部署者预置的终端用户账号和密码。
App 调用 `POST /v1/reference/session:login` 取得访问令牌，再以该令牌创建会议、
提交标准字幕事件和结束会议。令牌仅保存在本次进程内，切换账号时清除；账号密码和
令牌均不写入 APK、日志或持久化配置。登录地址须使用设备可访问的 HTTPS；本机调试
可以通过 `adb reverse tcp:8080 tcp:8080`，在设备上填写 `http://127.0.0.1:8080`
访问开发机。未做端口转发时，设备上的 `localhost` 不是开发机。参考账号与 CZUR
系统账号相互独立。
Mock 模式三次字幕都保存后可停止；SDK 模式随时可请求停止输入，等待模型最后一条
字幕后再向后端结束会议。结果页地址单独填写
已部署的静态 Web `index.html` 地址；Stop 返回已受理后，App 在内置 WebView
打开含 `meetingId` 与单次生成的 `genTime` 的结果链接。Starry 设备无需外部浏览器。

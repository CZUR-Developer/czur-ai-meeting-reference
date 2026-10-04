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
当前还可解析按国内版字段路径重建的听悟 `translate_result` 固定样例，并将译文与
原文片段关联，在应用内双栏和悬浮窗显示。会前现场互译入口在 SDK 构建中已开放：
后端本场能力决策允许翻译时，听悟启动消息会开启译文，App 提交标准译文事件，
Stop 后由后端生成字幕和译文结果。真实双向会议与 Web 读回仍待设备验收。
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
本地 Adapter 未登记的翻译/总结能力会在本场功能提示中关闭。会前页面已显示
这些模式，并将尚未接通的模式明确禁用；其他结果交付方式待 P5-6 接入。

不要将闭源 AAR、真实模型地址或会话材料提交到本仓。`sdk` 构建明确要求外部 AAR；
缺失时配置阶段报错，不会退到 Mock。

```bash
# 干净检出默认 Mock
./gradlew :app:assembleDebug :app:testDebugUnitTest

# 从 starry 构建或交付渠道取得对应版本 AAR 后，在本机注入
./gradlew :app:assembleDebug :app:testDebugUnitTest \
  -PmeetingRuntime=sdk \
  -PmeetingSdkAar=/absolute/path/ai-meeting-sdk-0.4.0-release.aar
```

排查真实模型字幕但设备 MIC 声学入口不确定时，可将同一 SDK Module 的
`ai-meeting-sdk-0.4.0-debug.aar` 注入上面的 `meetingSdkAar` 参数。调试 AAR 若在
参考 App 私有外部目录找到 `meeting-debug.wav`，会按实时节奏把该 16 kHz、单声道、
PCM16 WAV 发往模型；没有该文件就继续使用 MIC。录音仅通过设备调试工具临时放在
`/sdcard/Android/data/com.czur.aimeeting.reference/files/meeting-debug.wav`，
不可加入仓库或 APK。正式 release AAR 始终使用 MIC。此验证仍使用真实参考 App、
后端签发、听悟 Adapter、标准事件提交与结果页面；WAV 读完后在 App 点击停止。

裸 AAR 不传递依赖元数据，`sdk` 模式显式添加 SDK 所需的 OkHttp 运行依赖。
SDK 从公共 `model-runtime-contract-0.4.0.jar` 编译，参考 App 只打包本工程
`model-runtime-contract` 的同版类，避免重复类。SDK 模式的最终验收还需要设备说话
产生真实字幕，并确认音频送达模型端点、后端只接收标准事件。

会中模型会话、标准事件队列和跨应用字幕窗由 `MeetingService` 持有。用户在 App
前台点击开始时启动 microphone 前台服务；切换到其他 App 后，标准 MIC 与模型会话
仍由该服务持有，页面重新打开时订阅同一场字幕状态。设备上的后台连续采集尚待真实
会议验证。悬浮窗需要用户在系统设置中授予
“显示在其他应用上层”权限，页面和悬浮窗的停止操作进入同一服务收口流程。
Mock 构建使用同一服务流程，但选择不占用麦克风的 `dataSync` 前台服务类型。

Create 响应的 `limits.maxMeetingDurationMs` 是本场计时上限；会议页与悬浮窗读取
Service 的同一份“已进行 / 剩余”状态。上限超过 20 分钟时，剩余 20 分钟出现提示；
曾有有效字幕后连续 4 分钟无转写出现提醒，连续 5 分钟无转写或到达时长上限时，
进入现有 Stop 流程。非空原文 partial/final 会重置无转写计时。设备上的短会验收点是：
两处都能看到相同的已进行/剩余时间、字幕继续修订且可正常手动停止和打开结果；
分钟阈值由 `MeetingTimeRuleTest` 使用可控时钟验证，无需让设备空等五分钟。

悬浮窗的“收起”会变为右上角 Mini，点击 Mini 恢复到收起前的字幕窗或互译窗；
互译普通窗显示最新双栏，大窗显示最近的双栏记录，两种窗都读取同一份会议状态。
Mini 期间会议仍继续采音、收模型结果和提交标准事件，恢复后能看到新内容。
国内版字幕 Mini 虽调用 `setAsrSdkEnabled(false)`，当前 Cloud ASR AIDL 实现仅记录日志、
没有真正的暂停接口；参考版因此只实现窗口收起，不声称 Mini 暂停识别或计费。

P5-4 的会前入口先填写设备可达的后端地址，以及部署者预置的终端用户账号和密码。
App 调用 `POST /v1/reference/session:login` 取得访问令牌，再以该令牌创建会议、
提交标准字幕/译文事件和结束会议。两类事件共用本场 `clientSequence` 与单线程
提交队列；模型确认完成后，Stop 排在此前事件提交之后。令牌仅保存在本次进程内，切换账号时清除；账号密码和
令牌均不写入 APK、日志或持久化配置。登录地址须使用设备可访问的 HTTPS；本机调试
可以通过 `adb reverse tcp:8080 tcp:8080`，在设备上填写 `http://127.0.0.1:8080`
访问开发机。未做端口转发时，设备上的 `localhost` 不是开发机。参考账号与 CZUR
系统账号相互独立。
会前选择的两种会议语言分别进入 `languages.source` 和 `languages.targets`；现场互译
只在 SDK 构建中开放，且仍须本场后端能力决策和听悟 Adapter 同时允许。模型只有字幕
能力时，本场不会启动互译。“AI 会议纪要”仍不可用；真实双向互译已用调试录音在设备上完成会中与结果读回，实体麦克风声学效果仍待验。
互译分栏同时考虑会前语言、模型源语种、有效方向角、片段修订和用户交换栏位；P5
无有效方向角时按语种降级，实体方向角左右准确性留待 P6 验证。
结果方式目前只开放“网页查看”；本地、二维码、邮箱和无痕仍显示暂不可用，待 P5-6
接通实际业务后开放。实时字幕模式可选择演示文稿，系统授权录屏后由会议 Service
定时采集 JPEG，并在 Stop 前提交截图包；互译模式暂不开放演示文稿。用户点击开始时冻结本场设置；创建后
还会核对后端 `capabilityDecisions`，能力不满足时不会启动客户端会议。
Mock 模式三次字幕都保存后可停止；SDK 模式随时可请求停止输入，等待模型最后一条
字幕后再向后端结束会议。结果页地址单独填写
已部署的静态 Web `index.html` 地址；Stop 返回已受理后，App 在内置 WebView
打开含 `meetingId` 与单次生成的 `genTime` 的结果链接。Starry 设备无需外部浏览器。

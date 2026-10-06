# Android reference application

Open this directory in Android Studio when developing the reference meeting
application. The app consumes the public audio-source SDK and canonical
meeting protocols; it does not contain CZUR launcher or production-only UI
integration.

工程采用 Android Studio 结构：`app/` 是参考 APK，`model-runtime-contract/`
是模型 Adapter、会话材料、生命周期和标准字幕事件的唯一公共源码。
`meeting-client-core/` 保存共享 Adapter 工厂、默认解析和显示规则，详见其
[接入说明](meeting-client-core/README.md)。Mock 直接依赖 core；SDK 模式使用 AAR
内带的同一 core，App 不重复打包。Server 组合入口继续按 P6-6/P6-7 实装计划推进。
默认 `mock` 模式不需要私有 AAR，可干净构建并演示同一片段的初稿、修订稿、最终稿；
未完成文字以 80% 透明度显示，最终文字以 100% 显示。

`sdk` 模式把 `starry` 独立 SDK Module 产出的版本化 AAR 从仓外注入。SDK 在有兼容
共享音频接口的新 Starry 系统读取 Broker；旧 CZUR 系统无服务／接口不兼容，以及
普通 Android 设备使用标准麦克风，并通过 WebSocket
直连模型；本仓公开的 `TingwuModelAdapter` 编码
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
不用修改。登录后参考 App 读取既有 `GET /v1/capabilities`，将部署静态能力与
本地已装配的 Adapter 能力求交后开放会前模式；旧后端未实现发现接口时只保留
实时字幕入口。参考 App 仍读取 Create 响应的 `capabilityDecisions`，本场权益、
模型与降级结果以该决策为准；未请求或本场不可用的翻译/总结不会启动会议。
P5-6 的参考结果方式以网页、本地 PDF、邮箱链接和互译无痕为准；扫码接收由合作伙伴
自行开发，并通过会前条件 Hook 扩展。

`sdk` 模式已构建期登记公开的 `bailian-summary` 文本 Adapter。AI 会议纪要模式
在模型最终字幕到齐后，向已认证后端请求本场 `:summarySession`；后端只下发
百炼临时 Key、应用 completion 地址与过期时间。SDK 用短期 Bearer 向
`/api/v1/apps/{appId}/completion` 发送 `input.prompt`，Adapter 从 `output.text`
解析标准标题、概述、要点和待办；App 在 Stop 前提交标准纪要，后端不调用总结模型。
部署方须在后端配置百炼 AppId、永久 Key 及本场 `summary.structured` 能力，
永久 Key 不进入 App。当前仅以环回假 HTTP 和代码构建验证协议，真实百炼请求及
设备 App→后端→Web 纪要真链仍待验。

不要将闭源 AAR、真实模型地址或会话材料提交到本仓。`sdk` 构建明确要求外部 AAR；
缺失时配置阶段报错，不会退到 Mock。

```bash
# 干净检出默认 Mock
./gradlew :app:assembleDebug :app:testDebugUnitTest

# 从 starry 构建或交付渠道取得对应版本 AAR 后，在本机注入
./gradlew :app:assembleDebug :app:testDebugUnitTest \
  -PmeetingRuntime=sdk \
  -PmeetingSdkAar=/absolute/path/ai-meeting-sdk-0.5.0-release.aar
```

排查真实模型字幕但设备 MIC 声学入口不确定时，可将同一 SDK Module 的
`ai-meeting-sdk-0.5.0-debug.aar` 注入上面的 `meetingSdkAar` 参数。调试 AAR 若在
参考 App 私有外部目录找到 `meeting-debug.wav`，会按实时节奏把该 16 kHz、单声道、
PCM16 WAV 发往模型；没有该文件时，SDK 探测 CZUR／Starry 平台及共享音频接口兼容性，
兼容时选择系统共享音频，由系统处理 SELF/OTHER 切换；旧系统无兼容服务时选择标准 MIC。
普通 Android 平台使用标准 MIC。已探测到匹配接口描述符的 Broker 若实际开流失败，返回明确音频
错误，不在本场取流失败后切换到普通 MIC，避免掩盖新系统故障和破坏音源竞合。
新系统与 SDK 使用带 `clientToken` 的内部开流接口；共享供流、普通 MIC 双顺序和专业
录拍竞合已有设备证据。2026-10-06 新接口复验确认实际模式为 `CZUR_BROKER`、音频
帧持续增长，方向输出 `NO_DIRECTION` 与递增时间戳；撤销录音权限后直接调用 SDK 得到
`PERMISSION_DENIED`，没有权限弹窗且进程存活，恢复权限后可重开 Broker 并正常停止。
有效实体方向的左右准确性与物理旧系统的 MIC 降级仍未实测，P6 继续保持未整体签收。
录音仅通过设备调试工具临时放在
`/sdcard/Android/data/com.czur.aimeeting.reference/files/meeting-debug.wav`，
不可加入仓库或 APK。正式 release AAR 使用相同的自动音频选择，但不包含 WAV。
此验证仍使用真实参考 App、
后端签发、听悟 Adapter、标准事件提交与结果页面；WAV 读完后在 App 点击停止。

裸 AAR 不传递依赖元数据，`sdk` 模式显式添加 SDK 所需的 OkHttp 运行依赖。
SDK 从公共 `model-runtime-contract-0.4.0.jar` 编译，参考 App 只打包本工程
`model-runtime-contract` 的同版类，避免重复类。SDK 模式的最终验收还需要设备说话
产生真实字幕，并确认音频送达模型端点、后端只接收标准事件。

会中模型会话、标准事件队列和跨应用字幕窗由 `MeetingService` 持有。用户在 App
前台点击开始时启动 microphone 前台服务；切换到其他 App 后，SDK 音源与模型会话
仍由该服务持有，页面重新打开时订阅同一场字幕状态。设备上的后台连续采集尚待真实
会议验证。悬浮窗需要用户在系统设置中授予
“显示在其他应用上层”权限，页面和悬浮窗的停止操作进入同一服务收口流程。
Mock 构建使用同一服务流程，但选择不占用麦克风的 `dataSync` 前台服务类型。

Create 响应的 `limits.maxMeetingDurationMs` 是本场计时上限；会议页与悬浮窗读取
Service 的同一份“已进行 / 剩余”状态。上限超过 20 分钟时，剩余 20 分钟显示约
10 秒的中央提醒卡片；从开会起连续 4 分钟无转写时显示剩余 60 秒倒数，确认或
收到新转写后关闭，连续 5 分钟无转写或到达时长上限时进入现有 Stop 流程。
非空原文 partial/final 会重置无转写计时。设备上的短会验收点是：
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
能力时，本场不会启动互译。AI 会议纪要已接本地 Adapter，真实百炼材料与设备
结果仍待验；真实双向互译已用调试录音在设备上完成会中与结果读回，实体麦克风声学效果仍待验。
互译分栏同时考虑会前语言、模型源语种、有效方向角、片段修订和用户交换栏位；P5
无有效方向角时按语种降级，实体方向角左右准确性留待 P6 验证。

### SDK 音频来源、方向与权限边界

`ModelSessionListener.onAudioInputMode(AudioInputMode)` 返回本次实际来源：`MIC`
为标准 Android 麦克风，`CZUR_BROKER` 为兼容的新系统共享音频，`DEBUG_WAV` 为
仅 Debug 的测试文件。参考 App 在会中功能栏显示该枚举值；伙伴可用于状态展示，
无需自行探测系统。旧 CZUR 系统的 MIC 降级遵循该系统既有录音竞合能力，不具备新
Broker 的竞合保证；模式用于准确说明当前能力。

`onAudioDirection(AudioDirectionSample)` 返回原始／稳定角度、单调时间戳和
`AVAILABLE`／`NO_DIRECTION`／`UNSUPPORTED` 状态。SDK 只提供技术样本，App 的
`MeetingDisplayState.updateAudioDirection()` 更新全局稳定方向，业务 `DisplayRouter`
结合会前语言、模型语种和片段状态确定左右栏。`MeetingService` 将方向和字幕回调都
排入主线程队列；方向消失时清除旧全局角度，新片段按语种降级，已有片段不搬栏。

`onAudioError(AudioInputErrorCode, String)` 区分权限拒绝、开流失败与读取失败。
SDK 无权限时返回 `PERMISSION_DENIED`，不负责申请权限；参考 App 的开始按钮和诊断
正常入口检查并申请 `RECORD_AUDIO`，Service 只显示错误提示，不从后台弹权限框。
SDK 终止本场音频后，用户仍可停止会议；App 使用已收到的标准事件执行后端 Stop，
不等待已关闭会话的最终模型回调，不生成缺字幕的纪要或主动交付空结果。结束后返回
会前页面，由 App 正常申请权限并重新开始。
只实现旧 `onError(String)` 的调用方仍会由默认方法收到音频错误。

Debug `AudioDiagnosticActivity` 同时显示模式、方向状态、帧数和峰值；“SDK 权限自检
（不申请）”按钮只用于撤销权限后的设备验收，验证 SDK 返回错误且应用不崩溃。正常
诊断按钮仍由 App 请求权限，Release 不包含诊断页或测试音源。

本轮诊断设备证据保存于 `/home/cfy/Sync/p6-sdk-audio-contract-20261006/`：`06`／`07`
记录 Broker 模式、持续读帧、无有效声源的方向状态及正常停止；`11` 记录无权限错误
回调；`13`／`14` 记录恢复权限后的重新取流和最终停止。这些证据验证模式与方向状态
出口及 SDK 权限边界，不代签有效 DOA 左右分栏或物理旧系统的 MIC 取流。

### 参考结果方式

结果方式包含网页查看、本地 PDF、按后端 SMTP 配置开放的邮箱链接，以及仅现场互译
可选的无痕方式。参考 App 不提供扫码接收或小程序；需要接收者检查的合作伙伴可按下文
在会前开会条件 Hook 注入自己的实现。参考链路不新增录音归档，模型音频仍直连模型，
后端保存标准事件和已启用的结果产物。实时字幕模式可选择演示文稿，系统授权录屏后由会议 Service
定时采集 JPEG，并在 Stop 前提交截图包；互译模式暂不开放演示文稿。用户点击开始时冻结本场设置；创建后
还会核对后端 `capabilityDecisions`，能力不满足时不会启动客户端会议。
Mock 模式三次字幕都保存后可停止；SDK 模式随时可请求停止输入，等待模型最后一条
字幕后再向后端结束会议。网页和邮箱方式需要填写已部署的静态 Web `index.html`
地址；网页方式在 Stop 成功后由内置 WebView 打开含 `meetingId` 与 `genTime` 的
结果链接，邮箱方式发送同一链接。本地方式生成 PDF，互译无痕方式不主动交付结果。
这里的“无痕”沿用当前业务入口名称，当前参考实现仍向后端提交标准事件并保留结果；
它不承诺无服务端记录。合作伙伴若需要真正的不留存流程，须自行调整后端与 App，
不能仅依赖这个结果方式选项。
Starry 设备无需外部浏览器。

### 合作伙伴会前开会条件 Hook

`app/src/main/java/com/czur/aimeeting/reference/meeting/MeetingStartConditions.java`
是唯一需要修改的装配入口。参考实现返回始终放行的 `MeetingStartCondition`；合作伙伴
可新增自己的实现类，再让 `create(Context)` 返回该实现。`MainActivity` 在登录、模型能力
和结果输入检查之后，申请录屏权限与启动会议 Service 之前，调用
`check(MeetingSetup)`。返回 `Decision.block("请先完成接收者登记")` 时，页面显示该理由，
本场不会创建会议；返回 `Decision.allow()` 才继续原流程。后端 Create/Start 的权益
判断仍是权威检查，Hook 不能代替后端鉴权。

例如伙伴自行开发小程序接收时，可由其代码异步登记并缓存本场接收者人数，再在
`check(MeetingSetup)` 读取缓存并决定是否放行。Hook 在主线程调用，不能在检查方法中
同步请求网络或等待扫码轮询。伙伴需要自行开发扫码 UI、小程序和后端绑定；参考工程
不包含这些模块，也不因保留 Hook 而展示扫码结果方式。

# Android reference application

Open this directory in Android Studio when developing the reference meeting
application. The app consumes the public audio-source SDK and canonical
meeting protocols; it does not contain CZUR launcher or production-only UI
integration.

## 当前交付状态（2026-10-08）

P6核心音频底座和分层SDK已于2026-10-07完成对应新固件验收。真实Broker MIC字幕、
单向互译及正常Stop／同场Web已有设备证据；新固件生命周期收尾再次验证了字幕正常链。
实体左右方向准确性、物理旧系统降级、HDMI-IN／投屏／外设音源、真实百炼总结等具体范围
仍按原记录补验。下文P5／P6接入过程中的验证时点属于历史，不能当成全部场景已通过。

P7正在扩展参考产品：身份来源、可组合设置、结果动作与可选UI工具。当前新增设置将字幕／
互译与“会后生成会议纪要”分别选择；语言直接消费后端发现协议的`languages`，没有固定中英文列表。
结果动作整理后参考默认只提供Web；下文P5／P6的其他交付方式作为历史实现留痕。

工程采用 Android Studio 结构：`app/` 是参考 APK，`model-runtime-contract/`
是模型 Adapter、会话材料、生命周期和标准字幕事件的唯一公共源码。
`meeting-client-core/` 保存共享 Adapter 工厂、默认解析和显示规则，详见其
[接入说明](meeting-client-core/README.md)。Mock 直接依赖 core；SDK 模式使用 AAR
内带的同一 core，App 不重复打包。参考 App 已接入完整 `MeetingClient` 组合入口；
主机构建与迁移后设备验证的状态见 P6-7 过程记录，源码接线不代签设备验收。

P7-1a新增可注入的`IdentityProvider`及`MeetingClient.refreshIdentity()`。
默认账号密码登录更新`MeetingAppAssembly`持有的内存身份，Activity与Service共用；
进入页面／登录返回刷新状态，每次开始时SDK再次读取当前凭据并冻结为本场快照。
伙伴替换身份来源和App登录入口即可，SDK不引用CZUR个人中心、不退出设备系统账号。
接口及例子见[公共核心的身份接入](meeting-client-core/README.md#可替换身份与默认登录)。
默认 `mock` 模式不需要私有 AAR，可干净构建并演示同一片段的初稿、修订稿、最终稿；
未完成文字以 80% 透明度显示，最终文字以 100% 显示。

`sdk` 模式把 `starry` 独立 SDK Module 产出的版本化 AAR 从仓外注入。SDK 在有兼容
共享音频接口的新 Starry 系统读取 Broker；旧 CZUR 系统无服务／接口不兼容，以及
普通 Android 设备使用标准麦克风，并通过 WebSocket
直连模型；本仓公开的 `TingwuModelAdapter` 编码
`StartTranscription`/`StopTranscription`，把模型 partial/final 消息归一化为标准字幕。
当前还可解析按国内版字段路径重建的听悟 `translate_result` 固定样例，并将译文与
原文片段关联，在应用内双栏和悬浮窗显示。会前现场互译入口在 SDK 构建中已开放：
后端本场能力决策允许翻译时，听悟启动消息会开启译文，组合层提交标准译文事件，
Stop后由后端生成字幕和译文结果。2026-10-07实体MIC的字幕／单向互译及Web读回已有对应验收；
双向现场声源的完整效果仍待具体条件补验。
真实模式由组合层在创建会议后调用独立的
`POST /v1/meetings/{meetingId}:modelSession` 领取材料：`adapterId`、完整的临时签名
`endpoint`、`expiresAt`、`initParams.taskId`，可选 `token`。Create 的 `realtime`
仍属于旧后端音频入口。`endpoint` 与
`token` 不会写入日志或结果。SDK 模式首次开会会请求麦克风权限。

P5-2 增加 `sample-json-ws` 第二格式样例：它的模型消息使用顶层
`type/segment/revision`，与听悟的 `header/payload` 格式不同；两者在
`ModelAdapterRegistry` 中构建期注册，后端 `modelSession.adapterId` 决定本场选用哪一个。
伙伴适配其他格式时新增 Adapter 并在注册表登记其实时能力，通用会议页面和 SDK
不用修改。登录后参考 App 调用 `MeetingClient.discover`，直接读取部署与本地能力
已求交的 `availableCapabilities` 开放会前模式；发现失败时显示原因并保留账号切换入口，
不创建会议。组合层读取 Create 响应的 `capabilityDecisions`，本场权益、
模型与降级结果以该决策为准；必需能力不可用时不启动会议，可选能力不启用。
P5-6 的参考结果方式以网页、本地 PDF、邮箱链接和互译无痕为准；扫码接收由合作伙伴
自行开发，并通过会前条件 Hook 扩展。

`sdk` 模式已构建期登记公开的 `bailian-summary` 文本 Adapter。AI 会议纪要模式
在模型最终字幕到齐后，向已认证后端请求本场 `:summarySession`；后端只下发
百炼临时 Key、应用 completion 地址与过期时间。SDK 用短期 Bearer 向
`/api/v1/apps/{appId}/completion` 发送 `input.prompt`，Adapter 从 `output.text`
解析标准标题、概述、要点和待办；组合层在 Stop 前提交标准纪要，后端不调用总结模型。
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
有效实体方向的左右准确性与物理旧系统的MIC降级仍未实测，作为P6核心签收之外的具体补验项保留。
录音仅通过设备调试工具临时放在
`/sdcard/Android/data/com.czur.aimeeting.reference/files/meeting-debug.wav`，
不可加入仓库或 APK。正式 release AAR 使用相同的自动音频选择，但不包含 WAV。
此验证仍使用真实参考 App、
后端签发、听悟 Adapter、标准事件提交与结果页面；WAV 读完后在 App 点击停止。

裸 AAR 不传递依赖元数据，`sdk` 模式显式添加 SDK 所需的 OkHttp 运行依赖。
SDK 从公共 `model-runtime-contract-0.4.0.jar` 编译，参考 App 只打包本工程
`model-runtime-contract`的同版类，避免重复类。SDK模式的正常链验收目标是实体语音产生真实字幕、
音频直达模型、后端接收标准事件以及正常Stop／同场结果；2026-10-07已有对应新固件的签收记录。

会中 `MeetingService` 持有完整会议句柄、跨应用字幕窗与平台资源；模型、音频和
标准事件队列由 SDK 内的唯一协调器持有。用户在 App
前台点击开始时启动 microphone 前台服务；切换到其他 App 后，SDK 音源与模型会话
仍由该服务持有，页面重新打开时订阅同一场字幕状态。后台连续采集的具体设备验收范围以
对应记录为准，源码生命周期说明不代签场景验证。悬浮窗需要用户在系统设置中授予
“显示在其他应用上层”权限，页面和悬浮窗的停止操作进入同一 `MeetingSession.requestStop()`。
Mock 构建使用同一服务流程，但选择不占用麦克风的 `dataSync` 前台服务类型。

调试版参考 App（APK 的 `ApplicationInfo.FLAG_DEBUGGABLE` 已开启）在会议
`MeetingService` 存活期间支持以下正常停止命令；字幕持续增加时也可直接使用：

```bash
adb shell am broadcast -a com.czur.aimeeting.reference.DEBUG_STOP -p com.czur.aimeeting.reference
```

广播在主线程调用与页面、悬浮窗相同的 `requestStop()`，沿现有流程排空音频、等待
模型最终字幕、保存结果并按本场结果方式交付；网页方式仍打开同场结果页。这是正常
Stop，不是 `close()`、强停进程或可恢复的业务暂停。当前状态尚不能停止或已请求停止时，
沿用原 `canStop` 判断。普通 Release APK 不注册接收器；调试接收器随 Service
创建/销毁注册/注销，原 Service 仍不导出。此开关取决于 App 的调试标记，与注入的
SDK AAR 是 Debug 还是 Release 无关。

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
App调用`MeetingClient.login`更新集中装配的参考内存身份，Service用同一Provider创建独立会议客户端。
组合层封装 `POST /v1/reference/session:login`、建会、标准字幕/译文提交及结束会议。
字幕／互译两种会中呈现均可独立开启会后总结；用户选择的功能必须同时获本场授权。
旧`MEETING_MINUTES`调用保留“字幕＋总结”的语义，新页面使用独立checkbox。
两类事件在协调器内共用本场`clientSequence`与单线程
提交队列；模型确认完成后，Stop 排在此前事件提交之后。令牌仅保存在本次进程内Provider，切换账号时清除；账号密码和
令牌均不写入 APK、日志或持久化配置。登录地址须使用设备可访问的 HTTPS；本机调试
可以通过 `adb reverse tcp:8080 tcp:8080`，在设备上填写 `http://127.0.0.1:8080`
访问开发机。未做端口转发时，设备上的 `localhost` 不是开发机。参考账号与 CZUR
系统账号相互独立。
会前选择的两种会议语言分别进入 `languages.source` 和 `languages.targets`；现场互译
只在 SDK 构建中开放，且仍须本场后端能力决策和听悟 Adapter 同时允许。模型只有字幕
能力时，本场不会启动互译。AI 会议纪要已接本地 Adapter，真实百炼材料与设备
结果仍待验。P5使用调试录音验证双向互译会中与结果读回；2026-10-07随后补验了实体MIC
中文原文与英语译文的正常Stop／Web链。双向现场声学效果不由这次单向短会代签。
互译分栏同时考虑会前语言、模型源语种、有效方向角、片段修订和用户交换栏位；P5
无有效方向角时按语种降级，实体方向角左右准确性仍列在后续补验范围。

### SDK 音频来源、方向与权限边界

基础模型运行接口的 `ModelSessionListener.onAudioInputMode(AudioInputMode)` 和组合入口的
`MeetingListener.onAudioInputMode(AudioInputMode)` 均返回本次实际来源：`MIC`
为标准 Android 麦克风，`CZUR_BROKER` 为兼容的新系统共享音频，`DEBUG_WAV` 为
仅 Debug 的测试文件。参考 App 在会中功能栏显示该枚举值；伙伴可用于状态展示，
无需自行探测系统。旧 CZUR 系统的 MIC 降级遵循该系统既有录音竞合能力，不具备新
Broker 的竞合保证；模式用于准确说明当前能力。

`onAudioDirection(AudioDirectionSample)` 返回原始／稳定角度、单调时间戳和
`AVAILABLE`／`NO_DIRECTION`／`UNSUPPORTED` 状态。SDK 基础层只提供技术样本，唯一
公共 core 的 `MeetingDisplayState` 结合会前语言、模型语种、句开始方向快照及片段状态
确定左右栏。完整会议的协调器在内部串行处理句开始、方向、字幕与译文，Service 只通过
`onDisplay(MeetingDisplaySnapshot)` 接收已经配对及路由的不可变结果；页面/悬浮窗
用同一无状态 `DisplaySnapshotPresentation` 设置文本和透明度，不再处理原始方向。
`NO_DIRECTION` 保留上次有效稳定角，`UNSUPPORTED` 让新片段按语种
降级，已有片段不搬栏。听悟模糊方向区的迟到语种窗口通过独立deadline定时释放，
不被HTTP上传或秒级计时tick延长；停止即时冲刷待显示快照，真实語种不被显示侧改写。

`onAudioError(AudioInputErrorCode, String)` 区分权限拒绝、开流失败与读取失败。
SDK 无权限时返回 `PERMISSION_DENIED`，不负责申请权限；参考 App 的开始按钮和诊断
正常入口检查并申请 `RECORD_AUDIO`，Service 只显示错误提示，不从后台弹权限框。
SDK 终止本场音频后，用户仍可停止会议；组合层使用已收到的标准事件执行后端 Stop，
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
Mock 模式最终字幕保存后可停止；SDK 模式随时可请求停止输入，等待模型最后一条
字幕后再向后端结束会议。网页和邮箱方式需要填写已部署的静态 Web `index.html`
地址；网页方式在 Stop 成功后由内置 WebView 打开含 `meetingId` 与 `genTime` 的
结果链接，邮箱方式发送同一链接。本地方式生成 PDF，互译无痕方式不主动交付结果。
这里的“无痕”沿用当前业务入口名称，当前参考实现仍向后端提交标准事件并保留结果；
它不承诺无服务端记录。合作伙伴若需要真正的不留存流程，须自行调整后端与 App，
不能仅依赖这个结果方式选项。
Starry 设备无需外部浏览器。

### 完整会议入口与平台扩展点

`src/sdk/.../MeetingRuntimeFactory` 仅构造 `AiMeetingClient(context, config)`；Mock Factory
为同一 `MeetingCoordinator` 注入现存固定字幕/纪要运行端口，不增加第二套业务流程。
配置包含 Server 地址、登录 Token、客户端身份、会议档位和串行回调 Executor；Android
使用主线程 Handler。Activity 的会前客户端和 Service 的会议客户端彼此独立。

```java
MeetingClient client = MeetingRuntimeFactory.create(context, config);
MeetingSession session = client.start(setup.toOptions(), new MeetingListener() {
    @Override public void onDisplay(MeetingDisplaySnapshot snapshot) {
        // SDK 已决定片段配对、源侧和交换；UI 只渲染 final=100%、partial=80%。
    }
    @Override public void beforeStop(Runnable continuation) {
        // 停止周期屏采，在平台队列提交最后有效截图、关闭投影，再调用 continuation。
        continuation.run(); // 没有屏采时直接继续；详细接线见 MeetingService。
    }
    @Override public void onResult(MeetingResult result) {
        // WEB/邮箱消费 resultUrl；LOCAL_READY 的 typed content 在 App 生成 PDF。
    }
});
// 稍后用户点击停止，且最近 onState 的 state.canStop() 为 true 时调用：
session.requestStop(); // 正常收口；client.close() 仅用于取消/释放，不产生会议结果。
```

`start()` 异步创建会议并立即返回句柄；App 保存该句柄，过早的 Stop 不受理。

屏幕授权、周期 JPEG、PDF/MediaStore 和权限申请均在 App；截图通过
`session.submitPresentation(offsetMs, jpeg)` 交组合层保存。系统单独停止投影只结束
屏采，字幕会议继续。`onResult` 先于 READY 状态，LOCAL 的 PDF 队列在保存完成后才
停止 Service；邮箱失败保留 SDK 返回的同一个网页链接。合作伙伴可更换这些平台/UI
实现，或在 SDK Factory 传入自定义 Adapter 工厂注册表，继续复用同一组合流程。

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

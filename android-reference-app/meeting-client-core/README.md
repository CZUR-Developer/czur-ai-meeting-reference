# 会议客户端公共核心

本模块使用 Kotlin/JVM，实现共享 Server 协议、会议编排、模型 Adapter 注册和显示规则。
产物是 JVM 17 普通 JAR，供参考 Mock App 和 Android SDK 使用同一份实现。
Android 控件、权限申请、音源、模型网络传输、屏幕采集和本地 PDF 由平台层实现；
实时音频通过设备 SDK 直达模型。

首次接入见[从零指南](../../docs/partner-getting-started.md)，页面／Service 接线见
[Android 参考应用](../README.md)。Android 参考交付最低支持 Android 14（API 34）。

## 模块内容与能力边界

| 接口或包 | 职责 |
| --- | --- |
| `client.provider` | 听悟、JSON 实时样例、百炼总结与独立 JSON 文本翻译的协议适配 |
| `ModelAdapterRegistry` | 按后端 `adapterId` 创建本场独立 Adapter |
| `MeetingCapabilityState` | 保存 Create 返回的本场能力决策 |
| `DeploymentOptions.languages` | 透传后端发现协议的语言列表 |
| `client.display` | 统一字幕修订、句开始方向快照和 A／B 分栏 |
| `client.result` | 结果动作注册、会前准备、同场结果访问与执行 |

功能能力按本地可执行能力、后端部署和本场授权确定，不能从供应商品牌推断。
所有当前会议模式要求 `transcript.realtime`；互译另需 `translation.realtime`，
开启独立总结另需 `summary.structured`。用户选择的必需能力无法授予时，不启动本场模型。
本场分人为可选能力，未授予时移除发言人字段，仍可开字幕会议。

语言列表直接来自后端 `AI_MEETING_MODEL_LANGUAGES`，App 按发现结果显示。
核心不会查询供应商的实时语种清单，也不会自动将语言配置与 Provider／Product Profile
求交。部署者应声明实际模型支持且已开通的语种子集，语种变化时同步调整配置。
听悟 Adapter 将 `cn/en/ja/ko/de/fr/ru`（含 `JP/KR` 别名）归一化为
`zh-CN/en-US/ja-JP/ko-KR/de-DE/fr-FR/ru-RU`；映射存在不代表供应商为当前账户开放该能力。
未知语种保持未知，不默认当作中文。

## 两层入口与组合契约

基础层 `AudioInput` 交付标准 PCM 和物理方向，无需 Server 或模型。
组合层 `MeetingClient` 提供身份、能力发现和开始；`MeetingSession` 提供停止、
演示图片提交与显示列交换；`MeetingListener` 返回不可变显示快照、状态和结果。
Android `AiMeetingClient` 装配平台运行时，Mock 注入 `MeetingRuntimePort`，
二者共用 `MeetingCoordinator`。同进程基础输入、诊断和完整会议应择一持有真实音源。

正常链为当前身份读取 → 能力发现 → Create → 领取材料／创建 Adapter → StartClient →
采音／模型 → 标准事件确认 → 冻结采音用量 → 按需总结 → 录音发布 → 后端 Stop →
真实 READY 清单 → App 显式结果动作。后端负责身份、模型选择、权益／计费和存储。
模型完整地址、短期材料及本场授权均由后端下发。

`MeetingClientConfig` 接受 Server URL、`IdentityProvider`、`ClientIdentity`、
`MeetingProfile` 和**串行** `callbackExecutor`。Android 通常使用主线程 Executor，
避免结果与状态回调重排。`discover()` 提供匿名部署发现及本地可执行能力交集，
可用功能不等于本场已获授权。固定访问令牌构造方式可供简单集成，持久登录应使用身份插件。

HTTP 连接等待为 10 秒，普通 JSON 请求读取等待为 10 秒；实时模型、总结和独立翻译
材料请求读取等待为 30 秒，同源录音上传读取等待为 60 秒。这些是 socket 读取等待，
并非整场会议的总时限；停止音源不依赖普通结果 HTTP 请求完成。

### 可替换身份与默认登录

`IdentityProvider.currentIdentity()` 在 IO 队列返回已登录、需要登录或读取失败。
`IdentitySession` 携带本部署后端接受的 Bearer 和可选显示名。SDK 不打开登录页，
不读取特定厂商的私有账号接口，也不退出系统账号。

App 进入页面或登录返回时调用 `MeetingClient.refreshIdentity()`；开始会议时 SDK
再次读取，并冻结为本场 Server 客户端身份。刷新全局身份不会更改已开始会议的账号。

`ReferenceIdentityProvider` 是 JVM／Mock 的内存实现；参考 App 默认装配
`PersistentReferenceIdentityProvider`，将短期令牌、显示名、到期时间和后端归属
保存到应用私有设置，不保存密码。有效会话可在进程重启后恢复，过期／主动退出清除。
伙伴可替换成 ContentProvider、系统账户或自有凭据仓库：

```kotlin
val identity = IdentityProvider {
    val token = myAccount.currentMeetingToken()
    if (token == null) IdentityResult.loginRequired("请登录伙伴账号")
    else IdentityResult.loggedIn(IdentitySession(token, "伙伴账号"))
}
val config = MeetingClientConfig(
    serverUrl, identity, clientIdentity, productProfile, mainThreadExecutor)
```

默认 `MeetingClient.login()` 通过 `LoginIdentityProvider` 写入后端登录会话。
伙伴使用自己的登录页面时，可在成功后将后端认可的会话写入身份插件，再调用
`refreshIdentity()`；打开表单、取消或登录失败时不应提前清除有效身份。
身份插件与 App 的登录 UI 分开装配，Activity 和 Service 使用同一身份来源。

### 生命周期、回调与线程

同一客户端同时只允许一场未完成会议，完成后可开下一场。
`session/client.close()` 幂等取消，不执行后端 Stop 或生成 READY；client 关闭后不能复用。
listener 和会前回调交给配置的串行 Executor，关闭后不再派发尚未执行的通知。
App 负责权限、前台服务、计时／提醒、页面／窗口及屏采授权。

| 类型／回调 | 可使用的事实 |
| --- | --- |
| `MeetingStarted` | 本场单调 `startedAtMs`、`maxMeetingDurationMs` 和实际能力 |
| `MeetingSessionState` | 阶段、`active`、`canStop`、能力及已确认原文事件数量 |
| `onDisplay` | 不可变原译文快照、语义 A／B、`swapped` 和模型真实语言 |
| `onTranscriptActivity(nowMs)` | 非空原文活动，不等待迟到语种显示窗口 |
| `onAudioInputMode/onAudioError` | 实际音源与类型化音频错误 |
| `onAudioLevel` | 同流 0～100 RMS 显示电平及单调测量时刻 |
| `onResult` | 真实 READY 清单，在 READY 状态回调前到达 |

Coordinator 将状态／显示、HTTP／总结和模型控制分开串行处理；慢 HTTP 不阻塞
句开始、方向或语种截止点。`modelControl` 顺序调用模型 start／stop／close。
完整会议的音量回调使用 `callbackExecutor`；基础 `AudioInput.Listener` 的电平在采音线程
交付，应快速返回并自行切到 UI。SDK 约每 300ms 采样一个 100ms PCM 帧，
将 RMS 相对满幅 -60～0dB 映射为 0～100；该值用于显示，不等于环境分贝。

### 模型请求描述与独立文本翻译

`ModelHttpRequest` 描述方法、头、内容类型和正文，不执行网络。
`ModelAdapter.connectionRequest(grant)` 默认 GET，加上非空 token 的 Bearer；
`SummaryAdapter.request(grant, input)` 默认 POST／JSON，正文来自 `requestBody(input)`。
伙伴可覆盖请求描述，SDK 按描述发送，不额外补充 Bearer。签名 URL 可不提供 token；
自定义 `x-api-key`、版本头或请求正文由 Adapter 使用本场 `grant` 构造。

Adapter 输出事件的时间是该模型任务已收音频的相对毫秒。SDK 再加一次本次连接首帧
偏移，转换为会议录音的相对时间；Adapter 不要自行叠加会议／续接偏移。

仅 ASR 加独立文本翻译时，后端注入独立翻译签发器并声明翻译能力和实际语言，
在 `modelSession` 中附 `translationSession`（`adapterId/endpoint/expiresAt/token/initParams`）。
实时模型关闭原生翻译，仍只开一场会议和一路音源。未装配 sidecar 的听悟使用原生译文。

```kotlin
val adapters = ModelAdapterRegistry()
adapters.registerTranslation("partner-translation", ::PartnerTranslationAdapter)
```

`TranslationAdapter` 在 IO 队列处理已确认原文的文本请求／响应。
`TranslationInput` 含原文 ID、revision、源语种、目标语种和正文；
`input.result(text, detectedSourceLanguage)` 返回关联原文的标准 `TranslationEvent`。
根据真实源语种在会前两种语言间选择目标，交换显示列不改变翻译目标。
相同 final 修订不重复调用，更新修订可再翻译。

`sample-json-translation` 发送 `sourceSegmentId/sourceRevision/sourceLanguage/targetLanguage/text`，
响应为 `{"text":"译文","sourceLanguage":"实际源语种（可省略）"}`。
可选 `tokenHeader` 默认 Authorization，`tokenPrefix` 对 Authorization 默认为 Bearer，
其他头默认空。该协议样例需要部署者提供相应服务，不自带真实翻译模型。

停止先关闭音源，再等待已接纳的译文保存，按顺序继续总结、发布和 Stop。
翻译失败保留原输入，用户再次确认停止时可重试，不伪造译文或 READY。
长期会议在下一次翻译前检查材料到期并同场续领，不后台轮询或重建 ASR。

### 本场建会参数与录音

`MeetingOptions` 将会中 `mode`、独立 `summaryEnabled`、结果动作、语言和截图设置分开。
`Mode.MEETING_MINUTES` 表示字幕加总结；`LIVE_TRANSLATION` 加 `summaryEnabled=true`
表示互译加总结。总结在最终字幕保存后、后端 Stop 前生成。

`withCreateParameters(title, timezone, retainAudio)` 返回不可变新快照，保留其他选项。
`title=null` 时省略标题字段，时区显式提供。直接构造 `MeetingOptions` 默认标题
`参考会议`、时区 `Asia/Shanghai`、`retainAudio=false`；参考 App 的 SDK 装配另通过
`MeetingSetup.toOptions()` 明确设为 `true`，Mock 则为 `false`。

```kotlin
val named = selected.withCreateParameters("客户会议", "Europe/Paris", true)
// retainAudio=true 需要同时装配同源录音 Runtime 和产物发布器。
```

Runtime 通过 `ModelRuntime/MeetingRuntimePort.supportsRetainedAudio()` 声明能力，
`ModelSession.sealedAudioArtifact()` 提供停止、尾帧排完和编码封口后的本地音频事实。
`SealedAudioArtifact` 包含本场 ID、格式／MIME、文件长度、实交 PCM 字节数及派生时长。
同一 16kHz／mono／PCM16 流同时送模型和录音，编码 padding 不计用量。

通过 `MeetingClientConfig.withArtifactPublisher(...)` 装配发布器。发布器只读取本场
冻结身份、sealed 产物和用量；正常返回表示上传及必要登记已确认，随后执行后端 Stop。
缺失 sealed 事实、发布失败或 Stop 未确认时保留 `STOP_PENDING`，用户可继续原场收尾。
发布已确认后不重复发布，不重开音源、建会或重复计费；具体外部登记幂等由发布器实现。

### 跨会议人物身份

`speaker.identity.cross_meeting` 是服务端会后能力，不属于实时 Adapter 解析清单。
只有部署声明、本场分人、Runtime 同源录音、已装配发布器及本场 `retainAudio=true`
均满足时才请求；Create 授予后还会核对本场选中 Adapter 的分人能力。
真实身份以服务端 `speaker.identity` 产物为准，不能将 `sessionSpeakerId` 当作跨场 `personId`。
关闭录音会同时移除该请求；缺少此能力不阻断其他已授权功能。

### 可注册结果动作

`ResultActionPlugin` 声明稳定 ID、名称、必需产物和后端服务；
`ResultActionRegistry` 是 App 开放清单，不隐式安装插件。
会前 `prepare` 只读取已准备的本地状态并冻结闭包，网络／扫码须提前异步完成。

`serviceCapabilities(discovery)` 当前将链接期限映射为 `result.link`、邮件开关映射为
`result.email`。新增后端专属依赖需要一起扩展发现协议和映射，声明服务不等于安装 App 动作。
纯本地动作可以不依赖后端服务，但仍可要求真实产物。

```kotlin
val registry = ResultActionRegistry(listOf(myNoticePlugin))
val prepared = registry.prepare("partner.notice", options, discovery)
if (!prepared.allowed()) showMessage(prepared.message())
// 仅在已确认同场 READY 后执行，平台工作／UI 队列由 App 提供。
val context = ResultActionContext(readyResult, session.resultActionAccess(),
    ResultActionRegistry.serviceCapabilities(discovery), platformWork, mainExecutor)
registry.execute(requireNotNull(prepared.action()), context, actionCallback)
```

正常 `MeetingResult` 仅包含 Manifest，`content()` 和 `resultUrl()` 为 `null`，
`deliveryStatus()` 为 `NOT_REQUESTED`。这表示会议已就绪但尚未由插件交付。
Web／纯提示动作无需正文；PDF／离线 HTML 必须在 READY 后通过窄访问异步读取：

```kotlin
context.access().readContent(object : MeetingClient.Callback<MeetingResultContent?> {
    override fun onSuccess(content: MeetingResultContent?) {
        context.workExecutor().execute {
            // 根据 content.artifactTypes() 渲染实际存在的章节；未提供的能力不伪造内容。
            // 在此生成本地 HTML／PDF，打开界面时切换到 context.uiExecutor()。
        }
    }
    override fun onError(error: MeetingError) {
        // 按 error.code 结束本次动作并提示可重试；会议本身仍保持 READY。
    }
})
```

上例省略插件完成回调和平台文件处理；完整实现见
[`LocalPdfResultActionPlugin.kt`](../app/src/main/java/com/czur/aimeeting/reference/LocalPdfResultActionPlugin.kt)。
请求沿本场 IO 队列执行，回到串行 `callbackExecutor`；访问内部使用开始时冻结的身份和
`genTime`，不向插件暴露 token 或原 Server client。读正文、发邮件或 UI 动作失败不回退 READY，
可用原结果和冻结动作再执行。交互结束才关闭 client，关闭后不再派发访问回调。

`NO_ACTIVE_DELIVERY` 正常 Stop 后直接 ENDED，不读取 Manifest 或主动动作；
标准事件、总结和用量仍按选项保存，不承诺删除服务端数据。
`ResultDelivery`／`DeliveryStatus` 等枚举入口仍可使用，动作 ID 需显式注册相应实现。

### 正常停止与结果

只有 `canStop=true` 时受理 `requestStop()`。先冲刷显示，并调用
`beforeStop(continuation)`；App 停止周期截图、结束末帧和采集后调用 continuation，
无截图时直接续接。continuation 仅首次有效，不要在主线程等网络或遗漏续接。

截图使用 `submitPresentation(offsetMs, jpeg, callback)`，时间相对 `startedAtMs`。
后端 ACK 的序号、单帧数量和产物 ID 确认后才调用 `onSuccess`；无回调入口仅表示排队。
App 只有成功时才确认 SDK 图片被接受。无新图或截图失败仍需关闭采集、继续停止。
公共核心负责序号和请求上限，App 不复制图片去重或编码算法。

标准字幕／译文共用 `clientSequence`；未确认事件保留同序号和内容，后续提交或正常
停止先补交队首，确认后再推进，复用后端幂等。该补交只覆盖当前宿主内存，不提供磁盘队列
或进程恢复。`savedEventCount` 只计已确认原文事件，不因译文或重复提交增加。

随后停止同一音源、排完尾帧并等待模型最终事件，按顺序确认事件、冻结用量、总结、
音频发布和后端 Stop。PCM16 的时长为完整字节数／32 的整数毫秒，静音、模型连接前采音
和尾帧计入；网络重发与会后等待不计入。没有实采用量的 Mock 不提交虚构的零。
无有效最终字幕时跳过总结并提示，仍可发布真实录音和正常结束，不制造空总结。

未确认的阶段保留 `STOP_PENDING/canStop=true`，再次停止沿用原场和已确认阶段。
Stop 受理后生成一次 `genTime`，Manifest、产物、链接和邮件共用；受理不等于 READY。
`onResult` 先交付真实清单，随后的 READY 状态才允许 App 派发动作。
状态包括 CREATING、RUNNING、STOPPING、FINALIZING、STOP_PENDING、READY、ENDED、FAILED；
STARTING／CLOSED 枚举未作为独立状态通知。

### 连接续接与可选收尾记录

`ModelSessionReconnector` 使用本场冻结 Server、身份和会议 ID 重新领取模型材料，
再创建新 Adapter，不重建会议。`supportsSessionReconnect()` 默认 false；支持的 Adapter
可通过 `drainConfirmedOnDisconnect()` 交出真实已确认原文，不把 partial 当作 final。
听悟已声明支持并保留收到 `SentenceEnd` 的原文。Android Runtime 的材料请求需独立
工作线程，Stop 不等待迟到 HTTP；连接重建不提供进程级恢复保证。

`withStoppedMeetingStore(store)` 是可选持久化接缝；参考 App 已在
`MeetingAppAssembly.clientConfig` 中接入 `ReferenceStoppedMeetingStore`。
伙伴可替换为自有实现，或按需不装配。
尾事件、用量、选定总结已确认且音频封口后保存 `StoppedMeetingCheckpoint`；
宿主重建调用 `resumeFinalization(id, callback)` 只续原场发布、Stop 和 READY，
不重新采音或总结。Store 由部署者管理，不由核心扫描或删除音频目录。
`StoppedMeetingCheckpoint` 同时保存原场交付策略：`NO_ACTIVE_DELIVERY` 续办在 Stop
成功后只推进到 ENDED，不读取结果清单，成功回调仍是 `FinalizationResult`，但
`result()` 为 null、`delivered()` 为 false；旧停止记录没有策略字段时按主动交付处理。

`IdentityProvider.identityForFinalization(meetingId, originalIdentity)` 可在原场再次收尾时
提供同账号新会话，默认返回原身份。部署者须自行确认原账号归属，不能只比较显示名。
新的材料同步用于发布、Stop 和 Manifest，会议 ID、用量、幂等键、已确认阶段和
`genTime` 不变；此接口不引入后台自动登录或跨账号恢复。

### 默认听悟与国内显示

听悟按同一 `index` 替换完整 partial，原文／译文 final 按源句顺序关联。
`stash_result` 保留下一句 ID 和起点；迟到源语种可修订标准事件，已确认正文不被旧
partial 覆盖。模型结束时，已确认但缺译文的原文仍保留。

`onSegmentStarted()` 绑定句开始方向：明确 A 区 180～260°、B 区 280～360°。
`NO_DIRECTION` 保留上次有效角，`UNSUPPORTED` 按语种降级；新句采样不移动旧句。
Adapter 通过 `mayEmitDeferredSourceLanguage()` 声明源语种可能迟到；互译且方向模糊时
最多等待 1.5 秒，期间只保留最新快照，可信语种到达即释放。到期或 Stop 时按会前 A／B
语种评分兜底，平分选 A。听悟已声明该行为，工厂 ID 或供应商品牌不决定等待规则。

基础显示使用 `nextLanguageDeadlineMs()`、`releaseExpiredLanguages(nowMs)` 和
`flushPendingLanguages()`；状态更新应在同一串行上下文。组合入口已自动调度，
App 只消费不可变 `MeetingDisplaySnapshot`，按 final 100%／partial 80% 呈现。
`latestTranscriptSegmentId` 指最近可见原文更新，旧段迟到修订不改变首次排序。

```kotlin
val adapters = ModelAdapterRegistry()
adapters.register("partner-json", ::PartnerAdapter, setOf("transcript.realtime"))
// 将注册表交给 AiMeetingClient；后端本场 adapterId 决定实际工厂。
```

实时传输当前为 WebSocket、16kHz／单声道／PCM16。Adapter 抹平消息格式差异，
其他传输和音频编码需另行评估平台支持。日志不输出正文、音频、材料 URL 或 token。

## 构建和依赖

在 `android-reference-app/` 执行：

```bash
./gradlew :model-runtime-contract:jar :meeting-client-core:jar :meeting-client-core:test
```

产物位于本模块 `build/libs/meeting-client-core-0.1.0.jar`，不内带
`model-runtime-contract` 或 `org.json`。Mock 依赖本模块源码；SDK 模式消费主 AAR 内带
的同源 core，不重复打包。契约通过本工程打包一份，Android 提供 JSON 类。
Kotlin 模块依赖会传递标准库；以裸 JAR 交付给未启用 Kotlin 插件的宿主时，需显式提供
`org.jetbrains.kotlin:kotlin-stdlib:2.4.21`。

`MeetingRuntimePort` 是平台／Mock 装配边界，无需业务 App 重写会议流程。
Android 的单调时间来自 `SystemClock.elapsedRealtime()`；自定义 Runtime／Mock 应使用
同一时基。构建、固定样例和单元测试不替代模型、部署和设备场景验收，当前范围见
[验证范围](../../docs/validation-scope.md)。

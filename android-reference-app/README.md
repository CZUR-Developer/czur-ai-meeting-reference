# Android 参考应用

本工程提供可编辑的会议 App 源码，配合 Android SDK、后端和结果 Web 完成登录、
实时字幕、双语互译、会后总结及结果回看。合作伙伴可替换身份、模型、结果动作和页面，
继续复用同一套会议编排。

首次接入请阅读[从零指南](../docs/partner-getting-started.md)；第三方平台、所需资源、
能力开关与缺少能力时的表现见[模型与能力指南](../docs/model-and-capability-guide.md)。
接口扩展见[接入手册](../docs/partner-integration-guide.md)。

## 支持环境与模块

最低支持 **Android 14（API 34）**。工程使用 compile／target SDK 35、Kotlin 2.4.21、
Gradle 8.7 和 Android Gradle Plugin 8.5.2，目标 JVM 为 17。Android 自有应用层、
公共会议核心、模型契约和测试使用 Kotlin。Lint 使用 9.4.0，配置见 `gradle.properties`。
这是当前验证矩阵；Gradle 8.7 已被后续 Kotlin 版本标记为过时，AGP 8.5.2 对 compileSdk 35
有上游警告，升级构建链需重新跑单测和真机回归。

| 模块或交付物 | 职责 |
| --- | --- |
| `app/` | 可编辑的页面、身份与结果插件、权限、前台 Service、计时和截图时序 |
| `model-runtime-contract/` | 模型 Adapter、会话材料、音源状态和标准事件契约 |
| `meeting-client-core/` | 共享 Server 协议、会议编排、Adapter 注册和显示规则 |
| `meeting-android/libs/ai-meeting-device.aar` | 随仓交付的闭源设备 AAR：系统／标准 MIC 音源、同源录音及截图能力、native；不含 core 或契约 |
| `meeting-android/`（业务 AAR） | 模型连接与会议门面；构建时注入设备 AAR，产物是宿主唯一需要集成的会议 AAR；运行时仍需宿主提供 OkHttp 与 coroutines（见下） |
| 设备 AAR 内置 UI | `com.czur.aimeeting.ui.SubtitleTextView` 字幕文字显示控件 |

设备 AAR 随本仓放在 `meeting-android/libs/ai-meeting-device.aar`：版本 0.5.0，
SHA-256 `150f5a52294036b7e273a91d4d59afa7df212021bb6825e9199384023cd28053`，ABI 实际打包 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`（产品保证只有 ARM 两种），含 `classes.jar`、`jni/`、`proguard.txt`；
业务 AAR 由本仓 `meeting-android/` 源码构建。构建不需要任何 `-P` 参数。设备 AAR 与业务
模块初始化时做版本握手；宿主直接使用裸业务 AAR 时，还需显式提供 OkHttp 5.3.2 和
kotlinx-coroutines-android 1.11.0。无需访问 CZUR 产品源码仓；模型 Key、真实会话材料和
账号密码不放进构建配置。具体功能、版本配套与供应商账户准备方式见[从零指南](../docs/partner-getting-started.md)。

## 部署配置与构建（代码即配置）

后端和结果页地址来自仓外本地配置：

`deployment.local.properties`（模板是同目录的 `.example`，真实文件已被 Git 忽略），
两个键 `backendUrl`、`resultWebUrl`，构建时注入 `BuildConfig` 由
`ReferenceDeployment.kt` 读取。构建不接受 `-P` 参数，终端用户不需要在设备上填写。
缺文件或缺键时构建直接失败，不会产出指向占位地址的包；多环境用
`REFERENCE_DEPLOYMENT_FILE` 选择另一份文件。模型地址和短期材料由后端下发，
这里不包含模型 Key 或账号密码。

Debug 构建如需信任本机自签 CA，把 PEM 放到
`app/src/debug/res/raw/reference_debug_ca.pem`（已被 Git 忽略，生成方式见
[`../docs/local-lan-https.md`](../docs/local-lan-https.md)）；`build.gradle.kts` 只在
该文件存在时才生成 `debug-overrides`。Release 不信任开发 CA。

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

设备 AAR 已随仓放在 `meeting-android/libs/ai-meeting-device.aar`；`:meeting-android` 在构建时
解包它的 `classes.jar`、`jni/` 和 `proguard.txt` 一起打进业务 AAR，版本握手保持。最小混淆
验证固定用 `./gradlew :app:assembleMinifiedDebug`，不再传参数。

只构建业务 AAR 时不需要 `deployment.local.properties`：

```bash
cd android-reference-app
./gradlew :meeting-android:assembleRelease
```

`settings.gradle.kts` 只有在 `deployment.local.properties`（或
`REFERENCE_DEPLOYMENT_FILE` 指定的文件）存在时才纳入 `:app`，所以干净 clone 不会被参考
App 的部署校验拦住；需要构建 App 时再复制模板并填写两个地址。

干净上下文只需要：仓库（含设备 AAR）、Android SDK/JDK、可访问的后端和结果页、
参考账号；设备在 PC 所在局域网内。没有这些运行时条件，APK 仍然能编译，但会因发现不到
能力和语言而无法开会。

## 默认参考功能与接入边界

默认页面包括会前登录和设置、实时字幕／双语互译、独立的“生成会议纪要”开关、
按能力开放的语言与演示截图设置，以及正常停止后的同场 Web 结果。
后端只声明 `transcript.realtime` 时，页面隐藏未装配的总结和互译入口，仍可完成字幕会议。
界面语言列表直接使用后端发现的 `languages`；部署者须配置实际模型支持的语种子集。

参考 App 固定 SDK 模式，装配同源录音和 `HttpMeetingArtifactPublisher`，并设置
`retainAudio=true`。录音在停止后上传到参考后端，实时音频仍由设备直达模型。
是否生成总结、翻译、演示产物和跨会议身份，取决于实际装配、声明、用户选择及本场授权。
用户已选的互译／总结能力无法授予时，该场不会启动。

参考账号与 CZUR 系统账号独立。会员、充值、Launcher、小程序和系统个人中心不在参考包中。
默认只注册 Web 结果动作，邮件、PDF 和伙伴自有通知须显式装配；部署声明邮件能力不会
自动生成 App 入口。离线 HTML 等结果方式可使用同一结果插件接口扩展。

当前功能的验证范围和硬件专项边界见[验证范围](../docs/validation-scope.md)。合作伙伴
更换模型、账户、语言、存储或设备后，应按[从零指南](../docs/partner-getting-started.md)
验证自己的部署。构建和 SDK 截图 API 的验证分别说明其范围，不替代实际模型／
硬件场景及完整演示会议产物验证。

## 页面、登录与集中装配

`MeetingAppAssembly` 是身份、登录入口、模型、结果动作和页面／窗口工厂的集中装配点。
默认身份插件 `PersistentReferenceIdentityProvider` 将短期访问令牌、显示名、到期时间和
后端归属保存在应用私有设置，不保存密码。进程重启恢复有效登录；过期、退出或切换后端
清除对应身份。同一后端升级保留有效登录，只更换结果 Web 地址不会注销账号。

未登录时账号按钮打开参考登录表单，已登录时交给 `AppLoginEntry.signOut`（默认直接退出）；
取消或登录失败保留原会话，成功才替换。App 进入页面、
登录返回时刷新身份；开始时 SDK 再读取并冻结本场凭据。会中切换全局账号不会改变已开始
会议的归属。伙伴可在 `MeetingAppAssembly.initialize` 后通过 `registerIdentityProvider`
和 `registerLoginEntry` 替换身份与登录 UI，接口见
[公共核心的身份接入](meeting-client-core/README.md#可替换身份与默认登录)。

`MeetingPageViews.Factory` 只创建／绑定 View，页面继续使用相同的会议状态。
默认起始页使用普通、不透明的 Activity，采用蓝白配色；会议设置与会后结果分区，
账号入口位于顶部，状态与开始入口固定在底部。登录表单仍可独立使用对话框。
`ReferenceUiScale` 按页面实际内容区局部映射 1920×1080 设计尺度，不修改系统
`DisplayMetrics`。Start／Stop 固定在滚动内容之外，会中仍交给 Service 的真悬浮窗。
可编辑公开 XML，或在首次创建 Activity 前注册薄工厂：

```kotlin
MeetingAppAssembly.registerPageFactory { activity ->
    MeetingPageViews(activity.layoutInflater.inflate(R.layout.partner_meeting_page, null) as FrameLayout)
}
```

自定义布局提供相同控件 ID 即可复用 Activity 接线。原文白色、译文黄色，同一发言方
原译同侧；页面与窗口共用已路由快照，不自行重做模型解析、方向路由或片段修订。
`result_settings_host` 保留给结果插件提供自己的设置 View；`setup_action_slot` 承载开始／
重新确认停止按钮，需放在滚动区域之外。`summary_setting`、`presentation_setting` 是
可选显示分组，随对应能力或模式隐藏，不另行保存业务状态。
资源来源见[页面资源清单](app/UI-RESOURCE-SOURCES.md)和[窗口资源清单](docs/czur-overlay-resources.md)。

## 完整会议入口与平台扩展点

`src/main/.../MeetingRuntimeFactory` 创建 `AiMeetingClient` 和同源录音输出。Activity 的会前客户端和 Service 的会议客户端
分别创建，共享身份与装配。每个客户端同时持有一场未完成会议，关闭后不可重用。

模型 Adapter 在创建客户端之前登记，每场创建新实例。地址、材料和本场 `adapterId`
来自后端，不写入 App 的部署配置：

```kotlin
val adapters = ModelAdapterRegistry()
adapters.register(PartnerModelAdapter.ID, ::PartnerModelAdapter,
    setOf(MeetingCapabilityState.TRANSCRIPT))
MeetingAppAssembly.registerModelAdapters(adapters)
// 总结／独立文本翻译分别通过 registerSummary／registerTranslation 登记。
```

内置听悟 `tingwu-realtime` 负责实时原文、原生译文与本场发言人归一化；
`sample-json-ws`／`sample-json-translation` 是协议样例，需要伙伴实现对应服务。
`bailian-summary` 通过后端签发的短期材料直连百炼应用 API，以 `input.prompt` 请求，
从 `output.text` 解析标题、概述、要点和待办。永久 Key 只在后端配置。
模型替换、文本翻译和显示规则见[公共核心说明](meeting-client-core/README.md)。

```kotlin
val client = MeetingRuntimeFactory.create(context, config)
val session = client.start(setup.toOptions(), object : MeetingListener {
    override fun onDisplay(snapshot: MeetingDisplaySnapshot) {
        // SDK 已完成配对和路由；UI 渲染 final=100%、partial=80%。
    }
    override fun beforeStop(continuation: Runnable) {
        // 有屏采时：停周期采集，等保存确认，完成末帧及关闭，再调用 continuation。
        continuation.run() // 无屏采时直接继续。完整接线见 MeetingService。
    }
    override fun onResult(result: MeetingResult) {
        // 保存真实 READY 清单；在 onState(READY) 后执行会前冻结的动作。
    }
})
// 最近状态 canStop() 为 true 时正常结束。
session.requestStop()
```

`start()` 异步建会并立即返回句柄。`requestStop()` 正常收尾；`close()` 取消和释放资源，
不替代 Stop，也不生成结果。标准事件、冻结用量、选定总结、录音发布和后端 Stop
由唯一协调器依序处理。App 无需自行数 PCM、调用模型或重复提交用量。

### 会议心跳由宿主按 Create 间隔驱动

心跳不是 SDK 内部定时器：宿主（参考 App 里是 `MeetingService`）必须在收到
`onStarted` 后按 Create 响应的 `limits.heartbeatIntervalMs` 周期调用
`session.heartbeat()`。间隔为 `0` 表示旧服务端不要求心跳，宿主不要启动定时器。

**首跳是分界**：服务端用第一次心跳冻结本场“真正开始”时间，并从那时起按间隔检查
活跃。宿主必须在会议开始后很快发出第一跳，不能等到失联窗口快结束才补：

- **从未收到首跳**（会议仍停在 `CREATED`／`STARTING`）：创建 15 分钟后按“从未
  开始”取消，按 0 用量结算，终态 `CANCELLED`，不产生结果清单。已提交冻结采音
  用量（`audio_duration_ms`）的会议不参与这类取消，继续走正常 Stop 收尾。
- **收到过首跳后失联**（`now - lastActive` 超过
  `max(3 × heartbeatIntervalMs, 10 分钟)`）：按弃场收口，终态
  `READY_WITH_WARNINGS`，结算时长取最后活跃减真正开始并按部署上限截断。这类
  会议仍能读到结果清单，但没有正常 Stop 的完整收尾（缺客户端纪要、封口音频等）。

用户点停止后不能立刻停心跳：冻结用量、纪要生成和后端 Stop 都可能排在业务队列里
超过失联窗口。心跳要持续到服务端受理 Stop（收尾链确认 ACK），此时服务端进入
`STOPPING`，SDK 自己也会忽略后续 `heartbeat()` 调用。参考宿主在 `READY`／
`ENDED`／`FAILED`／`CLOSED` 终态或 Service 销毁时取消定时器。

```kotlin
override fun onStarted(started: MeetingStarted) {
    if (started.heartbeatIntervalMs() > 0) {
        heartbeatTimer.start(started.heartbeatIntervalMs())  // 首次立即、之后按间隔
    }
}

override fun requestStop() {
    // 不要在这里停心跳：收尾可能排队很久，停早了服务端会把收尾中的会议判成弃场。
    session.requestStop()
}
override fun onState(state: MeetingSessionState) {
    // 到终态再停；Stop ACK 之后 SDK 本身也不再发送。
    when (state.phase()) {
        MeetingSessionState.Phase.READY,
        MeetingSessionState.Phase.ENDED,
        MeetingSessionState.Phase.FAILED,
        MeetingSessionState.Phase.CLOSED -> heartbeatTimer.stop()
        else -> Unit
    }
}
override fun onDestroy() { heartbeatTimer.stop() }
```

`heartbeat()` 在 SDK 内走**独立**单线程执行器，不与字幕／翻译的业务 IO 队列共用，
慢请求不会把心跳挤过服务端失联窗口；同一时刻最多一个心跳在途，上一次未返回时
后续触发直接忽略，不会在本地堆积。失败只记录，不改变会议状态，也不在本次调用内
重试。

## SDK 音频来源、方向与权限边界

SDK 主动探测平台与兼容接口，通过 `onAudioInputMode(AudioInputMode)` 返回实际来源：

| 模式 | 实际来源 |
| --- | --- |
| `MIC` | 普通 Android 或缺少兼容接口的 CZUR 固件，使用标准麦克风 |
| `CZUR_BROKER` | 兼容 CZUR 共享音频接口，由系统处理共享源和竞合 |
| `DEBUG_WAV` | 仅调试 AAR 的受控测试文件 |

已探测到兼容 Broker 但实际开流失败时会返回音频错误，本场不再切换普通 MIC。
旧固件的 MIC 路径遵循该系统的标准录音能力，不附带新 Broker 的竞合保证。

`onAudioDirection(AudioDirectionSample)` 提供原始／稳定方向、单调时间戳及
`AVAILABLE`／`NO_DIRECTION`／`UNSUPPORTED`。完整会议中，公共 core 结合方向、
会前语言、模型语种、片段状态和交换操作决定左右栏；App 只消费 `onDisplay`。
无当前方向和不支持方向分别处理，既有片段不会随新句重新搬栏。

`onAudioError(code)` 只回 `PERMISSION_DENIED`、开流或读取失败等类型化错误码，原因只写 SDK 日志。
SDK 不申请权限；App 开始入口申请 `RECORD_AUDIO`，Service 不从后台弹权限框。
同流 `onAudioLevel` 返回 0～100 电平及单调测量时刻；它基于 RMS，约 300ms 通知一次，
用于声音图显示，不表示环境分贝，也不决定发言、结果事件或计费。停止时 UI 复位。

## Service、计时与正常停止

`MeetingService` 持有会议句柄、前台通知、跨应用窗口、计时和截图时序。页面离开只解除
观察，重新打开订阅同一场状态；SDK 持有音源、模型和标准事件队列。参考 App 从前台入口
启动 `microphone` 类型服务，有截图时同时声明 `mediaProjection`。
录音和悬浮窗权限由 App 在创建会议前申请；悬浮窗权限通过系统设置授予，未授权时不建会。

默认开始成功后，Service 挂载 `TYPE_APPLICATION_OVERLAY` 真悬浮窗，会前设置 Activity
随即退出。可以打开其他应用并操作窗口以外的区域；字幕条支持收起为 Mini 和恢复，互译
支持普通／大窗／Mini。形态切换保持同一场会议，不重建模型连接或音源。点击浮窗时钟可
主动打开详细会议页，详情中的缩小按钮关闭详情、返回原浮窗；重复打开 App 不叠加会中页面。
单实例 Activity 只在前台观察 Service，暂停即解除，避免多个透明页面争用状态。

页面和窗口的停止使用同一 `requestStop()`。App 即时停止计时、退窗，再继续末帧和
后台收尾；结果状态按实际后端确认推进。浮窗停止在退窗后恢复收尾页面；启用截图时，
先完成末帧流程再恢复页面，避免把自己的详情页采入末帧。后台结果插件需要 UI 时恢复
唯一宿主；息屏期间等待下次唤醒，不主动点亮屏幕。主动息屏同样请求正常停止。
Create 的 `limits.maxMeetingDurationMs` 是本场上限：超过 20 分钟的会议在剩余 20 分钟
时短暂提醒；连续 4 分钟无转写提醒，连续 5 分钟无转写或到达上限时正常停止。
非空原文 partial／final 重置无转写计时；10 秒无新原文会清除当前可见文字，不删除结果。

调试版 APK 的 Service 存活时，可用自动化工具沿同一业务入口正常停止：

```bash
adb shell am broadcast -a com.czur.aimeeting.reference.DEBUG_STOP -p com.czur.aimeeting.reference
```

Release 不注册该接收器。登录、开始和结果检查脚本见
[设备工具说明](../tools/android-device/README.md)；脚本、构建和固定样例均不替代实际模型／硬件验收。

### 进程异常退出后的续办

参考 App 演示了 SDK 的原场续办：开会时 SDK 把会议编号、服务地址、录音文件位置和
结果设置写入应用私有的 `meeting-active` 目录，并把最终字幕追加到同目录的本地日志；
正常收口或续办成功后删除现场。

页面启动和登录成功后，App 在后台对未确认停止的现场逐场调用 `recoverInterrupted`。
SDK 用录音文件的持久前缀重建封口事实，补交冻结用量与标准纪要，再按发布／Stop／
READY 的顺序收口。未登录、网络或后端失败时现场保留，下次启动或登录后重试；伙伴
可以改到开机广播、工作队列或其他时机，也可以把 `ReferenceMeetingRecoveryStore`
换成自己的数据库实现。

续办同时需要停止记录端口：参考实现的 `ReferenceStoppedMeetingStore` 保存封口音频
事实、冻结用量、服务归属和 Stop 幂等键，SDK 靠它推进发布／Stop／READY 并支持再次
续办。只装配现场端口、不装配停止记录端口时，客户端会明确拒绝续办。

后端必须接受创建很久之后才到达的 Stop，并要求用量提交先于发布；参考后端按这套
协议实现。长期未收口的会议是否、以及多久标记为中断，是各部署自己的产品策略，
公共协议不规定，参考实现也不替伙伴做决定。

## 字幕显示

页面通过 `SubtitleViewFactory` 直接使用设备 AAR 内的
`com.czur.aimeeting.ui.SubtitleTextView`：控件接收宿主投影好的片段、文字和 final 状态，
负责部分透明度、行高、最大行数和最近可见行裁切，不持有第二份文字账本，也不判断语种、
方向或左右栏。宿主用明确 px 设置字号和行高，重排不会改变会议快照。

窗口层（悬浮窗、Mini／大窗、权限、提醒、Service）仍由参考 App 自己的 `basic-ui`
实现；设备 AAR 不再提供 `OverlayController`／`DefaultMeetingOverlay` 等窗口工具。
伙伴可以只用 `SubtitleTextView`，窗口和页面交互自行实现。

## 独立SDK截图与参考演示帧

截图由 SDK `ScreenCaptureClient` 提供。App 在主线程申请投影授权并启动相应前台服务，
通过 `ScreenCaptureSourceFactory` 创建 `createSession(enableProcessing = true)` 单场会话。
App 决定 10 秒周期、相对本场单调起点的偏移、排除窗口和末帧时序；裁剪、过滤、去重、
编码等处理封装于 SDK。调用方可以关闭内置处理，不读取内部判断依据；返回 `null` 表示
本次没有新图，仍需正常续接停止。

当前参考端使用 SDK File 输出，IO 协程读 JPEG 后清理临时文件，再提交
`session.submitPresentation(offsetMs, jpeg, callback)`。只有后端真实保存 ACK 成功后
调用 `confirmAccepted()`；失败不确认。周期请求完成后才采末帧，成功、无新图或失败
均关闭会话／客户端并续接 `beforeStop`。截图计算、编码、文件和网络不阻塞主线程。

兼容系统使用 `SYSTEM_CAPTURE` 排除悬浮窗：每次在主线程读取当前已挂载的
`View.getWindowToken()`，不缓存跨挂载句柄。自定义窗口工厂应实现
`MeetingOverlayFactory.Window.windowToken()`；提醒窗口也纳入排除清单。
不支持排窗的系统使用 MediaProjection 整屏截图，App 按 `supportsWindowExclusion()`
明确传空列表，并提示可能包含悬浮窗。

SDK 另提供 Bitmap 输出；关闭处理时 File 为 PNG，开启处理时为 JPEG。
交付后的 Bitmap／File 归调用方回收或保留，接受确认不负责回收，也不执行网络。
完整方法和 Main／IO 协程约束见配套 SDK 的 `SCREEN-CAPTURE.md`。
从窗口停止时，App 等末帧结束才返回会议页面，避免自己的会议整页进入末帧。

## 可注册结果动作：默认只Web

`MeetingAppAssembly` 默认只注册 `WebResultActionPlugin`。会前冻结动作设置和构建配置
中的结果页地址，真实 READY 后生成带同场 `meetingId/genTime` 的链接并打开 Web。
Web 动作不读取正文。`MeetingResult` 此时是 Manifest 清单，`content()` 为 `null`；
需要正文的插件必须通过本场 `context.access().readContent(...)` 异步读取。

伙伴实现 `AppResultActionPlugin`，声明稳定 ID、名称、产物／服务依赖，
`createSettings(Context)` 返回设置 View 和冻结工厂；开始时生成不含 Activity／View 的
插件闭包。注册示例：

```kotlin
MeetingAppAssembly.registerResultActions(listOf(CompletionNoticeAction()))
// 显式可选：WebResultActionPlugin()、LocalPdfResultActionPlugin()、EmailResultActionPlugin()
```

PDF 插件通过窄 `readContent` 读取正文，再将文件工作放到 `workExecutor`；参考实现见
[`LocalPdfResultActionPlugin.kt`](app/src/main/java/com/czur/aimeeting/reference/LocalPdfResultActionPlugin.kt)。
邮件插件冻结收件人，通过窄 `sendEmail` 发送 Web 链接，仍需部署邮件服务。
离线 HTML 可沿 PDF 的正文读取方式替换文件输出。扩展示例见
[结果交付手册](../docs/partner-integration-guide.md)。

结果服务发现目前映射两类字段：`resultLinkTtlMs > 0` 为 `result.link`，
`resultEmailEnabled == true` 为 `result.email`。新后端服务依赖需同步扩展协议、
后端声明、客户端解析和映射；纯本地动作可声明空服务集，仍按需要声明产物。
协议声明服务不会自动安装 App 插件。

READY 和动作成功分别处理；动作失败可以用原 READY、`genTime` 和冻结参数再次执行，
无需重开会议、重复总结或计费。平台 UI 通过 `UiOperation` 在可见 Activity 消费；
文件工作走 `workExecutor`。动作完成或用户结束交互时关闭原 client，新场开始前释放
旧访问对象；默认交接只保存在进程内，不承诺进程恢复。

`NO_ACTIVE_DELIVERY` 表示不主动交付：正常 Stop 后 ENDED、不取 Manifest／触发动作，
仍保存标准事件并按原总结／用量规则收尾，不承诺删除服务端记录。

## 合作伙伴会前开会条件 Hook

[`MeetingStartConditions.kt`](app/src/main/java/com/czur/aimeeting/reference/meeting/MeetingStartConditions.kt)
是开会前条件装配入口。默认 `MeetingStartCondition` 放行；伙伴可新增实现，
由 `create(Context)` 返回。App 在登录、能力检查之后，申请屏采权限与启动 Service
之前调用 `prepare`，组合 `check(MeetingSetup)` 与结果动作准备。

返回 `Decision.block("请先完成接收者登记")` 时显示理由且不建会，返回
`Decision.allow()` 后继续。Hook 在主线程，只读已准备的状态；网络、扫码和绑定由
伙伴提前异步完成。参考包不提供小程序或扫码接收模块。后端 Create／Start 的
鉴权与权益判断仍是权威检查。

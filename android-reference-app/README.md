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

| 模块或交付物 | 职责 |
| --- | --- |
| `app/` | 可编辑的页面、身份与结果插件、权限、前台 Service、计时和截图时序 |
| `model-runtime-contract/` | 模型 Adapter、会话材料、音源状态和标准事件契约 |
| `meeting-client-core/` | 共享 Server 协议、会议编排、Adapter 注册和显示规则 |
| 配套主 SDK AAR | 系统／标准 MIC 音源、模型直连、同源录音及截图能力；内带同源 core |
| 配套 UI AAR | 可选字幕控件、窗口基础管理及默认窗口实现 |

主 SDK 和 UI 工具由配套交付包提供。无需访问 CZUR 产品源码仓；AAR、模型 Key、
真实会话材料和本地配置均不提交到本公开源码仓。具体功能、版本配套与供应商账户
准备方式见[从零指南](../docs/partner-getting-started.md)。

## 构建期部署地址

后端和默认 Web 结果页地址由开发者在 PC 上配置，编译为 App 的 `BuildConfig`。
终端用户只需登录和使用会议功能，无需填写 IP 或网址。模型地址和短期材料由后端下发，
这份构建配置不包含模型 Key 或账号密码。

在 Android Studio 打开本目录后，复制模板并填写两个地址。实际文件已被 Git 忽略：

```bash
cp deployment.properties.example deployment.local.properties
# 编辑 referenceBackendUrl 和 referenceResultWebUrl。
```

取值优先级为显式 Gradle `-PreferenceBackendUrl`／`-PreferenceResultWebUrl`、选定
`.properties` 文件中的同名键、空值。默认文件为本工程根目录下的
`deployment.local.properties`；可用 `-PreferenceDeploymentFile=/absolute/path/deployment.properties`
选择仓外文件，相对路径按 Android Gradle 根目录解析。地址缺失时 App 会提示联系应用提供方。

```bash
./gradlew :app:assembleDebug \
  -PreferenceBackendUrl=https://api.example.invalid \
  -PreferenceResultWebUrl=https://results.example.invalid/index.html
```

以上默认构建为 Mock；真实会议还需下节的 SDK 参数。局域网可从仓库根使用
[`build-lan-apk.py`](../tools/android-device/build-lan-apk.py)，显式注入可达的 PC 地址和
配套 AAR。工具不会启动服务或生成证书；命令及 HTTPS 准备见
[构建／启动工具](../tools/android-device/README.md)和[局域网 HTTPS 指南](../docs/local-lan-https.md)。
设备上的 `127.0.0.1` 指设备自身；局域网运行应填写设备可达的服务地址。

## 选择运行模式并构建

所有命令在本目录执行。默认 `mock` 可在干净检出后构建，提供固定字幕修订和结果演示，
不占用 MIC、不领取真实模型材料、不验证真实模型协议。Mock 中的演示截图入口禁用。

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

真实会议使用 `sdk`，完整默认窗口和字幕控件同时注入匹配的 UI AAR：

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest \
  -PmeetingRuntime=sdk \
  -PmeetingSdkAar=/absolute/path/ai-meeting-sdk-0.5.0-release.aar \
  -PmeetingUiAar=/absolute/path/ai-meeting-ui-0.2.0-release.aar
```

`sdk` 缺少主 AAR 时配置失败。主 AAR 内带同一公共 core，App 不再依赖
`:meeting-client-core`，避免重复类；`model-runtime-contract` 由本工程打包唯一一份。
裸 AAR 不传递依赖元数据，App 已显式补齐 OkHttp 和截图挂起接口所需的协程运行依赖。

UI AAR 独立选择，也可用于 Mock：

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest \
  -PmeetingUiAar=/absolute/path/ai-meeting-ui-0.2.0-release.aar
```

不提供 UI AAR 时编译 `basic-ui` 工厂，提供基本文字和单一窗口，供最小构建／开发使用；
它不包含完整窗口形态和最近文字裁切。提供 UI AAR 时编译 `meeting-ui` 工厂。
两者互斥，无反射加载，也不在源码仓保存 AAR。

## 默认参考功能与接入边界

默认页面包括会前登录和设置、实时字幕／双语互译、独立的“生成会议纪要”开关、
按能力开放的语言与演示截图设置，以及正常停止后的同场 Web 结果。
后端只声明 `transcript.realtime` 时，页面隐藏未装配的总结和互译入口，仍可完成字幕会议。
界面语言列表直接使用后端发现的 `languages`；部署者须配置实际模型支持的语种子集。

SDK 模式默认装配同源录音和 `HttpMeetingArtifactPublisher`，并设置 `retainAudio=true`；
Mock 无真实音源，设置为 `false`。录音在停止后上传到参考后端，实时音频仍由设备直达模型。
是否生成总结、翻译、演示产物和跨会议身份，取决于实际装配、声明、用户选择及本场授权。
用户已选的互译／总结能力无法授予时，该场不会启动。

参考账号与 CZUR 系统账号独立。会员、充值、Launcher、小程序和系统个人中心不在参考包中。
默认只注册 Web 结果动作，邮件、PDF 和伙伴自有通知须显式装配；部署声明邮件能力不会
自动生成 App 入口。离线 HTML 等结果方式可使用同一结果插件接口扩展。

当前功能的验证范围和硬件专项边界见[验证范围](../docs/validation-scope.md)。合作伙伴
更换模型、账户、语言、存储或设备后，应按[从零指南](../docs/partner-getting-started.md)
验证自己的部署。Mock、构建和 SDK 截图 API 的验证分别说明其范围，不替代实际模型／
硬件场景及完整演示会议产物验证。

## 页面、登录与集中装配

`MeetingAppAssembly` 是身份、登录入口、模型、结果动作和页面／窗口工厂的集中装配点。
默认身份插件 `PersistentReferenceIdentityProvider` 将短期访问令牌、显示名、到期时间和
后端归属保存在应用私有设置，不保存密码。进程重启恢复有效登录；过期、退出或切换后端
清除对应身份。同一后端升级保留有效登录，只更换结果 Web 地址不会注销账号。

“切换账号”打开登录表单；取消或登录失败保留原会话，成功才替换。App 进入页面、
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

`src/sdk/.../MeetingRuntimeFactory` 创建 `AiMeetingClient` 和同源录音输出；Mock 为同一
`MeetingCoordinator` 注入固定运行端口。Activity 的会前客户端和 Service 的会议客户端
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

`onAudioError` 返回 `PERMISSION_DENIED`、开流或读取失败等类型化错误。
SDK 不申请权限；App 开始入口申请 `RECORD_AUDIO`，Service 不从后台弹权限框。
同流 `onAudioLevel` 返回 0～100 电平及单调测量时刻；它基于 RMS，约 300ms 通知一次，
用于声音图显示，不表示环境分贝，也不决定发言、结果事件或计费。停止时 UI 复位。

## Service、计时与正常停止

`MeetingService` 持有会议句柄、前台通知、跨应用窗口、计时和截图时序。页面离开只解除
观察，重新打开订阅同一场状态；SDK 持有音源、模型和标准事件队列。SDK 模式从前台入口
启动 `microphone` 类型服务，有截图时同时声明 `mediaProjection`；Mock 使用 `dataSync`。
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

## 可选字幕／窗口UI工具：构建期注入

`SubtitleViewFactory` 的 `meeting-ui` 实现直接调用 UI AAR；基础实现使用 TextView。
工厂消费 `DisplaySnapshotPresentation` 投影的片段、文字、源侧／交换和各段 final 状态，
不持有第二份文字账本。页面展示完整历史；普通窗口通过 `renderParts`／`setParts`
交付完整输入，由控件裁最近可见行。宿主用明确 px 设置字号和行高，重排不会改变会议快照。

### 窗口工厂：默认四形态与一个形态的替换例

UI AAR 的 `DefaultMeetingOverlay` 提供字幕、普通互译、大互译和 Mini 四形态。
普通窗口显示最近 3 行，简洁模式 2 行；大窗两侧独立滚动；Mini 点击恢复此前形态。
隐藏／切换窗口只改变 UI，仍继续采音、识别和计费。提醒卡片、权限和窗口尺寸由 App 管理。

`MeetingOverlayFactory.Window` 是 App 最小句柄；`meeting-ui/.../ui/OverlayViewFactory.kt`
提供可编辑的布局例。伙伴只需一种形态，可复用公开的 `SingleFormOverlayFactory`：

```kotlin
MeetingAppAssembly.registerOverlayFactory(SingleFormOverlayFactory())
// 恢复默认：MeetingAppAssembly.registerOverlayFactory(OverlayViewFactory())
```

工具层还提供 `OverlayController.ContentFactory`／`Content`／`LayoutPolicy`／`WindowSpec`／
`StateListener`。替换工厂仅影响随后创建的窗口，不改变音源、Adapter、显示路由或会议收尾。
结束时 dismiss 释放窗口，无需额外停止一次会议。

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

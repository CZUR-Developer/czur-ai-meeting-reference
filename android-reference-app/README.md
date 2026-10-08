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

参考App已提供国内版形态的会前设置、字幕／互译会中页、普通／大／Mini及简洁窗口，
默认使用自身登录入口，停止后自动打开同场Web结果。正式参考组合同时注入匹配版本的
主SDK和UI AAR，构建方式见下文；窗口交换、Mini恢复及正常停止／结果链已有验证证据。
受控录音的UI／真实模型验证与实体MIC／DOA验证分开，未配置的能力保持关闭；
CZUR系统账号、会员和Launcher等私有联动不包含在参考交付中。
参考产品包含身份来源、可组合设置、结果动作与可选UI工具。当前新增设置将字幕／
互译与“会后生成会议纪要”分别选择；语言直接消费后端发现协议的`languages`，没有固定中英文列表。
P7-2a源码已切换为默认仅Web与Manifest-only收口；PDF／邮件作为显式可选插件保留。
该接线已通过独立249项组件测试及两模式构建，并已本地提交；44记录通过默认Web正常设备链。
下文P5／P6其他交付方式是历史证据，不代签P7的新接线。

P7-3a在基础音频与完整会议入口增加同流输入电平：`AudioInputLevel.level()`为0～100，
`sampledAtElapsedRealtimeMs()`是单调测量毫秒时刻。SDK沿用旧RMS公式，每三个100ms帧
测当次帧，约300ms通知一次；参考页和悬浮窗读取Service的同一值，正常停止立即复位。
正式会议不另开Monitor／MIC；Mock没有实际PCM，不代签实采。国内形态主界面用声音图显示
电平，音源枚举、能力说明和电平数值不占用主显示区，仍可通过日志／独立诊断查看。本项源码与
主机构建、独立验收及设备演示分别记录，不把Mock或APK构建当成实采电平证明。

P7-3b通过独立可选UI AAR装配字幕控件。页面历史单元、字幕浮窗及互译两栏共用
`SubtitleViewFactory`，消费同一已路由快照；每次完整替换文字，partial为80%、final为100%，
View自身alpha保持1。3b已独立验收并本地提交；本步3c在同一UI AAR增加基础／默认
窗口和单形态可编辑例，3c已独立签收提交；真实设备效果与3d组合验证。

### 国内形态的参考页面与App装配

参考App采用居中白色会前卡、蓝色标题栏、字幕／双语模式和能力驱动的语言选择。
“会后生成会议纪要”独立于呈现模式，未提供总结能力时保持关闭；演示文稿只在字幕模式开放。
“账号登录”打开参考账号密码弹窗，“连接设置”独立配置后端与Web结果地址。
地址只保存在本进程，变更后返回页面会关闭旧会前客户端并重新刷新身份／能力。
更换后端清除默认参考会话；不写入凭据，不接CZUR个人中心、会员、充值或Launcher。

会中直接复用国内字幕／互译背景和控制图标，时长、声音图、窗口入口和Stop固定在滚动区之外。
双语原文白色、译文黄色（`#FFDB10`），同一发言方的原译在同侧上下排列；字幕24设计字号、
36设计行高。原文／译文消费同一SDK已路由快照和`SubtitleViewFactory`，不复制分栏、修订或模型逻辑。
停止后沿原准备动作自动打开同场Web，原生容器提供“本场会议结果”及返回入口。
默认Web没有主页面地址输入；`Settings.freeze()`在点击开始时读取连接配置，
系统屏采授权返回继续消费同一准备对象。自定义结果插件仍可在`result_settings_host`放自己的View。

App侧`MeetingPageViews.Factory`只创建／绑定View；默认XML中的dp／sp记录设计数值，
`ReferenceUiScale`局部映射1920×1080基准，不改系统DisplayMetrics。大屏会前卡为988×856、
蓝标题86高、40圆角，语言／开关／开始按钮复用国内比例与样式；小窗内容可滚动，Start／Stop不随文本滚走。
伙伴可编辑公开XML，或在首次创建Activity前登记自己的薄工厂：

```java
MeetingAppAssembly.registerPageFactory(activity -> new MeetingPageViews(
    (FrameLayout) activity.getLayoutInflater().inflate(R.layout.partner_meeting_page, null)));
```

自定义布局提供相同的控件ID即可由原Activity接线，不创建另一份会议状态；页面不放入SDK。
构建组合共用同一App布局，正式国内形态交付使用SDK＋UI：同时注入主SDK和UI AAR。
无UI AAR的基础版是有限开发fallback，不作为完整四形态交付；Mock＋UI用于正常显示回归。
复制图片和纯显示代码的来源见[页面资源清单](app/UI-RESOURCE-SOURCES.md)及
[窗口资源清单](docs/czur-overlay-resources.md)。参考版使用自己的登录、默认仅Web结果；
CZUR会员、个人中心、Launcher和小程序等私有联动不在本包复制。
源码、主机布局／组件测试、APK构建、独立验收和实体截图／真实模型链分别留证，构建不代签设备效果。

P7-1c的SDK会议入口复用正在使用的唯一音源累计完整标准PCM帧，停流及完整尾帧交付后
一次冻结时长。组合层按“最终字幕／译文保存→冻结用量提交→按需总结→原Stop”执行；
App不计数、不额外采音。时长沿用16kHz／mono／PCM16字节数除以32的整数毫秒口径，
静音和模型连接前采音计入，网络重发及会后等待不计入。默认Mock仍未提供实采用量，
其会话返回empty，保留原演示链；不能以Mock结果代签真实PCM计数或CZUR会员扣费。

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
`MeetingAppAssembly` 持有的 `ModelAdapterRegistry` 中构建期注册，后端
`modelSession.adapterId` 决定本场选用哪一个。伙伴适配其他格式时新增 Adapter 并在集中装配点
登记其实时能力，通用会议页面和 SDK
不用修改。登录后参考 App 调用 `MeetingClient.discover`，直接读取部署与本地能力
已求交的 `availableCapabilities` 开放会前模式；发现失败时显示原因并保留账号切换入口，
不创建会议。组合层读取 Create 响应的 `capabilityDecisions`，本场权益、
模型与降级结果以该决策为准；必需能力不可用时不启动会议，可选能力不启用。
P5-6历史参考结果方式包含网页、本地PDF、邮箱链接和互译无痕；P7默认仅Web。扫码接收由合作伙伴
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

### 可选字幕／窗口UI工具：构建期注入

UI工具独立于主音源／会议SDK。干净Mock不需要闭源输入；提供`meetingUiAar`时，
Mock与SDK使用同一独立UI AAR。正式SDK参考交付同时注入主AAR和UI AAR：

```bash
# Mock＋UI：core仍来自源码，勿用主SDK AAR充当UI依赖
./gradlew :app:assembleDebug :app:testDebugUnitTest \
  -PmeetingUiAar=/absolute/path/ai-meeting-ui-0.2.0-release.aar

# SDK＋UI：core仅来自主SDK AAR，contract仍由公开源码模块提供
./gradlew :app:assembleDebug :app:testDebugUnitTest \
  -PmeetingRuntime=sdk \
  -PmeetingSdkAar=/absolute/path/ai-meeting-sdk-0.5.0-release.aar \
  -PmeetingUiAar=/absolute/path/ai-meeting-ui-0.2.0-release.aar
```

`src/meeting-ui/.../ui/SubtitleViewFactory.java`直接调用UI工具；未提供UI AAR时只编译
`src/basic-ui/.../ui/SubtitleViewFactory.java`基础TextView版。两份工厂互斥，不反射加载，
也不在公开仓提交AAR。基础版保障可重建和正常文字显示，不复制完整最近文字裁切功能。
UI AAR只含控件和必要呈现类型，无core、contract、网络或原生采音，也没有其他运行依赖。

工厂只接收`DisplaySnapshotPresentation`已投影的段ID、文字和各自1.0／0.8状态；
`speakerColumn`仅把SDK源侧与交换开关投影为实际左右侧，原译整对同侧，未确定侧不猜测。
页面历史单元保留整段；普通窗口用`renderParts`把final历史和当前partial交给SDK已有`setParts`，
每段状态分别保留，只由控件裁最近可见行。没有旧`bilingual`跨侧入口或第二文字账本。
参考宿主把24设计字号换成明确px交给工具，并用`setLineHeightPx`指定36设计行高；
工厂原`create`默认1.3倍仍供其他宿主使用。缩窄／放大从完整输入重新排版，不改会议快照。
占位也用工厂render→setParts提交，避免继承setText在重排时被旧字幕覆盖。
页面与窗口的电平、Stop、结果Web及Service生命周期继续沿同一业务链。
伙伴换字幕控件时只改对应薄工厂及构建输入；无需修改音源、Adapter或会议收口。
3c默认会议窗口的WindowManager操作由UI工具持有；权限申请和提醒窗口仍留App。

### 窗口工厂：默认四形态与一个形态的替换例

`MeetingOverlay`只保留原Service调用签名、提醒和工厂句柄，不再持有第二份四形态状态。
默认`MeetingAppAssembly.overlayFactory()`为构建期互斥的`OverlayViewFactory`：

- 无UI AAR的`basic-ui`版复用同一国内素材和同侧View，仅提供一个基础字幕／普通互译窗口，
  保留返回、交换、停止及同场声音指示；不复制完整四形态、Mini或SDK最近文字排版工具。
- 有UI AAR的`meeting-ui`版装配`DefaultMeetingOverlay`，默认注册字幕、普通互译、大互译、Mini。
  字幕和普通互译读取完整历史与当前句，裁最近3行；普通窗简洁模式裁2行。
  大窗左右独立滚动历史；Mini恢复之前形态。窗口隐藏／切换
  只影响UI，不暂停会议或修改音源、模型、计费及收口。

`meeting-ui/.../ui/OverlayViewFactory.java`同时是公开可编辑的px布局和内容装配例。
App按1920×1080设计尺度等比换算：宽1800、字幕高222、普通互译293、大互译1008，
Mini126×60；位置由App决定。字幕24设计字号／36设计行高，原文白色、译文黄色，
普通互译的控制区在双栏中央；简洁模式高度164、背景透明，文字2行。
Mini复用国内APNG和声音图，点击可恢复。原素材／动画字节不变，放入nodpi避免二次密度缩放。
这些设计数值先在App换算，再交UI工具；不引入Starry私有pt／AutoSize或平台业务依赖。

`CzurOverlayViews`是可编辑的纯显示内容；原`OverlayViewFactory.presentation`已删除，
直接消费统一`DisplaySnapshotPresentation.speakerColumn`及字幕投影，保留源侧／交换和
原／译文各自final；不改分栏、选段或音源。旧跨侧投影已随页面迁移移除。
窗口Stop／交换／返回回调继续由同一Service提供，时长、电平来自其同一State。
提醒仍由原App卡片及规则管理，没有放入UI AAR。宿主结束／退出时调用dismiss释放窗口，
不是另发一次Stop。

伙伴只要一个形态时，直接使用可编辑的`SingleFormOverlayFactory.java`：

```java
// 有 meetingUiAar 的构建，在首次显示之前（例如伙伴 Application.onCreate）显式装配：
MeetingAppAssembly.registerOverlayFactory(new SingleFormOverlayFactory());
// 默认恢复：MeetingAppAssembly.registerOverlayFactory(new OverlayViewFactory());
```

例子只注册`partner.single`，替换自己的View／位置尺寸，消费同一已投影字幕／双栏输入，
不用实现默认四项，也没有专用演示页面或运行时插件加载。App扩展接口是
`MeetingOverlayFactory`及其最小`Window`句柄；工具扩展接口是`OverlayController`嵌套
`ContentFactory`／`Content`／`LayoutPolicy`／`WindowSpec`／`StateListener`。
只改实现与Assembly注册，不修改Service、音源、Adapter、模型通信、分栏或会议收口。
已创建的窗口继续使用自己的句柄；新装配从下一次创建窗口生效。

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
模型最终字幕、保存标准结果、确认READY清单后通用派发冻结动作；默认Web打开同场结果页。这是正常
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
互译普通窗显示最近可见3行（简洁2行），大窗显示左右独立可滚动历史，两种窗都读取同一份会议状态。
Mini 期间会议仍继续采音、收模型结果和提交标准事件，恢复后能看到新内容。
国内版字幕收起的清空文字／暂停ASR入口属于P9产品规则，本步不迁移；
参考版只实现窗口收起，不声称Mini暂停识别或计费。

当前会前先在独立连接设置填写设备可达的后端与Web地址，再从账号入口填写部署者预置的账号和密码。
App调用`MeetingClient.login`更新集中装配的参考内存身份，Service用同一Provider创建独立会议客户端。
组合层封装 `POST /v1/reference/session:login`、建会、标准字幕/译文提交及结束会议。
字幕／互译两种会中呈现均可独立开启会后总结；用户选择的功能必须同时获本场授权。
旧`MEETING_MINUTES`调用保留“字幕＋总结”的语义，新页面使用独立checkbox。
两类事件在协调器内共用本场`clientSequence`与单线程
提交队列；模型确认完成后，SDK先提交本场冻结用量，再按需总结和Stop，均排在此前事件之后。
同场Stop重试复用已冻结值和已有确认，不重开音源或重复已成功的提交。
令牌仅保存在本次进程内Provider，切换账号时清除；账号密码和
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
仅 Debug 的测试文件。参考 App 保留该值的接线，国内形态主界面不显示诊断枚举；伙伴可用于日志或状态展示，
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

### 可注册结果动作：默认只Web

`MeetingAppAssembly`是构建期唯一清单，默认`List.of(new WebResultActionPlugin())`。
后端链接服务可用时页面显示网页查看，地址来自独立连接设置；没有未装配邮件／PDF／小程序／无痕选项，
也没有最近PDF按钮。后端邮件能力声明不会自动添加入口。模型音频仍直连模型，
标准事件、用量、总结和屏采顺序不因动作变化而改变。

伙伴新增`AppResultActionPlugin`实现并在Assembly登记即可：
公共core接口负责稳定ID、名称、产物／服务依赖、`prepare`与异步执行；App薄接口
`createSettings(Context)`返回插件自己的View及`freeze`工厂。
页面仅在会前Hook读取一次输入，工厂返回只含不可变参数和Application Context的core插件；
`prepare`产生本场闭包，不保留Activity／View。没有输入的动作返回空View，不需改MainActivity。
后端需要提供什么服务由部署负责，不默认转到CZUR服务。

`MeetingStartConditions.prepare`先检查原伙伴条件，再准备动作。屏采授权期间沿用同一
`PreparedMeeting`，授权返回后通过Assembly暂存、Service一次消费；不重读输入或接收者状态。
取消授权／启动失败清理暂存，不承诺进程恢复。
正常Stop只读真实READY Manifest，`onResult`先存结果，随后实际READY状态停止
计时／屏采／悬浮窗／采音前台通知，并使用冻结动作通用派发。
Web生成含同场`meetingId/genTime`的链接，保留已有query／fragment，不读取正文。

插件需要可见页面时向`context.uiExecutor()`提交`AppResultActionPlugin.UiOperation`：
Assembly的同进程内存宿主暂存已经准备好的通用操作，Service退出不清此交接；
新Service绑定／Activity可见后沿原入口消费一次，不强行后台拉起页面，不承诺进程恢复。
操作只捕获Application Context、冻结参数及完成回调，不捕获Activity／View，
不重新使用已关闭Session；排队不是成功，实际显示后才报告成功。
普通执行与完成回调仍走主线程，旧Service关闭只停止旧宿主状态更新，不吞掉完成闭包。
耗时文件工作交原存活`workExecutor`，协议交该场仍有效的窄`access`；
通用Service／MainActivity不识别PDF或邮件。普通失败只显示独立动作提示，提供“再次执行”
和“结束本场结果交互”；重试沿用同一READY／genTime，不再Stop、采音、总结或计费。
成功或结束交互释放原client；开始新场前也释放旧结果访问，仅支持当前进程交互。

最小替换例，修改Assembly默认初始化即可（未加入默认清单）：

```java
private static List<AppResultActionPlugin> resultActions =
        List.of(new CompletionNoticeAction()); // example.notice：同场READY完成提示，无正文或网络。
// 显式可选：new LocalPdfResultActionPlugin()、new EmailResultActionPlugin()
```

PDF插件READY后按需读取typed正文，复用原`LocalPdfWriter`／`LocalResultActivity`；
邮件插件冻结网页地址与收件人，通过本场窄`sendEmail`通知。二者均需显式注册，
失败不改变READY。旧六／七／八参MeetingSetup及七／八参MeetingOptions保持可编译，
旧枚举只映射动作ID，不隐式安装实现；旧`DeliveryStatus`字段仅兼容保留。
旧无痕调用映射`NO_ACTIVE_DELIVERY`，仍保存标准事件、按原总结／计费规则Stop，
ENDED且不读Manifest／主动交付，不承诺无服务端记录。
源码和主机测试不代签新接线的设备UI、真实Web或PDF／SMTP部署。

### 完整会议入口与平台扩展点

`src/sdk/.../MeetingRuntimeFactory` 构造
`AiMeetingClient(context, config, MeetingAppAssembly.modelAdapters())`；Mock Factory
为同一 `MeetingCoordinator` 注入同一注册表及现存固定字幕／纪要运行端口，不增加第二套业务流程。
配置包含 Server 地址、登录 Token、客户端身份、会议档位和串行回调 Executor；Android
使用主线程 Handler。Activity 的会前客户端和 Service 的会议客户端彼此独立。

模型协议与身份、结果动作、页面／窗口工厂都在`MeetingAppAssembly`集中装配。
运行工厂首次取用时创建的默认表包含听悟、`sample-json-ws`及百炼总结；单独使用页面／窗口装配不加载模型协议类。
伙伴先实现公开`ModelAdapter`，
再在首次创建Activity／Service客户端前（例如自己的`Application.onCreate`）注册：

```java
// PartnerModelAdapter是伙伴自己的协议实现；每次调用工厂须创建新实例。
ModelAdapterRegistry adapters = new ModelAdapterRegistry();
adapters.register(PartnerModelAdapter.ID, PartnerModelAdapter::new,
        Set.of(MeetingCapabilityState.TRANSCRIPT));
MeetingAppAssembly.registerModelAdapters(adapters);
// 恢复默认组合：MeetingAppAssembly.registerModelAdapters(new ModelAdapterRegistry());
```

会后文本协议可在同一注册表调用`registerSummary(id, factory)`。相同ID后注册替换原工厂；
解析能力须如实登记，后端仍决定本场`adapterId`、模型地址、短期材料与授权。
装配只保存工厂，不保存某场Adapter；替换装配仅影响随后创建的客户端，会中不得修改原表。
SDK版执行选中的真实Adapter；Mock版继续固定字幕／纪要演示，不领取真实材料、不执行伙伴Adapter，
不能用Mock结果代签伙伴协议。更换模型实现只需新增实现并在此登记，无需修改两份运行工厂或SDK源码。

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
        // 只保存同场READY清单；收到onState(READY)后再用冻结动作通用派发。
    }
});
// 稍后用户点击停止，且最近 onState 的 state.canStop() 为 true 时调用：
session.requestStop(); // 正常收口；client.close() 仅用于取消/释放，不产生会议结果。
```

`start()` 异步创建会议并立即返回句柄；App 保存该句柄，过早的 Stop 不受理。

屏幕授权、周期 JPEG、PDF/MediaStore 和权限申请均在 App；截图通过
`session.submitPresentation(offsetMs, jpeg)` 交组合层保存。系统单独停止投影只结束
屏采，字幕会议继续。`onResult`先于READY状态，后者之后才执行冻结动作；正文或通知失败
不回退会议READY，结果访问保留到成功或用户结束交互。合作伙伴可更换这些平台/UI
实现，或在`MeetingAppAssembly`登记自定义Adapter工厂注册表，继续复用同一组合流程。

### 合作伙伴会前开会条件 Hook

`app/src/main/java/com/czur/aimeeting/reference/meeting/MeetingStartConditions.java`
是唯一需要修改的装配入口。参考实现返回始终放行的 `MeetingStartCondition`；合作伙伴
可新增自己的实现类，再让 `create(Context)` 返回该实现。`MainActivity` 在登录、模型能力
检查之后，申请录屏权限与启动会议Service之前，通过`MeetingStartConditions.prepare`组合
`check(MeetingSetup)`与动作准备。返回 `Decision.block("请先完成接收者登记")` 时，页面显示该理由，
本场不会创建会议；返回 `Decision.allow()` 才继续原流程。后端 Create/Start 的权益
判断仍是权威检查，Hook 不能代替后端鉴权。

例如伙伴自行开发小程序接收时，可由其代码异步登记并缓存本场接收者人数，再在
`check(MeetingSetup)` 读取缓存并决定是否放行。Hook 在主线程调用，不能在检查方法中
同步请求网络或等待扫码轮询。伙伴需要自行开发扫码 UI、小程序和后端绑定；参考工程
不包含这些模块，也不因保留 Hook 而展示扫码结果方式。

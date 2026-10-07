# 会议客户端公共核心

本模块是平台无关的普通 Java 17 JAR，供参考 Mock App 和 CZUR SDK 使用同一套会议编排、
Server 协议、模型适配与国内显示规则。Android 控件、权限申请、音源、模型网络传输、
屏幕采集和本地 PDF 不在本模块。模型直连仍在设备 SDK，不在后端代转音频。

当前包含：

- `client.provider`：默认听悟、第二格式 JSON 样例、百炼总结的唯一公开解析实现。
- `ModelAdapterRegistry`：按 Server 下发的 `adapterId` 选择工厂，每场会议创建独立实例。
- `MeetingCapabilityState`：Create 响应的本场能力决策，不由厂商名字推断。
- `client.display`：共享显示状态、句开始方向快照，以及国内 A/B 多语种兜底。

## 两层入口与组合契约

基础层由 Android SDK 的 `AudioInput` 提供，不需要 Server/model：App 接收标准 PCM 和物理方向。
组合层 `MeetingClient` 提供发现、登录与开始；`MeetingSession` 控制停止、截图与交换显示列；
`MeetingListener` 返回不可变显示快照、状态和类型化结果。Android `AiMeetingClient` 只装配运行时，
Mock 注入窄端口 `MeetingRuntimePort`；二者使用唯一 `MeetingCoordinator`，App 不领取材料或重复分栏。
同进程基础输入、诊断和完整会议择一持有真实音源。

正常链为当前身份读取 → 能力发现 → Create → Server 材料/新 Adapter → StartClient → 采音/模型 → 标准事件保存 →
按能力总结 → Stop 输入受理 → 实际 READY/交付。后端仍负责身份、模型选择、计费和存储。
模型完整地址、短期材料及本场授权只取 Server，配置不接收模型地址或凭据。

`MeetingClientConfig` 接受 Server URL、`IdentityProvider`、`ClientIdentity`、`MeetingProfile` 和
**串行** `callbackExecutor`。Android 通常用主线程 Executor；不能用无序线程池让结果和状态重排。
旧访问令牌构造器保留，将固定token包装为参考内存身份。`discover()`仍为匿名能力发现，
返回typed `DeploymentOptions`及本地可执行能力交集；可用功能不等于本场已获许可。

### 可替换身份与默认登录

`IdentityProvider.currentIdentity()`在IO队列返回`IdentityResult`：已登录、需要登录或读取失败。
已登录的`IdentitySession`携带本部署会议后端接受的Bearer凭据及可选展示名。SDK不打开登录页、
不读取Starry私有Provider，也不退出系统账号。App进入页面或登录返回时调用
`MeetingClient.refreshIdentity()`，通过串行回调处理状态；开始会议时SDK再次读取，并把本次
凭据冻结到本场Server客户端。会中刷新身份不会悄悄更换该场请求使用的账号。

参考默认`ReferenceIdentityProvider`只保存进程内会话；`MeetingClient.login()`沿用已有账号密码
端点，成功后更新同一Provider并保留旧typed返回值。伙伴自定义Provider使用自己的App登录动作，
返回后刷新即可，无须实现账号密码接口。

```java
IdentityProvider identity = () -> {
    // 伙伴从自己的会话来源读取；需要时在实现内交换成其会议后端可接受的凭据。
    String token = myAccount.currentMeetingToken();
    return token == null ? IdentityResult.loginRequired("请登录伙伴账号")
            : IdentityResult.loggedIn(new IdentitySession(token, "伙伴账号"));
};
MeetingClientConfig config = new MeetingClientConfig(serverUrl, identity,
        clientIdentity, productProfile, mainThreadExecutor);
```

参考App的`MeetingAppAssembly`集中注册来源并供Activity与Service共用；开始Intent只传会议设置，
不复制可能过时的token。账号密码登录界面是默认示例，伙伴可替换页面动作和身份来源。
CZUR系统token与默认参考token没有天然等价关系：私有身份及后端认可适配在P7-1c实施，
本接口及主机测试不代签CZUR生产账号联通。

同一客户端同时只允许一场未完成会议，完成后可开下一场；`close()` 幂等取消，关闭后不可重用。
所有 listener/会前回调排到配置的 Executor，关闭后不再执行尚未派发的通知。
App 保留权限、前台服务、时长提醒/自动停止、页面/悬浮窗、屏幕授权/采集和 PDF。

| 类型/回调 | App 可使用的事实 |
| --- | --- |
| `MeetingStarted` | 本场单调 `startedAtMs`、Server `maxMeetingDurationMs` 和实际能力 |
| `MeetingSessionState` | 阶段、`active`、`canStop`、实际能力；`savedEventCount` 只计成功保存的原文事件 |
| `onDisplay` | 同一份源句/译文、语义 A/B、`swapped` 和真实语言，UI 只呈现 |
| `onTranscriptActivity(nowMs)` | 非空原文到达即排队，不等待 1.5 秒显示窗口；与起点共用单调时基 |
| `onAudioInputMode/onAudioError` | 实际音源和类型化错误；SDK 不申请权限或自动重试 |
| `onResult` | 实际 READY 后交付，先于 READY 状态；LOCAL 的 PDF 工作不应被该状态提前关闭 |

Coordinator 使用轻量串行状态/方向/显示/deadline 队列和独立串行 HTTP/模型启动停止/总结队列；
慢网络不会阻塞句开始、活跃事实和语种截止点。所有模式必需 `transcript.realtime`；互译另需
`translation.realtime`，纪要另需 `summary.structured`，缺少必需能力不启动模型。
speaker 可选，未授予时移除字段、不拒绝开场；总结工厂独立参与求交，不误当实时解析能力。

### 正常停止与结果

`requestStop()` 只在 `canStop=true` 时受理：先冲刷显示，再调用 `beforeStop(continuation)`。
App 停截图 tick，在工作线程完成最后有效截图的 `submitPresentation(offsetMs, jpeg)` 调用并关闭采集，
再调用 continuation；无采集时直接延续。不要阻塞 UI 或遗漏延续，延续仅首次有效，之后不再接纳截图。
JPEG 在调用时复制，`offsetMs` 相对 `startedAtMs`，序号由组合层分配。

随后停止同一音源、排完完整 PCM 尾帧，等待末尾字幕/译文与 FINISHED；
标准事件、截图和按能力生成的纪要均排在后端 Stop 前。
Stop 202 只到 `STOP_PENDING/canStop=true`，用户可再确认，不重复衔接、停音源或已提交纪要。
首次 Stop 受理后生成一次 `genTime`，Manifest、Artifact、链接和邮件共用它；受理不等于 READY。

- WEB：只读 READY 清单，`WEB_AVAILABLE` 和 URL，`content=null`。
- LOCAL：下载真实内容，`LOCAL_READY`；App 用 typed 字幕/译文、全文、纪要和 JPEG 写 PDF。
- EMAIL：只读清单、发送同一 URL；失败仍交付 URL 与 `EMAIL_FAILED`，不是结果失败。
- TRACELESS：正常 Stop 后 `ENDED`，不读 Manifest、不触发 `onResult`，不新增删数据行为。
  音频输入失败后的正常 Stop 同样不主动交付。

session/client `close()` 只是取消，不执行后端 Stop、不生成 READY/结果；希望交付应先正常停止。
当前阶段为 CREATING/RUNNING/STOPPING/FINALIZING、必要时 STOP_PENDING，以及 READY/ENDED/FAILED；
枚举 STARTING/CLOSED 暂未独立通知。

### 默认听悟与国内显示

听悟按同一 `index` 替换全量 partial，原文 final 与独立译文 final 按源句顺序配对；
`stash_result` 保留下一句的 ID/起点。迟到 `detected_source_lang` 可修订标准原文的语种，
已确认原文不会被旧 partial 重新打开。模型结束时仍保留已收到、尚缺译文的原文 final。

显示层通过 `onSegmentStarted()` 在句开始绑定方向；明确方向区 A=180～260°、B=280～360°。
`NO_DIRECTION` 保留上次有效角，`UNSUPPORTED` 按语种降级；后续新句采样不移动已锁侧的旧句。
仅默认听悟、互译且有效模糊方向区启用一次最多1.5秒的语种窗口：最新快照替换旧快照，
可信语种及时到达直接释放，到期/Stop 按会前 A/B 本地评分兜底，平分选 A。

低层 `MeetingDisplayState.nextLanguageDeadlineMs()` 提供最早单调截止点，使用方独立于网络 IO
安排一次定时调用 `releaseExpiredLanguages(nowMs)`；停止调用 `flushPendingLanguages()`。
所有状态更新在同一串行上下文。`snapshot()` 输出不可变 `MeetingDisplaySnapshot`；
显示侧和模型真实语言彼此独立，UI 根据 `isFinal` 渲染80%/100%透明度。
组合入口已由 Coordinator 调度 deadline 和 Stop 冲刷，App 不再复制计时。
`latestTranscriptSegmentId` 表示最近可见原文更新，非列表末行 ID；旧段迟到修订不改首次段顺序，
但更新此字段，字幕浮窗可据此取最新原文。旧五参构造兼容，未知该事实时为 null。
core 的窗口诊断使用 Java 日志 `FINE` 级，包含片段ID/修订/等待时长，不输出正文或凭据。

伙伴只需新增 Adapter 实现，并在会议开始前注册工厂和可解析能力：

```java
ModelAdapterRegistry adapters = new ModelAdapterRegistry(); // 自带听悟、样例和百炼。
adapters.register("partner-json", PartnerAdapter::new, Set.of("transcript.realtime"));
// 将 adapters 交给 AiMeetingClient；Server 本场返回 adapterId，Coordinator 自动选新实例。
```

当前实时传输边界为 WebSocket 加 16 kHz/单声道/PCM16。Adapter 只归一化消息，
不代表任意传输、编码或私有音频协议均已支持。会议模型地址、短期访问材料与本场权限由 Server 决定。

构建：

```bash
./gradlew :model-runtime-contract:jar :meeting-client-core:jar :meeting-client-core:test
```

产物是 `build/libs/meeting-client-core-0.1.0.jar`，不内带 `model-runtime-contract` 或 `org.json`。
Mock App 直接依赖本模块；SDK App 使用私有 AAR 内带的同一 JAR，不再依赖本工程模块。
SDK 从公共契约 JAR 以 `compileOnly` 编译，App 打包唯一一份契约；Android 提供 JSON 类。
`api(project(":model-runtime-contract"))` 仅表达源码模块的编译依赖，不生成合并 JAR。

`MeetingRuntimePort` 是平台/Mock 装配边界，不供业务 App 另写会议流程；真实运行时接收材料/新 Adapter，
本地 Mock 可固定声明能力且不领材料（参数为 null），仍用同一 Coordinator。Android 装配覆盖
`elapsedRealtimeMs()` 为 `SystemClock.elapsedRealtime()`；Mock 应使用同一 Android 单调时基。

P6-5 与 P6-6 两片已独立签收；组合入口使用相同源码生成的 JAR 作为 SDK 输入。
P6-7 继续迁移实际 App Service/页面链路并回归真机，主机环回或 AAR/APK 不代签真实听悟、实体方向、采集或 PDF。
日志 INFO 记录阶段开始/结束/结果，逐段诊断为 FINE；不记录原文、原始载荷、URL、token 或 Throwable 正文。
本轮不建设缓存、备份恢复、通用重试/安全矩阵或新后端协议。

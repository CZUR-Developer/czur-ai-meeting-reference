# 接入自己的实时模型 Adapter

> 扩展开发主线 ｜ 前置阅读：[模型与能力配置指南](model-and-capability-guide.md#5-更换模型时修改边界是什么) ｜ 全部文档见 [文档导航](README.md)

这篇讲一件事：**当你的实时语音模型不是听悟、协议也不是样例格式时，怎么把它接进来。**

先说清楚什么时候不需要写代码：

| 你的情况 | 要做什么 | 看哪篇 |
| --- | --- | --- |
| 用听悟，只是换成自己的账号 | 改配置里的 AppKey／AccessKey | [模型配置指南 3.1](model-and-capability-guide.md#31-听悟先只跑实时字幕) |
| 用仓库自带的样例协议（`sample-json-ws`） | 起样例服务 + 改两个配置 | [从零接入指南](partner-getting-started.md) 或 [准备清单第四节](preparation-checklist.md#四模型还没准备好先跑样例链路) |
| 自己的模型协议和以上都不同 | **写一个后端发行器 + 一个 Android Adapter**，就是本篇 |

## 一、两侧各管什么

```text
你的后端                    参考后端（backend-reference）              设备上的 App
   |                              |                                      |
   |  长期密钥留在这里  --------->  ModelSessionIssuer                      |
   |                              |  签发短期材料（endpoint/token/TTL）    |
   |                              |  -------------------------------->   ModelAdapter
   |  <-------- WebSocket 握手、PCM 音频、你的协议 JSON ------------------  |
   |                              |            解析成标准事件              |
   |                              |            原文／译文／发言段 --> 会议状态机、界面
```

两条硬规矩：

- **长期密钥只在后端**。Adapter 拿到的是本场短期材料（`ModelSessionGrant` 的 token／initParams），不要写进 App、APK 或日志。
- **Adapter 只解析协议**。MIC 采集、PCM 发送、重连、录音留存都由 SDK 管，Adapter 不碰音频。

## 二、后端：实现 `ModelSessionIssuer`

接口在 `backend-core/src/main/java/com/czur/reference/application/model/ModelSessionIssuer.java`，核心只有一个方法：

```java
public interface ModelSessionIssuer {
    Session issue(Request request);   // 每场首次 Create 后调用一次
    // 记录：Request 里有 meetingId、sourceLanguage、targetLanguages、
    //       translationEnabled、multilingualInput；不要从客户端正文另取语言。
}
```

返回的 `Session` 是四个字段：`adapterId`、`endpoint`、`expiresAt`、`initParams`（外加可选的 `translationSession`）。骨架：

```java
public final class MyModelSessionIssuer implements ModelSessionIssuer {
    public static final String ADAPTER_ID = "my-asr";      // 必须与 Android Adapter 的 id() 完全一致
    private static final Duration TTL = Duration.ofMinutes(10);

    private final URI endpoint;      // 你的模型网关地址
    private final String apiKey;     // 长期密钥，只存在后端配置里
    private final Clock clock;

    @Override
    public Session issue(Request request) {
        // 短期限令牌由你的网关签发；拿不到就抛异常，让本场失败得明确。
        String token = exchangeShortLivedToken(apiKey, request.meetingId(), TTL);
        return new Session(ADAPTER_ID, endpoint.toString(),
                clock.instant().plus(TTL), Map.of("token", token));
    }
}
```

可参考 `backend-reference/src/main/java/com/czur/reference/application/model/SampleJsonWsModelSessionIssuer.java`（40 行）。

**装配位置**：默认发行器在 `backend-reference/.../bootstrap/ReferenceMeetingHttpAssembly.java:131` 的 `defaultIssuer()` 里按配置选择。你有两条路：

- 改 `defaultIssuer()`，加一个分支返回你的发行器；
- 不改它，在 `ReferenceClientMeetingProcess.java:78` 或 `ReferenceMeetingHttpAssembly.startClientModel(..., issuer, ...)` 处直接注入自己的实例。

**一个必须知道的坑**：`MeetingDeploymentConfig.java:348-359` 会校验 `AI_MEETING_MODEL_ADAPTER` 的取值，目前只接受 `tingwu-realtime`、`sample-json-ws` 和不填。你把环境变量写成 `my-asr` 而后端没加对应分支时，**启动会直接报错**（`IllegalArgumentException: 参考会议部署配置 AI_MEETING_MODEL_ADAPTER 非法`）。这不是配置写错，是校验还没认这个名字——要么改校验，要么走上面的注入接缝。

## 三、Android：实现 `ModelAdapter`

接口在 `android-reference-app/model-runtime-contract/src/main/kotlin/com/czur/aimeeting/model/ModelAdapter.kt`。**必须实现**的三个：

| 方法 | 作用 |
| --- | --- |
| `id(): String` | 必须等于后端 `Session.adapterId`，否则开会时报“Adapter 与本场模型授权不匹配” |
| `startMessage(grant): String` | 连接建立后发的第一条协议消息（例如 `{"type":"start"}`） |
| `onServerMessage(message, listeners…): ModelSignal` | 解析模型消息，回调标准事件，返回 `AUDIO_READY` / `FINISHED` / `NONE` |

**按需覆盖**的四个：`connectionRequest(grant)`（改握手头，比如 `x-api-key`）、`stopMessage(grant)`、`mayEmitDeferredSourceLanguage()`、`supportsSessionReconnect()` 和 `drainConfirmedOnDisconnect(listener)`。

骨架（照 `meeting-client-core/.../provider/SampleJsonModelAdapter.kt`，37 行）：

```kotlin
class MyModelAdapter : ModelAdapter {
    private val revisions = HashMap<String, Int>()

    override fun id(): String = ID                       // "my-asr"

    override fun connectionRequest(grant: ModelSessionGrant) =
        ModelHttpRequest("GET", mapOf("x-api-key" to grant.initParams().getValue("token")))

    override fun startMessage(grant: ModelSessionGrant) = """{"type":"start","lang":"${grant.initParams()["lang"]}"}"""

    override fun onServerMessage(message: String, listener: TranscriptListener): ModelSignal {
        val json = JSONObject(message)
        return when (json.getString("type")) {
            "ready"    -> ModelSignal.AUDIO_READY      // 收到它 SDK 才开始送音频
            "finished" -> ModelSignal.FINISHED
            "text" -> {
                val segment = json.getString("segment")
                val revision = json.getInt("revision")
                if (revision > revisions.getOrDefault(segment, 0)) {
                    listener.onTranscript(TranscriptEvent(
                        segment, revision,
                        json.getLong("startMs"), json.getLong("endMs"),
                        json.getString("language"), json.getString("text"),
                        json.getBoolean("final")))
                    revisions[segment] = revision
                }
                ModelSignal.NONE
            }
            else -> ModelSignal.NONE                     // 未知类型必须忽略，不能抛
        }
    }

    companion object { const val ID = "my-asr" }
}
```

要同时输出译文，覆盖四参重载并在里面调 `translationListener.onTranslation(TranslationEvent(...))`；要让界面先开一个发言段，再调 `segmentStartedListener.onSegmentStarted(...)`。

**时间基准最容易错**：`startMs`／`endMs` 是**本次模型任务收到的音频**的相对毫秒，从 0 开始。不要自己累加会议偏移、续接偏移或上一段的时长——SDK 会把本代首帧在整场录音中的位置加上去。加错了表现是字幕时间整体漂移。

**注册**：`ModelAdapterRegistry`（`meeting-client-core/.../ModelAdapterRegistry.kt`）构造时已预注册听悟、样例、百炼总结和样例翻译。加自己的只要一行：

```kotlin
val adapters = ModelAdapterRegistry()
    .register("my-asr", ::MyModelAdapter, setOf(MeetingCapabilityState.TRANSCRIPT))

// 在创建会议客户端之前替换注册表（例如 Application.onCreate）；
// 之后每场会按后端下发的 adapterId 新建实例
MeetingAppAssembly.registerModelAdapters(adapters)
```

同 ID 后注册覆盖前者，不影响在途会议；工厂每次要返回新实例，Adapter 不能跨场复用状态。

## 四、声明能力与语言

后端仍按环境变量求交，别漏：

```dotenv
AI_MEETING_MODEL_ADAPTER=my-asr
AI_MEETING_MODEL_LANGUAGES=zh-CN,en-US
AI_MEETING_MODEL_CAPABILITIES=transcript.realtime
AI_MEETING_PROFILE_REQUIRED_CAPABILITIES=transcript.realtime
AI_MEETING_ENTITLEMENT_SUPPORTED_CAPABILITIES=transcript.realtime
```

你声明了 `translation.realtime` 才可能出现互译入口；Adapter 注册时 `register(...)` 的 `capabilities` 也要包含对应能力。两边任一缺失，入口就不会出现——这是设计如此，不是 bug。

## 五、怎么验

**先单测**。两边的现有测试就是模板：

- Android：`app/src/test/java/.../sample/SampleJsonModelAdapterTest.kt`（44 行）——覆盖 partial→final、修订递增、未知类型忽略、格式错误抛异常。
- 后端：`backend-reference/src/test/java/.../model/SampleJsonWsModelSessionIssuerTest.java`——覆盖签发字段、有效期、缺配置时报错。

**再端到端**。用样例服务把整条链路先跑通（见[准备清单第四节](preparation-checklist.md#四模型还没准备好先跑样例链路)），确认“音源 → 模型 → 归一化 → 界面 → 结果页”通了，再把环境变量指向你的模型。设备上要确认：

- [ ] 点开始后，模型收到 `startMessage`，回 `ready` 后开始有音频；
- [ ] 字幕按段出现，同一段修订时文字替换而不是新增一行；
- [ ] `final` 后不再被后续修订覆盖；
- [ ] 正常停止时收到 `FINISHED`，界面收尾、结果页有原文；
- [ ] 断网／模型报错时，界面给出明确错误，不是静默卡住。

## 六、排错对照

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 后端启动报 `AI_MEETING_MODEL_ADAPTER` | `MeetingDeploymentConfig` 不认这个 ID | 改校验或注入自己的发行器（见第二节） |
| 开会报“Adapter 与本场模型授权不匹配” | 后端 `Session.adapterId` 与 Android `id()` 不一致 | 两处写成同一个常量 |
| 一直不收音频 | 没返回 `AUDIO_READY`，或返回了但消息类型判断没命中 | 检查 `ready` 消息分支 |
| 字幕时间整体偏移 | Adapter 自己加了会议偏移 | 只报本次任务内的相对时间 |
| 每次修订多出一行 | 没按 `segmentId` + `revision` 去重 | 照样例维护 revision 表 |
| 未知消息导致崩溃 | 对不认识的 `type` 抛了异常 | 未知类型返回 `ModelSignal.NONE` |
| 互译入口不出现 | 能力未声明或缺翻译 Adapter | 见第四节 |
| 换了模型后录音／结果缺东西 | 产物发布与模型无关，检查 artifact store | [模型指南第 1 节](model-and-capability-guide.md#1-第三方平台与业务功能总表) |

## 七、可以直接抄的文件

| 文件 | 行数 | 用途 |
| --- | --- | --- |
| `backend-core/.../application/model/ModelSessionIssuer.java` | — | 发行器接口与 `Session` 定义 |
| `backend-reference/.../model/SampleJsonWsModelSessionIssuer.java` | 40 | 最小发行器实现 |
| `backend-reference/.../model/TingwuModelSessionIssuer.java` | — | 真实厂商对照：凭据交换、语言映射 |
| `model-runtime-contract/.../ModelAdapter.kt` | — | Adapter 接口与全部默认实现 |
| `meeting-client-core/.../provider/SampleJsonModelAdapter.kt` | 37 | 最小 Adapter 实现 |
| `meeting-client-core/.../provider/TingwuModelAdapter.kt` | — | 真实厂商对照：多语种、修订、分人 |
| `meeting-client-core/.../ModelAdapterRegistry.kt` | — | 注册表与预置项 |
| `tools/sample-json-ws-model.py` | — | 样例服务，端到端验证用 |

# 合作伙伴扩展开发手册

本文面向需要二次开发的合作伙伴。它说明如何从本仓库启动一套可替换的
Android App、Java 21 后端和静态 Web 结果页，并指出各项能力的扩展位置。
本文只描述参考实现的公开边界；不会把 CZUR 的账号、模型密钥、私有地址或
系统服务带入参考工程。

首次接触项目请先读[从零接入指南](partner-getting-started.md)，按准备清单完成真实字幕
闭环。全部第三方平台、资源、能力与缺失行为集中在[模型与能力配置指南](model-and-capability-guide.md)。
本文重点说明已有参考实现和扩展接口，不要求伙伴了解内部开发阶段或CZUR私有工程。

## 1. 交付范围

参考工程包含以下可独立打开和构建的部分：

| 目录 | 作用 | 入口 |
| --- | --- | --- |
| `backend-core/` | 会议状态、协议、能力计划、持久化端口和产物端口 | Maven 普通 JAR |
| `backend-reference/` | 参考 HTTP 进程、演示登录、Mock 和默认装配 | `ReferenceClientMeetingMain` |
| `android-reference-app/` | Kotlin First 的登录、会前设置、会中字幕／互译、停止和结果动作 | Android Studio 工程 |
| `web-reference/` | 读取标准 Manifest 并展示原文、译文、总结和可用媒体 | 静态构建产物 |
| `contracts/` | OpenAPI、JSON Schema、实时事件和 Fixture | 协议依据 |
| `deploy/` | 交付阶段的 Dockerfile、Compose 和唯一 V001 初始化脚本 | 产品部署示例 |

参考 App 默认提供 Web 结果动作。邮箱、本地 PDF、扫码／小程序或伙伴自己的
结果渲染器都通过结果动作扩展，不在核心会议状态机中写死。参考 App 已接入 SDK
截图黑盒会话，内置处理可选启用，输出 Bitmap 或 File；调用方不接触内部算法，
周期、末帧和窗口排除仍由 App 决定。接入方式见下文“SDK 截图扩展”。

## 2. 本机开发和局域网短会

开发验证直接使用本机 Java 21、Maven、Python 3、Node.js/npm 和 Android SDK，
不使用 Docker 作为检查工具。Dockerfile 和 Compose 只用于交付部署；本机没有
隔离数据库时，可以临时运行 `mysql:5.7`，但容器、数据卷和账号不属于交付物。
使用交付 Docker 镜像时，按[部署说明](../deploy/README.md)显式加载配置、初始化
外部 MySQL 并启动应用；同页说明容器上游、Android HTTPS 与静态 Web 的连接关系。

```bash
cd czur-ai-meeting-reference
cp tools/reference-client.env.example tools/reference-client.env
# 编辑 tools/reference-client.env：数据库、演示账号、模型 Adapter 和可写产物目录
bash tools/migrate-reference-client.sh
bash tools/run-reference-client-backend.sh
```

另开终端检查后端和 Web：

```bash
bash tools/check-reference-backend.sh
REFERENCE_HTTP_BASE_URL='http://192.0.2.10:8080' \
REFERENCE_WEB_BIND=0.0.0.0 REFERENCE_WEB_PORT=8090 \
bash tools/run-reference-web.sh
```

将文档占位地址 `192.0.2.10` 换为设备可达的PC局域网地址。完整真实模型、HTTPS与
SDK构建顺序见[从零指南第4节](partner-getting-started.md#4-按步骤完成第一场真实会议)；
模板默认的JSON模型端点需要另行提供服务，直接复制模板不能取得真实识别能力。

参考客户端入口只启动 HTTP 业务接口；Android 通过
`POST /v1/meetings/{meetingId}:modelSession` 取得短期材料后直连模型，音频不经过
后端。开发者在 PC 上为 App 构建注入设备可达的 HTTPS 后端地址和独立结果页地址，
终端用户只需正常登录，不配置 IP／网址。设备自己的 `127.0.0.1` 不表示开发电脑。
开发电脑尚无 HTTPS 入口时，按[本机局域网 HTTPS 联调](local-lan-https.md)
生成仓外证书、启动 Node 流式 TLS 入口，并把 PEM 放到 `app/src/debug/res/raw/reference_debug_ca.pem` 让 Debug APK 信任该 CA。
正式 Release 不携带开发 CA，沿用部署方的正式 HTTPS 证书；`adb reverse` 仅用于诊断。

Android 部署参数只有一处：`android-reference-app/app/src/main/java/com/czur/aimeeting/reference/meeting/ReferenceDeployment.kt`
里的 `BACKEND_URL`（设备可达的 HTTPS 后端根地址）与 `RESULT_WEB_URL`（结果页地址），
改完重新构建。仓库预置的是参考部署地址（`192.168.0.116`），换到别的环境必须替换，
否则设备连不上；设备端没有手输地址界面或地址回退来源。把值改回占位域名时，构建会打印
警告、App 提示"部署地址未配置"。
构建与设备启动命令见 [工具说明](../tools/android-device/README.md)。

数据库统一使用 MySQL 5.7。先由部署方创建隔离数据库，再执行仓库中当前唯一的
`deploy/migrations/V001__initial_schema.sql`（或运行 `deploy/migrate.sh`）。该脚本只新增
`ai_trans_*` 专用表，不创建数据库／账号，也不修改旧业务表。应用启动只做 readiness
检查，不会自动建表或修改表结构。开发机没有隔离数据库时，只能执行 Mock／模块测试，
不能把它写成真实局域网会议验收。

### 模型配置

`tools/reference-client.env.example` 只提供占位值。最小的确定性接线可使用
`sample-json-ws`；真实验证时才改为已获准的 Provider，并将密钥通过本机未跟踪
配置或 Secret 注入：

```dotenv
AI_MEETING_MODEL_ADAPTER=tingwu-realtime
AI_MEETING_MODEL_LANGUAGES=zh-CN,en-US,ja-JP
AI_MEETING_MODEL_CAPABILITIES=transcript.realtime,translation.realtime
```

服务端将 Provider、Product Profile、权益和部署限制求交，得到可用能力；支持语言
则由部署方按实际 Provider 填写 `AI_MEETING_MODEL_LANGUAGES`，`/v1/capabilities`
按该声明直接返回；未配置时为 `zh-CN,en-US`，当前没有 Provider／Profile 语言列表
自动求交或向模型查询语种的过程。参考界面不固定厂商清单。当前听悟实现已有中文、英语、日语、韩语、
德语、法语、俄语七种标准语言映射，部署只声明实际开放的子集。

默认命令行入口的 ASR 发行器支持听悟和 `sample-json-ws`，并可选择组合下文的
独立文本翻译样例。更换已有实现的端点和声明可改配置；接入新模型时，需要在
自己的后端宿主注入 `ModelSessionIssuer`，并在
Android 注册匹配的 `ModelAdapter`，再声明该实现确实支持的语言／能力。仅把
`AI_MEETING_MODEL_ADAPTER` 改成任意字符串不会自动生成供应商实现，会议状态机无须修改。
实际第三方密钥不得写入源码、模板、APK、日志或本手册。

## 3. 登录和鉴权扩展

登录通过 App 层插件装配，会议核心消费已验证身份：

1. App 通过 `IdentityProvider` 读取、保存、恢复和清除登录会话。
2. 参考实现的 `PersistentReferenceIdentityProvider` 只保存可恢复的会话材料，
   不保存密码；退出会清除本地会话。
3. 后端通过 `IdentityProvider` 验证 Bearer 凭据，会议核心只接收已经验证的
   `VerifiedIdentity`。

合作伙伴可在自己的 `MeetingAppAssembly` 中替换 App Provider，也可在后端装配
自己的身份 Provider。替换点不要求修改 `MeetingCoordinator`、会议状态机、模型
Adapter 或结果页。CZUR 的 `ContentProvider/UserHandler` 属于产品侧实现，不是
参考工程的默认依赖。

登录交互通过 App 的 `AppLoginEntry` 单独替换；默认入口未登录时打开参考表单，
已登录时提供“切换账号／退出登录”。取消或登录失败保留原会话，新登录成功才替换，
明确退出才清除。伙伴在
`MeetingAppAssembly.initialize(context)` 后、创建会议页面前装配自己的
`IdentityProvider` 和 `registerLoginEntry(...)`：

```kotlin
MeetingAppAssembly.registerIdentityProvider(partnerIdentityProvider)
MeetingAppAssembly.registerLoginEntry(AppLoginEntry { activity, defaultEntry, onIdentityChanged ->
    // 打开伙伴自己的登录界面；其成功回调更新 partnerIdentityProvider，再通知页面。
    partnerLogin.open(activity) { onIdentityChanged() }
    // 如使用参考账号密码流程，可改为 defaultEntry()。
})
```

替换入口会完全接管账号切换，不会先执行默认退出。会话保存／恢复由伙伴 Provider
负责，回调只触发重新读取身份和能力；SDK 不申请账号、不弹登录 UI，也不引用系统账号。
默认插件将会话与构建后端配对：同一后端升级／重启恢复有效登录，更换后端的 APK 不复用
原后端会话。伙伴自定义身份插件应保持这种归属；它不提供运行时切换部署地址的入口。

## 4. 模型和能力扩展

### 实时模型

Android 端通过 `ModelAdapter` 接收 Provider 数据，将原文、译文、修订、发言人和
结束信号归一化为公共事件。新增 Provider 时：

1. 实现 `ModelAdapter` 及其生命周期；
2. 在 `ModelAdapterRegistry` 注册 Adapter ID；
3. 后端实现对应的 `ModelSessionIssuer`，只签发短期连接材料；
4. 在部署配置中声明支持的语言和能力；
5. 用现有 Adapter 契约测试验证 partial/final、修订和停止时序。

模型原始 JSON 和厂商短码留在对应 Adapter／Issuer 内部；永久密钥只由后端发行器的
外部配置持有，不放入 Android Adapter、APK 或 Web。设备直连模型时，后端接收
归一化的标准事件和用量事实，另按本场策略接收留存录音／截图。

`ModelHttpRequest` 是交给 SDK 执行的中性请求描述，支持自定义 `method`、
`headers`、`contentType` 和文本 `body`；地址来自本场 Server Grant。实时 Adapter
通过 `connectionRequest(grant)` 描述握手，总结／文本翻译 Adapter 通过 `request(...)`
描述请求。例如伙伴可以使用 `x-api-key` 或已签名 URL，SDK 不再额外追加 Bearer。
原默认实时 GET、总结 POST/JSON 和非空 token 的 Bearer 行为保持。
线程、时间及扩展接口见 [公共 Android core 说明](../android-reference-app/meeting-client-core/README.md#模型请求描述与独立文本翻译)。

### 可选独立文本翻译

仅提供 ASR 时，可在服务端把 `ModelSessionIssuer` 与 `TranslationSessionIssuer`
通过 `TextTranslationModelSessionIssuer` 组合，Android 注册匹配的 `TranslationAdapter`。
参考命令行入口已有可选 JSON 样例，外部配置如下：

```dotenv
AI_MEETING_SAMPLE_JSON_TRANSLATION_ENDPOINT=https://translation.example.invalid/translate
AI_MEETING_PROFILE_OPTIONAL_CAPABILITIES=translation.realtime
AI_MEETING_ENTITLEMENT_SUPPORTED_CAPABILITIES=transcript.realtime,translation.realtime
AI_MEETING_MODEL_CAPABILITIES=transcript.realtime,translation.realtime
```

部署方须提供该 HTTP 服务，并声明 ASR 与翻译实际支持的语言。样例 Adapter ID 为
`sample-json-translation`，请求包含 `sourceSegmentId/sourceRevision/sourceLanguage/targetLanguage/text`，
响应为 `{"text":"译文","sourceLanguage":"实际源语种（可省略）"}`。参考发行器不带 token，
五分钟有效；它用于协议接线，不包含真实翻译模型，不能凭样例通过宣称模型质量已验。
正式供应商自行实现发行器和 Adapter；长期 Key 不交给 App。

首次材料随同场 `:modelSession` 的可选 `translationSession` 返回；SDK 对已确认原文逐段
调用，按实际源语种选择会前两语中的目标语。下一段请求前距离过期不超过 30 秒时，
仅调用同场 `POST /v1/meetings/{meetingId}:translationSession` 续领，不重建 ASR 或音源，
也不后台轮询。组合后关闭 ASR 原生译文并保留双语识别；未配置时听悟一体原译文路径保持。
停止先停源，已接纳的译文仍沿原标准事件和确认流程收口，不另建会议状态机。

### 可选能力

能力缺失时保持剩余链路可用，不生成空产物或假入口：

| 能力 | 缺失时的 App 行为 | 缺失时的 Web 行为 |
| --- | --- | --- |
| `translation.realtime` | 隐藏互译入口，只保留原文 | 无译文产物时隐藏译文区 |
| `summary.structured` | 不显示纪要开关，不请求总结材料，Stop 后仍可回看文字 | 无总结产物时隐藏总结区 |
| `speaker.diarization.session` | 不显示发言人列表，不把空 ID 当作发言人 | 原文按实际数据展示，不虚构分人结果 |
| `speaker.identity.cross_meeting` | 不请求跨会议身份；本场临时 Speaker 不冒充跨场身份 | 隐藏自动识别选项；有本场编号时仍可人工改名，不设独立人物栏目 |

`transcript.realtime` 是参考会议的必选能力；未声明时 App 保持开始按钮禁用，
后端拒绝 Create。上述行为由能力声明和本场 Create 决定共同驱动，不能靠固定的
“中文／英文”或“听悟已配置”推断能力。

### 录音留存策略

`audio.original` 是结果产物类型；是否保留录音使用既有 Create 字段
`artifactPolicy.retainAudio`，不放入模型能力清单。默认参考 App 在 SDK 运行时保留
录音，Mock 运行时不保留。伙伴可在自己的会前装配中复用已有 API 关闭：

```kotlin
val withoutRecording = options.withCreateParameters(
    title = options.title(),
    timezone = options.timezone(),
    retainAudio = false,
)
// 将 withoutRecording 交给原有会议启动流程，仍使用同一个 SDK 和结果链。
```

此选项不关闭实时采音或字幕／互译，只控制本场录音留存及上传；停止后的 Manifest
不含音频时，Web 隐藏播放器，已有文字／总结仍正常交付。跨会议身份参考实现依赖
会后录音，关闭留存时也不请求该可选能力。参考页面当前不额外增加录音开关，伙伴
可按自己的产品流程决定该参数；不需要增加新的 Server capability 或结果状态机。

演示采集同样使用既有 Create `artifactPolicy.presentationCapture`；
`presentation.capture` 是其结果产物类型，不作为模型能力配置。未启用时不启动
采集、Web 不显示演示区。SDK 构建已接入内置处理的黑盒会话；干净 Mock 不提供
截图引擎，其演示开关保持禁用。

### SDK 截图扩展

系统授权与前台 Service 仍由 App 管理，取得 `ScreenCaptureClient` 后为每场创建
独立会话。参考 App 显式开启内置处理；伙伴也可选择 `enableProcessing = false`
取得未处理原图。公开接口只返回图片、正常的空结果或技术失败，不提供算法判断原因。

```kotlin
// 每场只创建一次；目录预先在 IO 上创建，窗口句柄每次在主线程取最新值。
val images = captureClient.createSession(enableProcessing = true)
suspend fun captureOneFrame() {
    val image = images.captureFile(screenshotDirectory, currentWindowTokens()) ?: return
    try {
        // 伙伴自己的业务方法：返回时必须已经保存成功或收到服务端真实保存 ACK。
        saveOrUploadAndAwaitAck(image.value)
        image.confirmAccepted()
    } finally {
        // 示例选择不保留临时 File；取消时同样完成 IO 清理，不阻塞主线程。
        withContext(NonCancellable + Dispatchers.IO) { image.value.delete() }
    }
}
```

上述保存方法是伙伴业务的示意，不是 SDK 的上传入口。参考 App 对应接线使用现有
三参 `MeetingSession.submitPresentation`，只有 `onSuccess` 后才确认；两参无回调入口
只代表入队，不能用作保存确认。失败不确认，后续仍可取得该图；`null` 正常续接。

需要 Bitmap 时改用 `captureBitmap(...)`。开启处理的 File 为 JPEG，关闭处理的 File
为 PNG；交付后的 Bitmap／File 归调用方管理。确认不回收 Bitmap、不删除 File，
也不执行网络请求。SDK 处理、编码和文件输出不阻塞主线程，App 自己的读写同样须放 IO。

App 决定周期、末帧、排除哪些窗口及会议相对时间。每场沿用一次在途截图／提交，
周期帧 ACK 结束后才开始末帧；末帧成功、无新图或失败均须续接原会议停止流程。
停止后关闭 `images`，释放本场状态；`captureClient.close()` 另行释放原采集授权。
完整参考接线见 [Android 截图说明](../android-reference-app/README.md#独立sdk截图与参考演示帧)。

## 5. 结果动作和 Web 扩展

结果交付通过 `ResultActionPlugin` 注册。默认 `WebResultActionPlugin` 生成统一
结果链接；插件只负责准备、执行和展示自己的动作，不复制会议状态机，也不修改
Manifest。

默认 Web 插件只保存最近一场的完整原链接，会前不再单设“查看上场结果”按钮；
历史回看统一走参考 App 的会议记录列表（`GET /v1/meetings` 列出本账号会议，点开时用
当前时间现生成结果链接）。两条路径都沿用原 `genTime` 语义，不恢复已结束的会议。

伙伴可以新增：

- 本地离线 HTML 或 PDF 渲染器：先通过动作上下文异步读取正文，再生成自己的文件并交给 App；
- 邮件动作：发送同一结果链接或伙伴自己的附件策略；
- 小程序／扫码动作：在 App 中接入伙伴接收端和 Hook；
- NAS、企业 IM 或其他结果接收器：实现同一个动作接口。

### 本地结果插件必须按需读取正文

正常进入 `READY` 时，`context.result()` 提供本场 ID、标题、状态、`genTime` 和
产物类型清单，`content()` 通常为 `null`。它不意味着正文为空。需要本地渲染的
插件须调用 `context.access().readContent(...)`，取得同场实际 READY 的文字、
译文、总结和演示帧等正文；默认 Web 动作只生成链接，不需要提前下载这些内容。

下面是可装配到结果动作注册表的最小 Kotlin 示例。`writeHtml` 与 `openHtml` 是
伙伴注入的文件生成和页面展示函数；示例未附带离线 HTML 渲染器：

```kotlin
import com.czur.aimeeting.client.MeetingClient
import com.czur.aimeeting.client.MeetingOptions
import com.czur.aimeeting.client.MeetingResult
import com.czur.aimeeting.client.MeetingResultContent
import com.czur.aimeeting.client.result.ResultActionOutcome
import com.czur.aimeeting.client.result.ResultActionPlugin
import java.io.File

class LocalHtmlAction(
    private val writeHtml: (MeetingResult, MeetingResultContent) -> File,
    private val openHtml: (File) -> Unit,
) : ResultActionPlugin {
    override fun id() = "local.html"
    override fun displayName() = "本地查看"
    override fun requiredArtifactTypes() = setOf("transcript.final")

    override fun prepare(options: MeetingOptions) =
        ResultActionPlugin.Preparation.allow { context, callback ->
            context.access().readContent(object : MeetingClient.Callback<MeetingResultContent?> {
                override fun onSuccess(content: MeetingResultContent?) {
                    if (content == null) {
                        callback.onError("本场正文不可用")
                        return
                    }
                    context.workExecutor().execute {
                        runCatching { writeHtml(context.result(), content) }.fold(
                            onSuccess = { file ->
                                context.uiExecutor().execute {
                                    runCatching { openHtml(file) }.fold(
                                        onSuccess = {
                                            callback.onSuccess(ResultActionOutcome.success(id(), "已打开本地结果"))
                                        },
                                        onFailure = { callback.onError("无法打开本地结果") },
                                    )
                                }
                            },
                            onFailure = { callback.onError("生成本地结果失败") },
                        )
                    }
                }
                override fun onError(error: MeetingError) {
                    // SDK 只回稳定错误码；宿主映射自己的文案。
                    callback.onError("读取本场正文失败")
                }
            })
        }
}
```

`MeetingClient.Callback` 的稳定错误入口是 `onError(error: MeetingError)`；宿主按
`MeetingErrorCode` 映射自己的文案，SDK 不返回终端句子。`onError(message: String)`
仅为旧宿主插件保留，SDK 自身不调用。

文件读写／渲染交给 `workExecutor()`，页面操作交给 `uiExecutor()`；注入函数不能
捕获已销毁的 Activity，异步动作最终只回调一次成功或失败。正文读取、文件生成或
展示失败属于结果动作失败，重试该动作即可，不重新停止会议或重复结算。

渲染时以 `MeetingResultContent.artifactTypes()` 和各字段实际内容决定区域；无译文、
总结或图片时不造空区。`readContent` 不打包留存录音文件；完整离线媒体方案还需按
Manifest 的 `ArtifactRef` 单独流式下载录音，再以本地相对路径引用。
实际 PDF 接线见 [LocalPdfResultActionPlugin](../android-reference-app/app/src/main/java/com/czur/aimeeting/reference/LocalPdfResultActionPlugin.kt)，
接口生命周期见 [公共客户端核心说明](../android-reference-app/meeting-client-core/README.md)。

Web 只读取 `READY` Manifest。原文、译文、结构化总结和录音分别由真实
`ArtifactRef` 决定是否展示；录音使用 `GET /v1/artifacts/{artifactId}?genTime=...`
流式播放，不把长音频塞进 JSON。伙伴更换结果页面时，仍应复用 Manifest、产物类型和
有效期规则。

默认结果页提供大屏原文／总结分栏、小屏标签、原译对照、底部播放器、原文点击定位
与跟随、演示大图及复制。浏览器还可导出文本／录音、打印演示 PDF；App WebView 默认
隐藏需要下载／打印宿主的入口。页面完全依赖标准产物，缺少总结、翻译或录音时
对应区域自动收敛；人物数据只用于原文姓名，不单独占右栏。字段、样式和交互层的替换位置见 [Web 结果页](../web-reference/README.md)。

保留录音时，默认 HTTP 上传与 file／S3 Store 使用同一预算函数：
`max(64 MiB, AI_MEETING_MAX_MEETING_DURATION_MS × 32 + 1 MiB)`。
它按标准 16 kHz／mono／PCM16 正常回退计算，不要求设备一定具有 Opus 编码能力；
实际时长仍使用冻结采音事实，容量余量不计费用量。伙伴自建 HTTP transport、
Artifact Store 或反向代理时，应对齐该最长会议预算，避免录音上传失败阻塞后台收尾。

默认参考 Web 在原文姓名处提供“单个／全局”改名；持有效结果链接即可修改，无需再次登录。
全局仅指本场同一发言人。
未配置声纹时仍可人工改名。有本场跨会议身份能力和产物时可勾选“下次自动识别”，
额外登记到可替换 Provider。登记失败仍保留本场已保存名称并明确提示。
编辑沿用原 `genTime` 的有效期，登记归属使用会议真实所属账号，不伪造访客身份。
后端 `SpeakerResultAccessPolicy.Factory` 可替换此规则；选择所有者登录策略时，
通过 `resultSpeakerEditingRequiresLogin=true` 告知 Web，再接入自己的认证方式。
这不改变 App 登录、会议控制及独立人物库管理的授权。
`speaker.identity` 是单独的标准产物，本场 speaker 编号与跨会议 `personId` 分开。
参考讯飞插件、FFmpeg 依赖和可选环境项见 [后端说明](../backend-reference/README.md#可选跨会议声纹参考插件)
及 [本机配置模板](../tools/reference-client.env.example)。未注册 Provider 时自动识别选项隐藏，
字幕／译文／总结和录音照常交付；伙伴可用自己的 `SpeakerIdentityProvider` 替换供应商与人员库。
写入协议、姓名优先级和登录扩展见 [Web 发言人编辑](../web-reference/README.md#发言人改名与可选跨会议记录)。

## 6. 交付前检查

在提交参考代码前运行：

```bash
bash tools/check-reference-delivery.sh
bash tools/verify-local.sh
cd android-reference-app && ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

`check-reference-delivery.sh` 只检查公开交付所需的目录、脚本、模板、未跟踪密钥
边界和 Git 差异格式；它不代替隔离数据库、真实 Provider、Android 设备或跨会议
声纹验收。最终验收应记录以下四类证据，不能互相替代：

- 静态检查和协议 Fixture；
- Maven／Gradle／Web 模块构建；
- 本机局域网数据库、Provider 和设备的真实短会；
- 两场真实会议的跨会议声纹结果（启用该能力时）。

合作伙伴应使用自己的部署、设备和模型逐项记录上述结果；可选能力未配置时，按实际
降级行为验收。已在会前选择的互译或总结若未获得本场授权，App会提示且不启动该场，
不会静默替换所选业务。模型配置和最小验收清单见[从零接入指南](partner-getting-started.md)。

## 7. 参考仓与 CZUR 产品边界

本仓是可修改的开源参考实现；闭源 SDK 二进制及其许可由配套交付包提供，
CZUR系统实现、生产账号体系和云端私有服务不属于本仓交付。合作伙伴通过公开接口
扩展自己的实现，真实密钥、证书、生产地址和用户数据不随参考源码公开。

公共会议能力只维护一份源码：服务端由 `backend-core` 普通 JAR 提供，Android 由
公共 core／协议和闭源 SDK 分层提供。伙伴宿主消费配套构建的 JAR／AAR，
在产品装配层接入自己的账号、模型材料、结算和结果发布插件；不复制状态机或依赖整个
参考应用。新供应商接口由对应 Adapter／Provider 适配，参考默认实现与产品私有实现独立。

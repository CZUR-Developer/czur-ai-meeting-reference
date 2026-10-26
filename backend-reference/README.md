# Backend reference implementation

Java 21 参考应用，负责演示登录、默认插件选择、进程启动和参考测试。
公共会议领域、协议、能力计划、HTTP 路由和 JDBC 实现在
[`backend-core`](../backend-core/README.md)，通过普通 JAR 依赖复用，
宿主无需依赖整个参考应用或复制状态机。

首次接入请依次阅读：

1. [从零接入指南](../docs/partner-getting-started.md)：环境、资源和首场真实会议。
2. [模型与能力指南](../docs/model-and-capability-guide.md)：供应商、能力与可缺失组合。
3. [伙伴扩展手册](../docs/partner-integration-guide.md)：替换登录、模型、存储和结果交付。
4. [部署说明](../deploy/README.md)：外部 MySQL、配置和后端应用镜像。

## 默认运行链

`ReferenceClientMeetingMain`／`ReferenceClientMeetingProcess` 是本机和 Docker
交付的默认入口。它只启动 HTTP 业务服务；Android 通过
`POST /v1/meetings/{meetingId}:modelSession` 领取短期材料，再由 SDK 直连模型。
标准原文、译文和用量提交到会议后端，选择保留的封口录音也会上传后端。
默认进程不启动服务端 WSS 音频入口、固定文本 Mock Worker 或第二套会议状态。

```text
Android App → SDK → 实时模型
                ↘ 会议后端 → MySQL／产物存储 → Web 结果
```

在仓库根目录运行：

```bash
cp tools/reference-client.env.example tools/reference-client.env
# 先编辑本机配置，再初始化与启动；不要提交填好凭据的配置。
bash tools/migrate-reference-client.sh
bash tools/run-reference-client-backend.sh
# 另一个终端检查：
bash tools/check-reference-backend.sh
```

部署方须先创建 MySQL 5.7 专用数据库与账号。初始化脚本只执行仓库唯一的
[MySQL V001](../deploy/migrations/V001__initial_schema.sql)，应用启动只检查基线，
不自动建表。`tools/reference-client.env.example` 默认选择 `sample-json-ws` 协议样例，
需要另行启动对应的样例模型服务；它不自带真实识别能力。
真实听悟接入须更改 Adapter、注入已开通凭据并调整语言与能力声明。

`AI_MEETING_HTTP_BIND=0.0.0.0:8080` 是后端 HTTP 上游；Android 登录与后端地址
使用设备可达的 HTTPS 入口，见[局域网 HTTPS 指南](../docs/local-lan-https.md)。
设备自己的 `127.0.0.1` 指设备本身。静态 Web 另部署，本机示例为：

```bash
# 将占位地址替换为设备可达的开发电脑局域网地址。
REFERENCE_HTTP_BASE_URL='http://192.0.2.10:8080' \
REFERENCE_WEB_BIND=0.0.0.0 REFERENCE_WEB_PORT=8090 \
bash tools/run-reference-web.sh
```

`run-reference-web.sh` 未设置 `REFERENCE_HTTP_BASE_URL` 时使用本机 loopback；
设备回看必须显式配置可达地址。App 的后端与结果页地址在构建时注入，
终端用户只输入示例账号和密码。

## 源码与装配边界

| 实现 | 所在位置与职责 |
| --- | --- |
| `ReferenceClientMeetingProcess` | 本模块：默认客户端直连模型进程 |
| `ReferenceMeetingHttpAssembly` | 本模块：参考插件选择与公共装配的薄封装 |
| `ReferenceDevIdentityProvider`、预置登录入口 | 本模块：显式启用的单账号参考实现 |
| 讯飞人物 Provider、FFmpeg 样本处理与人员映射 | 本模块：可选会后插件 |
| `domain/`、`application/port/`、公共 HTTP／JDBC | `backend-core`：共享领域与框架实现 |
| `src/test/java/` | 本模块：参考行为和公共核心测试 |

IntelliJ IDEA 打开仓库根 `pom.xml` 即可导入两个模块。生产宿主直接依赖
`backend-core`，在装配层注入自己的账号、模型、权益结算、存储和结果发布实现。
源码归属及维护规则见 [`SOURCE.md`](SOURCE.md)。

## 能力和语言声明

`GET /v1/capabilities` 返回部署可用功能，依据产品档位、权益、模型能力、
可选 Provider 是否装配及部署限制计算。Create 的 `capabilityDecisions`
再冻结本场授权；发现接口不代表当前用户或某场会议已获准全部功能。

最小配置只开放 `transcript.realtime`。增加翻译、总结或本场分人时，
须同时配置 Profile、权益和实际模型能力，并装配真实实现。总结还需要
`SummarySessionIssuer`；跨会议身份来自单独人物 Provider。
本场未请求的可选能力返回 `NOT_REQUESTED`，未获准所选翻译／总结时 App 提示并阻止启动。

语言另由 `AI_MEETING_MODEL_LANGUAGES` 声明；省略时使用 `zh-CN,en-US`。
发现接口的 `languages` 直接返回该有序列表，创建会议也按该列表校验语言。
框架不会自动查询供应商语种，或对 Provider／Profile／配置执行语言求交。
部署方更换模型时，须自行核实并同步声明 Adapter 与模型实际支持的语言。

### 听悟临时会话

选择 `AI_MEETING_MODEL_ADAPTER=tingwu-realtime`，并显式启用
`AI_MEETING_TINGWU_ENABLED=true`、注入听悟 AppKey 与 RAM AccessKey 后，
已认证且归属本场的 `:modelSession` 请求调用听悟实时 `CreateTask`。
响应包含 `adapterId=tingwu-realtime`、临时连接 `endpoint`、`expiresAt`
及 `initParams.taskId`；听悟没有单独的 `token` 字段。RAM 长期 Key 不下发 App。

参考映射覆盖 `zh-CN/en-US/ja-JP/ko-KR/de-DE/fr-FR/ru-RU`，
实际开放范围仍由已开通供应商能力与部署声明决定。实时任务按未知人数请求说话人分离；
双语会话根据所选两语生成多语识别参数与双目标翻译。
Adapter 有映射不等于供应商在所有组合中均保证同样的识别或翻译效果。

### 可选独立文本翻译

`AI_MEETING_SAMPLE_JSON_TRANSLATION_ENDPOINT` 有值时，参考进程通过
`TextTranslationModelSessionIssuer` 组合 ASR 与独立翻译材料，
以 `adapterId=sample-json-translation` 演示 HTTP JSON 协议；
需要另行提供对应服务，不自动连接任何翻译供应商。
未配置时保留模型原生翻译或仅 ASR 路径。

正式供应商实现公共 `TranslationSessionIssuer`，在 Android 注册对应
`TranslationAdapter`。SDK 在短期材料将过期时调用同场 `:translationSession`
续领，不新建 ASR 任务或会议；翻译仍须通过本场授权。
详细格式见[可选独立文本翻译](../docs/partner-integration-guide.md#可选独立文本翻译)。

### 可选百炼结构化总结

显式配置 `AI_MEETING_BAILIAN_SUMMARY_ENABLED=true`、`AI_MEETING_BAILIAN_APP_ID`
和 `DASHSCOPE_API_KEY` 后，已启动且获准 `summary.structured` 的会议可调用
`POST /v1/meetings/{meetingId}:summarySession`。后端换取短期 Key，
将短期 Bearer、应用 completion 地址和有效期交给 SDK，永久 Key 不下发。
短期材料 TTL 默认 300 秒，允许 1～1800 秒。

SDK 调用百炼应用 API，将响应归一化为标准总结后在 Stop 前提交。
底层大语言模型由百炼应用配置决定，参考代码不固定某个模型型号。
伙伴可以注入其他 `SummarySessionIssuer` 并注册对应 Android Adapter；
未装配发行器时发现接口不开放总结，App 隐藏入口，结果页不显示空总结。

### 可选跨会议声纹参考插件

实时 ASR 负责本场 `sessionSpeakerId`；跨会议识别由可替换的会后
`SpeakerIdentityProvider` 承担，默认关闭。选择讯飞示例所需环境配置如下：

| 配置项 | 意义与默认值 |
| --- | --- |
| `AI_MEETING_SPEAKER_IDENTITY_PROVIDER` | 空或 `none` 关闭；`xfyun` 显式选择参考实现 |
| `AI_MEETING_XFYUN_VOICEPRINT_APP_ID`、`API_KEY`、`API_SECRET` | 三者均使用完整 `AI_MEETING_XFYUN_VOICEPRINT_` 前缀；启用时必填 |
| `AI_MEETING_XFYUN_VOICEPRINT_ENDPOINT` | 供应商 HTTPS 端点，默认讯飞公开声纹端点 |
| `AI_MEETING_XFYUN_VOICEPRINT_SERVICE_ID` | 默认 `s1aa729d0`，须与供应商接口对应 |
| `AI_MEETING_XFYUN_VOICEPRINT_GROUP_PREFIX` | 默认 `reference_`；按部署与主体隔离分组 |
| `AI_MEETING_XFYUN_VOICEPRINT_MATCH_THRESHOLD` | 默认 `0.6`，供应商分数范围 0～1 |
| `AI_MEETING_XFYUN_VOICEPRINT_TIMEOUT_MS` | 单次 HTTP 超时，默认 `10000` 毫秒 |
| `AI_MEETING_FFMPEG` | 默认 `ffmpeg`；应用镜像已提供，本机 JVM 运行须自行安装 |
| `AI_MEETING_SPEAKER_PCM_SAMPLE_RATE`、`AI_MEETING_SPEAKER_PCM_CHANNELS` | 裸 PCM 默认 16000 Hz／单声道；容器录音按头部解码 |

Profile optional 与权益支持列表须声明
`speaker.diarization.session,speaker.identity.cross_meeting`；实时模型能力只声明
真实本场分人，跨会议能力由已装配人物 Provider 注册。每场还须请求该能力并保留录音。
不提供声纹时，App／Web 只展示实际本场分人，不虚构跨会议身份。

唯一 MySQL V001 已包含可选人员映射表。启用插件时只读核对该表，
默认会议 readiness 不依赖它。标准 `person_id` 和私有供应商特征引用分开保存，
不向 Web 公开供应商编号。会后按同一 speaker 的最终时间窗提取 PCM 样本，
样本不足保留未识别；供应商故障不阻断其余产物 READY。

已登录用户可调用
`POST /v1/meetings/{meetingId}/speakers/{sessionSpeakerId}:enroll`，
正文仅 `{"displayName":"人物名称"}`，成功返回 `personId`、`displayName`、
`manifestRevision`。注册更新身份产物与清单，原文、录音、时间和原结果链接保留。
匿名结果链接没有写权限。替换供应商只改 Provider 与工厂，不复制状态机。

### 可选邮件接口

后端具有可选 SMTP 结果链接接口。配置 `AI_MEETING_RESULT_EMAIL_ENABLED=true`
及 SMTP 参数后，发现接口报告 `resultEmailEnabled=true`，
`POST /v1/meetings/{meetingId}:sendResultEmail` 可向 1～10 个收件人发送结果页链接，
不发送 PDF 附件。它仍要求本场归属和有效结果链接。
当前参考 App 默认仅提供 Web 结果动作；接入邮件还须自行注册应用侧结果插件。

## 宿主扩展与资源管理

自定义宿主优先直接使用 `MeetingHttpAssembly.startClientModel(...)`：

```java
var config = MeetingDeploymentConfig.fromEnvironmentWithExternalIdentity(
        environment, "meeting-api", "partner-android");
try (var assembly = MeetingHttpAssembly.startClientModel(
        config, clock, artifactStore, identityProvider, entitlementPolicy, modelIssuer,
        java.util.function.UnaryOperator.identity(), loginEndpoint, summaryIssuer)) {
    // 外层 Servlet／Spring transport 复用此唯一 dispatcher，不另开内部转发服务。
    var dispatcher = assembly.dispatcher();
}
```

| 扩展点 | 责任 |
| --- | --- |
| `IdentityProvider`／`SessionLoginEndpoint` | 校验伙伴凭据、提供登录；参考账号不要求延用 |
| `EntitlementPolicy` | 读取开会资格并生成预占候选，公共 JDBC Provider 保留持久用量事实 |
| `UnaryOperator<EntitlementProvider>` | 可选外部结算装饰器，Create／Stop 共用返回实例 |
| `ModelSessionIssuer`／`TranslationSessionIssuer`／`SummarySessionIssuer` | 为本场 Adapter 发行短期模型材料 |
| `ArtifactStore` | 参考 file／S3，或伙伴自定义对象存储 |
| `SpeakerIdentityFactory` | 可选会后人物插件，共用唯一数据库 Context 与 Store |

身份、策略与模型实现须支持并发调用。装饰器须继续委托唯一 JDBC Provider，
保持同场用量事实与重放语义，不能只在外部扣费而跳过会议账本。
`assembly.databaseContext()` 提供唯一连接上下文，调用方不得单独关闭它。
关闭顺序为外层 HTTP → Assembly → 宿主自有资源。

`S3ArtifactStore.ObjectsApi` 允许通过 `get/putAbsent/delete/close` 对接原生 OSS 等传输，
复用现有产物发布与读取逻辑；条件写入、namespace 和真实端点可用性由部署方确认。
小型标准产物与长录音采用对应读取／流式通路，不能把长录音一次读入 JSON。

### 最终事实与结果读取

停止采音后 SDK 提交实测 `audioDurationMs`，后端冻结同场值，再在 Stop 中结算并发布 READY。
16 kHz、单声道、PCM16 以字节数除以 32 得到毫秒数，实测 0 也是有效用量。
不要将模型收尾或总结等待时间当作采音时长。

宿主需要生成自己的发布格式时，可在尾事件与用量保存后读取最终快照：

```java
var snapshot = JdbcClientMeetingResultFinalizer.readFinalSnapshot(
        assembly.databaseContext(), verifiedContext, meetingId);
if (snapshot instanceof JdbcClientMeetingResultFinalizer.Ready ready) {
    var finalTranscripts = ready.transcripts();
    var finalTranslations = ready.translations();
    var optionalSummary = ready.summary();
    long capturedDurationMs = ready.frozenAudioDurationMs();
}
```

`Ready` 只表明同场最终事实已读取，不代表结算、发布或会议 READY。
该读取不调用模型、不写产物和状态；长录音和截图只返回描述符。
客户端结果内容、Manifest 与媒体流沿用统一访问决定，
`artifactMediaPort()` 读句柄在流结束后由调用方关闭。

## 数据库范围

默认客户端运行链使用 MySQL 5.7 与 `JdbcDatabaseContext`，全部会议表为
`ai_trans_*`。会议命令工作单元将会议事实、幂等结果和命令 Outbox 放在同一事务。
先执行唯一 MySQL V001，启动只读检查连接和初始化结果，不修改其他业务表。
连接池与借出连接由对应所有者释放，不为每个插件建立第二套会议事实源。

持久层只支持 MySQL 5.7：`JdbcDatabaseEngine` 只有 `MYSQL`，PostgreSQL 适配与
相关测试夹具已移除；非默认服务端 WSS／Mock 入口仍通过
`ReferenceMeetingHttpAssembly.start(...)` 装配，但同样使用 MySQL 基线。

`startClientModel(...)` 不创建服务端音频 Worker、WSS、删除或实时 Token 续签组件，
相关有效 HTTP DELETE／renew 路由返回 501；Create 的 `realtime` 字段仅保留协议兼容。
`AI_MEETING_WSS_ORIGIN` 的存在不表示默认后端中转实时音频。

## 构建与验证

在仓库根运行：

```bash
mvn -B -f pom.xml clean test
bash tools/verify-local.sh
```

Maven 从根 `contracts/realtime.proto` 使用固定版本 protoc 生成
`backend-core/target/generated-sources/` 下的 Java 类，不依赖本机预装 protoc，
也不提交生成文件。默认测试不启动数据库或厂商模型；外部数据库测试需显式提供夹具。

MySQL 集成测试只接受 loopback 的 `ai_trans_m1_*` 隔离库。
先建库并执行同一 V001，再按下例传入测试配置。部分测试会创建和删除自己唯一命名的
`ai_trans_m2_*` 随机库，测试账号须有该权限；禁止指向部署库或其他已有业务库。

```bash
REFERENCE_TEST_MYSQL_DATABASE_URL='jdbc:mysql://127.0.0.1:3306/ai_trans_m1_local?useSSL=false&allowPublicKeyRetrieval=true' \
REFERENCE_TEST_MYSQL_DATABASE_USER='meeting' \
REFERENCE_TEST_MYSQL_DATABASE_PASSWORD='<从测试 Secret 注入>' \
mvn -B -f pom.xml clean test
```

固定文本 `MockModelDriver` 与 `AlternativeTextModelDriver` 均不访问真实模型。
`ModelDriverContractTestKit` 验证对应端口生命周期与事件契约，
不代替 SDK 直连模型、供应商质量或真实设备会议验收。
真实接入按[从零指南的验收清单](../docs/partner-getting-started.md)确认。

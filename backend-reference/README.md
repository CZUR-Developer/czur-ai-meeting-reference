# Backend reference implementation

这是公开仓中的 Java 21 参考后端，用于展示会议领域状态、能力计划、可替换的
Model Driver 端口和 PostgreSQL 事实源边界。领域、命令和应用端口保持与 HTTP
框架、数据库实现、厂商 SDK 和生产服务解耦；Provider 特有的响应转换只能位于
Driver 实现中。

## 当前公开范围

- `domain/meeting/`：会议状态机、聚合、不变量和快照恢复；本次同步新增部署范围语义，
  并保留原有文件与测试。
- `application/command/`：创建/停止命令、请求校验、幂等预占和会议仓库调用。
- `application/port/`：`MeetingRepository`、`IdempotencyStore`、`OutboxStore` 等替换
  边界；生产实现可接入 PostgreSQL 或其他持久化系统。
- `application/identity/referencedev/`：默认禁用的固定 Bearer Token 身份示例，
  只用于本地演示和契约测试，不是合作伙伴或 CZUR 生产账号实现。
- `application/persistence/`：线程安全的内存适配器，仅用于参考和测试，不是生产事实源。
- `application/persistence/jdbc/`：基于标准 JDBC `DataSource` 的 PostgreSQL 适配器，
  覆盖会议快照、幂等记录、Outbox、跨端口工作单元，以及 P2-T11 的配置、连接池、
  readiness/liveness 和关闭生命周期。运行时不自动建表，必须先执行并登记仓库
  唯一的 `deploy/migrations/V001__initial_schema.sql` 当前基线。
- `domain/capability/`：P3-T01 已验收的能力描述、产品 Profile、依赖/回退规则和不可变能力计划。
- `application/port/model/`：P3-T02 已验收的 Model Driver SPI、规范化会话/音频/事件对象、无网络 `MockModelDriver`，以及 P3-T04 的 `AlternativeTextModelDriver`。
- 对应 `src/test/java/`：能力计划、Model Driver 生命周期、幂等、顺序、取消、超时及资源边界测试；`ModelDriverContractTestKit` 为任意 Driver 复用的测试作用域契约工具包。

能力计划把 Provider 能力事实、产品档位、本场授权和部署限制固化为带
`planRevision` 的不可变结果。Model Driver 只接收应用层已经固化的
`RealtimeSessionSpec`，按公共序号处理音频并输出稳定的
`segmentId`/`revision`/`final` 事件；Mock 和 `AlternativeTextModelDriver` 均不访问网络，
固定文本仅用于契约测试和示例；上述服务端 Driver 不承担 P5 的 Android 直连模型运行。

## P5-1 听悟临时会话

显式启用 `AI_MEETING_TINGWU_ENABLED=true` 后，已认证的
`POST /v1/meetings/{meetingId}:modelSession` 为归属当前主体、处于 CREATED 或
RUNNING 的会议调用听悟实时 `CreateTask`。响应只下发 `meetingId`、
`adapterId=tingwu-realtime`、包含临时凭据的 `endpoint`、`expiresAt` 和
`initParams.taskId`；听悟没有单独的 `token` 字段。RAM AccessKey 留在服务端，
App 按公开 Adapter 接口直连听悟，后端继续只保存标准事件。此接口不改写
Create v1 的 `realtime` 响应或旧 WSS 路径。部署变量及启用窗口见
`deploy/README.md`。

单语字幕与已获准的中英双语任务均显式设置
`Parameters.Transcription.RealtimeDiarizationEnabled=true`、
`DiarizationEnabled=true` 和 `Diarization.SpeakerCount=0`，按会议未知人数请求
说话人分离。此参数修正不增加语种或私有会议账本；当前语言仍限 `zh-CN/en-US`，
连接材料默认五分钟期限保持。请求形状的定向测试不代替真实说话人准确率验收。

## 对象存储传输扩展

`S3ArtifactStore` 还提供公开的 `ObjectsApi` 和四参数构造器：
`S3ArtifactStore(objects, objectPrefix, namespace, maxArtifactBytes)`。
部署方可实现原生 OSS 等对象传输的 `get`／`putAbsent`／`delete`／`close`，
复用唯一的产物写入、发布和读取逻辑，无需复制 Store 状态机或使用 S3 网络协议。
`putAbsent` 使用实际存储的条件写入，不覆盖已存对象；其网络兼容性须在真实端点验收。
`namespace` 明确标识部署、端点和 Bucket，相同物理存储与前缀使用相同值。
Store 关闭时关闭传输客户端，原 S3 配置入口及公开 Artifact 协议保持。
该实现全量读取小产物，配置大小上限；长会音频使用单独的流式上传能力。
本扩展不自动承担部署方已有的存储计费、配额或旧资源登记。

## P5-2 模型能力与第二格式样例

后端现有完整 Create 会把 Product Profile、终端本场请求、权益授权、
`AI_MEETING_MODEL_CAPABILITIES` 声明的模型能力求交，并在
`capabilityDecisions` 返回每项能力的 `availability` 和 `reasonCode`。
未设置模型能力变量时仍沿用 `AI_MEETING_MOCK_CAPABILITIES`，保持 P4 默认
Mock 的原有行为。可选翻译、总结需先列入 Profile；ASR-only 模型只声明
`transcript.realtime`，这两项便返回 `UNAVAILABLE` 与
`PROVIDER_CAPABILITY_MISSING`。未在本场请求的可选能力返回
`NOT_REQUESTED`，App 也应关闭入口。

最小配置示例（其余必填项沿用 `.env.example`）：

```dotenv
AI_MEETING_PROFILE_OPTIONAL_CAPABILITIES=translation.realtime,summary.structured
AI_MEETING_ENTITLEMENT_SUPPORTED_CAPABILITIES=transcript.realtime,translation.realtime,summary.structured
AI_MEETING_MODEL_CAPABILITIES=transcript.realtime
```

本场 Create 的 `requestedCapabilities` 显式传入上述三项时，后两项返回
`UNAVAILABLE / PROVIDER_CAPABILITY_MISSING`；参考 App 默认只请求实时字幕，
因此默认请求里的后两项是 `NOT_REQUESTED`。P5-5 已建立可选翻译和总结的
产物执行映射；是否启用仍由本场请求、权益、模型能力和部署许可共同决定。

设置 `AI_MEETING_MODEL_ADAPTER=sample-json-ws` 和
`AI_MEETING_SAMPLE_JSON_WS_ENDPOINT=ws://...` 后，原 `:modelSession` 接口
签发 `adapterId=sample-json-ws`、该 WebSocket 地址、五分钟使用窗口和空的
`initParams`。这个接口只提供模型连接材料；部署方须另行提供遵循样例 JSON
格式的 WebSocket 模型服务。参考 App 按 `adapterId` 选择已注册 Adapter，
Create v1 的旧 `realtime` 字段与 P2 音频链路不变。

## P5-5 百炼文本总结临时材料

显式配置 `AI_MEETING_BAILIAN_SUMMARY_ENABLED=true`、部署方的
`DASHSCOPE_API_KEY` 与 `AI_MEETING_BAILIAN_APP_ID` 后，认证 App 在本场
已 `startClient`、状态为 STARTING/RUNNING 且首次 Create 已获准
`summary.structured` 时可调用
`POST /v1/meetings/{meetingId}:summarySession`。后端向百炼
`POST /api/v1/tokens?expire_in_seconds=...` 换取短期 Key，只将短期 Bearer、
应用 completion 地址与过期时间交给 App；永久 Key 不进入响应、日志或结果。
TTL 默认 300 秒，允许 1–1800 秒；地区地址可由 `.env.example` 中的变量配置。
Android SDK 调用应用 completion，App 归一化后在 Stop 前提交标准纪要。
未配置时旧会和 ASR-only 的 Create 行为不变；新版 App 的会前纪要入口会关闭。

现有匿名 `GET /v1/capabilities` 返回当前部署的静态能力，取 Profile、权益、
模型声明及部署开关的交集；`summary.structured` 还须已配置百炼签发。
此接口不签发凭据，也不代表某位用户或某一场会议已获准能力；Create 的
`capabilityDecisions` 仍是本场权威结果。

## P5-6 邮箱结果链接

部署者配置 `AI_MEETING_RESULT_EMAIL_ENABLED=true` 及 `.env.example` 中的 SMTP
地址、端口、账号、密码和发件地址后，`/v1/capabilities` 才报告
`resultEmailEnabled=true`。已登录 App 在 Stop 完成后调用
`POST /v1/meetings/{meetingId}:sendResultEmail`，后端核对本场归属、输入已接管、
结果 `genTime` 在统一有效期内，再向会前选定的 1～10 个邮箱发送同一结果页链接。
邮件没有 PDF 附件；未配置 SMTP 时接口保持关闭。

## P3-T05 会议应用切片

会议应用切片同步自 `czur-cloud` 固定提交
`23726454ebb893ae1402cff59b0f226b041daa04`。创建和停止命令通过端口访问会议事实源、
幂等记录和 Outbox；`deploymentScope + clientSessionId` 是创建唯一性范围，旧调用
默认使用 `default`。内存适配器只用于示例和测试；PostgreSQL 适配器提供持久化端口，
HTTP API、认证、计费、Worker 和完整运行时编排不在本切片内。

### PostgreSQL 适配器

`JdbcMeetingRepository`、`JdbcIdempotencyStore` 和 `JdbcOutboxStore` 只依赖
`javax.sql.DataSource`。参考运行时可以由应用启动 PostgreSQL JDBC 驱动（Maven
依赖使用 `runtime` 作用域），也可以由容器运行时提供驱动；核心领域和单元测试不
要求数据库可用。适配器不会打印响应正文、Outbox 载荷或连接凭据。

`MeetingCommandService` 保留旧的双端口构造器，供既有内存示例兼容使用；JDBC
运行时应使用 `MeetingUnitOfWork` 构造器。`JdbcMeetingUnitOfWork` 在一个
`Connection` 中提交会议快照、幂等记录和命令 Outbox，任何一步失败都会整体回滚。
事务内读取会议时使用 PostgreSQL 行锁，使多个后端实例并发 Stop 也只能由首次
状态转换写入一条 `MeetingStopRequested`；后到事务复用已提交的停止状态。
Outbox 的领取、发布和失败重试继续由提交后的 Worker 使用独立短事务执行，不能放入
会议命令事务，更不能在 Gateway 中拼接业务事务。数据库容器是否由 Compose 启动不
改变这个边界。

### PostgreSQL 运行时

`JdbcDatabaseRuntime` 是完整 PostgreSQL 会议的生产候选 JDBC 装配入口。它从
`DATABASE_URL`、`DATABASE_USER`、`DATABASE_PASSWORD` 以及连接池参数创建一个
共享 HikariCP `DataSource`，再向应用暴露 `JdbcMeetingUnitOfWork` 和提交后使用的
`JdbcOutboxStore`。配置非法、数据库不可用、V001 未登记，或已登记基线的
物理表不符合冻结契约时启动失败；不会自动执行迁移、修补半表，
也不会回退到 `InMemory*`。

`checkReadiness()` 检查连接、只读探针、V001 登记与物理 catalog 契约；
迁移登记齐全但物理形态漂移时返回脱敏的
`SCHEMA_INVALID`。`isLive()` 只表达运行时尚未关闭。因此数据库暂时中断时 readiness
会失败而 liveness 保持正常，连接池可在数据库恢复后重新建连。`close()` 可重复调用，
关闭后禁止继续取得业务组件。
Compose 数据库和外部 PostgreSQL 使用完全相同的配置入口，不根据 hostname 或
Profile 切换实现。

### P8 M1：MySQL 5.7 初始化与连接

公共连接层新增 Connector/J `8.3.0` 和 `JdbcDatabaseContext`，配置继续使用
`DATABASE_URL`、`DATABASE_USER`、`DATABASE_PASSWORD` 及原连接池参数。
`jdbc:mysql://<host>:3306/<database>` 明确选择 MySQL，原 PostgreSQL URL、
两个 `DatabaseRuntimeConfig.of` 重载及默认值保持。
`DATABASE_SOCKET_TIMEOUT_SECONDS` 仍以整数秒配置；池向 pgjdbc 传秒，向
Connector/J 传毫秒。MySQL 连接固定使用 UTF-8／`utf8mb4_bin`、UTC 时区及
`forceConnectionTimeZoneToSession=true`，URL 不得覆盖这些运行时参数。

先对选定库执行 [MySQL 当前 V001](../deploy/migrations/mysql/V001__initial_schema.sql)
及 [独立初始化入口](../deploy/migrate-mysql.sh)，再调用
`JdbcDatabaseContext.start(config)`。上下文拥有唯一 HikariCP 池；
`engine()` 和 `table(JdbcDatabaseTable)` 提供封闭固定映射：PostgreSQL 原表名
保留，MySQL 25 张当前基线表全部为 `ai_trans_*`，包含
`ai_trans_schema_migrations`。启动只读检查连接、`V001__initial_schema` 登记及
必要表存在／InnoDB，不读取旧业务表，不自动建表、登记或修补结构。
`checkBaselineReadiness()` 的 READY 只签连接与最小初始化基线，不签会议业务。
装配者统一关闭上下文，调用者关闭借出的连接；重复 `close()` 安全。

M1 尚未适配 MySQL 的会议 Store、Create／Stop／Worker 或 HTTP 装配。
`JdbcDatabaseRuntime.start/open` 对 MySQL 在创建池之前明确返回
`INVALID_CONFIGURATION`，因而旧 `ReferenceMeetingHttpAssembly` 不会误装配
PostgreSQL SQL。MySQL 正常会议链留 M2，CZUR 服务装配留 M3；原 PG catalog
契约门和完整运行时行为保留。每种引擎只交付一份当前 V001，不把两份基线连续执行。

本机定向真实连接测试使用 `REFERENCE_TEST_MYSQL_DATABASE_URL`、
`REFERENCE_TEST_MYSQL_DATABASE_USER`、`REFERENCE_TEST_MYSQL_DATABASE_PASSWORD`，
与原 PG 测试变量分开；仅接受 loopback 的 `ai_trans_m1_*` 隔离库。
`JdbcDatabaseContextMysqlIntegrationTest` 只读核对实际5.7 server、当前基线、
会话 UTC／utf8mb4、中文／emoji／canonical 原文及 UTC 微秒，未提供配置时明确跳过。
该测试不运行会议 Store，也不代表生产业务库、CZUR 真会或部署已验收。

### reference-dev 身份

`ReferenceDevIdentityProvider` 只有在 `REFERENCE_DEV_IDENTITY_ENABLED=true` 时
启用。启用后必须完整配置 `.env.example` 中的 Token、稳定主体、角色、权限、
audience、client 和有效期；缺失或非法配置会在装配阶段失败，不会静默匿名登录。
配置对象只保存固定 Token 的 SHA-256 摘要，诊断文本和日志不会输出 Token、摘要、
主体或完整请求。部署者可额外配置 `REFERENCE_DEV_LOGIN_USERNAME` 和
`REFERENCE_DEV_LOGIN_PASSWORD`，开放参考 App 使用的
`POST /v1/reference/session:login`：提交 `{"username":"...","password":"..."}`，
成功返回 `{"accessToken":"...","expiresAt":"..."}`，其中 Token 与上述固定
Bearer Token 相同。该演示入口只有一个预置账号，不提供自助注册、管理员后台、
Token 刷新或生产认证；合作伙伴应替换身份实现，不把它当作正式账号体系。

## 注入部署方身份与开会资格策略

P7-1c-1的装配开放保留上述默认参考链，并增加普通Java接口接入方式：

```java
var config = ReferenceMeetingDeploymentConfig.fromEnvironmentWithExternalIdentity(
        environment, "meeting-api", "partner-android");
// identityProvider验证部署方自己的凭据；entitlementPolicy读取自己的账号／会员规则。
// clock、artifactStore与这两个实现均由部署方集中装配，示例中不携带真实凭据。
try (var assembly = ReferenceMeetingHttpAssembly.start(
        config, clock, artifactStore, identityProvider, entitlementPolicy)) {
    var dispatcher = assembly.dispatcher();
    // 部署方HTTP入口将已约束的HttpRequest交给dispatcher.handle(request)，
    // 再写回HttpResponse；Gateway只做协议转发，不另启第二listener。
    // 既有收口Worker复用assembly.entitlementProvider()，不另建一套默认权益实现。
}
```

- `IdentityProvider`沿用已有接口，把部署方凭据转换为稳定主体与本次权限。
  Create、Stop、查询／删除、模型材料及续期共用同一个实例。外部配置入口不要求
  `REFERENCE_DEV_*`凭据或预置账号，且不开放演示登录；登录入口由部署方提供。
- 新`EntitlementPolicy`只提供`authorizeStart`与`prepareReservation`。前者读取
  当前开会资格，后者生成首次预占候选；两个方法不写会议记录、不扣费。现有JDBC
  Provider继续把预占和用量操作保存在同一会议PostgreSQL库，供Create工作单元
  绑定，不能仅用外部授权成功代替PG预占记录。
- 同一能力配置、会议协议与V001基线继续使用。原`fromEnvironment`、`start`和
  JDBC Provider的默认策略构造器保留；参考Process的Worker与Create复用同一个
  权益Provider。身份／策略需要支持并发调用，其外部服务资源由部署方关闭。
- Maven仍输出普通`backend-reference-0.1.0-SNAPSHOT.jar`，无需Spring或新的
  服务进程。消费方可在现有模块中装配公开端口；CZUR私有登录、会员和部署材料
  不进入本参考工程。

### 同一公共HTTP分发入口

`ReferenceMeetingHttpAssembly`只创建一份`MeetingHttpDispatcher`，通过
`dispatcher()`提供给部署方Servlet／Spring等HTTP transport；显式创建的
`newJdkHttpServer(address)`也使用该同一实例。原`runtime()`、`contextResolver()`
和全部JDK构造器继续保留。部署方不必重写`MeetingHttpAdapter`路由选择，也不应
另外启动一个JDK listener来转发本进程的业务请求。

Dispatcher仅提取原JDK业务分发顺序：可选`ResultEmailEndpoint`未装配仍返回501，
装配后使用既有客户端写入认证；读取／删除／客户端写入使用既有
`authenticationRoute`及可信上下文解析器；Create／Stop仍由自身应用服务认证。
原JDK内部健康路径、原始网络字节检查、有界执行器和关闭门留在JDK listener，
不进入公共业务dispatcher。其他transport负责自身的网络边界与健康入口；应先
停止请求接收，再关闭Assembly及其唯一PG池。此扩展不新增路由、鉴权规则、
会议状态机或部署事实，不修改V001、模型和权益行为。

### 装配部署方结算装饰器

已有外部用量拥有者的部署可增加第七个参数
`UnaryOperator<EntitlementProvider>`，薄装饰同一个持久Provider：

```java
// decoratorFactory接收唯一JDBC Provider，返回部署方自己的线程安全装饰器。
try (var assembly = ReferenceMeetingHttpAssembly.start(
        config, clock, artifactStore, identityProvider, entitlementPolicy,
        issuer, decoratorFactory)) {
    // Create、客户端Stop和Worker访问器共享工厂返回的同一个实例。
    var workerEntitlement = assembly.entitlementProvider();
}
```

工厂在PG readiness通过后仅调用一次；`issuer`仍可为`null`以保留默认模型选择。
旧`start`重载使用identity工厂，默认参考链和原外部身份／模型接线保持不变。
工厂及其返回值不可为`null`；工厂失败时启动失败并关闭已创建的PG池。

装饰器不取代PG预占绑定、版本检查或稳定操作重放：`authorizeStart`保持只读，
其余用量方法须委托输入Provider。需要外部结算时，`commitUsage`可在PG委托
成功后确认同场外部幂等操作；外部未确认须返回失败，原Stop保留待收口状态，
重复Stop使用同一冻结请求重试。PG成功或重放不表示外部已结算，不能因此跳过
未确认操作，也不能提前发布READY。装饰器的外部资源由部署方负责关闭。
本扩展只提供普通Java装配边界；参考测试使用替身确认时序，不代签真实账号扣费。

客户端事件会议已开放`POST /v1/meetings/{meetingId}/usage:submit`：停止采音后
提交`{"audioDurationMs":1000}`，首次返回201，同场同值重放返回200；不同值不能
覆盖。后端同时保存首次Reservation版本，原Stop读取冻结值，通过共享Provider
实际`commitUsage`后才生成READY。结果时长采用这份采音值，不把模型收口、总结
等待计入。16kHz、单声道、PCM16沿用采音字节数除以32的整数毫秒口径，实测0可
结算；旧没有提交用量的调用继续显示结果，不伪造零用量。Stop正文仍为空或`{}`。
此处交付后端协议与正常结算；SDK同流计数／提交、真实CZUR身份和会员适配仍在
P7-1c后续小提交完成，不能以参考测试或结果READY代签生产功能已迁移。

### Stop 前读取最终标准事件快照

部署方在模型尾事件及采音用量保存后，可调用普通 Java 只读入口：

```java
var snapshot = JdbcClientMeetingResultFinalizer.readFinalSnapshot(
        assembly.databaseRuntime().dataSource(), verifiedContext, meetingId);
if (snapshot instanceof JdbcClientMeetingResultFinalizer.Ready ready) {
    // 部署方用本场最终标准事件生成自己的资源格式；不从尚未生成的 Artifact 读取。
    var finalTranscripts = ready.transcripts();
    var finalTranslations = ready.translations();
    long capturedDurationMs = ready.frozenAudioDurationMs();
}
```

`verifiedContext`由部署方现有认证入口取得，并在上游检查所需权限；此方法在同一
只读`REPEATABLE_READ`连接内先复用`MeetingAccessGuard`核对会议原始归属，再读取
客户端启动标记、已保存的冻结采音值与首次Create。Create仍由原事实Store执行
规范正文、摘要和响应一致性校验；最终修订、partial过滤与排序直接复用原Stop
finalizer的同一个投影。返回的Protobuf消息和列表均不可变，保留全部标准字段。

`Ready`包含`transcripts`、`translations`、最终原文聚合`language`、实测
`frozenAudioDurationMs`，以及首次Create的`retainAudioRequested`、`sourceLanguage`
和可空`title`。真实无final返回空列表／`und`；实测0返回0；未冻结采音值返回
`Rejected(INVALID_STATE)`。其他拒绝沿用`ClientMeetingInputPort.Reason`的
`NOT_FOUND`、`ACCESS_DENIED`、`INPUT_SOURCE_CONFLICT`和`STORAGE_UNAVAILABLE`。

正常发布在`STARTING/RUNNING`读取，同场`STOPPING/READY`仍可只读核对。此处的
`Ready`是快照读取结果，不代表资源发布、用量结算或会议READY：方法只结束只读
事务，不调用Provider、不生成receipt、不写Artifact、Manifest或会议状态。
部署方直接复用Assembly的唯一DataSource，不为读取构造新的finalizer／Clock／池。
原公共HTTP协议、参考默认装配和Stop收口行为保持；真实对象上传和旧消费者接线
需由部署方另行实现并在对应环境验收。

### 注入部署方模型发行器

需要复用已有模型服务的部署，可在同一装配增加第六个参数：

```java
// issuer实现ModelSessionIssuer；自身远程服务和资源由部署方集中装配。
try (var assembly = ReferenceMeetingHttpAssembly.start(
        config, clock, artifactStore, identityProvider, entitlementPolicy, issuer)) {
    // 对接原运行时和身份解析器，不另开后端进程或模型会话管理器。
}
// 停止外层HTTP／WSS并关闭assembly后，部署方按资源所有权关闭issuer。
```

非null发行器优先于配置中的sample／听悟默认实现，无需再配置参考听悟凭据。
身份、PG会议事实、公开`:modelSession`响应及唯一V001基线保持不变；可信能力
配置仍参与首次Create交集，不能仅凭注入实现启用翻译或总结。

`ModelSessionIssuer.Request`新增`meetingId()`：原Lookup从已经验证归属的同一
持久快照传入真实会议ID、首次原语／目标语和冻结`translationEnabled`。部署方
发行器可直接将该ID传给已有模型服务，不从客户端正文另取或生成另一场ID。
旧三参数Request构造器保留，未知ID为null；默认听悟／sample发行器仍按原语言
与能力行为签发。原五参数Assembly重载保留，模型发行选择不变；新重载的issuer
为null时也使用原默认选择。发行器须支持并发调用，其资源不会被Assembly代关。
这里只开放普通Java扩展端口，不携带CZUR私有Feign接口、凭据或部署实现。

## P3-T04 Driver 契约测试

`ModelDriverContractTestKit` 位于测试作用域，统一覆盖 `describe`、配置校验、
能力子集、会话生命周期、音频重复/乱序、背压、超时/取消、幂等键、回调失败、
并发完成、shutdown，以及 close/abort 与 pending/final 回调窗口的线性化。新增 Driver 的测试只需
提供能力描述和工厂入口，即可继承同一组 JUnit 测试；若实现能提供确定性的
pending 创建暂停点，再覆盖 `createPendingDriver` 与 `supportsPendingDispatchHook()`
即可启用该竞态测试：

```java
final class MyDriverContractTest extends ModelDriverContractTestKit {
    protected CapabilityDescriptor descriptor() { return myDescriptor; }
    protected ModelDriver createDriver(CapabilityDescriptor d, ModelEventSink sink) {
        return new MyDriver(d, sink); // 仅实现公开 ModelDriver 端口
    }
}
```

仓库中的 `MockModelDriverContractTest` 与 `AlternativeModelDriverContractTest`
均通过该入口执行。后者使用独立 `providerId=alternative-fixed-text`、仅声明
`transcript.realtime`/`transcript.final` 能力，并以“每帧独立分段、完成时逐段
REPLACE”为不同固定语义；它拥有独立会话状态机，不依赖 Mock、HTTP、数据库、
厂商 SDK 或生产配置。TCK 是端口和生命周期门禁，不是模型质量评测，
也不替代真实会议的端到端测试。

## 明确边界

P3-T03 迁移上述已独立验收的纯 Java 公共切片；P3-T04 仅新增 Model Driver、
TCK 及其登记，不修改 `contracts/`、
`deploy/`、Gateway、API/Worker、Android、Web 或系统音频代码，也不携带真实模型、
厂商 SDK、私有 DTO、生产地址、凭据、用户数据或闭源二进制。目标仓既有会议领域
代码在 P3-T05 中补齐了应用命令、持久化端口和 `deploymentScope` 语义；领域和
端口仍保持纯 Java，PostgreSQL 驱动只位于 JDBC 运行时适配边界。

P2-T11 已验证连接池在 PostgreSQL 停止和重启后的恢复，但该切片仍不构成数据库
备份/恢复演练、Meeting API/Worker、Gateway
编排、真实 Provider Driver 或完整后端闭环验收；两个无网络固定文本实现只证明
公开端口可替换，不代表真实模型质量或生产接入。数据库仍应是未来运行时会议状态、
幂等记录和 Outbox 的事实源；内存和 Mock 仅用于单元测试与示例。

## 独立验证

在仓库根目录执行：

```bash
mvn -B -f backend-reference/pom.xml clean test
```

Maven 会从仓库根的 `contracts/realtime.proto` 用固定版本的 protoc 自动生成
`target/generated-sources/` 下的 Java 类；不使用开发机预装的 `protoc`，也不提交
生成文件。干净检出后可先运行 `mvn -B -f backend-reference/pom.xml test-compile`；
生成类只允许留在实时协议/传输边界，不进入会议领域或 Model Driver 端口。

该命令只构建本模块，不启动数据库、不调用外部服务，也不会触发父仓库的 Maven
reactor。Fixture、公开边界和依赖检查由仓库根目录的工具脚本执行。

真实 PostgreSQL 事务和运行时门禁需显式传入一次性测试库连接；集成用例在随机
schema 中自行装载 V001，不要指向已有环境。正式部署则先执行 `deploy/migrate.sh`：

```bash
REFERENCE_TEST_DATABASE_URL='jdbc:postgresql://127.0.0.1:5432/meeting' \
REFERENCE_TEST_DATABASE_USER='meeting' \
REFERENCE_TEST_DATABASE_PASSWORD='<从测试 Secret 注入>' \
mvn -B -f backend-reference/pom.xml clean test
```

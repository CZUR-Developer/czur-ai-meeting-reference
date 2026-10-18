# Backend core

Java 21 公共会议核心，输出普通 JAR：
`com.czur.ai.meeting:backend-core:0.1.0-SNAPSHOT`。
参考后端与产品宿主通过同一 JAR 复用领域、协议、能力计划、会议流程和持久化端口。
根 `pom.xml` 聚合 `backend-core` 与 `backend-reference`；核心 POM 自包含，
不依赖参考应用或其父 POM。

首次运行请先阅读[从零接入指南](../docs/partner-getting-started.md)；
模型与能力配置见[模型与能力指南](../docs/model-and-capability-guide.md)。

## 模块边界

| 目录或入口 | 职责 |
| --- | --- |
| `domain/` | 会议状态、归属、产品档位和能力决策 |
| `application/command/`、`application/port/` | 创建、停止、用量、结果及可替换端口 |
| `application/persistence/jdbc/` | JDBC 持久化；默认客户端会议使用 MySQL 5.7 |
| `application/model/` | 实时、翻译和总结的短期模型材料发行接口及示例实现 |
| `protocol/` | 标准结果和实时消息编解码；唯一 Protobuf 源为根 `contracts/realtime.proto` |
| `MeetingDeploymentConfig`、`MeetingHttpAssembly` | 冻结部署配置并装配同一会议核心 |

参考进程、演示账号登录、文件测试存储、固定文本 Model Driver 和默认实现选择留在
`backend-reference`。核心通过接口接收宿主提供的身份、权益、模型和存储实现。
Android SDK 领取短期材料后直连模型，实时音频不由会议后端中转；选择保留的会后录音
仍可上传后端并纳入结果。

### 语言列表与能力协商

部署方用 `AI_MEETING_MODEL_LANGUAGES` 声明有序的标准语言码列表。
`GET /v1/capabilities` 的 `languages` 直接返回 `modelLanguages()`；
未配置该变量时使用 `zh-CN,en-US`。当前实现不向模型查询语种，也不对 Provider、
Product Profile（产品档位）和部署配置执行语言列表求交。

部署方须自行确认所声明语言已由实际模型与 Adapter 支持；更换模型时同步更新声明。
App 按该列表生成语言选项，创建会议的语言校验也使用该列表。
供应商短码转换归对应 Adapter，框架不保证所有模型支持听悟示例中的语言。

语言列表和功能能力是两项独立配置。功能能力由 Profile、权益、模型能力、
已装配的可选 Provider 和部署限制共同决定，再由首次 Create 冻结本场结果。
完整说明见[模型与能力指南](../docs/model-and-capability-guide.md)。

## 能力缺失时的结果边界

参考最小配置需要 `transcript.realtime`。翻译、结构化总结、本场分人、
跨会议身份、录音和演示产物按实际配置与本场选择开放。
框架不因某个模型缺失就自动使用 CZUR 模型或凭据。

某项可选能力未开放时，App 隐藏相应入口；已选择功能还须通过本场 Create 授权。
已选择但未获准的翻译或总结会提示并阻止该场启动，不静默改为另一种业务。
正常停止后只发布实际生成的 `ArtifactRef`，最终 `MeetingManifest` 是结果入口依据。
参考 Web 隐藏没有产物的区域，不制造空译文、空总结或虚假人物列表。
本场 `sessionSpeakerId` 与跨会议身份 `speaker.identity.cross_meeting` 分开处理。

## 构建与宿主接入

在仓库根执行：

```bash
mvn -B clean test
mvn -B -pl backend-core -am package -DskipTests
```

第一条顺序构建两个模块并执行测试；测试源码位于 `backend-reference/src/test/java/`，
通过普通核心依赖验证公共逻辑，不交付 test JAR。第二条仅生成核心 JAR。

宿主通过 `MeetingDeploymentConfig.fromEnvironmentWithExternalIdentity(...)` 冻结配置，
并调用 `MeetingHttpAssembly.startClientModel(...)` 注入身份、资格、产物存储、
实时模型材料发行器以及可选登录入口、总结发行器和结算装饰器。
装配只管理自身唯一的 JDBC 连接池，外部账号、模型与存储资源由注入者管理。
关闭时先停止外层 HTTP 请求接收，再关闭装配，最后释放宿主自己的资源。

```java
SummarySessionIssuer partnerSummary = new PartnerSummaryIssuer(partnerConfig);
try (var assembly = MeetingHttpAssembly.startClientModel(
        config, clock, artifactStore, identity, policy, modelIssuer,
        java.util.function.UnaryOperator.identity(), login, partnerSummary)) {
    // 外层 HTTP transport 使用 assembly.dispatcher()；也可显式创建 JDK HTTP server。
}
```

`SummarySessionIssuer` 只返回总结 Adapter 所需的短期材料。
传入 `null` 时按部署配置选择可选的百炼实现；配置也未提供发行器时，
发现接口不声明 `summary.structured`。长期模型 Key 只由后端配置持有，
不下发 Android，不写入结果或日志。替换供应商只改宿主发行器与 Android Adapter，
无需复制状态机、HTTP 路由或结果 Manifest。

生产宿主通过普通 Maven JAR 与 POM 固定消费同源核心，三方依赖正常传递。
不要复制会议源码、依赖整个参考应用或使用 `systemPath`。

## 可选会后人物身份

`application/port/speaker/SpeakerIdentityProvider` 将跨会议人物识别／注册与实时 ASR
分开。宿主在 `startClientModel(..., login, summaryIssuer, speakerFactory)` 中显式
注入工厂，复用装配提供的唯一 `JdbcDatabaseContext` 和 `ArtifactStore`。
未提供工厂时不声明跨会议能力，也不访问可选人物表。

会后识别使用本场冻结能力、最终原文时间窗与同场 `audio.original`；
录音读取、FFmpeg 切片和供应商访问在会议连接释放后执行。
核心通过同一 Finalizer 和 READY metadata 发布标准
[`speaker.identity`](../contracts/speaker-identity.schema.json)。
没有匹配时 `personId/displayName` 为空，识别失败不阻断其余产物 READY。

命名注册使用 `POST /v1/meetings/{meetingId}/speakers/{sessionSpeakerId}:enroll`，
需要同场归属用户的 Bearer Token，正文仅 `displayName`；匿名结果链接只读。
注册后更新身份产物与原客户端 Manifest revision，原录音、原文、时间和链接保留。
参考讯飞实现、所需配置和依赖见
[后端参考说明](../backend-reference/README.md#可选跨会议声纹参考插件)。

## 最终标准快照

`JdbcClientMeetingResultFinalizer.readFinalSnapshot(assembly.databaseContext(),
verifiedContext, meetingId)` 可在模型尾事件和采音用量保存后读取同场最终事实。
使用同一 `JdbcDatabaseContext`，避免把默认 PostgreSQL 的 DataSource 重载用于 MySQL。

`Ready` 包含最终原文／译文、已提交总结、实测采音时长、首次创建参数，以及可选录音
和演示截图的描述符。没有总结时 `summary()` 为 `null`；描述符不载入长音频或 JPEG。
快照读取只核对归属和持久事实，不调用模型、不结算、不写产物、Manifest 或会议 READY。

## 数据库与非默认运行入口

默认参考客户端使用 MySQL 5.7，先执行唯一
[`V001__initial_schema.sql`](../deploy/migrations/V001__initial_schema.sql)。
启动只检查已初始化的基线，不自动建表；不能把 readiness 当作业务全流程验收。

源码保留 PostgreSQL 适配及服务端 WSS／Mock 音频接入组件，供显式装配的兼容宿主使用。
`startClientModel(...)` 不装配这套音频 Worker、WSS、删除或实时 Token 续签组件；
相关有效 HTTP DELETE／renew 路由返回 501。
`JdbcDatabaseRuntime` 完整运行入口仍只接受 PostgreSQL，相关音频／Worker SQL
未适配 MySQL。公开交付的 MySQL V001 只对应默认客户端会议链，不能据此将全部
PostgreSQL 组件视作 MySQL 可用，也不能把该 SQL 用于初始化 PostgreSQL。
新伙伴从默认客户端入口开始即可，无需装配这些非默认组件。

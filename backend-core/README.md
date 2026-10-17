# Backend core

Java 21 的公共会议核心，输出 `com.czur.ai.meeting:backend-core:0.1.0-SNAPSHOT`
普通 JAR。参考后端和生产宿主共用一份领域、协议、能力计划、会议流程、持久化及端口。
根 `pom.xml` 只聚合两个 Module；本 POM 自包含，无父 POM 或参考应用依赖。

## 模块边界

`JdbcClientMeetingResultFinalizer.readFinalSnapshot(...)` 同时返回已提交的标准结构化纪要，
未提交时 `Ready.summary()` 为 `null`。纪要来自原有唯一投影和客户端提交事实，
部署方可转换为旧结果格式；快照读取不调用模型、不写产物／READY、不新增表结构。
JSON 树在进入和离开快照时复制，原仅字幕构造器保持兼容。

- `domain/`：会议状态、归属及能力决策。
- `application/command/`、`application/port/`：Create、Stop、用量、结果及可替换端口。
- `application/persistence/jdbc/`：共享 PostgreSQL 兼容适配和 MySQL 5.7 正常客户端会议实现。
  本参考方案的局域网默认链路选择 MySQL 5.7；PostgreSQL 只由旧兼容宿主或显式测试夹具
  选择，不能据此把两种数据库都当作本期部署默认。
- `application/model/`：模型材料发行端口和可替换的总结材料发行端口；模型音频由 Android SDK 直连。`SummarySessionIssuer` 只描述“为本场总结 Adapter 签发短期材料”的中性边界，百炼是参考宿主的默认实现，公共核心不绑定供应商。
- `protocol/`：统一结果和实时消息编解码；唯一 Protobuf 源为根 `contracts/realtime.proto`。
- `application/bootstrap/MeetingDeploymentConfig`、`MeetingHttpAssembly`：中性配置和装配。

部署必须用 `AI_MEETING_MODEL_LANGUAGES`（或宿主等价配置）显式声明有序的标准语言码列表，公开发现接口再返回 `modelLanguages()`；P10 会移除公共核心缺省的中英回退。最终可用语言取部署声明、Provider 能力描述和 Product Profile 的交集，双语使用交集内由业务选择的两种语言。供应商字段和语种短码只由对应 Adapter 映射；公共核心不硬编码 CZUR 的 UI 选项，也不把七语或任何听悟能力写成通用保证。

参考宿主可以装配 `tingwu-realtime` 作为示例 Provider，但公开源码、模板、日志和构建产物不包含真实听悟 Key。合作伙伴更换 ASR 模型时，实现并注册自己的 Adapter，核心只消费标准会话材料和标准实时事件。

参考 Main／Process、演示账号与登录、真实 Mock Driver／收口、文件测试存储及默认
实现选择留在 `backend-reference`。核心不构造这些实现，也不选择宿主日志后端。
旧参考 WSS／Mock 链的组件由宿主显式注入 `LegacyAudioFactory`，不反向依赖参考模块。

## 能力缺失时的结果边界

每场会议的能力由 Provider、Product Profile、本场授权和部署限制计算并冻结；核心只
输出稳定的能力决策和最终 `MeetingManifest`，不因为某个 Provider 缺失就回退到
CZUR 模型。`transcript.realtime`／`transcript.final` 是参考产品档位的必选能力；
翻译、结构化总结、本场发言人、跨会议身份、录音和演示产物均可缺失。

缺失可选能力时，服务仍可完成剩余能力的 Stop 与 READY；相应的材料、事件和
`ArtifactRef` 不产生。消费端必须按最终决策和 Manifest 隐藏对应入口／结果区，不能
展示空译文、空总结、虚假的“未知发言人”列表，也不能把本场 `sessionSpeakerId`
当成 `speaker.identity.cross_meeting`。参考 Web 的默认行为是只读取 READY 产物；
伙伴动作和渲染器沿用同一规则。能力矩阵与 P10 验收证据见仓库外的
`docs/ai-meeting-decoupling/67-p10-reference-completion-plan.md`。

## 构建与接入

### 可选会后人物身份

`application/port/speaker/SpeakerIdentityProvider` 将跨会议人物识别／注册与实时 ASR
分开。宿主在新增 `startClientModel(..., login, summaryIssuer, speakerFactory)` 重载
显式传入 Factory，复用给定的唯一 `JdbcDatabaseContext` 和 `ArtifactStore`。原重载
继续关闭该扩展；未装配 Provider 时，发现与 Create 均不声明跨会议能力、不访问可选
人物表，原 `readFinalSnapshot`、字幕／翻译／总结／音频及 CZUR 既有接缝保持。

会后识别复用首次 Create 的能力、最终原文时间窗和同场 `audio.original`。Provider
读取录音、FFmpeg 切片、访问供应商均在会议连接释放后执行；核心再通过原 Finalizer
和 READY metadata 发布标准 [`speaker.identity`](../contracts/speaker-identity.schema.json)。
没有匹配时 `personId/displayName` 为空，不用本场 speaker 编号冒充人物；识别失败
不阻断其余产物 READY。本场分人编号／人物编号／供应商特征引用属于不同概念。

命名注册走 `POST /v1/meetings/{meetingId}/speakers/{sessionSpeakerId}:enroll`，需要
同场归属的用户 Bearer，正文仅 `displayName`；匿名 `genTime` 链接只读。注册先在
事务外调用 Provider，再短事务发布新身份产物并增加原客户端 Manifest revision，
不另写旧 Worker 的 `meeting_manifest_revision`。原录音、原文、起止时间和原链接不变。

未来 CZUR 应实现该中性 Provider 并包装原 Speech 的识别／注册及已有人员库，不重复
建立供应商特征。本期遵循用户“参考先完成，Cloud 后统一适配”的决定，未修改 Cloud
或其固定 JAR；正式统一消费时须从唯一参考源码生成同源制品并验证 CZUR 发布插件。

### 常规构建

在仓库根执行：

```bash
mvn -B clean test
mvn -B -pl backend-core -am package -DskipTests
```

第一条构建两模块并执行现有测试；测试源码暂集中于 `backend-reference/src/test/java/`，
通过普通核心依赖测试共同逻辑，不交付 test JAR。第二条仅生成核心 JAR。

宿主使用 `MeetingDeploymentConfig.fromEnvironmentWithExternalIdentity(...)` 冻结配置，
注入身份、资格、产物 Store、模型发行器及可选结算装饰器，再调用
`MeetingHttpAssembly.startClientModel(...)`。宿主负责先关闭 HTTP listener 再关闭装配；
共享装配仅管理自己的会议连接池，外部账号／模型／存储资源归注入者所有。

需要自定义总结模型时，宿主额外注入 `SummarySessionIssuer`；它只返回
`adapterId`、短期 `endpoint`、`expiresAt` 和 `token`，不会把长期模型 Key 交给核心或
Android。传入 `null` 时，核心才会按部署配置使用参考默认实现（当前为可选的百炼）；
没有配置发行器时，能力发现不会声明 `summary.structured`。因此替换总结模型只改
宿主装配和对应 Adapter，不复制会议状态机、HTTP 路由或结果 Manifest。

```java
SummarySessionIssuer partnerSummary = new PartnerSummaryIssuer(partnerConfig);
try (var assembly = MeetingHttpAssembly.startClientModel(
        config, clock, artifactStore, identity, policy, modelIssuer,
        java.util.function.UnaryOperator.identity(), login, partnerSummary)) {
    // HTTP transport 使用 assembly.newJdkHttpServer(...) 或 dispatcher()。
}
```

通过 Maven 消费普通核心 JAR 和本 POM，三方依赖会正常传递。生产宿主可固定版本随仓
交付；不要复制会议源码、依赖整个参考应用或使用 `systemPath`。
每种数据库仍使用根 `deploy/migrations/` 的唯一 V001；模块拆分不改 SQL 或协议。

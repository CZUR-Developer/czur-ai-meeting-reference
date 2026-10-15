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
- `application/persistence/jdbc/`：共享 PostgreSQL／MySQL 5.7 正常客户端会议实现。
- `application/model/`：听悟等公共临时材料签发工具，模型音频由 Android SDK 直连。
- `protocol/`：统一结果和实时消息编解码；唯一 Protobuf 源为根 `contracts/realtime.proto`。
- `application/bootstrap/MeetingDeploymentConfig`、`MeetingHttpAssembly`：中性配置和装配。

部署可用 `AI_MEETING_MODEL_LANGUAGES` 声明有序的标准语言码列表，公开发现接口直接返回
`modelLanguages()`；省略时保持既有中英默认。部署宿主负责声明实际模型可用范围，
供应商字段和语种短码仍由对应模型实现映射；公共核心不硬编码 CZUR 的 UI 选项。
默认听悟签发器已覆盖中文、英文、日文、韩文、德文、法文、俄文，双语继续使用所选两语
作为 `LanguageHints` 和双向翻译目标，沿用既有产品规则。

参考 Main／Process、演示账号与登录、真实 Mock Driver／收口、文件测试存储及默认
实现选择留在 `backend-reference`。核心不构造这些实现，也不选择宿主日志后端。
旧参考 WSS／Mock 链的组件由宿主显式注入 `LegacyAudioFactory`，不反向依赖参考模块。

## 构建与接入

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

通过 Maven 消费普通核心 JAR 和本 POM，三方依赖会正常传递。生产宿主可固定版本随仓
交付；不要复制会议源码、依赖整个参考应用或使用 `systemPath`。
每种数据库仍使用根 `deploy/migrations/` 的唯一 V001；模块拆分不改 SQL 或协议。

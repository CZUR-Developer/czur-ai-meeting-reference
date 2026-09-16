# P3-T03 公开切片来源与审计记录

本文件记录 P3-T03 选择性迁移的固定来源、路径映射和逐文件校验结果。公开仓只
保留已独立验收的纯 Java 能力计划与 Model Driver 端口/Mock；它不是完整后端，
也不包含数据库、HTTP API、Worker、Gateway 编排或真实 Provider Driver。

## 固定来源与目标基线

- 来源仓库：`/mnt/DataDisk/Data/Code/czur-cloud`，来源目录：
  `reference-backend/`。
- P3-T01 最终验收提交：`b1fb855d`（完整提交
  `b1fb855dc623db01b98f3e99cdb81cf14ca7b8e1`），内容为能力描述、产品
  Profile、部署限制、能力计划及其测试。
- P3-T02 最终验收提交：`23726454`（完整提交
  `23726454ebb893ae1402cff59b0f226b041daa04`）。其 Model Driver 迁移链为
  `6c8dbc25`、`daedbd8e`、`e2389624`、`3c7de044`、`23726454`；公开切片
  使用最终提交内容，不从来源工作区复制。
- 目标仓库：`/home/cfy/Data/Code/czur-ai-meeting-reference`，目标目录：
  `backend-reference/`。开工基线为分支 `master`、HEAD
  `d3781729b3bb56d1d3489623ad24b18042c555df`；配置的 `origin/master` 远端跟踪
  引用缺失（Git 显示 `[丢失]`），因此 ahead/behind 无法计算，本任务不 push。

## 目录映射与公开白名单

只迁移下列四个来源目录到对应公开目录；路径仅做仓库目录名前缀替换，文件内容
保持逐字节一致：

| 来源目录（`czur-cloud/reference-backend`） | 公开目录（`czur-ai-meeting-reference/backend-reference`） | 来源验收 |
| --- | --- | --- |
| `src/main/java/com/czur/reference/domain/capability/` | `src/main/java/com/czur/reference/domain/capability/` | P3-T01 `b1fb855d`（在 P3-T02 `23726454` 未改变） |
| `src/test/java/com/czur/reference/domain/capability/` | `src/test/java/com/czur/reference/domain/capability/` | P3-T01 `b1fb855d`（在 P3-T02 `23726454` 未改变） |
| `src/main/java/com/czur/reference/application/port/model/` | `src/main/java/com/czur/reference/application/port/model/` | P3-T02 `23726454` |
| `src/test/java/com/czur/reference/application/port/model/` | `src/test/java/com/czur/reference/application/port/model/` | P3-T02 `23726454` |

P3-T03 白名单仅为上述四个 Java 源/测试目录，以及
`backend-reference/README.md`、`backend-reference/SOURCE.md`。未迁移
`reference-backend` 的 command、persistence、其他 port、部署、协议或服务实现；
目标仓既有 `domain/meeting` 文件不属于本次迁移。许可证审查确认迁移文件无第三方
版权头或闭源代码，适用目标仓根目录 Apache License 2.0（`LICENSE`）及
`NOTICE`；Maven 依赖仅为测试用 JUnit，依赖许可证由构建元数据追踪。

## 逐文件 SHA-256 一致性

以下为目标工作区文件与固定来源提交文件的 SHA-256（来源路径省略共同前缀，
目标路径为公开仓相对路径）。能力目录按 P3-T01 `b1fb855d` 校验，Model Driver
目录按 P3-T02 `23726454` 校验；两组结果均为逐字节相等。

### 能力计划（P3-T01 `b1fb855d`）

| 来源路径 | 公开路径 | SHA-256 |
| --- | --- | --- |
| `reference-backend/src/main/java/com/czur/reference/domain/capability/CapabilityDecision.java` | `backend-reference/src/main/java/com/czur/reference/domain/capability/CapabilityDecision.java` | `2a4c95cbae129531547d2d04176fdc20de3ac27827f618e532b33534bb68767f` |
| `reference-backend/src/main/java/com/czur/reference/domain/capability/CapabilityDescriptor.java` | `backend-reference/src/main/java/com/czur/reference/domain/capability/CapabilityDescriptor.java` | `7f005cce3a3e4883ce8a42bb802a7ba6c8a245a840eb21d589fdf93b21357818` |
| `reference-backend/src/main/java/com/czur/reference/domain/capability/CapabilityIds.java` | `backend-reference/src/main/java/com/czur/reference/domain/capability/CapabilityIds.java` | `edb9c9c8fcb68730f80f1d78de7f5f12c56451cca444b5bc3512034fc84c827f` |
| `reference-backend/src/main/java/com/czur/reference/domain/capability/CapabilityPlan.java` | `backend-reference/src/main/java/com/czur/reference/domain/capability/CapabilityPlan.java` | `228fafd5370524c202b5cd3aa7dd1c5bdf251e9ce94af21b6e9a9c57c3f01f36` |
| `reference-backend/src/main/java/com/czur/reference/domain/capability/CapabilityPlanner.java` | `backend-reference/src/main/java/com/czur/reference/domain/capability/CapabilityPlanner.java` | `667b78f4d2b3228b5847c092e5105b70e730d7414a5bb8c75203d6dfcb6ef5b6` |
| `reference-backend/src/main/java/com/czur/reference/domain/capability/CapabilityPlanningException.java` | `backend-reference/src/main/java/com/czur/reference/domain/capability/CapabilityPlanningException.java` | `25dffd3024c88de07372555ce2129263d6debdcd31a1b8fb4a231afa02311933` |
| `reference-backend/src/main/java/com/czur/reference/domain/capability/DeploymentLimits.java` | `backend-reference/src/main/java/com/czur/reference/domain/capability/DeploymentLimits.java` | `3071d2b8a7be4e70b99c7bf1d214f7f902bae8c43750f9c95f472682872f754a` |
| `reference-backend/src/main/java/com/czur/reference/domain/capability/ProductProfile.java` | `backend-reference/src/main/java/com/czur/reference/domain/capability/ProductProfile.java` | `90b2766f38a4fcb8c029546d5374f68559958c2e25e5528e797187ff82f82edb` |
| `reference-backend/src/test/java/com/czur/reference/domain/capability/CapabilityPlannerTest.java` | `backend-reference/src/test/java/com/czur/reference/domain/capability/CapabilityPlannerTest.java` | `44b34f5a2d3987cd254023f27218362b1cf124314330d734b2fd6a4e8723de0a` |

### Model Driver 端口与 Mock（P3-T02 `23726454`）

| 来源路径 | 公开路径 | SHA-256 |
| --- | --- | --- |
| `reference-backend/src/main/java/com/czur/reference/application/port/model/AudioAcceptance.java` | `backend-reference/src/main/java/com/czur/reference/application/port/model/AudioAcceptance.java` | `5a04a0cd50bf481bce060655c1686d417e894b8b02b10185e839226c55dea723` |
| `reference-backend/src/main/java/com/czur/reference/application/port/model/AudioDiscontinuity.java` | `backend-reference/src/main/java/com/czur/reference/application/port/model/AudioDiscontinuity.java` | `bc61ab43023cab039eed7a34d55e844bc68123be8df717934ffadae91bc60298` |
| `reference-backend/src/main/java/com/czur/reference/application/port/model/AudioProfile.java` | `backend-reference/src/main/java/com/czur/reference/application/port/model/AudioProfile.java` | `3ff241466ce6c6a14c3e22159a2acc18b2be55ea035e666eb56b4a9df0a9ee59` |
| `reference-backend/src/main/java/com/czur/reference/application/port/model/CanonicalTranscriptEvent.java` | `backend-reference/src/main/java/com/czur/reference/application/port/model/CanonicalTranscriptEvent.java` | `9b44afb3cdded783954b91cb95514b3fe4fd027ed2cefc8e0da9c08d50bb89c0` |
| `reference-backend/src/main/java/com/czur/reference/application/port/model/InputCompletion.java` | `backend-reference/src/main/java/com/czur/reference/application/port/model/InputCompletion.java` | `9ae5e94c803717b9b1de521dbf54485563081f677518f40ca0c5f8914d7aad30` |
| `reference-backend/src/main/java/com/czur/reference/application/port/model/MeetingAudioFrame.java` | `backend-reference/src/main/java/com/czur/reference/application/port/model/MeetingAudioFrame.java` | `a2a7a0f6f425bc5b61d526409ba57ef2d46481efe8acbe8452ded324f2126dbb` |
| `reference-backend/src/main/java/com/czur/reference/application/port/model/MockModelDriver.java` | `backend-reference/src/main/java/com/czur/reference/application/port/model/MockModelDriver.java` | `5793212d9cfc8d8aabd8de885b3755de7e67d7f650688776e69d195826b6e01e` |
| `reference-backend/src/main/java/com/czur/reference/application/port/model/ModelDriver.java` | `backend-reference/src/main/java/com/czur/reference/application/port/model/ModelDriver.java` | `50928404780163398c4fa99b9f696cc503d5e4b870c11a92e8b550583fd5f37a` |
| `reference-backend/src/main/java/com/czur/reference/application/port/model/ModelDriverError.java` | `backend-reference/src/main/java/com/czur/reference/application/port/model/ModelDriverError.java` | `7894ae241509db1a78f46d926a5c201e54ffef2ece8bc1542ee9e354d26f3380` |
| `reference-backend/src/main/java/com/czur/reference/application/port/model/ModelDriverResult.java` | `backend-reference/src/main/java/com/czur/reference/application/port/model/ModelDriverResult.java` | `c5b838d0741516eadb0a42c1f89d02b92dea768e42548b0fd8d158121c9adb99` |
| `reference-backend/src/main/java/com/czur/reference/application/port/model/ModelDriverSession.java` | `backend-reference/src/main/java/com/czur/reference/application/port/model/ModelDriverSession.java` | `e2b9712169039bb05285da7590f1a8efcd0b657149d2d7000c19cff458760cca` |
| `reference-backend/src/main/java/com/czur/reference/application/port/model/ModelDriverSessionState.java` | `backend-reference/src/main/java/com/czur/reference/application/port/model/ModelDriverSessionState.java` | `8be1ce40d34228a95f435b571bfe8ebfa4ba578ed449957379dc9ed384206219` |
| `reference-backend/src/main/java/com/czur/reference/application/port/model/ModelEventSink.java` | `backend-reference/src/main/java/com/czur/reference/application/port/model/ModelEventSink.java` | `ab882436266fda677f88aff470ecb4ba9dcd77f2e598bedf46adec03fa7bd14a` |
| `reference-backend/src/main/java/com/czur/reference/application/port/model/ModelOperationContext.java` | `backend-reference/src/main/java/com/czur/reference/application/port/model/ModelOperationContext.java` | `1b04dbebbe62f552e895e6903fa4a485492568f6f0f27a5d7ea7021f90d338f0` |
| `reference-backend/src/main/java/com/czur/reference/application/port/model/ModelTerminal.java` | `backend-reference/src/main/java/com/czur/reference/application/port/model/ModelTerminal.java` | `e9dde10e0f61188d19579c3effabeebb032f43e8366e24128cfcb836e96c74b9` |
| `reference-backend/src/main/java/com/czur/reference/application/port/model/RealtimeSessionSpec.java` | `backend-reference/src/main/java/com/czur/reference/application/port/model/RealtimeSessionSpec.java` | `5437a5de6efca5a312787b0e1429ed5884009b32ed72bfab58c52e1ea982e1b9` |
| `reference-backend/src/main/java/com/czur/reference/application/port/model/SessionSnapshot.java` | `backend-reference/src/main/java/com/czur/reference/application/port/model/SessionSnapshot.java` | `b63f06246817550a907fff475890a21a4cce4a6ad5b928e75ccef8347cd96cb4` |
| `reference-backend/src/main/java/com/czur/reference/application/port/model/SpeakerReference.java` | `backend-reference/src/main/java/com/czur/reference/application/port/model/SpeakerReference.java` | `269ada1443a8d13bd29e48fbbd0bb65c42cfc9e6cafc5b5e34d014c1706db0ce` |
| `reference-backend/src/test/java/com/czur/reference/application/port/model/MockModelDriverTest.java` | `backend-reference/src/test/java/com/czur/reference/application/port/model/MockModelDriverTest.java` | `285b1abb0948a2d128540fa9f0ff8337b6cda2d4f2f58d091937a386f002df01` |
| `reference-backend/src/test/java/com/czur/reference/application/port/model/ModelDriverSettingsValidationTest.java` | `backend-reference/src/test/java/com/czur/reference/application/port/model/ModelDriverSettingsValidationTest.java` | `dc4e1c6a1a8ef3df63b0f740499bd7caa28329e3c9a3530761dfccb3f5fc56d4` |

## 差异与审查结论

- 迁移的 29 个 Java 文件均与固定来源提交逐字节一致；无重排、换行、包名或
  行为改动，故无机械差异待说明。README 与本 SOURCE.md 是公开仓审计文档，
  不属于来源 Java 切片。
- 对 Java `import`、Maven POM 和日志字符串做审查：仅使用 JDK 21 与测试作用域
  JUnit；无厂商 SDK、Gateway、数据库框架/JDBC、生产 URL、Token、密码、账号、
  用户数据、原始音频或完整 Provider 响应。`MockModelDriver` 不访问网络。
- 公开边界检查还需结合 `tools/check-public-boundary.py`、Maven 测试、依赖树及
  Fixture 校验结果；这些命令的实际输出记录在本次 P3-T03 交付说明中。

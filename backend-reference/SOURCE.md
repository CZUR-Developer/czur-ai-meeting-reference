# P3-T03/P3-T04/P3-T05 公开切片来源与审计记录

本文件记录 P3-T03 选择性迁移的固定来源、路径映射和逐文件校验结果，以及
P3-T04 的测试工具/无网络第二 Driver、P3-T05 的会议应用切片和 P2-T05 的 JDBC
适配器增量。公开仓只保留可替换的参考实现；它不是完整后端，也不包含 HTTP API、
Worker、Gateway 编排或真实 Provider Driver。JDBC 适配器属于显式数据库运行时边界，
不改变领域和端口的 JDK-only 设计。

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
  `backend-reference/`。P3-T05 开工基线为分支 `master`、HEAD
  `a621a3f2e5d6272a32f5897c07b1b1abb1820f03`；配置的 `origin/master` 远端跟踪
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
`NOTICE`；Maven 依赖包括测试用 JUnit，以及 JDBC 适配器运行时使用的 PostgreSQL
驱动，依赖许可证由构建元数据追踪。

P3-T04 增量白名单仍只允许 Model Driver 的 main/test 目录及上述两个说明文件；
P3-T05 白名单增加会议领域的快照恢复、应用命令、应用端口、内存适配器及其测试，
P2-T05 进一步增加 JDBC 适配器及其显式 PostgreSQL 运行时依赖，具体新增文件在下文登记。
P3-T04/P3-T05 未修改 `contracts/`、`deploy/`、数据库、
Gateway、API/Worker、Android 或 Web。

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

## P3-T04 增量登记：TCK 与第二 Driver

P3-T04 基于公开仓 P3-T03 提交 `6893361` 实现，没有从 `czur-cloud` 复制新的
生产代码，也未修改既有会议领域、能力计划、协议、部署或 API 文件。新增文件如下：

| 公开路径 | 来源/边界 | 说明 |
| --- | --- | --- |
| `backend-reference/src/main/java/com/czur/reference/application/port/model/AlternativeTextModelDriver.java` | 本任务新写的 JDK-only 参考实现 | 独立 `providerId=alternative-fixed-text`、能力集合、会话状态机和固定文本分段策略，仅声明 `transcript.realtime` 与 `transcript.final`；不访问 HTTP、数据库、厂商 SDK、生产配置或真实模型 |
| `backend-reference/src/test/java/com/czur/reference/application/port/model/ModelDriverContractTestKit.java` | 本任务新写的 test-scope 契约工具 | 抽象 `descriptor()`/`createDriver(...)` 扩展入口，统一覆盖生命周期、能力、幂等、并发、错误和回调线性化；可选覆盖 `createPendingDriver(...)` 与 `supportsPendingDispatchHook()` 以启用确定性的 pending 竞态；不读取实现内部状态 |
| `backend-reference/src/test/java/com/czur/reference/application/port/model/MockModelDriverContractTest.java` | 本任务新增测试入口 | 使用 TCK 验证既有 Mock |
| `backend-reference/src/test/java/com/czur/reference/application/port/model/AlternativeModelDriverContractTest.java` | 本任务新增测试入口 | 使用同一 TCK 验证第二 Driver，并额外断言独立能力和固定文本语义 |

`ModelDriverSession` 是 Driver 返回的 identity handle。TCK 使用值相等但非原始对象
的伪造句柄验证 `INVALID_ARGUMENT`，不会以 record 值相等绕过身份边界。两个具体测试
类均继承同一个 `ModelDriverContractTestKit`，因此替换 Driver 只改变工厂和能力描述；
会议状态机、能力计划和公共协议没有第二套语义。

第二 Driver 的固定文本（`alternative partial N`、`alternative final alternative-segment-N`）
和无 speaker 能力只用于替换证明、契约测试与示例，不是模型质量评测或生产接入。

## 差异与审查结论

- P3-T03/P3-T04 迁移的 29 个 Java 文件均与固定来源提交逐字节一致；P3-T05
  复核的 22 个会议相关 Java 文件也逐字节一致。无重排、换行、包名或行为改动，
  故无机械差异待说明。README 与本 SOURCE.md 是公开仓审计文档，不属于来源
  Java 切片。
- 对 Java `import`、Maven POM 和日志字符串做审查：领域、应用端口和两个固定文本
  Driver 仅使用 JDK 21 与测试作用域 JUnit；P2-T05 JDBC 适配器只使用标准
  `java.sql`/`javax.sql`，PostgreSQL 驱动为运行时依赖；无厂商 SDK、Gateway、生产
  URL、Token、密码、账号、用户数据、原始音频或完整 Provider 响应。两个固定文本
  Driver 均不访问网络。
- 公开边界检查还需结合 `tools/check-public-boundary.py`、Maven 测试、依赖树及
  Fixture 校验结果；P3-T03、P3-T04 和 P3-T05/P2-T05 分别记录各自命令的实际输出。

## P3-T05 会议应用切片

P3-T05 从 `czur-cloud` 的固定提交
`23726454ebb893ae1402cff59b0f226b041daa04`（目录
`reference-backend/src/`）同步会议领域和应用层的纯 Java 参考代码。目标路径为
`backend-reference/src/`；同步内容与来源逐字节一致，P3-T05 本身不引入 HTTP、
数据库驱动、认证、计费、Worker、Gateway、Android、Web 或生产配置；后续 P2-T05
仅在新增的 JDBC 适配器边界引入运行时 PostgreSQL 驱动。

| 来源目录/文件 | 公开目录/文件 | SHA-256 |
| --- | --- | --- |
| `src/main/java/com/czur/reference/application/command/`（4 个文件） | `backend-reference/src/main/java/com/czur/reference/application/command/` | 见下方校验命令 |
| `src/main/java/com/czur/reference/application/error/`（3 个文件） | `backend-reference/src/main/java/com/czur/reference/application/error/` | 见下方校验命令 |
| `src/main/java/com/czur/reference/application/persistence/`（3 个文件） | `backend-reference/src/main/java/com/czur/reference/application/persistence/` | 见下方校验命令 |
| `src/main/java/com/czur/reference/application/port/{IdempotencyStore,MeetingRepository,OutboxStore}.java` | `backend-reference/src/main/java/com/czur/reference/application/port/` | 见下方校验命令 |
| `src/main/java/com/czur/reference/domain/meeting/`（5 个文件） | `backend-reference/src/main/java/com/czur/reference/domain/meeting/` | 见下方校验命令 |
| 对应 `src/test/java` 测试（4 个文件） | `backend-reference/src/test/java` | 见下方校验命令 |

逐文件一致性由以下命令复核（路径前缀替换后比较 SHA-256）：

```bash
python3 - <<'PY'
from pathlib import Path
import hashlib
src = Path('/mnt/DataDisk/Data/Code/czur-cloud/reference-backend/src')
dst = Path('/home/cfy/Data/Code/czur-ai-meeting-reference/backend-reference/src')
for path in sorted(src.rglob('*.java')):
    target = dst / path.relative_to(src)
    assert target.exists(), target
    assert hashlib.sha256(path.read_bytes()).digest() == hashlib.sha256(target.read_bytes()).digest(), path
print('P3_T05_SOURCE_SHA_OK')
PY
```

固定提交下本次审计覆盖的 22 个会议相关文件 SHA-256（其中未改变的文件也一并复核）：

```text
main/java/com/czur/reference/application/command/CreateMeetingCommand.java e3ae729f719712a65c9d9aaa001468ee2129c2af343ad73031422334bc6c4457
main/java/com/czur/reference/application/command/MeetingCommandResult.java 8101f6f820df08a4faa1e32ebb6e745154238ddbd07a026d13c2d8678c29f94f
main/java/com/czur/reference/application/command/MeetingCommandService.java 10530100b06fa55fb8a34a6db20a9dc1d8a42a141e205d97b051e3ab80963258
main/java/com/czur/reference/application/command/StopMeetingCommand.java 7a8876465fb6ee0966aaf7ee128149bc6929066c8c3d152b1fabb354b05c8bdb
main/java/com/czur/reference/application/error/IdempotencyConflictException.java 2b0aa3d4a9ed45bd3defa4cf81c7b434fe69153eb90f82a561c3043bd5d87ffd
main/java/com/czur/reference/application/error/MeetingAlreadyExistsException.java 4a2c2da40f495a3cc6d39de095328c33aca7d3a559f45c8f8e867bb672075109
main/java/com/czur/reference/application/error/MeetingNotFoundException.java c5fc58e9ea6acb2a992bd1c21a31f718acdf74d736821910d7466485093299ef
main/java/com/czur/reference/application/persistence/InMemoryIdempotencyStore.java 7b7f0906c0dc0df1e1abe7e24a176e0044b01bf4e57e74f398839941a95ef48f
main/java/com/czur/reference/application/persistence/InMemoryMeetingRepository.java aff5cb42e4b34f0880423670f34645f35c48e8b6d4f23a5a7130d316663c1cce
main/java/com/czur/reference/application/persistence/InMemoryOutboxStore.java cb6bc051ee061141b408f9d348d2a7c14da7da4d163f4e7eb78f050f6c76f883
main/java/com/czur/reference/application/port/IdempotencyStore.java e5ff47edb5b6dad61aede8383cce0b900e7c30197ad34d38911a963e996b81ef
main/java/com/czur/reference/application/port/MeetingRepository.java 1c960ef1cd1cc9866adcab28d8bceb16b3f67107df6eb9b5e5eaa854a3cc9867
main/java/com/czur/reference/application/port/OutboxStore.java 26062abd9258c4af4393296e2c4fb9fbba22a97a4af41af1da7879d7a3fda98d
main/java/com/czur/reference/domain/meeting/InvalidMeetingTransitionException.java 912b66f97ac22a648a56d1ba4635944f69eff38c2e4a20db4178f7be8c1972a0
main/java/com/czur/reference/domain/meeting/MeetingAggregate.java 6d7c6e6254b2717554ff0d51b9dc3a80d7855c783e5296173539f744a87d909c
main/java/com/czur/reference/domain/meeting/MeetingEvent.java 761f928abcdbfba962ce904ddaf2c78ba2bdaa790dac996a04d6c993c8a060a1
main/java/com/czur/reference/domain/meeting/MeetingState.java 614139b6416a3df64880280c486eaa1a0fb377ebd9423d00e879786bd709a680
main/java/com/czur/reference/domain/meeting/MeetingStateMachine.java 02a524fd2ccdabb5a60bee13ce01e843cae7a7609ffabbc69a73bbec4643fac4
test/java/com/czur/reference/application/MeetingCommandServiceTest.java a0dd8619d4f73208dff8374cdaac3bd8f0614f29f339c9ae1ec85eedb9b1cfc2
test/java/com/czur/reference/application/OutboxStoreTest.java 0ec7e2b220bc42645adaf4bab75875788484fb925d1837d160606e80e528f754
test/java/com/czur/reference/domain/meeting/MeetingAggregateTest.java bcef40ae78438773f465ef85fdb0d760f47cd196c8d96269f66aa6a8d2d58f72
test/java/com/czur/reference/domain/meeting/MeetingStateMachineTest.java ddcdbbae069ece17a86742d066bbd2fac14e6a378d6cde0c773bc9c5c4f99c81
```

本次切片提供 `deploymentScope + clientSessionId` 唯一性、旧 `default` 构造兼容、
快照恢复、幂等预占/释放以及 Outbox lease/fencing 语义；内存实现仅用于参考和测试。
JDBC 适配器已在 P2-T05 增加；P2-T10 又增加公开的 `MeetingUnitOfWork`、
`JdbcMeetingUnitOfWork` 和命令服务事务入口。生产连接池、迁移执行、备份恢复、
HTTP API 与 Worker 仍属于后续任务。

## P2-T05 JDBC 适配器增量

P2-T05 新增文件位于 `backend-reference/src/main/java/com/czur/reference/application/persistence/jdbc/`：

- `JdbcMeetingRepository`：按会议 ID 读写快照，并支持部署范围与客户端会话查询；
- `JdbcIdempotencyStore`：以 `(subject_scope, idempotency_key)` 唯一键完成原子预占、重放、冲突和释放；
- `JdbcOutboxStore`：以 V001/V002 表结构实现追加、`SKIP LOCKED` 领取、lease token/version 栅栏、发布确认、失败重试和死信；
- `IdempotencyResultJson`：JDK-only 的固定响应 JSONB 编解码，仅处理公开命令结果。

PostgreSQL 驱动在 `backend-reference/pom.xml` 使用 `runtime` 作用域；领域和端口编译
不依赖驱动。`JdbcPostgresIntegrationTest` 仅在显式设置
`REFERENCE_TEST_DATABASE_URL`、可选账号密码后运行，数据库须预先执行 V001/V002；
默认单测不连接外部数据库。该增量不实现 HTTP、Worker、连接池、迁移执行、备份恢复
或完整运行时，不应单独宣称完整生产闭环。

## P2-T10 Unit of Work 增量

P2-T10 在应用端口增加 `MeetingUnitOfWork`，并以 `JdbcMeetingUnitOfWork` 将
`MeetingRepository`、`IdempotencyStore` 和 `OutboxStore.append` 绑定到同一个 JDBC
连接。`MeetingCommandService` 的工作单元入口会原子提交创建/停止快照、幂等结果和
确定性命令事件；异常时统一回滚。旧双端口构造器继续存在，避免破坏已有内存示例。

事务内 Outbox 视图明确拒绝 `claim`、`markPublished` 和 `markFailed`；这些 Worker
操作在提交后通过原 `JdbcOutboxStore` 的独立短事务执行。真实 PostgreSQL 集成测试
覆盖成功提交、重复 Stop 不产生第二条收口事件、Outbox 冲突回滚，以及回调在写入
三类端口后抛错时整体回滚。事务内会议查询使用 `SELECT ... FOR UPDATE`，并由两个
独立命令服务和数据库连接验证并发 Stop 只生成一条停止事件。JDBC Outbox 的重放比较使用 PostgreSQL JSONB 相等语义，
避免数据库规范化空白或键顺序后把相同载荷误判为冲突。

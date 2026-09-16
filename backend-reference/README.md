# Backend reference implementation

这是公开仓中的纯 Java 参考切片，用于展示会议领域状态、能力计划和可替换的
Model Driver 端口。代码保持与 HTTP 框架、数据库、厂商 SDK 和生产服务解耦；
Provider 特有的响应转换只能位于仓库外的 Driver 实现中。

## 当前公开范围

- `domain/meeting/`：会议状态机、聚合、不变量和快照恢复；本次同步新增部署范围语义，
  并保留原有文件与测试。
- `application/command/`：创建/停止命令、请求校验、幂等预占和会议仓库调用。
- `application/port/`：`MeetingRepository`、`IdempotencyStore`、`OutboxStore` 等替换
  边界；生产实现可接入 PostgreSQL 或其他持久化系统。
- `application/persistence/`：线程安全的内存适配器，仅用于参考和测试，不是生产事实源。
- `domain/capability/`：P3-T01 已验收的能力描述、产品 Profile、依赖/回退规则和不可变能力计划。
- `application/port/model/`：P3-T02 已验收的 Model Driver SPI、规范化会话/音频/事件对象、无网络 `MockModelDriver`，以及 P3-T04 的 `AlternativeTextModelDriver`。
- 对应 `src/test/java/`：能力计划、Model Driver 生命周期、幂等、顺序、取消、超时及资源边界测试；`ModelDriverContractTestKit` 为任意 Driver 复用的测试作用域契约工具包。

能力计划把 Provider 能力事实、产品档位、本场授权和部署限制固化为带
`planRevision` 的不可变结果。Model Driver 只接收应用层已经固化的
`RealtimeSessionSpec`，按公共序号处理音频并输出稳定的
`segmentId`/`revision`/`final` 事件；Mock 和 `AlternativeTextModelDriver` 均不访问网络，
固定文本仅用于契约测试和示例，不代表任何真实模型已接入。

## P3-T05 会议应用切片

会议应用切片同步自 `czur-cloud` 固定提交
`23726454ebb893ae1402cff59b0f226b041daa04`。创建和停止命令通过端口访问会议事实源、
幂等记录和 Outbox；`deploymentScope + clientSessionId` 是创建唯一性范围，旧调用
默认使用 `default`。内存适配器只用于示例和测试；PostgreSQL、事务/Unit of Work、
HTTP API、认证、计费、Worker 和真实运行时编排不在本切片内。

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
代码在 P3-T05 中补齐了应用命令、持久化端口和 `deploymentScope` 语义；这些内容
仍保持纯 Java，且没有引入运行时外部依赖。

该切片不构成 JDBC/数据库恢复、事务或 Unit of Work、Meeting API/Worker、Gateway
编排、真实 Provider Driver 或完整后端闭环验收；两个无网络固定文本实现只证明
公开端口可替换，不代表真实模型质量或生产接入。数据库仍应是未来运行时会议状态、
幂等记录和 Outbox 的事实源；内存和 Mock 仅用于单元测试与示例。

## 独立验证

在仓库根目录执行：

```bash
mvn -B -f backend-reference/pom.xml clean test
```

该命令只构建本模块，不启动数据库、不调用外部服务，也不会触发父仓库的 Maven
reactor。Fixture、公开边界和依赖检查由仓库根目录的工具脚本执行。

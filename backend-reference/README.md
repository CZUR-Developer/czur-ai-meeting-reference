# Backend reference implementation

这是公开仓中的纯 Java 参考切片，用于展示会议领域状态、能力计划和可替换的
Model Driver 端口。代码保持与 HTTP 框架、数据库、厂商 SDK 和生产服务解耦；
Provider 特有的响应转换只能位于仓库外的 Driver 实现中。

## 当前公开范围

- `domain/meeting/`：目标仓已有的会议状态机和聚合代码，本次迁移保留原有文件与测试。
- `domain/capability/`：P3-T01 已验收的能力描述、产品 Profile、依赖/回退规则和不可变能力计划。
- `application/port/model/`：P3-T02 已验收的 Model Driver SPI、规范化会话/音频/事件对象，以及无网络 `MockModelDriver`。
- 对应 `src/test/java/`：能力计划和 Model Driver 生命周期、幂等、顺序、取消、超时及资源边界测试。

能力计划把 Provider 能力事实、产品档位、本场授权和部署限制固化为带
`planRevision` 的不可变结果。Model Driver 只接收应用层已经固化的
`RealtimeSessionSpec`，按公共序号处理音频并输出稳定的
`segmentId`/`revision`/`final` 事件；Mock 不访问网络，也不代表任何真实模型已接入。

## 明确边界

本次 P3-T03 只迁移上述已独立验收的纯 Java 公共切片，不修改 `contracts/`、
`deploy/`、Gateway、API/Worker、Android、Web 或系统音频代码，也不携带真实模型、
厂商 SDK、私有 DTO、生产地址、凭据、用户数据或闭源二进制。目标仓既有会议领域
代码保持不变。

该切片不构成 JDBC/数据库恢复、事务或 Unit of Work、Meeting API/Worker、Gateway
编排、真实 Provider Driver、两个模型替换证明或完整后端闭环验收。数据库仍应是
未来运行时会议状态、幂等记录和 Outbox 的事实源；内存和 Mock 仅用于单元测试与示例。

## 独立验证

在仓库根目录执行：

```bash
mvn -B -f backend-reference/pom.xml clean test
```

该命令只构建本模块，不启动数据库、不调用外部服务，也不会触发父仓库的 Maven
reactor。Fixture、公开边界和依赖检查由仓库根目录的工具脚本执行。

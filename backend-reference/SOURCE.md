# 后端源码归属与维护说明

本文件说明参考后端的源码组成、公共核心的维护边界以及再分发时应保留的信息。
接入与运行步骤见[后端参考说明](README.md)和
[从零接入指南](../docs/partner-getting-started.md)。

## 源码组成

公共会议领域、能力计划和 Model Driver 端口来自 CZUR 会议业务中抽取的通用实现；
其当前权威源码位于本仓 `backend-core`。参考进程、演示账号、默认插件与测试由
`backend-reference` 提供。公共 JAR 与参考后端均从本仓模块构建，接入方无需取得
CZUR 产品私有仓库、内部部署脚本或开发者本机文件。

| 当前仓内路径 | 内容与责任 |
| --- | --- |
| `backend-core/src/main/java/com/czur/reference/domain/` | 会议领域、能力与产品档位 |
| `backend-core/src/main/java/com/czur/reference/application/` | 公共应用服务、HTTP、模型材料发行和持久化端口／适配器 |
| `backend-core/src/main/java/com/czur/reference/protocol/` | 标准结果与实时协议编解码 |
| `backend-reference/src/main/java/` | 默认进程、参考登录、示例插件和装配 |
| `backend-reference/src/test/java/` | 公共核心与参考实现测试、Model Driver 契约工具包 |
| `contracts/` | Protobuf、JSON Schema 和公开协议说明 |
| `deploy/migrations/V001__initial_schema.sql` | 默认 MySQL 客户端会议链的唯一初始化基线 |

生成的协议 Java 类位于 `backend-core/target/generated-sources/`，由 Maven 使用
固定版本 protoc 生成，不提交生成产物。目录名和 Java 包名承担各自职责，
不要因包名含 `reference` 就复制公共类到产品宿主。

## 维护规则

- 领域、协议、会议状态、能力计划和结果收口仅维护一份，产品宿主通过普通核心 JAR 消费。
- 登录、资格／计费、模型、存储和结果交付通过公开接口装配；私有业务规则留在宿主插件。
- 修改公共核心时同步文档、协议 Fixture 和相应测试，并评估参考后端及产品宿主的兼容。
- `MockModelDriver`、固定文本 Driver 和样例协议仅用于演示与契约测试，不代表真实模型能力。
- 持久层只支持 MySQL 5.7，具体限制见
  [数据库范围](README.md#数据库范围)。
- 不向公开仓提交真实凭据、私有端点、签名材料、账号／设备数据或客户会议内容。

## 许可证与依赖

本仓提供的源码遵循根目录 [`LICENSE`](../LICENSE) 与 [`NOTICE`](../NOTICE)
中的声明。`NOTICE` 声明项目版权为 CZUR, Inc.；重新分发时保留适用的许可证与通知。
本文件不授予独立于这些文件之外的权利，也不对第三方平台账号或模型服务作授权承诺。

第三方库各自遵循其许可证，依赖版本与作用域以当前
[核心 POM](../backend-core/pom.xml)及[参考应用 POM](pom.xml)为准。
发布二进制前检查实际依赖元数据、SDK 配套交付说明与相应的再分发要求；
模型、对象存储等外部服务按部署方与供应商的协议使用。

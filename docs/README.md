# 文档导航

这个仓库有 30 多份 Markdown。绝大多数人只需要三条主线里的一条，其余是模块说明和工具
速查。**第一次来，从第一节第 0 篇开始读**，其余按需跳转。

## 一、第一次接入（按顺序读，一条线走完）

| # | 文档 | 解决的问题 | 约 |
| --- | --- | --- | --- |
| 0 | [接入前准备清单](preparation-checklist.md) | 要申请哪些账号、准备哪些基础设施和工具；没有听悟时怎么先跑通链路 | 10 分钟 |
| 1 | [从零接入指南](partner-getting-started.md) | 准备源码与 SDK、建库、起后端、起 HTTPS、装 APK、开第一场真实会议、Docker 交付 | 30–60 分钟 |
| 2 | [模型与能力配置指南](model-and-capability-guide.md) | 听悟／百炼／讯飞各做什么、要申请什么、能不能缺、缺了什么表现 | 20 分钟 |
| 3 | [本机局域网 HTTPS 联调](local-lan-https.md) | 自己签 CA、起 TLS 入口、让 Debug APK 信任它 | 15 分钟 |
| 4 | [支持范围与验收清单](validation-scope.md) | 哪些是实测覆盖、哪些必须你的环境自己验 | 10 分钟 |

## 二、部署与运维

- [参考后端部署](../deploy/README.md)：外部 MySQL、唯一 V001、Docker 应用与外部配置、静态 Web。
- [数据库基线](../deploy/migrations/README.md)：为什么只有一份 V001、初始化不建库。
- [数据库运维边界](../deploy/backup-and-restore.md)：备份／恢复属于部署方职责。
- [Web 结果页](../web-reference/README.md)：静态产物、`config.json`、Manifest 渲染。
- [MySQL 5.7 能力验证](../tools/mysql57-compatibility/README.md)：隔离探针，不是交付目标。

## 三、扩展开发

主线是 **[扩展开发手册](partner-integration-guide.md)**：登录保持、鉴权、模型 Adapter、
存储、权益、结果动作、SDK 工具的扩展边界。按模块细读时：

| 模块 | 文档 | 里面有什么 |
| --- | --- | --- |
| 后端核心 | [backend-core](../backend-core/README.md) | 共享 JAR 的端口、装配与消费方式 |
| 参考后端 | [backend-reference](../backend-reference/README.md) | 启动、演示登录、Mock、数据库集成测试 |
| 参考 App | [android-reference-app](../android-reference-app/README.md) | 模块划分、运行模式、会前开会条件 hook、构建期部署地址 |
| 客户端核心 | [meeting-client-core](../android-reference-app/meeting-client-core/README.md) | 会议状态机、事件、身份与结果动作契约 |
| 业务 AAR | [meeting-android](../android-reference-app/meeting-android/README.md) | 业务 AAR 的组成、构建与设备 AAR 握手 |
| 公共协议 | [contracts](../contracts/README.md) | OpenAPI、JSON Schema、实时事件协议与夹具 |

## 四、工具速查

- [本机开发验证](../tools/README.md)：`verify-local.sh` 覆盖什么、数据库集成测试怎么开。
- [设备侧工具](../tools/android-device/README.md)：安装、登录、开会、停止、浮窗与结果检查。
- [契约夹具检查](../tools/contract-fixtures/README.md)：公开协议样例的校验。
- [音频源 SDK](../system-audio-contract/README.md)：公开接口、Stub 和 Mock。

## 五、维护者与来源说明

这几份不是接入必读，改代码或追溯来源时再看：

- [贡献指南](../CONTRIBUTING.md)、[后端源码归属](../backend-reference/SOURCE.md)、[契约来源](../contracts/SOURCE.md)
- [参考应用 UI 资源来源](../android-reference-app/app/UI-RESOURCE-SOURCES.md)、[参考窗口资源来源](../android-reference-app/docs/czur-overlay-resources.md)、[结果页资源说明](../web-reference/ASSETS.md)

## 怎么选，一句话

- 只要后端能跑：第一节 1、2，加 [backend-core](../backend-core/README.md)。
- 只要 App 能跑：第一节 1、3，加 [android-reference-app](../android-reference-app/README.md)。
- 只要结果页：[web-reference](../web-reference/README.md) + [contracts](../contracts/README.md)。
- 要交付到客户现场：第一节 1、4，加 [部署说明](../deploy/README.md)。

各文档都标明哪些是参考实现、哪些是可选样例、哪些需要伙伴自己提供。没有配置的服务不会
自动使用 CZUR 账号；文中的域名、地址和凭据都应替换为部署方自己的值。

# CZUR AI Meeting Reference

[English](README.md) | **[简体中文](README.zh-CN.md)**

AI 字幕纪要的开源参考实现：Android 应用、后端、结果网页、公共协议和测试工具都在同一个仓库里。
你可以先把完整链路跑通，再按模块替换成自己的实现；模型账号、存储、身份和计费由你自己的服务提供。

## 这个项目是什么

| 模块 | 作用 |
| --- | --- |
| Android 应用（`android-reference-app/`） | 会议采集与界面：登录、会议选项、实时字幕、悬浮窗、结果回看；含公共客户端核心和设备 AAR |
| 后端（`backend-core/`、`backend-reference/`） | 面向客户端的控制面、会议状态机、产物存储、Manifest、权益边界和后台任务 |
| 结果网页（`web-reference/`） | 静态结果阅读器：原文、译文、纪要、发言人命名和结果链接 |
| 协议（`contracts/`） | 控制面 OpenAPI、Manifest Schema、实时协议和一致性夹具 |
| 工具（`tools/`） | 本地启动脚本、设备辅助、契约校验和交付检查 |
| 部署（`deploy/`） | 唯一一份 MySQL 5.7 基线和参考部署示例 |

典型基线：Android 14（API 34）设备、JDK 21 后端、MySQL 5.7、静态 Web 主机。主链路是
登录 → Create → 实时模型会话 → 字幕事件 → Stop → Manifest → 结果页；翻译、结构化纪要和
本场分人是可选能力。

## 文档导览

| 你要做什么 | 读这个 | 约 |
| --- | --- | --- |
| 动手前先看要准备什么 | [接入前准备清单](docs/preparation-checklist.zh-CN.md) | 10 分钟 |
| 第一次把真实字幕跑起来 | [从零接入指南](docs/partner-getting-started.zh-CN.md) | 30–60 分钟 |
| 接自己的模型、语言或能力组合 | [模型与能力配置指南](docs/model-and-capability-guide.md) | 20 分钟 |
| 换登录、存模型、加结果动作 | [扩展开发手册](docs/partner-integration-guide.zh-CN.md) | 40 分钟 |
| 交付到自己的服务器 | [部署说明](deploy/README.md) | 30 分钟 |
| 只嵌入会议结果页 | [Web 结果页](web-reference/README.md) | 15 分钟 |
| 先看哪些验证过、哪些没有 | [支持范围与验收清单](docs/validation-scope.md) | 10 分钟 |

第一次接入只走一条线：先过准备清单，再走从零接入指南第 1–6 步，命令可直接粘贴；设备 AAR 随仓库交付，
建好 Android 与 Web 两份本地部署配置后，可从干净仓库重建。其余文档（各模块 README、工具、公共协议）见 **[文档导航](docs/README.md)**。

## 仓库里有什么

| 目录 | 内容 |
| --- | --- |
| `backend-core/`、`backend-reference/` | 会议核心与参考后端（Java 21） |
| `android-reference-app/` | 参考 App、客户端核心、业务 AAR（含设备 AAR） |
| `web-reference/` | 结果页静态源码 |
| `contracts/`、`system-audio-contract/` | 公共协议与音频源接口 |
| `deploy/` | 后端 Docker Compose 示例（连接外部 MySQL） |
| `tools/` | 本机验证、契约校验、设备侧工具 |

## 使用与交付边界

| 谁 | 约定 |
| --- | --- |
| 接入方 | 不把生产地址、密钥、证书、用户数据或设备签名材料提交进仓库；本地配置放仓外或未跟踪文件 |
| 使用设备 AAR 的伙伴 | AAR 二进制随本参考仓交付；闭源 SDK 源码按交付许可使用 |
| 发布方 | 发布前按[验收清单](docs/validation-scope.md#目标环境的最小验收)自测，使用正式签名和自己的部署环境 |

开发环境、验证命令、贡献流程和发布边界见 [CONTRIBUTING.md](CONTRIBUTING.md) 与
[本机开发验证](tools/README.md)。

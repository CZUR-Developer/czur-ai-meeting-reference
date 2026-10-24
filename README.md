# CZUR AI Meeting Reference

开源参考实现用于演示 AI 字幕纪要的正交插件化架构。合作伙伴需要自行提供并运营 Android 应用、后端、模型、存储、认证和计费；本仓库提供可运行的参考代码、公共协议和测试工具，不包含 CZUR 生产实现或设备签名材料。

## 合作伙伴从这里开始

| 阅读顺序 | 文档 | 解决的问题 |
| --- | --- | --- |
| 1 | [从零接入指南](docs/partner-getting-started.md) | 工程组成、SDK交付材料、资源与环境、配置／启动／第一场真实会议、Docker交付 |
| 2 | [模型与能力配置指南](docs/model-and-capability-guide.md) | 听悟／百炼／讯飞分别做什么、需要哪些资源、是否可缺失、如何替换和组合 |
| 3 | [扩展开发手册](docs/partner-integration-guide.md) | 登录保持、鉴权、模型Adapter、存储、权益、结果动作和SDK工具的扩展边界 |

首次接入按第1份文档的真实字幕主线推进；默认Mock构建和JSON协议样例只用于模拟／接线，
不替代真实音源与模型。设备 AAR 随参考仓交付（`android-reference-app/meeting-android/libs/ai-meeting-device.aar`），干净 clone 即可构建。

## 工程结构

| 目录 | 内容 |
| --- | --- |
| `pom.xml` | IntelliJ IDEA 的 Maven 聚合入口（Java 21） |
| `backend-core/` | 独立普通 JAR：共享会议、协议、能力、持久化与扩展端口 |
| `backend-reference/` | 依赖核心的参考应用：启动、演示登录、Mock 和默认装配 |
| `android-reference-app/` | Kotlin First 的参考应用、会议公共核心及模型契约，可由 Android Studio 打开 |
| `web-reference/` | 参考 Web 静态页面源码 |
| `contracts/` | OpenAPI、JSON Schema、实时事件协议与 Fixture |
| `system-audio-contract/` | 音频源 SDK 的公开接口、Stub 和 Mock |
| `tools/` | 契约校验和验收脚本 |
| `docs/` | 接入、开发和部署说明 |
| `deploy/` | 后端 Docker Compose 示例（连接外部 MySQL） |

合作伙伴扩展开会条件时，按 [`android-reference-app/README.md`](android-reference-app/README.md#合作伙伴会前开会条件-hook)
替换参考 App 的默认放行检查。参考工程默认不包含扫码接收和小程序接收端；录音已
按本场 `retainAudio` 留存策略接入 `audio.original` 公共产物链并由 Web 流式回放，伙伴仍可自行
开发接收者流程并在开会前读取其已缓存的状态，不必修改模型或会议收口链。

SDK 构建已接入可选的截图黑盒会话，图片处理、编码和去重由闭源 SDK 提供；参考 App
只负责授权、周期／末帧、窗口排除与业务保存确认。Bitmap／File 和资源归属见
[`Android 截图说明`](android-reference-app/README.md#独立sdk截图与参考演示帧)。

## 本机开发验证

使用 IntelliJ IDEA 打开根目录 `pom.xml`，使用 Android Studio 打开
`android-reference-app/`。Android 最低支持 Android 14／API 34。
应用的系统音频实现和签名组件由设备厂商通过 SDK 提供，不在此仓库中实现。

开发机安装 Java 21、Maven、Python 3 和 Node.js/npm 后，从仓库根目录运行：

```bash
bash tools/verify-local.sh
cd android-reference-app && ./gradlew :app:assembleDebug
```

前一条命令检查工程结构、Java 测试、协议 Fixture 和 Web 静态构建；Android
构建需要本机 Android SDK。参考链路的数据库集成测试在显式提供隔离 MySQL 5.7
连接时运行，配置方式见 [`backend-reference/README.md`](backend-reference/README.md)。
日常开发验证不启动 Docker；本机若没有 MySQL，可临时使用 `mysql:5.7` 容器，但
容器、数据卷和账号不属于交付物。公开发布前再单独执行
`python3 tools/check-public-boundary.py`。

MySQL 5.7 的独立能力探针见 [`tools/mysql57-compatibility/`](tools/mysql57-compatibility/README.md)；
此入口仅做本机隔离能力探针。共享核心保留 PostgreSQL 兼容适配，参考客户端直连
会议链默认使用 MySQL 5.7；非默认 WSS／Mock 链可在显式 PostgreSQL 测试夹具中运行，
不改变参考方案的 MySQL 交付边界。

公共后端核心的边界和消费方式见 [`backend-core/README.md`](backend-core/README.md)。
两模块从根目录执行 `mvn -B clean test`；单独构建参考应用使用
`mvn -B -pl backend-reference -am package`，无需预先安装核心。

## Android 部署地址随构建注入

合作伙伴开发者在 PC 上配置后端与结果页地址，打包后交给终端用户；App 不提供填写 IP／
网址的入口。部署参数只有一处：`android-reference-app/app/src/main/java/com/czur/aimeeting/reference/meeting/ReferenceDeployment.kt`
里的 `BACKEND_URL` 与 `RESULT_WEB_URL`，改完重新构建即可。仓库预置的是参考部署地址
（`192.168.0.116`）：在别的网络里必须替换，否则设备连不上；改回占位域名时构建会警告、
App 提示"部署地址未配置"。

Debug 构建如需信任本机自签 CA，把 PEM 放到
`app/src/debug/res/raw/reference_debug_ca.pem`（已被 Git 忽略），构建自动生成对应的
网络安全配置；文件不存在时只信任系统 CA（生成方式见
[本机局域网 HTTPS 联调](docs/local-lan-https.md)）。
设备端没有手输地址界面；构建期配置说明见
[`Android 说明`](android-reference-app/README.md#构建期部署地址)及
[`构建与启动工具`](tools/android-device/README.md)。

## 交付部署

交付用的后端 Dockerfile 和 Compose 配置不参与上述开发验证。参考后端的配置与
启动命令见 [`deploy/README.md`](deploy/README.md)。先由部署方创建 MySQL 5.7
数据库。在仓库根目录将 `.env.example` 复制为 `deploy/.env` 并填写，再进入 `deploy/`
执行唯一 V001 初始化和 Compose 启动；完整顺序见[从零指南第6节](docs/partner-getting-started.md#6-docker-交付部署)。
Compose 不启动数据库容器；本机临时 MySQL 容器只属于测试环境，不作为交付目标。

根目录和 Android 工程都提交可重建的 Gradle Wrapper；首次执行可能需要下载
Gradle 和 Android 构建插件。Web 按 `web-reference/README.md` 生成静态资源。

后端默认配置只使用占位地址。本机JVM路径使用 `tools/reference-client.env`，
Compose路径使用 `deploy/.env`；两份文件各自从对应模板复制。真实地址、令牌或证书不提交到仓库。

## 公共仓库边界

禁止提交生产地址、密钥、证书、用户数据、AOSP Framework/vendor/HAL、
设备签名配置、CZUR 云端代码和闭源 SDK 二进制。所有本地配置放在未跟踪的
`.env` 或外部密钥管理系统中；仓内模板仅包含占位值。

合作伙伴的准备清单和实际接入步骤见[从零接入指南](docs/partner-getting-started.md)；
全部模型及能力缺失行为见[模型与能力配置指南](docs/model-and-capability-guide.md)；
接口扩展和交付边界见[扩展开发手册](docs/partner-integration-guide.md)；
已覆盖的验证范围和目标环境验收清单见[当前支持范围](docs/validation-scope.md)。

## 发布前检查

发布自己的产品前，应检查适用许可证、依赖、运行配置和敏感信息边界，
使用正式签名与部署环境完成[验收清单](docs/validation-scope.md#目标环境的最小验收)。
配套闭源 SDK 按其交付许可使用，不随参考源码重新公开。

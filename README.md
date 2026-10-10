# CZUR AI Meeting Reference

开源参考实现用于演示 AI 字幕纪要的正交插件化架构。合作伙伴需要自行提供并运营 Android 应用、后端、模型、存储、认证和计费；本仓库提供可运行的参考代码、公共协议和测试工具，不包含 CZUR 生产实现或设备签名材料。

## 工程结构

| 目录 | 内容 |
| --- | --- |
| `pom.xml` | IntelliJ IDEA 的 Maven 聚合入口（Java 21） |
| `backend-core/` | 独立普通 JAR：共享会议、协议、能力、持久化与扩展端口 |
| `backend-reference/` | 依赖核心的参考应用：启动、演示登录、Mock 和默认装配 |
| `android-reference-app/` | 可由 Android Studio 打开的参考应用工程 |
| `web-reference/` | 参考 Web 静态页面源码 |
| `contracts/` | OpenAPI、JSON Schema、实时事件协议与 Fixture |
| `system-audio-contract/` | 音频源 SDK 的公开接口、Stub 和 Mock |
| `tools/` | 契约校验和验收脚本 |
| `docs/` | 接入、开发和部署说明 |
| `deploy/` | 后端 Docker Compose 示例 |

合作伙伴扩展开会条件时，按 [`android-reference-app/README.md`](android-reference-app/README.md#合作伙伴会前开会条件-hook)
替换参考 App 的默认放行检查。参考工程不包含扫码接收、小程序或录音归档；伙伴可自行
开发接收者流程并在开会前读取其已缓存的状态，不必修改模型或会议收口链。

## 本机开发验证

使用 IntelliJ IDEA 打开根目录 `pom.xml`，使用 Android Studio 打开
`android-reference-app/`。Android
应用的系统音频实现和签名组件由设备厂商通过 SDK 提供，不在此仓库中实现。

开发机安装 Java 21、Maven、Python 3 和 Node.js/npm 后，从仓库根目录运行：

```bash
bash tools/verify-local.sh
cd android-reference-app && ./gradlew :app:assembleDebug
```

前一条命令检查工程结构、Java 测试、协议 Fixture 和 Web 静态构建；Android
构建需要本机 Android SDK。数据库集成测试仅在显式提供隔离 PostgreSQL 连接时运行，
配置方式见 [`backend-reference/README.md`](backend-reference/README.md)。日常开发验证
不启动 Docker。公开发布前再单独执行 `python3 tools/check-public-boundary.py`。

MySQL 5.7 的独立能力探针见 [`tools/mysql57-compatibility/`](tools/mysql57-compatibility/README.md)；
此入口仅做本机隔离能力探针。共享核心的正常 SDK 直连会议链支持 PostgreSQL 和
MySQL 5.7；参考进程默认 PostgreSQL，旧 WSS／Mock 链仍限 PostgreSQL。

公共后端核心的边界和消费方式见 [`backend-core/README.md`](backend-core/README.md)。
两模块从根目录执行 `mvn -B clean test`；单独构建参考应用使用
`mvn -B -pl backend-reference -am package`，无需预先安装核心。

## 交付部署

交付用的后端 Dockerfile 和 Compose 配置不参与上述开发验证。参考后端的配置与
启动命令见 [`deploy/README.md`](deploy/README.md)。在
`deploy/` 准备 `.env` 和 WSS 证书后，使用 `docker compose --profile database up --build`
启动演示数据库；`docker compose up --build` 连接 `.env` 指定的外部
PostgreSQL。两种模式复用同一迁移脚本，再启动单进程后端。

根目录和 Android 工程都提交可重建的 Gradle Wrapper；首次执行可能需要下载
Gradle 和 Android 构建插件。Web 按 `web-reference/README.md` 生成静态资源。

默认配置只使用占位地址。复制 `.env.example` 为本地 `.env` 后再按需修改，
不得把真实地址、令牌或证书提交到仓库。

## 公共仓库边界

禁止提交生产地址、密钥、证书、用户数据、AOSP Framework/vendor/HAL、
设备签名配置、CZUR 云端代码和闭源 SDK 二进制。所有本地配置放在未跟踪的
`.env` 或外部密钥管理系统中；`.env.example` 仅包含占位值。

## 开源发布

Gitee 是开发主仓；经过许可证、依赖和敏感信息扫描，并由发布负责人批准后，
才将版本标签单向同步到 GitHub。日常开发不直接向 GitHub 推送。

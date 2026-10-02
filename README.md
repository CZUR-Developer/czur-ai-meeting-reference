# CZUR AI Meeting Reference

开源参考实现用于演示 AI 字幕纪要的正交插件化架构。合作伙伴需要自行提供并运营 Android 应用、后端、模型、存储、认证和计费；本仓库提供可运行的参考代码、公共协议和测试工具，不包含 CZUR 生产实现或设备签名材料。

## 工程结构

| 目录 | 内容 |
| --- | --- |
| `backend-reference/` | 可由 IntelliJ IDEA 导入的 Maven 参考后端（Java 21） |
| `android-reference-app/` | 可由 Android Studio 打开的参考应用工程 |
| `web-reference/` | 参考 Web 静态页面源码 |
| `contracts/` | OpenAPI、JSON Schema、实时事件协议与 Fixture |
| `system-audio-contract/` | 音频源 SDK 的公开接口、Stub 和 Mock |
| `tools/` | 契约校验和验收脚本 |
| `docs/` | 接入、开发和部署说明 |
| `deploy/` | 后端 Docker Compose 示例 |

## 开发入口

使用 IntelliJ IDEA 打开 `backend-reference/pom.xml`，使用 Android Studio 打开
`android-reference-app/`。Android
应用的系统音频实现和签名组件由设备厂商通过 SDK 提供，不在此仓库中实现。

```bash
./gradlew checkStructure
cd backend-reference && mvn test
cd ../android-reference-app && ./gradlew :app:assembleDebug
cd ..
python3 tools/check-public-boundary.py
cd web-reference && npm run check && npm run build
```

参考后端的配置与启动命令见 [`deploy/README.md`](deploy/README.md)。在
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

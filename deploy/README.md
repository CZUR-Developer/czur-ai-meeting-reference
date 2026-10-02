# 参考后端 Docker 部署

本目录部署 P2 的 ASR-only Mock 参考后端：一个应用进程同时提供 HTTP、WSS 和周期收口 Worker。PostgreSQL 是必需的；可使用 Compose 演示数据库或部署方已有的 PostgreSQL。两种模式都运行 `migrate.sh` 与唯一的 `migrations/V001__initial_schema.sql`，应用不会自动建表。此处没有真实模型、后台管理或 Web 容器。

## 准备配置

在本目录执行 `cp ../.env.example .env`，然后编辑 `.env`。至少填写：

| 配置 | 应填写什么 |
| --- | --- |
| `REFERENCE_DEV_IDENTITY_ENABLED`、`AI_MEETING_CREATE_ENABLED` | 演示时都设为 `true`。 |
| `REFERENCE_DEV_IDENTITY_BEARER_TOKEN` | 自行指定不少于 32 字节的演示登录 Token；调用会议接口时作为 Bearer Token。 |
| `AI_MEETING_PAGE_TOKEN_SECRET` | 自行指定不少于 32 字节的分页密钥。 |
| `AI_MEETING_TLS_KEYSTORE_FILE`、`AI_MEETING_TLS_KEYSTORE_PASSWORD` | 可读取的 PKCS#12 文件路径及其密码；Compose 将文件只读挂载给 WSS。 |
| `AI_MEETING_WSS_ORIGIN` | App 可访问的 `wss://主机:端口`；端口须与 `WSS_PORT` 一致。 |

示例已经给出 24 小时的结果链接有效期；如需调整，修改 `AI_MEETING_RESULT_LINK_TTL_MS`。保持该项为正数，才能读取结果页使用的 Manifest。

本地演示证书可用 `keytool -genkeypair -alias meeting -keyalg RSA -storetype PKCS12 -keystore local-meeting.p12 -validity 30 -dname CN=localhost -ext SAN=dns:localhost,ip:127.0.0.1` 生成，按提示输入密码，再填入 `.env`。使用自签名证书的客户端需要信任该证书。`.env` 和证书不入库。

## Compose 演示数据库

在 `deploy/` 目录执行：

```bash
docker compose --profile database up --build
```

Compose 先等待 PostgreSQL 健康，再运行一次迁移；迁移成功后启动应用。验证 `curl http://localhost:8080/internal/health/readiness` 返回成功。Artifact 使用 `meeting-artifacts` 卷，数据库使用 `meeting-db` 卷；`docker compose --profile database down` 停止服务但保留数据卷。

## 外部 PostgreSQL

仍在同一个 `.env` 中把 `DATABASE_URL`、`DATABASE_USER`、`DATABASE_PASSWORD` 改成容器可访问的外部数据库地址和凭据，例如 `jdbc:postgresql://db.example.invalid:5432/meeting`。然后在 `deploy/` 执行：

```bash
docker compose up --build
```

此命令不启动 `db`，但仍由同一个 `migrate` 服务执行 `migrate.sh`，成功后才启动应用。外部数据库必须已创建目标空库且可连接；迁移用户需有建表权限。App 与迁移使用 `.env` 中相同的数据库地址和凭据。

`API_PORT`、`WSS_PORT` 控制宿主机映射端口；容器内固定监听 8080/8443。后端运行失败时先检查迁移服务退出状态、readiness 和应用日志。结果读取仍遵循本仓的统一 HTTP 协议；本期只交付 Mock 字幕及模拟最终转写，不代表已接入合作伙伴真实模型。

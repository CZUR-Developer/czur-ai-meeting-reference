# 参考后端 Docker 部署

本目录部署参考后端：一个应用进程同时提供 HTTP、旧 WSS 音频链路和周期收口 Worker。PostgreSQL 是必需的；可使用 Compose 演示数据库或部署方已有的 PostgreSQL。两种模式都运行 `migrate.sh` 与唯一的 `migrations/V001__initial_schema.sql`，应用不会自动建表。P5 新增听悟临时会话签发；后端不转发模型音频，也不包含后台管理或 Web 容器。

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

`API_PORT`、`WSS_PORT` 控制宿主机映射端口；容器内固定监听 8080/8443。后端运行失败时先检查迁移服务退出状态、readiness 和应用日志。结果读取仍遵循本仓的统一 HTTP 协议；旧 WSS 音频链路继续产生 Mock 字幕，P5 直连模型链路由 App 提交标准事件。

## P5 听悟模型会话材料

后端可通过 `.env` 中的 `AI_MEETING_TINGWU_ENABLED=true` 开放已认证的
`POST /v1/meetings/{meetingId}:modelSession`。另需由部署方注入
`TINGWU_APP_KEY`、`ALIYUN_ACCESS_KEY_ID`、
`ALIYUN_ACCESS_KEY_SECRET`；参考后端也接受 `.env.example` 中的
`AI_MEETING_TINGWU_*` 同义配置，前者有值时优先使用。Compose 会把宿主环境
已注入的三个标准变量传给应用容器。可用
`AI_MEETING_TINGWU_SOURCE_LANGUAGE=cn`（或 `en`）选择本次部署的识别语言；
`AI_MEETING_TINGWU_CREATE_TASK_URL` 缺省为阿里云北京听悟 OpenAPI 地址。
这些值属于部署方自己的听悟账号，不依赖 CZUR 云账号。

接口只为同一主体拥有的 CREATED/RUNNING 会议签发临时材料。服务端调用
听悟实时 `CreateTask`，将 `Data.MeetingJoinUrl` 放在 `endpoint`，将
`Data.TaskId` 放在 `initParams.taskId`。JoinUrl 自带连接凭据，本协议的可选
`token` 字段因此省略。参考 App 使用 `endpoint` 直连听悟并发送音频；后端仍只
接收 `startClient` 和 `events:append` 的标准事件。每次请求会创建新听悟任务，
客户端应只在准备开始本场连接时请求，并在 `expiresAt` 前启动连接。此时间是
本后端的客户端启用窗口，不是听悟承诺的 URL 失效时间。旧 Create v1 的
`realtime` 和 WSS 音频路径不受此配置影响。

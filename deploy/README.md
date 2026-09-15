# 参考后端部署与数据库

本目录提供参考后端的 Docker Compose 拓扑和 PostgreSQL 迁移示例。数据库容器
只用于本地演示、契约测试和快速验证；CZUR 生产环境及合作伙伴环境可以继续
使用已经管理的外部 PostgreSQL，不能把数据库 Docker 化当作接入前提。

## 选择数据库来源

| 模式 | 数据库来源 | Compose 行为 | 适用场景 |
| --- | --- | --- | --- |
| `reference-db` | Compose 中的 PostgreSQL 16 | 仅启用 `database` Profile 时启动 `db` | 本地开发、演示、契约测试 |
| `external-db` | CZUR 或合作伙伴管理的 PostgreSQL | 不启用 `database` Profile；通过 `DATABASE_URL` 注入 | 生产、私有云、局域网部署 |

`api` 和 `worker` 不再声明对 `db` 的强依赖，因此默认执行
`docker compose up` 不会启动数据库。应用镜像仍需要一个可用的数据库地址；
未配置外部地址时，Compose 的占位默认值指向 `db`，只有在启用
`database` Profile 后才可用。

## Compose 快速启动（参考数据库）

```bash
cp ../.env.example .env
docker compose --profile database up -d db
docker compose --profile database run --rm migrate
docker compose up -d api worker
docker compose ps
```

`db` 具有 `pg_isready` 健康检查；`migrate` 等待健康状态后按文件名顺序执行
`migrations/V*.sql`。迁移任务执行完成后会退出，这是正常现象。迁移版本记录在
`schema_migrations` 中，重复运行只会跳过已登记版本。

## 使用外部数据库

复制 `.env.example` 后设置真实连接信息（不要提交 `.env`）：

```bash
DATABASE_URL='jdbc:postgresql://db.example.invalid:5432/meeting' \
DATABASE_USER='meeting_app' \
DATABASE_PASSWORD='<从 Secret Manager 注入>' \
docker compose up -d api worker
```

应用支持 `jdbc:postgresql://` 地址；执行仓库迁移脚本时也支持
`postgresql://`/`postgres://` 地址：

```bash
DATABASE_URL='postgresql://db.example.invalid:5432/meeting' \
DATABASE_USER='meeting_migrator' \
DATABASE_PASSWORD='<从 Secret Manager 注入>' \
./migrate.sh
```

迁移账号只需要迁移期间的建表权限；应用账号应按部署方策略限制为运行所需的
最小权限。脚本日志只输出迁移版本和结果，不输出完整连接串、密码、Token、
音频或会议正文。

外部数据库可以用只读检查确认连通性和凭据：

```bash
DATABASE_URL='postgresql://db.example.invalid:5432/meeting' \
DATABASE_USER='meeting_app' \
DATABASE_PASSWORD='<从 Secret Manager 注入>' \
./healthcheck-db.sh
```

## 迁移和备份规则

- `migrations/` 是公开、版本化的数据库事实源；文件只追加，不修改已经在生产
  执行过的版本。新版本使用例如 `V002__add_result_index.sql` 的命名。
- 每个迁移文件必须在 PostgreSQL 16 和外部 PostgreSQL 上通过 `ON_ERROR_STOP`
  验证，并保持可重复执行或由 `schema_migrations` 明确跳过。
- Outbox Worker 的 `claim`、`markPublished`、`markFailed` 必须在同一个条件
  更新中校验 `lease_owner`、`lease_token`、`lease_version` 和仍有效的
  `lease_until`。`V002__outbox_lease_fencing.sql` 提供这些字段和过期租约扫描
  索引；内存适配器继续只用于单进程测试，不代表生产持久化语义。
- 数据库保存会议状态、事件、幂等记录、工作流租约、产物元数据和 Outbox；音频
  及大文件仍在对象/文件存储，必须独立备份。
- 备份、恢复、恢复后的契约测试和对象存储校验见
  [`backup-and-restore.md`](backup-and-restore.md)。

## Gateway 边界

Compose 只描述容器拓扑。Gateway（如果部署方已有）负责 TLS、路由、基础认证
上下文和限流；会议状态、幂等、模型能力、计费、存储、Outbox 和 Worker 逻辑
集中在参考后端，不需要为了切换数据库修改 Gateway 业务代码。

## 配置与安全

示例中的数据库用户、密码、模型地址都是占位值。生产部署必须通过环境变量、
Docker Secret 或外部 Secret Manager 注入真实值，不得把填充后的 `.env`、备份、
证书或连接串提交到仓库。

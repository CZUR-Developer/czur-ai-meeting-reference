# PostgreSQL 迁移目录

迁移文件按 `V<编号>__<描述>.sql` 命名，并按文件名顺序执行。例如新增字段
时创建 `V002__add_manifest_revision.sql`，不要修改已经在任何环境执行过的
文件。每个版本执行成功后，`deploy/migrate.sh` 会把文件名写入
`schema_migrations`；重复运行会跳过已登记版本。

当前 `V001__initial_schema.sql` 提供参考后端最小事实源：会议聚合、事件和
实时流游标、工作流步骤租约、产物元数据、用量预占、HTTP/命令幂等、可靠
Outbox，以及插件调用诊断。它不包含终端用户密码、模型密钥、对象存储正文
或 CZUR 私有账号字段。

迁移由部署方执行：

```bash
DATABASE_URL='postgresql://db.example.invalid:5432/meeting' \
DATABASE_USER='meeting_migrator' \
DATABASE_PASSWORD='<从 Secret Manager 注入>' \
../migrate.sh
```

CZUR 生产可以复用这些 SQL 和约束，但继续使用现有数据库发布流程；不要求
使用 Docker PostgreSQL，也不把 Gateway 作为迁移或业务逻辑入口。

# PostgreSQL 迁移目录

迁移文件按 `V<编号>__<描述>.sql` 命名，并按文件名顺序执行。例如新增字段
时创建 `V002__add_manifest_revision.sql`，不要修改已经在任何环境执行过的
文件。每个版本执行成功后，`deploy/migrate.sh` 会把文件名写入
`schema_migrations`；重复运行会跳过已登记版本。

当前 `V001__initial_schema.sql` 提供参考后端最小事实源：会议聚合、事件和
实时流游标、工作流步骤租约、产物元数据、用量预占、HTTP/命令幂等、可靠
Outbox，以及插件调用诊断。它不包含终端用户密码、模型密钥、对象存储正文
或 CZUR 私有账号字段。`V002__outbox_lease_fencing.sql` 为 Outbox 增加
`lease_token` 和单调递增的 `lease_version`，用于防止租约过期后的旧 Worker
误确认事件。`V003__identity_entitlement.sql` 增加会议主体、Grant、主预占引用、
会前用量预占和独立的权益操作幂等表。V001/V002 历史用量逐行保留并标记为
`LEGACY_V1`，历史会议标记为 `LEGACY_UNOWNED`；迁移不会猜测真实用户、Grant，
也不会从历史多条用量中选择主 Reservation。

V003 必须允许在“Schema 已提交但版本登记失败”后安全重跑。执行约束前会先
检查历史 Reservation 状态，发现未知状态时整事务失败；不要绕过该检查手工登记
版本。没有 PostgreSQL 时可以先运行静态门禁：

```bash
python3 ../../tools/check-identity-entitlement-migration.py
```

## Outbox 租约栅栏约束

生产数据库实现 `claim`、`markPublished`、`markFailed` 时，必须使用条件更新
同时校验以下四项：`lease_owner`（持有者）、`lease_token`（本次租约令牌）、
`lease_version`（栅栏版本）和 `lease_until`（租约仍在有效期内）。任一条件
不满足时，更新行数应为 0，并由调用方重新领取或记录冲突；不能依赖 Worker
本地缓存判断成功。成功重新领取时，`lease_version` 必须原子递增并生成新的
`lease_token`。`PENDING` 或未领取事件的 `lease_token` 使用 `NULL`。

示意条件（字段名可映射到具体数据库访问层）：

```sql
UPDATE outbox_event
   SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP
 WHERE event_id = :event_id
   AND status = 'LEASED'
   AND lease_owner = :owner
   AND lease_token = :token
   AND lease_version = :version
   AND lease_until > CURRENT_TIMESTAMP;
```

参考后端的内存适配器用于单进程测试，保持原有行为，不模拟数据库租约持久化；
生产适配器必须按上述条件更新实现。迁移字段本身不生成令牌，也不负责计费或
业务重试。

没有 PostgreSQL 客户端时，可先运行不连接数据库的静态检查：

```bash
python3 ../tools/check-outbox-migration.py
```

迁移由部署方执行：

```bash
DATABASE_URL='postgresql://db.example.invalid:5432/meeting' \
DATABASE_USER='meeting_migrator' \
DATABASE_PASSWORD='<从 Secret Manager 注入>' \
../migrate.sh
```

CZUR 生产可以复用这些 SQL 和约束，但继续使用现有数据库发布流程；不要求
使用 Docker PostgreSQL，也不把 Gateway 作为迁移或业务逻辑入口。

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

`V004__meeting_event_envelope.sql` 为规范事件增加 `spec_version`、`stream_id` 和
`canonical_payload`。三个字段必须同时为空或同时为非空；三者为空只表示 V001
历史事件，JDBC 读取会 fail-closed，不会猜测归属。新事件在 `canonical_payload`
保存上游已规范化的 JSON 原文，并由数据库约束验证其解析结果与 `payload` JSONB
一致：前者用于精确幂等比较和原样返回，后者继续用于 JSON 查询与索引。V004 尚未
发布；进入共享环境后不得原地修改，应按后续版本迁移规则追加新文件。

`V005__meeting_finalization_fact.sql` 在 `meeting` 上增加 meeting-level
`input_state`，并约束只有 `INPUT_ACCEPTED` 可以且必须携带非空
`finalization_receipt_id`。Stop 只写 `INPUT_COMPLETE_PENDING`；后续 Worker 在
真正接管输入时才原子写入 receipt 并推进会议生命周期。`meeting_stream` 继续只
保存实时逻辑流的游标/恢复事实，查询不得临时聚合 stream 行生成 receipt。参考
Schema 尚未发布，V005 只验证 V001→最新版本的全新数据库，不为未发布的中间
开发数据提供回填规则。

`V006__meeting_read_model.sql` 在 `meeting` 上增加默认值为 `NOT_STARTED` 的
`manifest_state`，并分别约束可持久化的会议生命周期和 Manifest 状态。协议解码
哨兵 `MeetingState.UNKNOWN` 不允许入库。该迁移还建立
`(deployment_scope, subject_scope, created_at DESC, meeting_id DESC)` 索引，供后续
认证查询适配器按所有者隔离并进行稳定的 keyset 分页；V006 本身不实现分页 Token、
查询端口或 Manifest 内容存储。

`V007__meeting_manifest_revision.sql` 新增追加式 `meeting_manifest_revision` 表。
每一版同时保存 T14-01 codec 产生的 canonical JSON、等价 JSONB 镜像和 canonical
UTF-8 的原始 32 字节 SHA-256；数据库关闭式校验 12 个顶层字段、会议身份、revision、
最终状态和摘要。revision 1 必须没有前序，后续版本必须通过复合自外键精确引用同场
会议的前一版。该迁移不引入 `pgcrypto`、head/`is_latest`、额外索引、trigger、回填
或业务 DML；最新版本由复合主键反向读取。V007 会精确核对已有同名表，只有“DDL 已
提交但尚未登记版本”的完整结构可以安全重跑，半表、错列和同名弱约束都会失败。

`V008__durable_input_fact.sql` 新增每会议一个的 durable input 接管事实；
`V009__final_usage_fact.sql` 固化由该输入事实得出的最终标准用量；
`V010__workflow_step_fencing.sql` 将 V001 的工作流占位行收紧为可恢复 checkpoint、
明确的下一次领取时间和完整租约 fencing 状态。这三个版本只冻结 JDBC 物理事实，
不会启动 Worker、重新读取 Provider 状态或推断历史业务值。

`V011__finalization_execution_artifact_ready.sql` 新增每会议一个的
`finalization_execution_fact`：四列分别保存 `meeting_id`、codec canonical JSON、
等价 JSONB 镜像与 canonical UTF-8 的原始 32 字节 SHA-256。数据库关闭式校验六个
根字段、`finalization-execution-fact/v1`、会议身份、`startedAt` 微秒上限、可空
标题、Profile/Plan 对象、JSON 镜像和摘要，不重复拆出 startedAt/Profile/Plan 列。

V011 同时原位替换 V001 的 `artifact` 占位表，不另建同义表。新表的一行只表示产物
已经 READY，保存 artifact/meeting/type/media/language/revision、唯一 intent、唯一
storage binding、size/checksum 与 `recorded_at`；不保存 status、正文、checkpoint、
Profile、租约、Manifest、URL、路径、凭据或开放式 metadata。迁移只接受“精确 V001
artifact 且零行、execution 表不存在”的首次执行，或“两张 V011 表同时完整存在”的
精确重跑。V001 artifact 有任意行、只存在一张 V011 表、半表、错列、弱约束、错误
外键或 view 都会使整个 `BEGIN`/`COMMIT` 事务失败并保留原状，部署方不得先手工登记
版本或删除旧行绕过门禁。

V011 还关闭式锁定三类基础条件：`meeting.meeting_id` 必须仍是第 1 列的
`TEXT NOT NULL` 非延迟主键；`meeting` 与两张 V011 表都必须是独立普通持久表，
不接受 UNLOGGED/TEMP、partition leaf 或传统 `INHERITS` 父/子表；Artifact 文本值按
Java 21 `String.isBlank()` 的完整空白码点集判定，不只是去除普通空格。迁移先把目标
schema 固定在 `pg_temp` 之前，避免临时同名表遮蔽；再用 transaction advisory lock
串行化同 schema 的 V011，并按 `meeting → finalization_execution_fact → artifact`
固定锁序取得 catalog 保护。execution 的 `ACCESS SHARE` 不阻断业务写，artifact 的
`ACCESS EXCLUSIVE` 保护 V001 检空与 `DROP TABLE`。因此精确重跑不会与正常的
execution→artifact 写事务形成反向等待环；并发插入已提交的 V001 旧行会使迁移失败并
保留原行，不会在检空与 `DROP TABLE` 之间丢失。

`V012__artifact_deletion_plan.sql` 新增每会议一个的 `meeting_deletion_plan` 和按
`(meeting_id, ordinal)` 固定顺序的 `artifact_deletion_item`。计划保存删除受理时间、
可空的来源 Manifest revision、可空的最终收口 Workflow 身份、目标数量、删除计划
codec 的 canonical JSON、等价 JSONB 镜像、原始 32 字节 SHA-256 以及
`PENDING/COMPLETED` 终态时间。计划 JSON 关闭六个根字段并核对会议、时间、来源、
目标数量、镜像和摘要；item 固定 artifact/type/revision，只有 `DELETED` 可以且必须
携带 Store 返回的不透明 binding 和删除时间。item 故意不外键引用 READY `artifact`
表，使元数据删除与审计确认可以在同一后续事务中完成。

V012 同时增加 `meeting_deleted_at_equivalence_ck`：Meeting 只有在生命周期和 Manifest
都为 `DELETED` 时才能保存非空 `deleted_at`，且该时间必须等于同次终态写入的
`updated_at`；其它生命周期必须保持 Manifest 非 `DELETED` 且 `deleted_at` 为空。
首次迁移若发现任一 `DELETION_PENDING`、`DELETED` 或非空 `deleted_at` 旧事实会整
事务失败，因为数据库无法证明对应删除计划。迁移只接受“三个对象全部不存在”的首次
执行和“三个对象全部精确存在”的原样重跑；半表、半约束、弱 CHECK、错误 FK、额外
索引、TEMP 遮蔽、UNLOGGED/分区/继承表均关闭式拒绝。迁移使用 schema 级 advisory
lock，并按 Meeting → 来源 Workflow → 来源 Manifest → 删除计划 → item 的顺序加锁。
参考运行时在后续切片通过 readiness 启用 V012，本版本迁移提交本身仍保持 V011 runtime
门槛，确保每个小提交可以独立编译和回退。

这里的版本登记机制是仓库自带的 `deploy/migrate.sh` 与 `schema_migrations`，不是
Flyway。脚本在 SQL 成功提交后登记完整文件基名；如果发生“DDL 已提交但版本登记
失败”，应原样重跑脚本，让迁移自身的精确 catalog 门禁确认结构，不能只凭列名补
登记。

V003 和 V007～V012 必须允许在“Schema 已提交但版本登记失败”后安全重跑。执行约束前会先
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

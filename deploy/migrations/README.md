# 首次交付数据库基线

本项目尚未正式发布，每个支持的数据库引擎只交付一份当前 `V001__initial_schema.sql`。两份 V001 表达同一版结构，部署按引擎择一执行；开发阶段的中间迁移与历史数据回填不属于交付物。

| 引擎 | 当前基线 | 初始化入口 | 登记表 |
| --- | --- | --- | --- |
| PostgreSQL 16（参考默认） | [V001__initial_schema.sql](V001__initial_schema.sql) | [deploy/migrate.sh](../migrate.sh) | `schema_migrations` |
| MySQL 5.7（CZUR 新服务选用） | [mysql/V001__initial_schema.sql](mysql/V001__initial_schema.sql) | [deploy/migrate-mysql.sh](../migrate-mysql.sh) | `ai_trans_schema_migrations` |

`deploy/migrate.sh` 在首次启动时将 V001 建表与 `schema_migrations` 登记放入同一事务；再次启动时检查登记并跳过。Compose 自带数据库与外部 PostgreSQL 使用相同脚本，应用进程不自动建表。

MySQL 初始化仅向已经存在的目标数据库新增 26 张 `ai_trans_*` 表（含登记表和同键事务辅助锁表），不创建数据库或账号，不修改旧业务表，也不加入旧服务的迁移版本链。使用本机 `mysql` 客户端；`MYSQL_CLIENT` 可以指定已安装客户端路径。与应用使用相同的 `DATABASE_URL`、`DATABASE_USER`、`DATABASE_PASSWORD`，URL 支持 `jdbc:mysql://主机[:端口]/库名` 或 `mysql://主机[:端口]/库名`，当前入口支持主机名／IPv4；JDBC 查询参数由应用消费，初始化客户端自身固定 UTF-8 和 UTC。连接地址和 Secret 不输出到脚本日志。

`ai_trans_idempotency_lock` 仅为 MySQL 的完整 Create 事务持久序列化原编码 `scope`/`idempotency_key`：二元主键使用完整 UTF-8 字节（2048/256 字节），先 no-op 插入，再在调用方事务内 `FOR UPDATE`。它不保存请求/响应或业务状态，不做后台清理、恢复平台或在线迁移；首次未发布版本仍并入同一 MySQL V001。PostgreSQL 保留原 advisory transaction lock 和原 V001，不要求这张辅助表。

```bash
export DATABASE_URL='jdbc:mysql://127.0.0.1:3306/meeting_example'
export DATABASE_USER='<database-user>'
export DATABASE_PASSWORD='<database-password>'
bash deploy/migrate-mysql.sh
```

MySQL 的 DDL（数据定义语句）会隐式提交，无法承诺整份初始化事务回滚。脚本只有在全部表、索引和即时外键成功建立后才登记 `V001__initial_schema`；已完整登记的重复执行直接跳过。脚本不关闭外键检查，会议／预占的双向外键保留，先建立全部表再添加即时引用。若初始化中途失败，不登记当前基线；已经提交的部分结构保持现场，本期不自动修复、删除或迁移它们。

MySQL 使用 InnoDB、`ROW_FORMAT=DYNAMIC`、`utf8mb4` 和 UTC `DATETIME(6)`。标识键使用 `VARBINARY` 保存 UTF-8 字节，保证大小写和尾空格精确比较；不使用截断前缀唯一索引。JSON 与 canonical 原文分别保存，重放和摘要读取原文 `LONGTEXT`；32 字节摘要为 `BINARY(32)`，音频为 `BLOB`，`target_languages` 为 JSON 字符串数组。MySQL 5.7 不执行 `CHECK`，基线不复制 PostgreSQL 的检查约束／指纹体系；领域校验和工作单元关系检查仍由公共持久层承担。JSON 列不设置 5.7 不支持的默认值，写入方必须显式提供 `metadata`（通常为 `{}`）。

MySQL 完整索引上限为 3072 字节，当前专用表按字节限定索引字段：一般 ID／`meeting_id`／`step_key` 1024，`deployment_scope` 256、稳定 `subject_scope` 1024、编码幂等 `scope` 2048、`idempotency_key` 256、索引类型 128、`operation_scope` 64、`artifact.storage_binding` 3072。`idempotency_record.subject_scope` 保存编码范围，使用 2048 字节；状态为有限领域枚举。该范围用于首版普通生成标识；PostgreSQL 保留原有部分 Artifact 文本 4096 字节上限，MySQL 并不宣称该边界完全等价。`media_type`、`artifact_deletion_item.storage_binding` 及其他不参加索引／外键的正文标识使用 `LONGTEXT`，不人为套用索引上限。部署前按所选引擎核对配置与实际标识范围。

当前正常生成值的来源可直接核对公共源码：`ReferenceMeetingHttpAssembly.RandomIds` 使用 24 字节随机数转无填充 Base64 URL，会议 ID 为 36 字节；`JdbcClientMeetingResultFinalizer` 的最终文本／翻译／总结 ID 为固定前缀＋64 位十六进制摘要，分别 75／87／83 字节，`JdbcClientMeetingInputPort` 的演示截图 ID 为 88 字节。产物类型为当前固定的 `transcript.final`／`translation.final`／`summary.structured`／`presentation.capture`（最长 20 字节）。CZUR OSS 复用的 `S3ArtifactStore` 生成 `s3_intent_v1_`＋摘要（77 字节）和 `s3_v1_`＋摘要（70 字节）；参考文件系统实现对应值为 81／74 字节。这些当前生成值在 MySQL 首版物理键范围内，不将任意外部实现的 4096 字节输入视为已支持。

当前开发使用隔离测试库或随机 schema，结构变化时同步更新这份 V001 和对应的空库测试，不新增 V002。已有的未发布开发测试库无需迁移，可重新初始化；不要把此规则用于承载真实用户数据的正式版本。首次正式发布后，V001 固定不再改写，后续确需升级时才新增版本化迁移。

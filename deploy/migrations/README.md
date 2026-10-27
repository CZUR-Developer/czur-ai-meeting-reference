# 参考方案唯一数据库基线

参考代码的局域网运行统一使用 MySQL 5.7 兼容模式。仓库只交付一份当前初始化脚本：

- [V001__initial_schema.sql](V001__initial_schema.sql)
- 执行入口：[deploy/migrate.sh](../migrate.sh)
- 等价调用入口：[deploy/migrate-mysql.sh](../migrate-mysql.sh)
- 基线登记表：`ai_trans_schema_migrations`

脚本只向调用方已经创建的目标数据库新增会议专用 `ai_trans_*` 表，不创建数据库或账号，不读写其他业务表。应用启动只做只读 readiness 检查，不自动建表。

确需绕过脚本、手工执行 V001 时，必须在整份 SQL 成功后补跑 `INSERT INTO ai_trans_schema_migrations(version) VALUES ('V001__initial_schema')`，否则应用会以迁移未登记（`MIGRATION_MISSING`）拒绝启动。

MySQL 5.7 的 DDL（数据定义语句）会隐式提交，脚本只有在整份 V001 成功后才登记 `V001__initial_schema`；已完整登记的重复执行直接跳过。脚本不关闭外键检查，不删除或修复中途失败留下的结构。失败后请在仍然隔离的测试库中人工核对，再重新初始化。

数据库使用 InnoDB、`utf8mb4`、二进制排序和 UTC `DATETIME(6)`。MySQL 5.7 不执行 CHECK 约束，领域校验和工作单元关系由公共持久层承担。JSON、规范化正文、音频和摘要按当前公共 core 的 MySQL 映射保存。表名固定为 `ai_trans_*`，不支持通过配置改前缀，避免误写现有业务表。

已有开发库升级：本仓仍只维护一份 V001 基线，已登记 `V001__initial_schema` 的库
不会自动重跑。只有初始化时间早于 `ai_trans_meeting_finalization_pending` 表加入
V001 的开发库才需要手工补齐：对已有库执行与 V001 完全等价的单表 `CREATE TABLE`
（字段、主键、索引、InnoDB／utf8mb4／`ROW_FORMAT=DYNAMIC` 一致），不要重跑整份
基线，也不要改写已有业务数据。这一步不产生新版本：不要删除或改写
`ai_trans_schema_migrations` 中已有的 `V001__initial_schema` 行，也不要伪造
`V002`；建表成功后用 `SHOW CREATE TABLE` 核对结构即可，readiness 只检查 V001
登记和固定表存在。新建库仍统一使用 `deploy/migrate.sh`。

本机局域网测试示例：

```bash
export DATABASE_URL='jdbc:mysql://127.0.0.1:3306/meeting_reference?useSSL=false&allowPublicKeyRetrieval=true'
export DATABASE_USER='meeting'
export DATABASE_PASSWORD='只在本机注入'
bash deploy/migrate.sh
```

MySQL 容器可以作为本机临时测试依赖，例如使用 `mysql:5.7` 并只绑定 loopback；容器、数据卷和测试账号由测试方维护。正式部署由部署方提供 MySQL 实例，并通过 `deploy/migrate.sh` 执行同一份 V001。公共持久层当前只支持 MySQL 5.7，非 `jdbc:mysql://` 地址在启动期被拒绝；支持范围见[后端说明](../../backend-reference/README.md#数据库范围)。仓库不交付其他数据库的初始化脚本或适配路径。

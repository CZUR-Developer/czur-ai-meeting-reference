# 参考方案唯一数据库基线

参考代码的局域网运行统一使用 MySQL 5.7 兼容模式。仓库只交付一份当前初始化脚本：

- [V001__initial_schema.sql](V001__initial_schema.sql)
- 执行入口：[deploy/migrate.sh](../migrate.sh)
- 等价调用入口：[deploy/migrate-mysql.sh](../migrate-mysql.sh)
- 基线登记表：`ai_trans_schema_migrations`

脚本只向调用方已经创建的目标数据库新增会议专用 `ai_trans_*` 表，不创建数据库或账号，不读写其他业务表。应用启动只做只读 readiness 检查，不自动建表。

MySQL 5.7 的 DDL（数据定义语句）会隐式提交，脚本只有在整份 V001 成功后才登记 `V001__initial_schema`；已完整登记的重复执行直接跳过。脚本不关闭外键检查，不删除或修复中途失败留下的结构。失败后请在仍然隔离的测试库中人工核对，再重新初始化。

数据库使用 InnoDB、`utf8mb4`、二进制排序和 UTC `DATETIME(6)`。MySQL 5.7 不执行 PostgreSQL 的 CHECK 约束，领域校验和工作单元关系由公共持久层承担。JSON、规范化正文、音频和摘要按当前公共 core 的 MySQL 映射保存。表名固定为 `ai_trans_*`，不支持通过配置改前缀，避免误写现有业务表。

已有开发库升级：本仓仍只维护一份 V001 基线，已登记 `V001__initial_schema` 的库
不会自动重跑。新增 `ai_trans_meeting_finalization_pending` 时，只需对已有库执行
与 V001 完全等价的单表 `CREATE TABLE`（字段、主键、索引、InnoDB／utf8mb4／
`ROW_FORMAT=DYNAMIC` 一致），不要重跑整份基线，也不要改写已有业务数据。

本机局域网测试示例：

```bash
export DATABASE_URL='jdbc:mysql://127.0.0.1:3306/meeting_reference?useSSL=false&allowPublicKeyRetrieval=true'
export DATABASE_USER='meeting'
export DATABASE_PASSWORD='只在本机注入'
bash deploy/migrate.sh
```

MySQL 容器可以作为本机临时测试依赖，例如使用 `mysql:5.7` 并只绑定 loopback；容器、数据卷和测试账号由测试方维护。正式部署由部署方提供 MySQL 实例并执行同一份 V001。公共 core 包含 PostgreSQL 可选适配器，参考仓仅交付 MySQL 初始化脚本，默认链使用 MySQL。

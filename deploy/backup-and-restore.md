# 数据库备份与恢复

参考后端把会议状态、幂等记录、工作流租约和 Outbox 作为可恢复事实保存到
PostgreSQL。音频和会议产物仍位于配置的对象存储或文件存储中，数据库备份
不能代替对象存储备份；恢复时必须同时恢复两者，才能得到完整会议结果。

## 备份

使用合作伙伴或 CZUR 已有的 PostgreSQL 运维工具执行逻辑备份。以下示例使用
占位环境变量，命令不会把密码写入脚本或 Git：

```bash
export PGHOST=127.0.0.1
export PGPORT=5432
export PGDATABASE=meeting
export PGUSER=meeting_backup
export PGPASSWORD='<从 Secret Manager 读取>'
pg_dump --format=custom --file=backup/meeting-$(date +%Y%m%d%H%M%S).dump
unset PGPASSWORD
```

生产环境应由部署方设置备份周期、保留时间、加密位置和访问审计，并定期在
隔离数据库验证备份可读。不要把 `PGPASSWORD`、连接字符串或导出的用户数据
提交到仓库。

## 恢复

恢复前先停止创建会议和 Worker，避免恢复期间继续写入。确认目标数据库版本
与参考后端兼容后执行：

```bash
export PGHOST=127.0.0.1 PGPORT=5432 PGDATABASE=meeting PGUSER=meeting_restore
export PGPASSWORD='<从 Secret Manager 读取>'
pg_restore --clean --if-exists --no-owner --dbname="$PGDATABASE" backup/meeting-YYYYMMDDHHMMSS.dump
unset PGPASSWORD
```

随后运行 `./deploy/migrate.sh` 检查迁移版本，再恢复对象存储中的对应前缀，
最后通过健康检查和固定契约测试验证会议查询、幂等重放、Outbox 重试及产物
读取。恢复失败时保留原数据库和备份，不在原库执行不可逆的清理操作。

## Compose 本地卷

本地演示可以使用 `docker compose --profile database up -d db` 创建
`meeting-db` 卷。需要迁移时先运行 `docker compose --profile database run --rm
migrate`。删除该卷会永久删除本地演示数据；生产环境不要把 Compose 卷删除
当作备份方案。

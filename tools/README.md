# Development tools

Contract validators, fixture runners, and acceptance scripts are kept here.
Tools must be deterministic and must not require production credentials.

`check-outbox-migration.py` 检查 Outbox 租约栅栏迁移是否包含字段、非负约束、
幂等 DDL 和过期租约索引，并拒绝在迁移中写入或删除业务数据：

```bash
python3 tools/check-outbox-migration.py
```

## P2-T13 PostgreSQL 真重启门禁

`test-t13-postgres-restart.sh` 使用本次专属的 `postgres:16` Debian 容器和
named volume，执行仓库 `deploy/migrate.sh`，再分别在两个 Maven/JVM 中运行
`prepareBeforeRestart` 与 `verifyAfterRestart`。中间会真实 stop/start 同一
PostgreSQL 容器，验证 V001～V006 readiness、会议状态与 receipt、旧 HMAC 分页
token、meeting-global 事件分页，以及删除幂等和 Outbox 不会因重放增加：

```bash
tools/test-t13-postgres-restart.sh
```

脚本仅将数据库随机端口绑定到 `127.0.0.1`，不会调用 Compose、prune 或扫描并
删除已有资源。容器和数据卷都带有本次 run-id 归属标签；任何删除前都会按精确
名称读取并核对标签。正常退出、测试失败、`INT`、`TERM` 和 `HUP` 都会精确清理
日志中显示的本次容器、数据卷和状态目录。

`SIGKILL` 无法触发 shell trap。如果进程被强制杀死，先把脚本启动时打印的精确
名称逐字代入以下检查，并确认两个命令输出的 run-id 都与容器名中的本次 run-id
完全相同；标签不存在或不一致时禁止删除。不要使用通配符、模糊匹配、`prune`
或 `docker compose down`：

```bash
docker container inspect --format '{{index .Config.Labels "com.czur.ai-meeting.t13-restart.run-id"}}' <日志中的精确-container-name>
docker volume inspect --format '{{index .Labels "com.czur.ai-meeting.t13-restart.run-id"}}' <日志中的精确-volume-name>
```

标签核对通过后，才可执行：

```bash
docker rm -f -- <日志中的精确-container-name>
docker volume rm -- <日志中的精确-volume-name>
rm -rf -- <日志中的精确-state-directory>
```

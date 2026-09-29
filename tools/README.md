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
PostgreSQL 容器，验证 V001～V007 readiness、V007 原生 Manifest 文本/JSONB/摘要、
会议状态与 receipt、旧 HMAC 分页 token、meeting-global 事件分页，以及删除幂等和
Outbox 不会因重放增加：

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

## P2-T14-03-05 Manifest 正式端口双重启总门禁

`test-t14-manifest-restart.sh` 使用本次随机命名、带 T14 专属 ownership label 的
`postgres:16` Debian 容器和 named volume，并把仓库 `deploy/` 只读挂载后运行
真实 `deploy/migrate.sh` 登记 V001～V007。脚本在三个相互独立的 Maven/JVM 中
执行三个阶段，并在阶段之间对同一容器做两次真实 `docker stop`/`docker start`：

1. 阶段 A 经正式 owned 建会、领域推进、Stop、输入接管、Manifest begin/publish
   建立 READY revision 1，并以正式结果查询证明 Found；
2. 第一次重启后的阶段 B 证明 postmaster 启动时间严格变晚、发布稳定重放且不增
   revision，再经正式删除端口建立 DELETION_PENDING revision 2 并立即撤销结果；
3. 第二次重启后的阶段 C 再次证明启动时间严格变晚，删除稳定重放且 revision、
   删除幂等记录和 `MeetingDeletionRequested` Outbox 均不增加，最终 readiness 仍通过。

运行方式：

```bash
tools/test-t14-manifest-restart.sh
```

脚本每次启动和重启后都同时验证容器内 `pg_isready` 与宿主动态 IPv4 loopback
端口，并在每次重启后重新读取端口映射。它不会连接、停止、挂载或删除已有
PostgreSQL（包括任何固定名称或固定端口实例），不会调用 Compose、`prune`、模糊
搜索或通配删除。容器、数据卷在停止、启动和删除前都会按精确名称核对本次
run-id label；正常退出、失败以及 `INT`、`TERM`、`HUP` 会精确清理本次三项资源。
状态目录权限固定为 `700`、文件为 `600`，只保存阶段、revision 和 postmaster
启动时间，不保存数据库地址、账号、密码、credential、receipt 或 Manifest 正文。

`SIGKILL` 无法触发 shell trap。强制终止后的恢复只能使用脚本启动日志打印的三个
精确名称。先逐字代入以下命令，确认两个 label 都存在，且值与容器/卷名称中同一次
run-id 完全一致；名称不匹配、label 缺失或 label 不一致时必须停止，禁止删除：

```bash
docker container inspect --format '{{index .Config.Labels "com.czur.ai-meeting.t14-manifest-restart.run-id"}}' <日志中的精确-container-name>
docker volume inspect --format '{{index .Labels "com.czur.ai-meeting.t14-manifest-restart.run-id"}}' <日志中的精确-volume-name>
```

只有核对通过后，才可执行以下精确恢复；禁止替换为通配符、名称前缀、
`docker compose down`、`docker system prune` 或 `docker volume prune`：

```bash
docker rm -f -- <日志中的精确-container-name>
docker volume rm -- <日志中的精确-volume-name>
rm -rf -- <日志中的精确-state-directory>
```

## P2-T14-04-03 Outbox 类型领取与栅栏总门禁

`test-t14-outbox-fence.sh` 使用本次随机命名、带 T14 Outbox 专属 ownership
label 的 `postgres:16` Debian 容器和 named volume。脚本把仓库 `deploy/`
只读挂载后执行真实 `deploy/migrate.sh`，精确确认 V001～V007，再在三个相互
独立的 Maven/JVM 中依次完成以下验证：

1. 空或未知类型白名单不领取，已注册类型在排序和 limit 前过滤，未知类型保持
   `PENDING` 且 attempt、lease 和 version 均不变化；
2. 双 Worker 并发领取单赢家、过期 lease 被另一 Worker 重领、续租旋转 token/
   version/expiry、并发续租单赢家；
3. 旧 owner、旧 token、旧 version 和旧 expiresAt 对 renew/publish/fail 全部被
   栅栏拒绝，并区分不存在、过期、已发布和已死信；
4. 第三个 JVM 从真实数据库核对最终持久状态，而不只相信操作返回值。

运行方式：

```bash
tools/test-t14-outbox-fence.sh
```

脚本每个 JVM 启动前都会重新读取随机的 IPv4 loopback 端口；不使用固定端口，
不连接、停止、挂载或删除已有 PostgreSQL。宿主机无需安装 `psql`，迁移登记检查
使用本次 PostgreSQL 容器内的客户端。跨 JVM 必须交接的 lease token 仅短暂保存
在本次 `0700` 状态目录的 `0600` 文件中，从不写日志，并在第二阶段使用后立即
覆盖；正常退出、测试失败、`INT`、`TERM` 和 `HUP` 都会精确清理本次资源。

`SIGKILL` 无法触发 shell trap。强制终止后，只能使用脚本启动日志打印的三个
精确名称。先逐字代入以下命令，并确认 label 值与名称中的同一次 run-id 完全一致；
任一名称不匹配、label 缺失或 label 不一致时必须停止，禁止删除：

```bash
docker container inspect --format '{{index .Config.Labels "com.czur.ai-meeting.t14-outbox-fence.run-id"}}' <日志中的精确-container-name>
docker volume inspect --format '{{index .Labels "com.czur.ai-meeting.t14-outbox-fence.run-id"}}' <日志中的精确-volume-name>
```

确认归属后才可执行以下精确恢复，禁止使用通配符、名称前缀、
`docker compose down`、`docker system prune` 或 `docker volume prune`：

```bash
docker rm -f -- <日志中的精确-container-name>
docker volume rm -- <日志中的精确-volume-name>
rm -rf -- <日志中的精确-state-directory>
```

## P2-T14-09-10 Mock 最终收口双 JVM 恢复门

`test-t14-worker-cross-jvm.sh` 使用本次专属的 `postgres:16` Debian 容器、
named volume 和随机 IPv4 loopback 端口，在五个独立随机 schema 中验证真实进程
退出与恢复：第一个 Artifact READY、权益提交成功、成功 marker 已持久化、
Completion 已提交和失败 marker 已持久化。JVM-A 在对应线性化点调用
`Runtime.halt(47)`；JVM-B 重新构造正式 JDBC/文件 Store、权益 Provider、
Completion 和 Worker，并从同一 PostgreSQL 与文件根自主恢复；JVM-C 再证明终态
运行 idle，数据库和文件哈希不变。

```bash
tools/test-t14-worker-cross-jvm.sh
```

门禁同时要求 takeover 活跃期的旧 lease 对 renew、complete、fail、marker persist
和 marker recover 全部返回 `NOT_OWNER`，并核对 receipt、V008、V009、execution
fact、Workflow、Manifest、Artifact、usage operation、Reservation 和文件内容唯一。
checkpoint、业务正文和恢复决定不会通过进程环境传入，子进程输出还会执行完整泄露
检查。

脚本不会访问保留端口 `55432`，不会枚举或清理已有数据库资源，也不使用 Compose、
`prune`、通配符或模糊删除。容器和数据卷均带本次 run-id 归属标签；正常退出、失败及
`INT`、`TERM`、`HUP` 都只按精确名称和标签清理本次资源。`SIGKILL` 后必须先读取脚本
启动日志中的精确名称，并逐一核对以下标签；标签缺失或不一致时禁止删除：

```bash
docker container inspect --format '{{index .Config.Labels "com.czur.ai-meeting.t14-worker-cross-jvm.run-id"}}' <日志中的精确-container-name>
docker volume inspect --format '{{index .Labels "com.czur.ai-meeting.t14-worker-cross-jvm.run-id"}}' <日志中的精确-volume-name>
```

确认归属后，才可精确执行：

```bash
docker rm -f -- <日志中的精确-container-name>
docker volume rm -- <日志中的精确-volume-name>
```

# 本机开发验证

从仓库根目录运行 `bash tools/verify-local.sh`。脚本直接调用开发机上的 Gradle、
Maven、Python 3 和 npm，检查工程结构、Java 测试、公开协议 Fixture 和 Web 构建；
不启动 Docker，也不连接未显式指定的数据库。Android App 的构建与测试需在
`android-reference-app/` 使用本机 Android SDK 单独执行。

需要验证 JDBC 和会议持久化时，先准备**隔离的 PostgreSQL 16 测试库**，再按
`backend-reference/README.md` 设置 `REFERENCE_TEST_DATABASE_URL`、
`REFERENCE_TEST_DATABASE_USER` 和 `REFERENCE_TEST_DATABASE_PASSWORD`，运行同一条
`bash tools/verify-local.sh`。集成测试在随机 schema 中建表，不能使用承载用户数据的库。
没有测试库时，数据库集成测试会跳过；这不等于数据库验收通过。

`test-t13-*`～`test-t16-*` 是早期 P2 验收留下的 Docker 临时数据库脚本，
**不再作为当前开发验证入口，也不应在日常开发中执行**。对应历史测试结论见
AITransRepos 的 P2 验收记录。上述本机 Maven 测试不能自动代替旧脚本的跨进程、
数据库重启验收；若后续改动涉及这些语义，应另用本机进程与隔离 PostgreSQL
编排定向复验，完成前明确标记“未复验”。交付后端的 Dockerfile 和 Compose 用法只见
`deploy/README.md`，与开发验证分开。

公开发布前单独运行 `python3 tools/check-public-boundary.py`；它是发布内容检查，
不能替代编译、测试或实际功能验收。

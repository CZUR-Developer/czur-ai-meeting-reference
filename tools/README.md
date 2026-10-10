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

MySQL 5.7 的小型数据库能力实验见
[`mysql57-compatibility/`](mysql57-compatibility/README.md)。仅连接显式指定的本机隔离库，
不执行正式 V001；此入口仅为能力探针。共享核心的正常客户端会议链支持 MySQL 5.7，
参考默认进程的旧 WSS／Mock 链仍使用 PostgreSQL。

## Android 设备启动校验

[`android-device/`](android-device/README.md) 提供基于真实页面 resource-id 的启动工具：
一键填写后端／Web 地址、登录、选择字幕／互译及演示选项、确认系统授权、开始会议；
也可分步执行和复用 Debug 正常停止。只需本机 Python 3 和 ADB，密码由环境变量注入，
不使用 Docker、不注入 token。结果内容按改动范围另用脚本或视觉验收。

## P5-2 第二格式模型样例

`sample-json-ws-model.py` 是只供本机/设备接线演示的固定字幕 WebSocket
服务，不是生产模型。开发机执行 `python3 -m pip install 'websockets>=13,<14'`
后运行：

```bash
python3 tools/sample-json-ws-model.py --host 0.0.0.0 --port 19091
```

后端配置 `AI_MEETING_MODEL_ADAPTER=sample-json-ws`，并把
`AI_MEETING_SAMPLE_JSON_WS_ENDPOINT` 设为开发机可被设备访问的
`ws://<开发机局域网 IP>:19091`；设备上不能使用 `127.0.0.1` 指代开发机。
服务收到 `start` 返回 `ready`，收到 PCM 二进制帧后返回同一片段的
partial/final，收到 `stop` 返回 `finished`。这只验证第二 Adapter 的选择、
音频发送与字幕修订，不代表第二个真实模型已接入。

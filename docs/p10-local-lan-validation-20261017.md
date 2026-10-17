# P10 本机局域网验证记录（2026-10-17）

## 目标

本步只验证参考方案在本机 MySQL 5.7、HTTP 后端、Web 和 Android 设备之间的最小可运行闭环：登录、能力发现、开始字幕会议、实时显示、正常停止和同场结果回看。可选能力必须按服务端声明动态收敛；没有声明的总结、互译或声纹能力不能伪造入口，也不能阻塞字幕会议。

截图去重算法、截图黑盒封装和完整截图结果链按产品决定延期，本记录不把它们算入本步交付。

## 环境与边界

- 代码仓：`czur-ai-meeting-reference`，分支 `master`。
- 数据库：本机临时 MySQL 5.7 容器，绑定 `127.0.0.1`，隔离库名符合 `ai_trans_m1_*`；只执行当前唯一 `deploy/migrations/V001__initial_schema.sql`。
- HTTP 后端、Web 和样例模型均运行在开发机；Android 设备通过 ADB 反向端口访问开发机服务，设备端不使用 `127.0.0.1` 表示开发机。
- 本轮没有修改 Cloud、AOSP、Starry、CZUR 自带 App 或既有业务数据库，也没有写入第三方模型密钥。
- 设备会议只声明 `transcript.realtime`，用于验证“可选能力缺失时仍能完成基础会议”；这不是听悟、百炼或跨会议声纹的真实能力验收。

## 验证结果

### 本机代码与数据库

1. MySQL 5.7 隔离库初始化、健康检查和 M1/M2 集成验证通过：16/16，失败 0，错误 0，跳过 0。
2. 全量本机验证 `bash tools/verify-local.sh` 通过：Maven 测试 1327 项，失败 0，错误 0，跳过 408；协议 Fixture、Web 检查和 Web 构建均通过。
3. Android Debug APK 构建通过；`meeting-client-core` 测试 104 项通过；ADB 自动化工具测试 8 项通过。
4. `git diff --check`、公开交付检查、Markdown 代码围栏以及 JSON/YAML 静态检查通过。

### Android 设备最小闭环

- 登录后强制停止 App 再打开，仍显示“切换账号”，登录会话持久化通过。
- 会前能力发现通过；服务端只声明字幕时，总结入口隐藏，互译入口不可用。
- 字幕会议可开始，实时字幕显示，点击停止后正常收口，会议状态为 `READY`，结果页显示最终转写。
- 数据库中该场 `requestedCapabilities` 只有 `transcript.realtime`，没有错误请求 `speaker.diarization.session`。
- 明确请求服务端未声明的可选能力时，自动化工具会失败并给出能力未声明提示；这是预期门禁，不是异常。

## 尚未签收

以下项目需要相应真实配置或单独条件，不能由本次字幕链验收代替：

- 听悟／其他真实 Provider 的翻译和总结链；
- 录音二进制上传、Web 流式播放和对应设备结果；
- 跨会议声纹两场正向结果；
- HDMI、投屏、外设音源等系统条件场景；
- 截图去重算法和黑盒 API（按用户决定延期）。

## 复现命令

```bash
REFERENCE_TEST_MYSQL_DATABASE_URL='jdbc:mysql://127.0.0.1:33157/ai_trans_m1_p10_20261017?...' \
REFERENCE_TEST_MYSQL_DATABASE_USER='本机隔离账号' \
REFERENCE_TEST_MYSQL_DATABASE_PASSWORD='本机隔离密码' \
bash tools/verify-local.sh

cd android-reference-app
ANDROID_HOME=/home/cfy/Android/Sdk ./gradlew :meeting-client-core:test :app:assembleDebug
```

配置中的真实凭据、设备地址和临时产物不进入 Git；重新执行时必须使用新的本机隔离库或确认库内无业务数据。

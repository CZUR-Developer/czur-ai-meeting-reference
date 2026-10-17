# P10 本机局域网验证记录（2026-10-17）

## 目标

本步只验证参考方案在本机 MySQL 5.7、HTTP 后端、Web 和 Android 设备之间的最小可运行闭环：登录、能力发现、开始字幕会议、实时显示、正常停止和同场结果回看。可选能力必须按服务端声明动态收敛；没有声明的总结、互译或声纹能力不能伪造入口，也不能阻塞字幕会议。

截图去重算法、截图黑盒封装和完整截图结果链按产品决定延期，本记录不把它们算入本步交付。

## 首轮 Mock 最小闭环环境与边界

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

## 后续真实模型增量（同日）

在本机独立 MySQL 5.7、真实模型的外置配置和 SDK＋UI APK 上完成了一场正常会议。
后端使用参考专用 Profile／deployment scope，模型凭据仅注入仓外进程配置。
App 后端访问在本场明确使用 ADB reverse，Web 使用 PC 的真实局域网地址；本场不代签
完整 LAN HTTPS。Starry 的修改仅为已授权的 SDK 公共 core JAR 与来源说明同步，
CZUR App、Cloud、AOSP 和既有业务库未修改。

- 设备使用 `CZUR_BROKER` 实际采音，听悟返回最终原文 6 段／335 字、本场发言人 2 个。
- 正常调试停止走原 `requestStop` 流程，最终 170 条标准事件，录音封口用量 59,600 ms，
  后端与 App 均到 `READY`。
- 百炼真实总结已生成：概述 155 字、关键要点 4 项，统一 Manifest 包含原文、总结和录音。
- 音频 GET 返回 321,214 字节 OGG/Opus；FFprobe 检出单声道、48 kHz、59.62 秒，
  FFmpeg 完整解码通过。录音文件／正文未加入仓库。
- 设备实际 WebView DOM 显示“结果已生成”、6 段原文、总结和录音播放器；译文区隐藏。
  CDP 调试验证播放器实际 `play()` 成功，播放时间前进后正常暂停。
- 发现播放器 Range 请求仍被返回完整实体，时长显示为无限；正在补单段 Range，
  不能把“能播放”扩写为“时长／拖动播放全部通过”。

本场同时发现并纠正两处实际问题：本机环境误用 CZUR Profile 导致 Create 503，
以及媒体下载重新创建的 Matcher 未执行匹配即取 group 导致合法录音 GET 失败。
前者只修正仓外测试配置，后者提交 `f732d81` 并用本场下载验证。

公共客户端 `d3b9aa0` 保持旧 JVM 构造／复制接口，可选能力协商测试合计 106 项通过；
SDK 匹配 core 后 82 项通过，新 SDK＋UI 的参考 App 111 项通过，启动工具 10 项通过。
这些增量构建不冒作 Maven 全量重跑。

## 真实局域网 HTTPS 与互译增量（同日）

已移除本轮后端／Web 的 ADB reverse，设备改用 PC 局域网 HTTPS 入口登录和控制会议。
TLS 使用仓外 CA／IP SAN 证书，Debug 通过可选构建参数信任 CA，Release 未引用网络
安全配置且不打包开发 CA。SDK 的地址／证书校验没有放宽，模型 WSS 仍由设备直连。

- 自动化完成连接设置→真实登录→部署能力→中文到英语互译＋总结→RUNNING，
  实际音源为 `CZUR_BROKER`；PC 播放语音由设备麦克风收录。
- 正常停止后同场 `READY`；原文 7 段／410 字，译文 7 段，本场发言人 1 个；
  百炼概述 94 字、关键要点 5 项，录音产物 357,522 字节，采音用量 66,300 ms。
- 设备实际 WebView 重新加载后，“结果已生成”、7 段原文、7 段译文、纪要与录音
  同时可见。未装配跨场 Provider 时人物区隐藏。实际 `play()` 后时间推进并正常暂停。
- 录音时长仍为无限；`08fecf2` 已补单段 Range、9 项定向测试通过，但运行后端
  尚未加载该版本，因此本次不签时长与拖动播放。

设备结果验证已沉淀为 `tools/android-device/check-web-result.mjs`：经 WebView CDP
读取实际 DOM 数量，可选短暂播放／拖动，不输出正文或结果链接、不修改会议业务。
Web 构建会重建 `dist`；本轮在构建后按原启动脚本重新注入宿主 `config.json`，
避免把本机配置误加入源码或留给开发构建工具固定。

## 尚未签收

以下项目需要相应真实配置或单独条件，不能由本次字幕链验收代替：

- 真实字幕／互译／总结及 LAN HTTPS 已由上述两场分别通过；会中逐项交互仍需继续核对；
- 录音时长／拖动播放；二进制上传、同场 Web 播放和完整解码已在上场通过；
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

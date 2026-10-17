# Android 参考 App 启动校验工具

用 Python 3 标准库和 ADB 自动完成**打开 App → 填写后端／Web 地址 → 真实账号登录 →
选择功能 → 开始会议**。工具读取现有页面的 `resource-id`、`checked`、`enabled` 和
`bounds`，每次使用当前控件位置；不注入 token、不复制 SDK、也不另建会议状态机。
日常验证在开发机执行，无须 Docker 或测试平台。仅支持当前参考 App 的 UI；伙伴改 UI
后可调整脚本中的选择器。

## 准备

1. 安装已构建的参考 App，连接 USB／ADB，后端与 Web 服务已正常启动。
2. 默认使用局域网地址。设备上的 `127.0.0.1` 指设备自身；PC 服务地址应填 PC 的可达 IP。
3. 参考账号为后端已经配置的终端用户账号。密码从环境注入，勿写进命令参数、脚本或证据。
   可在本机用交互式 `read -s` 设置变量，或者使用已有环境注入方式：

```bash
read -r -s 'CZUR_MEETING_PASSWORD?测试账号密码：'
export CZUR_MEETING_PASSWORD
```

以上 `read` 为 zsh 示例。Bash 可用 `read -r -s -p '测试账号密码：' CZUR_MEETING_PASSWORD`。
密码实际经 ADB shell 的标准输入送入原生输入控件，不放进子进程 argv。
标准 Android `input text` 使用键盘映射，本工具支持单行 ASCII 账号／密码／地址，
字面量 `%s` 和非 ASCII 输入请在页面手动填写。Ctrl+A 清空字段使用 Android 14
`input keycombination`；不安装额外输入法。

## 一键配置并开始

从仓库根目录执行（把示例地址替换成实际部署地址）：

```bash
python3 tools/android-device/meeting-device.py setup-start \
  --serial '<ADB序列号>' \
  --backend-url 'http://<PC局域网IP>:8080' \
  --web-url 'http://<PC局域网IP>:8090/index.html' \
  --username 'meeting-user' --mode transcript --presentation \
  --output '/tmp/meeting-start-check'
```

默认只启用字幕，`--presentation` 启用演示截图，`--summary` 启用纪要。
`--mode translation --source-language 中文 --target-language English` 使用互译；语言名称与
页面显示一致，不写死后端的语言清单。互译与演示截图遵循参考产品现有规则，不能同时选择。
未声明或未装配的可选能力保持关闭；未请求该能力时属于正常降级，工具会在回执中标记
`summaryAvailable=false` 等事实。只有明确请求一个后端未声明的可选能力时才失败；用户选择的
必需能力缺失仍会阻止开始会议。

已登录时也会点击“切换账号”，由原页面退出并重新登录本次账号。每次连接配置只在 App
进程内保存；应用进程重启后重新执行 `setup-start`。工具不清除 App 数据，也不强杀会议。

开始阶段会确认已知的系统录音／整屏采集授权按钮，走与人工点击相同的权限流程，不使用
`pm grant`。加 `--manual-permissions` 可保留弹窗供人工确认；未知弹窗或等待超时会输出
当前页面的按钮 ID／文字，并留下脱敏 XML。屏幕锁定等情况需自行解除。

## 分步使用与正常停止

```bash
python3 tools/android-device/meeting-device.py open
python3 tools/android-device/meeting-device.py configure \
  --backend-url 'http://<PC局域网IP>:8080' --web-url 'http://<PC局域网IP>:8090/index.html'
python3 tools/android-device/meeting-device.py login --username 'meeting-user'
python3 tools/android-device/meeting-device.py options --mode transcript --presentation
python3 tools/android-device/meeting-device.py start
python3 tools/android-device/meeting-device.py status
python3 tools/android-device/meeting-device.py stop --timeout 90
```

多设备时给各命令传 `--serial`，或先设置 `ANDROID_SERIAL`。`stop` 复用 App 已有
`DEBUG_STOP`，经正常 `requestStop()` 完成末帧和会议收口，等待同场 READY；仅 Debug APK
支持，Release 请使用正常停止按钮。它不负责校验 Web 图片／字幕内容或模拟完整会议。

每个阶段输出一行 JSON：`stage`、`status`（PASS／PROGRESS／FAIL）和必要回执。
登录 PASS 确认真实登录与能力加载后开始按钮可用；开始 PASS 确认**本场 App RUNNING
日志**，不以 ADB 点击回执代替业务成功。动态字幕和音量动画会影响 UI dump，所以开始后
无需继续找停止按钮。`status` 可读取页面可见信息、当前 PID 的会议 ID／阶段、实际音源模式
（如 MIC／CZUR_BROKER）和截图模式；未出现在日志中的字段为 `null`，不猜测。
`submittedFrameCountInLogWindow` 只是设备当前日志缓冲区可见次数，不是后端最终图片总数。
工具不清 logcat；`start-ui.xml` 保留的是进入 RUNNING 前最近一次 UI／授权观察，不能当成
会中截图。结果内容可按改动范围选择现有脚本或少量视觉验证。

## 可选 USB 调试映射

只有显式创建 ADB reverse 后，设备的 `127.0.0.1` 才可访问相应 PC 端口：

```bash
python3 tools/android-device/meeting-device.py reverse --device-port 8080 --host-port 38169
python3 tools/android-device/meeting-device.py setup-start \
  --backend-url 'http://127.0.0.1:8080' --web-url 'http://127.0.0.1:8080/index.html' \
  --username 'meeting-user'
python3 tools/android-device/meeting-device.py remove-reverse --device-port 8080
```

映射要求该 PC 端口确实提供相应后端／Web 路由；工具不启动服务器，不自动覆盖其他端口。
`remove-reverse` 只移除指定端口，测试后由调用方执行。

## 团队设备 runner 与主机单测

默认运行本机 `adb`；可通过 `--adb-command 'python3 /path/to/adb-wrapper.py'` 或
`CZUR_ADB_COMMAND` 接入设备操作封装。包装器接收普通 ADB 参数（含 `-s`），透传 stdin，
原始命令输出写 stdout，失败返回非零；不要自行打印密码或完整输入内容。工具不依赖
某个 AI 工具的本机技能路径。单测不接触设备：

```bash
python3 -m unittest discover -s tools/android-device -p 'test_*.py' -v
```

`--output` 为可选证据目录，只写阶段 JSON 和脱敏 UI XML，不保存原始日志／密码／token。
仓库内需要保留本地运行输出时放在已忽略的 `reports/` 下；不要把真实账号、地址或设备
会议内容添加进公开仓库。

# Android 参考 App 启动校验工具

## 开发者先构建带部署地址的 APK

后端和结果页地址由合作伙伴开发者在 PC 上配置，随 APK 构建确定。终端用户只需登录和
选择会议功能，设备端没有连接设置或填写 IP 的步骤。正式部署可使用
[`deployment.properties.example`](../../android-reference-app/deployment.properties.example)
提供的键：复制为同目录已忽略的 `deployment.local.properties` 后填写实际值，或用
`-PreferenceDeploymentFile=/absolute/path/deployment.properties` 选择仓外文件。
显式 `-PreferenceBackendUrl=...`／`-PreferenceResultWebUrl=...` 优先于文件中的同名键。

局域网联调提供轻量构建入口，从仓库根目录执行：

```bash
python3 tools/android-device/build-lan-apk.py \
  --host '192.0.2.10' --backend-port 8443 --web-port 8090 \
  --sdk-aar /absolute/path/ai-meeting-sdk-0.5.0-release.aar \
  --ui-aar /absolute/path/ai-meeting-ui-0.2.0-release.aar \
  --debug-ca /absolute/path/ca.pem
```

`192.0.2.10` 为文档占位值，替换为设备可达的 PC 地址。省略 `--host` 时，仅根据本机
路由选取出口 IPv4，不向远端发请求；多网卡或网络隔离时请显式指定，并核对它与已准备的
TLS 证书 IP SAN 一致。默认生成后端 `https://<host>:8443` 和结果页
`http://<host>:8090/index.html`；端口独立可配，例如已有 Web 运行在 8091 时传
`--web-port 8091`。已有域名／路径可直接传 `--backend-url` 和 `--web-url`，完整 URL
原样交给 Gradle，保留 query／fragment；两者都提供时不探测主机地址。
工具始终把最终两个地址作为显式 `-P` 传入，覆盖本地部署文件；若要完全采用文件原值，
请直接在 Android 工程目录运行 Gradle，无需经过此 LAN helper。

`--dry-run` 只打印构建目录与实际参数，不运行 Gradle。默认 `--runtime sdk`，必须提供
匹配的主 SDK 和 UI AAR；不会因材料缺失退回 Mock。显式 `--runtime mock` 可验证基础
构建且无须 AAR，也可额外传 UI AAR。本工具只构建 Debug，不安装 APK、启动服务、运行
Docker、生成证书或修改 SDK；CA 只转发既有 Debug 构建参数。TLS 准备见
[`本机 HTTPS 指南`](../../docs/local-lan-https.md)。

## 独立截图 API 回归（SDK Debug）

已安装最新 SDK Debug APK 时，可以单独验证截图，不必登录、启动会议或提供模型 Key：

```bash
python3 tools/android-device/check-screen-capture-api.py \
  --serial '<ADB序列号>' --output '/tmp/screen-capture-api-check'
```

工具打开 Debug 专用测试画面，确认正常系统投影授权，自动核对 Bitmap/File、窗口排除、
同画面接受前可重试/接受后无新图、变化画面、新会话隔离及关闭一场不关闭底层授权。
它只读取 SDK 公开返回值，不读取算法中间信息。普通系统不支持排窗时，不签排窗通过。
输出为 JSON 和固定测试图，不采会议内容；存在运行中会议时拒绝启动。脚本不清数据、
不关闭真实会议。验后按返回离开测试页即可。此入口和投影前台 Service 均不进入 Release。

排查某个测试画面时，先在设备显示该页面，再加 `--current-screen` 运行。正常授权后
探针退回原页面，等待五秒，分别保存原始 PNG 与开启内置处理后的可选图片。此模式的
`PASS` 只表示观察流程完成；`processedImageDelivered=false` 只说明本次没有处理输出，
不单独判断过滤是否合理。原图可能包含当前屏幕内容，使用无敏感信息的测试素材。
每轮回执带独立 `runId`，脚本只读取本轮结果；需配套当前 SDK Debug APK，不能用旧回执
代替新的执行。算法原因和内部数据均不向此工具暴露。

## 日常会议流程

用 Python 3 标准库和 ADB 自动完成**打开 App → 真实账号登录 → 选择功能 → 开始会议**。
地址已经在构建时注入，工具读取现有页面的 `resource-id`、`checked`、`enabled` 和
`bounds`，每次使用当前控件位置；不注入 token、不复制 SDK、也不另建会议状态机。
日常验证在开发机执行，无须 Docker 或测试平台。仅支持当前参考 App 的 UI；伙伴改 UI
后可调整脚本中的选择器。可见账号字段会读回核对；中文输入法把按键合成为
全角字符时，工具切换一次现有输入法语言并重填，不安装额外输入法。密码不读回。

## 准备

1. 安装上述已注入部署地址的参考 App，连接 USB／ADB，后端与 Web 服务已正常启动。
2. 后端构建地址使用已配置 HTTPS、且设备信任证书的 PC 地址，Web 可以使用局域网 HTTP。
   设备上的 `127.0.0.1` 指设备自身；loopback 构建地址仅用于显式创建 ADB reverse 的诊断。
3. 参考账号为后端已经配置的终端用户账号。密码从环境注入，勿写进命令参数、脚本或证据。
   可在本机用交互式 `read -s` 设置变量，或者使用已有环境注入方式：

```bash
read -r -s 'CZUR_MEETING_PASSWORD?测试账号密码：'
export CZUR_MEETING_PASSWORD
```

以上 `read` 为 zsh 示例。Bash 可用 `read -r -s -p '测试账号密码：' CZUR_MEETING_PASSWORD`。
密码实际经 ADB shell 的标准输入送入原生输入控件，不放进子进程 argv。
标准 Android `input text` 使用键盘映射，本工具支持单行 ASCII 账号／密码，
字面量 `%s` 和非 ASCII 输入请在页面手动填写。Ctrl+A 清空字段使用 Android 14
`input keycombination`；不安装额外输入法。

## 一键登录并开始

从仓库根目录执行，设备侧不再传后端／Web 地址：

```bash
python3 tools/android-device/meeting-device.py setup-start \
  --serial '<ADB序列号>' \
  --username 'meeting-user' --mode transcript --presentation \
  --output '/tmp/meeting-start-check'
```

默认只启用字幕，`--presentation` 启用演示截图，`--summary` 启用纪要。
`--mode translation --source-language 中文 --target-language English` 使用互译；语言名称与
页面显示一致，不写死后端的语言清单。互译与演示截图遵循参考产品现有规则，不能同时选择。
未声明或未装配的可选能力保持关闭；未请求该能力时属于正常降级，工具会在回执中标记
`summaryAvailable=false` 等事实。只有明确请求一个后端未声明的可选能力时才失败；用户选择的
必需能力缺失仍会阻止开始会议。

未登录时直接填写账号表单；已登录时读取实际 UI 层级，在账号菜单中按完整文字选择
“切换账号”，再提交本次账号，不按菜单序号点击，也不选择“退出登录”。登录成功后由
原页面替换会话，取消或登录失败保留原有效身份。同一编译后端下有效登录会话在进程重启
后恢复；换后端重新打包后不会误用原身份。只想重开会议时可直接使用
`open → options → start`。工具不清除 App 数据，也不强杀会议。

开始阶段会确认已知的系统录音／整屏采集授权按钮，走与人工点击相同的权限流程，不使用
`pm grant`。加 `--manual-permissions` 可保留弹窗供人工确认；未知弹窗或等待超时会输出
当前页面的按钮 ID／文字，并留下脱敏 XML。屏幕锁定等情况需自行解除。

## 分步使用与正常停止

```bash
python3 tools/android-device/meeting-device.py open
python3 tools/android-device/meeting-device.py login --username 'meeting-user'
python3 tools/android-device/meeting-device.py options --mode transcript --presentation
python3 tools/android-device/meeting-device.py start
python3 tools/android-device/meeting-device.py status
python3 tools/android-device/meeting-device.py stop --timeout 90
```

多设备时给各命令传 `--serial`，或先设置 `ANDROID_SERIAL`。`stop` 复用 App 已有
`DEBUG_STOP`，经正常 `requestStop()` 完成末帧和会议收口，等待同场 READY；仅 Debug APK
支持，Release 请使用正常停止按钮。它不负责校验 Web 图片／字幕内容或模拟完整会议。
`open` 通过正常 Activity 导航回到会前／会中页；结果页仍在前台时使用其返回入口，
不等待已删除的连接按钮，不清数据或重置登录，也不强停当前会议。

每个阶段输出一行 JSON：`stage`、`status`（PASS／PROGRESS／FAIL）和必要回执。
登录 PASS 确认真实登录与能力加载后开始按钮可用；开始 PASS 确认**本场 App RUNNING
日志**，不以 ADB 点击回执代替业务成功。动态字幕和音量动画会影响 UI dump，所以开始后
无需继续找停止按钮。`status` 可读取页面可见信息、当前 PID 的会议 ID／阶段、实际音源模式
（如 MIC／CZUR_BROKER）和截图模式；未出现在日志中的字段为 `null`，不猜测。
`submittedFrameCountInLogWindow` 只是设备当前日志缓冲区可见次数，不是后端最终图片总数。
工具不清 logcat；`start-ui.xml` 保留的是进入 RUNNING 前最近一次 UI／授权观察，不能当成
会中截图。结果内容可按改动范围选择现有脚本或少量视觉验证。

## 设备实际 Web 结果与录音

结果页面已经在 App 中打开时，可用 Node.js 22+ 内置 WebSocket 读取该 WebView 的
实际页面和播放器。先找到本 App 进程 PID，再转发对应的调试 socket：

```bash
adb -s '<ADB序列号>' shell pidof com.czur.aimeeting.reference
# 将下方 <PID> 替换为上条返回值；仅 Debug WebView 开放该 socket。
adb -s '<ADB序列号>' forward tcp:19094 'localabstract:webview_devtools_remote_<PID>'
node tools/android-device/check-web-result.mjs --port=19094 \
  --meeting-id=mtg_当前场次 --expect=transcript,translation,summary,audio --reload --play
# 后端支持媒体 Range 后可增加 --seek，验证有限时长和实际拖动后继续播放。
```

`--expect` 只填写当前会议实际提供的模块，可选值为 `transcript`、`translation`、
`summary`、`audio`、`identity`、`presentation`。`presentation` 会逐张解码演示图片，要求
图片数量大于零且全部成功，避免只验到标题／图片占位。工具报告各区可见性和段落数；不会输出会议正文、账号、
Token 或结果链接。`--play`／`--seek` 会短暂播放后暂停；不传时只读状态。
该脚本不登录、不创建结果，也不把本场 Speaker 当作跨会议身份。

## 同场可选 PDF 插件验证（仅 Debug）

参考 App 默认只启用 Web。需要比对同一场 Web／PDF 时，在正常停止且打开结果后执行：

```bash
adb -s '<ADB序列号>' shell am start \
  -n com.czur.aimeeting.reference/.LocalPdfProbeActivity --es meetingId 'mtg_当前场次'
adb -s '<ADB序列号>' pull \
  /sdcard/Android/data/com.czur.aimeeting.reference/files/local-pdf-probe.json /tmp/local-pdf-probe.json
```

入口读取 Web 插件记住的原场次，使用当前登录身份、原有效期与标准正文读取端口，执行
`LocalPdfResultActionPlugin` 并打开实际 PDF。不会创建会议或改变默认注册表；没有近期
结果、身份失效或场次不匹配时报告失败，不静默改用其他场次。等待 `ReferencePdfProbe`
日志出现对应 `status=PASS` 再拉回执；其中只有数量和图片摘要。PDF 保存到设备公共
`Download/AI会议纪要/`。结合 `pdfinfo`／`pdfimages` 检查真实文件，回执本身不代签渲染。
此页面及验收入口均不进入 Release；伙伴正式接入仍通过结果插件注册表。

Debug 包的 `AiMeetingReference` 日志还记录截图请求是否为末帧、排除窗口数量，以及
回调是否有图片。可据此区分没有请求、尚未返回和正常无图片；不记录 SDK 内部判断、
特征或跳过原因。`presentation` 检查只证明已有图片能解码，变化页是否缺失、稳定页
是否重复及末帧是否正确，仍须和实际输入画面、时间及图片清单一起核对。

## 可选 USB 调试映射（仅诊断）

本机开发尚未配置 HTTPS 时，可以显式创建 ADB reverse，将设备 loopback HTTP 映射到
PC 后端。现有登录客户端允许这个开发入口；直接构建 `http://<PC局域网IP>` 会被拒绝。
以下例子将设备的 `127.0.0.1:8080` 映射到 PC 后端的 `38169` 端口，Web 仍走局域网：

```bash
# 开发者先构建并安装这个诊断APK；真实LAN验收仍使用HTTPS入口。
python3 tools/android-device/build-lan-apk.py \
  --backend-url 'http://127.0.0.1:8080' \
  --web-url 'http://192.0.2.10:8090/index.html' \
  --sdk-aar /absolute/path/ai-meeting-sdk-0.5.0-release.aar \
  --ui-aar /absolute/path/ai-meeting-ui-0.2.0-release.aar
python3 tools/android-device/meeting-device.py reverse --device-port 8080 --host-port 38169
python3 tools/android-device/meeting-device.py setup-start --username 'meeting-user'
python3 tools/android-device/meeting-device.py remove-reverse --device-port 8080
```

映射要求该 PC 端口确实提供后端 HTTP 路由，Web 服务也须已启动并可达；工具不启动服务器，
不自动覆盖其他端口。
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

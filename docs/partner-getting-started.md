# 合作伙伴从零接入指南

> 第一次接入 · 第 1/4 篇 ｜ 下一篇 → [模型与能力配置指南](model-and-capability-guide.md) ｜ 全部文档见 [文档导航](README.md)

本文面向首次接触本项目的 Android／后端开发者。读完后，您可以准备所需资源，
在自己的环境中完成“登录 → 真实语音字幕 → 停止 → 网页回看”，再按需加入互译、
总结、人物识别和演示截图。部署地址不进仓库，由本地文件提供（见 3.3 节）；缺少时构建直接失败。

建议按顺序阅读：

1. 本文：了解组成、准备环境、跑通第一场会议。
2. [模型与能力配置指南](model-and-capability-guide.md)：集中查看第三方平台、申请资源、功能对应关系和可选能力配置。
3. [扩展开发手册](partner-integration-guide.md)：替换登录、模型、存储、结果动作和 SDK 工具。

## 1. 这套参考工程可以做什么

本工程提供 AI 字幕纪要和双语互译的参考实现：Android 端采集语音、实时显示文字，
会议结束后通过默认 Web 页面查看结果。后端统一管理身份、会议、可用能力、
模型连接材料、用量和结果。合作伙伴可在此基础上开发自己的产品。

| 组成 | 作用 | 获得方式 |
| --- | --- | --- |
| Android 参考 App | 登录、会前选项、字幕／互译、悬浮窗、停止和结果入口 | 本仓 `android-reference-app/`，Kotlin First |
| 设备 AAR（闭源） | 音源探测、采音、截图、native 和 SubtitleTextView；不含 core／契约 | 本仓 `android-reference-app/meeting-android/libs/ai-meeting-device.aar`（0.5.0，SHA-256 `150f5a52294036b7e273a91d4d59afa7df212021bb6825e9199384023cd28053`，实际包含 arm64-v8a／armeabi-v7a／x86／x86_64，产品保证只有 ARM 两种） |
| 业务 AAR（源码） | 模型连接、数据归一化及通用会议门面 | 本仓 `android-reference-app/meeting-android/`，设备 AAR 已随仓在 `meeting-android/libs/ai-meeting-device.aar` |
| 字幕显示控件 | 设备 AAR 内置的 `com.czur.aimeeting.ui.SubtitleTextView` | 随设备 AAR 交付，不再单独提供 UI AAR |
| 公共服务端核心 | 会议流程、标准协议、能力协商、持久化与扩展端口 | 本仓 `backend-core/`，构建成普通 JAR |
| 参考后端 | 可启动的 HTTP 服务，装配示例登录、模型发行器和结果存储 | 本仓 `backend-reference/` |
| Web 结果页 | 按实际结果显示原文、译文、总结、录音、图片及人物信息 | 本仓 `web-reference/`，构建成静态文件 |
| 数据库初始化与部署示例 | 唯一 MySQL 初始化 SQL、后端 Dockerfile 和 Compose | 本仓 `deploy/` 及根目录 `Dockerfile` |

AAR 是 Android 库包。参考 App 固定使用设备 AAR + 本仓业务 AAR 构建；字幕/占位使用
设备 AAR 内的 `SubtitleTextView`，不再单独引入 UI AAR。设备 AAR 版本 0.5.0，
SHA-256 `150f5a52294036b7e273a91d4d59afa7df212021bb6825e9199384023cd28053`，ABI 实际包含 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`，产品保证只有 ARM 两种；业务 AAR 初始化时校验设备 AAR 版本。
宿主直接使用裸业务 AAR 时，还需显式提供 OkHttp 5.3.2 和 kotlinx-coroutines-android 1.11.0。

参考方案独立部署，不依赖 CZUR 的账号、业务服务、内部域名或现成的模型额度。
第三方服务账号和资源由部署方申请与维护，真实 Key 不包含在源码或 SDK 中。

## 2. 先理解数据流

下图为本指南的 Android 直连模型路径：

```text
                    登录、发现能力、创建／停止会议
Android App／SDK  <-------------------------------->  参考后端
       |                                               |
       | 实时音频直连，接收原文／译文                    +--> MySQL：会议及结果元数据
       v                                               +--> 文件目录／对象存储：结果正文
    实时语音模型                                        |
       |                                               +--> 可选声纹服务：会后匹配／注册
       v
SDK 归一化 --> App 显示字幕／互译
       |
       +--> 可选总结／独立翻译模型：SDK 按后端材料调用
       |
       +--> 标准文字、用量、可选录音／截图 --> 参考后端
                                                       |
                                     结果就绪后 --> Web 结果页
```

后端下发模型地址、短期连接材料和能力，App 无须自行拼接厂商请求。
实时语音流直接发送到模型；选择保留的封口录音仍会上传到后端供回放和可选人物处理。
默认 Web 用于会后回看，会中字幕显示在 App 中。

SDK 会探测设备能力：有兼容系统共享音源接口时使用该接口；普通 Android 或不具备
新接口的旧固件回退标准麦克风，并向 App 报告实际模式。普通系统的共享采音、方向角、
截图排窗等能力受系统限制，不能仅凭安装参考 App 获得。

## 3. 开始前准备什么

### 3.1 第一场真实字幕会议的必需资源

| 资源 | 需要准备的内容 | 用途 |
| --- | --- | --- |
| 工程源码 | 当前参考工程完整目录 | 同时构建后端、App 和 Web |
| SDK 交付包 | 设备 AAR 已随仓（0.5.0，SHA-256 `150f5a52294036b7e273a91d4d59afa7df212021bb6825e9199384023cd28053`）；业务 AAR 由本仓源码构建 | 真实音频与模型运行、字幕控件 |
| 开发电脑／服务主机 | 可被 Android 设备访问，具有可写结果目录 | 运行后端和静态 Web |
| MySQL | MySQL 5.7 兼容实例、专用数据库及可初始化／读写的账号 | 保存会议状态与元数据 |
| 一个实时语音服务 | 本指南默认路径使用您自己的阿里云听悟 AppKey、AccessKey ID／Secret及已开通服务 | 将真实语音转为字幕 |
| 示例登录信息 | 部署者自行设定的参考用户名、密码及会话配置 | 让终端用户正常登录 App |
| 网络和 HTTPS | 设备到后端、Web、模型均可达；App 登录入口有可信 HTTPS | 设备连接与模型交互 |
| Android 测试设备 | Android 14／API 34 或以上，麦克风、网络、ADB 调试条件 | 运行 App 和验证真实语音 |

初次只开启字幕即可，百炼总结、讯飞声纹、对象存储、邮件和小程序均非必需。
全部模型资源和缺失后的效果见[统一模型能力表](model-and-capability-guide.md#1-第三方平台与业务功能总表)。

### 3.2 本机工具

以下本机路径以 Bash 4+／Linux 风格命令为例；源码可分别在 Android Studio 和支持
Maven 的开发环境中打开。

| 环境 | 说明 |
| --- | --- |
| Java Development Kit（JDK）21、Maven 3.9.x | 后端构建和运行；Android 工程自带 Gradle Wrapper |
| Android Studio、Android SDK Platform 35、ADB | App 构建、安装与设备调试；最低支持 Android 14／API 34 |
| Node.js 18+、npm；建议 22+ | Web要求18+；可选WebView结果检查脚本使用22+内置能力 |
| Python 3 | 本机 Web 启动、LAN 构建及设备脚本 |
| MySQL 命令行客户端、curl | 执行初始化、检查服务 |
| OpenSSL 1.1.1+ | 本机联调生成开发证书；已有可信 HTTPS 时不必自签 |
| FFmpeg，可选 | 启用参考跨会议声纹时，在后端提取录音片段 |

本机开发检查直接使用这些工具。后端 Docker 镜像是另一条交付部署路径，见第 6 节；
没有 MySQL 实例时可自行使用临时测试容器，数据库容器不属于本工程交付物。

### 3.3 两个本地配置文件（都不在仓库里）

部署地址不进仓库，由本地文件提供；Android 和 Web 各一份，都已被 `.gitignore` 忽略。
缺少时**构建直接失败**，不会产出连不上后端的包或页面。

| 文件 | 从哪来 | 作用 | 缺了会怎样 |
| --- | --- | --- | --- |
| `android-reference-app/deployment.local.properties` | 复制同目录的 `.example` | APK 的后端地址与结果页地址，构建时注入 `BuildConfig` | `./gradlew` 直接失败，提示填 `backendUrl`／`resultWebUrl` |
| `web-reference/deployment.local.json` | 复制同目录的 `.example` | 结果页的后端地址，构建时生成 `dist/config.json` | `npm run build` 直接失败，提示填 `apiBaseUrl` |

要区分测试与生产，就多留几份文件，用环境变量选择：

```bash
REFERENCE_DEPLOYMENT_FILE=deployment.prod.properties ./gradlew :app:assembleDebug
REFERENCE_WEB_DEPLOYMENT_FILE=deployment.prod.json npm --prefix web-reference run build
```

换机器、重新 clone、清理工作区后都要重新建一遍；内容就是各自那一两个字段，不会随仓库走。

## 4. 按步骤完成第一场真实会议

下面所有相对路径均以仓库根目录为起点，另有说明的命令除外。
先只启用中文／英语字幕、文件结果存储和示例登录，避免同时排查多个外部服务。

### 第 1 步：建立本机配置

```bash
cd czur-ai-meeting-reference
cp tools/reference-client.env.example tools/reference-client.env
```

编辑新文件，而非 `.example` 模板。新文件已被 Git 忽略。该模板默认使用
`sample-json-ws` 协议样例，其地址没有随工程启动真实模型。使用听悟时，必须将
下列设置改为自己的值，保留模板中的其他必需配置：

```dotenv
DATABASE_URL='jdbc:mysql://127.0.0.1:3306/meeting_reference?useSSL=false&allowPublicKeyRetrieval=true'
DATABASE_USER=replace-with-database-user
DATABASE_PASSWORD=replace-with-database-password

REFERENCE_DEV_IDENTITY_ENABLED=true
REFERENCE_DEV_LOGIN_USERNAME=meeting-user
REFERENCE_DEV_LOGIN_PASSWORD=replace-with-login-password
REFERENCE_DEV_IDENTITY_BEARER_TOKEN=replace-with-generated-session-token
AI_MEETING_PAGE_TOKEN_SECRET=replace-with-generated-page-secret

AI_MEETING_CREATE_ENABLED=true
AI_MEETING_PROFILE_REQUIRED_CAPABILITIES=transcript.realtime
AI_MEETING_ENTITLEMENT_SUPPORTED_CAPABILITIES=transcript.realtime
AI_MEETING_MODEL_CAPABILITIES=transcript.realtime
AI_MEETING_MODEL_LANGUAGES=zh-CN,en-US

AI_MEETING_MODEL_ADAPTER=tingwu-realtime
AI_MEETING_TINGWU_ENABLED=true
AI_MEETING_TINGWU_APP_KEY=replace-with-tingwu-app-key
AI_MEETING_TINGWU_ACCESS_KEY_ID=replace-with-access-key-id
AI_MEETING_TINGWU_ACCESS_KEY_SECRET=replace-with-access-key-secret

AI_MEETING_ARTIFACT_STORE=file
AI_MEETING_ARTIFACT_ROOT=.local/reference-artifacts
AI_MEETING_SPEAKER_IDENTITY_PROVIDER=none
```

将同名项修改为唯一有效赋值，避免在文件后部留下旧值覆盖新值。首次不用设置
`AI_MEETING_PROFILE_OPTIONAL_CAPABILITIES`，也不用开启总结。
本地文件由 Bash 加载，使用 shell 兼容的 `KEY=VALUE` 格式；密码等值含空格、`$`
或其他特殊字符时应正确加引号，建议使用单引号包裹。

还需检查：

- 会话 Token 和分页 Secret 分别生成至少 32 个 UTF-8 字节的随机值，可各运行一次 `openssl rand -hex 32`；示例占位值不能用于登录。
- 将 `REFERENCE_DEV_IDENTITY_ISSUED_AT`／`REFERENCE_DEV_IDENTITY_EXPIRES_AT` 改为覆盖当前部署时刻的 UTC 时间，分别表示签发与过期时间；不要长期照抄模板年份。
- `AI_MEETING_PROFILE_ID=reference-asr` 和 `AI_MEETING_PROFILE_REVISION=1` 与当前参考 App 匹配，首次接入保留。
- 初次使用模板的 `0.0.0.0:8080` HTTP 监听；MySQL 的 `127.0.0.1` 仅表示数据库就在开发电脑上。
- 模型永久凭据只填本机后端配置，不写入 Android 部署文件、APK、Web 配置或 Git。

配置在后端启动时读取。修改后需要重启该后端进程，并在下一场会议验证。

### 第 2 步：准备数据库并执行唯一初始化脚本

由数据库管理员先创建专用数据库及账号，例如数据库名 `meeting_reference`，
编码使用 `utf8mb4`；确认第 1 步连接信息与之对应。

```bash
bash tools/migrate-reference-client.sh
```

脚本执行 [`V001__initial_schema.sql`](../deploy/migrations/V001__initial_schema.sql)，
创建 `ai_trans_*` 专用表并登记初始化。它不创建数据库或账号，不迁移其他业务表。
应用启动只检查初始化结果，不自动建表。首次接入无需手工寻找其他版本脚本。

### 第 3 步：启动后端并确认健康

终端 A，从仓库根目录运行：

```bash
bash tools/run-reference-client-backend.sh
```

该脚本构建并启动客户端直连模型的后端入口。保持此终端运行。终端 B 检查：

```bash
bash tools/check-reference-backend.sh
curl --fail http://127.0.0.1:8080/v1/capabilities
```

预期：存活和数据库就绪检查均通过，能力接口返回当前部署的能力与语言。
这一步确认后端与数据库启动成功，真实模型调用在设备会议中另行确认。

### 第 4 步：准备设备可达的 HTTPS 和 Web

先确定开发电脑的局域网 IP。以下 `192.0.2.10` 是文档占位地址，执行前换成实际值。

1. 依照[本机局域网 HTTPS 指南](local-lan-https.md#1-在仓外生成本机-ca-和-ip-san-证书)第 1、2 节生成开发 CA／服务端证书并启动 TLS 入口：设备访问 `https://<PC_LAN_IP>:8443`，上游转到本机 `http://127.0.0.1:8080`。若已有可信域名／HTTPS 代理，可直接使用。
2. 另开终端 C，启动会后 Web：

```bash
REFERENCE_HTTP_BASE_URL='http://192.0.2.10:8080' \
REFERENCE_WEB_BIND=0.0.0.0 REFERENCE_WEB_PORT=8090 \
bash tools/run-reference-web.sh
```

这里 Web 的 API 指向设备浏览器能访问的 PC 地址；App 的后端登录地址仍使用 HTTPS。
局域网结果页可使用 HTTP，避免浏览器额外信任开发 CA；正式 HTTPS 结果页须配 HTTPS API。
同时确认电脑防火墙和无线网络允许设备访问所用端口。

| 地址 | 谁访问它 | 本机联调示例 |
| --- | --- | --- |
| 数据库 JDBC 地址 | 后端进程 | `jdbc:mysql://127.0.0.1:3306/meeting_reference` |
| HTTP 上游 | PC 上的 TLS 入口、健康检查 | `http://127.0.0.1:8080` |
| App 后端地址 | Android SDK／登录 | `https://<PC_LAN_IP>:8443` |
| 结果页地址 | 设备 WebView／浏览器 | `http://<PC_LAN_IP>:8090/index.html` |
| Web 的 API 地址 | 设备 WebView／浏览器 | `http://<PC_LAN_IP>:8080` |

设备自己的 `127.0.0.1` 指设备自身。模型 WebSocket 地址同样需要设备可达。
完整的局域网测试不依赖 `adb reverse`。

### 第 5 步：构建并安装真实 SDK 版本 App

设备 AAR 已随参考仓放在 `meeting-android/libs/ai-meeting-device.aar`；业务 AAR 由本仓
`meeting-android` 自行构建，构建不需要任何参数。

先给地址：复制模板并填写（已被 Git 忽略），构建时注入 APK：

```bash
cd android-reference-app
cp deployment.local.properties.example deployment.local.properties
# 编辑 deployment.local.properties：
#   backendUrl=https://<PC_LAN_IP>:8443
#   resultWebUrl=http://<PC_LAN_IP>:8090/index.html
```

Debug 联调还要让 APK 信任步骤 4 生成的开发 CA：把 PEM 命名为 `reference_debug_ca.pem`
放到 `android-reference-app/app/src/debug/res/raw/`（已被 Git 忽略，
生成方式见 [本机局域网 HTTPS 指南](local-lan-https.md)）；不放到时会只信任系统 CA。
没建这份配置文件时 `./gradlew` 会直接失败，提示缺 `backendUrl`／`resultWebUrl`——不会
产出一个指向作者本机地址的包。测试／生产各留一份，用 `REFERENCE_DEPLOYMENT_FILE` 切换。

再构建并安装：

```bash
cd android-reference-app
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

地址在打包时确定，终端用户没有手填 IP 的步骤。App 保存当前后端的有效登录；
保留数据覆盖安装或重开后可恢复登录，退出会清除会话，更换后端的 APK 不复用旧身份。
### 第 6 步：登录、开会、停止并看结果

1. 在设备上打开参考 App，使用第 1 步设置的参考用户名／密码登录。
2. 按系统提示授予录音权限，开启悬浮窗权限；演示截图未开启时无需截图授权。
3. 选择 AI 字幕纪要和已声明语言，使用默认 Web 结果动作；首次暂不开启总结或演示。
4. 点击开始，会前设置关闭并显示跨应用字幕悬浮窗；对麦克风说话，确认真实字幕。
   打开另一个应用，确认字幕仍持续、窗口外可操作；可收起为 Mini 后恢复。
5. 点击正常停止，确认悬浮窗退出、音源释放；结果生成完成后打开 Web。
6. 核对这场会议的最终原文；SDK 默认保留录音时，还应能播放并拖动录音。
7. 返回 App 再开一场，确认可以正常开始。重开 App，确认有效登录恢复。

步骤完成后，您拥有自己的真实字幕闭环。结果就绪 `READY` 表示本场标准产物已发布；
没有启用的可选能力不会生成空结果区。不要通过杀进程或清数据代替正常停止。

自动启动、正常停止和 Web 检查可使用[现有脚本](../tools/android-device/README.md#日常会议流程)，
脚本走原有 App 入口，不需要修改会议核心。

## 5. 在字幕闭环上逐项扩展

每次只增加一项配置／实现，重启后端，重新获取能力并开新会议验收。

| 下一项 | 准备与配置 | 应看到的结果 |
| --- | --- | --- |
| 双语互译 | 声明翻译与所需语言，确认所选模型开通翻译；也可装配独立翻译模型 | App 原文／译文；Web 实际译文 |
| AI 纪要 | 准备百炼应用与 Key，或自己的总结 Adapter／发行器 | 会前可选总结；会后标题、概述、要点、待办 |
| 本场分人 | 模型支持并声明本场发言人能力 | 本场发言人编号／分组；不等于跨场认识某个人 |
| 跨会议人物身份 | 讯飞声纹资源或自定义 Provider、本场分人、留存录音及 FFmpeg | Web 人物信息、登录后命名注册、后续会议识别 |
| 演示截图 | SDK 版、系统截图能力或标准屏幕捕获授权；选择演示模式 | 变化页面截图及 Web 演示区；参考 App 的互译与演示不同时选择 |
| 替换账号／权益／存储／结果渠道 | 在对应装配点实现插件 | 使用自己的业务系统，会议公共流程保持共用 |

完整配置组合和“缺少这项会怎样”见[模型与能力指南](model-and-capability-guide.md)。
截图及其可选去重在 SDK 内部运行，不需要另申请图像模型；周期、末帧、排除窗口与
保存确认接线见[截图扩展](partner-integration-guide.md#sdk-截图扩展)。

## 6. Docker 交付部署

本机短会跑通后，可用交付镜像部署相同参考后端。部署方需要 Docker／Compose、
外部 MySQL、持久化结果目录或对象存储、HTTPS 代理／证书，以及静态 Web 服务器。
不要求使用 CZUR 的微服务、配置中心或部署平台。

从仓库根目录执行：

```bash
cp .env.example deploy/.env
# 编辑 deploy/.env，再执行：
cd deploy
bash migrate.sh --env-file .env
bash healthcheck-db.sh --env-file .env
docker compose up --build -d
```

`deploy/.env` 是本部署的环境配置文件。Dockerfile 构建代码，Compose 的 `env_file`
在运行时注入该文件，不把真实 Key 烘焙进镜像。注意根模板默认关闭身份和建会，
需要按第 4 节明确填写／开启登录、Token、分页 Secret、有效期、建会、模型与能力。
仅复制模板不能得到已配置完成的真实模型服务。

Compose 将容器 `8080` 映射到宿主 `API_PORT`（默认 `8080`），并为文件产物挂载
持久卷。容器内的数据库地址须能访问外部 MySQL，容器 `127.0.0.1` 不代表宿主机。
同一 `.env` 还需供宿主初始化使用，宜采用双方都可达的数据库地址。

Web 单独构建，将 `web-reference/dist/` 放到静态站点，并配置 `config.json`：

```bash
cd ../web-reference
npm run build
cp config.example.json dist/config.json
```

编辑 `dist/config.json` 的 `apiBaseUrl` 为浏览器可达的 API；正式 HTTPS 站点使用
HTTPS API，配置相应跨域读取或同源路由。再以正式后端和结果站点地址重建 App。

`npm run build` 每次都会重建 `dist/`，手工拷进去的 `config.json` 会被清掉。本机反复构建时，
可以在 `web-reference/config.local.json`（已被忽略）里写 `{"apiBaseUrl":"..."}`，或用环境变量：

```bash
REFERENCE_API_BASE_URL='https://your-backend:8443' npm run build
```

两者都没有时构建照旧不生成 `config.json`，保持"产物不带地址、由部署方注入"的交付约定。
生产 Release 使用部署方正式证书，不携带本机 Debug CA。完整说明见[部署文档](../deploy/README.md)。

数据库交付只有一份 MySQL V001；仓库不交付 MySQL 镜像／数据卷，也不提供 Web 镜像。

## 7. 合作伙伴通常改哪些地方

| 想替换什么 | 扩展接缝 | 本仓提供的基础示例与边界 |
| --- | --- | --- |
| App 登录和登录保持 | `IdentityProvider`、`AppLoginEntry`，在 `MeetingAppAssembly` 注册 | 单账号表单、会话保存／恢复、切换／退出；没有注册和找回密码平台 |
| 后端鉴权 | 后端 `IdentityProvider` | 验证 Bearer 后生成已验证身份；对接伙伴自己的登录系统 |
| 开会资格、套餐／扣费 | `EntitlementPolicy` 和用量结算接缝 | 固定权益／时长示例及实际会议用量事实；不连接支付渠道或预设伙伴价格 |
| 存储 | `ArtifactStore` | 本地 file 与 S3 兼容示例；数据库另存元数据；用户空间配额由伙伴业务决定 |
| 实时／翻译／总结模型 | 后端材料发行器＋Android 对应 Adapter | 归一化原文、译文、总结；不在 App 页面中写厂商 JSON 分支 |
| 跨会议声纹 | `SpeakerIdentityProvider` | 可选讯飞插件和人员映射，未启用时其余链正常 |
| 结果交付／页面 | `ResultActionPlugin`、Manifest／Artifact 渲染 | 默认 Web；本地离线 HTML、邮件、小程序等由伙伴开发／装配 |
| 开会前检查 | App 会前 Hook | 可注入自有条件；参考代码不提供扫码接收端／小程序 |
| 悬浮窗／字幕 UI | SDK UI 工具及 App 页面／窗口工厂 | 基础能力与默认实现，伙伴可以按产品替换窗口形态 |

具体接口、线程与生命周期见[扩展开发手册](partner-integration-guide.md)、
[Android 说明](../android-reference-app/README.md)、[公共核心说明](../backend-core/README.md)。
SDK 与服务端核心应使用配套版本；复用公共核心 JAR，不依赖整个参考应用或复制状态机。

## 8. 首次接入的验收清单和常见问题

最小验收：后端／数据库就绪 → 正常账号登录 → 获取真实能力和语言 → 实体麦克风字幕
→ 正常停止 → 同场 Web 原文／可用录音 → 停后再开 → 重开登录保持。

| 现象 | 优先核对 |
| --- | --- |
| App 提示联系应用提供方 | APK 是否注入后端和结果页地址；修改文件后是否重新构建 |
| 登录失败／不能连接 | 设备可达的 HTTPS、证书主机名、账号配置、会话有效期；设备 `127.0.0.1` 的含义 |
| 后端启动或就绪失败 | MySQL 连接／账号、唯一 V001、身份启用、分页 Secret、模型发行器配置 |
| 没有互译、总结或人物入口 | 实现是否装配，能力是否在相应配置中声明；按实际能力隐藏是正常行为 |
| 有界面却没有真实字幕 | APK 是否 `sdk` 版本、录音授权、模型资源和连接；协议样例不提供真实识别 |
| 总结区为空／隐藏 | 是否配置并请求总结，最终 Manifest 是否确有总结产物；无模型时不会补空总结 |
| Web 不能读取结果 | Web 的 `apiBaseUrl` 是否设备可达、跨域／协议配置、链接是否仍在有效期 |
| 只有本场编号，没有跨会议姓名 | 本场分人与跨场识别分别配置；还需要录音、声纹 Provider及已注册人物 |

问题定位时可提供构建版本、所选能力、失败步骤和脱敏错误信息；请勿提交永久 Key、
登录令牌、私钥或真实会议内容。真实本机配置留在已忽略文件中。

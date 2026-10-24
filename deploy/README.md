# 参考后端部署

参考后端的本机局域网入口是 `ReferenceClientMeetingMain`。它只提供 HTTP 业务服务，
Android 通过服务端签发的临时材料直连已配置的模型 Adapter；实时模型音频不经过后端，
会后封口录音按本场留存策略上传到后端产物 Store，供统一结果回看。
镜像和 Compose 只编排这个应用进程，不携带数据库容器。

以下命令从本仓库的 `deploy/` 目录执行。宿主机准备 Docker／Compose 和 MySQL 客户端；
本机开发的 HTTPS 入口和静态 Web 工具另需 Node.js、npm、Python 3、OpenSSL。

## 数据库

参考方案统一使用 MySQL 5.7 兼容模式。部署方先创建一个专用数据库，再执行仓库唯一
初始化脚本：

```bash
export DATABASE_URL='jdbc:mysql://127.0.0.1:3306/meeting_reference?useSSL=false&allowPublicKeyRetrieval=true'
export DATABASE_USER='meeting'
export DATABASE_PASSWORD='从部署方 Secret 注入'
bash ./migrate.sh
bash ./healthcheck-db.sh
```

脚本只新增 `ai_trans_*` 会议专用表，不创建数据库／账号，不读写旧业务表。应用启动
只做只读 readiness；未登记 V001 时启动失败。正式部署使用部署方已有 MySQL 实例，
不要求安装本项目提供的数据库镜像。

本机可以临时运行 `mysql:5.7` 容器来验证脚本和局域网链，但容器、数据卷和账号均不
属于参考代码交付物。容器只绑定 loopback，并使用随机名称／测试数据库；测试结束后由
操作者清理该临时依赖。不要把现有 CZUR 业务库或其他测试实例填入模板。

## 应用配置和启动

复制根目录占位配置后，仅在本机填写：

```bash
cp ../.env.example .env
# 至少填写 DATABASE_URL、DATABASE_USER、DATABASE_PASSWORD、身份、分页密钥、
# REFERENCE_DEV_IDENTITY_ENABLED=true、登录用户名／密码、
# AI_MEETING_CREATE_ENABLED=true 和实际可达的模型 Adapter 端点；不要提交 .env。
bash ./migrate.sh --env-file .env
bash ./healthcheck-db.sh --env-file .env
docker compose up --build
```

`--env-file` 显式加载部署者维护的 shell 兼容 `KEY=VALUE` 文件，支持引号和注释；
初始化、健康检查与 Compose 使用相同的配置，值含空格或 shell 特殊字符时应加引号。
无参数运行脚本仍可使用前一节的 `export` 方式，不会自动加载其他目录的 `.env`。
默认示例账号需填写根模板的 `REFERENCE_DEV_LOGIN_USERNAME/PASSWORD`；ASR 模型可
按[伙伴手册](../docs/partner-integration-guide.md#模型配置)选择听悟发行器并填写其模型配置，
或选择 `sample-json-ws` 并提供实际存在的 WebSocket 服务。这个模型端点从 Android
设备直连，必须能被设备访问，不能填写应用容器自己的 `127.0.0.1`。

不使用容器做开发检查时，直接运行：

```bash
bash ../tools/migrate-reference-client.sh
bash ../tools/run-reference-client-backend.sh
```

Compose 中的 `DATABASE_URL` 必须从应用容器可达；宿主机初始化也需要访问该数据库。
建议填写两端都能访问的数据库 DNS／地址，不能把容器内的 `127.0.0.1` 当作宿主机
或数据库容器。仅绑定宿主 loopback 的临时 MySQL 是本机 JVM 联调依赖，不能直接
套用为独立容器的数据库地址。数据库连接／网络策略由部署方按自己的外部 MySQL 配置。

## Android HTTPS 与静态 Web

容器内部 `http://...:8080` 是业务上游，设备的登录／后端入口必须使用 HTTPS。
正式部署由部署方已有反向代理／域名证书将 HTTPS 转到映射端口。开发 PC 没有 HTTPS
入口时，按[本机局域网 HTTPS 联调](../docs/local-lan-https.md)生成仓外证书、
启动现有 Node TLS 入口并构建信任该公开 CA 的 Debug APK；上游使用
`http://127.0.0.1:<API_PORT>`，App 后端构建地址为 `https://<PC_LAN_IP>:8443`。
部署地址由开发者在 PC 上配置并注入 APK，终端用户只输入账号／密码。不要把设备自己的
`127.0.0.1` 或上游 HTTP 地址当作 LAN 登录入口。

静态 Web 源码随仓库交付，部署方可放在自己的静态站点。本机预览可另开终端执行：

```bash
# 将 192.0.2.10 换成设备可达的开发 PC 局域网 IP。
REFERENCE_HTTP_BASE_URL='http://192.0.2.10:8080' \
REFERENCE_WEB_BIND=0.0.0.0 REFERENCE_WEB_PORT=8090 \
bash ../tools/run-reference-web.sh
```

App Web 结果构建地址使用 `http://<PC_LAN_IP>:8090/index.html`；Web 的
`config.json` 中 `apiBaseUrl` 必须从设备浏览器可达。`npm run build` 会清空 `dist/`，
本机可用 `web-reference/config.local.json`（已忽略）或 `REFERENCE_API_BASE_URL` 让构建直接
生成该文件；两者都没有时不生成，由部署方注入。Web 使用开发 HTTPS 上游时，
浏览器也要信任该 CA，Debug APK 的信任配置不会替浏览器安装证书。生产 HTTPS 页面
应使用同样可达的 HTTPS API，避免混合内容。仓库只交付后端应用镜像，不新增 Web
或数据库镜像。

Android 的部署参数在 `android-reference-app/app/src/main/java/com/czur/aimeeting/reference/meeting/ReferenceDeployment.kt`
里（代码即配置）：`BACKEND_URL` 填设备可达的 HTTPS 后端，`RESULT_WEB_URL` 填结果页地址。
仓库只保留占位值，不改也能构建，但 APK 会指向占位域名。Debug 联调的自签 CA 放在
`app/src/debug/res/raw/reference_debug_ca.pem`（已被 Git 忽略）。
完整命令见 [Android 构建／启动工具](../tools/android-device/README.md)。

## 本机常驻运行（可选）

反复重启上面三个服务、或希望它们脱离终端存活时，可以用 systemd 用户单元托管。
下面三条命令只引用仓外／已忽略的配置文件路径，命令里不出现凭据值。

```bash
REPO="$(git rev-parse --show-toplevel)"
ENV_FILE="$HOME/.config/ai-meeting/reference-client.env"   # 放仓外，按 tools/reference-client.env.example 填写

# 1. 业务后端（端口取自配置文件的 AI_MEETING_HTTP_BIND，模板默认 8080）
systemd-run --user --unit=ai-meeting-reference-backend \
  --property=WorkingDirectory="$REPO" \
  --property=Restart=on-failure --property=RestartSec=5s \
  --property=CollectMode=inactive-or-failed \
  --setenv=REFERENCE_ENV_FILE="$ENV_FILE" \
  /bin/bash -c 'exec ./tools/run-reference-client-backend.sh'

# 2. 局域网 HTTPS 入口（8443）：证书仍是仓外文件，见 local-lan-https；
#    下面的 BIND 是文档占位地址，换成开发机局域网 IP
systemd-run --user --unit=ai-meeting-reference-https \
  --property=WorkingDirectory="$REPO" \
  --property=Restart=on-failure --property=RestartSec=5s \
  --property=CollectMode=inactive-or-failed \
  --setenv=REFERENCE_TLS_CERT="$HOME/certs/server.pem" \
  --setenv=REFERENCE_TLS_KEY="$HOME/certs/server.key" \
  --setenv=REFERENCE_TLS_BIND=192.0.2.10 --setenv=REFERENCE_TLS_PORT=8443 \
  --setenv=REFERENCE_TLS_UPSTREAM=http://127.0.0.1:8080 \
  "$(command -v node)" tools/run-reference-lan-https.mjs

# 3. 静态 Web（8090）：先用 npm --prefix web-reference run build 构建 dist
systemd-run --user --unit=ai-meeting-reference-web \
  --property=WorkingDirectory="$REPO" \
  --property=Restart=on-failure --property=RestartSec=5s \
  --property=CollectMode=inactive-or-failed \
  "$(command -v python3)" -m http.server 8090 --bind 0.0.0.0 --directory web-reference/dist
```

- 配置文件放仓外（例如 `~/.config/ai-meeting/`）：`tools/check-public-boundary.py` 按工作区
  扫描而不是按 git 跟踪扫描，放在仓库里即使被 `.gitignore` 忽略也会判失败。
  `run-reference-client-backend.sh` 用 `REFERENCE_ENV_FILE` 指向仓外路径即可。
- 后端端口以配置文件里的 `AI_MEETING_HTTP_BIND` 为准（模板默认 `0.0.0.0:8080`），
  必须和 `REFERENCE_TLS_UPSTREAM` 指向的端口一致，否则 HTTPS 入口会 502。
- `--user` 单元跟随登录会话；希望退出登录后仍在，执行一次
  `loginctl enable-linger "$(id -un)"`。
- 这些是瞬态单元（`/run/user/<uid>/systemd/transient/`），**机器重启后不会自动恢复**，
  重跑上面三步即可。
- 启停和日志：`systemctl --user restart ai-meeting-reference-backend`、
  `journalctl --user -u ai-meeting-reference-backend -f`；需要落文件时追加
  `--property=StandardOutput=append:/path/to/backend.log`
  `--property=StandardError=append:/path/to/backend.log`。
- `tools/run-reference-client-backend.sh` 默认每次启动都跑一遍 Maven 构建；已构建过可加
  `--setenv=SKIP_BUILD=1` 跳过，前提是 `backend-core` 已 install 到本机 `~/.m2`，
  否则 `main` 会 `NoClassDefFoundError`。
- 写 `bash -c '...'` 时不要在里面用 `${VAR}`：systemd 在 exec 前会替换 ExecStart
  参数里的 `${...}`，变量名对不上就被替换成空串。传值用 `--setenv`，命令里只留
  不带花括号的 `$VAR` 或相对路径。

## 模型、身份和结果

`REFERENCE_DEV_*` 仅是单账号示例登录；合作伙伴可替换 Identity／Login Provider，
保存登录状态的逻辑在 App 装配层，不进入会议状态机。实时模型、翻译、总结和声纹
按部署声明与已装配 Provider 开放。语言列表由 `AI_MEETING_MODEL_LANGUAGES` 直接
声明，省略时为 `zh-CN,en-US`；当前不自动求交 Provider／Profile 的语言列表，部署者
须核对模型与 Adapter 实际支持范围。真实厂商 Key 只留在后端本机或外部 Secret 注入，
不写入模板、日志或仓库。

运行镜像已提供 `ffmpeg`，启用[可选声纹插件](../backend-reference/README.md#可选跨会议声纹参考插件)
后可直接提取样本；不启用时不会执行它。最长会议由 `AI_MEETING_MAX_MEETING_DURATION_MS`
声明，HTTP 录音上传与 file／S3 Store 统一按 `最长毫秒数 × 32 + 1 MiB` 计算预算
（下限为 64 MiB），足以覆盖标准 PCM 回退；该预算不改变 JSON 限制或计费用量。
外部反向代理如设置请求体限制，也应按该预算同步配置。

默认结果展示是 Web；本地 HTML、邮件、扫码／小程序等通过结果动作或渲染器扩展，
不复制会议状态机。截图采集／可选处理的接口与接入见 Android／SDK 手册，
演示多图、末帧及 Web／PDF 对照须按[当前支持范围与验收清单](../docs/validation-scope.md)
在目标设备上核对，不用部署成功代替设备验收。

## 镜像边界

根目录 `Dockerfile` 只构建参考应用镜像。数据库初始化脚本、外部 MySQL 实例和
测试容器由部署方负责；本仓不发布 MySQL 镜像或数据库数据卷。

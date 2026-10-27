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

```sql
-- 只建库；最小权限账号的 DDL 见本节末尾
CREATE DATABASE meeting_reference CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
```

```bash
export DATABASE_URL='jdbc:mysql://127.0.0.1:3306/meeting_reference?useSSL=false&allowPublicKeyRetrieval=true'
export DATABASE_USER='meeting'
export DATABASE_PASSWORD='从部署方 Secret 注入'
bash ./migrate.sh
bash ./healthcheck-db.sh
```

`DATABASE_URL` 必须整段带单引号：值里的 `?` 和 `&` 不加引号会被 shell 当成通配符和
后台运算符，命令会被截断或直接报错。写进 `deploy/.env` 或仓外运行配置时同理。

脚本只新增 `ai_trans_*` 会议专用表，不创建数据库／账号，不读写旧业务表。
开发期图省事可以用一个高权限账号（例如本机 root）一次建库、建号并执行 V001；
**交付和生产的口径是分账号、最小权限**：迁移账号只要一次性 DDL，应用账号只保留运行期
DML，不要复用旧业务库，也不要把 root 凭据写进 `.env` 或仓外运行配置：

```sql
CREATE USER 'meeting_migrator'@'%' IDENTIFIED BY '<迁移密码>';
GRANT CREATE, ALTER, INDEX, REFERENCES, SELECT, INSERT, UPDATE, DELETE
  ON meeting_reference.* TO 'meeting_migrator'@'%';
CREATE USER 'meeting_app'@'%' IDENTIFIED BY '<应用密码>';
GRANT SELECT, INSERT, UPDATE, DELETE ON meeting_reference.* TO 'meeting_app'@'%';
```

应用启动
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
# 运行配置放仓外，默认 ~/.config/ai-meeting/reference-client.env；
# 换路径时先 export REFERENCE_ENV_FILE=<仓外路径>。
bash ../tools/migrate-reference-client.sh
bash ../tools/run-reference-client-backend.sh
```

这两个脚本读同一份仓外 `KEY=VALUE` 配置，不读 `deploy/.env`，也不自动加载仓库内
其他文件。不要把这份配置改放回 `tools/`：`tools/check-public-boundary.py` 按工作区
扫描文件内容，仓库里填好密码的 `*.env` 会让交付检查判失败。

Compose 中的 `DATABASE_URL` 必须从应用容器可达；宿主机初始化也需要访问该数据库。
建议填写两端都能访问的数据库 DNS／地址，不能把容器内的 `127.0.0.1` 当作宿主机
或数据库容器。仅绑定宿主 loopback 的临时 MySQL 是本机 JVM 联调依赖，不能直接
套用为独立容器的数据库地址。数据库连接／网络策略由部署方按自己的外部 MySQL 配置。

## 改端口和地址要同步哪些地方

后端端口不是一处配置。改动前先确认下面五处指向同一个端口，否则健康检查和 HTTPS
入口会稳定 502，设备登录也会连到没人监听的地址：

| 位置 | 配置项 | 默认值 | 改哪里 |
| --- | --- | --- | --- |
| 后端监听 | `AI_MEETING_HTTP_BIND` | `0.0.0.0:8080` | 仓外运行配置；容器内由 `deploy/docker-compose.yml` 固定为 `0.0.0.0:8080` |
| 本机健康检查 | `REFERENCE_HTTP_BASE_URL` | `http://127.0.0.1:8080` | `tools/check-reference-backend.sh` 读取的环境变量，不改脚本 |
| HTTPS 入口上游 | `REFERENCE_TLS_UPSTREAM` | `http://127.0.0.1:8080` | 启动 `tools/run-reference-lan-https.mjs` 时的环境变量 |
| Web 的 API 地址 | `REFERENCE_HTTP_BASE_URL` | 同健康检查 | `tools/run-reference-web.sh` 用它生成 `dist/config.json` 的 `apiBaseUrl` |
| App 的后端地址 | `backendUrl` | 无默认值 | `android-reference-app/deployment.local.properties`，改后必须重建 APK |

容器部署时宿主端口另由 `deploy/.env` 的 `API_PORT` 映射到容器 `8080`；此时只改
`API_PORT` 和上面四处引用宿主端口的值，不要改 Compose 里的容器内监听。
HTTPS 入口自身端口（默认 `8443`）不在这个清单里，见下节的 `REFERENCE_TLS_PORT`。

## Android HTTPS 与静态 Web

容器内部 `http://...:8080` 是业务上游，设备的登录／后端入口必须使用 HTTPS。
正式部署由部署方已有反向代理／域名证书将 HTTPS 转到映射端口。开发 PC 没有 HTTPS
入口时，按[本机局域网 HTTPS 联调](../docs/local-lan-https.md)生成仓外证书、
启动现有 Node TLS 入口并构建信任该公开 CA 的 Debug APK；上游使用
`http://127.0.0.1:<API_PORT>`，App 后端构建地址为 `https://<PC_LAN_IP>:8443`。
`8443` 被其他服务占用时，用 `REFERENCE_TLS_PORT` 换一个端口（例如 `18443`），
同时把 App 的 `backendUrl` 改成 `https://<PC_LAN_IP>:18443` 并重建 APK；证书 SAN
里写的是 IP，与端口无关，不用重签。
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
`config.json` 中 `apiBaseUrl` 必须从设备浏览器可达。参考构建默认允许明文 HTTP，方便局域网联调；是否收紧由部署方决定——正式交付建议去掉 manifest 里的 `usesCleartextTraffic` 并改用 HTTPS 结果页。它不再手工注入：`npm run build` 会读
`web-reference/deployment.local.json`（已忽略）并在 `dist/` 里生成，缺配置则构建直接失败；
测试／生产用 `REFERENCE_WEB_DEPLOYMENT_FILE` 切换。Web 使用开发 HTTPS 上游时，
浏览器也要信任该 CA，Debug APK 的信任配置不会替浏览器安装证书。生产 HTTPS 页面
应使用同样可达的 HTTPS API，避免混合内容。仓库只交付后端应用镜像，不新增 Web
或数据库镜像。

手工打开结果页时必须同时带会议 ID 和链接生成时间：

```text
http://<PC_LAN_IP>:8090/index.html?meetingId=<mtg_...>&genTime=<毫秒时间戳>
```

`meetingId` 是 Create 响应里的 `mtg_...`，`genTime` 是生成这条链接时的 Unix 毫秒
（`date +%s%3N`），服务端按部署配置的 `AI_MEETING_RESULT_LINK_TTL_MS` 判断过期；
`genTime=-1` 表示永不过期，只用于本机诊断。只给 `meetingId` 时页面拿不到有效期上下文，
会直接报错；链接过期后页面显示结果不可读，重新取一次 `genTime` 即可。

Android 的部署参数在 `android-reference-app/deployment.local.properties`
（已被 Git 忽略，模板为同目录 `.example`）：`backendUrl` 填设备可达的 HTTPS 后端，
`resultWebUrl` 填结果页地址，构建时注入；缺配置时 `./gradlew` 直接失败。
Debug 联调的自签 CA 放在 `app/src/debug/res/raw/reference_debug_ca.pem`（已被 Git 忽略）。
完整命令见 [Android 构建／启动工具](../tools/android-device/README.md)。

## 同机跑多份部署

构建产物目录是按仓库路径固定的，不是按配置隔离的：`web-reference/dist/`、
`web-reference/dist-check/`、`backend-core/target/` 和 `backend-reference/target/`
都被同一份 clone 的所有配置共用。

- `tools/run-reference-web.sh` 每次启动都会先 `npm run build`，并把当前的
  `REFERENCE_HTTP_BASE_URL` 重新写进 `web-reference/dist/config.json`。也就是说，
  在同一份 clone 上再起一个 8091，会把已经跑着的 8090／8091 实例正在提供的
  `config.json` 一起改掉，旧实例的页面会悄悄换成新地址。要并行对比两份部署，
  每份用独立的 clone／`git worktree`，各自构建自己的 `dist/`。
- `run-reference-client-backend.sh` 不加 `SKIP_BUILD=1` 时会先 `mvn install`，
  写的是同一份 `target/`；两个进程同时构建会互相覆盖 JAR 和 `runtime-libs/`，
  启动的可能是对方刚构建出来的产物。并行部署时用独立 clone，或先分别构建再
  以 `SKIP_BUILD=1` 启动。
- 部署配置、证书、产物目录和数据库仍各自独立；不要用同一份仓外配置同时跑两个
  监听地址不同的后端，端口与地址会互相覆盖。

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
  `--setenv=SKIP_BUILD=1` 跳过，前提是匹配的后端 JAR
  （`backend-reference/target/backend-reference-0.1.0-SNAPSHOT.jar`）和完整运行依赖目录
  （`backend-reference/target/runtime-libs/`）已经构建好。仅执行过 `mvn install` 不够：
  `runtime-libs/` 由单独的 `dependency:copy-dependencies -DincludeScope=runtime` 生成；
  脚本用 `java -cp '<JAR>:backend-reference/target/runtime-libs/*'` 组装 classpath，
  不从 `~/.m2` 读取，缺运行依赖时 `main` 会 `NoClassDefFoundError`。
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

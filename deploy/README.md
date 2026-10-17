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
`http://127.0.0.1:<API_PORT>`，设备连接 `https://<PC_LAN_IP>:8443`。
不要把设备自己的 `127.0.0.1` 或上游 HTTP 地址填入设备登录框。

静态 Web 源码随仓库交付，部署方可放在自己的静态站点。本机预览可另开终端执行：

```bash
# 将 192.0.2.10 换成设备可达的开发 PC 局域网 IP。
REFERENCE_HTTP_BASE_URL='http://192.0.2.10:8080' \
REFERENCE_WEB_BIND=0.0.0.0 REFERENCE_WEB_PORT=8090 \
bash ../tools/run-reference-web.sh
```

App“连接设置”的 Web 结果地址填写 `http://<PC_LAN_IP>:8090`；Web 的
`config.json` 中 `apiBaseUrl` 必须从设备浏览器可达。Web 使用开发 HTTPS 上游时，
浏览器也要信任该 CA，Debug APK 的信任配置不会替浏览器安装证书。生产 HTTPS 页面
应使用同样可达的 HTTPS API，避免混合内容。仓库只交付后端应用镜像，不新增 Web
或数据库镜像。

## 模型、身份和结果

`REFERENCE_DEV_*` 仅是单账号示例登录；合作伙伴可替换 Identity／Login Provider，
保存登录状态的逻辑在 App 装配层，不进入会议状态机。实时模型、翻译、总结、声纹和
语言列表都由部署配置与 Provider 能力声明共同决定，真实厂商 Key 只留在后端本机或
外部 Secret 注入，不写入模板、日志或仓库。

运行镜像已提供 `ffmpeg`，启用[可选声纹插件](../backend-reference/README.md#p10-可选跨会议声纹参考插件)
后可直接提取样本；不启用时不会执行它。最长会议由 `AI_MEETING_MAX_MEETING_DURATION_MS`
声明，HTTP 录音上传与 file／S3 Store 统一按 `最长毫秒数 × 32 + 1 MiB` 计算预算
（至少保留旧 64 MiB），足以覆盖标准 PCM 回退；该预算不改变 JSON 限制或计费用量。
外部反向代理如设置请求体限制，也应按该预算同步配置。

默认结果展示是 Web；本地 HTML、邮件、扫码／小程序等通过结果动作或渲染器扩展，
不复制会议状态机。截图采集／可选处理的接口与接入见 Android／SDK 手册，
演示多图、末帧及 Web／PDF 对照仍按专项验收边界记录，不用部署成功代替设备验收。

## 镜像边界

根目录 `Dockerfile` 只构建参考应用镜像。数据库初始化脚本、外部 MySQL 实例和
测试容器由部署方负责；本仓不发布 MySQL 镜像或数据库数据卷。

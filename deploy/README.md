# 参考后端部署

参考后端的本机局域网入口是 `ReferenceClientMeetingMain`。它只提供 HTTP 业务服务，
Android 通过服务端签发的临时材料直连已配置的模型 Adapter；音频不经过后端。
镜像和 Compose 只编排这个应用进程，不携带数据库容器。

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
# AI_MEETING_CREATE_ENABLED=true 和模型 Adapter 端点；不要提交 .env。
bash ./migrate.sh
docker compose up --build
```

不使用容器做开发检查时，直接运行：

```bash
bash ../tools/migrate-reference-client.sh
bash ../tools/run-reference-client-backend.sh
```

Compose 中的 `DATABASE_URL` 是应用容器看到的地址；不能把容器内的
`127.0.0.1` 当作宿主机或数据库容器地址。局域网 Android 地址应填写开发机可达的
PC 局域网 IP，例如 `http://192.168.1.20:8080`，不能填写设备自己的
`127.0.0.1`。

## 模型、身份和结果

`REFERENCE_DEV_*` 仅是单账号示例登录；合作伙伴可替换 Identity／Login Provider，
保存登录状态的逻辑在 App 装配层，不进入会议状态机。实时模型、翻译、总结、声纹和
语言列表都由部署配置与 Provider 能力声明共同决定，真实厂商 Key 只留在后端本机或
外部 Secret 注入，不写入模板、日志或仓库。

默认结果展示是 Web；本地 HTML、邮件、扫码／小程序等通过结果动作或渲染器扩展，
不复制会议状态机。截图新增去重算法按当前计划暂缓。

## 镜像边界

根目录 `Dockerfile` 只构建参考应用镜像。数据库初始化脚本、外部 MySQL 实例和
测试容器由部署方负责；本仓不发布 MySQL 镜像或数据库数据卷。

# Backend deployment examples

此目录提供参考后端的 Docker Compose 拓扑示例：`api` 暴露统一协议，
`worker` 执行异步模型任务，`db` 保存元数据，`meeting-storage` 是本地持久化
卷；需要对象存储时可启用 `object-storage` profile。模型服务使用 `model`
profile 占位，合作伙伴替换为自己的镜像或局域网地址。

```bash
cp ../.env.example .env
docker compose -f docker-compose.yml config
docker compose -f docker-compose.yml up -d db api worker
```

Compose 只定义拓扑和占位配置，不代替合作伙伴的认证、计费、模型、存储和
运维实现。生产环境必须通过环境变量或 Docker Secrets 注入密码和密钥，
不得提交填充后的 `.env`。

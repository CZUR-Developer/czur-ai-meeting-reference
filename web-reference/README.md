# Web reference client

这是一个最小的静态结果页参考工程。页面根据后端能力清单动态显示模块，
不包含独立登录，也不携带合作伙伴地址。部署使用合作伙伴已有的静态 Web
服务器；本仓库不提供 Web Docker 镜像。

```bash
npm run check
npm run build             # 生成 web-reference/dist/
```

将 `config.example.json` 复制为部署目录中的 `config.json` 并填写后端地址；
该配置由宿主服务器注入，不要把真实地址或令牌提交到 Git。

# Web reference client

这是一个最小的静态**会后结果页**。Android App 停止会议后打开
`index.html?meetingId=<会议ID>&genTime=<链接生成时间毫秒>`。页面读取 Manifest，
结果生成中时短暂等待；完成后展示 Manifest 中 READY 的 `transcript.final`，
仅当同一 Manifest 还含 READY 的 `translation.final` 时追加最终译文区。
纯字幕结果保持原页面；不会预览会议中的字幕，也不要求 Web 单独登录。

部署使用合作伙伴已有的静态 Web 服务器；本仓库不提供 Web Docker 镜像。

```bash
npm run check
npm test
npm run build             # 生成 web-reference/dist/
```

将 `config.example.json` 复制为部署目录中的 `config.json` 并填写浏览器可访问的
后端地址；同源部署可填空字符串。静态 Web 与后端不在同一来源时，需要部署侧
允许结果接口跨域读取。该配置由宿主服务器注入，不要把真实地址或令牌提交到 Git。

# Web reference client

这是一个最小的静态**会后结果页**。Android App 停止会议后打开
`index.html?meetingId=<会议ID>&genTime=<链接生成时间毫秒>`。页面读取 Manifest，
并从后端公开能力发现读取 `resultLinkTtlMs`，与结果接口使用同一有效期配置；
结果生成中时短暂等待；完成后展示 Manifest 中 READY 的 `transcript.final`，
仅当同一 Manifest 还含 READY 的 `translation.final` 时追加最终译文区；
仅当有 READY 的 `summary.structured` 时展示结构化纪要的标题、概述、要点和待办；
READY 的 `presentation.capture` 截图包按修订序号读取，画面按会议时间展示。
纯字幕结果保持原页面；不会预览会议中的字幕或演示画面，也不要求 Web 单独登录。

页面按本场 Manifest 动态决定结果模块：没有翻译、总结、本场发言人、跨会议身份、录音
或演示产物时，分别隐藏对应区域，不显示空标题、空列表、虚假身份或“能力失败”的
伪入口。`sessionSpeakerId` 只代表本场临时发言人；它不等于跨会议声纹注册结果。
实时字幕是服务端产品档位的必选能力，缺失时会议不会进入结果页；其余可选能力缺失
不阻塞已有文字结果。

本页面是参考方案的默认 `web` 结果动作，读取标准 `MeetingManifest` 和 `ArtifactRef`，不理解听悟或其他 Provider 的厂商字段。结果动作保持可替换：伙伴可以在 READY 后实现 `local.html`，将 Manifest 和所需产物写入本地目录／压缩包，使用相对路径离线查看，也可以实现邮件、NAS 或自有 Web 渲染器。替换渲染器不改变会议状态机、收口或结算。当前仓库提供的是默认 Web 页面；本地离线 HTML 仍是 P10 的扩展实现，不在本 README 中宣称已经完成。

部署使用合作伙伴已有的静态 Web 服务器；本仓库不提供 Web Docker 镜像。

```bash
npm run check
npm test
npm run build             # 生成 web-reference/dist/
```

将 `config.example.json` 复制为部署目录中的 `config.json` 并填写浏览器可访问的
后端地址；同源部署可填空字符串。静态 Web 与后端不在同一来源时，需要部署侧
允许结果接口跨域读取。该配置由宿主服务器注入，不要把真实地址或令牌提交到 Git。

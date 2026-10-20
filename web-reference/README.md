# Web reference client

这是默认交付的静态**会后结果页**，采用 CZUR 会议纪要的大屏分栏、移动标签及底部
播放器布局。Android App 停止会议后打开
`index.html?meetingId=<会议ID>&genTime=<链接生成时间毫秒>`。页面读取 Manifest，
并从后端公开能力发现读取 `resultLinkTtlMs`，与结果接口使用同一有效期配置；
结果生成中时短暂等待；完成后展示 Manifest 中 READY 的 `transcript.final`，
仅当同一 Manifest 还含 READY 的 `translation.final` 时追加最终译文区；
仅当有 READY 的 `summary.structured` 时展示结构化纪要的标题、概述、要点和待办；
READY 的 `presentation.capture` 截图包按修订序号读取，画面按会议时间展示。
若 Manifest 含 READY 的 `audio.original`，页面只生成同一 Artifact 的流式播放地址，
不把录音正文下载到结果 JSON；没有录音产物时整个录音区保持隐藏。
只有原文／译文时，正文扩展至整个内容区；不会预览会议中的字幕或演示画面。
只读回看无需 Web 单独登录。

页面按本场 Manifest 动态决定结果模块：没有翻译、总结、本场发言人、跨会议身份、录音
或演示产物时，分别隐藏对应区域，不显示空标题、空列表、虚假身份或“能力失败”的
伪入口。`sessionSpeakerId` 只代表本场临时发言人；它不等于跨会议声纹注册结果。
实时字幕是服务端产品档位的必选能力，缺失时会议不会进入结果页；其余可选能力缺失
不阻塞已有文字结果。

## 页面与交互

- 大屏：左侧原文／译文，右侧总结纪要、待办、演示文稿和可选人物栏目，各自滚动。
  小屏（宽度不大于 768 CSS 像素）改为单区标签切换，录音控制保持在底部。
- 原译对照使用 `sourceSegmentId` 关联，支持同段多目标语种。无法关联的译文单独显示，
  不按数组位置猜对应关系。可切换译文显隐，复制／导出会保留完整原译文。
- 录音支持播放／暂停、前后 15 秒、进度拖动、0.5～2 倍速；有真实时间的原文可点击
  定位并播放，高亮跟随当前发言。手动滚动暂时解除跟随，可点击“跟随播放位置”恢复。
- 演示图按标准产物顺序显示，可打开大图、上一张／下一张和缩放。此页不执行截图或去重。
- 复制原译文、总结和待办。浏览器另提供 TXT、原始录音下载，以及演示图片的打印／保存
  PDF。打印 PDF 使用浏览器能力，形成每图一页；不依赖第三方在线转换服务。
- Android WebView 默认没有下载和打印宿主，故隐藏分享／导出菜单，保留阅读、复制、
  播放和图片预览。App 的可选本地 PDF 结果插件仍是独立扩展点，见[接入说明](../docs/partner-integration-guide.md#本地结果插件必须按需读取正文)。

没有结构化总结时，不显示总结和待办栏目。已有总结但 `actionItems` 为空时，待办栏目
显示“暂无待办事项”；没有录音就不提供播放和文字跳转。只有录音、演示或译文的标准
产物也可独立呈现。这里描述结果渲染能力，会议能否开启仍由后端档位和权益声明决定。

### 标准字段与扩展位置

| 显示／交互 | 读取字段 | 字段缺失时 |
| --- | --- | --- |
| 会议日期、时长 | Manifest 的 `startedAt`、`durationMs`；播放器优先使用媒体实际时长 | 隐藏缺失信息；不拿链接 `genTime` 当会议时间 |
| 原文定位和高亮 | `segments[].startMs`，单位为相对于录音开始的毫秒 | 保留文字，省略该段定位；不伪造零秒 |
| 原译关联 | `segments[].segmentId`、`translations[].sourceSegmentId` | 译文独立保留 |
| 发言人姓名 | `sessionSpeakerId` 关联标准人物产物的 `displayName` | 仅显示本场发言人编号，不冒充跨场已注册姓名 |
| 总结／待办 | `summary.structured` 的 `title`、`overview`、`keyPoints`、`actionItems` | 隐藏未生成的总结能力 |
| 媒体 | `audio.original` 的 Artifact URL；`presentation.capture` 的标准截图序列 | 隐藏相应控制 |

代码按三层拆分，均为原生 ES modules，无前端框架或运行时 npm 依赖：

- `src/result.mjs`：读取标准协议、渲染正文、装配人物登录与登记；伙伴可以替换账号端口。
- `src/result-model.mjs`：结果可用性、原译关联、时间轴、复制与导出文本的纯函数。
- `src/result-ui.mjs`：标签、媒体控制、段落跟随、复制／导出和图片预览；重绘时清理旧监听。
- `src/index.html`、`src/result.css`、`src/assets/`：可直接替换的布局、样式及图标。

更换 UI 时保留标准字段与有效期规则；不要在页面内绑定厂商返回字段、永久模型 Key
或另建会议状态。图标来源与替换规则见[资源说明](ASSETS.md)。

## 可选人物身份与命名注册

只有同场 Manifest 存在 READY 的 `speaker.identity` 时，页面才读取标准
`speaker-identity/v1` JSON，并开放人物区和“命名并注册”入口。人物名称按
`sessionSpeakerId` 映射回字幕段；本场编号、稳定 `personId` 分别标明。
没有匹配的 `personId`／`displayName` 保持为空，不把本场编号当作已注册人物。
没有 Provider／没有标准身份产物时，页面保留本场发言人与其他结果，不显示注册入口。

匿名 `genTime` 链接只用于 GET。编辑时需显式登录本场会议所属账号：默认内联表单调用
`POST /v1/reference/session:login`，再用取得的 Bearer 调用
`POST /v1/meetings/{meetingId}/speakers/{sessionSpeakerId}:enroll`，正文仅为
`{"displayName":"人物名称"}`，不携带 `genTime` 写入。后端负责真实身份和会议归属检查。
Token 只保留在本页内存，页面刷新需重新登录；不写入 URL、localStorage 或日志，密码
在提交后清空。注册成功后重新读取同一 `meetingId`／原 `genTime` 的 Manifest 和产物，
不凭注册响应伪造会议结果或另建结果状态。

登录是窄扩展点：`createSpeakerIdentityActions(base, meetingId, genTime, fetcher, authenticate)`
接受可替换的 `authenticate(username, password)`，返回含 `accessToken` 的会话即可。
伙伴可在结果页装配点换成自己的账号／单点登录适配，不需要改人物展示、登记或结果读取。
此页仅提供默认参考登录示例，不建立账号管理或认证平台。

本页面是参考方案的默认 `web` 结果动作，读取标准 `MeetingManifest` 和 `ArtifactRef`，不理解听悟或其他 Provider 的厂商字段。结果动作保持可替换：伙伴可以在 READY 后实现 `local.html`，将 Manifest 和所需产物写入本地目录／压缩包，使用相对路径离线查看，也可以实现邮件、NAS 或自有 Web 渲染器。替换渲染器不改变会议状态机、收口或结算。当前仓库交付默认 Web 页面和 App 可选 PDF 插件；离线 HTML 渲染器由伙伴实现，本地正文读取及媒体下载边界见[结果扩展说明](../docs/partner-integration-guide.md#本地结果插件必须按需读取正文)。

部署使用合作伙伴已有的静态 Web 服务器；本仓库不提供 Web Docker 镜像。

```bash
npm run check
npm test
npm run build             # 生成 web-reference/dist/
```

将 `config.example.json` 复制为部署目录中的 `config.json` 并填写浏览器可访问的
后端地址；同源部署可填空字符串。静态 Web 与后端不在同一来源时，需要部署侧
允许结果接口跨域读取。该配置由宿主服务器注入，不要把真实地址或令牌提交到 Git。

构建会重建 `dist/`，请在**每次构建完成后**注入 `config.json`。配置不在源码中，
部署系统应保管其原件。HTTPS 后端需使用浏览器／设备实际信任的证书。

## 验证

Node 测试覆盖产物读取、人物登录协议、时间轴、原译配对、复制文本和缺能力投影。
真实页面交互可用[设备结果校验工具](../tools/android-device/README.md#设备实际-web-结果与录音)
的 `--interactions` 验证；共用检查函数为 `tools/result-ui-checks.mjs`，可以在浏览器
自动化中通过 `page.evaluate(checkResultInteractions)` 复用，不依赖特定测试框架。
浏览器下载／打印以及目标 WebView 的真实显示需单独验收，不能由 Node 单测替代。

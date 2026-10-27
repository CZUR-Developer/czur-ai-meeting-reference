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

- 大屏：左侧原文／译文，右侧总结纪要、待办、演示文稿，各自滚动；有总结时右侧默认显示总结。
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
| 发言人姓名 | 段落 `speakerDisplayName` 优先，再按 `sessionSpeakerId` 读取标准人物产物 | 无姓名时显示本场编号；人工改名不代表跨场登记成功 |
| 总结／待办 | `summary.structured` 的 `title`、`overview`、`keyPoints`、`actionItems` | 隐藏未生成的总结能力 |
| 媒体 | `audio.original` 的 Artifact URL；`presentation.capture` 的标准截图序列 | 隐藏相应控制 |
| 结果状态与告警 | Manifest 的 `status`、`warnings[].code`、`warnings[].capability` | 保留正文阅读；`READY_WITH_WARNINGS` 展示告警但隐藏姓名编辑入口 |

代码按三层拆分，均为原生 ES modules，无前端框架或运行时 npm 依赖：

- `src/result.mjs`：读取标准协议、渲染正文、装配发言人编辑；伙伴可以替换编辑授权与账号端口。
- `src/result-model.mjs`：结果可用性、原译关联、时间轴、复制与导出文本的纯函数。
- `src/result-ui.mjs`：标签、媒体控制、段落跟随、复制／导出和图片预览；重绘时清理旧监听。
- `src/index.html`、`src/result.css`、`src/assets/`：可直接替换的布局、样式及图标。

更换 UI 时保留标准字段与有效期规则；不要在页面内绑定厂商返回字段、永久模型 Key
或另建会议状态。图标来源与替换规则见[资源说明](ASSETS.md)。

## 发言人改名与可选跨会议记录

本节是 Web 编辑授权、改名与附带登记协议的权威说明，其他文档只摘要并链接到这里。

参考宿主通过能力发现的 `resultSpeakerEditingEnabled=true` 开放原文姓名按钮；
只有本场 Manifest 状态为 `READY` 时才真正渲染按钮。`READY_WITH_WARNINGS` 仍正常展示
正文和降级告警，但后端姓名编辑端口只接受 `READY`，页面不再显示必然 409 的入口。
持有有效结果链接即可输入昵称，不要求再次登录；选择“单个”（所选原文段）或“全局”（本场同一
`sessionSpeakerId` 的全部原文段）。修改持久化到标准原文，不依赖声纹 Provider。
无本场分人数据时没有姓名按钮。复制和 TXT 导出使用同一名称投影。

部署声明 `speaker.identity.cross_meeting` 且本场存在对应身份产物时，弹窗提供
“下次自动识别”。勾选后先保存本场名称，再调用可替换 Provider 登记；不勾选时只改
本场。单个＋登记也不会连带修改本场其他段落。登记失败明确提示本场已保存，保留弹窗
供重试；不将其显示为下次识别成功。后续会议依样本和供应商实际匹配，不能保证每次命中。

同场 READY 的 `speaker.identity` 只用于原文姓名投影和可选登记，不建立独立“人物”Tab，
也不展示内部 `personId`。未匹配时保留本场编号；无 Provider 时仍可本场改名，隐藏
自动识别选项。人物数据不会替代总结：没有真实总结产物就不显示总结栏目。

默认参考宿主声明 `resultSpeakerEditingRequiresLogin=false`。页面使用原结果链接调用
`POST /v1/meetings/{meetingId}/speakers/{sessionSpeakerId}:rename?genTime=<原值>`，
不请求登录、不发送 Authorization；请求正文例如：

```json
{"displayName":"演示发言人","scope":"SINGLE","segmentId":"segment-1","remember":false}
```

`ALL` 时可省略 `segmentId`；响应包含 `manifestRevision`、`changedSegments`、
`registration`（`NOT_REQUESTED`、`REGISTERED`、`SAMPLE_UNAVAILABLE`、`NOT_GRANTED`、
`UNAVAILABLE`）。本场已保存、登记失败也返回成功写入和独立登记状态。
后端复用结果读取的有效期与撤销校验，仅授权本场编辑及附带登记；不开放其他会议控制
或独立人物库管理。保存成功后重新读取同一 `meetingId`／原 `genTime` 的 Manifest 和产物，
不凭写入响应伪造会议结果或另建结果状态。

标准 `transcript.final.segments[].speakerDisplayName` 为可选字段：缺席时读取自动
身份名称；字符串为本段人工名称；显式 `null` 保持编号显示。后两种优先于身份名称，
保证单个修改不会因后来登记而扩大范围。其他结果渲染器应遵守同一规则。
旧宿主未装配编辑入口时不显示姓名编辑按钮；既有 `:enroll` API 仍供已认证的自定义界面使用。

授权是窄扩展点：后端替换 `SpeakerResultAccessPolicy.Factory`，Web 按
`resultSpeakerEditingRequiresLogin` 决定是否显示登录；字段缺席默认需要登录，兼容旧宿主。
`createSpeakerIdentityActions(base, meetingId, genTime, fetcher, authenticate, { requiresLogin })`
接受可替换的 `authenticate(username, password)`，返回含 `accessToken` 的会话即可。
选择 `ownerOnly` 的宿主使用无 query 的 rename 和 Bearer；默认认证示例调用
`POST /v1/reference/session:login`。Token 只留在页面内存，密码提交后清空；刷新后需重新登录。
伙伴可替换自己的账号／单点登录适配，不需要改姓名展示、登记或结果读取。
参考默认链接策略不启用此表单，不建立账号管理或认证平台。

本页面是参考方案的默认 `web` 结果动作，读取标准 `MeetingManifest` 和 `ArtifactRef`，不理解听悟或其他 Provider 的厂商字段。结果动作保持可替换：伙伴可以在 READY 后实现 `local.html`，将 Manifest 和所需产物写入本地目录／压缩包，使用相对路径离线查看，也可以实现邮件、NAS 或自有 Web 渲染器。替换渲染器不改变会议状态机、收口或结算。当前仓库交付默认 Web 页面和 App 可选 PDF 插件；离线 HTML 渲染器由伙伴实现，本地正文读取及媒体下载边界见[结果扩展说明](../docs/partner-integration-guide.md#本地结果插件必须按需读取正文)。

部署使用合作伙伴已有的静态 Web 服务器；本仓库不提供 Web Docker 镜像。

```bash
npm run check
npm test
npm run build             # 生成 web-reference/dist/
```

构建前先准备本地配置：复制 `deployment.local.example.json` 为 `deployment.local.json`
（已被 Git 忽略），把 `apiBaseUrl` 填成浏览器可达的后端地址；`npm run build` 一步把页面和
生成的 `dist/config.json` 一起放进产物。缺少这份文件或字段为空时构建直接失败，不会产出
打开就报"缺少后端配置"的页面。测试／生产各留一份，用 `REFERENCE_WEB_DEPLOYMENT_FILE` 切换。

手工打开结果页时两个参数都要带：

```text
http://<主机>:8090/index.html?meetingId=<mtg_...>&genTime=<毫秒时间戳>
```

`meetingId` 是 Create 响应里的 `mtg_...`；`genTime` 是生成链接时的 Unix 毫秒
（`date +%s%3N`），服务端按 `resultLinkTtlMs` 判断过期，`-1` 只在诊断时表示永不过期。
缺 `genTime` 时页面拿不到有效期上下文，会直接报错；链接过期后重新取一个 `genTime`。

`dist/` 和其中的 `config.json` 是按仓库路径固定的，同一份 clone 上并行跑两份部署会互相覆盖：
`tools/run-reference-web.sh` 每次启动都会重新构建，把当前的 `REFERENCE_HTTP_BASE_URL`
写进同一个 `web-reference/dist/config.json`，已经在提供服务的 8090／8091 实例也会跟着换
地址。要并行对比，用独立的 clone 或 `git worktree`，各自构建自己的 `dist/`。

静态 Web 与后端不在同一来源时，需要部署侧允许结果接口跨域读取。不要把真实地址或令牌
提交到 Git；HTTPS 后端需使用浏览器／设备实际信任的证书。

## 验证

Node 测试覆盖产物读取、链接编辑与可选登录协议、时间轴、原译配对、复制文本和缺能力投影。
真实页面交互可用[设备结果校验工具](../tools/android-device/README.md#设备实际-web-结果与录音)
的 `--interactions` 验证；共用检查函数为 `tools/result-ui-checks.mjs`，可以在浏览器
自动化中通过 `page.evaluate(checkResultInteractions)` 复用，不依赖特定测试框架。
浏览器下载／打印以及目标 WebView 的真实显示需单独验收，不能由 Node 单测替代。

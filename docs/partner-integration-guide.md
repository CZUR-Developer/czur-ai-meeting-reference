# 参考方案接入手册

本文面向需要二次开发的合作伙伴。它说明如何从本仓库启动一套可替换的
Android App、Java 21 后端和静态 Web 结果页，并指出各项能力的扩展位置。
本文只描述参考实现的公开边界；不会把 CZUR 的账号、模型密钥、私有地址或
系统服务带入参考工程。

## 1. 交付范围

参考工程包含以下可独立打开和构建的部分：

| 目录 | 作用 | 入口 |
| --- | --- | --- |
| `backend-core/` | 会议状态、协议、能力计划、持久化端口和产物端口 | Maven 普通 JAR |
| `backend-reference/` | 参考 HTTP 进程、演示登录、Mock 和默认装配 | `ReferenceClientMeetingMain` |
| `android-reference-app/` | Kotlin First 的登录、会前设置、会中字幕／互译、停止和结果动作 | Android Studio 工程 |
| `web-reference/` | 读取标准 Manifest 并展示原文、译文、总结和可用媒体 | 静态构建产物 |
| `contracts/` | OpenAPI、JSON Schema、实时事件和 Fixture | 协议依据 |
| `deploy/` | 交付阶段的 Dockerfile、Compose 和唯一 V001 初始化脚本 | 产品部署示例 |

参考 App 默认提供 Web 结果动作。邮箱、本地 PDF、扫码／小程序或伙伴自己的
结果渲染器都通过结果动作扩展，不在核心会议状态机中写死。截图新增算法仍按
当前项目决定暂缓；现有基础截图能力不代表完整去重算法已经交付。

## 2. 本机开发和局域网短会

开发验证直接使用本机 Java 21、Maven、Python 3、Node.js/npm 和 Android SDK，
不使用 Docker 作为检查工具。Dockerfile 和 Compose 只用于交付部署；本机没有
隔离数据库时，可以临时运行 `mysql:5.7`，但容器、数据卷和账号不属于交付物。

```bash
cd czur-ai-meeting-reference
cp tools/reference-client.env.example tools/reference-client.env
# 编辑 tools/reference-client.env：数据库、演示账号、模型 Adapter 和可写产物目录
bash tools/migrate-reference-client.sh
bash tools/run-reference-client-backend.sh
```

另开终端检查后端和 Web：

```bash
bash tools/check-reference-backend.sh
bash tools/run-reference-web.sh
```

参考客户端入口只启动 HTTP 业务接口；Android 通过
`POST /v1/meetings/{meetingId}:modelSession` 取得短期材料后直连模型，音频不经过
后端。设备上的登录／后端地址使用开发电脑可达的 HTTPS 地址，不能填写设备自己的
`127.0.0.1`。开发电脑尚无 HTTPS 入口时，按[本机局域网 HTTPS 联调](local-lan-https.md)
生成仓外证书、启动 Node 流式 TLS 入口，并通过可选 Gradle 属性让 Debug APK 信任该 CA。
正式 Release 不携带开发 CA，沿用部署方的正式 HTTPS 证书；`adb reverse` 仅用于诊断。

数据库统一使用 MySQL 5.7。先由部署方创建隔离数据库，再执行仓库中当前唯一的
`deploy/migrations/V001__initial_schema.sql`（或运行 `deploy/migrate.sh`）。该脚本只新增
`ai_trans_*` 专用表，不创建数据库／账号，也不修改旧业务表。应用启动只做 readiness
检查，不会自动建表或修改表结构。开发机没有隔离数据库时，只能执行 Mock／模块测试，
不能把它写成真实局域网会议验收。

### 模型配置

`tools/reference-client.env.example` 只提供占位值。最小的确定性接线可使用
`sample-json-ws`；真实验证时才改为已获准的 Provider，并将密钥通过本机未跟踪
配置或 Secret 注入：

```dotenv
AI_MEETING_MODEL_ADAPTER=tingwu-realtime
AI_MEETING_MODEL_LANGUAGES=zh-CN,en-US,ja-JP
AI_MEETING_MODEL_CAPABILITIES=transcript.realtime,translation.realtime
```

服务端将 Provider、Product Profile、权益和部署限制求交，得到可用能力；支持语言
则由部署方按实际 Provider 填写 `AI_MEETING_MODEL_LANGUAGES`，`/v1/capabilities`
按该声明返回，参考界面不固定厂商清单。当前听悟实现已有中文、英语、日语、韩语、
德语、法语、俄语七种标准语言映射，部署只声明实际开放的子集。

默认命令行入口只装配已有的听悟和 `sample-json-ws` 发行器。更换已有实现的端点和
声明可改配置；接入新模型时，需要在自己的后端宿主注入 `ModelSessionIssuer`，并在
Android 注册匹配的 `ModelAdapter`，再声明该实现确实支持的语言／能力。仅把
`AI_MEETING_MODEL_ADAPTER` 改成任意字符串不会自动生成供应商实现，会议状态机无须修改。
实际第三方密钥不得写入源码、模板、APK、日志或本手册。

## 3. 登录和鉴权扩展

登录是 App 层的插件装配，不是会议业务的隐含依赖：

1. App 通过 `IdentityProvider` 读取、保存、恢复和清除登录会话。
2. 参考实现的 `PersistentReferenceIdentityProvider` 只保存可恢复的会话材料，
   不保存密码；退出会清除本地会话。
3. 后端通过 `IdentityProvider` 验证 Bearer 凭据，会议核心只接收已经验证的
   `VerifiedIdentity`。

合作伙伴可在自己的 `MeetingAppAssembly` 中替换 App Provider，也可在后端装配
自己的身份 Provider。替换点不要求修改 `MeetingCoordinator`、会议状态机、模型
Adapter 或结果页。CZUR 的 `ContentProvider/UserHandler` 属于产品侧实现，不是
参考工程的默认依赖。

登录交互通过 App 的 `AppLoginEntry` 单独替换；默认入口委托参考表单。伙伴在
`MeetingAppAssembly.initialize(context)` 后、创建会议页面前装配自己的
`IdentityProvider` 和 `registerLoginEntry(...)`：

```kotlin
MeetingAppAssembly.registerIdentityProvider(partnerIdentityProvider)
MeetingAppAssembly.registerLoginEntry(AppLoginEntry { activity, defaultEntry, onIdentityChanged ->
    // 打开伙伴自己的登录界面；其成功回调更新 partnerIdentityProvider，再通知页面。
    partnerLogin.open(activity) { onIdentityChanged() }
    // 如使用参考账号密码流程，可改为 defaultEntry()。
})
```

替换入口会完全接管账号切换，不会先执行默认退出。会话保存／恢复由伙伴 Provider
负责，回调只触发重新读取身份和能力；SDK 不申请账号、不弹登录 UI，也不引用系统账号。

## 4. 模型和能力扩展

### 实时模型

Android 端通过 `ModelAdapter` 接收 Provider 数据，将原文、译文、修订、发言人和
结束信号归一化为公共事件。新增 Provider 时：

1. 实现 `ModelAdapter` 及其生命周期；
2. 在 `ModelAdapterRegistry` 注册 Adapter ID；
3. 后端实现对应的 `ModelSessionIssuer`，只签发短期连接材料；
4. 在部署配置中声明支持的语言和能力；
5. 用现有 Adapter 契约测试验证 partial/final、修订和停止时序。

模型原始 JSON、厂商短码和永久密钥只能留在 Adapter／Issuer 内部，不能泄漏到
会议核心或 Web。设备直连模型时，后端仍只接收归一化后的标准事件和用量事实。

### 可选能力

能力缺失时保持剩余链路可用，不生成空产物或假入口：

| 能力 | 缺失时的参考行为 |
| --- | --- |
| `translation.realtime` | 隐藏互译入口，只保留原文 |
| `summary.structured` | 不显示纪要开关，不请求总结材料，Stop 后仍可回看文字 |
| `speaker.diarization.session` | 不显示发言人列表，不把空 ID 当作发言人 |
| `speaker.identity.cross_meeting` | 不显示跨会议身份；本场临时 Speaker 不冒充跨场身份 |

`transcript.realtime` 是参考会议的必选能力；未声明时 App 保持开始按钮禁用，
后端拒绝 Create。上述行为由能力声明和本场 Create 决定共同驱动，不能靠固定的
“中文／英文”或“听悟已配置”推断能力。

### 录音留存策略

`audio.original` 是结果产物类型；是否保留录音使用既有 Create 字段
`artifactPolicy.retainAudio`，不放入模型能力清单。默认参考 App 在 SDK 运行时保留
录音，Mock 运行时不保留。伙伴可在自己的会前装配中复用已有 API 关闭：

```kotlin
val withoutRecording = options.withCreateParameters(
    title = options.title(),
    timezone = options.timezone(),
    retainAudio = false,
)
// 将 withoutRecording 交给原有会议启动流程，仍使用同一个 SDK 和结果链。
```

此选项不关闭实时采音或字幕／互译，只控制本场录音留存及上传；停止后的 Manifest
不含音频时，Web 隐藏播放器，已有文字／总结仍正常交付。跨会议身份参考实现依赖
会后录音，关闭留存时也不请求该可选能力。参考页面当前不额外增加录音开关，伙伴
可按自己的产品流程决定该参数；不需要增加新的 Server capability 或结果状态机。

演示采集同样使用既有 Create `artifactPolicy.presentationCapture`；
`presentation.capture` 是其结果产物类型，不作为模型能力配置。未启用时不启动
采集、Web 不显示演示区。截图新增实装与算法封装仍按本期决定暂缓。

## 5. 结果动作和 Web 扩展

结果交付通过 `ResultActionPlugin` 注册。默认 `WebResultActionPlugin` 生成统一
结果链接；插件只负责准备、执行和展示自己的动作，不复制会议状态机，也不修改
Manifest。

默认 Web 插件仅保存最近一场的完整原链接，并提供“查看上场结果”入口；回看沿用
原 `genTime` 和有效期，这个入口不提供账号会议列表，也不恢复已结束的会议。

伙伴可以新增：

- 本地离线 HTML 或 PDF 渲染器：从 `MeetingResult` 生成自己的文件并交给 App；
- 邮件动作：发送同一结果链接或伙伴自己的附件策略；
- 小程序／扫码动作：在 App 中接入伙伴接收端和 Hook；
- NAS、企业 IM 或其他结果接收器：实现同一个动作接口。

Web 只读取 `READY` Manifest。原文、译文、结构化总结和录音分别由真实
`ArtifactRef` 决定是否展示；录音使用 `GET /v1/artifacts/{artifactId}?genTime=...`
流式播放，不把长音频塞进 JSON。伙伴更换结果页面时，仍应复用 Manifest、产物类型和
有效期规则。

启用跨会议人物身份时，默认参考 Web 还提供登录后的命名注册入口；匿名结果链接仍只读。
`speaker.identity` 是单独的标准产物，本场 speaker 编号与跨会议 `personId` 分开。
参考讯飞插件、FFmpeg 依赖和可选环境项见 [后端说明](../backend-reference/README.md#p10-可选跨会议声纹参考插件)
及 [本机配置模板](../tools/reference-client.env.example)。未注册 Provider 时人物区隐藏，
字幕／译文／总结和录音照常交付；伙伴可用自己的 `SpeakerIdentityProvider` 替换供应商与人员库。

## 6. 交付前检查

在提交参考代码前运行：

```bash
bash tools/check-reference-delivery.sh
bash tools/verify-local.sh
cd android-reference-app && ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

`check-reference-delivery.sh` 只检查公开交付所需的目录、脚本、模板、未跟踪密钥
边界和 Git 差异格式；它不代替隔离数据库、真实 Provider、Android 设备或跨会议
声纹验收。最终验收应记录以下四类证据，不能互相替代：

- 静态检查和协议 Fixture；
- Maven／Gradle／Web 模块构建；
- 本机局域网数据库、Provider 和设备的真实短会；
- 两场真实会议的跨会议声纹结果（启用该能力时）。

2026-10-17 初次参考验证完成本机 MySQL 5.7 隔离库和“只声明实时字幕”的 Android
设备短会：登录会话恢复、能力发现、实时字幕、正常停止、`READY` 结果和可选能力
降级均已通过。该证据记录在
[`docs/p10-local-lan-validation-20261017.md`](p10-local-lan-validation-20261017.md)。
初次短会未启用翻译／总结／跨会议身份，且未保留录音，该时点不代签其他功能。
后续真实 LAN HTTPS 已验证原文、中文到英语互译、总结及录音播放／拖动；Web 真实
登录和人物命名注册也已通过。真实中文到日语的第二场已匹配首场同一人物，跨会议
识别通过；该场手工打开 Web 的原译文／总结／身份与 609.02 秒录音播放／拖动已验。
随后约 9 分半的真实中日长会已验默认 App 自动打开 Web，55 段原文／55 段日语、
3 个人物、总结及 568.12 秒录音播放／拖动通过。会议结束后强停重开，登录保持，
实际点击“查看上场结果”回到同一场原链接；另起会议主动息屏后正常停止并到 READY，
唤醒后自动打开同场 Web，原文、总结和 83.02 秒录音播放／拖动通过。
最终冻结 Debug 包又完成正常短会：停止退窗、结果成功后一次结束提示、自动 Web，
以及同场原文、总结、人物和 42.52 秒录音播放／拖动均已验证。
各次配置、实现版本与证据以该记录为准，模块测试不能代替真实能力验收。

## 7. 参考仓与 CZUR 产品边界

本仓是可修改的开源参考实现；CZUR 的闭源 SDK、AOSP Broker、旧
`CloudAsrService`、生产账号体系和云端私有服务不属于本仓交付。合作伙伴可以
通过公开接口替换实现，但不能把私有密钥或产品代码复制进来。Gitee 是开发主仓，
开发和回归完成后再按发布流程同步 GitHub；没有发布授权时不推送或打标签。

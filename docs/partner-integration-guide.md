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
不使用 Docker 作为检查工具。Dockerfile 和 Compose 只用于交付部署。

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
后端。设备上的后端地址必须填写开发电脑的局域网 IP，例如
`http://192.168.1.20:8080`，不能填写设备自己的 `127.0.0.1`。

数据库先执行仓库中当前唯一的
`deploy/migrations/V001__initial_schema.sql`。应用启动只做 readiness 检查，
不会自动建表或修改表结构。开发机没有隔离数据库时，只能执行 Mock／模块测试，
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

语言不是听悟或任何厂商的固定清单。服务端把 Provider、Product Profile、权益和
部署声明求交后通过 `/v1/capabilities` 返回；更换 Adapter 时只需替换 Adapter、
端点和声明，不改会议状态机。实际第三方密钥不得写入源码、模板、APK、日志或
本手册。

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
| `audio.original` | 不生成录音引用，文字结果仍可完成 |
| `presentation.capture` | 不显示演示结果区 |

`transcript.realtime` 是参考会议的必选能力；未声明时 App 保持开始按钮禁用，
后端拒绝 Create。上述行为由能力声明和本场 Create 决定共同驱动，不能靠固定的
“中文／英文”或“听悟已配置”推断能力。

## 5. 结果动作和 Web 扩展

结果交付通过 `ResultActionPlugin` 注册。默认 `WebResultActionPlugin` 生成统一
结果链接；插件只负责准备、执行和展示自己的动作，不复制会议状态机，也不修改
Manifest。

伙伴可以新增：

- 本地离线 HTML 或 PDF 渲染器：从 `MeetingResult` 生成自己的文件并交给 App；
- 邮件动作：发送同一结果链接或伙伴自己的附件策略；
- 小程序／扫码动作：在 App 中接入伙伴接收端和 Hook；
- NAS、企业 IM 或其他结果接收器：实现同一个动作接口。

Web 只读取 `READY` Manifest。原文、译文、结构化总结和录音分别由真实
`ArtifactRef` 决定是否展示；录音使用 `GET /v1/artifacts/{artifactId}?genTime=...`
流式播放，不把长音频塞进 JSON。伙伴更换结果页面时，仍应复用 Manifest、产物类型和
有效期规则。

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

## 7. 参考仓与 CZUR 产品边界

本仓是可修改的开源参考实现；CZUR 的闭源 SDK、AOSP Broker、旧
`CloudAsrService`、生产账号体系和云端私有服务不属于本仓交付。合作伙伴可以
通过公开接口替换实现，但不能把私有密钥或产品代码复制进来。Gitee 是开发主仓，
开发和回归完成后再按发布流程同步 GitHub；没有发布授权时不推送或打标签。

# 模型与能力配置指南

> 第一次接入 · 第 2/4 篇 ｜ 上一篇 ← [从零接入指南](partner-getting-started.md) ｜ 下一篇 → [本机局域网 HTTPS 联调](local-lan-https.md)

本文集中说明完整参考方案用到的第三方模型、CZUR 当前采用的平台、伙伴应准备的
资源，以及可选能力缺失后的行为。首次接入步骤见[从零接入指南](partner-getting-started.md)。
平台名称用于说明参考路径，不代表合作伙伴必须购买同一供应商或使用 CZUR 账号。
服务开通、区域、额度、语言和资费以您自己的第三方平台配置为准，仓库不包含真实凭据。

## 1. 第三方平台与业务功能总表

| 功能／输入输出 | CZUR 当前方案采用的平台 | 参考实现与资源 | 在业务中出现在哪里 | 可以不提供吗 |
| --- | --- | --- | --- | --- |
| 实时语音识别（ASR）：音频 → 原文 | 阿里云听悟 | 内置 `tingwu-realtime`；需听悟 AppKey 与后端使用的 AccessKey ID／Secret | App 实时字幕；Web 最终原文 | **当前参考产品必需**。可替换 ASR，但默认流程无法在完全没有 ASR 的情况下开会 |
| 实时翻译：原文语义 → 译文 | 阿里云听悟的原生翻译 | 同一听悟会话；确认开通翻译、语言方向并声明能力 | App 双语互译；Web 最终译文 | 可缺失，保留字幕；互译入口不可用，Web 无译文区 |
| 本场发言人分离：音频 → 本场编号和时间段 | 阿里云听悟的分人能力 | 同一听悟会话声明 `speaker.diarization.session` | 会中／会后按本场发言人区分内容 | 可缺失，不生成分人结果；跨会议识别的参考路径也不能开启 |
| 文本总结：最终文字 → 结构化纪要 | 阿里云百炼／DashScope 应用 API | 内置 `bailian-summary`；需百炼应用 ID 和可调用该应用的 DashScope API Key | 会前纪要选项；Web 标题、概述、要点、待办 | 可缺失；没有总结开关和总结区，已有原文／录音仍可回看 |
| 跨会议声纹：录音片段 → 人物匹配／注册 | 讯飞开放平台声纹服务 | 参考后端可选 `xfyun`；需 AppID、APIKey、APISecret、对应服务权限 | Web 勾选下次自动识别；后续会议匹配同一个人 | 可缺失；保留本场编号／人工改名，无下次自动识别选项 |

百炼这里使用“应用 API”，具体大语言模型及应用提示词由该平台应用配置决定。
源码没有固定某个大模型型号，不能从 Adapter 名称推断为某一款通义／Qwen 模型。
若伙伴的百炼应用使用不同输出，需确保它满足既有结构化结果约定，或替换总结 Adapter。

完整功能不需要分别购买五个模型：默认听悟可同时提供前三项；总结和跨会议声纹是
独立可选服务。ASR、翻译、总结、声纹均可通过各自接口替换，其他公共流程继续复用。

以下内容应与 AI 模型分开准备：

| 依赖 | 用途 | 与模型的区别／是否可选 |
| --- | --- | --- |
| MySQL 5.7 | 会议状态、归属、产物和人物映射元数据 | 当前参考部署必需的数据库 |
| 本地 file 或对象存储 | 保存原文、录音、截图等实际文件 | 必须有可用 Store；本机 file 足够，不要求购买云存储 |
| 阿里云 OSS | CZUR 产品使用的对象存储平台 | 参考提供 file／S3 兼容实现；不把 CZUR 的 OSS 接线或账号作为默认依赖 |
| FFmpeg | 后端解码／截取声纹样本 | 启用参考声纹插件才需要；交付后端镜像已包含，本机运行自行安装 |
| SDK 截图与去重 | 获取屏幕图像，可选本地处理，产生演示图片 | 本地／系统能力，不需要图像模型 Key；不包含 OCR 或图片内容理解模型 |
| SDK 音量、方向角（DOA） | 音量显示和互译左右侧显示的设备参考信息 | 来源于音源／设备能力，不是声纹或 ASR；普通 MIC 无方向角时按可用信息处理 |

`sample-json-ws` 和 `sample-json-translation` 分别是 WebSocket 语音协议、HTTP 文本
翻译协议样例，供开发自己的模型服务／Adapter时对照；它们本身不提供真实 AI 服务。
仅填写一个样例端点不会获得识别或翻译能力。

## 2. 哪些配置决定用户能看到的功能

可用功能由已装配实现、产品配置、账号权益和部署限制共同决定；创建会议时再冻结
这场会议的请求。支持语言由部署配置声明，App 读取发现接口，不应自己写死厂商语言表。
`AI_MEETING_MODEL_LANGUAGES` 省略时使用 `zh-CN,en-US`；发现接口直接返回声明列表，
当前不会向 Provider 查询语种，也不对 Provider／Profile 语言列表自动求交。
部署者须核对模型和 Adapter 的实际语言支持范围。

```text
实际模型／Provider 已装配 + 正确凭据和服务开通
                       |
产品能力配置 ∩ 账号权益 ∩ 实现能力 ∩ 部署限制
                       |
                 本场选择并冻结
                       |
      App 功能入口／字幕  →  真实结果产物  →  Web 对应区域
```

主要配置含义：

| 配置键 | 含义 |
| --- | --- |
| `AI_MEETING_PROFILE_REQUIRED_CAPABILITIES` | 当前产品必选能力；参考默认仅 `transcript.realtime` |
| `AI_MEETING_PROFILE_OPTIONAL_CAPABILITIES` | 产品允许用户按需选用的能力；没有则不开放 |
| `AI_MEETING_ENTITLEMENT_SUPPORTED_CAPABILITIES` | 参考账号权益允许的能力；伙伴可用自己的权益插件替换 |
| `AI_MEETING_MODEL_CAPABILITIES` | 声明模型组合实际支持的转写、翻译、总结和本场分人能力；须有真实实现 |
| `AI_MEETING_MODEL_LANGUAGES` | 当前部署实际开放的标准语言码列表 |
| `AI_MEETING_DISABLED_CAPABILITIES` | 可选的部署禁用集合 |

配置表示部署方的能力声明，不能让未实现／未开通的模型自动获得该功能。
总结未装配发行器时不会宣称总结可用；跨会议声纹由已注册人物 Provider 声明，
不能只加一段能力字符串冒充已接入。

如果用户会前已选择互译或总结，它会成为本场所需能力；Create未授权该选择时App会
提示且不启动会议，不会静默改为仅字幕。本指南的“可缺失”指不提供该能力时关闭入口，
用户仍可选择剩余的字幕业务。

## 3. 模型资源与配置入口

本机配置使用 [`tools/reference-client.env.example`](../tools/reference-client.env.example)
复制出的本地文件，容器使用根 [`.env.example`](../.env.example) 复制出的 `deploy/.env`。
以下片段编辑到正在使用的同一配置文件中，替换同名旧值；省略号和占位值不代表可直接运行。
修改后重启后端，刷新 App 能力并开新会议。

### 3.1 听悟：先只跑实时字幕

在阿里云创建／取得听悟应用，开通所需服务并为后端配置可创建听悟任务的调用凭据。
需要的配置：

```dotenv
AI_MEETING_MODEL_ADAPTER=tingwu-realtime
AI_MEETING_TINGWU_ENABLED=true
AI_MEETING_TINGWU_APP_KEY=replace-with-tingwu-app-key
AI_MEETING_TINGWU_ACCESS_KEY_ID=replace-with-access-key-id
AI_MEETING_TINGWU_ACCESS_KEY_SECRET=replace-with-access-key-secret
AI_MEETING_MODEL_LANGUAGES=zh-CN,en-US
```

同时按从零指南设置必选、权益和模型的 `transcript.realtime`。后端创建任务后将
短期连接材料交给 SDK；Android 不保存上面两项长期 AccessKey。

当前听悟 Adapter 的标准语言映射包括中文 `zh-CN`、英语 `en-US`、日语 `ja-JP`、
韩语 `ko-KR`、德语 `de-DE`、法语 `fr-FR`、俄语 `ru-RU`。这表示已有对应映射，
实际语言／组合仍须由部署方核对供应商开通情况，只声明真实可用子集。
更换供应商后，声明新模型的语言并在其 Adapter 内转换厂商语言码。

### 3.2 翻译：原生听悟或独立模型二选一

听悟原生路径沿用上一节凭据，为 Profile optional、权益 supported、模型 capabilities
三处同时加入 `translation.realtime`，声明所需语言，App 才能选择互译。

若所选 ASR 不带翻译，可组合独立文本翻译：

- 后端提供 `TranslationSessionIssuer`，通过 `TextTranslationModelSessionIssuer` 与 ASR 发行器组合。
- Android 注册对应的 `TranslationAdapter`；SDK 将已确认原文提交给它，再归一化译文。
- 仓库已有 `sample-json-translation` 协议样例，配置 `AI_MEETING_SAMPLE_JSON_TRANSLATION_ENDPOINT`；实际 HTTP 服务由伙伴提供。
- 组合路径关闭 ASR 原生译文，避免重复翻译；ASR 音源及会议仍共用原流程。

两条路径均需按实际翻译模型声明语言。文本翻译样例的请求、响应、短期材料及续领说明
见[独立文本翻译扩展](partner-integration-guide.md#可选独立文本翻译)。

### 3.3 百炼：可选结构化纪要

在百炼准备可调用的应用，确认其接受文本输入，输出可映射为标题、概述、要点和待办。
应用 ID 与调用 Key 的所属账号／可用区域须匹配。

```dotenv
AI_MEETING_BAILIAN_SUMMARY_ENABLED=true
AI_MEETING_BAILIAN_APP_ID=replace-with-bailian-app-id
DASHSCOPE_API_KEY=replace-with-dashscope-api-key
```

此外三处同时加入 `summary.structured`，并在 App 会前开启纪要。单独配置 Key 不会
强制所有会议总结。后端用长期 Key 换取短期材料，SDK 按本场最终文字调用总结服务。

默认 Adapter向应用API发送 `input.prompt`，从响应 `output.text` 中读取一个JSON对象：

```json
{
  "title": "会议标题",
  "overview": "会议概述",
  "keyPoints": ["要点一"],
  "actionItems": ["明确的待办事项"]
}
```

没有明确待办时使用空数组。两段文本及数组元素都应为字符串；默认请求已带结构化
输出要求。请用自己的应用确认其实际响应遵循此约定，再启用该能力。

换成其他总结服务时，实现后端 `SummarySessionIssuer` 与 Android `SummaryAdapter`；
保持统一结构化结果，Web 不需要理解厂商响应字段。无总结模型时不填凭据、不声明该能力。

### 3.4 讯飞：可选跨会议人物识别／注册

在讯飞开放平台开通对应声纹服务，准备 AppID、APIKey、APISecret。
参考插件在会后依据本场发言人时间段与留存录音提取样本，匹配已注册人物；用户在
结果 Web 点击发言人，可按单个／全局修改本场名称，并勾选“下次自动识别”登记。
本场临时 Speaker 编号与稳定人物 ID 分开保存；人工名称编辑独立于声纹模型。

```dotenv
AI_MEETING_SPEAKER_IDENTITY_PROVIDER=xfyun
AI_MEETING_XFYUN_VOICEPRINT_APP_ID=replace-with-xfyun-app-id
AI_MEETING_XFYUN_VOICEPRINT_API_KEY=replace-with-xfyun-api-key
AI_MEETING_XFYUN_VOICEPRINT_API_SECRET=replace-with-xfyun-api-secret
AI_MEETING_XFYUN_VOICEPRINT_GROUP_PREFIX=partner_
AI_MEETING_FFMPEG=ffmpeg
```

前置条件是已开放 `speaker.diarization.session`，本场实际有分人信息并保留录音。
在 Profile optional 和权益列表加入 `speaker.identity.cross_meeting`；该跨场能力由
已注册 Provider 声明，**不要加入实时 ASR 的模型能力列表**。
匹配阈值等可选项见[声纹插件说明](../backend-reference/README.md#可选跨会议声纹参考插件)。

默认参考 Web 读取及修改本场名字／附带登记均使用有效结果链接，无需再次登录。
伙伴可替换后端编辑授权策略，选择所有者登录规则；独立人物库管理仍需认证。
先保存本场名称，再执行可选登记；失败提示不丢失已保存名称。未配置 Provider 时，
仍能修改带本场编号的原文段，自动识别选项隐藏。
没有可匹配人物时保持未识别，短片段或识别失败不伪造人物，也不阻断其余结果就绪。
其他供应商使用 `SpeakerIdentityProvider` 扩展，伙伴自行管理人物库和业务授权。

### 3.5 组合示例：四类能力都开放

以下是“听悟原生转写／翻译／分人＋百炼总结＋讯飞身份”的能力声明组合。
需同时填写前述对应模型资源；仅复制列表无法替代接入和服务开通。

```dotenv
AI_MEETING_PROFILE_REQUIRED_CAPABILITIES=transcript.realtime
AI_MEETING_PROFILE_OPTIONAL_CAPABILITIES=translation.realtime,summary.structured,speaker.diarization.session,speaker.identity.cross_meeting
AI_MEETING_ENTITLEMENT_SUPPORTED_CAPABILITIES=transcript.realtime,translation.realtime,summary.structured,speaker.diarization.session,speaker.identity.cross_meeting
AI_MEETING_MODEL_CAPABILITIES=transcript.realtime,translation.realtime,summary.structured,speaker.diarization.session
AI_MEETING_MODEL_LANGUAGES=zh-CN,en-US,ja-JP,ko-KR,de-DE,fr-FR,ru-RU
```

只提供字幕＋翻译时，required保留 `transcript.realtime`，optional仅保留
`translation.realtime`，权益和模型列表均为 `transcript.realtime,translation.realtime`；
各列表不保留总结、分人或跨场身份。
设置 `AI_MEETING_BAILIAN_SUMMARY_ENABLED=false` 和 `AI_MEETING_SPEAKER_IDENTITY_PROVIDER=none`，
不填写总结或声纹凭据。
按此思路声明实际交付能力，无须为缺少的模型造占位实现。

## 4. 不提供某项能力时，最终看见什么

| 缺少／未请求的部分 | Android | 会后默认 Web |
| --- | --- | --- |
| 所有 ASR | 默认产品不能开始；只改配置无法变成纯录音产品 | 没有按当前主线生成的新会议结果 |
| 翻译 | 互译功能不可用，普通字幕继续 | 显示原文；无实际译文产物就隐藏译文区 |
| 总结 | 无纪要选项／不调用总结服务 | 原文和可用录音照常，无空总结区 |
| 本场分人 | 不提供按发言人区分的结果 | 原文保留；不虚构人物列表 |
| 跨会议声纹 | 不请求跨场识别；不影响实时字幕 | 保留本场编号和人工改名；无自动识别选项及跨场人物 |
| 留存录音 | 实时采音／字幕照常，停止后不上传留存音频 | 没有播放器；参考跨场声纹依赖录音，因而也不请求 |
| 演示截图 | 不启动截图，无需屏幕授权 | 不显示演示区，其余结果照常 |
| 邮件／小程序／本地离线结果插件 | 默认仍可使用 Web 动作 | Web 正常，不会自动发送邮件或生成离线产品 |

录音使用本场 `artifactPolicy.retainAudio`，演示使用
`artifactPolicy.presentationCapture`；它们是留存／采集策略，不是模型能力开关。
参考 SDK 模式默认保留录音，是否在伙伴页面增加开关由自己的业务决定。

Web 根据最终 `MeetingManifest`（本场结果清单）里的 READY `ArtifactRef`（产物引用）
显示对应模块。先核对真实产物，再决定展示；声明了能力但未选择该功能也可能没有产物。
本表描述正常的“未提供／未请求”，不保证已声明必需模型的任意运行故障都能自动忽略。

## 5. 更换模型时，修改边界是什么

```text
伙伴后端发行器 --> 本场地址／短期材料 --> SDK 中匹配的 Adapter
                                               |
                                       标准事件／标准结果
                                               |
                                   既有 App 业务与 Web 渲染
```

| 替换对象 | 后端接缝 | Android 接缝 |
| --- | --- | --- |
| 实时 ASR／原生翻译 | `ModelSessionIssuer` | `ModelAdapter`，登记到 `ModelAdapterRegistry` |
| 独立文本翻译 | `TranslationSessionIssuer` | `TranslationAdapter` |
| 文本总结 | `SummarySessionIssuer` | `SummaryAdapter` |
| 会后声纹 | `SpeakerIdentityProvider` | 无须让 App 直接接声纹供应商；消费标准结果 |

按供应商实际接口适配握手、请求、事件／响应和停止时序，声明相同 Adapter ID、
能力和语言；更换已有实现的地址／凭据可以用配置，新增 API 格式须增加实现，
不能把任意厂商名称填进 `AI_MEETING_MODEL_ADAPTER` 就自动接通。
厂商 JSON、语言短码与字段差异留在 Adapter／Provider，公共核心与页面只看标准结果。

默认结果 Web、本地离线 HTML 或自有结果平台也可独立替换，具体扩展见
[结果动作与 Web 扩展](partner-integration-guide.md#5-结果动作和-web-扩展)。

# 接入前准备清单

> 接入前 · 第 0/4 篇 ｜ 下一篇 → [从零接入指南](partner-getting-started.md) ｜ 全部文档见 [文档导航](README.md)

这篇回答一个问题：**动手之前，我到底要先准备什么。**

先看第二节的最小组合——多数人能靠它跑通第一场会议。缺什么，再到第三节按分组查。
第四节是“模型还没准备好”的替代路径：先用样例模型把链路跑通，再换自己的模型。

清单里的每一项都标了：必需还是可选、谁提供、拿不到会怎样、对应哪个配置。以本页为准，
其他文档里的资源表是同一份清单在具体步骤里的摘录。

## 一、先分清四类依赖

| 类别 | 你要准备 | 拿不到时的典型表现 |
| --- | --- | --- |
| 账号与凭据 | 模型服务的 Key、登录账号、会话密钥 | 能启动但开不了会，或开会拿不到字幕 |
| 基础设施 | MySQL、设备可达的主机、TLS 证书、存储 | 后端起不来，或设备连不上 |
| 开发环境 | JDK、Android SDK、Node、Python 等 | 构建阶段就失败 |
| 设备 | Android 14 真机、SDK 对应的设备 AAR | App 装不上或没有真实音源 |

## 二、最小基础设施组合

下面五项是最容易被漏掉的基础设施，凑齐它们才具备跑第一场会的前提。登录账号、会话密钥、TLS 入口、可写的结果存储和结果页地址同样是必需运行配置，见第三节；不要以为只准备这五项就能直接开会：

| # | 依赖 | 说明 | 对应位置 |
| --- | --- | --- | --- |
| 1 | 源码 | 本仓库完整目录，含 `meeting-android/libs/ai-meeting-device.aar` | — |
| 2 | MySQL 5.7 兼容实例 | 一个专用库和可初始化／读写的账号 | `DATABASE_URL`／`DATABASE_USER`／`DATABASE_PASSWORD` |
| 3 | 一台设备可达的主机 | 跑后端和静态 Web；设备能访问它的局域网地址 | `AI_MEETING_HTTP_BIND`、静态站端口 |
| 4 | 一个实时语音模型 | 换凭据用听悟即可；换成自己的协议要另写 Adapter（见[教程](custom-model-adapter.md)）；还没模型时先用第四节的样例模型验证接线 | `AI_MEETING_MODEL_ADAPTER` 及其配套密钥 |
| 5 | Android 14 设备 | 麦克风、网络、ADB；与设备 AAR 版本匹配 | `deployment.local.properties` 里的后端地址 |

下面这些**第一场会可以先不准备**：总结（百炼）、跨会议声纹（讯飞）、对象存储／OSS、
邮件与小程序接收、Docker 交付部署。缺了对应能力，界面按实际能力隐藏，不会伪造结果。

## 三、完整清单

### 3.1 账号与凭据

| 依赖 | 必需性 | 谁提供 | 拿不到会怎样 | 配置项 |
| --- | --- | --- | --- | --- |
| 实时语音识别（ASR） | **必需**（可替换，不能没有） | 你或第三方 | 开会拿不到字幕，这是参考流程的核心能力 | `AI_MEETING_MODEL_ADAPTER`、听悟 `AI_MEETING_TINGWU_APP_KEY`／`ACCESS_KEY_ID`／`ACCESS_KEY_SECRET` |
| 实时翻译 | 可选 | 听悟原生，或你自己的翻译服务 | 互译入口不可用，字幕仍在；Web 无译文区 | `AI_MEETING_MODEL_CAPABILITIES` 含 `translation.realtime` |
| 本场发言人分离 | 可选 | 听悟同一会话 | 不生成分人结果，不阻塞字幕 | 同上，含 `speaker.diarization.session` |
| 结构化总结 | 可选 | 百炼应用 ID + DashScope Key | 没有总结开关与总结区，列表标题退回“未命名会议” | `AI_MEETING_BAILIAN_APP_ID`、`DASHSCOPE_API_KEY`、`AI_MEETING_BAILIAN_SUMMARY_ENABLED` |
| 跨会议声纹 | 可选 | 讯飞 AppID／APIKey／APISecret | 只有本场编号与人工改名，无“下次自动识别” | `AI_MEETING_XFYUN_VOICEPRINT_*`、`AI_MEETING_SPEAKER_IDENTITY_PROVIDER` |
| 登录身份 | **必需** | 参考实现自带预置账号；产品要接自己的 IdP | 无法登录，开不了会 | `REFERENCE_DEV_LOGIN_*`、`REFERENCE_DEV_IDENTITY_*` |
| 会话／分页密钥 | **必需** | 你自己生成 | 后端启动校验失败 | `REFERENCE_DEV_IDENTITY_BEARER_TOKEN`、`AI_MEETING_PAGE_TOKEN_SECRET` |
| 权益与计费 | 产品阶段必需 | 自己实现（参考实现是固定策略） | 参考环境能跑，产品环境无法计费 | `AI_MEETING_ENTITLEMENT_*`、`EntitlementProvider` 扩展点 |

### 3.2 基础设施

| 依赖 | 必需性 | 说明 | 配置项 |
| --- | --- | --- | --- |
| MySQL 5.7 兼容实例 | **必需** | 参考方案只交付一份 V001 初始化；不建库、不建账号 | `DATABASE_URL` 等 |
| 设备可达的主机 | **必需** | 后端与静态 Web 都要能被设备访问；设备自己的 `127.0.0.1` 指设备自身 | `AI_MEETING_HTTP_BIND` |
| TLS 证书 | **必需** | App 登录入口必须是 HTTPS；开发期可自签，见 [本机局域网 HTTPS](local-lan-https.md) | 调试 CA 固定读取 `app/src/debug/res/raw/reference_debug_ca.pem`，不是 deployment 配置项 |
| 结果页静态站 | **必需** | `web-reference` 构建产物 + 生成的 `config.json` | `deployment.local.properties` 的 `resultWebUrl` |
| 存储（产物、录音） | **必需** | 本机 file 存储就够；对象存储可选 | `AI_MEETING_ARTIFACT_STORE`、`AI_MEETING_ARTIFACT_ROOT` |
| 对象存储（S3／OSS） | 可选 | 参考实现不依赖 CZUR 的 OSS 账号 | 存储实现所在配置 |
| FFmpeg | 可选 | 只有启用参考声纹插件时才需要；交付镜像已包含 | — |
| 反向代理／域名 | 正式部署需要 | 开发期用自签 TLS 入口代替 | — |

### 3.3 开发环境

| 工具 | 版本下限 | 用来做什么 |
| --- | --- | --- |
| JDK | 21 | 构建并运行后端 |
| Maven | 3.9.x | 后端构建；Android 走自带 Gradle Wrapper |
| Android Studio、Android SDK | SDK Platform 35、ADB | 构建 App、装机调试；最低 Android 14／API 34 |
| Node.js／npm | 18+（建议 22+） | 构建结果页静态产物 |
| Python 3 | 3.x | 启动本机静态 Web、设备脚本、可选样例模型 |
| MySQL 命令行客户端、curl | — | 执行 V001 初始化、检查服务 |
| OpenSSL | 1.1.1+ | 开发期生成自签证书；已有可信证书时可跳过 |
| Docker | 可选 | 只用于交付部署路径，本机开发验证不需要 |

### 3.4 设备

产品级能力优先用 **CZUR StarryHub 系列设备**（Android 14／API 34）。
设备 AAR 里的系统级采音、投屏截图和窗口排除依赖这套固件与系统接口，
换成普通 Android 14 手机也能跑基础链路，但那些系统级能力不保证。

| 依赖 | 说明 |
| --- | --- |
| 测试设备 | 优先 StarryHub 系列设备；普通 Android 14／API 34 设备只能验证基础字幕链路 |
| 设备 AAR | 已随仓：`meeting-android/libs/ai-meeting-device.aar`（0.5.0），与设备固件成对。换版本时替换该文件，Gradle 只读这个路径 |
| 系统能力 | 录音、悬浮窗；演示截图另需屏幕采集授权。系统共享音源（`CZUR_BROKER`）与系统级窗口排除只在兼容的 StarryHub 固件上提供，普通设备回退到标准麦克风 |
| 机型差异 | 音频来源用 `AudioInputMode` 反映：`MIC`（普通 Android 或旧固件）、`CZUR_BROKER`（兼容 CZUR 共享音频）、`DEBUG_WAV`（调试） |

## 四、模型还没准备好：先跑样例链路

听悟还没开通、自己的模型也没接好时，可以用仓库自带的协议样例先把整条链路跑通。
**它只返回固定的假字幕，用来验证接线，不代表任何识别质量。**

```bash
# 1) 开发机装样例服务依赖并启动（地址要设备可达）
python3 -m pip install 'websockets>=13,<14'
python3 tools/sample-json-ws-model.py --host 0.0.0.0 --port 19091

# 2) 后端配置：模板默认就是这条 adapter 路径。
#    配置文件放仓外，例如 ~/.config/ai-meeting/reference-client.env，
#    并用 REFERENCE_ENV_FILE 指向它；不要复制成仓库里的 tools/reference-client.env。
AI_MEETING_MODEL_ADAPTER=sample-json-ws
AI_MEETING_SAMPLE_JSON_WS_ENDPOINT=ws://<开发机局域网 IP>:19091/model
```

然后按[从零接入指南](partner-getting-started.md)第 2–6 步走完建库、起后端、起 HTTPS、
装 APK、开会。看到字幕刷出来，说明“音源 → 模型 → 归一化 → 界面 → 结果页”这条路是通的；
接下来把 Adapter 和密钥换成自己的模型即可。写自己的 Adapter 见
[自定义模型 Adapter 教程](custom-model-adapter.md)，替换边界见
[模型与能力配置指南第 5 节](model-and-capability-guide.md#5-更换模型时修改边界是什么)
和[扩展开发手册第 4 节](partner-integration-guide.md#4-模型和能力扩展)。

## 五、准备完成后的环境自检

这一节只核对“资源和配置是否就位”，不要求后端已经在跑、App 已经装好或账号已经登录；
这些属于接入后的验收，见下一节。逐条过一遍，全绿再进入接入步骤：

- [ ] `mysql -h <host> -u <user> -p <db>` 能连上，且已通过 `deploy/migrate.sh`
      （本机模板用 `bash tools/migrate-reference-client.sh`）登记 `V001__initial_schema`；
      只手工执行 SQL 文件不算完成。
- [ ] 后端运行配置在仓外（默认 `~/.config/ai-meeting/reference-client.env`，或用
      `REFERENCE_ENV_FILE` 指定）；仓库里没有 `tools/reference-client.env`。
      `python3 tools/check-public-boundary.py` 输出 `PUBLIC_BOUNDARY_OK`。
- [ ] `deployment.local.properties` 里 `backendUrl`、`resultWebUrl` 已填，构建时不再报“缺少部署配置”。
- [ ] `backendUrl` 是设备可达的 HTTPS 地址；开发自签证书已按
      [本机局域网 HTTPS](local-lan-https.md) 放到 Debug CA 路径，正式环境由部署方提供证书。
- [ ] 模型端点从设备可达（`ws://` 或你的供应商地址），不能用设备自己的 `127.0.0.1`。
- [ ] 结果页构建产物里的 `config.json` 已指向浏览器可达的后端 `apiBaseUrl`。

## 六、接入后验收（不属于准备条件）

准备就绪后，按[从零接入指南](partner-getting-started.md)第 2–6 步完成建库、启动、
装 APK 和第一场会议，再按[当前支持范围与验收清单](validation-scope.md#目标环境的最小验收)
逐项验收。最低限度的入口检查是：

- [ ] 后端启动后 `curl http://127.0.0.1:<端口>/v1/capabilities` 返回当前能力与语言，
      设备浏览器也能打开 `https://<主机>:8443/v1/capabilities`（自签证书需先在设备信任）。
- [ ] 参考 APK 安装成功，登录后能看到“开始”可用。
- [ ] 真实开一场会，确认字幕、正常停止和结果页可回看；可选能力按实际声明逐项核对。

入口检查没过时，对应文档：[数据库与初始化](partner-getting-started.md#第-2-步准备数据库并执行唯一初始化脚本)、
[启动后端](partner-getting-started.md#第-3-步启动后端并确认健康)、
[HTTPS 与 Web](partner-getting-started.md#第-4-步准备设备可达的-https-和-web)、
[构建与安装](partner-getting-started.md#第-5-步构建并安装真实-sdk-版本-app)。

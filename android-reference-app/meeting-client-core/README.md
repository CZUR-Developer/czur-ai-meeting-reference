# 会议客户端公共核心

本模块是平台无关的普通 Java 17 JAR，供参考 App 和 CZUR SDK 使用同一份模型适配与显示规则。
Android 控件、权限申请、音源及真实模型网络传输不在本模块。

当前包含：

- `client.provider`：默认听悟、第二格式 JSON 样例、百炼总结的唯一公开解析实现。
- `ModelAdapterRegistry`：按 Server 下发的 `adapterId` 选择工厂，每场会议创建独立实例。
- `MeetingCapabilityState`：Create 响应的本场能力决策，不由厂商名字推断。
- `client.display`：共享显示状态、句开始方向快照，以及国内 A/B 多语种兜底。

伙伴只需新增 Adapter 实现，并在会议开始前注册工厂和可解析能力：

```java
ModelAdapterRegistry adapters = new ModelAdapterRegistry(); // 自带听悟、样例和百炼。
adapters.register("partner-json", PartnerAdapter::new, Set.of("transcript.realtime"));
ModelAdapter adapter = adapters.create(grant.adapterId()); // 每次取得新实例。
```

当前实时传输边界为 WebSocket 加 16 kHz/单声道/PCM16。Adapter 只归一化消息，
不代表任意传输、编码或私有音频协议均已支持。会议模型地址、短期访问材料与本场权限由 Server 决定。

构建：

```bash
./gradlew :model-runtime-contract:jar :meeting-client-core:jar :meeting-client-core:test
```

产物是 `build/libs/meeting-client-core-0.1.0.jar`，不内带 `model-runtime-contract` 或 `org.json`。
Mock App 直接依赖本模块；SDK App 使用私有 AAR 内带的同一 JAR，不再依赖本工程模块。
SDK 从公共契约 JAR 以 `compileOnly` 编译，App 打包唯一一份契约；Android 提供 JSON 类。
`api(project(":model-runtime-contract"))` 仅表达源码模块的编译依赖，不生成合并 JAR。

P6-5 的抽离和国内规则补齐分开验收；Server/完整会议组合入口在 P6-6 实装，参考 App 的大迁移在 P6-7。

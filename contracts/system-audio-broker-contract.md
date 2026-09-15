# SystemAudioBroker 最小控制面与数据面契约（草案）

> 状态：实现前草案。用于 Framework、音频服务、Android SDK 和测试并行开发；不是合作伙伴公开接口。

## 1. 边界

SystemAudioBroker 只负责：

- 选择并发布一条已经确定格式的系统音频流；
- 为多个消费者提供独立读取位置；
- 管理订阅的打开、关闭、失效和数据连续性状态。

它不负责账号、会议、模型、存储、计费或网络请求。普通 `AudioRecord` 路径保持原有行为；只有接入 SDK 的应用使用该扩展能力。

## 2. 控制面（Binder/AIDL 语义）

方法名可以按 Android 工程规范调整，但输入输出语义必须保持一致：

```text
openSubscription(AudioRequest request) -> AudioStreamDescriptor
renewLease(SubscriptionId id, LeaseToken token) -> Lease
getStreamState(SubscriptionId id) -> AudioStreamState
closeSubscription(SubscriptionId id, CloseReason reason)
```

`AudioStreamDescriptor`：

```text
subscriptionId       当前订阅 ID，不跨 Broker 重启复用
dataQueueDescriptor  FMQ 描述符（由 SDK 隐藏）
dataReadySignal      数据可读/状态变化通知（由 SDK 隐藏）
format               采样率、声道、编码和帧时长
initialSequence      首个可读帧序号
generation           输入源或格式变更代数
leaseExpiresAt       订阅授权到期时间
```

接口不携带 `meetingId`、用户 Token、模型地址、存储地址或计费信息。

## 3. 状态与错误

```text
REQUESTED --校验通过--> OPENING --数据面就绪--> ACTIVE
REQUESTED --失败-----> REJECTED
ACTIVE --消费者落后--> GAP       （只影响当前订阅）
ACTIVE/GAP --撤销----> REVOKED
ACTIVE/GAP --关闭----> CLOSED
```

`GAP` 是音频连续性状态，不是会议状态。下一帧必须带 `GAP_BEFORE` 和丢失帧数。权限不足、格式不支持、订阅数超限、lease 过期、Broker 重启分别使用稳定错误码，不能依赖字符串日志判断。

## 4. 数据面规则

- 一个 active Producer；多个消费者拥有独立读游标；
- Producer 不等待慢消费者，消费者落后时只推进自己的游标并报告 gap；
- FMQ `kUnsynchronizedWrite` 是首版候选，SDK 负责等待通知和阻塞式 `read()`；
- 共享数据只读，应用不能修改 Producer 写入内容；
- 帧序号单调递增，格式或来源变化递增 `generation` 并发送 `FORMAT_CHANGED` 或 `SOURCE_CHANGED`；
- 消费者进程死亡、主动关闭、lease 超时和 Broker 重启都回收对应订阅；其他订阅不受影响；
- Broker 不记录 PCM 和会议正文。

## 5. SDK 对应用的最小接口

```text
SystemSharedAudioSource.open(request) -> AudioSource
AudioSource.start()
AudioSource.read(buffer, timeout) -> ReadResult
AudioSource.stop()
AudioSource.close()
```

应用只处理标准 PCM、序号、时间戳和 gap；不直接操作 FMQ 描述符、通知句柄或 Binder lease。`read()` 在无数据时阻塞等待，在 `stop()`、关闭、撤销或 Broker 重启时返回明确状态。

## 6. 首版验收

必须在主机模型和目标设备分别验证：

1. 1～8 个消费者下快速消费者无 gap；
2. 慢消费者只影响自己；
3. 消费者关闭或进程退出不影响其他订阅；
4. 16 kHz、48 kHz 的格式声明和帧大小一致；
5. `stop()` 能唤醒阻塞读取；
6. Broker 重启后旧订阅失效，重新打开可获得新订阅；
7. 普通 `AudioRecord` 行为与未启用 Broker 时一致。

消费者上限、缓冲时长、lease 时长、P95/P99 延迟和最大 gap 不在本草案中预设，必须由设备压测结果冻结并写入 Product Profile。

## 7. 实现分工

| 交付包 | 内容 |
| --- | --- |
| `broker-control` | 权限、订阅表、lease、Binder death、状态和错误码 |
| `broker-dataplane` | 单一 Producer、FMQ、游标、gap、格式/generation |
| `audio-source-sdk` | FMQ 封装、阻塞式 `read()`、停止和错误映射 |
| `broker-conformance` | 多消费者、慢读、退出、重启、格式和性能测试 |

在该契约完成评审前，只能运行 Mock 和 POC；不能让生产 App 依赖未冻结字段。

## 8. 当前代码接入点（只读调查）

当前 RK3576 Android 14 共用代码中的旧数据面为：

```text
AudioRecord.cpp
  ├─ IAudioAiService.copyMicData(byte[], size)
  └─ cloudasr_mic.copyMicData(byte[], size)
       ↓
CloudAsrService MicCompetitor / RingBuffer
```

对应位置：

- `frameworks/av/media/libaudioclient/AudioRecord.cpp`：HDMI-IN 判断、`audioai`/`cloudasr_mic` Binder 查找和逐帧 `byte[]` 推送；
- `frameworks/base/core/java/android/audioai/IAudioAiService.aidl`：旧综合音频 AI Binder；
- `vendor/rockchip/common/apps/CloudAsrService/.../IMicDataReceiver.aidl`：旧 `cloudasr_mic` 接收接口；
- `CloudAsrService` 的 `MicCompetitor`/`RingBuffer`：旧服务内的竞争处理和缓冲。

最小实现的插入边界应位于 AudioFlinger/音频服务的服务端采集点，而不是继续在 `AudioRecord.cpp` 增加业务分支。迁移期保留旧调用路径；新 Broker 通过独立接口和 SDK 启用，普通 `AudioRecord` 不改变。Framework 源码修改和编译属于 AOSP 工作区，不在本仓库执行。

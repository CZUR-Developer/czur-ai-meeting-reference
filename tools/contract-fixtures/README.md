# 公开契约 Fixture 检查

`contracts/fixtures/` 的 `realtime-events.jsonl` 和 `capability-scenarios.jsonl`
只包含八类会议级持久事件；P2 ASR-only 样例使用 `streamId = meetingId`，
`eventSequence` 按会议连续从 1 开始。`realtime-wss-transient.jsonl` 单列
ACK/heartbeat，使用 `eventSequence="0"` 且无 `eventId`，不属于 REST 补拉事件。
`meeting-global-reset.invalid.jsonl` 是泛协议多流计数负例，不开放 P2 第二条流。

`audio-frame-checksum.valid.json` 与 `.invalid.json` 只校验 `AAEC/w==` 原始
音频字节的线上 SHA-256，不定义服务端内部完整帧 `contentDigest`。其它
`.invalid.json` 文件各自只植入一种故障。
主事件中的翻译和声纹正文只展示通用协议形状，不表示 P2 Mock Profile 已启用
这些能力；能力仍以本场授权与执行计划为准。

在仓库根目录运行：

```bash
python3 tools/contract-fixtures/validate.py
```

输出 `CONTRACT_FIXTURES_OK` 只证明离线样例通过，不证明 WSS、持久 ACK、
Protobuf 编解码或真实服务行为。Create v1 Fixture/V013 首次响应投影不在本步修改。

# 参考实现来源说明

`src/main` 和 `src/test` 中的会议状态领域代码选自 `czur-cloud/reference-backend/`
已验收的 `MeetingState*`、`MeetingEvent`、`MeetingAggregate*` 实现。该目录仅保留
与协议无关的公开领域逻辑；HTTP、生产账号、模型、存储和计费实现不在此仓库。
后续修改应保持小提交，并在提交说明中注明对应来源版本。

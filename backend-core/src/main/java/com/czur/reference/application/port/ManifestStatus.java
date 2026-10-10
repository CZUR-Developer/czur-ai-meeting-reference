package com.czur.reference.application.port;

/**
 * 会议最终产物清单的公开状态。
 *
 * <p>枚举值与 OpenAPI/Manifest Schema 保持一致。它描述清单及其必选产物的
 * 生成进度，不代替 {@code MeetingState}，也不表示某一个模型或存储插件的
 * 私有状态。</p>
 */
public enum ManifestStatus {
    /** 尚未开始生成最终产物。 */
    NOT_STARTED,
    /** 正在生成或等待最终产物。 */
    GENERATING,
    /** 必选产物已经可读取。 */
    READY,
    /** 产物已经可读取，但至少存在一条降级或缺失能力告警。 */
    READY_WITH_WARNINGS,
    /** 生成已结束但至少一个必选产物失败。 */
    FAILED,
    /** 会议删除已受理，清单正在等待清理 Worker 收口。 */
    DELETION_PENDING,
    /** 会议删除流程已完成清理，清单不再可读取。 */
    DELETED
}

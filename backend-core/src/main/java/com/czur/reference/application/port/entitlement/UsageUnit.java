package com.czur.reference.application.port.entitlement;

/**
 * 参考后端可持久化和对账的标准用量单位。
 *
 * <p>首版只冻结会议持续时间。新增单位必须同时定义预估、最终事实来源、
 * 幂等摘要和迁移语义，不能把模型厂商自己的计费字段直接加入公共端口。</p>
 */
public enum UsageUnit {
    MEETING_DURATION_MS
}

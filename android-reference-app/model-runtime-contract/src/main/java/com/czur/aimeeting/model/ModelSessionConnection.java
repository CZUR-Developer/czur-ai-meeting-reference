package com.czur.aimeeting.model;

import java.util.Objects;

/**
 * 同一业务会议下一次模型连接所需的材料和全新协议解析实例。
 *
 * <p>模型供应商重新签发任务时，片段序号和时间可能从零开始；因此不能沿用旧
 * Adapter 内部的 partial／final 状态。此对象只替换模型连接，不表示重新创建
 * 业务会议、重新打开音源或重新开始录音。连接代次与录音时间偏移由 Runtime 管理。</p>
 *
 * @param grant Server 为原 meetingId 重新下发的完整连接材料，不得写入日志。
 * @param adapter 注册工厂为该材料创建的新实例，不与前一个连接共享修订状态。
 */
public record ModelSessionConnection(ModelSessionGrant grant, ModelAdapter adapter) {
    public ModelSessionConnection {
        Objects.requireNonNull(grant, "grant");
        Objects.requireNonNull(adapter, "adapter");
    }
}

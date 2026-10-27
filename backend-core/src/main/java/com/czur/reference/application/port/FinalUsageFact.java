package com.czur.reference.application.port;

import com.czur.reference.application.port.entitlement.UsageActual;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.domain.meeting.MeetingIdPolicy;

import java.time.Instant;
import java.util.Objects;

/**
 * 从已经持久化的输入接管事实固化出的最终用量事实。
 *
 * <p>最终用量事实是一次性确定的值：标准首版用量为
 * {@link UsageUnit#MEETING_DURATION_MS}，数量直接取
 * {@link FinalizationInputFact#audioDurationMs()}。后续重试只能把这份事实重新
 * 提交给 {@link FinalUsageFactStore}，不能重新读取当前时钟、重新计算时长，或
 * 根据当前模型状态生成另一份用量。</p>
 *
 * <p>工厂方法同时复制输入事实中的会议标识、输入绑定、内容摘要和接管时间，
 * 形成最终用量与 durable input 的完整来源绑定。该类型不调用数据库、模型或
 * 计费服务；这些动作由更高层的工作单元负责。所有值均不可变，适合跨进程
 * 重试和进程重启后的事实重放。</p>
 *
 * @param meetingId 来源会议标识
 * @param actual 标准累计用量；首版单位固定为会议时长毫秒
 * @param sourceInputBinding 来源输入事实的稳定绑定标识
 * @param sourceInputDigest 来源输入事实的完整内容摘要
 * @param sourceAcceptedAt 来源输入事实完成接管的稳定时间
 */
public final class FinalUsageFact {
    private final String meetingId;
    private final UsageActual actual;
    private final String sourceInputBinding;
    private final String sourceInputDigest;
    private final Instant sourceAcceptedAt;

    private FinalUsageFact(
            String meetingId,
            UsageActual actual,
            String sourceInputBinding,
            String sourceInputDigest,
            Instant sourceAcceptedAt) {
        requireText("meetingId", meetingId);
        Objects.requireNonNull(actual, "actual");
        requireText("sourceInputBinding", sourceInputBinding);
        requireText("sourceInputDigest", sourceInputDigest);
        Objects.requireNonNull(sourceAcceptedAt, "sourceAcceptedAt");
        if (actual.unit() != UsageUnit.MEETING_DURATION_MS) {
            throw new IllegalArgumentException(
                    "final usage fact must use meeting duration milliseconds");
        }
        this.meetingId = meetingId;
        this.actual = actual;
        this.sourceInputBinding = sourceInputBinding;
        this.sourceInputDigest = sourceInputDigest;
        this.sourceAcceptedAt = sourceAcceptedAt;
    }

    /**
     * 从一个已经持久化的输入接管事实生成最终用量事实。
     *
     * <p>该方法是本类型唯一的业务构造入口。它不读取当前时间，
     * {@code sourceAcceptedAt} 完全来自输入事实；因此同一输入事实在首次收口和
     * 任意重试中都会得到相同的 {@link #equals(Object)} 值。</p>
     *
     * @param inputFact 已持久化且已完成接管的输入事实
     * @return 与输入事实完整绑定的最终用量事实
     * @throws NullPointerException inputFact 为空
     */
    public static FinalUsageFact fromInput(FinalizationInputFact inputFact) {
        Objects.requireNonNull(inputFact, "inputFact");
        return new FinalUsageFact(
                inputFact.meetingId(),
                new UsageActual(UsageUnit.MEETING_DURATION_MS, inputFact.audioDurationMs()),
                inputFact.inputBinding(),
                inputFact.contentDigest(),
                inputFact.acceptedAt());
    }

    /**
     * 从 workflow checkpoint 已冻结的来源材料恢复最终用量事实。
     *
     * <p>该入口只供 durable checkpoint 恢复使用，不重新读取 V008、模型状态或
     * 当前时钟。构造不变量与 {@link #fromInput(FinalizationInputFact)} 完全一致，
     * 因而恢复结果可与 Admission 首次形成的 V009 值做逐字段等值比较。</p>
     */
    public static FinalUsageFact restore(
            String meetingId,
            UsageActual actual,
            String sourceInputBinding,
            String sourceInputDigest,
            Instant sourceAcceptedAt) {
        MeetingIdPolicy.requireValid(meetingId);
        MeetingIdPolicy.utf8Length(sourceInputBinding);
        MeetingIdPolicy.utf8Length(sourceInputDigest);
        Objects.requireNonNull(sourceAcceptedAt, "sourceAcceptedAt");
        if (sourceAcceptedAt.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(
                    "sourceAcceptedAt must use database microsecond precision");
        }
        return new FinalUsageFact(
                meetingId,
                actual,
                sourceInputBinding,
                sourceInputDigest,
                sourceAcceptedAt);
    }

    public String meetingId() {
        return meetingId;
    }

    /** 返回兼容权益端口的标准累计用量。 */
    public UsageActual actual() {
        return actual;
    }

    public String sourceInputBinding() {
        return sourceInputBinding;
    }

    public String sourceInputDigest() {
        return sourceInputDigest;
    }

    public Instant sourceAcceptedAt() {
        return sourceAcceptedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FinalUsageFact that)) {
            return false;
        }
        return meetingId.equals(that.meetingId)
                && actual.equals(that.actual)
                && sourceInputBinding.equals(that.sourceInputBinding)
                && sourceInputDigest.equals(that.sourceInputDigest)
                && sourceAcceptedAt.equals(that.sourceAcceptedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(meetingId, actual, sourceInputBinding,
                sourceInputDigest, sourceAcceptedAt);
    }

    /** 只输出用量数值和时间，隐藏会议、输入绑定和摘要正文。 */
    @Override
    public String toString() {
        return "FinalUsageFact[meetingId=redacted"
                + ", actualUnit=" + actual.unit()
                + ", actualAmount=" + actual.amount()
                + ", sourceInputBinding=redacted"
                + ", sourceInputDigestPresent=true"
                + ", sourceAcceptedAt=" + sourceAcceptedAt + ']';
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}

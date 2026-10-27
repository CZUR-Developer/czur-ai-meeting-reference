package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.entitlement.UsageReservation;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * owned 会议创建与事后恢复共用的首次预占事实校验器。
 *
 * <p>首次 reserve 结果是创建链的稳定事实源。当前 Reservation
 * 后续可以被绑定、提交或取消，因此本类只比较创建时已冻结且
 * 不允许随状态机演进的字段；当前状态、绑定、终态时间和版本由
 * 各调用场景根据自己的语义额外判断。这样创建事务与恢复查询不会
 * 各自维护一份容易漂移的不可变字段列表。</p>
 *
 * <p>数据库时间列按微秒持久化。时间比较统一在
 * 这里截断到微秒，避免调用方因 JDBC 往返精度不同做出不一致的
 * 判定。</p>
 */
final class OwnedMeetingCreationFacts {
    private OwnedMeetingCreationFacts() {
    }

    /**
     * 判断当前 Reservation 是否仍属于同一份首次预占事实。
     *
     * <p>本方法故意不比较 {@code state/version/bound/actual/terminal}
     * 字段：它们是合法可演进事实。调用方必须在本方法之外明确声明
     * 当前场景允许的状态与版本。</p>
     */
    static boolean sameInitialReservationFacts(
            UsageReservation current,
            UsageReservation firstReserved) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(firstReserved, "firstReserved");
        return current.reservationId().equals(firstReserved.reservationId())
                && current.deploymentScope().equals(firstReserved.deploymentScope())
                && current.subjectScope().equals(firstReserved.subjectScope())
                && current.grantId().equals(firstReserved.grantId())
                && current.expectedMeetingId().equals(firstReserved.expectedMeetingId())
                && current.reservedUsage().equals(firstReserved.reservedUsage())
                && samePersistentInstant(current.createdAt(), firstReserved.createdAt())
                && samePersistentInstant(current.expiresAt(), firstReserved.expiresAt());
    }

    /** 以数据库实际持久化精度比较两个非空时间事实。 */
    static boolean samePersistentInstant(Instant left, Instant right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        return left.truncatedTo(ChronoUnit.MICROS)
                .equals(right.truncatedTo(ChronoUnit.MICROS));
    }
}

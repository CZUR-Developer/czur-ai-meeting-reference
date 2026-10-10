package com.czur.reference.application.port;

import java.util.Objects;

/**
 * 只读取得 V008 中已经提交的完整输入赢家。
 *
 * <p>Admission 首次提交与 receipt 同事务；并发 Reconciler 或提交确认未知时，
 * 调用方必须从这里取回原始 {@code acceptedAt} 等完整值，再调用 Admission 的
 * 只读恢复，不能用新的时钟构造一个不同候选。实现须区分确实没有事实、事实损坏
 * 和数据库暂时不可读；不允许用内存缓存代替数据库赢家。</p>
 */
public interface AcceptedInputFactLookup {
    /** @return 当前已提交的 V008 赢家；不创建输入事实或 receipt。 */
    Result find(String meetingId);

    sealed interface Result permits Found, Missing, Inconsistent, Unavailable { }

    record Found(FinalizationInputFact fact) implements Result {
        public Found { Objects.requireNonNull(fact, "fact"); }
        @Override public String toString() { return "Found[fact=redacted]"; }
    }

    record Missing() implements Result { }
    record Inconsistent() implements Result { }
    record Unavailable() implements Result { }
}

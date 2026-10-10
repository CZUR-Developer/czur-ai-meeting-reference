package com.czur.reference.application.port;

import com.czur.reference.application.finalization.FinalizationExecutionFact;

import java.util.Objects;
import java.util.Set;

/**
 * 实时首次 Start 专用的只读事实投影，不负责开启会议或验证连接 Token。
 *
 * <p>可信 deploymentScope 由服务端注入。实现须先核对会议归属，再从同一数据库
 * 快照读取 V011；若 V011 不存在，严格读取并核对 V013 首次赢家。已开始会议直接
 * 返回 V011 冻结事实，不把当前 Profile 或时钟冒充历史值。</p>
 */
public interface RealtimeStartFactsLookup {
    Result find(String trustedDeploymentScope, String meetingId);

    sealed interface Result permits First, Started, Rejected { }

    /** 尚无 V011；以下最小输入都已与 Meeting 和 V013 赢家交叉核对。 */
    record First(String subjectScope, String title, String profileId,
                 int profileRevision, Set<String> requestedCapabilities) implements Result {
        public First {
            Objects.requireNonNull(subjectScope, "subjectScope");
            Objects.requireNonNull(profileId, "profileId");
            if (profileRevision <= 0) throw new IllegalArgumentException("invalid profile");
            requestedCapabilities = Set.copyOf(requestedCapabilities);
        }

        @Override public String toString() { return "RealtimeStartFactsLookup.First[redacted]"; }
    }

    /** V011 是 startedAt、标题和完整 Profile 的权威来源。 */
    record Started(String subjectScope, FinalizationExecutionFact executionFact)
            implements Result {
        public Started {
            Objects.requireNonNull(subjectScope, "subjectScope");
            Objects.requireNonNull(executionFact, "executionFact");
        }

        @Override public String toString() { return "RealtimeStartFactsLookup.Started[redacted]"; }
    }

    /** 不携带会议、主体、Token、SQL 或动态异常文本的固定读取失败。 */
    record Rejected(Reason reason) implements Result {
        public Rejected { Objects.requireNonNull(reason, "reason"); }
    }

    enum Reason {
        MISSING, ACCESS_DENIED, CREATE_FACT_INCONSISTENT, FACT_INCONSISTENT, UNAVAILABLE
    }
}

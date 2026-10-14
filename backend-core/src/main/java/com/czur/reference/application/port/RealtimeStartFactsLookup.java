package com.czur.reference.application.port;

import com.czur.reference.application.finalization.FinalizationExecutionFact;

import java.util.Objects;
import java.util.Set;

/**
 * 实时首次 Start 专用的只读事实投影，不负责开启会议或验证连接 Token。
 *
 * <p>可信 deploymentScope 由服务端注入。实现须先核对会议归属，再从同一数据库
 * 快照读取 V011 和经交叉核对的 V013 首次赢家。已开始会议的标题、Profile 和执行
 * Plan 仍取 V011 冻结事实；演示请求只取首次 Create，不把当前配置或时钟冒充历史值。</p>
 */
public interface RealtimeStartFactsLookup {
    Result find(String trustedDeploymentScope, String meetingId);

    sealed interface Result permits First, Started, Rejected { }

    /** 尚无 V011；以下最小输入都已与 Meeting 和 V013 赢家交叉核对。 */
    record First(String subjectScope, String title, String profileId,
                 int profileRevision, Set<String> requestedCapabilities,
                 boolean presentationCaptureRequested) implements Result {
        public First {
            Objects.requireNonNull(subjectScope, "subjectScope");
            Objects.requireNonNull(profileId, "profileId");
            if (profileRevision <= 0) throw new IllegalArgumentException("invalid profile");
            requestedCapabilities = Set.copyOf(requestedCapabilities);
        }

        /** 原调用保持源兼容；未提供首次演示请求时不默认启用截图。 */
        public First(String subjectScope, String title, String profileId,
                int profileRevision, Set<String> requestedCapabilities) {
            this(subjectScope, title, profileId, profileRevision, requestedCapabilities, false);
        }

        @Override public String toString() { return "RealtimeStartFactsLookup.First[redacted]"; }
    }

    /** V011 仍是执行事实的权威来源；演示请求另取经核对的首次 Create。 */
    record Started(String subjectScope, FinalizationExecutionFact executionFact,
                   boolean presentationCaptureRequested)
            implements Result {
        public Started {
            Objects.requireNonNull(subjectScope, "subjectScope");
            Objects.requireNonNull(executionFact, "executionFact");
        }

        /** 原调用保持源兼容，不修改执行事实或其持久化协议。 */
        public Started(String subjectScope, FinalizationExecutionFact executionFact) {
            this(subjectScope, executionFact, false);
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

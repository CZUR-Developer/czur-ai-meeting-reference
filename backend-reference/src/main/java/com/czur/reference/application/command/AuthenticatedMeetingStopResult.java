package com.czur.reference.application.command;

import com.czur.reference.application.port.MeetingStatus;
import com.czur.reference.application.port.OwnedMeetingStopUnitOfWork;
import com.czur.reference.application.port.identity.IdentityProviderError;

import java.util.Objects;

/** 认证停止命令的脱敏稳定结果；成功快照来自停止事务后的权威查询。 */
public sealed interface AuthenticatedMeetingStopResult permits
        AuthenticatedMeetingStopResult.Success,
        AuthenticatedMeetingStopResult.IdentityFailure,
        AuthenticatedMeetingStopResult.StopFailure,
        AuthenticatedMeetingStopResult.QueryFailure,
        AuthenticatedMeetingStopResult.InternalFailure {

    record Success(OwnedMeetingStopUnitOfWork.SuccessCode code, MeetingStatus meeting)
            implements AuthenticatedMeetingStopResult {
        public Success {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(meeting, "meeting");
        }

        @Override
        public String toString() {
            return "StopSuccess[code=" + code + ", status=redacted]";
        }
    }

    record IdentityFailure(IdentityProviderError error) implements AuthenticatedMeetingStopResult {
        public IdentityFailure {
            Objects.requireNonNull(error, "error");
        }
    }

    record StopFailure(OwnedMeetingStopUnitOfWork.ErrorCode code)
            implements AuthenticatedMeetingStopResult {
        public StopFailure {
            Objects.requireNonNull(code, "code");
        }
    }

    enum QueryFailureCode { NOT_FOUND, ACCESS_DENIED, CONFLICT, UNAVAILABLE }

    record QueryFailure(QueryFailureCode code, boolean retryable)
            implements AuthenticatedMeetingStopResult {
        public QueryFailure {
            Objects.requireNonNull(code, "code");
            if (code != QueryFailureCode.UNAVAILABLE && retryable) {
                throw new IllegalArgumentException("only unavailable query can be retryable");
            }
        }
    }

    enum Stage { IDENTITY, STOP, QUERY, QUERY_INCONSISTENT }

    record InternalFailure(Stage stage) implements AuthenticatedMeetingStopResult {
        public InternalFailure {
            Objects.requireNonNull(stage, "stage");
        }
    }
}

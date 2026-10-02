package com.czur.reference.application.finalization;

import com.czur.reference.application.port.AcceptedInputFactLookup;
import com.czur.reference.application.port.CompletedAudioReadinessPort;
import com.czur.reference.application.port.FinalizationAdmissionUnitOfWork;
import com.czur.reference.application.port.FinalizationInputFact;
import com.czur.reference.application.port.InputState;
import com.czur.reference.application.port.MeetingInputClosureContextPort;
import com.czur.reference.domain.meeting.MeetingIdPolicy;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 将已提交的 Stop、V015 完整声明与音频、V013/V011 冻结计划汇合到一次 Admission。
 *
 * <p>Stop、{@code input_complete} 和声明后补齐的末帧都可以调用同一个
 * {@link #reconcile(String)}；以后同进程扫描 pending 会议也调用它。这里不拥有
 * HTTP/WSS、JDBC 连接、模型或 Worker 生命周期。先确认可信上下文及完整音频，
 * 然后调用既有 Admission 一次性保存 V008/V009、receipt 和 Workflow；材料未齐
 * 或存储不可读时绝不伪造 receipt。</p>
 *
 * <p>V008 是并发与提交结果未知时的唯一赢家：只要赢家已存在，就用它的完整字段
 * 调用只读 {@code recover}。首次候选的时间取调用时的微秒时钟且不早于 Stop 的
 * 持久更新时间；候选遇到竞争或提交未知后不会用新时钟再次构造不同候选。</p>
 *
 * <p>P2 仅支持 ASR-only，目标语言恒为空；能力计划是否真的没有启用翻译，必须
 * 由 {@link MeetingInputClosureContextPort} 在读取 V013/V011 时关闭式核验。</p>
 */
public final class MeetingInputReconciler {
    private static final Logger LOGGER = Logger.getLogger(MeetingInputReconciler.class.getName());

    private final MeetingInputClosureContextPort contextPort;
    private final AcceptedInputFactLookup acceptedInputLookup;
    private final CompletedAudioReadinessPort audioReadiness;
    private final FinalizationAdmissionUnitOfWork admission;
    private final Clock clock;

    public MeetingInputReconciler(MeetingInputClosureContextPort contextPort,
            AcceptedInputFactLookup acceptedInputLookup,
            CompletedAudioReadinessPort audioReadiness,
            FinalizationAdmissionUnitOfWork admission, Clock clock) {
        this.contextPort = Objects.requireNonNull(contextPort, "contextPort");
        this.acceptedInputLookup = Objects.requireNonNull(
                acceptedInputLookup, "acceptedInputLookup");
        this.audioReadiness = Objects.requireNonNull(audioReadiness, "audioReadiness");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 尝试收口指定会议。可由不同入口重复/并发调用；只有 {@link Accepted} 含
     * Admission 已持久确认的 receipt，{@link Pending} 和 {@link Failure} 均没有。
     */
    public Result reconcile(String meetingId) {
        MeetingIdPolicy.requireValid(meetingId);
        try {
            MeetingInputClosureContextPort.Result loaded = Objects.requireNonNull(
                    contextPort.load(meetingId), "context result");
            if (loaded instanceof MeetingInputClosureContextPort.WaitingForStop) {
                return new Pending(PendingReason.STOP_NOT_REQUESTED);
            }
            if (loaded instanceof MeetingInputClosureContextPort.Missing) {
                return new Failure(FailureReason.NOT_FOUND);
            }
            if (loaded instanceof MeetingInputClosureContextPort.Inconsistent) {
                return new Failure(FailureReason.INCONSISTENT);
            }
            if (loaded instanceof MeetingInputClosureContextPort.Unavailable) {
                return new Failure(FailureReason.UNAVAILABLE);
            }
            MeetingInputClosureContextPort.Context context =
                    ((MeetingInputClosureContextPort.Found) loaded).context();
            if (!meetingId.equals(context.meetingId())) {
                return new Failure(FailureReason.INCONSISTENT);
            }

            // 先读赢家：已提交的输入不能用新的 acceptedAt 重新构造。上下文快照
            // 与本次读取之间若发生了 Admission，仍以新的 V008 赢家恢复。
            AcceptedInputFactLookup.Result existing = Objects.requireNonNull(
                    acceptedInputLookup.find(meetingId), "accepted input result");
            if (existing instanceof AcceptedInputFactLookup.Found found) {
                return recoverWinner(context, found.fact(), null);
            }
            if (existing instanceof AcceptedInputFactLookup.Inconsistent) {
                return new Failure(FailureReason.INCONSISTENT);
            }
            if (existing instanceof AcceptedInputFactLookup.Unavailable) {
                return new Failure(FailureReason.UNAVAILABLE);
            }
            if (context.inputState() == InputState.INPUT_ACCEPTED) {
                return new Failure(FailureReason.INCONSISTENT);
            }

            CompletedAudioReadinessPort.Result checked = Objects.requireNonNull(
                    audioReadiness.check(meetingId), "audio readiness result");
            if (checked instanceof CompletedAudioReadinessPort.Pending
                    || checked instanceof CompletedAudioReadinessPort.Missing) {
                return new Pending(PendingReason.AUDIO_NOT_READY);
            }
            if (checked instanceof CompletedAudioReadinessPort.Inconsistent) {
                return new Failure(FailureReason.INCONSISTENT);
            }
            if (checked instanceof CompletedAudioReadinessPort.Unavailable) {
                return new Failure(FailureReason.UNAVAILABLE);
            }
            CompletedAudioReadinessPort.Ready ready =
                    (CompletedAudioReadinessPort.Ready) checked;
            if (!meetingId.equals(ready.meetingId())) {
                return new Failure(FailureReason.INCONSISTENT);
            }
            Instant acceptedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
            if (acceptedAt.isBefore(context.meetingUpdatedAt())) {
                acceptedAt = context.meetingUpdatedAt();
            }
            FinalizationInputFact candidate = new FinalizationInputFact(
                    meetingId, meetingId, ready.lastAcceptedSequence(),
                    ready.audioDurationMs(), context.sourceLanguage(), List.of(),
                    acceptedAt, ready.contentDigest());
            FinalizationAdmissionUnitOfWork.AdmissionRequest firstRequest =
                    request(context, candidate);
            FinalizationAdmissionUnitOfWork.Result result = admission.admit(firstRequest);
            if (result instanceof FinalizationAdmissionUnitOfWork.Success success) {
                return new Accepted(success);
            }
            FinalizationAdmissionUnitOfWork.Failure failure =
                    (FinalizationAdmissionUnitOfWork.Failure) result;
            if (failure.code() == FinalizationAdmissionUnitOfWork.ErrorCode.OUTCOME_UNKNOWN) {
                // V008 的独立查询看不到行，不足以判定首次事务没有提交。
                // 必须先按 Admission 的契约用原始请求执行只读恢复；不能重取
                // 时钟或构造另一个候选，更不能在确认前返回 receipt。
                FinalizationAdmissionUnitOfWork.Result recovered =
                        admission.recover(firstRequest);
                if (recovered instanceof FinalizationAdmissionUnitOfWork.Success success) {
                    return new Accepted(success);
                }
                FinalizationAdmissionUnitOfWork.Failure recoveryFailure =
                        (FinalizationAdmissionUnitOfWork.Failure) recovered;
                if (recoveryFailure.code()
                        == FinalizationAdmissionUnitOfWork.ErrorCode.ADMISSION_NOT_FOUND) {
                    // 只读恢复已明确证明本次 Admission 未提交。当前调用无 receipt；
                    // 下次 reconcile 可以重新开启一次首次尝试。
                    return new Failure(FailureReason.ADMISSION_NOT_FOUND);
                }
                if (recoveryFailure.code()
                        == FinalizationAdmissionUnitOfWork.ErrorCode.INPUT_FACT_CONFLICT) {
                    return readWinnerAndRecover(context, ready);
                }
                return admissionFailure(recoveryFailure);
            }
            if (failure.code() == FinalizationAdmissionUnitOfWork.ErrorCode.INPUT_FACT_CONFLICT) {
                return readWinnerAndRecover(context, ready);
            }
            return admissionFailure(failure);
        } catch (RuntimeException failure) {
            // 端口实现应返回固定分类；意外异常仍关闭式失败，不输出异常正文或
            // 会议标识，避免原始数据/凭据出现在运行日志中。
            LOGGER.log(Level.WARNING, "会议输入收口异常 module=input-reconciler");
            return new Failure(FailureReason.INTERNAL);
        }
    }

    private Result readWinnerAndRecover(MeetingInputClosureContextPort.Context context,
            CompletedAudioReadinessPort.Ready ready) {
        AcceptedInputFactLookup.Result read = Objects.requireNonNull(
                acceptedInputLookup.find(context.meetingId()), "winner result");
        if (read instanceof AcceptedInputFactLookup.Found found) {
            return recoverWinner(context, found.fact(), ready);
        }
        if (read instanceof AcceptedInputFactLookup.Unavailable) {
            return new Failure(FailureReason.UNAVAILABLE);
        }
        if (read instanceof AcceptedInputFactLookup.Inconsistent) {
            return new Failure(FailureReason.INCONSISTENT);
        }
        // 冲突已经报告有另一个赢家；但只读仍找不到赢家，属于矛盾事实。
        return new Failure(FailureReason.INCONSISTENT);
    }

    private Result recoverWinner(MeetingInputClosureContextPort.Context context,
            FinalizationInputFact winner, CompletedAudioReadinessPort.Ready ready) {
        if (!context.meetingId().equals(winner.meetingId())
                || !context.meetingId().equals(winner.inputBinding())
                || !context.sourceLanguage().equals(winner.sourceLanguage())
                || !winner.targetLanguages().isEmpty()
                || (context.inputState() == InputState.INPUT_COMPLETE_PENDING
                && winner.acceptedAt().isBefore(context.meetingUpdatedAt()))
                || (ready != null && (winner.lastAcceptedSequence()
                != ready.lastAcceptedSequence()
                || winner.audioDurationMs() != ready.audioDurationMs()
                || !winner.contentDigest().equals(ready.contentDigest())))) {
            return new Failure(FailureReason.INCONSISTENT);
        }
        FinalizationAdmissionUnitOfWork.Result recovered = admission.recover(
                request(context, winner));
        if (recovered instanceof FinalizationAdmissionUnitOfWork.Success success) {
            return new Accepted(success);
        }
        return admissionFailure((FinalizationAdmissionUnitOfWork.Failure) recovered);
    }

    private static FinalizationAdmissionUnitOfWork.AdmissionRequest request(
            MeetingInputClosureContextPort.Context context, FinalizationInputFact input) {
        return new FinalizationAdmissionUnitOfWork.AdmissionRequest(
                context.deploymentScope(), context.subjectScope(), context.meetingId(), input);
    }

    private static Failure admissionFailure(FinalizationAdmissionUnitOfWork.Failure failure) {
        return new Failure(switch (failure.code()) {
            case STORAGE_UNAVAILABLE -> FailureReason.UNAVAILABLE;
            case OUTCOME_UNKNOWN -> FailureReason.OUTCOME_UNKNOWN;
            case MEETING_NOT_FOUND -> FailureReason.NOT_FOUND;
            case FACT_INCONSISTENT, INPUT_FACT_CONFLICT, FINAL_USAGE_CONFLICT,
                    WORKFLOW_CONFLICT, MANIFEST_CONFLICT, ADMISSION_NOT_FOUND ->
                    FailureReason.INCONSISTENT;
            default -> FailureReason.ADMISSION_REJECTED;
        });
    }

    public sealed interface Result permits Accepted, Pending, Failure { }

    /** 唯一可让调用方展示 receipt 的结果；receipt 来自持久 Admission。 */
    public record Accepted(FinalizationAdmissionUnitOfWork.Success admission) implements Result {
        public Accepted { Objects.requireNonNull(admission, "admission"); }
        @Override public String toString() { return "Accepted[admission=redacted]"; }
    }

    public record Pending(PendingReason reason) implements Result {
        public Pending { Objects.requireNonNull(reason, "reason"); }
    }

    public record Failure(FailureReason reason) implements Result {
        public Failure { Objects.requireNonNull(reason, "reason"); }
        public boolean retryable() {
            // ADMISSION_NOT_FOUND 不是重试 recover：它允许下一次 reconcile
            // 发起一个全新的首次 Admission。
            return reason == FailureReason.UNAVAILABLE
                    || reason == FailureReason.OUTCOME_UNKNOWN
                    || reason == FailureReason.ADMISSION_NOT_FOUND;
        }
    }

    public enum PendingReason { STOP_NOT_REQUESTED, AUDIO_NOT_READY }
    public enum FailureReason {
        NOT_FOUND, INCONSISTENT, UNAVAILABLE, OUTCOME_UNKNOWN, ADMISSION_NOT_FOUND,
        ADMISSION_REJECTED, INTERNAL
    }
}

package com.czur.reference.application.port;

import java.util.Objects;

/**
 * durable input fact 与会议级输入接管的最小协同端口。
 *
 * <p>实现必须在同一份本地持久事务中按以下顺序完成工作：先锁定会议行并校验
 * 当前会议事实，再对 {@link DurableInputFactStore} 执行输入事实的首次写入或
 * 完整事实重放，最后只在事实可用（{@code APPLIED} 或 {@code REPLAYED}）时
 * 推进 {@link MeetingFinalizationStore} 并生成或重放 receipt。输入事实冲突时
 * 必须返回冲突结果，不得调用会生成 receipt 的 finalization 分支，也不得把
 * Stop 意图或当前时钟当作输入已持久接管的证明。</p>
 *
 * <p>该端口不修改既有
 * {@link MeetingFinalizationStore#acceptInput(String, java.time.Instant)} API；
 * JDBC 适配器应在内部把它们协同到同一事务资源，不能先用连接 A 持久化事实、
 * 再用连接 B 生成 receipt。公共端口不暴露 JDBC {@code Connection}、SQL、表名、
 * 连接池或驱动异常正文。</p>
 *
 * <p>如果提交确认结果未知，调用方只能重新读取 durable input fact 与会议级
 * finalization 事实来判断是否已经完成；不得根据超时、异常类型或本次候选事实
 * 猜测 receipt 是否存在。{@link Result} 保留两条已有状态机的原始结果，避免把
 * 输入持久化的 {@code APPLIED}/{@code REPLAYED} 与 receipt 的
 * {@code APPLIED}/{@code REPLAYED} 混为一个布尔成功标志。</p>
 */
public interface DurableInputAcceptanceUnitOfWork {

    /**
     * 在一个本地持久事务中持久化输入事实并协同生成或重放 receipt。
     *
     * @param request 会议标识与完整输入事实；两者的会议标识必须一致
     * @return durable input 持久化裁决与会议级 finalization 原始结果
     */
    Result accept(AcceptRequest request);

    /**
     * 协同端口请求。输入事实本身已经包含接管时间；实现必须使用该事实的
     * {@link FinalizationInputFact#acceptedAt()}，不得在事务内读取当前时钟替代它。
     *
     * @param meetingId 会议标识，必须为非空文本
     * @param inputFact 已完整接管的输入事实，且必须属于 {@code meetingId}
     */
    record AcceptRequest(String meetingId, FinalizationInputFact inputFact) {
        public AcceptRequest {
            requireText("meetingId", meetingId);
            Objects.requireNonNull(inputFact, "inputFact");
            if (!meetingId.equals(inputFact.meetingId())) {
                throw new IllegalArgumentException("meetingId must match inputFact.meetingId");
            }
        }

        /** 请求诊断不得输出会议标识、binding、语言、摘要或任何 receipt。 */
        @Override
        public String toString() {
            return "DurableInputAcceptanceUnitOfWork.AcceptRequest[redacted]";
        }
    }

    /**
     * 两条持久状态机的联合结果。
     *
     * <p>{@code inputStatus} 表示 durable input fact 的裁决，
     * {@code finalization} 是会议级端口返回的原始结果。只有 finalization 为
     * {@link MeetingFinalizationStore.Applied} 或
     * {@link MeetingFinalizationStore.Replayed} 且其中的事实为
     * {@code INPUT_ACCEPTED} 时，结果才表示本次调用已经生成或重放 receipt；
     * 冲突、未找到和其它非 accepted finalization 不携带 receipt。提交确认未知
     * 后的只读重查可以用 {@link MeetingFinalizationStore.Found} 表达已经存在的
     * accepted 事实，但这不是本次 {@code accept} 事务的写入结果，且只能与
     * {@code REPLAYED} 输入事实组合。特别是 {@code CONFLICT} 输入状态绝不能
     * 与 accepted finalization 组合；同一事务中首次 {@code APPLIED} 写入若不能
     * 完成 finalization 也必须回滚，因此不能返回
     * {@code APPLIED + Conflict/NotFound} 这种半提交组合。只有
     * {@code REPLAYED} 表示事实在本事务之前已经存在时，才可以与确定的
     * finalization 冲突或未找到结果组合。</p>
     *
     * <p>{@code finalization} 也可以是 {@link MeetingFinalizationStore.NotFound}
     * 或 {@link MeetingFinalizationStore.Conflict}，以便结果同时保留“既有输入
     * 事实存在但会议不能收口”的确定分类；{@link MeetingFinalizationStore.Found}
     * 只用于 commit-unknown 后的事实重读。存储不可用或提交结果未知由未来适配器
     * 按稳定不可用分类返回/映射，不通过 JDBC 异常泄漏。</p>
     */
    record Result(
            DurableInputFactStore.PersistStatus inputStatus,
            MeetingFinalizationStore.Result finalization) {
        public Result {
            Objects.requireNonNull(inputStatus, "inputStatus");
            Objects.requireNonNull(finalization, "finalization");
            boolean accepted = finalizationAccepted(finalization);
            if (inputStatus == DurableInputFactStore.PersistStatus.CONFLICT && accepted) {
                throw new IllegalArgumentException(
                        "durable input CONFLICT must not generate a receipt");
            }
            if (inputStatus == DurableInputFactStore.PersistStatus.APPLIED && !accepted) {
                throw new IllegalArgumentException(
                        "durable input APPLIED requires committed finalization");
            }
            if (finalization instanceof MeetingFinalizationStore.Found &&
                    inputStatus != DurableInputFactStore.PersistStatus.REPLAYED) {
                throw new IllegalArgumentException(
                        "finalization Found is only a replayed commit-unknown readback");
            }
        }

        /** durable input 是否首次写入并成为该会议的持久赢家。 */
        public boolean inputApplied() {
            return inputStatus == DurableInputFactStore.PersistStatus.APPLIED;
        }

        /** 会议级 finalization 是否已经提供可用 receipt。 */
        public boolean receiptAvailable() {
            return finalizationAccepted(finalization);
        }

        @Override
        public String toString() {
            return "DurableInputAcceptanceUnitOfWork.Result[inputStatus=" + inputStatus
                    + ", finalizationStatus=" + finalization.status() + ']';
        }

        private static boolean finalizationAccepted(MeetingFinalizationStore.Result result) {
            if (result instanceof MeetingFinalizationStore.Applied applied) {
                return applied.fact().isAccepted();
            }
            if (result instanceof MeetingFinalizationStore.Replayed replayed) {
                return replayed.fact().isAccepted();
            }
            if (result instanceof MeetingFinalizationStore.Found found) {
                return found.fact().isAccepted();
            }
            return false;
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}

package com.czur.reference.application.finalization;

import com.czur.reference.application.port.PendingMeetingInputPort;

import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 周期性驱动尚未接管输入的会议，不持有线程或数据库连接。
 *
 * <p>调度者串行调用 {@link #runOnce()}；每次最多处理一页。游标只存在于内存，
 * 进程重启后从第一页重新扫描。到页尾后下一轮从头开始，因此等待音频的旧会议
 * 不会长期挡住后面的候选。Stop/WSS 同时触发收口也安全，赢家仍由已有 Admission
 * 事务裁决。本类不创建另一套会议状态、租约或 receipt。</p>
 */
public final class MeetingInputReconciliationScan {
    private static final Logger LOGGER = Logger.getLogger(
            MeetingInputReconciliationScan.class.getName());

    private final PendingMeetingInputPort candidates;
    private final MeetingInputReconciler reconciler;
    private final int pageSize;
    private String afterMeetingId;

    /** @param pageSize 每轮最大候选数；必须为正数。 */
    public MeetingInputReconciliationScan(PendingMeetingInputPort candidates,
            MeetingInputReconciler reconciler, int pageSize) {
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
        if (pageSize <= 0) throw new IllegalArgumentException("pageSize must be positive");
        this.pageSize = pageSize;
    }

    /**
     * 扫描一页并返回本轮计数。空页表示本次游标已到末尾；下一轮从头开始。
     * 数据库查询异常交给外层调度者决定何时重试，不记成“没有待处理会议”。
     */
    public RunResult runOnce() {
        List<String> page = List.copyOf(candidates.listAfter(afterMeetingId, pageSize));
        if (page.isEmpty()) {
            afterMeetingId = null;
            return new RunResult(0, 0, 0, 0);
        }
        int accepted = 0;
        int pending = 0;
        int failed = 0;
        for (String meetingId : page) {
            MeetingInputReconciler.Result result = reconciler.reconcile(meetingId);
            if (result instanceof MeetingInputReconciler.Accepted) {
                accepted++;
            } else if (result instanceof MeetingInputReconciler.Pending) {
                pending++;
            } else {
                failed++;
                LOGGER.log(Level.WARNING, "扫描收口失败 module=input-reconciliation-scan reason={0}",
                        ((MeetingInputReconciler.Failure) result).reason());
            }
        }
        afterMeetingId = page.getLast();
        LOGGER.log(Level.FINE,
                "扫描收口一页 candidates={0} accepted={1} pending={2} failed={3}",
                new Object[]{page.size(), accepted, pending, failed});
        return new RunResult(page.size(), accepted, pending, failed);
    }

    /** 不包含会议标识或音频内容的调度计数。 */
    public record RunResult(int candidates, int accepted, int pending, int failed) { }
}

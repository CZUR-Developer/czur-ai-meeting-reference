package com.czur.reference.application.port;

import java.util.Objects;

/**
 * 会议输入收口的持久事实。
 *
 * <p>{@code finalizationReceipt} 是后端确认已经接管输入后的稳定 opaque 标识，
 * 不是登录凭据、访问 Token 或模型厂商任务号。只有
 * {@link InputState#INPUT_ACCEPTED} 允许携带 receipt；其余输入状态必须保持
 * {@code null}。该不变量让 App 能安全地区分“可以清理本地恢复数据”和“必须
 * 继续保留并重试”的两种情况。</p>
 *
 * @param inputState 当前服务端输入状态
 * @param finalizationReceipt 已接管时的稳定 receipt，未接管时必须为 {@code null}
 */
public record FinalizationFact(
        InputState inputState,
        String finalizationReceipt) {

    public FinalizationFact {
        Objects.requireNonNull(inputState, "inputState");
        if (inputState == InputState.INPUT_ACCEPTED) {
            requireText("finalizationReceipt", finalizationReceipt);
        } else if (finalizationReceipt != null) {
            throw new IllegalArgumentException(
                    "finalizationReceipt must be null before INPUT_ACCEPTED");
        }
    }

    /** 创建尚未完成输入接管的事实。 */
    public static FinalizationFact pending(InputState inputState) {
        if (inputState == InputState.INPUT_ACCEPTED) {
            throw new IllegalArgumentException("accepted input requires a receipt");
        }
        return new FinalizationFact(inputState, null);
    }

    /** 创建已经完成输入接管的事实。 */
    public static FinalizationFact accepted(String receipt) {
        return new FinalizationFact(InputState.INPUT_ACCEPTED, receipt);
    }

    /** 是否已经具备允许 App 清理恢复数据的 receipt。 */
    public boolean isAccepted() {
        return inputState == InputState.INPUT_ACCEPTED;
    }

    /** 隐藏 receipt 正文，避免调试输出被当作可复用凭据。 */
    @Override
    public String toString() {
        return "FinalizationFact[inputState=" + inputState
                + ", receiptPresent=" + (finalizationReceipt != null) + ']';
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}

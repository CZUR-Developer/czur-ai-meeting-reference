package com.czur.reference.application.finalization;

import com.czur.reference.application.port.FinalizationCompletionUnitOfWork.TerminalFailure;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * FAILED Completion 写入 Workflow error summary 的稳定、不可逆请求身份。
 *
 * <p>Workflow 表没有独立的 domain/retryable/diagnosticId 列。为了在不增加迁移的
 * 前提下证明 commit-unknown 恢复对应同一个 FailureRequest，本类对完整
 * {@link TerminalFailure} 使用长度前缀 UTF-8 字段计算 SHA-256，并保存固定前缀加
 * 64 位小写摘要。动态正文不会进入日志，且结果长度固定，远小于现有 TEXT 边界。</p>
 */
public final class FinalizationTerminalFailureIdentity {
    public static final String PREFIX = "finalization-terminal-failure-v1:sha256:";
    private static final String DOMAIN = "finalization-terminal-failure/v1";
    private static final Pattern VALUE = Pattern.compile(
            "^finalization-terminal-failure-v1:sha256:[0-9a-f]{64}$");

    private FinalizationTerminalFailureIdentity() {
    }

    /** 从完整稳定失败摘要生成可持久化 identity。 */
    public static String of(TerminalFailure failure) {
        Objects.requireNonNull(failure, "failure");
        return PREFIX + FinalizationOperationIdentity.digest(
                DOMAIN,
                failure.domain(),
                failure.code(),
                Boolean.toString(failure.retryable()),
                failure.diagnosticId(),
                failure.workflowSummary());
    }

    /** 防御性核对一个数据库值是否属于本版本 identity。 */
    public static boolean isValid(String value) {
        return value != null && VALUE.matcher(value).matches();
    }
}

package com.czur.reference.application.port.identity;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

/**
 * 原始身份凭据的短生命周期包装。
 *
 * <p>该类故意不是 record，也不实现基于内容的 {@code equals/hashCode}，并且
 * {@link #toString()} 永远只返回脱敏文本。构造和读取都使用防御性副本；读取
 * 回调结束后临时副本会立即清零。调用方仍不得从回调中保存数组引用，也不得
 * 把凭据写入命令、异常、数据库、Outbox 或日志。</p>
 *
 * <p>请求处理结束后必须调用 {@link #close()}。关闭操作幂等，关闭后不允许
 * 再次读取。Java 无法保证清除 JVM、网络库或调用方此前产生的全部副本，
 * 因此本类型是缩小暴露面的边界，不是通用密钥保险箱。</p>
 */
public final class SecretCredential implements AutoCloseable {
    private static final String REDACTED = "SecretCredential[REDACTED]";

    private byte[] value;

    private SecretCredential(byte[] value) {
        if (value.length == 0) {
            throw new IllegalArgumentException("credential must not be empty");
        }
        this.value = value.clone();
    }

    /** 从字节数组复制凭据；调用方可在返回后立即清除原数组。 */
    public static SecretCredential copyOf(byte[] value) {
        return new SecretCredential(Objects.requireNonNull(value, "value"));
    }

    /**
     * 在当前调用栈内使用凭据副本。
     *
     * @param operation 不得持有或返回传入数组本身的同步操作
     * @return 操作计算出的非凭据结果
     */
    public synchronized <T> T useBytes(Function<byte[], T> operation) {
        Objects.requireNonNull(operation, "operation");
        if (value == null) {
            throw new IllegalStateException("credential has been destroyed");
        }
        byte[] temporary = value.clone();
        try {
            return operation.apply(temporary);
        } finally {
            Arrays.fill(temporary, (byte) 0);
        }
    }

    /** 返回凭据是否已被显式销毁，不暴露长度或内容。 */
    public synchronized boolean isDestroyed() {
        return value == null;
    }

    /** 清除内部数组；重复调用不会失败。 */
    @Override
    public synchronized void close() {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
            value = null;
        }
    }

    /** 永远不输出凭据正文、摘要、长度或前后缀。 */
    @Override
    public String toString() {
        return REDACTED;
    }
}

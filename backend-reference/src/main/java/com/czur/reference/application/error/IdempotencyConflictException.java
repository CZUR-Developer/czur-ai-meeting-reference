package com.czur.reference.application.error;

/**
 * 同一幂等键被用于不同请求内容时抛出，调用方必须生成新的幂等键。
 *
 * <p>scope 可能是包含部署与真实主体的可逆 opaque 编码，key 也由外部调用方
 * 提供；两者都不能进入异常消息或日志。构造参数仅保留源码兼容和调用语义。</p>
 */
public final class IdempotencyConflictException extends IllegalStateException {
    public IdempotencyConflictException(String scope, String key) {
        super("Idempotency key is already used for a different request");
    }
}

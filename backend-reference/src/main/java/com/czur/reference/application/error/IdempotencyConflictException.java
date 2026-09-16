package com.czur.reference.application.error;

/** 同一幂等键被用于不同请求内容时抛出，调用方必须生成新的幂等键。 */
public final class IdempotencyConflictException extends IllegalStateException {
    public IdempotencyConflictException(String scope, String key) {
        super("Idempotency key is already used for a different request: " + scope + "/" + key);
    }
}

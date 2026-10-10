package com.czur.reference.application.port.entitlement;

import java.util.Objects;

/** 权益端口的显式结果，避免把计费实现异常泄露到应用层。 */
public sealed interface EntitlementProviderResult<T>
        permits EntitlementProviderResult.Success, EntitlementProviderResult.Failure {

    static <T> EntitlementProviderResult<T> success(T value) {
        return new Success<>(Objects.requireNonNull(value, "value"));
    }

    static <T> EntitlementProviderResult<T> failure(EntitlementProviderError error) {
        return new Failure<>(Objects.requireNonNull(error, "error"));
    }

    default boolean isSuccess() {
        return this instanceof Success<?>;
    }

    default T valueOrThrow() {
        if (this instanceof Success<T> success) {
            return success.value();
        }
        throw new IllegalStateException("entitlement provider result is failure");
    }

    default EntitlementProviderError errorOrThrow() {
        if (this instanceof Failure<T> failure) {
            return failure.error();
        }
        throw new IllegalStateException("entitlement provider result is success");
    }

    record Success<T>(T value) implements EntitlementProviderResult<T> {
        public Success {
            Objects.requireNonNull(value, "value");
        }
    }

    record Failure<T>(EntitlementProviderError error) implements EntitlementProviderResult<T> {
        public Failure {
            Objects.requireNonNull(error, "error");
        }
    }
}

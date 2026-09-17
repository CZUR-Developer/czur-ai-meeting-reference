package com.czur.reference.application.port.identity;

import java.util.Objects;

/** 身份端口的显式成功/失败结果，避免把 Provider 异常直接抛给应用层。 */
public sealed interface IdentityProviderResult<T>
        permits IdentityProviderResult.Success, IdentityProviderResult.Failure {

    static <T> IdentityProviderResult<T> success(T value) {
        return new Success<>(Objects.requireNonNull(value, "value"));
    }

    static <T> IdentityProviderResult<T> failure(IdentityProviderError error) {
        return new Failure<>(Objects.requireNonNull(error, "error"));
    }

    default boolean isSuccess() {
        return this instanceof Success<?>;
    }

    default T valueOrThrow() {
        if (this instanceof Success<T> success) {
            return success.value();
        }
        throw new IllegalStateException("identity provider result is failure");
    }

    default IdentityProviderError errorOrThrow() {
        if (this instanceof Failure<T> failure) {
            return failure.error();
        }
        throw new IllegalStateException("identity provider result is success");
    }

    record Success<T>(T value) implements IdentityProviderResult<T> {
        public Success {
            Objects.requireNonNull(value, "value");
        }
    }

    record Failure<T>(IdentityProviderError error) implements IdentityProviderResult<T> {
        public Failure {
            Objects.requireNonNull(error, "error");
        }
    }
}

package com.czur.reference.application.port.model;

import java.util.Objects;

/**
 * 不透明的模型会话句柄。
 *
 * <p>句柄只包含后端生成的逻辑 sessionId 和创建时的公共 spec；调用方不能
 * 通过它取得供应商 Task ID、Token 或直接修改 Driver 状态。它只能传回同一
 * Driver 的生命周期方法。</p>
 */
public record ModelDriverSession(String sessionId, RealtimeSessionSpec spec) {
    public ModelDriverSession {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        Objects.requireNonNull(spec, "spec");
    }
}

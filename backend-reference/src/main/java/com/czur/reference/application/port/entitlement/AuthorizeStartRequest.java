package com.czur.reference.application.port.entitlement;

import com.czur.reference.application.port.identity.Subject;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 无副作用的开会资格判断输入。
 *
 * <p>{@code deploymentScope} 必须来自服务端配置，不能由 Android 客户端覆盖。
 * 此请求不携带幂等键或请求摘要，因为 {@code authorizeStart} 只读取当前策略，
 * 不写 usage operation；真正的额度占用从 {@link ReserveUsageRequest} 开始。</p>
 */
public record AuthorizeStartRequest(
        String deploymentScope,
        Subject subject,
        String profileId,
        int profileRevision,
        Set<String> requestedCapabilities,
        UsageEstimate estimatedUsage,
        String clientSessionId) {

    public AuthorizeStartRequest {
        requireText("deploymentScope", deploymentScope);
        Objects.requireNonNull(subject, "subject");
        requireText("profileId", profileId);
        if (profileRevision <= 0) {
            throw new IllegalArgumentException("profileRevision must be positive");
        }
        Objects.requireNonNull(requestedCapabilities, "requestedCapabilities");
        TreeSet<String> copy = new TreeSet<>();
        for (String capability : requestedCapabilities) {
            requireText("requested capability", capability);
            copy.add(capability);
        }
        requestedCapabilities = Collections.unmodifiableSet(copy);
        Objects.requireNonNull(estimatedUsage, "estimatedUsage");
        requireText("clientSessionId", clientSessionId);
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}

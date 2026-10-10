package com.czur.reference.application.command;

import com.czur.reference.application.port.entitlement.UsageEstimate;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 客户端提交的“创建会议”业务意图。
 *
 * <p>本对象只保存客户端在重试之间必须保持稳定的业务字段。服务端生成的
 * meetingId、请求时间、Grant、Reservation，以及服务端决定的部署范围和已验证
 * 主体均不属于客户端意图，不能添加到这里或进入创建摘要。这样客户端在超时后
 * 使用同一幂等键重试时，不会因为服务端重新生成内部事实而被误判为冲突。</p>
 *
 * <p>{@code requestedCapabilities} 在构造时排序并防御性复制；调用方后续修改原
 * 集合不会改变意图或规范摘要。</p>
 */
public record CreateMeetingIntent(
        String clientSessionId,
        String profileId,
        int profileRevision,
        Set<String> requestedCapabilities,
        UsageEstimate estimatedUsage,
        String idempotencyKey) {

    public CreateMeetingIntent {
        requireText("clientSessionId", clientSessionId);
        requireText("profileId", profileId);
        if (profileRevision <= 0) {
            throw new IllegalArgumentException("profileRevision must be positive");
        }
        requestedCapabilities = immutableCapabilities(requestedCapabilities);
        if (requestedCapabilities.isEmpty()) {
            throw new IllegalArgumentException("requestedCapabilities must not be empty");
        }
        Objects.requireNonNull(estimatedUsage, "estimatedUsage");
        requireText("idempotencyKey", idempotencyKey);
    }

    /**
     * 避免 record 默认输出客户端幂等键；其余内容仅作为无凭据诊断摘要。
     */
    @Override
    public String toString() {
        return "CreateMeetingIntent[clientSessionId=" + clientSessionId
                + ", profileId=" + profileId
                + ", profileRevision=" + profileRevision
                + ", requestedCapabilities=" + requestedCapabilities
                + ", estimatedUsage=" + estimatedUsage + ']';
    }

    private static Set<String> immutableCapabilities(Set<String> capabilities) {
        Objects.requireNonNull(capabilities, "requestedCapabilities");
        TreeSet<String> copy = new TreeSet<>();
        for (String capability : capabilities) {
            requireText("requested capability", capability);
            copy.add(capability);
        }
        return Collections.unmodifiableSet(copy);
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}

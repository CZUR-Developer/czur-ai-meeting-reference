package com.czur.reference.application.port.entitlement;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 首次授权时冻结的 Product Profile 与能力边界。
 *
 * <p>会议创建不能在 reserve 成功后重新查询当前策略来推导这些字段：管理员可能
 * 已经修改 Profile、模型能力或时长上限，重试也可能携带一张后来签发的 Grant。
 * 因此首次 reserve 必须把本快照与幂等结果一起持久化，后续创建会议只读取首次
 * 快照，从而保证进程重启和策略变更不会改变同一个幂等请求的业务含义。</p>
 *
 * <p>能力集合会复制到按字典序排列的不可变集合。快照只忠实保存 Provider
 * 已经签发的事实，不在这里重新解释授权策略：部分能力规划会在请求能力之外
 * 补入必需依赖，是否允许这种关系必须由签发 Grant 的 Provider 决定。</p>
 */
public record AuthorizedProfileSnapshot(
        String profileId,
        int profileRevision,
        Set<String> requestedCapabilities,
        Set<String> grantedCapabilities,
        long maxDurationMs) {

    public AuthorizedProfileSnapshot {
        requireText("profileId", profileId);
        if (profileRevision <= 0) {
            throw new IllegalArgumentException("profileRevision must be positive");
        }
        requestedCapabilities = immutableCapabilities(
                requestedCapabilities, "requestedCapabilities");
        grantedCapabilities = immutableCapabilities(
                grantedCapabilities, "grantedCapabilities");
        if (requestedCapabilities.isEmpty()) {
            throw new IllegalArgumentException("requestedCapabilities must not be empty");
        }
        if (grantedCapabilities.isEmpty()) {
            throw new IllegalArgumentException("grantedCapabilities must not be empty");
        }
        if (maxDurationMs <= 0) {
            throw new IllegalArgumentException("maxDurationMs must be positive");
        }
    }

    /**
     * 从已经通过权益 Provider 校验的首次 Grant 创建快照。
     *
     * <p>该方法只复制创建会议所需的授权事实，不保存 Grant ID、主体或部署范围；
     * 后三者已经分别由 Reservation 和稳定操作结果保存并执行范围校验。</p>
     */
    public static AuthorizedProfileSnapshot from(MeetingGrant grant) {
        Objects.requireNonNull(grant, "grant");
        return new AuthorizedProfileSnapshot(
                grant.profileId(),
                grant.profileRevision(),
                grant.requestedCapabilities(),
                grant.grantedCapabilities(),
                grant.maxDurationMs());
    }

    private static Set<String> immutableCapabilities(Set<String> values, String name) {
        Objects.requireNonNull(values, name);
        TreeSet<String> copy = new TreeSet<>();
        for (String value : values) {
            requireText(name + " value", value);
            copy.add(value);
        }
        return Collections.unmodifiableSet(copy);
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}

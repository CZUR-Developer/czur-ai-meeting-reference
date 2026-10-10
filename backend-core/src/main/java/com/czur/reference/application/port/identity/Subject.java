package com.czur.reference.application.port.identity;

import com.czur.reference.domain.meeting.MeetingOwnership;

/**
 * 身份 Provider 返回的稳定终端用户主体。
 *
 * <p>{@code subjectScope} 是同一部署内长期稳定的不透明标识，只用于归属、
 * 隔离和幂等范围。它不能由 Bearer Token、邮箱显示名、设备序列号或角色拼接
 * 得到；凭据刷新和角色变化也不能改变该值。组织范围是合作伙伴可选的业务
 * 边界，不表示 CZUR 托管多租户。</p>
 *
 * <p>角色和权限有独立的生命周期，保存在 {@link VerifiedIdentity} 中，不参与
 * 本对象的相等性，避免权限变化导致历史会议“更换所有者”。</p>
 */
public record Subject(String subjectScope, String organizationScope) {

    public Subject {
        MeetingOwnership.requireOwnedSubjectScope(subjectScope);
        if (organizationScope != null && organizationScope.isBlank()) {
            throw new IllegalArgumentException("organizationScope must be null or non-blank");
        }
    }

    /** 创建不启用组织隔离的默认单部署主体。 */
    public Subject(String subjectScope) {
        this(subjectScope, null);
    }

    /**
     * 返回不暴露稳定主体和可选组织范围的诊断摘要。
     *
     * <p>主体值会进入持久归属和幂等范围，不应因为 record 默认输出被日志或异常
     * 拼接带出。调用方若要判断主体，只能使用结构化 accessor，不得解析此文本。</p>
     */
    @Override
    public String toString() {
        return "Subject[identified=true, organizationScoped="
                + (organizationScope != null) + ']';
    }
}

package com.czur.reference.domain.meeting;

/**
 * 会议归属字段的保留值与边界校验。
 *
 * <p>{@link #LEGACY_UNOWNED_SUBJECT_SCOPE} 只表示 V001/V002 或未认证兼容入口
 * 产生、且不能被普通用户认领的历史事实。身份 Provider、环境配置和新 owned
 * 会议都必须拒绝把它当成真实主体，避免登录用户穿透历史归属边界。</p>
 */
public final class MeetingOwnership {
    public static final String LEGACY_UNOWNED_SUBJECT_SCOPE = "LEGACY_UNOWNED";

    private MeetingOwnership() {
    }

    /** 校验可用于新会议的真实主体，并原样返回以方便值对象构造器复用。 */
    public static String requireOwnedSubjectScope(String subjectScope) {
        if (subjectScope == null || subjectScope.isBlank()) {
            throw new IllegalArgumentException("subjectScope must not be blank");
        }
        if (LEGACY_UNOWNED_SUBJECT_SCOPE.equals(subjectScope)) {
            throw new IllegalArgumentException("subjectScope uses a reserved legacy value");
        }
        return subjectScope;
    }
}

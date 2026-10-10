package com.czur.reference.application.command;

import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingOwnership;

import java.util.Objects;

/**
 * 对已有会议执行部署隔离和所有者校验的公共守卫。
 *
 * <p>Stop 以及 T13 的查询、事件读取和删除必须复用这个守卫，不能各自实现一套
 * 范围判断。部署范围不匹配、历史无主记录和真实主体不匹配都返回同一个
 * {@link Decision#ACCESS_DENIED}，避免调用方或未来 HTTP 适配器暴露某个会议属于
 * 哪个部署、是否为历史记录或是否由其他主体拥有。</p>
 *
 * <p>V001/V002 升级得到的 {@code LEGACY_UNOWNED} 会议没有可证明的终端用户
 * 所有者，任何普通认证主体都不能认领或访问。Grant 到期不属于这里的判断条件：
 * 它限制首次创建，不撤销已经建立的会议归属。该守卫也不判断权限或凭据有效期；
 * 认证应用服务必须在调用守卫前检查当前命令所需的 {@link MeetingPermissions}
 * 权限，避免把“可以执行哪类命令”和“是否拥有这场会议”混为同一个规则。</p>
 */
public final class MeetingAccessGuard {
    private MeetingAccessGuard() {
    }

    /**
     * 判断当前认证上下文能否以所有者身份访问会议。
     *
     * @param context 只由服务端配置和已验证身份组成的调用上下文
     * @param meeting 从会议事实源读取的不可变快照
     * @return 不携带主体值或动态异常正文的稳定判断结果
     */
    public static Decision evaluate(
            MeetingCommandContext context,
            MeetingAggregate.MeetingSnapshot meeting) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(meeting, "meeting");

        return evaluate(context, meeting.deploymentScope(), meeting.subjectScope());
    }

    /**
     * 直接按持久化记录的原始归属字段判断访问权限。
     *
     * <p>JDBC 只读适配器必须在解析状态、Grant、receipt 等仅所有者可见的列之前
     * 调用本入口。这样跨部署、跨主体或 {@code LEGACY_UNOWNED} 记录即使内部还
     * 存在损坏字段，也只会得到统一的 {@link Decision#ACCESS_DENIED}，不会通过
     * {@code SNAPSHOT_INCONSISTENT} 暴露外域行的归属类型或内部健康状态。数据库原始范围为
     * {@code null}/空白时也不抛出动态异常，而是关闭式拒绝；一个有效的认证
     * context 不可能与这些值相等。</p>
     *
     * @param context 只由服务端配置和已验证身份组成的调用上下文
     * @param storedDeploymentScope 持久化记录原始部署范围，可能来自待校验数据
     * @param storedSubjectScope 持久化记录原始主体范围，可能来自待校验数据
     */
    public static Decision evaluate(
            MeetingCommandContext context,
            String storedDeploymentScope,
            String storedSubjectScope) {
        Objects.requireNonNull(context, "context");
        return evaluate(context.deploymentScope(),
                context.identity().subject().subjectScope(),
                storedDeploymentScope, storedSubjectScope);
    }

    /**
     * 对用户登录和实时凭据两种已验证来源使用同一持久会议归属规则。
     * 传入的前两个范围必须来自对应的服务端核验结果，不能取自请求正文。
     */
    public static Decision evaluate(String verifiedDeploymentScope,
            String verifiedSubjectScope, String storedDeploymentScope,
            String storedSubjectScope) {
        Objects.requireNonNull(verifiedDeploymentScope, "verifiedDeploymentScope");
        Objects.requireNonNull(verifiedSubjectScope, "verifiedSubjectScope");
        if (!verifiedDeploymentScope.equals(storedDeploymentScope)
                || MeetingOwnership.LEGACY_UNOWNED_SUBJECT_SCOPE.equals(storedSubjectScope)
                || !verifiedSubjectScope.equals(storedSubjectScope)) {
            return Decision.ACCESS_DENIED;
        }
        return Decision.ALLOWED;
    }

    /**
     * 会议归属判断的稳定结果集合。
     *
     * <p>枚举不携带会议主体、当前主体或动态错误文本，适合由不同入口统一映射。
     * 新增结果时，所有 Stop、查询和删除调用方都必须显式处理，不能默认放行。</p>
     */
    public enum Decision {
        /** 部署范围和真实所有者均匹配。 */
        ALLOWED,
        /** 部署、历史归属或主体任一边界不匹配；不暴露具体拒绝原因。 */
        ACCESS_DENIED
    }
}

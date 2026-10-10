package com.czur.reference.application.command;

/**
 * 认证会议命令使用的稳定权限码。
 *
 * <p>字符串值是身份 Provider、应用编排和未来 HTTP 错误映射之间的公共契约。
 * Provider 可以用自己的角色体系生成这些权限，但应用层只判断这里冻结的权限码，
 * 不直接识别合作伙伴角色名称。修改已有值会使已签发凭据失去兼容性，因此只能在
 * 新协议版本中新增权限，不能复用或改变现有常量。</p>
 */
public final class MeetingPermissions {
    /** 允许当前已验证主体申请授权、预占用量并创建新会议。 */
    public static final String CREATE = "meeting:create";

    /** 允许当前已验证主体对自己拥有的会议提交停止意图。 */
    public static final String STOP = "meeting:stop";

    /** 允许当前已验证主体对自己拥有的会议发起异步删除。 */
    public static final String DELETE = "meeting:delete";

    private MeetingPermissions() {
    }
}

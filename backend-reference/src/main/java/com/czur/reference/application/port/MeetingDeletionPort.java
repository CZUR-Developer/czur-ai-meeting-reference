package com.czur.reference.application.port;

/**
 * 认证会议删除端口。
 *
 * <p>调用方在进入本端口前必须完成身份验证、删除权限检查和
 * {@code MeetingAccessGuard} 所要求的部署/主体归属判断。适配器应在同一
 * 事实源中以幂等键记录“进入 DELETION_PENDING”的结果，并由后续 Outbox/Worker
 * 处理 Artifact 清理；本端口不接受对象存储路径，也不允许 App 直接拼接删除
 * URL。</p>
 */
public interface MeetingDeletionPort {
    /** 受理或重放一次幂等删除请求。 */
    DeleteResult delete(DeleteRequest request);
}

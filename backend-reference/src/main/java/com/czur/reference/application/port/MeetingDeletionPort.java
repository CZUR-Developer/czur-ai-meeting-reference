package com.czur.reference.application.port;

import com.czur.reference.application.command.MeetingPermissions;

/**
 * 认证会议删除端口。
 *
 * <p>调用方在进入本端口前必须完成身份验证，并校验稳定权限
 * {@link MeetingPermissions#DELETE}。端口不重复解释 credential 或合作伙伴角色；
 * 但 JDBC 实现必须在锁定会议事实后，再使用数据库中的原始部署/主体执行归属
 * 检查，不能信任锁外查询结果。适配器应在同一事实源中以幂等键记录“进入
 * DELETION_PENDING”的结果，并由后续 Outbox/Worker 处理 Artifact 清理；本端口
 * 不接受对象存储路径，也不允许 App 直接拼接删除 URL。</p>
 */
public interface MeetingDeletionPort {
    /** 受理或重放一次幂等删除请求。 */
    DeleteResult delete(DeleteRequest request);
}

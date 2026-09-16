package com.czur.reference.application.port;

import com.czur.reference.domain.meeting.MeetingAggregate;

import java.util.Optional;

/**
 * 会议事实源端口。
 *
 * <p>应用层只依赖这个最小接口，不了解数据库、事务框架或表结构。
 * 实现必须按 {@code meetingId} 唯一保存快照，并保证一次 {@code save}
 * 对调用方可见。生产实现应在数据库事务中完成并发冲突检测；本模块提供
 * 的内存实现仅用于参考代码和测试。</p>
 */
public interface MeetingRepository {
    Optional<MeetingAggregate.MeetingSnapshot> find(String meetingId);

    /**
     * 按数据库唯一键语义查询会议。
     *
     * <p>数据库迁移将 {@code deployment_scope + client_session_id} 定义为
     * 唯一组合。生产实现必须让本查询与创建写入处于同一事务或依赖数据库
     * 唯一约束兜底，不能把本查询单独视为并发安全保证。默认实现返回空值，
     * 仅用于兼容 P2-T06 之前的自定义适配器；任何生产适配器都应覆盖它。</p>
     *
     * @param deploymentScope 稳定的部署范围标识
     * @param clientSessionId 客户端为一次会议生成的会话标识
     * @return 同一范围和会话对应的会议快照
     */
    default Optional<MeetingAggregate.MeetingSnapshot> findByDeploymentScopeAndClientSessionId(
            String deploymentScope,
            String clientSessionId) {
        return Optional.empty();
    }

    void save(MeetingAggregate.MeetingSnapshot meeting);
}

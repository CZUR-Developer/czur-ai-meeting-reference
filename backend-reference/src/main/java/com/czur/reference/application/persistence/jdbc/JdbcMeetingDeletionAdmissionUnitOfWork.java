package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.DeleteRequest;
import com.czur.reference.application.port.DeleteResult;
import com.czur.reference.application.port.MeetingDeletionPort;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.Objects;

/**
 * T14-10-05 删除受理工作单元的明确装配入口。
 *
 * <p>该入口启用冻结 ArtifactDeletionPlan 和删除 Workflow 的连接级事务；旧的
 * {@link JdbcMeetingDeletionPort#JdbcMeetingDeletionPort(DataSource, Clock)} 构造器
 * 仍保留给仅执行 V001～V007 删除受理契约的调用方。实例不创建后台线程，也不负责
 * 逐项物理删除或写入 DELETED 终态。</p>
 */
public final class JdbcMeetingDeletionAdmissionUnitOfWork implements MeetingDeletionPort {
    private final JdbcMeetingDeletionPort delegate;

    public JdbcMeetingDeletionAdmissionUnitOfWork(DataSource dataSource, Clock clock) {
        delegate = new JdbcMeetingDeletionPort(
                Objects.requireNonNull(dataSource, "dataSource"),
                Objects.requireNonNull(clock, "clock"),
                true);
    }

    @Override
    public DeleteResult delete(DeleteRequest request) {
        return delegate.delete(Objects.requireNonNull(request, "request"));
    }
}

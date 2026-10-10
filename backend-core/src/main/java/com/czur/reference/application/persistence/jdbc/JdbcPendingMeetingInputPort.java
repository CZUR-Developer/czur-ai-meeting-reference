package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.PendingMeetingInputPort;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 从同一会议事实源列出 STOPPING 且 INPUT_COMPLETE_PENDING 的本部署候选。
 *
 * <p>不要求 V015 声明已经到达：未齐的候选由 Reconciler 返回 Pending，
 * 后续扫描会重试。只查询 ID，不加载音频或结果；使用会议 ID 游标避免一批
 * 长期 Pending 会议饿死后面的会议。</p>
 */
public final class JdbcPendingMeetingInputPort implements PendingMeetingInputPort {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcPendingMeetingInputPort.class.getName());
    private static final String BASE_SQL = """
            SELECT meeting_id FROM meeting
             WHERE deployment_scope=? AND state='STOPPING'
               AND input_state='INPUT_COMPLETE_PENDING'
            """;

    private final DataSource dataSource;
    private final String deploymentScope;

    /** @param deploymentScope 可信部署配置，不能取自客户端输入。 */
    public JdbcPendingMeetingInputPort(DataSource dataSource, String deploymentScope) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        if (deploymentScope == null || deploymentScope.isBlank()) {
            throw new IllegalArgumentException("deploymentScope is required");
        }
        this.deploymentScope = deploymentScope;
    }

    @Override
    public List<String> listAfter(String afterMeetingId, int limit) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
        String sql = BASE_SQL + (afterMeetingId == null ? "" : " AND meeting_id > ?")
                + " ORDER BY meeting_id LIMIT ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, deploymentScope);
            if (afterMeetingId != null) statement.setString(2, afterMeetingId);
            statement.setInt(afterMeetingId == null ? 2 : 3, limit);
            List<String> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(rows.getString(1));
            }
            return List.copyOf(result);
        } catch (SQLException failure) {
            LOGGER.log(Level.WARNING, "待收口会议扫描失败 sqlState={0}",
                    JdbcReadLookupSupport.safeSqlState(failure));
            throw new IllegalStateException("pending meeting scan unavailable", failure);
        }
    }
}

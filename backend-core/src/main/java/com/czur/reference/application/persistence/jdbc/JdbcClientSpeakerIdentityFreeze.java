package com.czur.reference.application.persistence.jdbc;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * 会后跨会议人物识别的首次结果冻结。
 *
 * <p>人物产物以稳定 {@code artifact_id} 发布，而 ArtifactStore 不允许同一
 * artifactId 换一份正文。识别由外部 Provider 完成，同一场重试重新识别可能得到
 * 不同人物；若第一次正文已经发布、数据库登记却失败，重试必须发布同一份正文。
 * 本类因此在发布前把首次识别正文、大小与 checksum 写入独立事实行；重试直接
 * 读回并复用，不再调用 Provider，也不放松存储的不可变校验。</p>
 *
 * <p>冻结行只属于正常 Stop 收口；弃场决定仍由扫描器收口，不生成人物产物。
 * 本类不接管连接或事务：读回使用调用方的只读快照，冻结使用独立自动提交语句，
 * 保证正文在第一次发布前已经持久化。</p>
 */
final class JdbcClientSpeakerIdentityFreeze {
    private static final String FIND_SQL = """
            SELECT artifact_id, content_size, content_checksum, body_json
              FROM %s
             WHERE meeting_id = ?
            """;
    private static final String INSERT_SQL = """
            INSERT INTO %s (
                meeting_id, artifact_id, content_size, content_checksum, body_json)
            VALUES (?, ?, ?, ?, ?)
            """;

    private JdbcClientSpeakerIdentityFreeze() { }

    /** 在调用方只读快照里读回已冻结正文；本场尚未冻结时返回 empty。 */
    static Optional<String> find(Connection connection, JdbcDatabaseEngine engine,
            String meetingId, String artifactId) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(meetingId, "meetingId");
        Objects.requireNonNull(artifactId, "artifactId");
        try (PreparedStatement statement = connection.prepareStatement(
                FIND_SQL.formatted(table(engine)))) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows, artifactId)) : Optional.empty();
            }
        }
    }

    /**
     * 发布前冻结本次识别正文；并发重试由主键收敛到先落库的同一份正文。
     *
     * <p>正文先落库再返回给发布路径，因此任何“内容已发布但结果未登记”的
     * 重试都能读到同一份正文，不会用同一 artifactId 再算一份新正文。</p>
     */
    static String freeze(DataSource dataSource, JdbcDatabaseEngine engine, String meetingId,
            String artifactId, String bodyJson) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(meetingId, "meetingId");
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(bodyJson, "bodyJson");
        byte[] content = bodyJson.getBytes(StandardCharsets.UTF_8);
        String checksum = "sha256:" + JdbcClientResultArtifacts.digest(content);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     INSERT_SQL.formatted(table(engine)))) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            JdbcValues.setText(statement, 2, engine, artifactId);
            statement.setLong(3, content.length);
            statement.setString(4, checksum);
            statement.setString(5, bodyJson);
            statement.executeUpdate();
            return bodyJson;
        } catch (SQLException failure) {
            // 并发重试可能已先冻结；只有确切的重复键才读赢家，其它故障放弃本次收尾。
            if (!JdbcSqlErrors.isDuplicate(engine, failure)) {
                throw new IllegalStateException("speaker identity freeze database unavailable");
            }
        }
        return readFrozen(dataSource, engine, meetingId, artifactId);
    }

    private static String readFrozen(DataSource dataSource, JdbcDatabaseEngine engine,
            String meetingId, String artifactId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     FIND_SQL.formatted(table(engine)))) {
            JdbcValues.setText(statement, 1, engine, meetingId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException(
                            "speaker identity freeze is missing after duplicate");
                }
                return read(rows, artifactId);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("speaker identity freeze database unavailable");
        }
    }

    /** 读回正文并核对 artifactId、大小与 checksum；不一致即关闭式失败。 */
    private static String read(ResultSet rows, String artifactId) throws SQLException {
        String storedArtifactId = JdbcValues.text(rows, "artifact_id");
        String bodyJson = rows.getString("body_json");
        long contentSize = rows.getLong("content_size");
        String contentChecksum = rows.getString("content_checksum");
        if (bodyJson == null) {
            throw new IllegalStateException("frozen speaker identity is inconsistent");
        }
        byte[] content = bodyJson.getBytes(StandardCharsets.UTF_8);
        if (!artifactId.equals(storedArtifactId)
                || content.length != contentSize
                || !("sha256:" + JdbcClientResultArtifacts.digest(content))
                        .equals(contentChecksum)) {
            throw new IllegalStateException("frozen speaker identity is inconsistent");
        }
        return bodyJson;
    }

    private static String table(JdbcDatabaseEngine engine) {
        return JdbcDatabaseTable.MEETING_SPEAKER_IDENTITY.physicalName(engine);
    }
}

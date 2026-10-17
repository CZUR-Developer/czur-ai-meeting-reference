package com.czur.reference.application.speaker;

import com.czur.reference.application.persistence.jdbc.JdbcDatabaseContext;
import com.czur.reference.application.persistence.jdbc.JdbcDatabaseEngine;
import com.czur.reference.application.persistence.jdbc.JdbcDatabaseTable;
import com.czur.reference.application.port.speaker.SpeakerIdentityProvider.Person;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 参考讯飞插件的 MySQL 私有人物映射，复用公共 Context 的连接池。
 *
 * <p>标准 personId 与 group／feature 分列保存；按原部署与主体归属隔离。
 * 插件启用时只读检查对应 V001 表，不自动执行 DDL。默认装配不构造本对象，
 * 因而未提供声纹的会议和既有 CZUR 消费都不依赖新增表。</p>
 */
public final class JdbcReferenceSpeakerPersons {
    private final JdbcDatabaseContext database;
    private final String table;

    public JdbcReferenceSpeakerPersons(JdbcDatabaseContext database) {
        if (database.engine() != JdbcDatabaseEngine.MYSQL) {
            throw new IllegalArgumentException("参考人物映射需要 MySQL V001");
        }
        this.database = database;
        this.table = JdbcDatabaseTable.SPEAKER_IDENTITY_PERSON.physicalName(database.engine());
        try (var connection = database.dataSource().getConnection();
             var statement = connection.prepareStatement("SELECT person_id, display_name, provider_id, "
                     + "group_id, feature_id FROM " + table + " WHERE 1=0")) {
            statement.executeQuery().close();
        } catch (SQLException failure) {
            throw new IllegalStateException("参考人物映射表未就绪，请执行当前唯一 MySQL V001");
        }
    }

    /** 调用只持有本方法连接，不把连接传给供应商。 */
    public List<Entry> list(String deploymentScope, String subjectScope, String provider) throws SQLException {
        try (var connection = database.dataSource().getConnection();
             var statement = connection.prepareStatement("SELECT person_id, display_name, provider_id, "
                     + "group_id, feature_id FROM " + table
                     + " WHERE deployment_scope=? AND subject_scope=? AND provider_id=?")) {
            text(statement, 1, deploymentScope);
            text(statement, 2, subjectScope);
            text(statement, 3, provider);
            try (var rows = statement.executeQuery()) {
                List<Entry> entries = new ArrayList<>();
                while (rows.next()) entries.add(new Entry(new Person(text(rows, "person_id"),
                        rows.getString("display_name")), text(rows, "provider_id"),
                        text(rows, "group_id"), text(rows, "feature_id")));
                return List.copyOf(entries);
            }
        }
    }

    /** 仅按标准人物主键写入映射／名称；供应商网络调用须在调用本方法前完成。 */
    public void save(String deploymentScope, String subjectScope, Entry entry) throws SQLException {
        try (var connection = database.dataSource().getConnection();
             var statement = connection.prepareStatement("INSERT INTO " + table
                     + " (deployment_scope, subject_scope, person_id, display_name, provider_id, group_id, feature_id)"
                     + " VALUES (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE display_name=VALUES(display_name)")) {
            text(statement, 1, deploymentScope);
            text(statement, 2, subjectScope);
            text(statement, 3, entry.person().personId());
            statement.setString(4, entry.person().displayName());
            text(statement, 5, entry.providerId());
            text(statement, 6, entry.groupId());
            text(statement, 7, entry.featureId());
            statement.executeUpdate();
        }
    }

    private static void text(PreparedStatement statement, int index, String value) throws SQLException {
        statement.setBytes(index, value.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(ResultSet rows, String column) throws SQLException {
        return new String(rows.getBytes(column), StandardCharsets.UTF_8);
    }

    /** 仅插件内部持有供应商引用，不放入 speaker.identity 公共 JSON。 */
    public record Entry(Person person, String providerId, String groupId, String featureId) { }
}

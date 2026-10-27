package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.AcceptedInputFactLookup;
import com.czur.reference.application.port.FinalizationInputFact;
import com.czur.reference.domain.meeting.MeetingIdPolicy;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * V008 完整输入赢家的只读 JDBC 投影。
 *
 * <p>本类只读取已提交的原始 {@code acceptedAt}、语言、序号、时长、绑定及摘要，
 * 不根据当前时间或完成声明重建候选。并发 Admission 或提交确认未知时，调用方
 * 必须使用这里读回的整份事实执行只读恢复；缺行不等于可以推断旧事务未提交。</p>
 */
public final class JdbcAcceptedInputFactLookup implements AcceptedInputFactLookup {
    private static final Logger LOGGER = Logger.getLogger(
            JdbcAcceptedInputFactLookup.class.getName());
    private final DataSource dataSource;

    /** @param dataSource 已完成 V008 迁移、生命周期由运行时持有的连接源 */
    public JdbcAcceptedInputFactLookup(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** 只读返回数据库赢家；坏事实与数据库故障不伪装成不存在。 */
    @Override
    public Result find(String meetingId) {
        MeetingIdPolicy.requireValid(meetingId);
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            FinalizationInputFact fact;
            try {
                fact = JdbcDurableInputFactStore.find(connection, meetingId).orElse(null);
            } catch (IllegalStateException corruptFact) {
                // V008 连接内原语只在字段恢复失败时抛此固定类型；连接池运行时故障
                // 发生在此调用之外，仍由外层分为 Unavailable。
                return inconsistent();
            }
            connection.rollback();
            if (fact == null) {
                return new Missing();
            }
            if (!meetingId.equals(fact.meetingId())) {
                return inconsistent();
            }
            return new Found(fact);
        } catch (SQLException failure) {
            LOGGER.log(Level.WARNING, "已接管输入读取失败 sqlState={0}",
                    JdbcReadLookupSupport.safeSqlState(failure));
            return JdbcReadLookupSupport.classify(failure)
                    == JdbcReadLookupSupport.FailureKind.INCONSISTENT
                    ? new Inconsistent() : new Unavailable();
        } catch (RuntimeException failure) {
            // 未知连接池/驱动异常可能包含动态正文，只记录固定类别。
            LOGGER.log(Level.WARNING, "已接管输入读取不可用 module=accepted-input-lookup");
            return new Unavailable();
        }
    }

    private static Inconsistent inconsistent() {
        LOGGER.log(Level.WARNING, "已接管输入持久事实不一致 module=accepted-input-lookup");
        return new Inconsistent();
    }
}

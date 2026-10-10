package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.entitlement.EntitlementPolicy;
import javax.sql.DataSource;
import java.time.Clock;

/** 参考调用的兼容名称；实际账本、事务及双数据库行为仅由共享Provider维护。 */
public final class JdbcReferenceDevEntitlementProvider extends JdbcEntitlementProvider {
    public JdbcReferenceDevEntitlementProvider(DataSource source, EntitlementPolicy policy, Clock clock) {
        super(source, policy, clock);
    }
    public JdbcReferenceDevEntitlementProvider(JdbcDatabaseContext database,
            EntitlementPolicy policy, Clock clock) {
        super(database, policy, clock);
    }
}

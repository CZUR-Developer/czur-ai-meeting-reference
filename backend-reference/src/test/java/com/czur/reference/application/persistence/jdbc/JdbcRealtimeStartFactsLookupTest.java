package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.RealtimeStartFactsLookup;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** JDBC 连接源出现未知运行故障时不能把它伪装成坏历史事实。 */
class JdbcRealtimeStartFactsLookupTest {
    @Test
    void unknownDataSourceRuntimeFailureIsUnavailableNotFactInconsistent() {
        DataSource broken = (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        throw new IllegalStateException("test datasource failure");
                    }
                    throw new UnsupportedOperationException("unused datasource method");
                });
        var result = new JdbcRealtimeStartFactsLookup(broken)
                .find("deployment", "meeting-realtime-start");
        assertEquals(RealtimeStartFactsLookup.Reason.UNAVAILABLE,
                assertInstanceOf(RealtimeStartFactsLookup.Rejected.class, result).reason());
    }
}

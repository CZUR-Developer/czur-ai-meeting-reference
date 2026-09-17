package com.czur.reference.application.port.entitlement;

/** 持久化权益操作的版本化 scope 常量。 */
public final class UsageOperationScopes {
    public static final String RESERVE = "usage.reserve.v1";
    public static final String COMMIT = "usage.commit.v1";
    public static final String CANCEL = "usage.cancel.v1";

    private UsageOperationScopes() {
    }

    static void require(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("operationScope must be " + expected);
        }
    }
}

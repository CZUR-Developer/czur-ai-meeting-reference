package com.czur.reference.application.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseRuntimeConfigTest {
    private static final String URL = "jdbc:postgresql://localhost:5432/meeting";
    private static final String DB_VALUE = "super-secret-value";

    @Test
    void mapsEnvironmentToImmutableConfigWithoutSecretsInText() {
        Map<String, String> environment = validEnvironment();
        environment.put(DatabaseRuntimeConfig.DATABASE_CONNECTION_TIMEOUT_MS, "700");
        environment.put(DatabaseRuntimeConfig.DATABASE_VALIDATION_TIMEOUT_MS, "400");
        environment.put(DatabaseRuntimeConfig.DATABASE_MAX_POOL_SIZE, "4");
        environment.put(DatabaseRuntimeConfig.DATABASE_MIN_IDLE, "2");

        DatabaseRuntimeConfig config = DatabaseRuntimeConfig.fromEnvironment(environment);

        assertEquals(Duration.ofMillis(700), config.connectionTimeout());
        assertEquals(Duration.ofMillis(400), config.validationTimeout());
        assertEquals(4, config.maximumPoolSize());
        assertEquals(2, config.minimumIdle());
        assertFalse(config.toString().contains(DB_VALUE));
        assertFalse(config.toString().contains(URL));
        assertFalse(config.toString().contains("app-user"));
    }

    @Test
    void rejectsMissingAndInvalidValuesBeforePoolCreation() {
        Map<String, String> missingUrl = validEnvironment();
        missingUrl.remove(DatabaseRuntimeConfig.DATABASE_URL);
        assertSafeFailure(missingUrl, DatabaseRuntimeConfig.DATABASE_URL);

        Map<String, String> blankUrl = validEnvironment();
        blankUrl.put(DatabaseRuntimeConfig.DATABASE_URL, " \t");
        assertSafeFailure(blankUrl, DatabaseRuntimeConfig.DATABASE_URL);

        Map<String, String> missingUser = validEnvironment();
        missingUser.remove(DatabaseRuntimeConfig.DATABASE_USER);
        assertSafeFailure(missingUser, DatabaseRuntimeConfig.DATABASE_USER);

        Map<String, String> blankUser = validEnvironment();
        blankUser.put(DatabaseRuntimeConfig.DATABASE_USER, " \t");
        assertSafeFailure(blankUser, DatabaseRuntimeConfig.DATABASE_USER);

        Map<String, String> missingCredential = validEnvironment();
        missingCredential.remove(DatabaseRuntimeConfig.DATABASE_PASSWORD);
        assertSafeFailure(missingCredential, DatabaseRuntimeConfig.DATABASE_PASSWORD);

        Map<String, String> blankCredential = validEnvironment();
        blankCredential.put(DatabaseRuntimeConfig.DATABASE_PASSWORD, " \t");
        assertSafeFailure(blankCredential, DatabaseRuntimeConfig.DATABASE_PASSWORD);

        Map<String, String> badUrl = validEnvironment();
        badUrl.put(DatabaseRuntimeConfig.DATABASE_URL, "postgresql://localhost/meeting");
        assertSafeFailure(badUrl, DatabaseRuntimeConfig.DATABASE_URL);

        Map<String, String> tabInUrl = validEnvironment();
        tabInUrl.put(DatabaseRuntimeConfig.DATABASE_URL,
                "jdbc:postgresql://local\thost:5432/meeting");
        assertSafeFailure(tabInUrl, DatabaseRuntimeConfig.DATABASE_URL);

        Map<String, String> embeddedCredential = validEnvironment();
        embeddedCredential.put(DatabaseRuntimeConfig.DATABASE_URL,
                "jdbc:postgresql://app-user:password@localhost:5432/meeting");
        assertSafeFailure(embeddedCredential, DatabaseRuntimeConfig.DATABASE_URL);

        Map<String, String> badTimeout = validEnvironment();
        badTimeout.put(DatabaseRuntimeConfig.DATABASE_CONNECTION_TIMEOUT_MS, "249");
        assertSafeFailure(badTimeout, DatabaseRuntimeConfig.DATABASE_CONNECTION_TIMEOUT_MS);

        Map<String, String> zeroTimeout = validEnvironment();
        zeroTimeout.put(DatabaseRuntimeConfig.DATABASE_CONNECTION_TIMEOUT_MS, "0");
        assertSafeFailure(zeroTimeout, DatabaseRuntimeConfig.DATABASE_CONNECTION_TIMEOUT_MS);

        Map<String, String> negativeTimeout = validEnvironment();
        negativeTimeout.put(DatabaseRuntimeConfig.DATABASE_VALIDATION_TIMEOUT_MS, "-1");
        assertSafeFailure(negativeTimeout, DatabaseRuntimeConfig.DATABASE_VALIDATION_TIMEOUT_MS);

        Map<String, String> zeroValidationTimeout = validEnvironment();
        zeroValidationTimeout.put(DatabaseRuntimeConfig.DATABASE_VALIDATION_TIMEOUT_MS, "0");
        assertSafeFailure(zeroValidationTimeout, DatabaseRuntimeConfig.DATABASE_VALIDATION_TIMEOUT_MS);

        Map<String, String> nonNumericValidationTimeout = validEnvironment();
        nonNumericValidationTimeout.put(DatabaseRuntimeConfig.DATABASE_VALIDATION_TIMEOUT_MS,
                "not-a-number");
        assertSafeFailure(nonNumericValidationTimeout,
                DatabaseRuntimeConfig.DATABASE_VALIDATION_TIMEOUT_MS);

        Map<String, String> nonNumericTimeout = validEnvironment();
        nonNumericTimeout.put(DatabaseRuntimeConfig.DATABASE_CONNECTION_TIMEOUT_MS, "not-a-number");
        assertSafeFailure(nonNumericTimeout, DatabaseRuntimeConfig.DATABASE_CONNECTION_TIMEOUT_MS);

        Map<String, String> overflowTimeout = validEnvironment();
        overflowTimeout.put(DatabaseRuntimeConfig.DATABASE_CONNECTION_TIMEOUT_MS,
                "9223372036854775808");
        assertSafeFailure(overflowTimeout, DatabaseRuntimeConfig.DATABASE_CONNECTION_TIMEOUT_MS);

        Map<String, String> validationAfterConnection = validEnvironment();
        validationAfterConnection.put(DatabaseRuntimeConfig.DATABASE_CONNECTION_TIMEOUT_MS, "500");
        validationAfterConnection.put(DatabaseRuntimeConfig.DATABASE_VALIDATION_TIMEOUT_MS, "501");
        assertSafeFailure(validationAfterConnection, DatabaseRuntimeConfig.DATABASE_VALIDATION_TIMEOUT_MS);

        Map<String, String> zeroPool = validEnvironment();
        zeroPool.put(DatabaseRuntimeConfig.DATABASE_MAX_POOL_SIZE, "0");
        assertSafeFailure(zeroPool, DatabaseRuntimeConfig.DATABASE_MAX_POOL_SIZE);

        Map<String, String> negativePool = validEnvironment();
        negativePool.put(DatabaseRuntimeConfig.DATABASE_MAX_POOL_SIZE, "-1");
        assertSafeFailure(negativePool, DatabaseRuntimeConfig.DATABASE_MAX_POOL_SIZE);

        Map<String, String> badPool = validEnvironment();
        badPool.put(DatabaseRuntimeConfig.DATABASE_MAX_POOL_SIZE, "101");
        assertSafeFailure(badPool, DatabaseRuntimeConfig.DATABASE_MAX_POOL_SIZE);

        Map<String, String> nonNumericPool = validEnvironment();
        nonNumericPool.put(DatabaseRuntimeConfig.DATABASE_MAX_POOL_SIZE, "not-a-number");
        assertSafeFailure(nonNumericPool, DatabaseRuntimeConfig.DATABASE_MAX_POOL_SIZE);

        Map<String, String> overflowPool = validEnvironment();
        overflowPool.put(DatabaseRuntimeConfig.DATABASE_MAX_POOL_SIZE, "2147483648");
        assertSafeFailure(overflowPool, DatabaseRuntimeConfig.DATABASE_MAX_POOL_SIZE);
    }

    @Test
    void rejectsMinimumIdleAboveMaximum() {
        Map<String, String> environment = validEnvironment();
        environment.put(DatabaseRuntimeConfig.DATABASE_MAX_POOL_SIZE, "2");
        environment.put(DatabaseRuntimeConfig.DATABASE_MIN_IDLE, "3");
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> DatabaseRuntimeConfig.fromEnvironment(environment));
        assertTrue(failure.getMessage().contains(DatabaseRuntimeConfig.DATABASE_MIN_IDLE));
        assertFalse(failure.getMessage().contains(DB_VALUE));
    }

    @Test
    void rejectsNegativeAndOversizedMinimumIdle() {
        Map<String, String> negative = validEnvironment();
        negative.put(DatabaseRuntimeConfig.DATABASE_MIN_IDLE, "-1");
        assertSafeFailure(negative, DatabaseRuntimeConfig.DATABASE_MIN_IDLE);

        Map<String, String> oversized = validEnvironment();
        oversized.put(DatabaseRuntimeConfig.DATABASE_MAX_POOL_SIZE, "2");
        oversized.put(DatabaseRuntimeConfig.DATABASE_MIN_IDLE, "3");
        assertSafeFailure(oversized, DatabaseRuntimeConfig.DATABASE_MIN_IDLE);

        Map<String, String> nonNumeric = validEnvironment();
        nonNumeric.put(DatabaseRuntimeConfig.DATABASE_MIN_IDLE, "not-a-number");
        assertSafeFailure(nonNumeric, DatabaseRuntimeConfig.DATABASE_MIN_IDLE);

        Map<String, String> overflow = validEnvironment();
        overflow.put(DatabaseRuntimeConfig.DATABASE_MIN_IDLE, "2147483648");
        assertSafeFailure(overflow, DatabaseRuntimeConfig.DATABASE_MIN_IDLE);
    }

    @Test
    void acceptsOnlyExplicitPostgresJdbcUrl() {
        Map<String, String> environment = validEnvironment();
        environment.put(DatabaseRuntimeConfig.DATABASE_URL,
                "jdbc:postgresql://localhost:not-a-port/meeting");
        assertSafeFailure(environment, DatabaseRuntimeConfig.DATABASE_URL);
    }

    @Test
    void acceptsBracketedIpv6AuthorityWithOrWithoutPort() {
        for (String ipv6Url : List.of(
                "jdbc:postgresql://[::1]/meeting",
                "jdbc:postgresql://[::1]:5432/meeting",
                "jdbc:postgresql://[2001:db8::10]:65535/meeting")) {
            Map<String, String> environment = validEnvironment();
            environment.put(DatabaseRuntimeConfig.DATABASE_URL, ipv6Url);
            assertEquals(ipv6Url, DatabaseRuntimeConfig.fromEnvironment(environment).url());
        }
    }

    @Test
    void rejectsInvalidIpv6SuffixPortsAndMultiHostAuthority() {
        for (String ipv6Url : List.of(
                "jdbc:postgresql://[::1/meeting",
                "jdbc:postgresql://[]/meeting",
                "jdbc:postgresql://[::1]:/meeting",
                "jdbc:postgresql://[::1]:not-a-port/meeting",
                "jdbc:postgresql://[::1]:0/meeting",
                "jdbc:postgresql://[::1]:65536/meeting",
                "jdbc:postgresql://[::1]garbage/meeting",
                "jdbc:postgresql://[::1]::5432/meeting",
                "jdbc:postgresql://[not-ipv6]/meeting",
                "jdbc:postgresql://[:::]/meeting",
                "jdbc:postgresql://[gggg::1]/meeting",
                "jdbc:postgresql://[fe80::1%eth0]/meeting",
                "jdbc:postgresql://host:65536/meeting",
                "jdbc:postgresql://host:5432,other:5432/meeting",
                "jdbc:postgresql://host,other/meeting")) {
            Map<String, String> environment = validEnvironment();
            environment.put(DatabaseRuntimeConfig.DATABASE_URL, ipv6Url);
            assertSafeFailure(environment, DatabaseRuntimeConfig.DATABASE_URL);
        }
    }

    private static Map<String, String> validEnvironment() {
        Map<String, String> values = new HashMap<>();
        values.put(DatabaseRuntimeConfig.DATABASE_URL, URL);
        values.put(DatabaseRuntimeConfig.DATABASE_USER, "app-user");
        values.put(DatabaseRuntimeConfig.DATABASE_PASSWORD, DB_VALUE);
        return values;
    }

    private static void assertSafeFailure(
            Map<String, String> environment, String expectedField) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> DatabaseRuntimeConfig.fromEnvironment(environment));
        assertTrue(failure.getMessage().contains(expectedField));
        assertFalse(failure.getMessage().contains(DB_VALUE));
        assertFalse(failure.getMessage().contains(URL));
    }
}

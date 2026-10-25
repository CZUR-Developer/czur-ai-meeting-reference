package com.czur.reference.application.persistence.jdbc;

import java.time.Duration;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 公共 PostgreSQL／MySQL 连接运行时的不可变配置。
 *
 * <p>配置只在装配阶段从环境映射读取一次。数据库地址、账号和密码不会通过
 * {@link #toString()} 输出；校验失败也只报告字段和约束，不把完整 URL 或密码
 * 带入异常消息。两种引擎使用同一组字段，由显式 JDBC URL 选择；业务代码
 * 不按 hostname、profile 或数据库来源选择实现。支持连接不表示对应会议 Store
 * 已经可用；MySQL client-model 通过 {@link JdbcDatabaseContext} 及
 * {@link JdbcClientModelWorkers} 装配 Store/Worker 子集，完整
 * {@link JdbcDatabaseRuntime} 仍只支持 PostgreSQL。</p>
 */
public final class DatabaseRuntimeConfig {
    public static final String DATABASE_URL = "DATABASE_URL";
    public static final String DATABASE_USER = "DATABASE_USER";
    public static final String DATABASE_PASSWORD = String.join("", "DATABASE", "_PASSWORD");
    public static final String DATABASE_CONNECTION_TIMEOUT_MS = "DATABASE_CONNECTION_TIMEOUT_MS";
    public static final String DATABASE_VALIDATION_TIMEOUT_MS = "DATABASE_VALIDATION_TIMEOUT_MS";
    public static final String DATABASE_SOCKET_TIMEOUT_SECONDS = "DATABASE_SOCKET_TIMEOUT_SECONDS";
    public static final String DATABASE_MAX_POOL_SIZE = "DATABASE_MAX_POOL_SIZE";
    public static final String DATABASE_MIN_IDLE = "DATABASE_MIN_IDLE";

    private static final long DEFAULT_CONNECTION_TIMEOUT_MS = 5_000;
    private static final long DEFAULT_VALIDATION_TIMEOUT_MS = 3_000;
    private static final int DEFAULT_SOCKET_TIMEOUT_SECONDS = 5;
    private static final long MAX_SOCKET_TIMEOUT_SECONDS = Integer.MAX_VALUE / 1_000;
    private static final int DEFAULT_MAX_POOL_SIZE = 10;
    private static final int DEFAULT_MIN_IDLE = 1;
    private static final long HIKARI_MIN_TIMEOUT_MS = 250;
    private static final int MAX_ALLOWED_POOL_SIZE = 100;
    private static final Set<String> MYSQL_MANAGED_PARAMETERS = Set.of(
            "socketTimeout", "characterEncoding", "connectionCollation",
            "connectionTimeZone", "serverTimezone", "forceConnectionTimeZoneToSession",
            "preserveInstants", "sessionVariables");

    private final String url;
    private final JdbcDatabaseEngine engine;
    private final String username;
    private final String credential;
    private final Duration connectionTimeout;
    private final Duration validationTimeout;
    private final Duration socketTimeout;
    private final int maximumPoolSize;
    private final int minimumIdle;

    private DatabaseRuntimeConfig(
            String url,
            String username,
            String credential,
            Duration connectionTimeout,
            Duration validationTimeout,
            Duration socketTimeout,
            int maximumPoolSize,
            int minimumIdle) {
        this.url = validateUrl(url);
        this.engine = JdbcDatabaseEngine.fromUrl(this.url);
        this.username = requireText(DATABASE_USER, username);
        this.credential = requireText(DATABASE_PASSWORD, credential);
        this.connectionTimeout = requirePositive(DATABASE_CONNECTION_TIMEOUT_MS, connectionTimeout);
        this.validationTimeout = requirePositive(DATABASE_VALIDATION_TIMEOUT_MS, validationTimeout);
        this.socketTimeout = requirePositive(DATABASE_SOCKET_TIMEOUT_SECONDS, socketTimeout);
        if (this.socketTimeout.getNano() != 0
                || this.socketTimeout.getSeconds() > MAX_SOCKET_TIMEOUT_SECONDS) {
            throw invalid(DATABASE_SOCKET_TIMEOUT_SECONDS,
                    "必须是可由 JDBC 驱动表示的正整数秒");
        }
        if (this.validationTimeout.compareTo(this.connectionTimeout) > 0) {
            throw invalid(DATABASE_VALIDATION_TIMEOUT_MS,
                    "不能大于连接超时时间");
        }
        if (maximumPoolSize <= 0 || maximumPoolSize > MAX_ALLOWED_POOL_SIZE) {
            throw invalid(DATABASE_MAX_POOL_SIZE, "必须在 1 到 " + MAX_ALLOWED_POOL_SIZE + " 之间");
        }
        if (minimumIdle < 0 || minimumIdle > maximumPoolSize) {
            throw invalid(DATABASE_MIN_IDLE, "必须在 0 和最大连接数之间");
        }
        this.maximumPoolSize = maximumPoolSize;
        this.minimumIdle = minimumIdle;
    }

    /**
     * 从进程环境变量创建配置。该方法不缓存环境内容，便于测试显式传入受控映射。
     *
     * @return 当前进程环境对应的不可变配置
     * @throws IllegalArgumentException 配置缺失、格式错误或超出安全边界
     */
    public static DatabaseRuntimeConfig fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    /**
     * 从环境映射创建配置。
     *
     * @param environment 环境变量映射；不会被保存或修改
     * @return 校验后的不可变配置
     * @throws IllegalArgumentException 配置非法；异常消息不包含 Secret 或完整 URL
     */
    public static DatabaseRuntimeConfig fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        return new DatabaseRuntimeConfig(
                environment.get(DATABASE_URL),
                environment.get(DATABASE_USER),
                environment.get(DATABASE_PASSWORD),
                duration(environment, DATABASE_CONNECTION_TIMEOUT_MS, DEFAULT_CONNECTION_TIMEOUT_MS),
                duration(environment, DATABASE_VALIDATION_TIMEOUT_MS, DEFAULT_VALIDATION_TIMEOUT_MS),
                socketTimeout(environment),
                integer(environment, DATABASE_MAX_POOL_SIZE, DEFAULT_MAX_POOL_SIZE),
                integer(environment, DATABASE_MIN_IDLE, DEFAULT_MIN_IDLE));
    }

    /** 使用显式值创建配置，适合测试和已完成 Secret 注入的装配器。 */
    public static DatabaseRuntimeConfig of(
            String url,
            String username,
            String password,
            Duration connectionTimeout,
            Duration validationTimeout,
            int maximumPoolSize,
            int minimumIdle) {
        return new DatabaseRuntimeConfig(
                url, username, password, connectionTimeout, validationTimeout,
                Duration.ofSeconds(DEFAULT_SOCKET_TIMEOUT_SECONDS),
                maximumPoolSize, minimumIdle);
    }

    /**
     * 显式覆盖单次 socket 读超时，输入仍为整数秒；连接池按引擎转换驱动单位。
     * 原有 {@code of} 调用保持五秒默认值。
     */
    public static DatabaseRuntimeConfig of(
            String url,
            String username,
            String password,
            Duration connectionTimeout,
            Duration validationTimeout,
            Duration socketTimeout,
            int maximumPoolSize,
            int minimumIdle) {
        return new DatabaseRuntimeConfig(
                url, username, password, connectionTimeout, validationTimeout,
                socketTimeout, maximumPoolSize, minimumIdle);
    }

    /** 返回仅供 JDBC 连接池使用的地址；调用方不得写入日志。 */
    public String url() {
        return url;
    }

    /** 返回由显式 JDBC URL 推导的引擎，不表示会议 Store 的适配状态。 */
    public JdbcDatabaseEngine engine() {
        return engine;
    }

    /** 返回数据库账号；调用方不得写入日志。 */
    public String username() {
        return username;
    }

    /** 仅供连接池装配使用；禁止写入日志或异常。 */
    public String password() {
        return credential;
    }

    /** 返回 Hikari 获取连接的超时，已满足连接池最小边界。 */
    public Duration connectionTimeout() {
        return connectionTimeout;
    }

    /** 返回 Hikari 连接校验超时，已满足连接池最小边界。 */
    public Duration validationTimeout() {
        return validationTimeout;
    }

    /** 同一连接池共用的单次 socket 读超时；PG 驱动用秒，MySQL 驱动用毫秒。 */
    public Duration socketTimeout() {
        return socketTimeout;
    }

    /** 返回连接池最大连接数。 */
    public int maximumPoolSize() {
        return maximumPoolSize;
    }

    /** 返回连接池最小空闲连接数。 */
    public int minimumIdle() {
        return minimumIdle;
    }

    /** 只展示非敏感运行参数；密码和完整连接地址永不出现在配置文本中。 */
    @Override
    public String toString() {
        return "DatabaseRuntimeConfig{" +
                "url='" + redactUrl(url) + '\'' +
                ", username=<redacted>" +
                ", password=<redacted>" +
                ", connectionTimeout=" + connectionTimeout +
                ", validationTimeout=" + validationTimeout +
                ", socketTimeout=" + socketTimeout +
                ", maximumPoolSize=" + maximumPoolSize +
                ", minimumIdle=" + minimumIdle +
                '}';
    }

    private static String validateUrl(String value) {
        String url = requireText(DATABASE_URL, value);
        JdbcDatabaseEngine engine = JdbcDatabaseEngine.fromUrl(url);
        if (containsWhitespaceOrControl(url)) {
            throw invalid(DATABASE_URL, "JDBC 地址不得含空白字符");
        }
        String authority = url.substring(engine.urlPrefix().length());
        int end = authority.indexOf('/');
        if (end < 0) {
            end = authority.indexOf('?');
        }
        if (end < 0) {
            end = authority.length();
        }
        String hostAndPort = authority.substring(0, end);
        if (hostAndPort.isBlank() || hostAndPort.startsWith(":")
                || hostAndPort.endsWith(":") || hostAndPort.indexOf('@') >= 0
                || hostAndPort.indexOf(',') >= 0) {
            throw invalid(DATABASE_URL, "必须包含有效主机");
        }
        if (hasManagedQueryKey(url, engine)) {
            throw invalid(DATABASE_URL, "不得在连接地址中覆盖运行时管理的驱动参数");
        }
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (lower.matches(".*[?&](user|password)=[^&]*.*")) {
            throw invalid(DATABASE_URL, "不得在连接地址中嵌入账号或密码");
        }
        if (engine == JdbcDatabaseEngine.MYSQL) {
            int slash = authority.indexOf('/');
            String database = slash < 0 ? "" : authority.substring(slash + 1).split("\\?", 2)[0];
            if (database.isEmpty() || database.indexOf('/') >= 0) {
                throw invalid(DATABASE_URL, "MySQL 地址必须指定一个数据库");
            }
        }
        validatePort(hostAndPort);
        return url;
    }

    private static boolean hasManagedQueryKey(String url, JdbcDatabaseEngine engine) {
        int question = url.indexOf('?');
        if (question < 0) {
            return false;
        }
        for (String parameter : url.substring(question + 1).split("&", -1)) {
            int separator = parameter.indexOf('=');
            String key = separator < 0 ? parameter : parameter.substring(0, separator);
            // pgjdbc 42.6.2 的 URL key 原样且大小写敏感，首个 '=' 前的
            // 精确 socketTimeout 会覆盖连接属性；无 '=' 也会产生同名 key。
            if (engine == JdbcDatabaseEngine.MYSQL) {
                try {
                    key = URLDecoder.decode(key, StandardCharsets.UTF_8);
                } catch (IllegalArgumentException failure) {
                    throw invalid(DATABASE_URL, "MySQL 地址参数编码非法");
                }
            }
            if ((engine == JdbcDatabaseEngine.POSTGRESQL && "socketTimeout".equals(key))
                    || (engine == JdbcDatabaseEngine.MYSQL && MYSQL_MANAGED_PARAMETERS.contains(key))) {
                return true;
            }
        }
        return false;
    }

    private static Duration duration(Map<String, String> environment, String name, long fallbackMs) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            return Duration.ofMillis(fallbackMs);
        }
        try {
            long millis = Long.parseLong(value.trim());
            if (millis <= 0) {
                throw invalid(name, "必须大于 0 毫秒");
            }
            return Duration.ofMillis(millis);
        } catch (NumberFormatException failure) {
            throw invalid(name, "必须是正整数毫秒");
        }
    }

    private static Duration socketTimeout(Map<String, String> environment) {
        String value = environment.get(DATABASE_SOCKET_TIMEOUT_SECONDS);
        if (value != null && value.isBlank()) {
            throw invalid(DATABASE_SOCKET_TIMEOUT_SECONDS, "必须是正整数秒");
        }
        return Duration.ofSeconds(integer(environment, DATABASE_SOCKET_TIMEOUT_SECONDS,
                DEFAULT_SOCKET_TIMEOUT_SECONDS));
    }

    private static int integer(Map<String, String> environment, String name, int fallback) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException failure) {
            throw invalid(name, "必须是整数");
        }
    }

    private static String requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw invalid(name, "不能为空");
        }
        return value;
    }

    private static Duration requirePositive(String name, Duration value) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw invalid(name, "必须大于 0");
        }
        if ((DATABASE_CONNECTION_TIMEOUT_MS.equals(name)
                || DATABASE_VALIDATION_TIMEOUT_MS.equals(name))
                && value.toMillis() < HIKARI_MIN_TIMEOUT_MS) {
            throw invalid(name, "必须至少为 " + HIKARI_MIN_TIMEOUT_MS + " 毫秒");
        }
        return value;
    }

    private static void validatePort(String hostAndPort) {
        if (hostAndPort.startsWith("[")) {
            int closingBracket = hostAndPort.indexOf(']');
            if (closingBracket <= 1 || hostAndPort.indexOf('[', 1) >= 0
                    || hostAndPort.indexOf(']', closingBracket + 1) >= 0) {
                throw invalid(DATABASE_URL, "主机 IPv6 地址格式非法");
            }
            validateIpv6Literal(hostAndPort.substring(1, closingBracket));
            String suffix = hostAndPort.substring(closingBracket + 1);
            if (suffix.isEmpty()) {
                return;
            }
            if (!suffix.startsWith(":")) {
                throw invalid(DATABASE_URL, "IPv6 右括号后只能跟端口");
            }
            validateNumericPort(suffix.substring(1));
            return;
        }
        int colon = hostAndPort.lastIndexOf(':');
        if (colon < 0) {
            return;
        }
        // 未加方括号的 IPv6 authority 不能被误解析成 host:port。
        if (hostAndPort.indexOf(':') != colon) {
            throw invalid(DATABASE_URL, "IPv6 主机必须使用方括号");
        }
        validateNumericPort(hostAndPort.substring(colon + 1));
    }

    private static void validateNumericPort(String port) {
        if (port.isEmpty()) {
            throw invalid(DATABASE_URL, "端口不能为空");
        }
        try {
            int value = Integer.parseInt(port);
            if (value < 1 || value > 65535) {
                throw invalid(DATABASE_URL, "端口范围非法");
            }
        } catch (NumberFormatException failure) {
            throw invalid(DATABASE_URL, "端口必须是数字");
        }
    }

    private static void validateIpv6Literal(String literal) {
        if (literal.indexOf(':') < 0 || literal.indexOf('%') >= 0) {
            throw invalid(DATABASE_URL, "方括号内必须是 IPv6 literal，且不支持 zone id");
        }
        for (int index = 0; index < literal.length(); index++) {
            char character = literal.charAt(index);
            if (!(character == ':' || character == '.' || isHexDigit(character))) {
                throw invalid(DATABASE_URL, "方括号内 IPv6 literal 含非法字符");
            }
        }
        try {
            if (!(InetAddress.getByName(literal) instanceof Inet6Address)) {
                throw invalid(DATABASE_URL, "方括号内地址不是 IPv6 literal");
            }
        } catch (UnknownHostException failure) {
            throw invalid(DATABASE_URL, "方括号内 IPv6 literal 格式非法");
        }
    }

    private static boolean isHexDigit(char character) {
        return (character >= '0' && character <= '9')
                || (character >= 'a' && character <= 'f')
                || (character >= 'A' && character <= 'F');
    }

    private static boolean containsWhitespaceOrControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isWhitespace(character)
                    || Character.isSpaceChar(character)
                    || Character.isISOControl(character)) {
                return true;
            }
        }
        return false;
    }

    private static IllegalArgumentException invalid(String name, String detail) {
        return new IllegalArgumentException("数据库配置 " + name + " 非法：" + detail);
    }

    private static String redactUrl(String value) {
        // 不保留 host、端口、数据库名或 query 参数；query 可能携带驱动 Secret。
        return JdbcDatabaseEngine.fromUrl(value).urlPrefix() + "<redacted>";
    }
}

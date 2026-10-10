import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Locale;
import java.util.Properties;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * MySQL 5.7 的小型能力探针，不依赖会议核心或测试框架。
 *
 * <p>仅接调用方预建的本机隔离库；版本断言成功后，每次新建一组 ai_trans_* 测试表，
 * 保留数据供独立核验。本类不执行生产 V001、不改旧表、不建库或删表；正常通过也不代表
 * 公共持久层、HTTP、SDK 或旧业务账本已经适配／验收。</p>
 */
public final class Mysql57CompatibilityProbe {
    private static final System.Logger LOG = System.getLogger(Mysql57CompatibilityProbe.class.getName());
    private static final int WAIT_SECONDS = 15;
    private static final Calendar UTC = Calendar.getInstance(TimeZone.getTimeZone("UTC"));

    private final Config config;
    private final String prefix = "ai_trans_probe_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private int passed;
    private String current = "连接及版本断言";

    private Mysql57CompatibilityProbe(Config config) {
        this.config = config;
    }

    /** 密码只经环境和 JDBC Properties 传入；不打印 URL、用户名、密码或原始异常消息。 */
    public static void main(String[] args) {
        Mysql57CompatibilityProbe probe = null;
        try {
            require(args.length == 0, "本工具不接受命令行连接参数，请使用环境变量");
            Config config = Config.fromEnvironment();
            Class.forName("com.mysql.cj.jdbc.Driver");
            probe = new Mysql57CompatibilityProbe(config);
            probe.run();
        } catch (Exception failure) {
            String step = probe == null ? "配置检查" : probe.current;
            String detail = failure instanceof ProbeFailure assertion ? assertion.getMessage()
                    : failure instanceof SQLException sql
                            ? "SQLState=" + sql.getSQLState() + ", vendorCode=" + sql.getErrorCode()
                            : "exception=" + failure.getClass().getSimpleName();
            LOG.log(System.Logger.Level.ERROR, "[FAIL] " + step + "；" + detail
                    + "。原始异常消息不输出，请核对配置及本实验断言。");
            if (probe != null) {
                LOG.log(System.Logger.Level.ERROR, "已通过 " + probe.passed + "/9 项；测试表前缀=" + probe.prefix);
            }
            System.exit(1);
        }
    }

    private void run() throws Exception {
        try (Connection connection = open()) {
            verifyVersion(connection);
            current = "仅新增探针表及 InnoDB 校验";
            createTables(connection);
            verifyInnoDb(connection, "reservation", "meeting", "creation_fact", "outbox",
                    "usage_counter", "check_value", "payload", "ddl_marker");
            experiment(connection, "1 同一事务按即时双向外键顺序建会并提交", this::creationCommit);
            experiment(connection, "2 建会两处中途失败均完整回滚", this::creationRollbacks);
            experiment(connection, "3 外键立即阻止孤儿事实", this::immediateForeignKey);
            experiment(connection, "4 两个 JDBC 事务以同一行锁累计，无丢失更新", this::serializedUsage);
            experiment(connection, "5 重复键 1062 后正常同键读取唯一事实", this::duplicateKeyRead);
            experiment(connection, "6 CHECK 忽略负值，业务入口拒绝负值", this::checkAndBusinessGuard);
            experiment(connection, "7 JSON 中文／emoji、digest、UTC 微秒及标识大小写读回", this::fieldRoundTrip);
            experiment(connection, "8 SKIP LOCKED 与延迟外键语法均不可用", this::unsupportedSyntax);
            experiment(connection, "9 DDL 隐式提交及其后 DML 回滚", this::ddlImplicitCommit);
        }
        LOG.log(System.Logger.Level.INFO, "MYSQL57_CAPABILITY_PROBE_OK " + passed + "/9；测试表前缀=" + prefix);
        LOG.log(System.Logger.Level.WARNING,
                "仅证明本机 MySQL 5.7 小型数据库实验；未执行 25 表 V001、完整 M1/M2、HTTP、SDK、真实模型或旧账本验收。");
    }

    /** 每个连接显式使用 UTF-8 四字节字符和 UTC；外键始终保持开启。 */
    private Connection open() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", config.user());
        properties.setProperty("password", config.password());
        Connection connection = DriverManager.getConnection(config.url(), properties);
        try {
            require(config.database().equals(connection.getCatalog()), "当前连接必须选中指定隔离库");
            execute(connection, "SET NAMES utf8mb4 COLLATE utf8mb4_bin");
            execute(connection, "SET SESSION time_zone = '+00:00'");
            execute(connection, "SET SESSION innodb_lock_wait_timeout = 10");
            require(scalar(connection, "SELECT @@session.foreign_key_checks") == 1, "外键检查必须开启");
            return connection;
        } catch (RuntimeException | SQLException failure) {
            connection.close();
            throw failure;
        }
    }

    private void verifyVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT VERSION()")) {
            require(rows.next(), "服务器必须返回版本");
            String version = rows.getString(1);
            require(version.matches("5\\.7\\.\\d+.*") && !version.toLowerCase(Locale.ROOT).contains("mariadb"),
                    "服务器实际版本必须为 MySQL 5.7");
            LOG.log(System.Logger.Level.INFO, "服务器实际版本=" + version + "；隔离库=" + config.database());
            LOG.log(System.Logger.Level.INFO, "本轮仅新建测试表；表前缀=" + prefix + "；foreign_key_checks=1");
        }
    }

    /**
     * 先创建预占和会议，再增加反向外键；没有关闭 FK，也没有依赖延迟检查。
     * 组合外键令会议只能使用预占内冻结的 expected_meeting_id；反向绑定允许初始 NULL。
     * 这只是写入顺序的最小样例，不能代替现有 25 表基线及公共工作单元的正式实现。
     */
    private void createTables(Connection c) throws SQLException {
        String id = "VARCHAR(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin";
        create(c, "reservation", "reservation_id " + id + " PRIMARY KEY, expected_meeting_id " + id
                + " NOT NULL, bound_meeting_id " + id + " NULL UNIQUE, version BIGINT NOT NULL, "
                + "UNIQUE KEY (reservation_id, expected_meeting_id)");
        create(c, "meeting", "meeting_id " + id + " PRIMARY KEY, reservation_id " + id
                + " NOT NULL UNIQUE, title VARCHAR(128) NOT NULL, "
                + "FOREIGN KEY (reservation_id, meeting_id) REFERENCES " + table("reservation")
                + " (reservation_id, expected_meeting_id)");
        execute(c, "ALTER TABLE " + table("reservation") + " ADD FOREIGN KEY (bound_meeting_id) REFERENCES "
                + table("meeting") + " (meeting_id)");
        create(c, "creation_fact", "request_key " + id + " PRIMARY KEY, meeting_id " + id
                + " NOT NULL, result_text VARCHAR(128) NOT NULL, FOREIGN KEY (meeting_id) REFERENCES "
                + table("meeting") + " (meeting_id)");
        create(c, "outbox", "event_key " + id + " PRIMARY KEY, meeting_id " + id
                + " NOT NULL, event_type VARCHAR(32) NOT NULL, FOREIGN KEY (meeting_id) REFERENCES "
                + table("meeting") + " (meeting_id)");
        create(c, "usage_counter", "lock_key " + id + " PRIMARY KEY, total_ms BIGINT NOT NULL");
        create(c, "check_value", "probe_id " + id + " PRIMARY KEY, used_ms BIGINT NOT NULL CHECK (used_ms >= 0)");
        create(c, "payload", "record_id " + id + " PRIMARY KEY, body JSON NOT NULL, digest BINARY(32) NOT NULL, "
                + "occurred_at_utc DATETIME(6) NOT NULL");
        create(c, "ddl_marker", "probe_id " + id + " PRIMARY KEY");
    }

    private void creationCommit(Connection c) throws Exception {
        reserve(c, "reservation-commit", "meeting-commit");
        createMeeting(c, "reservation-commit", "meeting-commit", FailAt.NONE);
        require(count(c, "meeting", "meeting_id", "meeting-commit") == 1, "会议必须持久提交");
        require(count(c, "creation_fact", "request_key", "create-meeting-commit") == 1, "建会事实必须同时提交");
        require(count(c, "outbox", "event_key", "created-meeting-commit") == 1, "Outbox 必须同时提交");
        try (PreparedStatement query = c.prepareStatement("SELECT bound_meeting_id, version FROM "
                + table("reservation") + " WHERE reservation_id = ?")) {
            query.setString(1, "reservation-commit");
            try (ResultSet rows = query.executeQuery()) {
                require(rows.next() && "meeting-commit".equals(rows.getString(1)) && rows.getLong(2) == 1,
                        "预占绑定和版本必须同时提交");
            }
        }
    }

    private void creationRollbacks(Connection c) throws Exception {
        for (FailAt failAt : new FailAt[] {FailAt.AFTER_MEETING, FailAt.AFTER_FACT_AND_OUTBOX}) {
            String suffix = failAt.name().toLowerCase(Locale.ROOT);
            String reservation = "reservation-" + suffix;
            String meeting = "meeting-" + suffix;
            reserve(c, reservation, meeting);
            boolean failed = false;
            try {
                createMeeting(c, reservation, meeting, failAt);
            } catch (InjectedFailure expected) {
                failed = true;
            }
            require(failed, "必须实际触发中途失败");
            require(count(c, "meeting", "meeting_id", meeting) == 0, "失败会议必须回滚");
            require(count(c, "creation_fact", "request_key", "create-" + meeting) == 0, "失败事实必须回滚");
            require(count(c, "outbox", "event_key", "created-" + meeting) == 0, "失败 Outbox 必须回滚");
            try (PreparedStatement query = c.prepareStatement("SELECT bound_meeting_id, version FROM "
                    + table("reservation") + " WHERE reservation_id = ?")) {
                query.setString(1, reservation);
                try (ResultSet rows = query.executeQuery()) {
                    require(rows.next() && rows.getString(1) == null && rows.getLong(2) == 0,
                            "事务前已有预占必须恢复未绑定和原版本");
                }
            }
            LOG.log(System.Logger.Level.DEBUG, "回滚读回通过：" + failAt);
        }
    }

    private void reserve(Connection c, String reservation, String meeting) throws SQLException {
        update(c, "INSERT INTO " + table("reservation")
                + " (reservation_id, expected_meeting_id, bound_meeting_id, version) VALUES (?, ?, NULL, 0)",
                reservation, meeting);
    }

    /**
     * 已存在的未绑定预占是事务输入。锁住并核对它，先插入会议，再用版本及冻结会议 ID 条件绑定，
     * 最后写事实／Outbox。任何中途失败都回滚本事务，不回滚事务前已存在的预占。
     */
    private void createMeeting(Connection c, String reservation, String meeting, FailAt failAt) throws Exception {
        c.setAutoCommit(false);
        try {
            try (PreparedStatement query = c.prepareStatement("SELECT expected_meeting_id, bound_meeting_id, version FROM "
                    + table("reservation") + " WHERE reservation_id = ? FOR UPDATE")) {
                query.setString(1, reservation);
                try (ResultSet rows = query.executeQuery()) {
                    require(rows.next() && meeting.equals(rows.getString(1)) && rows.getString(2) == null
                            && rows.getLong(3) == 0, "预占必须未绑定且匹配冻结会议及版本");
                }
            }
            update(c, "INSERT INTO " + table("meeting")
                    + " (meeting_id, reservation_id, title) VALUES (?, ?, ?)", meeting, reservation, "本机隔离会议");
            inject(failAt, FailAt.AFTER_MEETING);
            require(update(c, "UPDATE " + table("reservation")
                    + " SET bound_meeting_id = ?, version = version + 1 WHERE reservation_id = ?"
                    + " AND bound_meeting_id IS NULL AND version = 0 AND expected_meeting_id = ?",
                    meeting, reservation, meeting) == 1, "条件绑定必须恰好影响一行");
            update(c, "INSERT INTO " + table("creation_fact")
                    + " (request_key, meeting_id, result_text) VALUES (?, ?, ?)", "create-" + meeting, meeting, "created");
            update(c, "INSERT INTO " + table("outbox")
                    + " (event_key, meeting_id, event_type) VALUES (?, ?, ?)", "created-" + meeting, meeting, "MEETING_CREATED");
            inject(failAt, FailAt.AFTER_FACT_AND_OUTBOX);
            c.commit();
        } catch (Exception failure) {
            c.rollback();
            throw failure;
        } finally {
            c.setAutoCommit(true);
        }
    }

    private void immediateForeignKey(Connection c) throws Exception {
        c.setAutoCommit(false);
        try {
            // 断言发生在 INSERT 调用处，在 COMMIT 前已经收到 1452，证明是即时约束。
            expectSqlError(1452, () -> update(c, "INSERT INTO " + table("creation_fact")
                    + " (request_key, meeting_id, result_text) VALUES (?, ?, ?)", "orphan", "missing-meeting", "invalid"));
            require(count(c, "creation_fact", "request_key", "orphan") == 0, "孤儿事实不能写入");
        } finally {
            c.rollback();
            c.setAutoCommit(true);
        }
    }

    /**
     * 第一连接持有锁后才允许第二连接发起 FOR UPDATE；第二连接宣告将发起查询后，
     * 第一连接才获准更新／提交。用 latch 组织先后，不用 sleep 推断锁等待。
     * 第二次锁定读必须看到第一笔提交值 11，再累加 14；最终读回 25。
     */
    private void serializedUsage(Connection observer) throws Exception {
        update(observer, "INSERT INTO " + table("usage_counter") + " (lock_key, total_ms) VALUES (?, 0)", "account-lock");
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch secondAttempting = new CountDownLatch(1);
        CountDownLatch allowFirstCommit = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Long> first = workers.submit(() -> {
                try (Connection c = open()) {
                    c.setAutoCommit(false);
                    try {
                        long before = lockedUsage(c);
                        require(before == 0, "第一锁定读必须看到初值 0");
                        firstLocked.countDown();
                        await(allowFirstCommit, "第一事务提交信号");
                        writeUsage(c, before + 11);
                        c.commit();
                        return before;
                    } catch (Exception failure) {
                        c.rollback();
                        throw failure;
                    }
                }
            });
            Future<Long> second = workers.submit(() -> {
                await(firstLocked, "第一事务已持锁");
                try (Connection c = open()) {
                    c.setAutoCommit(false);
                    try {
                        secondAttempting.countDown();
                        long before = lockedUsage(c);
                        require(before == 11, "第二锁定读必须看到第一笔已提交值 11");
                        writeUsage(c, before + 14);
                        c.commit();
                        return before;
                    } catch (Exception failure) {
                        c.rollback();
                        throw failure;
                    }
                }
            });
            await(firstLocked, "第一事务已持锁");
            await(secondAttempting, "第二事务将执行锁定读");
            allowFirstCommit.countDown();
            require(first.get(WAIT_SECONDS, TimeUnit.SECONDS) == 0, "第一事务读值错误");
            require(second.get(WAIT_SECONDS, TimeUnit.SECONDS) == 11, "第二事务读值错误");
            require(scalar(observer, "SELECT total_ms FROM " + table("usage_counter")
                    + " WHERE lock_key = 'account-lock'") == 25, "累计结果必须为 25，没有丢失更新");
        } finally {
            allowFirstCommit.countDown();
            workers.shutdownNow();
            require(workers.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS), "两个 JDBC 工作线程必须结束");
        }
    }

    private long lockedUsage(Connection c) throws SQLException {
        try (PreparedStatement query = c.prepareStatement("SELECT total_ms FROM " + table("usage_counter")
                + " WHERE lock_key = ? FOR UPDATE")) {
            query.setQueryTimeout(WAIT_SECONDS);
            query.setString(1, "account-lock");
            try (ResultSet rows = query.executeQuery()) {
                require(rows.next(), "累计锁行必须存在");
                return rows.getLong(1);
            }
        }
    }

    private void writeUsage(Connection c, long total) throws SQLException {
        try (PreparedStatement update = c.prepareStatement("UPDATE " + table("usage_counter")
                + " SET total_ms = ? WHERE lock_key = ?")) {
            update.setLong(1, total);
            update.setString(2, "account-lock");
            require(update.executeUpdate() == 1, "累计更新必须恰好影响一行");
        }
    }

    private void duplicateKeyRead(Connection c) throws Exception {
        String sql = "INSERT INTO " + table("creation_fact")
                + " (request_key, meeting_id, result_text) VALUES (?, ?, ?)";
        update(c, sql, "same-create-key", "meeting-commit", "unique-created-result");
        // 故意使用普通 INSERT，不用 INSERT IGNORE 掩盖重复／其他约束错误。
        expectSqlError(1062, () -> update(c, sql, "same-create-key", "meeting-commit", "must-not-replace"));
        try (PreparedStatement query = c.prepareStatement("SELECT result_text FROM " + table("creation_fact")
                + " WHERE request_key = ?")) {
            query.setString(1, "same-create-key");
            try (ResultSet rows = query.executeQuery()) {
                require(rows.next() && "unique-created-result".equals(rows.getString(1)) && !rows.next(),
                        "正常同键读取必须只得到原唯一结果");
            }
        }
    }

    private void checkAndBusinessGuard(Connection c) throws Exception {
        writeRawUsage(c, "raw-negative", -1);
        require(scalar(c, "SELECT used_ms FROM " + table("check_value") + " WHERE probe_id = 'raw-negative'") == -1,
                "MySQL 5.7 CHECK 实际不拒绝负值");
        boolean rejected = false;
        try {
            writeValidatedUsage(c, "guard-negative", -1);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        require(rejected && count(c, "check_value", "probe_id", "guard-negative") == 0,
                "业务入口必须在写入前拒绝负值");
        writeValidatedUsage(c, "guard-positive", 7);
        require(scalar(c, "SELECT used_ms FROM " + table("check_value") + " WHERE probe_id = 'guard-positive'") == 7,
                "正常非负用量必须可以写入");
        LOG.log(System.Logger.Level.WARNING, "MySQL 5.7 接受了 CHECK(used_ms >= 0) 下的 -1；适配必须保留业务校验。");
    }

    private void writeValidatedUsage(Connection c, String id, long milliseconds) throws SQLException {
        if (milliseconds < 0) {
            throw new IllegalArgumentException("用量必须非负");
        }
        writeRawUsage(c, id, milliseconds);
    }

    private void writeRawUsage(Connection c, String id, long milliseconds) throws SQLException {
        try (PreparedStatement insert = c.prepareStatement("INSERT INTO " + table("check_value")
                + " (probe_id, used_ms) VALUES (?, ?)")) {
            insert.setString(1, id);
            insert.setLong(2, milliseconds);
            insert.executeUpdate();
        }
    }

    private void fieldRoundTrip(Connection c) throws Exception {
        String text = "中文会议字幕 😀";
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        Instant instant = Instant.parse("2026-10-10T01:02:03.123456Z");
        for (String id : new String[] {"Meeting-A", "meeting-a"}) {
            try (PreparedStatement insert = c.prepareStatement("INSERT INTO " + table("payload")
                    + " (record_id, body, digest, occurred_at_utc) VALUES (?, ?, ?, ?)")) {
                insert.setString(1, id);
                insert.setString(2, "{\"text\":\"" + text + "\",\"kind\":\"final\"}");
                insert.setBytes(3, digest);
                insert.setTimestamp(4, Timestamp.from(instant), UTC);
                insert.executeUpdate();
            }
        }
        for (String id : new String[] {"Meeting-A", "meeting-a"}) {
            try (PreparedStatement query = c.prepareStatement("SELECT record_id, JSON_UNQUOTE(JSON_EXTRACT(body, '$.text')),"
                    + " digest, occurred_at_utc FROM " + table("payload") + " WHERE record_id = ?")) {
                query.setString(1, id);
                try (ResultSet rows = query.executeQuery()) {
                    require(rows.next() && id.equals(rows.getString(1)) && text.equals(rows.getString(2))
                            && Arrays.equals(digest, rows.getBytes(3))
                            && instant.equals(rows.getTimestamp(4, UTC).toInstant()) && !rows.next(),
                            "JSON／binary／UTC 微秒及大小写精确匹配必须读回一致");
                }
            }
        }
        require(count(c, "payload", "record_id", "MEETING-A") == 0, "全大写标识不能匹配其他大小写记录");
        require(scalar(c, "SELECT COUNT(*) FROM " + table("payload")) == 2, "不同大小写标识必须各有独立记录");
    }

    private void unsupportedSyntax(Connection c) throws Exception {
        expectSqlError(1064, () -> execute(c, "SELECT total_ms FROM " + table("usage_counter") + " FOR UPDATE SKIP LOCKED"));
        expectSqlError(1064, () -> execute(c, "ALTER TABLE " + table("meeting")
                + " ADD FOREIGN KEY (reservation_id) REFERENCES " + table("reservation")
                + " (reservation_id) DEFERRABLE INITIALLY DEFERRED"));
        LOG.log(System.Logger.Level.WARNING, "5.7 不接受 SKIP LOCKED 和 DEFERRABLE INITIALLY DEFERRED；不能沿用相应 SQL。");
    }

    private void ddlImplicitCommit(Connection c) throws Exception {
        c.setAutoCommit(false);
        try {
            update(c, "INSERT INTO " + table("ddl_marker") + " (probe_id) VALUES (?)", "before-ddl");
            create(c, "ddl_created", "probe_id VARCHAR(96) PRIMARY KEY");
            update(c, "INSERT INTO " + table("ddl_marker") + " (probe_id) VALUES (?)", "after-ddl");
            c.rollback();
        } finally {
            c.rollback();
            c.setAutoCommit(true);
        }
        try (Connection observer = open()) {
            require(count(observer, "ddl_marker", "probe_id", "before-ddl") == 1, "DDL 前 DML 被隐式提交");
            require(count(observer, "ddl_marker", "probe_id", "after-ddl") == 0, "DDL 后 DML 仍可回滚");
            verifyInnoDb(observer, "ddl_created");
        }
        LOG.log(System.Logger.Level.WARNING, "ROLLBACK 后 DDL 前标记和新表仍存在；MySQL 初始化不能承诺整份 DDL 原子回滚。");
    }

    private void verifyInnoDb(Connection c, String... names) throws SQLException {
        try (PreparedStatement query = c.prepareStatement("SELECT ENGINE FROM information_schema.TABLES"
                + " WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?")) {
            for (String name : names) {
                query.setString(1, config.database());
                query.setString(2, prefix + "_" + name);
                try (ResultSet rows = query.executeQuery()) {
                    require(rows.next() && "InnoDB".equalsIgnoreCase(rows.getString(1)) && !rows.next(),
                            "每张实验表必须实际为 InnoDB");
                }
            }
        }
    }

    private void create(Connection c, String name, String columns) throws SQLException {
        execute(c, "CREATE TABLE " + table(name) + " (" + columns
                + ") ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
    }

    /** 所有标识符只由本类固定后缀及内部随机十六进制前缀组成，用户输入不进入 SQL 标识符。 */
    private String table(String suffix) {
        return "`" + prefix + "_" + suffix + "`";
    }

    private void experiment(Connection c, String description, Experiment experiment) throws Exception {
        current = description;
        experiment.run(c);
        passed++;
        LOG.log(System.Logger.Level.INFO, "[PASS] " + description);
    }

    private static void execute(Connection c, String sql) throws SQLException {
        try (Statement statement = c.createStatement()) {
            statement.setQueryTimeout(WAIT_SECONDS);
            statement.execute(sql);
        }
    }

    private static int update(Connection c, String sql, String... values) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setString(i + 1, values[i]);
            }
            return statement.executeUpdate();
        }
    }

    private long count(Connection c, String suffix, String column, String value) throws SQLException {
        try (PreparedStatement query = c.prepareStatement("SELECT COUNT(*) FROM " + table(suffix) + " WHERE " + column + " = ?")) {
            query.setString(1, value);
            try (ResultSet rows = query.executeQuery()) {
                require(rows.next(), "计数必须返回结果");
                return rows.getLong(1);
            }
        }
    }

    private static long scalar(Connection c, String sql) throws SQLException {
        try (Statement statement = c.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            require(rows.next(), "查询必须返回标量");
            return rows.getLong(1);
        }
    }

    private static void expectSqlError(int code, SqlAction action) throws Exception {
        try {
            action.run();
        } catch (SQLException expected) {
            require(expected.getErrorCode() == code, "必须收到预期 MySQL 错误码 " + code
                    + "，实际 " + expected.getErrorCode());
            LOG.log(System.Logger.Level.DEBUG, "已收到预期 SQL 错误码=" + code + "；SQLState=" + expected.getSQLState());
            return;
        }
        throw new ProbeFailure("预期 MySQL 错误码 " + code + "，语句却成功执行");
    }

    private static void await(CountDownLatch latch, String description) throws InterruptedException {
        require(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), "等待超时：" + description);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new ProbeFailure(message);
        }
    }

    private static void inject(FailAt actual, FailAt point) throws InjectedFailure {
        if (actual == point) {
            throw new InjectedFailure();
        }
    }

    private enum FailAt { NONE, AFTER_MEETING, AFTER_FACT_AND_OUTBOX }
    /** 只保存本工具产生的断言文字，可安全输出；JDBC 原始异常文字仍不输出。 */
    private static final class ProbeFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private ProbeFailure(String message) { super(message); }
    }
    private static final class InjectedFailure extends Exception {
        private static final long serialVersionUID = 1L;
    }
    @FunctionalInterface private interface Experiment { void run(Connection connection) throws Exception; }
    @FunctionalInterface private interface SqlAction { void run() throws SQLException; }

    /** 只允许本机单地址 URL 和 ai_trans_probe_* 隔离库；真实业务库不能作为本工具目标。 */
    private record Config(String url, String user, String password, String database) {
        private static Config fromEnvironment() throws Exception {
            String url = environment("MYSQL57_TEST_URL", false);
            String user = environment("MYSQL57_TEST_USER", false);
            String password = environment("MYSQL57_TEST_PASSWORD", true);
            require(url.startsWith("jdbc:mysql://"), "只接受 MySQL 单地址 JDBC URL");
            URI uri = new URI(url.substring("jdbc:".length()));
            String host = uri.getHost();
            require("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                    || "[::1]".equals(host) || "::1".equals(host), "只允许本机 loopback 地址");
            require(uri.getUserInfo() == null && uri.getFragment() == null, "URL 不允许用户信息或片段");
            require(uri.getPort() == -1 || uri.getPort() > 0 && uri.getPort() <= 65535, "端口必须有效");
            String path = uri.getRawPath();
            require(path != null && path.matches("/ai_trans_probe_[A-Za-z0-9_]+"), "库名必须为 ai_trans_probe_* 隔离库");
            String query = uri.getRawQuery();
            require(query == null || !query.matches("(?i).*(?:^|&)(?:user|password)=.*"), "凭据只经环境变量传入");
            return new Config(url, user, password, path.substring(1));
        }

        private static String environment(String name, boolean allowEmpty) {
            String value = System.getenv(name);
            require(value != null && (allowEmpty || !value.isBlank()), "需要显式设置环境变量 " + name);
            return value;
        }
    }
}

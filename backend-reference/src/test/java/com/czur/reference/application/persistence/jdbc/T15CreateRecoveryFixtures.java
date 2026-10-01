package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.CreateMeetingRequestFact;
import com.czur.reference.application.command.CreateMeetingRequestSnapshot;
import com.czur.reference.application.command.CreateMeetingResponseFact;
import com.czur.reference.application.command.MeetingRequestCanonicalizer;
import com.czur.reference.application.port.CompleteMeetingCreationUnitOfWork;
import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.application.port.model.AudioProfile;
import com.czur.reference.domain.meeting.MeetingState;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

/**
 * T15-12-06 仅用于门禁的确定性输入。父/子 JVM 各自重建相同候选对象，
 * 但初始权益事实只由父 JVM 写一次；子 JVM 必须通过真实 JDBC 工作单元竞争。
 * 固定的令牌和时间均为测试数据，不得作为生产装配示例。
 */
final class T15CreateRecoveryFixtures {
    static final String DEPLOYMENT = "t15-create-recovery";
    static final String SUBJECT = "test-subject";
    static final Instant RESERVED_AT = Instant.parse("2030-01-01T00:00:00Z");
    static final Instant CREATED_AT = Instant.parse("2030-01-01T00:00:10.123456Z");
    private static final Set<String> CAPABILITIES = Set.of("transcript.realtime");

    private T15CreateRecoveryFixtures() {
    }

    static PGSimpleDataSource dataSource() {
        String url = required("REFERENCE_TEST_DATABASE_URL");
        if (url.contains("127.0.0.1:55432") || url.contains("localhost:55432")) {
            throw new IllegalStateException("shared database endpoint is forbidden");
        }
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(url);
        source.setUser(required("REFERENCE_TEST_DATABASE_USER"));
        source.setPassword(required("REFERENCE_TEST_DATABASE_PASSWORD"));
        return source;
    }

    static JdbcCompleteMeetingCreationUnitOfWork unitOfWork(DataSource source) {
        return new JdbcCompleteMeetingCreationUnitOfWork(
                source, Clock.fixed(CREATED_AT, ZoneOffset.UTC));
    }

    /** 插入一次真实首次 reserve 操作；重复调用不是幂等操作。 */
    static void insertReserve(DataSource source, String suffix) throws Exception {
        UsageOperationResult result = reserve(suffix);
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            JdbcUsageReservationStore.insertReservation(connection, result.reservation());
            if (!JdbcUsageReservationStore.insertOperation(connection, result, RESERVED_AT)) {
                throw new IllegalStateException("initial reserve operation not inserted");
            }
            connection.commit();
        }
    }

    static CompleteMeetingCreationUnitOfWork.CreateRequest request(
            String suffix, String key, String title, String token) {
        return request(suffix, suffix, key, title, token);
    }

    /** sessionSuffix 可相同而权益与会议候选不同，用于证明败者读回赢家原事实。 */
    static CompleteMeetingCreationUnitOfWork.CreateRequest request(
            String suffix, String sessionSuffix, String key, String title, String token) {
        UsageOperationResult reserve = reserve(suffix);
        CreateMeetingRequestSnapshot snapshot = new CreateMeetingRequestSnapshot(
                "session-" + sessionSuffix,
                new CreateMeetingRequestSnapshot.ProfileRef("standard", 1),
                new CreateMeetingRequestSnapshot.ClientDescriptor("app", "1", "v1", "device"),
                title, "Asia/Shanghai",
                new CreateMeetingRequestSnapshot.LanguageSelection("zh-CN", List.of()),
                AudioProfile.pcm16kMono(100), CAPABILITIES,
                new CreateMeetingRequestSnapshot.ArtifactPolicy(false, false), key);
        CreateMeetingRequestFact fact = new CreateMeetingRequestFact(
                snapshot, MeetingRequestCanonicalizer.digest(snapshot), 1);
        CreateMeetingResponseFact response = new CreateMeetingResponseFact(
                meetingId(suffix), MeetingState.CREATED,
                new CreateMeetingRequestSnapshot.ProfileRef("standard", 1),
                CAPABILITIES, List.of(), AudioProfile.pcm16kMono(100),
                new CreateMeetingResponseFact.RealtimeSession(
                        URI.create("wss://example.test/realtime"),
                        CreateMeetingResponseFact.REALTIME_SUBPROTOCOL,
                        token, CREATED_AT.plusSeconds(120)),
                new CreateMeetingResponseFact.Limits(3_600_000, 64_000, 200, null));
        return new CompleteMeetingCreationUnitOfWork.CreateRequest(
                DEPLOYMENT, SUBJECT, fact, response, reserve);
    }

    static String meetingId(String suffix) {
        return "mtg_t15_" + suffix;
    }

    private static UsageOperationResult reserve(String suffix) {
        MeetingGrant grant = new MeetingGrant("grant-" + suffix, DEPLOYMENT, SUBJECT,
                "standard", 1, CAPABILITIES, CAPABILITIES, 3_600_000,
                RESERVED_AT.minusSeconds(10), RESERVED_AT.plusSeconds(120));
        UsageReservation reservation = UsageReservation.reserved(
                "reservation-" + suffix, grant, meetingId(suffix),
                new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 60_000),
                RESERVED_AT, RESERVED_AT.plusSeconds(90));
        return UsageOperationResult.reserve(
                DEPLOYMENT, SUBJECT, "reserve-key-" + suffix, "reserve-digest-" + suffix,
                grant.expiresAt(), AuthorizedProfileSnapshot.from(grant), reservation);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing required test environment: " + name);
        }
        return value;
    }
}

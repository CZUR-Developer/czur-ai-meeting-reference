package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.CreateMeetingRequestFact;
import com.czur.reference.application.command.CreateMeetingRequestSnapshot;
import com.czur.reference.application.command.CreateMeetingResponseFact;
import com.czur.reference.application.command.MeetingRequestCanonicalizer;
import com.czur.reference.application.finalization.FinalizationExecutionFact;
import com.czur.reference.application.port.AcceptedInputFactLookup;
import com.czur.reference.application.port.CreateMeetingFactStore;
import com.czur.reference.application.port.IdempotencyScopeCodec;
import com.czur.reference.application.port.InputState;
import com.czur.reference.application.port.MeetingInputClosureContextPort;
import com.czur.reference.application.port.model.AudioProfile;
import com.czur.reference.domain.capability.CapabilityDecision;
import com.czur.reference.domain.capability.CapabilityIds;
import com.czur.reference.domain.capability.CapabilityPlan;
import com.czur.reference.domain.capability.ProductProfile;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** 隔离 PostgreSQL 16 上的只读收口事实门禁；不启动 Reconciler 或 Worker。 */
@EnabledIfEnvironmentVariable(named = "REFERENCE_TEST_DATABASE_URL", matches = ".+")
class JdbcMeetingInputClosureLookupsIntegrationTest {
    private static final String MEETING = "mtg_closure_lookup";
    private static final String DEPLOYMENT = "closure-deployment";
    private static final String SUBJECT = "closure-subject";
    private static final Instant CREATED = Instant.parse("2026-10-02T08:00:00.123456Z");
    private static final Instant STARTED = Instant.parse("2026-10-02T08:00:01.123456Z");
    private static final Instant STOPPED = Instant.parse("2026-10-02T08:01:00.123456Z");
    private static final Instant ACCEPTED = Instant.parse("2026-10-02T08:01:01.123456Z");
    private static final String KEY = "closure-test-key-0001";
    private static final List<String> MIGRATIONS = List.of(
            "V001__initial_schema.sql", "V002__outbox_lease_fencing.sql",
            "V003__identity_entitlement.sql", "V004__meeting_event_envelope.sql",
            "V005__meeting_finalization_fact.sql", "V006__meeting_read_model.sql",
            "V007__meeting_manifest_revision.sql", "V008__durable_input_fact.sql",
            "V009__final_usage_fact.sql", "V010__workflow_step_fencing.sql",
            "V011__finalization_execution_artifact_ready.sql",
            "V012__artifact_deletion_plan.sql", "V013__meeting_create_fact.sql");

    private PGSimpleDataSource admin;
    private PGSimpleDataSource source;
    private String schema;

    @BeforeEach
    void setUp() throws Exception {
        admin = postgres(null);
        schema = "closure_lookup_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = admin.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + quote(schema));
        }
        source = postgres(schema);
        for (String migration : MIGRATIONS) execute(migration(migration));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (admin != null && schema != null) {
            try (Connection connection = admin.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + quote(schema) + " CASCADE");
            }
        }
    }

    @Test
    void contextDistinguishesWaitingPendingAndAcceptedWithoutUsingRequestedTranslationTargets()
            throws Exception {
        fixture(false);
        JdbcMeetingInputClosureContextPort lookup =
                new JdbcMeetingInputClosureContextPort(source, DEPLOYMENT);
        assertInstanceOf(MeetingInputClosureContextPort.WaitingForStop.class, lookup.load(MEETING));

        execute("""
                UPDATE meeting SET state='STOPPING', input_state='INPUT_COMPLETE_PENDING',
                    aggregate_version=2, updated_at='2026-10-02T08:01:00.123456Z'
                 WHERE meeting_id='mtg_closure_lookup'
                """);
        MeetingInputClosureContextPort.Context pending = assertInstanceOf(
                MeetingInputClosureContextPort.Found.class, lookup.load(MEETING)).context();
        assertEquals(InputState.INPUT_COMPLETE_PENDING, pending.inputState());
        assertEquals("zh-CN", pending.sourceLanguage());
        assertEquals(STOPPED, pending.meetingUpdatedAt());
        assertEquals(DEPLOYMENT, pending.deploymentScope());
        assertEquals(SUBJECT, pending.subjectScope());

        execute("""
                UPDATE meeting SET state='FINALIZING', input_state='INPUT_ACCEPTED',
                    finalization_receipt_id='receipt-test', manifest_state='GENERATING',
                    aggregate_version=4, updated_at='2026-10-02T08:01:01.123456Z'
                 WHERE meeting_id='mtg_closure_lookup'
                """);
        MeetingInputClosureContextPort.Context accepted = assertInstanceOf(
                MeetingInputClosureContextPort.Found.class, lookup.load(MEETING)).context();
        assertEquals(InputState.INPUT_ACCEPTED, accepted.inputState());
        assertEquals(ACCEPTED, accepted.meetingUpdatedAt());
    }

    @Test
    void missingMeetingAndEffectiveTranslationNeverBecomeClosingContext()
            throws Exception {
        assertInstanceOf(MeetingInputClosureContextPort.Missing.class,
                new JdbcMeetingInputClosureContextPort(source, DEPLOYMENT).load(MEETING));
        fixture(true);
        assertInstanceOf(MeetingInputClosureContextPort.Inconsistent.class,
                new JdbcMeetingInputClosureContextPort(source, DEPLOYMENT).load(MEETING));
    }

    @Test
    void otherDeploymentCannotUseKnownMeetingIdToReachClosingFacts() throws Exception {
        fixture(false);
        assertInstanceOf(MeetingInputClosureContextPort.Missing.class,
                new JdbcMeetingInputClosureContextPort(source, "another-deployment")
                        .load(MEETING));
        assertInstanceOf(MeetingInputClosureContextPort.WaitingForStop.class,
                new JdbcMeetingInputClosureContextPort(source, DEPLOYMENT).load(MEETING));
    }

    @Test
    void changedCreateScopeIsInconsistent() throws Exception {
        fixture(false);
        execute("UPDATE meeting_create_fact SET scope='wrong-owner' WHERE meeting_id='" + MEETING + "'");
        assertInstanceOf(MeetingInputClosureContextPort.Inconsistent.class,
                new JdbcMeetingInputClosureContextPort(source, DEPLOYMENT).load(MEETING));
    }

    @Test
    void missingStartFactIsInconsistent() throws Exception {
        fixture(false);
        execute("DELETE FROM finalization_execution_fact WHERE meeting_id='" + MEETING + "'");
        assertInstanceOf(MeetingInputClosureContextPort.Inconsistent.class,
                new JdbcMeetingInputClosureContextPort(source, DEPLOYMENT).load(MEETING));
    }

    @Test
    void acceptedInputReturnsExactWinnerAndClassifiesMissingAndMalformed() throws Exception {
        JdbcAcceptedInputFactLookup lookup = new JdbcAcceptedInputFactLookup(source);
        assertInstanceOf(AcceptedInputFactLookup.Missing.class, lookup.find(MEETING));
        fixture(false);
        execute("""
                INSERT INTO durable_input_fact (meeting_id, input_binding,
                    last_accepted_sequence, audio_duration_ms, source_language,
                    target_languages, accepted_at, content_digest)
                VALUES ('mtg_closure_lookup', 'mtg_closure_lookup', 7, 140, 'zh-CN',
                    ARRAY[]::text[], '2026-10-02T08:01:01.123456Z', 'sha256:fixture')
                """);
        var fact = assertInstanceOf(AcceptedInputFactLookup.Found.class,
                lookup.find(MEETING)).fact();
        assertEquals(7, fact.lastAcceptedSequence());
        assertEquals(140, fact.audioDurationMs());
        assertEquals(ACCEPTED, fact.acceptedAt());
        assertEquals("sha256:fixture", fact.contentDigest());
        assertEquals(List.of(), fact.targetLanguages());

        // V008 的数据库 CHECK 不去重，读端仍必须拒绝损坏的完整值。
        execute("UPDATE durable_input_fact SET target_languages=ARRAY['en','en'] "
                + "WHERE meeting_id='" + MEETING + "'");
        assertInstanceOf(AcceptedInputFactLookup.Inconsistent.class, lookup.find(MEETING));
    }

    @Test
    void storageFailureIsUnavailableForBothLookups() {
        DataSource broken = (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, arguments) -> {
                    throw new SQLException("must not reach logs", "08006");
                });
        assertInstanceOf(MeetingInputClosureContextPort.Unavailable.class,
                new JdbcMeetingInputClosureContextPort(broken, DEPLOYMENT).load(MEETING));
        assertInstanceOf(AcceptedInputFactLookup.Unavailable.class,
                new JdbcAcceptedInputFactLookup(broken).find(MEETING));
    }

    private void fixture(boolean translationEnabled) throws Exception {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("""
                    INSERT INTO usage_reservation (reservation_id, status, created_at, updated_at,
                        record_format, deployment_scope, subject_scope, grant_id,
                        expected_meeting_id, bound_meeting_id, bound_at, usage_unit,
                        reserved_amount, expires_at, version, lease_version)
                    VALUES ('reservation-closure', 'RESERVED',
                        '2026-10-02T07:59:00Z', '2026-10-02T08:00:00.123456Z',
                        'V3', 'closure-deployment', 'closure-subject', 'grant-closure',
                        'mtg_closure_lookup', 'mtg_closure_lookup',
                        '2026-10-02T08:00:00.123456Z', 'MEETING_DURATION_MS',
                        60000, '2026-10-03T08:00:00Z', 1, 0)
                    """);
            statement.execute("""
                    INSERT INTO meeting (meeting_id, deployment_scope, client_session_id,
                        subject_scope, grant_id, grant_expires_at, usage_reservation_id,
                        profile_id, profile_revision, state, aggregate_version, created_at, updated_at)
                    VALUES ('mtg_closure_lookup', 'closure-deployment', 'session-closure',
                        'closure-subject', 'grant-closure', '2026-10-03T08:00:00Z',
                        'reservation-closure', 'profile', 1, 'RUNNING', 1,
                        '2026-10-02T08:00:00.123456Z', '2026-10-02T08:00:01.123456Z')
                    """);
            Set<String> requested = translationEnabled
                    ? Set.of(CapabilityIds.TRANSCRIPT_REALTIME, CapabilityIds.TRANSLATION_REALTIME)
                    : Set.of(CapabilityIds.TRANSCRIPT_REALTIME);
            CreateMeetingRequestSnapshot request = new CreateMeetingRequestSnapshot(
                    "session-closure", new CreateMeetingRequestSnapshot.ProfileRef("profile", 1),
                    new CreateMeetingRequestSnapshot.ClientDescriptor("app", "1", "v1", "device"),
                    null, "Asia/Shanghai",
                    // 请求目标语言不代表最终有效翻译；只有冻结计划能决定。
                    new CreateMeetingRequestSnapshot.LanguageSelection("zh-CN", List.of("en")),
                    AudioProfile.pcm16kMono(100), requested,
                    new CreateMeetingRequestSnapshot.ArtifactPolicy(false, false), KEY);
            CreateMeetingRequestFact requestFact = new CreateMeetingRequestFact(request,
                    MeetingRequestCanonicalizer.digest(request), 1);
            CreateMeetingResponseFact response = new CreateMeetingResponseFact(
                    MEETING, MeetingState.CREATED, request.profile(), requested,
                    List.of(transcriptDecision(), translationDecision(translationEnabled)),
                    request.audioProfile(), new CreateMeetingResponseFact.RealtimeSession(
                    URI.create("wss://example.test/realtime"),
                    CreateMeetingResponseFact.REALTIME_SUBPROTOCOL, "test-token",
                    CREATED.plusSeconds(120)),
                    new CreateMeetingResponseFact.Limits(60_000, 64_000, 200, null));
            new JdbcCreateMeetingFactStore(connection).persist(new CreateMeetingFactStore.Record(
                    IdempotencyScopeCodec.meetingCreateV2(DEPLOYMENT, SUBJECT), KEY,
                    requestFact, response, CREATED));

            // 此 Fixture 只验证只读 lookup 的身份、语言与能力来源；不执行
            // Admission/Worker，因此无需装配最终产物的 workflow/artifact 映射。
            ProductProfile profile = new ProductProfile("profile", 1,
                    Set.of(CapabilityIds.TRANSCRIPT_REALTIME),
                    Set.of(CapabilityIds.TRANSLATION_REALTIME),
                    java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
            new JdbcFinalizationExecutionFactStore(connection).persist(
                    FinalizationExecutionFact.freeze(MEETING, STARTED, null, profile,
                            new CapabilityPlan(1, List.of(transcriptDecision(),
                                    translationDecision(translationEnabled)))));
            connection.commit();
        }
    }

    private static CapabilityDecision transcriptDecision() {
        return new CapabilityDecision(
                CapabilityIds.TRANSCRIPT_REALTIME, CapabilityDecision.Availability.AVAILABLE,
                true, CapabilityDecision.Action.ENABLE, null, List.of(), List.of(), null, 1);
    }

    private static CapabilityDecision translationDecision(boolean enabled) {
        return new CapabilityDecision(
                CapabilityIds.TRANSLATION_REALTIME, CapabilityDecision.Availability.AVAILABLE,
                false, enabled ? CapabilityDecision.Action.ENABLE
                        : CapabilityDecision.Action.DISABLE,
                null, List.of(), List.of(), enabled ? null : "NOT_REQUESTED", 1);
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String migration(String filename) throws Exception {
        for (Path candidate : new Path[]{Path.of("deploy", "migrations", filename),
                Path.of("..", "deploy", "migrations", filename)}) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("migration file missing");
    }

    private static PGSimpleDataSource postgres(String targetSchema) {
        String url = required("REFERENCE_TEST_DATABASE_URL");
        if (url.contains("127.0.0.1:55432") || url.contains("localhost:55432")) {
            throw new IllegalStateException("shared database is forbidden");
        }
        PGSimpleDataSource result = new PGSimpleDataSource();
        result.setUrl(url);
        result.setUser(required("REFERENCE_TEST_DATABASE_USER"));
        result.setPassword(required("REFERENCE_TEST_DATABASE_PASSWORD"));
        if (targetSchema != null) result.setCurrentSchema(targetSchema);
        return result;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}

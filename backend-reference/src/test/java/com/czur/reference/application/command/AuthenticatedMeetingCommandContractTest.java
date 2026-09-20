package com.czur.reference.application.command;

import com.czur.reference.application.port.MeetingIdGenerator;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageUnit;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.VerifiedIdentity;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 认证会议命令纯 Java 输入、归属和规范摘要的契约门禁。 */
class AuthenticatedMeetingCommandContractTest {
    private static final Instant NOW = Instant.parse("2026-09-17T12:00:00Z");
    private static final String DEPLOYMENT = "deployment-private";
    private static final String SUBJECT = "subject-private";
    private static final String CREDENTIAL = "credential-private";
    private static final String CREATE_KEY = "create-key-private";
    private static final UsageEstimate ESTIMATE =
            new UsageEstimate(UsageUnit.MEETING_DURATION_MS, 60_000);

    @Test
    void permissionValuesAndIntentComponentsAreFrozen() {
        assertEquals("meeting:create", MeetingPermissions.CREATE);
        assertEquals("meeting:stop", MeetingPermissions.STOP);
        assertEquals("meeting:delete", MeetingPermissions.DELETE);
        assertEquals(1, MeetingRequestCanonicalizer.CREATE_SCHEMA_VERSION);
        assertEquals(1, MeetingRequestCanonicalizer.STOP_SCHEMA_VERSION);

        assertEquals(List.of(
                        "clientSessionId", "profileId", "profileRevision",
                        "requestedCapabilities", "estimatedUsage", "idempotencyKey"),
                recordComponents(CreateMeetingIntent.class));
        assertEquals(List.of("meetingId", "idempotencyKey"),
                recordComponents(StopMeetingIntent.class));
        assertEquals(List.of("deploymentScope", "identity"),
                recordComponents(MeetingCommandContext.class));

        List<Method> idMethods = Arrays.stream(MeetingIdGenerator.class.getDeclaredMethods())
                .filter(method -> Modifier.isAbstract(method.getModifiers()))
                .toList();
        assertEquals(1, idMethods.size());
        assertEquals("newMeetingId", idMethods.getFirst().getName());
        assertEquals(String.class, idMethods.getFirst().getReturnType());
        assertEquals(0, idMethods.getFirst().getParameterCount(),
                "会议 ID 生成器不得接收主体、幂等键、摘要或时间");
    }

    @Test
    void createIntentDefensivelyCopiesAndCanonicalizesCapabilityOrder() {
        HashSet<String> mutable = new HashSet<>(Set.of(
                "transcript.realtime", "summary.structured"));
        CreateMeetingIntent first = createIntent(
                "session-order", "profile-order", mutable, CREATE_KEY);
        mutable.clear();

        LinkedHashSet<String> reverseOrder = new LinkedHashSet<>();
        reverseOrder.add("summary.structured");
        reverseOrder.add("transcript.realtime");
        CreateMeetingIntent second = createIntent(
                "session-order", "profile-order", reverseOrder, "another-key");

        assertEquals(Set.of("transcript.realtime", "summary.structured"),
                first.requestedCapabilities());
        assertThrows(UnsupportedOperationException.class,
                () -> first.requestedCapabilities().add("translation.realtime"));
        assertEquals(MeetingRequestCanonicalizer.digest(first),
                MeetingRequestCanonicalizer.digest(second),
                "能力输入顺序和幂等键不得改变业务意图摘要");
    }

    @Test
    void canonicalEncodingHasStableVectorsAndUnambiguousUtf8Boundaries() {
        LinkedHashSet<String> capabilities = new LinkedHashSet<>();
        capabilities.add("transcript.realtime");
        capabilities.add("summary.structured");
        CreateMeetingIntent vector = new CreateMeetingIntent(
                "session-会议", "profile:1", 7, capabilities, ESTIMATE, CREATE_KEY);
        StopMeetingIntent stopVector = new StopMeetingIntent("meeting-会议", "stop-key");

        assertEquals("d36e82a8bb5459e1ed88104de274061e821042342b744f300ba7f8a98a2b03f6",
                MeetingRequestCanonicalizer.digest(vector));
        assertEquals("b956b2c6ec856053ec0adf0e79112e357a1cfcfaed819e647a5fc801eb5d10c7",
                MeetingRequestCanonicalizer.digest(stopVector));
        assertTrue(MeetingRequestCanonicalizer.digest(vector).matches("[0-9a-f]{64}"));

        CreateMeetingIntent firstBoundary = createIntent(
                "a", "bc", Set.of("transcript.realtime"), CREATE_KEY);
        CreateMeetingIntent secondBoundary = createIntent(
                "ab", "c", Set.of("transcript.realtime"), CREATE_KEY);
        assertNotEquals(MeetingRequestCanonicalizer.digest(firstBoundary),
                MeetingRequestCanonicalizer.digest(secondBoundary),
                "长度前缀必须区分简单字符串拼接会碰撞的字段边界");

        CreateMeetingIntent composed = createIntent(
                "caf\u00e9-😀", "profile", Set.of("能力.实时"), CREATE_KEY);
        CreateMeetingIntent decomposed = createIntent(
                "cafe\u0301-😀", "profile", Set.of("能力.实时"), CREATE_KEY);
        assertEquals(MeetingRequestCanonicalizer.digest(composed),
                MeetingRequestCanonicalizer.digest(createIntent(
                        "caf\u00e9-😀", "profile", Set.of("能力.实时"), "new-key")));
        assertNotEquals(MeetingRequestCanonicalizer.digest(composed),
                MeetingRequestCanonicalizer.digest(decomposed),
                "不同 UTF-8 字节序列不能被隐式正规化为同一意图");
    }

    @Test
    void stopDigestContainsOnlySchemaAndMeetingId() {
        StopMeetingIntent first = new StopMeetingIntent("meeting-stop", "stop-key-one");
        StopMeetingIntent retry = new StopMeetingIntent("meeting-stop", "stop-key-two");
        StopMeetingIntent anotherMeeting =
                new StopMeetingIntent("meeting-stop-other", "stop-key-one");

        assertEquals(MeetingRequestCanonicalizer.digest(first),
                MeetingRequestCanonicalizer.digest(retry));
        assertNotEquals(MeetingRequestCanonicalizer.digest(first),
                MeetingRequestCanonicalizer.digest(anotherMeeting));
    }

    @Test
    void accessGuardCoversOwnedLegacyDeploymentAndSubjectBranches() {
        MeetingCommandContext context = context(DEPLOYMENT, SUBJECT);

        assertEquals(MeetingAccessGuard.Decision.ALLOWED,
                MeetingAccessGuard.evaluate(context, owned(DEPLOYMENT, SUBJECT, "meeting-1")));
        assertEquals(MeetingAccessGuard.Decision.ACCESS_DENIED,
                MeetingAccessGuard.evaluate(context,
                        owned("deployment-other", SUBJECT, "meeting-2")));
        assertEquals(MeetingAccessGuard.Decision.ACCESS_DENIED,
                MeetingAccessGuard.evaluate(context,
                        owned(DEPLOYMENT, "subject-other", "meeting-3")));
        assertEquals(MeetingAccessGuard.Decision.ACCESS_DENIED,
                MeetingAccessGuard.evaluate(context, legacy(DEPLOYMENT, "meeting-4")));
        assertEquals(MeetingAccessGuard.Decision.ACCESS_DENIED,
                MeetingAccessGuard.evaluate(context, legacy("deployment-other", "meeting-5")));
        assertEquals(MeetingAccessGuard.Decision.ALLOWED,
                MeetingAccessGuard.evaluate(context, DEPLOYMENT, SUBJECT));
        assertEquals(MeetingAccessGuard.Decision.ACCESS_DENIED,
                MeetingAccessGuard.evaluate(context, "deployment-other", SUBJECT));
        assertEquals(MeetingAccessGuard.Decision.ACCESS_DENIED,
                MeetingAccessGuard.evaluate(context, DEPLOYMENT, "subject-other"));
        assertEquals(MeetingAccessGuard.Decision.ACCESS_DENIED,
                MeetingAccessGuard.evaluate(context, DEPLOYMENT, "LEGACY_UNOWNED"));
        assertEquals(MeetingAccessGuard.Decision.ACCESS_DENIED,
                MeetingAccessGuard.evaluate(context, null, SUBJECT));
        assertEquals(MeetingAccessGuard.Decision.ACCESS_DENIED,
                MeetingAccessGuard.evaluate(context, DEPLOYMENT, null));

        assertEquals(Set.of(
                        MeetingAccessGuard.Decision.ALLOWED,
                        MeetingAccessGuard.Decision.ACCESS_DENIED),
                Set.of(MeetingAccessGuard.Decision.values()));
        for (MeetingAccessGuard.Decision decision : MeetingAccessGuard.Decision.values()) {
            assertFalse(decision.toString().contains(SUBJECT));
            assertFalse(decision.toString().contains("subject-other"));
        }
    }

    @Test
    void identityContextAndIntentsHaveRedactedDiagnosticText() {
        VerifiedIdentity identity = identity(SUBJECT);
        MeetingCommandContext context = new MeetingCommandContext(DEPLOYMENT, identity);
        CreateMeetingIntent create = createIntent(
                "session-redacted", "profile-redacted",
                Set.of("transcript.realtime"), CREATE_KEY);
        StopMeetingIntent stop = new StopMeetingIntent("meeting-redacted", "stop-key-private");

        assertFalse(identity.toString().contains(SUBJECT));
        assertFalse(identity.toString().contains("organization-private"));
        assertFalse(identity.toString().contains("ROLE_PRIVATE"));
        assertFalse(identity.toString().contains(MeetingPermissions.CREATE));
        assertFalse(identity.toString().contains(CREDENTIAL));
        assertFalse(identity.subject().toString().contains(SUBJECT));
        assertFalse(identity.subject().toString().contains("organization-private"));

        assertFalse(context.toString().contains(DEPLOYMENT));
        assertFalse(context.toString().contains(SUBJECT));
        assertFalse(context.toString().contains("ROLE_PRIVATE"));
        assertFalse(context.toString().contains(MeetingPermissions.CREATE));
        assertFalse(context.toString().contains(CREDENTIAL));
        assertFalse(create.toString().contains(CREATE_KEY));
        assertFalse(stop.toString().contains("stop-key-private"));
    }

    @Test
    void invalidInputsFailBeforeCanonicalizationOrAccessChecks() {
        assertThrows(IllegalArgumentException.class,
                () -> context(" ", SUBJECT));
        assertThrows(NullPointerException.class,
                () -> new MeetingCommandContext(DEPLOYMENT, null));

        assertInvalidCreate(null, "profile", 1, Set.of("capability"), ESTIMATE, CREATE_KEY);
        assertInvalidCreate("session", " ", 1, Set.of("capability"), ESTIMATE, CREATE_KEY);
        assertInvalidCreate("session", "profile", 0, Set.of("capability"), ESTIMATE, CREATE_KEY);
        assertInvalidCreate("session", "profile", 1, null, ESTIMATE, CREATE_KEY);
        assertInvalidCreate("session", "profile", 1, Set.of(), ESTIMATE, CREATE_KEY);
        assertInvalidCreate("session", "profile", 1, setContainingNull(), ESTIMATE, CREATE_KEY);
        assertInvalidCreate("session", "profile", 1, Set.of(" "), ESTIMATE, CREATE_KEY);
        assertInvalidCreate("session", "profile", 1, Set.of("capability"), null, CREATE_KEY);
        assertInvalidCreate("session", "profile", 1, Set.of("capability"), ESTIMATE, " ");

        assertThrows(IllegalArgumentException.class,
                () -> new StopMeetingIntent(" ", "stop-key"));
        assertThrows(IllegalArgumentException.class,
                () -> new StopMeetingIntent("meeting", null));
        assertThrows(NullPointerException.class,
                () -> MeetingRequestCanonicalizer.digest((CreateMeetingIntent) null));
        assertThrows(NullPointerException.class,
                () -> MeetingRequestCanonicalizer.digest((StopMeetingIntent) null));
        assertThrows(IllegalArgumentException.class,
                () -> MeetingRequestCanonicalizer.digest(createIntent(
                        "invalid-\ud800", "profile", Set.of("capability"), CREATE_KEY)));
        assertThrows(IllegalArgumentException.class,
                () -> MeetingRequestCanonicalizer.digest(
                        new StopMeetingIntent("invalid-\udfff", "stop-key")));
        assertThrows(NullPointerException.class,
                () -> MeetingAccessGuard.evaluate(null, owned(DEPLOYMENT, SUBJECT, "meeting")));
        assertThrows(NullPointerException.class,
                () -> MeetingAccessGuard.evaluate(context(DEPLOYMENT, SUBJECT), null));
    }

    private static List<String> recordComponents(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(component -> component.getName())
                .toList();
    }

    private static CreateMeetingIntent createIntent(
            String clientSessionId,
            String profileId,
            Set<String> capabilities,
            String idempotencyKey) {
        return new CreateMeetingIntent(
                clientSessionId, profileId, 3, capabilities, ESTIMATE, idempotencyKey);
    }

    private static void assertInvalidCreate(
            String clientSessionId,
            String profileId,
            int profileRevision,
            Set<String> capabilities,
            UsageEstimate estimate,
            String idempotencyKey) {
        assertThrows(RuntimeException.class, () -> new CreateMeetingIntent(
                clientSessionId, profileId, profileRevision,
                capabilities, estimate, idempotencyKey));
    }

    private static Set<String> setContainingNull() {
        HashSet<String> values = new HashSet<>();
        values.add(null);
        return values;
    }

    private static MeetingCommandContext context(String deployment, String subject) {
        return new MeetingCommandContext(deployment, identity(subject));
    }

    private static VerifiedIdentity identity(String subject) {
        return new VerifiedIdentity(
                new Subject(subject, "organization-private"),
                Set.of("ROLE_PRIVATE"),
                Set.of(MeetingPermissions.CREATE, MeetingPermissions.STOP),
                NOW.minusSeconds(60), NOW.plusSeconds(600), CREDENTIAL);
    }

    private static MeetingAggregate.MeetingSnapshot owned(
            String deployment, String subject, String meetingId) {
        return MeetingAggregate.MeetingSnapshot.owned(
                meetingId, deployment, "client-session", subject,
                "grant-private", NOW.plusSeconds(600), "reservation-private",
                "profile", 1, MeetingState.CREATED, NOW, NOW);
    }

    private static MeetingAggregate.MeetingSnapshot legacy(
            String deployment, String meetingId) {
        return MeetingAggregate.MeetingSnapshot.legacyUnowned(
                meetingId, deployment, "client-session", "profile", 1,
                MeetingState.CREATED, NOW, NOW);
    }
}

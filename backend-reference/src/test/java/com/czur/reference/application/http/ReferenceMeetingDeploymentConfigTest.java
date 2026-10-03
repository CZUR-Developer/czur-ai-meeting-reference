package com.czur.reference.application.http;

import com.czur.reference.application.bootstrap.ReferenceMeetingDeploymentConfig;
import com.czur.reference.application.finalization.MockFinalizationArtifactContent;
import com.czur.reference.application.finalization.DeterministicMockFinalizationPlanner;
import com.czur.reference.application.finalization.FinalizationExecutionFact;
import com.czur.reference.application.finalization.ProductProfileSnapshot;
import com.czur.reference.application.identity.referencedev.ReferenceDevIdentityConfig;
import com.czur.reference.application.identity.referencedev.ReferenceDevIdentityProvider;
import com.czur.reference.application.persistence.jdbc.DatabaseRuntimeConfig;
import com.czur.reference.application.port.FinalizationInputFact;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.identity.IdentityVerificationRequest;
import com.czur.reference.application.port.identity.SecretCredential;
import com.czur.reference.domain.capability.CapabilityIds;
import com.czur.reference.domain.capability.CapabilityDecision;
import com.czur.reference.domain.capability.CapabilityPlanner;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.AbstractMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 不启动数据库、网络或真实模型，验证参考装配取得资源前的可信配置门禁。 */
class ReferenceMeetingDeploymentConfigTest {
    private static final String TOKEN = "local-test-token-0123456789-abcdefgh";
    private static final String SECRET = "page-secret-0123456789-abcdefghijkl";

    @Test
    void asrOnlyUsesOneTrustedProfileAndIdentitySourceWithoutOpeningDatabase() {
        Map<String, String> values = valid();
        var config = ReferenceMeetingDeploymentConfig.fromEnvironment(values);
        assertFalse(config.createEnabled());
        assertEquals("demo-deployment", config.deploymentScope());
        assertEquals("demo-profile", config.profile().id());
        assertEquals(2, config.profile().revision());
        assertEquals(config.deploymentScope(), config.entitlement().deploymentScope());
        assertEquals(config.profile().id(), config.entitlement().profileId());
        assertEquals(config.profile().revision(), config.entitlement().profileRevision());
        assertEquals(config.profile().requiredCapabilities(),
                config.entitlement().supportedCapabilities());
        assertTrue(config.mockCapabilities().supports(CapabilityIds.TRANSCRIPT_REALTIME));
        assertTrue(config.capabilityLimits().permits(CapabilityIds.TRANSCRIPT_REALTIME));
        assertEquals(List.of("mock.transcript"),
                config.profile().affectedWorkflowsOf(CapabilityIds.TRANSCRIPT_REALTIME));
        assertEquals(List.of("transcript.final"),
                config.profile().producedArtifactsOf(CapabilityIds.TRANSCRIPT_REALTIME));
        var plan = new CapabilityPlanner().plan(config.mockCapabilities(), config.profile(),
                Set.of(), config.entitlement().supportedCapabilities(), config.capabilityLimits());
        assertFalse(plan.failed());
        var realtime = plan.decisionFor(CapabilityIds.TRANSCRIPT_REALTIME);
        assertEquals(CapabilityDecision.Action.ENABLE, realtime.action());
        assertEquals(List.of("mock.transcript"), realtime.affectedWorkflows());
        assertEquals(List.of("transcript.final"), realtime.producedArtifacts());
        assertTrue(MockFinalizationArtifactContent.ARTIFACT_TYPES.containsAll(
                realtime.producedArtifacts()));
        var frozen = ProductProfileSnapshot.from(config.profile());
        assertEquals(config.profile().affectedWorkflows(), frozen.affectedWorkflows());
        assertEquals(config.profile().producedArtifacts(), frozen.producedArtifacts());
        assertEquals(config.profile().affectedWorkflows(),
                frozen.toProductProfile().affectedWorkflows());
        assertEquals("trusted-audience", config.audience());
        assertEquals("trusted-client", config.clientId());

        var provider = new ReferenceDevIdentityProvider(config.identity(), Clock.fixed(
                Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC));
        try (SecretCredential credential = SecretCredential.copyOf(
                TOKEN.getBytes(StandardCharsets.UTF_8))) {
            var request = new IdentityVerificationRequest(
                    IdentityVerificationRequest.CredentialType.BEARER_TOKEN,
                    credential, config.audience(), config.clientId());
            assertTrue(provider.verifyCredential(request).isSuccess());
        }
        try (SecretCredential credential = SecretCredential.copyOf(
                TOKEN.getBytes(StandardCharsets.UTF_8))) {
            var wrong = new IdentityVerificationRequest(
                    IdentityVerificationRequest.CredentialType.BEARER_TOKEN,
                    credential, "wrong-audience", config.clientId());
            assertFalse(provider.verifyCredential(wrong).isSuccess());
        }
        byte[] exposed = config.pageTokenSecretCopy();
        exposed[0] = 0;
        assertEquals((byte) 'p', config.pageTokenSecretCopy()[0]);
        values.put(ReferenceMeetingDeploymentConfig.DEPLOYMENT_SCOPE, "changed-later");
        assertEquals("demo-deployment", config.deploymentScope());
    }

    @Test
    void createStopReadAndDeleteConsumersUseTheSameTrustedIdentitySettings() {
        var config = ReferenceMeetingDeploymentConfig.fromEnvironment(valid());
        var provider = new ReferenceDevIdentityProvider(config.identity(), Clock.fixed(
                Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC));
        String key = "idempotency-key-123456789";
        String authorization = "Bearer " + TOKEN;
        var createDecoder = new CreateMeetingHttpRequestDecoder(
                config.audience(), config.clientId());
        String createBody = """
                {"clientSessionId":"session-1","profile":{"id":"demo-profile","revision":2},
                 "client":{"appId":"app","appVersion":"1","contractVersion":"0.1","deviceId":"device"},
                 "timezone":"Asia/Shanghai","languages":{"source":"zh-CN","targets":[]},
                 "audioProfile":{"encoding":"PCM_S16LE","sampleRateHz":16000,
                                 "channelCount":1,"frameDurationMs":40},
                 "requestedCapabilities":["transcript.realtime"],
                 "artifactPolicy":{"retainAudio":false,"presentationCapture":false}}
                """;
        try (var decoded = createDecoder.decode(new HttpRequest("POST", "/v1/meetings",
                Map.of("authorization", authorization, "idempotency-key", key,
                        "content-type", "application/json"), createBody))) {
            assertEquals(config.audience(), decoded.verificationRequest().audience());
            assertEquals(config.clientId(), decoded.verificationRequest().clientId());
            assertTrue(provider.verifyCredential(decoded.verificationRequest()).isSuccess());
        }

        var stopDecoder = new ReferenceMeetingHttpRequestDecoder(
                config.audience(), config.clientId());
        var stop = stopDecoder.decodeStop(new HttpRequest("POST",
                "/v1/meetings/mtg_one:stop",
                Map.of("authorization", authorization, "idempotency-key", key), ""),
                "mtg_one", key);
        try (SecretCredential credential = stop.verificationRequest().credential()) {
            assertEquals(config.audience(), stop.verificationRequest().audience());
            assertEquals(config.clientId(), stop.verificationRequest().clientId());
            assertTrue(provider.verifyCredential(stop.verificationRequest()).isSuccess());
        }

        var contextResolver = new AuthenticatedMeetingHttpContextResolver(
                config.deploymentScope(), config.audience(), config.clientId(),
                provider, new HttpErrorMapper(() -> "config-test-diag"));
        var read = contextResolver.forRead(new HttpRequest("GET", "/v1/meetings",
                Map.of("authorization", authorization), ""));
        assertEquals(config.deploymentScope(),
                ((AuthenticatedMeetingHttpContextResolver.Ready) read).context().deploymentScope());
        var delete = contextResolver.forDelete(new HttpRequest("DELETE",
                "/v1/meetings/mtg_one", Map.of("authorization", authorization), ""));
        assertEquals(config.deploymentScope(),
                ((AuthenticatedMeetingHttpContextResolver.Ready) delete).context().deploymentScope());
    }

    @Test
    void allFourRequiredRealtimeBreaksFailBeforeAssembly() {
        Map<String, String> noRequired = valid();
        noRequired.put(ReferenceMeetingDeploymentConfig.PROFILE_REQUIRED_CAPABILITIES,
                CapabilityIds.TRANSCRIPT_FINAL);
        noRequired.put(ReferenceMeetingDeploymentConfig.PROFILE_OPTIONAL_CAPABILITIES,
                CapabilityIds.TRANSCRIPT_REALTIME);
        rejects(noRequired, ReferenceMeetingDeploymentConfig.PROFILE_REQUIRED_CAPABILITIES);

        Map<String, String> noEntitlement = valid();
        noEntitlement.put(ReferenceMeetingDeploymentConfig.ENTITLEMENT_SUPPORTED_CAPABILITIES,
                CapabilityIds.TRANSCRIPT_FINAL);
        rejects(noEntitlement, ReferenceMeetingDeploymentConfig.ENTITLEMENT_SUPPORTED_CAPABILITIES);

        Map<String, String> noMock = valid();
        noMock.put(ReferenceMeetingDeploymentConfig.MOCK_CAPABILITIES,
                CapabilityIds.TRANSCRIPT_FINAL);
        rejects(noMock, ReferenceMeetingDeploymentConfig.MOCK_CAPABILITIES);

        Map<String, String> disabled = valid();
        disabled.put(ReferenceMeetingDeploymentConfig.DISABLED_CAPABILITIES,
                CapabilityIds.TRANSCRIPT_REALTIME);
        rejects(disabled, ReferenceMeetingDeploymentConfig.DISABLED_CAPABILITIES);
    }

    @Test
    void everyAdditionalRequiredCapabilityMustBeSupportedAndMapped() {
        Map<String, String> extra = valid();
        extra.put(ReferenceMeetingDeploymentConfig.PROFILE_REQUIRED_CAPABILITIES,
                CapabilityIds.TRANSCRIPT_REALTIME + "," + CapabilityIds.SUMMARY_STRUCTURED);
        rejects(extra, ReferenceMeetingDeploymentConfig.ENTITLEMENT_SUPPORTED_CAPABILITIES);
        extra.put(ReferenceMeetingDeploymentConfig.ENTITLEMENT_SUPPORTED_CAPABILITIES,
                CapabilityIds.TRANSCRIPT_REALTIME + "," + CapabilityIds.SUMMARY_STRUCTURED);
        rejects(extra, ReferenceMeetingDeploymentConfig.MOCK_CAPABILITIES);
        extra.put(ReferenceMeetingDeploymentConfig.MOCK_CAPABILITIES,
                CapabilityIds.TRANSCRIPT_REALTIME + "," + CapabilityIds.SUMMARY_STRUCTURED);
        extra.put(ReferenceMeetingDeploymentConfig.DISABLED_CAPABILITIES,
                CapabilityIds.SUMMARY_STRUCTURED);
        rejects(extra, ReferenceMeetingDeploymentConfig.DISABLED_CAPABILITIES);
        extra.remove(ReferenceMeetingDeploymentConfig.DISABLED_CAPABILITIES);
        rejects(extra, ReferenceMeetingDeploymentConfig.PROFILE_REQUIRED_CAPABILITIES);
    }

    @Test
    void optionalMayDisableButCannotFakeEnabledExecutionMapping() {
        Map<String, String> optional = valid();
        optional.put(ReferenceMeetingDeploymentConfig.PROFILE_OPTIONAL_CAPABILITIES,
                CapabilityIds.SUMMARY_STRUCTURED);
        optional.put(ReferenceMeetingDeploymentConfig.ENTITLEMENT_SUPPORTED_CAPABILITIES,
                CapabilityIds.TRANSCRIPT_REALTIME + "," + CapabilityIds.SUMMARY_STRUCTURED);
        var config = ReferenceMeetingDeploymentConfig.fromEnvironment(optional);
        var plan = new CapabilityPlanner().plan(config.mockCapabilities(), config.profile(),
                Set.of(CapabilityIds.SUMMARY_STRUCTURED),
                config.entitlement().supportedCapabilities(), config.capabilityLimits());
        assertEquals(CapabilityDecision.Action.DISABLE,
                plan.decisionFor(CapabilityIds.SUMMARY_STRUCTURED).action());
        optional.put(ReferenceMeetingDeploymentConfig.MOCK_CAPABILITIES,
                CapabilityIds.TRANSCRIPT_REALTIME + "," + CapabilityIds.SUMMARY_STRUCTURED);
        rejects(optional, ReferenceMeetingDeploymentConfig.PROFILE_OPTIONAL_CAPABILITIES);
    }

    @Test
    void secondAdapterConfigRegistersAsrOnlyModelAndKeepsOptionalFunctionsClosed() {
        Map<String, String> values = valid();
        values.put(ReferenceMeetingDeploymentConfig.PROFILE_OPTIONAL_CAPABILITIES,
                CapabilityIds.TRANSLATION_REALTIME + "," + CapabilityIds.SUMMARY_STRUCTURED);
        values.put(ReferenceMeetingDeploymentConfig.ENTITLEMENT_SUPPORTED_CAPABILITIES,
                CapabilityIds.TRANSCRIPT_REALTIME + ","
                        + CapabilityIds.TRANSLATION_REALTIME + ","
                        + CapabilityIds.SUMMARY_STRUCTURED);
        values.put(ReferenceMeetingDeploymentConfig.MODEL_CAPABILITIES,
                CapabilityIds.TRANSCRIPT_REALTIME);
        values.put(ReferenceMeetingDeploymentConfig.MODEL_ADAPTER, "sample-json-ws");
        values.put(ReferenceMeetingDeploymentConfig.SAMPLE_JSON_WS_ENDPOINT,
                "ws://127.0.0.1:19091/model");
        var config = ReferenceMeetingDeploymentConfig.fromEnvironment(values);
        assertEquals("reference-model", config.mockCapabilities().providerId());
        assertEquals("ws://127.0.0.1:19091/model",
                config.sampleJsonWsEndpoint().orElseThrow().toString());
        assertTrue(config.tingwuModelSession().isEmpty());
        var plan = new CapabilityPlanner().plan(config.mockCapabilities(), config.profile(),
                config.profile().allCapabilities(),
                config.entitlement().supportedCapabilities(), config.capabilityLimits());
        assertEquals(CapabilityDecision.Action.ENABLE,
                plan.decisionFor(CapabilityIds.TRANSCRIPT_REALTIME).action());
        for (String capability : Set.of(CapabilityIds.TRANSLATION_REALTIME,
                CapabilityIds.SUMMARY_STRUCTURED)) {
            var decision = plan.decisionFor(capability);
            assertEquals(CapabilityDecision.Action.DISABLE, decision.action());
            assertEquals("PROVIDER_CAPABILITY_MISSING", decision.warningCode());
        }
    }

    @Test
    void parsesAllFieldsFromOnePrivateMapSnapshot() {
        Map<String, String> changing = valid();
        Map<String, String> adversarial = new AbstractMap<>() {
            @Override public int size() { return changing.size(); }
            @Override public Set<Entry<String, String>> entrySet() {
                Map<String, String> beforeChange = Map.copyOf(changing);
                changing.put(ReferenceDevIdentityConfig.AUDIENCE, "changed-after-copy");
                changing.put(ReferenceMeetingDeploymentConfig.DEPLOYMENT_SCOPE,
                        "changed-after-copy");
                return beforeChange.entrySet();
            }
            @Override public String get(Object key) {
                throw new AssertionError("parser read caller map after snapshot");
            }
        };
        var config = ReferenceMeetingDeploymentConfig.fromEnvironment(adversarial);
        assertEquals("trusted-audience", config.audience());
        assertEquals("demo-deployment", config.deploymentScope());
        var provider = new ReferenceDevIdentityProvider(config.identity(), Clock.fixed(
                Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC));
        try (SecretCredential credential = SecretCredential.copyOf(
                TOKEN.getBytes(StandardCharsets.UTF_8))) {
            var request = new IdentityVerificationRequest(
                    IdentityVerificationRequest.CredentialType.BEARER_TOKEN,
                    credential, config.audience(), config.clientId());
            assertTrue(provider.verifyCredential(request).isSuccess());
        }
    }

    @Test
    void fixedAsrMappingCanProduceOneSupportedMockArtifact() {
        var config = ReferenceMeetingDeploymentConfig.fromEnvironment(valid());
        var plan = new CapabilityPlanner().plan(config.mockCapabilities(), config.profile(),
                Set.of(), config.entitlement().supportedCapabilities(), config.capabilityLimits());
        Instant acceptedAt = Instant.parse("2026-10-01T00:00:00Z");
        String meetingId = "mtg_reference_mock";
        String subject = "demo-subject";
        var execution = FinalizationExecutionFact.freeze(meetingId,
                acceptedAt.minusSeconds(60), null, config.profile(), plan);
        var input = new FinalizationInputFact(meetingId, "input-binding", 1, 1000,
                "zh-CN", List.of(), acceptedAt, "sha256:test-input");
        var grant = new MeetingGrant("grant-id", config.deploymentScope(), subject,
                config.profile().id(), config.profile().revision(),
                config.entitlement().supportedCapabilities(),
                config.entitlement().supportedCapabilities(), 3_600_000,
                acceptedAt.minusSeconds(60), acceptedAt.plusSeconds(3600));
        var reservation = UsageReservation.reserved("reservation-id", grant, meetingId,
                config.trustedEstimate(), acceptedAt.minusSeconds(30),
                acceptedAt.plusSeconds(1800)).bind(meetingId, acceptedAt.minusSeconds(20), 0);
        var mockPlan = new DeterministicMockFinalizationPlanner().derive(
                config.deploymentScope(), subject, input, execution, reservation);
        assertEquals(List.of("transcript.final"), mockPlan.artifacts().stream()
                .map(item -> item.spec().artifactType()).toList());
        assertTrue(mockPlan.artifacts().getFirst().canonicalContent()
                .contains("\"text\":\"【模拟最终转写】"));
    }

    @Test
    void requiredFieldsAndCrossLimitsAreFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> ReferenceMeetingDeploymentConfig.fromEnvironment(Map.of()));
        for (String key : new String[]{DatabaseRuntimeConfig.DATABASE_URL,
                DatabaseRuntimeConfig.DATABASE_PASSWORD,
                ReferenceDevIdentityConfig.BEARER_TOKEN,
                ReferenceMeetingDeploymentConfig.DEPLOYMENT_SCOPE,
                ReferenceMeetingDeploymentConfig.PROFILE_ID,
                ReferenceMeetingDeploymentConfig.PROFILE_REVISION,
                ReferenceMeetingDeploymentConfig.PROFILE_REQUIRED_CAPABILITIES,
                ReferenceMeetingDeploymentConfig.ENTITLEMENT_SUPPORTED_CAPABILITIES,
                ReferenceMeetingDeploymentConfig.ENTITLEMENT_MAX_DURATION_MS,
                ReferenceMeetingDeploymentConfig.ENTITLEMENT_GRANT_TTL_MS,
                ReferenceMeetingDeploymentConfig.ENTITLEMENT_RESERVATION_TTL_MS,
                ReferenceMeetingDeploymentConfig.MOCK_CAPABILITIES,
                ReferenceMeetingDeploymentConfig.ESTIMATED_DURATION_MS,
                ReferenceMeetingDeploymentConfig.MAX_MEETING_DURATION_MS,
                ReferenceMeetingDeploymentConfig.MAX_AUDIO_FRAME_BYTES,
                ReferenceMeetingDeploymentConfig.EVENT_REPLAY_WINDOW,
                ReferenceMeetingDeploymentConfig.PAGE_TOKEN_SECRET,
                ReferenceMeetingDeploymentConfig.WSS_ORIGIN,
                ReferenceMeetingDeploymentConfig.WSS_TOKEN_TTL_MS}) {
            Map<String, String> missing = valid();
            missing.remove(key);
            rejects(missing, key);
        }
        Map<String, String> disabledIdentity = valid();
        disabledIdentity.put(ReferenceDevIdentityConfig.ENABLED, "false");
        rejects(disabledIdentity, ReferenceDevIdentityConfig.ENABLED);

        Map<String, String> estimateTooLarge = valid();
        estimateTooLarge.put(ReferenceMeetingDeploymentConfig.ESTIMATED_DURATION_MS, "7000000");
        rejects(estimateTooLarge, ReferenceMeetingDeploymentConfig.ESTIMATED_DURATION_MS);
        Map<String, String> reservationTooLong = valid();
        reservationTooLong.put(ReferenceMeetingDeploymentConfig.ENTITLEMENT_RESERVATION_TTL_MS,
                "600001");
        rejects(reservationTooLong, ReferenceMeetingDeploymentConfig.ENTITLEMENT_RESERVATION_TTL_MS);
        Map<String, String> unsupportedGrant = valid();
        unsupportedGrant.put(ReferenceMeetingDeploymentConfig.ENTITLEMENT_SUPPORTED_CAPABILITIES,
                CapabilityIds.TRANSCRIPT_REALTIME + "," + CapabilityIds.SUMMARY_STRUCTURED);
        rejects(unsupportedGrant, ReferenceMeetingDeploymentConfig.ENTITLEMENT_SUPPORTED_CAPABILITIES);
        Map<String, String> shortSecret = valid();
        shortSecret.put(ReferenceMeetingDeploymentConfig.PAGE_TOKEN_SECRET, "short");
        rejects(shortSecret, ReferenceMeetingDeploymentConfig.PAGE_TOKEN_SECRET);
        Map<String, String> impossibleAudio = valid();
        impossibleAudio.put(ReferenceMeetingDeploymentConfig.MAX_AUDIO_FRAME_BYTES, "639");
        rejects(impossibleAudio, ReferenceMeetingDeploymentConfig.MAX_AUDIO_FRAME_BYTES);
    }

    @Test
    void createFlagAndOptionalSettingsHaveStrictFixedMeaning() {
        Map<String, String> values = valid();
        assertFalse(ReferenceMeetingDeploymentConfig.fromEnvironment(values).createEnabled());
        values.put(ReferenceMeetingDeploymentConfig.CREATE_ENABLED, "true");
        assertTrue(ReferenceMeetingDeploymentConfig.fromEnvironment(values).createEnabled());
        values.put(ReferenceMeetingDeploymentConfig.CREATE_ENABLED, "false");
        assertFalse(ReferenceMeetingDeploymentConfig.fromEnvironment(values).createEnabled());
        for (String malformed : new String[]{"TRUE", " false", "1", ""}) {
            values.put(ReferenceMeetingDeploymentConfig.CREATE_ENABLED, malformed);
            rejects(values, ReferenceMeetingDeploymentConfig.CREATE_ENABLED);
        }
        values.remove(ReferenceMeetingDeploymentConfig.CREATE_ENABLED);
        values.put(ReferenceMeetingDeploymentConfig.PROFILE_OPTIONAL_CAPABILITIES,
                CapabilityIds.SUMMARY_STRUCTURED);
        values.put(ReferenceMeetingDeploymentConfig.MAX_REPLAY_AUDIO_MS, "0");
        assertEquals(0L, ReferenceMeetingDeploymentConfig.fromEnvironment(values)
                .runtimeLimits().maxReplayAudioMs());
        values.put(ReferenceMeetingDeploymentConfig.PROFILE_OPTIONAL_CAPABILITIES, " ");
        rejects(values, ReferenceMeetingDeploymentConfig.PROFILE_OPTIONAL_CAPABILITIES);
    }

    @Test
    void malformedWssNumbersAndSecretsNeverEchoInput() {
        Map<String, String> urlWithSecret = valid();
        urlWithSecret.put(DatabaseRuntimeConfig.DATABASE_URL,
                "jdbc:postgresql://unreachable.example.invalid:5432/demo?password=URL_SECRET_SENTINEL");
        rejects(urlWithSecret, DatabaseRuntimeConfig.DATABASE_URL);
        for (String origin : new String[]{"http://secret.example.invalid",
                "wss://secret.example.invalid/private", "wss://user:pass@secret.example.invalid",
                "wss://secret.example.invalid?private=1", "wss://invalid space"}) {
            Map<String, String> values = valid();
            values.put(ReferenceMeetingDeploymentConfig.WSS_ORIGIN, origin);
            rejects(values, ReferenceMeetingDeploymentConfig.WSS_ORIGIN);
        }
        for (String key : new String[]{ReferenceMeetingDeploymentConfig.MAX_AUDIO_FRAME_BYTES,
                ReferenceMeetingDeploymentConfig.EVENT_REPLAY_WINDOW,
                ReferenceMeetingDeploymentConfig.MAX_REPLAY_AUDIO_MS,
                ReferenceMeetingDeploymentConfig.WSS_TOKEN_TTL_MS}) {
            Map<String, String> values = valid();
            values.put(key, "999999999999999999999999-SECRET");
            rejects(values, key);
        }
        Map<String, String> values = valid();
        var config = ReferenceMeetingDeploymentConfig.fromEnvironment(values);
        String description = config.toString() + config.database() + config.identity();
        for (String sensitive : new String[]{TOKEN, SECRET, "db-secret-sentinel",
                "jdbc:postgresql://unreachable.example.invalid:5432/demo",
                "demo-subject", "trusted-audience"}) {
            assertFalse(description.contains(sensitive));
        }
    }

    @Test
    void optionalResultLinkTtlRequiresExplicitPositiveMilliseconds() {
        Map<String, String> values = valid();
        assertTrue(ReferenceMeetingDeploymentConfig.fromEnvironment(values)
                .resultLinkTtl().isEmpty());
        values.put(ReferenceMeetingDeploymentConfig.RESULT_LINK_TTL_MS, "60000");
        assertEquals(Duration.ofMinutes(1), ReferenceMeetingDeploymentConfig
                .fromEnvironment(values).resultLinkTtl().orElseThrow());
        for (String malformed : List.of("", "0", "-1", " 1", "1 ",
                "1.5", "999999999999999999999999-SECRET")) {
            values.put(ReferenceMeetingDeploymentConfig.RESULT_LINK_TTL_MS, malformed);
            rejects(values, ReferenceMeetingDeploymentConfig.RESULT_LINK_TTL_MS);
        }
    }

    private static void rejects(Map<String, String> values, String key) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ReferenceMeetingDeploymentConfig.fromEnvironment(values));
        assertTrue(failure.getMessage().contains(key), "missing stable field name");
        for (String sensitive : new String[]{TOKEN, SECRET, "db-secret-sentinel",
                "unreachable.example.invalid", "demo-subject",
                "999999999999999999999999-SECRET", "URL_SECRET_SENTINEL"}) {
            assertFalse(failure.getMessage().contains(sensitive), "config value leaked");
        }
    }

    private static Map<String, String> valid() {
        Map<String, String> result = new HashMap<>();
        result.put(DatabaseRuntimeConfig.DATABASE_URL,
                "jdbc:postgresql://unreachable.example.invalid:5432/demo");
        result.put(DatabaseRuntimeConfig.DATABASE_USER, "demo-user");
        result.put(DatabaseRuntimeConfig.DATABASE_PASSWORD, "db-secret-sentinel");
        result.put(ReferenceDevIdentityConfig.ENABLED, "true");
        result.put(ReferenceDevIdentityConfig.BEARER_TOKEN, TOKEN);
        result.put(ReferenceDevIdentityConfig.SUBJECT_SCOPE, "demo-subject");
        result.put(ReferenceDevIdentityConfig.ROLES, "USER");
        result.put(ReferenceDevIdentityConfig.PERMISSIONS,
                "meeting:create,meeting:stop,meeting:delete");
        result.put(ReferenceDevIdentityConfig.AUDIENCE, "trusted-audience");
        result.put(ReferenceDevIdentityConfig.CLIENT_ID, "trusted-client");
        result.put(ReferenceDevIdentityConfig.ISSUED_AT, "2026-01-01T00:00:00Z");
        result.put(ReferenceDevIdentityConfig.EXPIRES_AT, "2027-01-01T00:00:00Z");
        result.put(ReferenceDevIdentityConfig.CREDENTIAL_ID, "demo-credential");
        result.put(ReferenceMeetingDeploymentConfig.DEPLOYMENT_SCOPE, "demo-deployment");
        result.put(ReferenceMeetingDeploymentConfig.PROFILE_ID, "demo-profile");
        result.put(ReferenceMeetingDeploymentConfig.PROFILE_REVISION, "2");
        result.put(ReferenceMeetingDeploymentConfig.PROFILE_REQUIRED_CAPABILITIES,
                CapabilityIds.TRANSCRIPT_REALTIME);
        result.put(ReferenceMeetingDeploymentConfig.ENTITLEMENT_SUPPORTED_CAPABILITIES,
                CapabilityIds.TRANSCRIPT_REALTIME);
        result.put(ReferenceMeetingDeploymentConfig.ENTITLEMENT_MAX_DURATION_MS, "3600000");
        result.put(ReferenceMeetingDeploymentConfig.ENTITLEMENT_GRANT_TTL_MS, "600000");
        result.put(ReferenceMeetingDeploymentConfig.ENTITLEMENT_RESERVATION_TTL_MS, "300000");
        result.put(ReferenceMeetingDeploymentConfig.MOCK_CAPABILITIES,
                CapabilityIds.TRANSCRIPT_REALTIME);
        result.put(ReferenceMeetingDeploymentConfig.ESTIMATED_DURATION_MS, "600000");
        result.put(ReferenceMeetingDeploymentConfig.MAX_MEETING_DURATION_MS, "3600000");
        result.put(ReferenceMeetingDeploymentConfig.MAX_AUDIO_FRAME_BYTES, "32000");
        result.put(ReferenceMeetingDeploymentConfig.EVENT_REPLAY_WINDOW, "100");
        result.put(ReferenceMeetingDeploymentConfig.PAGE_TOKEN_SECRET, SECRET);
        result.put(ReferenceMeetingDeploymentConfig.WSS_ORIGIN, "wss://meeting.example.invalid");
        result.put(ReferenceMeetingDeploymentConfig.WSS_TOKEN_TTL_MS, "300000");
        return result;
    }
}

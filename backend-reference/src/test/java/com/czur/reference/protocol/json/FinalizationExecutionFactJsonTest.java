package com.czur.reference.protocol.json;

import com.czur.reference.application.finalization.FinalizationExecutionFact;
import com.czur.reference.domain.capability.CapabilityDecision;
import com.czur.reference.domain.capability.CapabilityPlan;
import com.czur.reference.domain.capability.ProductProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 冻结执行事实 canonical JSON、摘要、严格解码和防漂移门禁。 */
class FinalizationExecutionFactJsonTest {
    private static final String MEETING_ID = "meeting-secret-01";
    private static final String TRANSCRIPT = "cap.transcript";
    private static final String SUMMARY = "cap.summary";
    private static final String CANONICAL =
            "{\"specVersion\":\"finalization-execution-fact/v1\","
                    + "\"meetingId\":\"meeting-secret-01\","
                    + "\"startedAt\":\"2026-09-28T03:02:01.123456Z\","
                    + "\"title\":null,"
                    + "\"productProfile\":{"
                    + "\"id\":\"profile/secret-v2\","
                    + "\"revision\":2,"
                    + "\"requiredCapabilities\":[\"cap.transcript\"],"
                    + "\"optionalCapabilities\":[\"cap.summary\"],"
                    + "\"dependencies\":{\"cap.summary\":[\"cap.transcript\"]},"
                    + "\"missingPolicies\":{\"cap.summary\":\"DISABLE\"},"
                    + "\"fallbackCapabilities\":{},"
                    + "\"affectedWorkflows\":{"
                    + "\"cap.summary\":[\"workflow.summary\"],"
                    + "\"cap.transcript\":[\"workflow.transcript\"]},"
                    + "\"producedArtifacts\":{"
                    + "\"cap.summary\":[\"summary.structured\"],"
                    + "\"cap.transcript\":[\"transcript.final\"]}},"
                    + "\"capabilityPlan\":{"
                    + "\"planRevision\":7,"
                    + "\"decisions\":[{"
                    + "\"capability\":\"cap.summary\","
                    + "\"availability\":\"UNAVAILABLE\","
                    + "\"requiredByProfile\":false,"
                    + "\"action\":\"DISABLE\","
                    + "\"fallbackCapability\":null,"
                    + "\"affectedWorkflows\":[\"workflow.summary\"],"
                    + "\"producedArtifacts\":[\"summary.structured\"],"
                    + "\"warningCode\":\"NOT_REQUESTED\","
                    + "\"planRevision\":7},{"
                    + "\"capability\":\"cap.transcript\","
                    + "\"availability\":\"AVAILABLE\","
                    + "\"requiredByProfile\":true,"
                    + "\"action\":\"ENABLE\","
                    + "\"fallbackCapability\":null,"
                    + "\"affectedWorkflows\":[\"workflow.transcript\"],"
                    + "\"producedArtifacts\":[\"transcript.final\"],"
                    + "\"warningCode\":null,"
                    + "\"planRevision\":7}]}}";

    @Test
    void fixedFixtureFreezesCanonicalUtf8AndSha256() {
        FinalizationExecutionFact fact = fixture();

        assertEquals(CANONICAL, FinalizationExecutionFactJson.encode(fact));
        assertArrayEquals(CANONICAL.getBytes(StandardCharsets.UTF_8),
                FinalizationExecutionFactJson.encodeUtf8(fact));
        assertEquals("sha256:7d9ae58c11ad288d62577257de93ee3a402762ff52a797781ecd7dde0f8910ef",
                FinalizationExecutionFactJson.sha256(fact));
    }

    @Test
    void roundTripAndObjectFieldOrderConvergeToCanonicalForm() throws Exception {
        FinalizationExecutionFact expected = fixture();

        assertEquals(expected, FinalizationExecutionFactJson.decode(CANONICAL));
        assertEquals(CANONICAL, FinalizationExecutionFactJson.encode(
                FinalizationExecutionFactJson.decode(reorderedJson())));

        String offsetTime = CANONICAL.replace(
                "2026-09-28T03:02:01.123456Z",
                "2026-09-28T11:02:01.123456+08:00");
        assertEquals(CANONICAL, FinalizationExecutionFactJson.encode(
                FinalizationExecutionFactJson.decode(offsetTime)));

        FinalizationExecutionFact titled = new FinalizationExecutionFact(
                expected.meetingId(),
                expected.startedAt(),
                "secret \"title\"",
                expected.productProfile(),
                expected.capabilityPlan());
        String titledJson = FinalizationExecutionFactJson.encode(titled);
        assertEquals(titled, FinalizationExecutionFactJson.decode(titledJson));
        assertEquals(titledJson, FinalizationExecutionFactJson.encode(
                FinalizationExecutionFactJson.decode(titledJson)));
    }

    @Test
    void fullFallbackProfileAndPlanRoundTripWithoutCurrentConfiguration() {
        ProductProfile profile = new ProductProfile(
                "profile/fallback-v3",
                3,
                Set.of(TRANSCRIPT),
                Set.of(SUMMARY, "cap.summary-fallback"),
                Map.of(SUMMARY, Set.of(TRANSCRIPT)),
                Map.of(SUMMARY, ProductProfile.MissingCapabilityPolicy.USE_FALLBACK),
                Map.of(SUMMARY, "cap.summary-fallback"),
                Map.of(
                        TRANSCRIPT, List.of("workflow.transcript"),
                        SUMMARY, List.of("workflow.summary"),
                        "cap.summary-fallback", List.of("workflow.summary-fallback")),
                Map.of(
                        TRANSCRIPT, List.of("transcript.final"),
                        SUMMARY, List.of("summary.structured"),
                        "cap.summary-fallback", List.of("summary.structured")));
        CapabilityPlan plan = new CapabilityPlan(9, List.of(
                decision(TRANSCRIPT, true, CapabilityDecision.Action.ENABLE,
                        null, profile, TRANSCRIPT, 9),
                decision(SUMMARY, false, CapabilityDecision.Action.USE_FALLBACK,
                        "cap.summary-fallback", profile, "cap.summary-fallback", 9),
                decision("cap.summary-fallback", false, CapabilityDecision.Action.ENABLE,
                        null, profile, "cap.summary-fallback", 9)));
        FinalizationExecutionFact fact = FinalizationExecutionFact.freeze(
                "meeting-fallback-secret",
                Instant.parse("2026-09-28T03:02:01.654321Z"),
                "secret fallback title",
                profile,
                plan);

        String canonical = FinalizationExecutionFactJson.encode(fact);
        FinalizationExecutionFact decoded = FinalizationExecutionFactJson.decode(canonical);

        assertEquals(fact, decoded);
        assertEquals(fact.productProfile(), decoded.productProfile());
        assertEquals(canonical, FinalizationExecutionFactJson.encode(decoded));
        assertDecodeFailure(canonical.replace(
                "\"missingPolicies\":{\"cap.summary\":\"USE_FALLBACK\"}",
                "\"missingPolicies\":{\"cap.summary\":\"DISABLE\"}"));
    }

    @Test
    void unknownDuplicateMissingWrongTypeAndTrailingDataFailClosed() {
        assertDecodeFailure(CANONICAL.replaceFirst("\\{", "{\"unknown\":true,"));
        assertDecodeFailure(CANONICAL.replace(
                "\"meetingId\":\"meeting-secret-01\",",
                "\"meetingId\":\"meeting-secret-01\","
                        + "\"meetingId\":\"another-secret\","));
        assertDecodeFailure(CANONICAL.replace("\"title\":null,", ""));
        assertDecodeFailure(CANONICAL.replace(
                "\"planRevision\":7,\"decisions\"",
                "\"planRevision\":\"7\",\"decisions\""));
        assertDecodeFailure(CANONICAL.replace("\"title\":null", "\"title\":false"));
        assertDecodeFailure(CANONICAL.replace(
                "\"availability\":\"UNAVAILABLE\"",
                "\"availability\":\"UNKNOWN_SECRET_VALUE\""));
        assertDecodeFailure(CANONICAL.replace(
                "\"action\":\"ENABLE\"",
                "\"action\":\"DISABLE\""));
        assertDecodeFailure(CANONICAL.replace(
                "\"action\":\"DISABLE\"",
                "\"action\":\"FAIL_MEETING\""));
        String escapedHighSurrogate = "\"title\":\"" + '\\' + "uD800\"";
        String escapedOtherSurrogate = "\"title\":\"" + '\\' + "uD801\"";
        assertDecodeFailure(CANONICAL.replace("\"title\":null", escapedHighSurrogate));
        assertDecodeFailure(CANONICAL.replace("\"title\":null", escapedOtherSurrogate));
        String malformedUtf16 = "invalid-" + '\\' + "uD800";
        assertDecodeFailure(CANONICAL.replace("profile/secret-v2", malformedUtf16));
        assertDecodeFailure(CANONICAL.replace("cap.transcript", malformedUtf16));
        assertDecodeFailure(CANONICAL.replace("NOT_REQUESTED", malformedUtf16));
        assertDecodeFailure(CANONICAL + " true");
    }

    @Test
    void warningSemanticsFailClosedAndUnavailableDisableReasonRoundTrips() {
        assertDecodeFailure(CANONICAL.replace(
                "\"warningCode\":null",
                "\"warningCode\":\"secret.warning.unexpected\""));
        assertDecodeFailure(CANONICAL.replace(
                "\"warningCode\":\"NOT_REQUESTED\"",
                "\"warningCode\":null"));

        String dynamicReason = CANONICAL.replace(
                "\"warningCode\":\"NOT_REQUESTED\"",
                "\"warningCode\":\"secret.warning.dynamic-unavailable\"");
        FinalizationExecutionFact decoded = FinalizationExecutionFactJson.decode(dynamicReason);
        assertEquals(dynamicReason, FinalizationExecutionFactJson.encode(decoded));
    }

    @Test
    void duplicateCapabilitiesMappingDriftAndInvalidTimesFailClosed() {
        assertDecodeFailure(CANONICAL.replace(
                "\"requiredCapabilities\":[\"cap.transcript\"]",
                "\"requiredCapabilities\":[\"cap.transcript\",\"cap.transcript\"]"));

        int lastWorkflow = CANONICAL.lastIndexOf("workflow.summary");
        String drifted = CANONICAL.substring(0, lastWorkflow)
                + "secret.workflow.drifted"
                + CANONICAL.substring(lastWorkflow + "workflow.summary".length());
        assertDecodeFailure(drifted);

        assertDecodeFailure(CANONICAL.replace(
                "\"cap.summary\":[\"workflow.summary\"]",
                "\"cap.summary\":[\"workflow.summary\",\"workflow.summary\"]"));
        assertDecodeFailure(CANONICAL.replace(
                "\"cap.summary\":[\"summary.structured\"]",
                "\"cap.summary\":[\"summary.structured\",\"summary.structured\"]"));
        assertDecodeFailure(CANONICAL.replace(
                "\"affectedWorkflows\":[\"workflow.summary\"]",
                "\"affectedWorkflows\":[\"workflow.summary\",\"workflow.summary\"]"));
        assertDecodeFailure(CANONICAL.replace(
                "\"producedArtifacts\":[\"summary.structured\"]",
                "\"producedArtifacts\":[\"summary.structured\","
                        + "\"summary.structured\"]"));

        assertDecodeFailure(CANONICAL.replace(
                "2026-09-28T03:02:01.123456Z",
                "2026-09-28T03:02:01.1234567Z"));
        assertDecodeFailure(CANONICAL.replace(
                "2026-09-28T03:02:01.123456Z",
                "2026-09-28T03:02:60Z"));
        assertDecodeFailure(CANONICAL.replace(
                "2026-09-28T03:02:01.123456Z",
                "+10000-01-01T00:00:00Z"));
    }

    private static FinalizationExecutionFact fixture() {
        ProductProfile profile = new ProductProfile(
                "profile/secret-v2",
                2,
                Set.of(TRANSCRIPT),
                Set.of(SUMMARY),
                Map.of(SUMMARY, Set.of(TRANSCRIPT)),
                Map.of(SUMMARY, ProductProfile.MissingCapabilityPolicy.DISABLE),
                Map.of(),
                Map.of(
                        TRANSCRIPT, List.of("workflow.transcript"),
                        SUMMARY, List.of("workflow.summary")),
                Map.of(
                        TRANSCRIPT, List.of("transcript.final"),
                        SUMMARY, List.of("summary.structured")));
        CapabilityPlan plan = new CapabilityPlan(7, List.of(
                new CapabilityDecision(
                        TRANSCRIPT,
                        CapabilityDecision.Availability.AVAILABLE,
                        true,
                        CapabilityDecision.Action.ENABLE,
                        null,
                        List.of("workflow.transcript"),
                        List.of("transcript.final"),
                        null,
                        7),
                new CapabilityDecision(
                        SUMMARY,
                        CapabilityDecision.Availability.UNAVAILABLE,
                        false,
                        CapabilityDecision.Action.DISABLE,
                        null,
                        List.of("workflow.summary"),
                        List.of("summary.structured"),
                        "NOT_REQUESTED",
                        7)));
        return FinalizationExecutionFact.freeze(
                MEETING_ID,
                Instant.parse("2026-09-28T03:02:01.123456Z"),
                null,
                profile,
                plan);
    }

    private static CapabilityDecision decision(
            String capability,
            boolean required,
            CapabilityDecision.Action action,
            String fallback,
            ProductProfile profile,
            String mappingCapability,
            long revision) {
        return new CapabilityDecision(
                capability,
                action == CapabilityDecision.Action.USE_FALLBACK
                        ? CapabilityDecision.Availability.DEGRADED
                        : CapabilityDecision.Availability.AVAILABLE,
                required,
                action,
                fallback,
                profile.affectedWorkflowsOf(mappingCapability),
                profile.producedArtifactsOf(mappingCapability),
                action == CapabilityDecision.Action.USE_FALLBACK
                        ? "PROVIDER_CAPABILITY_MISSING" : null,
                revision);
    }

    private static String reorderedJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode original = mapper.readTree(CANONICAL);
        ObjectNode profile = mapper.createObjectNode();
        JsonNode originalProfile = original.get("productProfile");
        profile.set("producedArtifacts", originalProfile.get("producedArtifacts"));
        profile.set("affectedWorkflows", originalProfile.get("affectedWorkflows"));
        profile.set("fallbackCapabilities", originalProfile.get("fallbackCapabilities"));
        profile.set("missingPolicies", originalProfile.get("missingPolicies"));
        profile.set("dependencies", originalProfile.get("dependencies"));
        profile.set("optionalCapabilities", originalProfile.get("optionalCapabilities"));
        profile.set("requiredCapabilities", originalProfile.get("requiredCapabilities"));
        profile.set("revision", originalProfile.get("revision"));
        profile.set("id", originalProfile.get("id"));

        ObjectNode reordered = mapper.createObjectNode();
        reordered.set("capabilityPlan", original.get("capabilityPlan"));
        reordered.set("productProfile", profile);
        reordered.set("title", original.get("title"));
        reordered.set("startedAt", original.get("startedAt"));
        reordered.set("meetingId", original.get("meetingId"));
        reordered.set("specVersion", original.get("specVersion"));
        return mapper.writeValueAsString(reordered);
    }

    private static void assertDecodeFailure(String json) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> FinalizationExecutionFactJson.decode(json));
        assertEquals("Finalization execution fact JSON is invalid", failure.getMessage());
        assertEquals(null, failure.getCause());
        assertFalse(failure.toString().contains(MEETING_ID));
        assertFalse(failure.toString().contains("another-secret"));
        assertFalse(failure.toString().contains("cap.transcript"));
        assertFalse(failure.toString().contains("secret.workflow"));
    }
}

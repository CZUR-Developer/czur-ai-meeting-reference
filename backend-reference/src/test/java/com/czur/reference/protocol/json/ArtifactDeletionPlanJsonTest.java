package com.czur.reference.protocol.json;

import com.czur.reference.application.deletion.ArtifactDeletionPlan;
import com.czur.reference.application.deletion.ArtifactDeletionPlan.SourceFinalizationWorkflow;
import com.czur.reference.application.deletion.ArtifactDeletionTarget;
import com.czur.reference.application.finalization.FinalizationCheckpoint;
import com.czur.reference.application.port.WorkflowStep;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 删除计划 canonical JSON、摘要、严格输入和 Workflow Definition 门禁。 */
class ArtifactDeletionPlanJsonTest {
    private static final String MEETING = "meeting-deletion-plan-v1";
    private static final String SOURCE_DIGEST = "sha256:" + "a".repeat(64);
    private static final String CHECKSUM_A = "sha256:" + "1".repeat(64);
    private static final String CHECKSUM_B = "sha256:" + "2".repeat(64);
    private static final Instant ACCEPTED_AT =
            Instant.parse("2026-09-29T06:07:08.123456Z");
    private static final String CANONICAL = "{\"specVersion\":\"artifact-deletion-plan/v1\""
            + ",\"meetingId\":\"meeting-deletion-plan-v1\""
            + ",\"acceptedAt\":\"2026-09-29T06:07:08.123456Z\""
            + ",\"sourceManifestRevision\":7"
            + ",\"sourceFinalizationWorkflow\":{\"meetingId\":\"meeting-deletion-plan-v1\""
            + ",\"stepType\":\"mock-finalization\""
            + ",\"stepKey\":\"mock_finalize_v1_8dbc672821b0fe1c88dd812d860230b5f0f4e8da277a5c1347b4f2059b9e0f63\""
            + ",\"checkpointType\":\"mock-finalization-checkpoint/v1\""
            + ",\"contentDigest\":\"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}"
            + ",\"targets\":[{\"artifactId\":\"artifact-summary\",\"spec\":{"
            + "\"artifactId\":\"artifact-summary\""
            + ",\"meetingId\":\"meeting-deletion-plan-v1\""
            + ",\"artifactType\":\"summary.structured\""
            + ",\"mediaType\":\"application/json\",\"language\":\"zh-CN\""
            + ",\"revision\":2,\"expectedSize\":80"
            + ",\"expectedChecksum\":\"sha256:1111111111111111111111111111111111111111111111111111111111111111\"}}"
            + ",{\"artifactId\":\"artifact-transcript\",\"spec\":{"
            + "\"artifactId\":\"artifact-transcript\""
            + ",\"meetingId\":\"meeting-deletion-plan-v1\""
            + ",\"artifactType\":\"transcript.final\""
            + ",\"mediaType\":\"application/json\",\"language\":null"
            + ",\"revision\":1,\"expectedSize\":120"
            + ",\"expectedChecksum\":\"sha256:2222222222222222222222222222222222222222222222222222222222222222\"}}]}";

    @Test
    void fixedFixtureFreezesCanonicalBytesDigestAndWorkflowDefinition() {
        ArtifactDeletionPlan plan = fixture();

        assertEquals(CANONICAL, ArtifactDeletionPlanJson.encode(plan));
        assertArrayEquals(CANONICAL.getBytes(StandardCharsets.UTF_8),
                ArtifactDeletionPlanJson.encodeUtf8(plan));
        assertEquals(
                "sha256:92dfd01a5480806662f66400169d95b1b281f4b4ebd6162f1fb50caa174d0b6c",
                ArtifactDeletionPlanJson.sha256(plan));

        WorkflowStep workflow = ArtifactDeletionPlanJson.workflowStep(plan);
        assertEquals(plan.workflowKey(), workflow.key());
        assertEquals("artifact-deletion", workflow.key().stepType());
        assertEquals("artifact-deletion-plan/v1", workflow.checkpointType());
        assertEquals(CANONICAL, workflow.canonicalPayload());
        assertEquals(ArtifactDeletionPlanJson.sha256(plan), workflow.contentDigest());
        assertEquals(ACCEPTED_AT, workflow.nextAttemptAt());
        assertEquals(ACCEPTED_AT, workflow.updatedAt());
        assertEquals(WorkflowStep.Status.PENDING, workflow.status());
    }

    @Test
    void targetArrayPermutationsConvergeToOneCanonicalJsonAndDigest() throws Exception {
        ArtifactDeletionPlan expected = fixture();
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(CANONICAL);
        ArrayNode targets = (ArrayNode) root.path("targets");
        JsonNode first = targets.get(0);
        JsonNode second = targets.get(1);
        targets.removeAll();
        targets.add(second);
        targets.add(first);
        String permuted = mapper.writeValueAsString(root);

        ArtifactDeletionPlan decoded = ArtifactDeletionPlanJson.decode(permuted);
        assertEquals(expected, decoded);
        assertEquals(CANONICAL, ArtifactDeletionPlanJson.encode(decoded));
        assertEquals(ArtifactDeletionPlanJson.sha256(expected),
                ArtifactDeletionPlanJson.sha256(decoded));
        assertEquals(expected, ArtifactDeletionPlanJson.decodeUtf8(
                permuted.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void strictEmptyPlanWithNullSourcesRoundTripsWithoutInventingTargets() {
        ArtifactDeletionPlan empty = new ArtifactDeletionPlan(
                MEETING, ACCEPTED_AT, null, null, List.of());
        String canonical = "{\"specVersion\":\"artifact-deletion-plan/v1\""
                + ",\"meetingId\":\"meeting-deletion-plan-v1\""
                + ",\"acceptedAt\":\"2026-09-29T06:07:08.123456Z\""
                + ",\"sourceManifestRevision\":null"
                + ",\"sourceFinalizationWorkflow\":null,\"targets\":[]}";

        assertEquals(canonical, ArtifactDeletionPlanJson.encode(empty));
        assertEquals(empty, ArtifactDeletionPlanJson.decode(canonical));
        assertTrue(ArtifactDeletionPlanJson.decode(canonical).targets().isEmpty());
    }

    @Test
    void unknownDuplicateMissingAndNonCanonicalObjectOrderFailClosed() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode missing = (ObjectNode) mapper.readTree(CANONICAL);
        missing.remove("acceptedAt");
        ObjectNode unknown = (ObjectNode) mapper.readTree(CANONICAL);
        unknown.put("credential", "secret");
        ObjectNode reorderedRoot = mapper.createObjectNode();
        ObjectNode original = (ObjectNode) mapper.readTree(CANONICAL);
        reorderedRoot.set("meetingId", original.get("meetingId"));
        reorderedRoot.set("specVersion", original.get("specVersion"));
        reorderedRoot.set("acceptedAt", original.get("acceptedAt"));
        reorderedRoot.set("sourceManifestRevision", original.get("sourceManifestRevision"));
        reorderedRoot.set("sourceFinalizationWorkflow",
                original.get("sourceFinalizationWorkflow"));
        reorderedRoot.set("targets", original.get("targets"));
        ObjectNode reorderedSpec = (ObjectNode) mapper.readTree(CANONICAL);
        ObjectNode spec = (ObjectNode) reorderedSpec.path("targets").get(0).path("spec");
        JsonNode artifactId = spec.remove("artifactId");
        spec.set("artifactId", artifactId);
        ObjectNode reorderedSource = (ObjectNode) mapper.readTree(CANONICAL);
        ObjectNode source = (ObjectNode) reorderedSource.path("sourceFinalizationWorkflow");
        JsonNode sourceMeeting = source.remove("meetingId");
        source.set("meetingId", sourceMeeting);
        ObjectNode reorderedTarget = (ObjectNode) mapper.readTree(CANONICAL);
        ObjectNode target = (ObjectNode) reorderedTarget.path("targets").get(0);
        JsonNode targetArtifactId = target.remove("artifactId");
        target.set("artifactId", targetArtifactId);

        assertInvalid("{\"specVersion\":\"artifact-deletion-plan/v1\","
                + CANONICAL.substring(1));
        assertInvalid(mapper.writeValueAsString(missing));
        assertInvalid(mapper.writeValueAsString(unknown));
        assertInvalid(mapper.writeValueAsString(reorderedRoot));
        assertInvalid(mapper.writeValueAsString(reorderedSpec));
        assertInvalid(mapper.writeValueAsString(reorderedSource));
        assertInvalid(mapper.writeValueAsString(reorderedTarget));
    }

    @Test
    void whitespaceEscapesTimeAndIntegerAmbiguityFailClosed() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode decimalRevision = (ObjectNode) mapper.readTree(CANONICAL);
        decimalRevision.put("sourceManifestRevision", 7.0d);
        ObjectNode overflowRevision = (ObjectNode) mapper.readTree(CANONICAL);
        overflowRevision.put("sourceManifestRevision", 2_147_483_648L);
        ObjectNode negativeSize = (ObjectNode) mapper.readTree(CANONICAL);
        ((ObjectNode) negativeSize.path("targets").get(0).path("spec"))
                .put("expectedSize", -1);

        assertInvalid(" " + CANONICAL);
        assertInvalid(CANONICAL.replace("meeting-deletion-plan-v1", "meeting\\u002ddeletion-plan-v1"));
        assertInvalid(CANONICAL.replace(
                "2026-09-29T06:07:08.123456Z",
                "2026-09-29T14:07:08.123456+08:00"));
        assertInvalid(CANONICAL.replace(
                "2026-09-29T06:07:08.123456Z",
                "2026-09-29T06:07:08.1234560Z"));
        assertInvalid(CANONICAL.replace(
                "2026-09-29T06:07:08.123456Z",
                "2026-09-29T06:07:08.123456789Z"));
        assertInvalid(mapper.writeValueAsString(decimalRevision));
        assertInvalid(mapper.writeValueAsString(overflowRevision));
        assertInvalid(mapper.writeValueAsString(negativeSize));
    }

    @Test
    void crossMeetingDuplicateAndTargetSpecIdentityDriftFailClosed() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode crossMeeting = (ObjectNode) mapper.readTree(CANONICAL);
        ((ObjectNode) crossMeeting.path("targets").get(0).path("spec"))
                .put("meetingId", "another-meeting");
        ObjectNode targetIdentity = (ObjectNode) mapper.readTree(CANONICAL);
        ((ObjectNode) targetIdentity.path("targets").get(0))
                .put("artifactId", "drifted-artifact");
        ObjectNode duplicateId = (ObjectNode) mapper.readTree(CANONICAL);
        ObjectNode duplicateIdSecond = (ObjectNode) duplicateId.path("targets").get(1);
        duplicateIdSecond.put("artifactId", "artifact-summary");
        ((ObjectNode) duplicateIdSecond.path("spec"))
                .put("artifactId", "artifact-summary");
        ObjectNode duplicateTypeRevision = (ObjectNode) mapper.readTree(CANONICAL);
        ObjectNode duplicateSpec = (ObjectNode) duplicateTypeRevision
                .path("targets").get(1).path("spec");
        duplicateSpec.put("artifactType", "summary.structured");
        duplicateSpec.put("revision", 2);

        assertInvalid(mapper.writeValueAsString(crossMeeting));
        assertInvalid(mapper.writeValueAsString(targetIdentity));
        assertInvalid(mapper.writeValueAsString(duplicateId));
        assertInvalid(mapper.writeValueAsString(duplicateTypeRevision));
    }

    @Test
    void sourceWorkflowIdentityCannotDriftIndependently() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String field : new String[]{
                "meetingId", "stepType", "stepKey", "checkpointType", "contentDigest"}) {
            ObjectNode root = (ObjectNode) mapper.readTree(CANONICAL);
            ((ObjectNode) root.path("sourceFinalizationWorkflow"))
                    .put(field, "drifted-value");
            assertInvalid(mapper.writeValueAsString(root));
        }
        ObjectNode revisionZero = (ObjectNode) mapper.readTree(CANONICAL);
        revisionZero.put("sourceManifestRevision", 0);
        assertInvalid(mapper.writeValueAsString(revisionZero));
    }

    @Test
    void malformedUtf8TrailingContentOversizeAndWrongShapesFailClosed() {
        assertInvalidUtf8(new byte[]{(byte) 0xc3, (byte) 0x28});
        assertInvalid(CANONICAL + "{}");
        assertInvalid("null");
        assertInvalid("[]");
        assertInvalid(CANONICAL + " ".repeat(ArtifactDeletionPlanJson.MAX_PLAN_BYTES));
        assertInvalid(CANONICAL.replace("\"targets\":[", "\"targets\":{\"value\":"));
        assertInvalid(CANONICAL.replace(
                "\"sourceFinalizationWorkflow\":{",
                "\"sourceFinalizationWorkflow\":[{"));
        assertInvalid(CANONICAL.replace(
                "meeting-deletion-plan-v1",
                "meeting-" + Character.toString((char) 0xd800)));
    }

    @Test
    void blankFieldsAndEncodingAboveTheCanonicalSizeLimitFailClosed() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode blankMeeting = (ObjectNode) mapper.readTree(CANONICAL);
        blankMeeting.put("meetingId", " ");
        ObjectNode blankArtifact = (ObjectNode) mapper.readTree(CANONICAL);
        ObjectNode blankTarget = (ObjectNode) blankArtifact.path("targets").get(0);
        blankTarget.put("artifactId", " ");
        ((ObjectNode) blankTarget.path("spec")).put("artifactId", " ");

        assertInvalid(mapper.writeValueAsString(blankMeeting));
        assertInvalid(mapper.writeValueAsString(blankArtifact));

        ArrayList<ArtifactDeletionTarget> largeTargets = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            String longType = "type-" + index + "-" + "x".repeat(3_900);
            largeTargets.add(ArtifactDeletionTarget.fromSpec(new WriteIntentSpec(
                    "artifact-large-" + index,
                    MEETING,
                    longType,
                    "application/json",
                    null,
                    1,
                    index,
                    CHECKSUM_A)));
        }
        ArtifactDeletionPlan oversized = new ArtifactDeletionPlan(
                MEETING, ACCEPTED_AT, null, null, largeTargets);
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> ArtifactDeletionPlanJson.encode(oversized));
        assertEquals("Artifact deletion plan JSON encoding failed", failure.getMessage());
        assertNull(failure.getCause());
    }

    @Test
    void publicFailuresAndDiagnosticsDoNotLeakPlanContentsOrDigest() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ArtifactDeletionPlanJson.decode(
                        CANONICAL.replaceFirst("artifact-summary", "secret-artifact")));
        assertEquals("Artifact deletion plan JSON is invalid", failure.getMessage());
        assertNull(failure.getCause());

        String diagnostic = fixture() + " " + ArtifactDeletionPlanJson.workflowStep(fixture());
        for (String secret : new String[]{
                MEETING, "artifact-summary", CHECKSUM_A, SOURCE_DIGEST,
                CANONICAL, ArtifactDeletionPlanJson.sha256(fixture())}) {
            assertFalse(diagnostic.contains(secret));
        }
        assertTrue(diagnostic.contains("targetCount=2"));
    }

    private static ArtifactDeletionPlan fixture() {
        ArtifactDeletionTarget transcript = ArtifactDeletionTarget.fromSpec(
                new WriteIntentSpec(
                        "artifact-transcript", MEETING, "transcript.final",
                        "application/json", null, 1, 120, CHECKSUM_B));
        ArtifactDeletionTarget summary = ArtifactDeletionTarget.fromSpec(
                new WriteIntentSpec(
                        "artifact-summary", MEETING, "summary.structured",
                        "application/json", "zh-CN", 2, 80, CHECKSUM_A));
        return new ArtifactDeletionPlan(
                MEETING,
                ACCEPTED_AT,
                7,
                new SourceFinalizationWorkflow(
                        FinalizationCheckpoint.workflowKey(MEETING),
                        FinalizationCheckpoint.CHECKPOINT_TYPE,
                        SOURCE_DIGEST),
                List.of(transcript, summary));
    }

    private static void assertInvalid(String json) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ArtifactDeletionPlanJson.decode(json));
        assertEquals("Artifact deletion plan JSON is invalid", failure.getMessage());
        assertNull(failure.getCause());
    }

    private static void assertInvalidUtf8(byte[] json) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ArtifactDeletionPlanJson.decodeUtf8(json));
        assertEquals("Artifact deletion plan JSON is invalid", failure.getMessage());
        assertNull(failure.getCause());
    }
}

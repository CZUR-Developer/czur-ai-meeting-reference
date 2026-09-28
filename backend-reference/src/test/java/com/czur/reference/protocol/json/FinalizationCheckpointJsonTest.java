package com.czur.reference.protocol.json;

import com.czur.reference.application.finalization.FinalizationCheckpoint;
import com.czur.reference.application.finalization.FinalizationCheckpointTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 完整 checkpoint canonical JSON、严格恢复和恶意输入关闭门禁。 */
class FinalizationCheckpointJsonTest {
    private static final String CANONICAL = "{\"specVersion\":\"mock-finalization-checkpoint/v1\""
            + ",\"meetingId\":\"meeting-checkpoint-v1\""
            + ",\"inputBinding\":\"input-binding-checkpoint\""
            + ",\"inputFactDigest\":\"sha256:199756e03894609f0e1472796d9a0be8f8bc1c41c87a147825938683510d5084\""
            + ",\"executionFactDigest\":\"sha256:30af3ec7b9fe8fc3d9942026e30c1a9c4c77f6a4d84094ab644a44689ab395a4\""
            + ",\"artifacts\":[{\"spec\":{\"artifactId\":\"art_mock_v1_977da7d3f1c2e910200b340144c0b8e1e5a521d3d142ecb588b6a074bd643a7b\""
            + ",\"meetingId\":\"meeting-checkpoint-v1\""
            + ",\"artifactType\":\"summary.structured\""
            + ",\"mediaType\":\"application/json\",\"language\":\"zh-CN\""
            + ",\"revision\":1,\"expectedSize\":298"
            + ",\"expectedChecksum\":\"sha256:c5d614db54e53c2ba265e613f6dd098b47a5408d24376fc93148dcb3c2ecd9ae\"}"
            + ",\"contentBase64\":\"eyJhcnRpZmFjdFR5cGUiOiJzdW1tYXJ5LnN0cnVjdHVyZWQiLCJleGVjdXRpb25GaW5nZXJwcmludCI6IjMwYWYzZWM3YjlmZThmYzNkOTk0MjAyNmUzMGMxYTljNGM3N2Y2YTRkODQwOTRhYjY0NGE0NDY4OWFiMzk1YTQiLCJsYW5ndWFnZSI6InpoLUNOIiwibW9jayI6dHJ1ZSwicmV2aXNpb24iOjEsInNjaGVtYVZlcnNpb24iOiJtb2NrLWZpbmFsaXphdGlvbi92MSIsInNvdXJjZUZpbmdlcnByaW50IjoiMTk5NzU2ZTAzODk0NjA5ZjBlMTQ3Mjc5NmQ5YTBiZThmOGJjMWM0MWM4N2ExNDc4MjU5Mzg2ODM1MTBkNTA4NCJ9Cg==\""
            + ",\"claim\":{\"size\":298,\"checksum\":\"sha256:c5d614db54e53c2ba265e613f6dd098b47a5408d24376fc93148dcb3c2ecd9ae\"}}"
            + ",{\"spec\":{\"artifactId\":\"art_mock_v1_b3e2ff418de3c91be70ef2aeee15532ae471b03400c4075579f8d06a862a11fc\""
            + ",\"meetingId\":\"meeting-checkpoint-v1\""
            + ",\"artifactType\":\"transcript.final\""
            + ",\"mediaType\":\"application/json\",\"language\":\"zh-CN\""
            + ",\"revision\":1,\"expectedSize\":296"
            + ",\"expectedChecksum\":\"sha256:1e919e9500793f4aabe77efbcd339e842369c45020f22f1a68cce5e204c19a9e\"}"
            + ",\"contentBase64\":\"eyJhcnRpZmFjdFR5cGUiOiJ0cmFuc2NyaXB0LmZpbmFsIiwiZXhlY3V0aW9uRmluZ2VycHJpbnQiOiIzMGFmM2VjN2I5ZmU4ZmMzZDk5NDIwMjZlMzBjMWE5YzRjNzdmNmE0ZDg0MDk0YWI2NDRhNDQ2ODlhYjM5NWE0IiwibGFuZ3VhZ2UiOiJ6aC1DTiIsIm1vY2siOnRydWUsInJldmlzaW9uIjoxLCJzY2hlbWFWZXJzaW9uIjoibW9jay1maW5hbGl6YXRpb24vdjEiLCJzb3VyY2VGaW5nZXJwcmludCI6IjE5OTc1NmUwMzg5NDYwOWYwZTE0NzI3OTZkOWEwYmU4ZjhiYzFjNDFjODdhMTQ3ODI1OTM4NjgzNTEwZDUwODQifQo=\""
            + ",\"claim\":{\"size\":296,\"checksum\":\"sha256:1e919e9500793f4aabe77efbcd339e842369c45020f22f1a68cce5e204c19a9e\"}}]"
            + ",\"commitPlan\":{\"meetingId\":\"meeting-checkpoint-v1\""
            + ",\"deploymentScope\":\"deployment-checkpoint\""
            + ",\"subjectScope\":\"subject-checkpoint\""
            + ",\"operationScope\":\"usage.commit.v1\""
            + ",\"idempotencyKey\":\"finalization-usage-v1:32213ed864a2e06a0c36bf0cdcf985ce5bfb09c4956a9bfe853ed38f80857be2\""
            + ",\"requestDigest\":\"sha256:0de0d11a1b8c3bf4b815818d32df905cbfa1c8eab167a424dfbad67f256f7be9\""
            + ",\"reservationId\":\"reservation-a\",\"expectedVersion\":1"
            + ",\"usageUnit\":\"MEETING_DURATION_MS\",\"usageAmount\":54321"
            + ",\"sourceInputBinding\":\"input-binding-checkpoint\""
            + ",\"sourceInputDigest\":\"sha256:source-checkpoint\""
            + ",\"sourceAcceptedAt\":\"2026-09-28T08:09:10.123456Z\"}}";
    private static final String SHA256 =
            "sha256:c6e0598f7e40b65ea274292d2d01407c641e4b2dda2b5bd75be36a0db5fc2e67";

    @Test
    void fixedFixtureFreezesCanonicalUtf8BytesAndSha256() {
        FinalizationCheckpoint checkpoint = FinalizationCheckpointTest.fixturePlan().checkpoint();

        assertEquals(CANONICAL, FinalizationCheckpointJson.encode(checkpoint));
        assertArrayEquals(CANONICAL.getBytes(StandardCharsets.UTF_8),
                FinalizationCheckpointJson.encodeUtf8(checkpoint));
        assertEquals(SHA256, FinalizationCheckpointJson.sha256(checkpoint));
        assertTrue(FinalizationCheckpointJson.encodeUtf8(checkpoint).length
                < FinalizationCheckpointJson.MAX_CHECKPOINT_BYTES);
        for (String forbidden : new String[]{
                "receipt", "leaseOwner", "leaseToken", "credential",
                "presigned", "localPath", "storageBinding"}) {
            assertFalse(CANONICAL.contains(forbidden));
        }
    }

    @Test
    void roundTripAndObjectOrArtifactReorderingConvergeToCanonicalBytes() throws Exception {
        FinalizationCheckpoint expected = FinalizationCheckpointTest.fixturePlan().checkpoint();
        assertEquals(expected, FinalizationCheckpointJson.decode(CANONICAL));
        assertEquals(expected, FinalizationCheckpointJson.decodeUtf8(
                CANONICAL.getBytes(StandardCharsets.UTF_8)));

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(CANONICAL);
        ArrayNode artifacts = (ArrayNode) root.remove("artifacts");
        ArrayNode reversedArtifacts = mapper.createArrayNode();
        reversedArtifacts.add(artifacts.get(1));
        reversedArtifacts.add(artifacts.get(0));
        ObjectNode reordered = mapper.createObjectNode();
        reordered.set("commitPlan", root.remove("commitPlan"));
        reordered.set("artifacts", reversedArtifacts);
        reordered.set("executionFactDigest", root.remove("executionFactDigest"));
        reordered.set("inputFactDigest", root.remove("inputFactDigest"));
        reordered.set("inputBinding", root.remove("inputBinding"));
        reordered.set("meetingId", root.remove("meetingId"));
        reordered.set("specVersion", root.remove("specVersion"));

        FinalizationCheckpoint decoded = FinalizationCheckpointJson.decode(
                mapper.writeValueAsString(reordered));
        assertEquals(expected, decoded);
        assertEquals(CANONICAL, FinalizationCheckpointJson.encode(decoded));

        String offsetTime = CANONICAL.replace(
                "2026-09-28T08:09:10.123456Z",
                "2026-09-28T16:09:10.123456+08:00");
        assertEquals(CANONICAL, FinalizationCheckpointJson.encode(
                FinalizationCheckpointJson.decode(offsetTime)));
    }

    @Test
    void duplicateUnknownMissingWrongTypeAndTrailingTokensFailClosed() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode missing = (ObjectNode) mapper.readTree(CANONICAL);
        missing.remove("inputFactDigest");
        ObjectNode nestedUnknown = (ObjectNode) mapper.readTree(CANONICAL);
        ((ObjectNode) nestedUnknown.path("commitPlan")).put("credential", "forbidden");
        ObjectNode wrongType = (ObjectNode) mapper.readTree(CANONICAL);
        ((ObjectNode) wrongType.path("commitPlan")).put("expectedVersion", 1.0d);

        assertInvalid("{\"specVersion\":\"mock-finalization-checkpoint/v1\","
                + CANONICAL.substring(1));
        assertInvalid(CANONICAL.substring(0, CANONICAL.length() - 1)
                + ",\"unknown\":true}");
        assertInvalid(mapper.writeValueAsString(missing));
        assertInvalid(mapper.writeValueAsString(nestedUnknown));
        assertInvalid(mapper.writeValueAsString(wrongType));
        assertInvalid(CANONICAL + "{}");
        assertInvalid("null");
        assertInvalid("[]");
    }

    @Test
    void canonicalBase64ContentClaimsAndArtifactOwnershipAreClosed() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode extraPadding = (ObjectNode) mapper.readTree(CANONICAL);
        ObjectNode artifact = (ObjectNode) extraPadding.path("artifacts").get(0);
        artifact.put("contentBase64", artifact.path("contentBase64").textValue() + "=");
        ObjectNode mismatchedClaim = (ObjectNode) mapper.readTree(CANONICAL);
        ((ObjectNode) mismatchedClaim.path("artifacts").get(0).path("claim"))
                .put("size", 303);
        ObjectNode wrongMeeting = (ObjectNode) mapper.readTree(CANONICAL);
        ((ObjectNode) wrongMeeting.path("artifacts").get(0).path("spec"))
                .put("meetingId", "another-meeting");
        ObjectNode duplicateArtifact = (ObjectNode) mapper.readTree(CANONICAL);
        ArrayNode duplicateArray = (ArrayNode) duplicateArtifact.path("artifacts");
        duplicateArray.set(1, duplicateArray.get(0).deepCopy());

        assertInvalid(mapper.writeValueAsString(extraPadding));
        assertInvalid(mapper.writeValueAsString(mismatchedClaim));
        assertInvalid(mapper.writeValueAsString(wrongMeeting));
        assertInvalid(mapper.writeValueAsString(duplicateArtifact));
    }

    @Test
    void commitIdentityExpectedVersionAndSourceProvenanceCannotBeAlteredIndependently()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String field : new String[]{
                "deploymentScope", "subjectScope", "operationScope", "idempotencyKey",
                "requestDigest", "reservationId", "sourceInputBinding", "sourceInputDigest"}) {
            ObjectNode root = (ObjectNode) mapper.readTree(CANONICAL);
            ((ObjectNode) root.path("commitPlan")).put(field, "drifted-value");
            assertInvalid(mapper.writeValueAsString(root));
        }
        ObjectNode version = (ObjectNode) mapper.readTree(CANONICAL);
        ((ObjectNode) version.path("commitPlan")).put("expectedVersion", 2);
        ObjectNode amount = (ObjectNode) mapper.readTree(CANONICAL);
        ((ObjectNode) amount.path("commitPlan")).put("usageAmount", 54_322);
        ObjectNode time = (ObjectNode) mapper.readTree(CANONICAL);
        ((ObjectNode) time.path("commitPlan"))
                .put("sourceAcceptedAt", "2026-09-28T08:09:11.123456Z");

        assertInvalid(mapper.writeValueAsString(version));
        assertInvalid(mapper.writeValueAsString(amount));
        assertInvalid(mapper.writeValueAsString(time));
    }

    @Test
    void malformedUtf8IllegalSurrogateSubMicrosecondAndOversizeDocumentsFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> FinalizationCheckpointJson.decodeUtf8(
                        new byte[]{(byte) 0xc3, (byte) 0x28}));
        String illegalSurrogate = "input-" + Character.toString((char) 0xd800);
        assertInvalid(CANONICAL.replace("input-binding-checkpoint", illegalSurrogate));
        assertInvalid(CANONICAL.replace(
                "2026-09-28T08:09:10.123456Z",
                "2026-09-28T08:09:10.123456789Z"));
        assertInvalid(CANONICAL + " ".repeat(
                FinalizationCheckpointJson.MAX_CHECKPOINT_BYTES));
    }

    private static void assertInvalid(String json) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> FinalizationCheckpointJson.decode(json));
        assertEquals("Finalization checkpoint JSON is invalid", failure.getMessage());
        assertNull(failure.getCause());
    }
}

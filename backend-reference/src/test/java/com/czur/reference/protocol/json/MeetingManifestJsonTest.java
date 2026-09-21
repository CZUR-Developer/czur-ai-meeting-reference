package com.czur.reference.protocol.json;

import com.czur.reference.application.port.MeetingManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公开 Manifest JSON 的关闭式编解码测试。
 *
 * <p>正向 Fixture 必须先解码、再 canonical 编码并再次解码为同一个领域值；
 * 反向 Fixture 和内联攻击样例必须在进入 application port 前被拒绝。</p>
 */
class MeetingManifestJsonTest {
    private static final List<String> POSITIVE_FIXTURES = List.of(
            "meeting-manifest.ready.json",
            "meeting-manifest.asr-only.json",
            "meeting-manifest.failed.json",
            "meeting-manifest.deletion-pending.json",
            "meeting-manifest.deleted.json");
    private static final List<String> INVALID_FIXTURES = List.of(
            "meeting-manifest.cancelled.invalid.json",
            "meeting-manifest.extra-property.invalid.json",
            "meeting-manifest.missing-id.invalid.json",
            "meeting-manifest.negative-duration.invalid.json",
            "meeting-manifest.nested-extra.invalid.json",
            "meeting-manifest.wrong-type.invalid.json");

    @Test
    void everyPositiveFixtureRoundTripsThroughCanonicalJson() throws Exception {
        for (String fixture : POSITIVE_FIXTURES) {
            MeetingManifest decoded = MeetingManifestJson.decode(fixture(fixture));
            String canonical = MeetingManifestJson.encode(decoded);

            assertEquals(decoded, MeetingManifestJson.decode(canonical), fixture);
            assertFalse(canonical.contains("genTime"), fixture);
            assertEquals(canonical,
                    MeetingManifestJson.encode(MeetingManifestJson.decode(canonical)), fixture);
        }
    }

    @Test
    void everyInvalidFixtureFailsClosed() throws Exception {
        for (String fixture : INVALID_FIXTURES) {
            assertThrows(IllegalArgumentException.class,
                    () -> MeetingManifestJson.decode(fixture(fixture)), fixture);
        }
    }

    @Test
    void canonicalJsonUsesFrozenFieldOrderAndExplicitNullableFields() throws Exception {
        String canonical = MeetingManifestJson.encode(MeetingManifestJson.decode(
                fixture("meeting-manifest.asr-only.json")));

        assertOrdered(canonical,
                "\"specVersion\"", "\"meetingId\"", "\"manifestRevision\"",
                "\"profile\"", "\"title\"", "\"status\"", "\"startedAt\"",
                "\"endedAt\"", "\"durationMs\"", "\"languages\"",
                "\"artifacts\"", "\"warnings\"");
        assertTrue(canonical.contains(
                "\"profile\":{\"id\":\"transcript-only/v1\",\"revision\":1}"));
        assertTrue(canonical.contains(
                "\"languages\":{\"source\":\"zh-CN\",\"targets\":[]}"));
        assertTrue(canonical.contains(
                "\"artifacts\":[{\"artifactId\":\"art_fixture_asr\","
                        + "\"type\":\"transcript.final\","
                        + "\"mediaType\":\"application/json\","
                        + "\"language\":\"zh-CN\",\"revision\":1,\"size\":64,"
                        + "\"checksum\":\"sha256:cccccccccccccccccccccccccccccccc"
                        + "cccccccccccccccccccccccccccccccc\",\"status\":\"READY\","
                        + "\"createdAt\":\"2026-09-15T02:01:01Z\","
                        + "\"supersedesArtifactId\":null,\"failure\":null}]"));
        assertTrue(canonical.contains(
                "\"code\":\"CAPABILITY_UNAVAILABLE\","
                        + "\"capability\":\"summary.structured\","
                        + "\"messageKey\":\"capability.summary.unavailable\","
                        + "\"diagnosticId\":null"));
        assertTrue(canonical.contains("\"title\":null"));
        assertTrue(canonical.contains("\"supersedesArtifactId\":null"));
        assertTrue(canonical.contains("\"failure\":null"));
    }

    @Test
    void missingAndNullOptionalStringsCanonicalizeToExplicitNull() throws Exception {
        String ready = fixture("meeting-manifest.ready.json");
        String missingLanguage = ready.replace("      \"language\": \"zh-CN\",\n", "");
        String nullLanguage = ready.replace(
                "\"language\": \"zh-CN\"", "\"language\": null");

        assertEquals(2, occurrences(
                MeetingManifestJson.encode(MeetingManifestJson.decode(missingLanguage)),
                "\"language\":null"));
        assertEquals(2, occurrences(
                MeetingManifestJson.encode(MeetingManifestJson.decode(nullLanguage)),
                "\"language\":null"));

        String missingDiagnostic = MeetingManifestJson.encode(MeetingManifestJson.decode(
                fixture("meeting-manifest.failed.json")));
        String nullDiagnostic = MeetingManifestJson.encode(MeetingManifestJson.decode(
                fixture("meeting-manifest.asr-only.json")));
        assertTrue(missingDiagnostic.contains("\"diagnosticId\":null"));
        assertTrue(nullDiagnostic.contains("\"diagnosticId\":null"));
    }

    @Test
    void externalDecodeFailuresAreFixedRedactedAndCauseFree() throws Exception {
        String ready = fixture("meeting-manifest.ready.json");
        String sentinel = "SENSITIVE_SENTINEL_9f72";

        assertRedactedFailure(ready.replace(
                "\"status\": \"READY\"", "\"status\": \"" + sentinel + "\""),
                sentinel);
        assertRedactedFailure(ready.replace(
                "2026-09-15T01:00:00Z", sentinel), sentinel);
        assertRedactedFailure("{\"specVersion\":\"" + sentinel + "\",", sentinel);
    }

    @Test
    void encodingFailureIsFixedAndCauseFree() {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> MeetingManifestJson.encode(null));
        assertEquals("Manifest JSON 编码失败", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals("java.lang.IllegalStateException: Manifest JSON 编码失败",
                failure.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // 顶层与嵌套重复键都必须由 streaming parser 拒绝。
            "{\"specVersion\":\"0.1\",\"specVersion\":\"0.2\"}",
            "{\"profile\":{\"id\":\"a\",\"id\":\"b\"}}",
            // 单个 JSON 值之后存在任何第二个 token 都不是合法文档。
            "{} {}",
            // 根必须是对象，空文档、数组和 JSON null 不能被视为缺字段 Manifest。
            "",
            "[]",
            "null"
    })
    void malformedDuplicateTrailingAndWrongRootFailClosed(String json) {
        assertThrows(IllegalArgumentException.class, () -> MeetingManifestJson.decode(json));
    }

    @Test
    void nestedRequiredUnknownNullAndWrongTypesFailClosed() throws Exception {
        String ready = fixture("meeting-manifest.ready.json");

        assertInvalid(ready.replace("\"id\": \"meeting-minutes/v1\", ", ""));
        assertInvalid(ready.replace(
                ",\n      \"createdAt\": \"2026-09-15T01:05:01Z\"", ""));
        assertInvalid(ready.replace(
                "\"targets\": [\"en-US\"]",
                "\"targets\": [\"en-US\"], \"provider\": \"secret\""));
        assertInvalid(ready.replace("\"durationMs\": 300000", "\"durationMs\": null"));
        assertInvalid(ready.replace("\"revision\": 3", "\"revision\": true"));
        assertInvalid(ready.replace("\"artifacts\": [", "\"artifacts\": {"));
    }

    @Test
    void numericCoercionFractionAndOverflowFailClosed() throws Exception {
        String ready = fixture("meeting-manifest.ready.json");

        assertInvalid(ready.replace("\"manifestRevision\": 1",
                "\"manifestRevision\": 1.0"));
        assertInvalid(ready.replace("\"durationMs\": 300000",
                "\"durationMs\": 300000.5"));
        assertInvalid(ready.replace("\"manifestRevision\": 1",
                "\"manifestRevision\": 2147483648"));
        assertInvalid(ready.replace("\"durationMs\": 300000",
                "\"durationMs\": 9223372036854775808"));
        assertInvalid(ready.replace("\"size\": 128", "\"size\": \"128\""));
    }

    @Test
    void invalidTimeEnumClosedStringsPatternsAndWhitespaceFailClosed() throws Exception {
        String ready = fixture("meeting-manifest.ready.json");

        assertInvalid(ready.replace("2026-09-15T01:00:00Z", "not-an-instant"));
        assertInvalid(ready.replace("\"status\": \"READY\"", "\"status\": \"DONE\""));
        assertInvalid(ready.replace("\"type\": \"transcript.final\"",
                "\"type\": \"provider.private\""));
        assertInvalid(ready.replace("mtg_fixture_ready", "meeting_fixture_ready"));
        assertInvalid(ready.replace("art_fixture_transcript", "artifact_fixture"));
        assertInvalid(ready.replace(
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "md5:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        assertInvalid(ready.replace("\"source\": \"zh-CN\"", "\"source\": \" \""));
        assertInvalid(ready.replace("\"mediaType\": \"application/json\"",
                "\"mediaType\": \"   \""));
    }

    @Test
    void ecmaScriptNbspAndBomWhitespaceMatchSchemaNonWhitespaceSemantics() throws Exception {
        String ready = fixture("meeting-manifest.ready.json");

        assertInvalid(ready.replace("meeting-minutes/v1", "\u00a0\ufeff"));
        assertInvalid(ready.replace("meeting-minutes/v1", "\ufeff"));
        assertInvalid(fixture("meeting-manifest.asr-only.json").replace(
                "\"diagnosticId\": null", "\"diagnosticId\": \"\u00a0\ufeff\""));

        assertEquals("\u00a0x", MeetingManifestJson.decode(
                ready.replace("meeting-minutes/v1", "\u00a0x")).profile().id());
        assertEquals("\ufeffx", MeetingManifestJson.decode(
                ready.replace("meeting-minutes/v1", "\ufeffx")).profile().id());
    }

    @Test
    void strictRfc3339OffsetsCanonicalizeToZAndInvalidSubsetsFailClosed()
            throws Exception {
        String ready = fixture("meeting-manifest.ready.json");
        MeetingManifest baseline = MeetingManifestJson.decode(ready);
        String offset = ready
                .replace("2026-09-15T01:00:00Z", "2026-09-15T09:00:00+08:00")
                .replace("2026-09-15T01:05:00Z", "2026-09-15T09:05:00+08:00")
                .replace("2026-09-15T01:05:01Z", "2026-09-15T09:05:01+08:00")
                .replace("2026-09-15T01:05:02Z", "2026-09-15T09:05:02+08:00");

        MeetingManifest decodedOffset = MeetingManifestJson.decode(offset);
        assertEquals(baseline, decodedOffset);
        String canonical = MeetingManifestJson.encode(decodedOffset);
        assertFalse(canonical.contains("+08:00"));
        assertTrue(canonical.contains("2026-09-15T01:00:00Z"));

        String nanos = ready.replace(
                "2026-09-15T01:00:00Z", "2026-09-15T09:00:00.123456789+08:00");
        assertTrue(MeetingManifestJson.encode(MeetingManifestJson.decode(nanos))
                .contains("2026-09-15T01:00:00.123456789Z"));

        assertTrue(MeetingManifestJson.encode(MeetingManifestJson.decode(ready.replace(
                        "2026-09-15T01:05:01Z", "2026-09-15T01:05:01+18:00")))
                .contains("2026-09-14T07:05:01Z"));
        assertTrue(MeetingManifestJson.encode(MeetingManifestJson.decode(ready.replace(
                        "2026-09-15T01:05:01Z", "2026-09-15T01:05:01-18:00")))
                .contains("2026-09-15T19:05:01Z"));

        MeetingManifest minimumYear = MeetingManifestJson.decode(ready.replace(
                "2026-09-15T01:00:00Z", "0000-01-01T00:00:00Z"));
        MeetingManifest maximumYear = MeetingManifestJson.decode(ready.replace(
                "2026-09-15T01:05:00Z", "9999-12-31T23:59:59.999999999Z"));
        MeetingManifest lowerMiddleOffset = MeetingManifestJson.decode(ready.replace(
                "2026-09-15T01:00:00Z", "0001-01-01T00:00:00+18:00"));
        MeetingManifest upperMiddleOffset = MeetingManifestJson.decode(ready.replace(
                "2026-09-15T01:05:00Z", "9998-12-31T23:59:59-18:00"));
        for (MeetingManifest encodable : List.of(
                baseline,
                minimumYear,
                maximumYear,
                decodedOffset,
                lowerMiddleOffset,
                upperMiddleOffset)) {
            assertEquals(encodable,
                    MeetingManifestJson.decode(MeetingManifestJson.encode(encodable)));
        }

        assertInvalid(ready.replace(
                "2026-09-15T01:00:00Z", "2026-09-15T24:00:00Z"));
        assertInvalid(ready.replace(
                "2026-09-15T01:00:00Z", "2026-09-15T01:00:00.1234567890Z"));
        assertInvalid(ready.replace(
                "2026-09-15T01:00:00Z", "2026-09-15T01:00:00+18:01"));
        assertInvalid(ready.replace(
                "2026-09-15T01:00:00Z", "2026-09-15T01:00:00-18:01"));
        assertInvalid(ready.replace(
                "2026-09-15T01:00:00Z", "2026-02-30T01:00:00Z"));
        assertInvalid(ready.replace(
                "2026-09-15T01:00:00Z", "0000-01-01T00:00:00+00:00"));
        assertInvalid(ready.replace(
                "2026-09-15T01:05:00Z", "9999-12-31T23:59:59-00:00"));
        assertInvalid(ready.replace(
                "2026-09-15T01:00:00Z", "0000-01-01T00:00:00+18:00"));
        assertInvalid(ready.replace(
                "2026-09-15T01:05:00Z", "9999-12-31T23:59:59-18:00"));
    }

    @Test
    void titleLimitCountsUnicodeCodePointsInsteadOfUtf16CodeUnits() throws Exception {
        String ready = fixture("meeting-manifest.ready.json");
        String title256 = "😀".repeat(256);
        String title257 = title256 + "😀";

        MeetingManifest accepted = MeetingManifestJson.decode(
                ready.replace("Fixture meeting", title256));
        assertEquals(256, accepted.title().codePointCount(0, accepted.title().length()));
        assertInvalid(ready.replace("Fixture meeting", title257));
    }

    @Test
    void artifactFailureMustMatchFailedStatus() throws Exception {
        String ready = fixture("meeting-manifest.ready.json");
        String failure = "{\"domain\":\"MODEL\",\"code\":\"MODEL_FAILED\","
                + "\"retryable\":false,\"diagnosticId\":\"diag-1\"}";
        String readyWithFailure = ready.replace(
                "\"createdAt\": \"2026-09-15T01:05:01Z\"",
                "\"createdAt\": \"2026-09-15T01:05:01Z\", \"failure\": " + failure);
        assertInvalid(readyWithFailure);

        String failedWithoutFailure = ready.replace(
                "\"status\": \"READY\",\n      \"createdAt\": \"2026-09-15T01:05:01Z\"",
                "\"status\": \"FAILED\",\n      \"createdAt\": \"2026-09-15T01:05:01Z\"");
        assertInvalid(failedWithoutFailure);

        String failed = failedWithoutFailure.replace(
                "\"createdAt\": \"2026-09-15T01:05:01Z\"",
                "\"createdAt\": \"2026-09-15T01:05:01Z\", \"failure\": " + failure);
        // 第二个 READY Artifact 保持非失败且无 failure；只有第一个失败产物携带详情。
        MeetingManifest failedManifest = MeetingManifestJson.decode(failed);
        assertEquals(MeetingManifest.ArtifactStatus.FAILED,
                failedManifest.artifacts().getFirst().status());
        assertTrue(MeetingManifestJson.encode(failedManifest).contains(
                "\"failure\":{\"domain\":\"MODEL\",\"code\":\"MODEL_FAILED\","
                        + "\"retryable\":false,\"diagnosticId\":\"diag-1\"}"));
    }

    @Test
    void failureDomainAndNonBlankPublicDiagnosticsAreClosedSets() throws Exception {
        String ready = fixture("meeting-manifest.ready.json");
        String failedArtifact = ready.replace(
                "\"status\": \"READY\",\n      \"createdAt\": \"2026-09-15T01:05:01Z\"",
                "\"status\": \"FAILED\", \"failure\": {"
                        + "\"domain\":\"PROVIDER\",\"code\":\"X\","
                        + "\"retryable\":false,\"diagnosticId\":\"diag\"},"
                        + "\n      \"createdAt\": \"2026-09-15T01:05:01Z\"");
        assertInvalid(failedArtifact);

        String blankFailureCode = failedArtifact
                .replace("\"domain\":\"PROVIDER\"", "\"domain\":\"MODEL\"")
                .replace("\"code\":\"X\"", "\"code\":\"   \"");
        assertInvalid(blankFailureCode);

        String asr = fixture("meeting-manifest.asr-only.json");
        assertInvalid(asr.replace("\"code\": \"CAPABILITY_UNAVAILABLE\"",
                "\"code\": \"   \""));
    }

    private static void assertInvalid(String json) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> MeetingManifestJson.decode(json));
        assertEquals("Manifest JSON 格式非法", failure.getMessage());
        assertNull(failure.getCause());
    }

    private static void assertRedactedFailure(String json, String sentinel) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> MeetingManifestJson.decode(json));
        assertEquals("Manifest JSON 格式非法", failure.getMessage());
        assertNull(failure.getCause());
        assertThrowableTreeDoesNotContain(failure, sentinel);
    }

    private static void assertThrowableTreeDoesNotContain(Throwable failure, String sentinel) {
        assertFalse(String.valueOf(failure.getMessage()).contains(sentinel));
        assertFalse(failure.toString().contains(sentinel));
        if (failure.getCause() != null) {
            assertThrowableTreeDoesNotContain(failure.getCause(), sentinel);
        }
        for (Throwable suppressed : failure.getSuppressed()) {
            assertThrowableTreeDoesNotContain(suppressed, sentinel);
        }
    }

    private static int occurrences(String source, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }

    private static void assertOrdered(String json, String... fields) {
        int previous = -1;
        for (String field : fields) {
            int current = json.indexOf(field, previous + 1);
            assertTrue(current > previous, "field order mismatch for " + field);
            previous = current;
        }
    }

    private static String fixture(String filename) throws IOException {
        for (Path candidate : List.of(
                Path.of("contracts", "fixtures", filename),
                Path.of("..", "contracts", "fixtures", filename))) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException(filename + " not found");
    }
}

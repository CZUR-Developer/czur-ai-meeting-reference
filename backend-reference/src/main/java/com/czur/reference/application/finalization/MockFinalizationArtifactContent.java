package com.czur.reference.application.finalization;

import com.czur.reference.domain.meeting.MeetingIdPolicy;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 确定性 Mock Artifact 的关闭式 canonical JSON 值。
 *
 * <p>除 transcript.final 中固定的模拟演示文本外，内容只保存能够由 checkpoint
 * 根摘要和 Artifact spec 独立交叉核对的字段；不保存模型输出、真实会议正文或
 * 不可验证的 Profile/Plan 派生值。模拟文本不是音频识别结果，解码时要求与固定
 * 文本完全一致，避免仅重新计算 size/checksum 就能注入任意正文。解码器为纯 Java
 * 固定形状解析器，不依赖 Jackson；字段顺序、转义、数字表示、尾随换行和 UTF-8
 * 字节必须与本类重新编码结果逐字节相同。</p>
 */
public final class MockFinalizationArtifactContent {
    public static final String SCHEMA_VERSION = "mock-finalization/v1";
    public static final String MEDIA_TYPE = "application/json";
    public static final int REVISION = 1;
    public static final Set<String> ARTIFACT_TYPES = Set.of(
            "transcript.final", "summary.structured");

    private static final String INVALID_CONTENT =
            "Mock finalization artifact content is invalid";
    private static final Pattern FINGERPRINT = Pattern.compile("^[0-9a-f]{64}$");
    private static final String MOCK_TRANSCRIPT_TEXT =
            "【模拟最终转写】大家好，会议现在开始。这段文字仅供演示，并非实际音频识别结果。";

    private final String artifactType;
    private final String executionFingerprint;
    private final String language;
    private final int revision;
    private final String sourceFingerprint;

    public MockFinalizationArtifactContent(
            String artifactType,
            String executionFingerprint,
            String language,
            int revision,
            String sourceFingerprint) {
        try {
            if (!ARTIFACT_TYPES.contains(artifactType)
                    || executionFingerprint == null
                    || !FINGERPRINT.matcher(executionFingerprint).matches()
                    || revision != REVISION
                    || sourceFingerprint == null
                    || !FINGERPRINT.matcher(sourceFingerprint).matches()) {
                throw invalid();
            }
            FinalizationOperationIdentity.requireText(language);
            this.artifactType = artifactType;
            this.executionFingerprint = executionFingerprint;
            this.language = language;
            this.revision = revision;
            this.sourceFingerprint = sourceFingerprint;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    /** 严格解码并要求输入本身就是唯一 canonical UTF-8 表示。 */
    public static MockFinalizationArtifactContent decodeCanonical(byte[] canonicalJson) {
        try {
            Objects.requireNonNull(canonicalJson, "canonicalJson");
            if (canonicalJson.length == 0
                    || canonicalJson.length > FinalizationCheckpoint.MAX_ARTIFACT_CONTENT_BYTES) {
                throw invalid();
            }
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(canonicalJson))
                    .toString();
            Parser parser = new Parser(json);
            MockFinalizationArtifactContent decoded = parser.parse();
            if (!Arrays.equals(canonicalJson, decoded.canonicalBytes())) {
                throw invalid();
            }
            return decoded;
        } catch (CharacterCodingException | RuntimeException failure) {
            throw invalid();
        }
    }

    /** 字段顺序、转义和尾随换行均固定的 canonical JSON。 */
    public byte[] canonicalBytes() {
        String json = "{\"artifactType\":" + quote(artifactType)
                + ",\"executionFingerprint\":" + quote(executionFingerprint)
                + ",\"language\":" + quote(language)
                + ",\"mock\":true"
                + ",\"revision\":" + revision
                + ",\"schemaVersion\":" + quote(SCHEMA_VERSION)
                + ",\"sourceFingerprint\":" + quote(sourceFingerprint)
                + ("transcript.final".equals(artifactType)
                        ? ",\"text\":" + quote(MOCK_TRANSCRIPT_TEXT) : "")
                + "}\n";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    public String artifactType() {
        return artifactType;
    }

    public String executionFingerprint() {
        return executionFingerprint;
    }

    public String language() {
        return language;
    }

    public int revision() {
        return revision;
    }

    public String sourceFingerprint() {
        return sourceFingerprint;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MockFinalizationArtifactContent that)) {
            return false;
        }
        return revision == that.revision
                && artifactType.equals(that.artifactType)
                && executionFingerprint.equals(that.executionFingerprint)
                && language.equals(that.language)
                && sourceFingerprint.equals(that.sourceFingerprint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                artifactType, executionFingerprint, language, revision, sourceFingerprint);
    }

    @Override
    public String toString() {
        return "MockFinalizationArtifactContent[artifactType=redacted"
                + ", executionFingerprint=redacted, language=redacted"
                + ", revision=" + revision + ", sourceFingerprint=redacted]";
    }

    private static String quote(String value) {
        MeetingIdPolicy.utf8Length(value);
        StringBuilder output = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20) {
                        output.append("\\u00")
                                .append(Character.forDigit((character >>> 4) & 0xf, 16))
                                .append(Character.forDigit(character & 0xf, 16));
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        return output.append('"').toString();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(INVALID_CONTENT);
    }

    /** 只解析本协议固定字段顺序；重新编码比较负责唯一 canonical 表示。 */
    private static final class Parser {
        private final String input;
        private int offset;

        private Parser(String input) {
            this.input = input;
        }

        private MockFinalizationArtifactContent parse() {
            expect("{\"artifactType\":");
            String artifactType = string();
            expect(",\"executionFingerprint\":");
            String executionFingerprint = string();
            expect(",\"language\":");
            String language = string();
            expect(",\"mock\":true,\"revision\":");
            int revision = positiveInt();
            expect(",\"schemaVersion\":");
            String schemaVersion = string();
            expect(",\"sourceFingerprint\":");
            String sourceFingerprint = string();
            if ("transcript.final".equals(artifactType)) {
                expect(",\"text\":");
                if (!MOCK_TRANSCRIPT_TEXT.equals(string())) {
                    throw invalid();
                }
            }
            expect("}\n");
            if (offset != input.length() || !SCHEMA_VERSION.equals(schemaVersion)) {
                throw invalid();
            }
            return new MockFinalizationArtifactContent(
                    artifactType, executionFingerprint, language, revision, sourceFingerprint);
        }

        private String string() {
            if (offset >= input.length() || input.charAt(offset++) != '"') {
                throw invalid();
            }
            StringBuilder value = new StringBuilder();
            while (offset < input.length()) {
                char character = input.charAt(offset++);
                if (character == '"') {
                    return value.toString();
                }
                if (character == '\\') {
                    escaped(value);
                } else if (character < 0x20 || Character.isLowSurrogate(character)) {
                    throw invalid();
                } else if (Character.isHighSurrogate(character)) {
                    if (offset >= input.length()
                            || !Character.isLowSurrogate(input.charAt(offset))) {
                        throw invalid();
                    }
                    value.append(character).append(input.charAt(offset++));
                } else {
                    value.append(character);
                }
            }
            throw invalid();
        }

        private void escaped(StringBuilder value) {
            if (offset >= input.length()) {
                throw invalid();
            }
            switch (input.charAt(offset++)) {
                case '"' -> value.append('"');
                case '\\' -> value.append('\\');
                case '/' -> value.append('/');
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> unicode(value);
                default -> throw invalid();
            }
        }

        private void unicode(StringBuilder value) {
            char first = hexCharacter();
            if (Character.isLowSurrogate(first)) {
                throw invalid();
            }
            if (!Character.isHighSurrogate(first)) {
                value.append(first);
                return;
            }
            expect("\\u");
            char second = hexCharacter();
            if (!Character.isLowSurrogate(second)) {
                throw invalid();
            }
            value.append(first).append(second);
        }

        private char hexCharacter() {
            if (offset + 4 > input.length()) {
                throw invalid();
            }
            int value = 0;
            for (int index = 0; index < 4; index++) {
                int digit = Character.digit(input.charAt(offset++), 16);
                if (digit < 0) {
                    throw invalid();
                }
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private int positiveInt() {
            int start = offset;
            if (offset >= input.length()
                    || input.charAt(offset) < '1' || input.charAt(offset) > '9') {
                throw invalid();
            }
            while (offset < input.length()
                    && input.charAt(offset) >= '0' && input.charAt(offset) <= '9') {
                offset++;
            }
            try {
                return Integer.parseInt(input.substring(start, offset));
            } catch (NumberFormatException failure) {
                throw invalid();
            }
        }

        private void expect(String expected) {
            if (!input.startsWith(expected, offset)) {
                throw invalid();
            }
            offset += expected.length();
        }
    }
}

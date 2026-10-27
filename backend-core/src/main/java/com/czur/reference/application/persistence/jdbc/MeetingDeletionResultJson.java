package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.FinalizationFact;
import com.czur.reference.application.port.InputState;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingStatus;
import com.czur.reference.domain.meeting.MeetingState;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 首次删除受理事实的严格、稳定 JSON 编解码器。
 *
 * <p>该正文将由 T13-04-06 在删除事务中保存到幂等记录。它记录的是第一次受理
 * 事务准备提交的 {@code DELETION_PENDING} 快照，不是当前会议查询结果；事务成功
 * 后这份正文成为不可改写的首次事实。清理 Worker 后来把会议推进为
 * {@code DELETED} 时不得覆盖它。重复命令解码这份事实后，再由删除端口包装为
 * {@code DeleteResult.Replayed}。</p>
 *
 * <p>编码字段及顺序固定为
 * {@code schemaVersion/operation/outcome/meetingId/state/inputState/
 * finalizationReceipt/manifestState/updatedAt}。解码允许数据库 JSON 重排
 * 字段，但拒绝未知、缺失、重复字段、尾逗号、错误类型、未知枚举、非法时间和
 * 尾随内容。对外异常使用固定文本，不携带原始 JSON、receipt 或攻击者字段名。</p>
 *
 * <p>本类保持 package-private：它是 JDBC 幂等持久化格式，不是公开 REST 协议，
 * 也不应被领域层或插件直接依赖。</p>
 */
final class MeetingDeletionResultJson {
    private static final int SCHEMA_VERSION = 1;
    private static final String OPERATION = "MEETING_DELETE";
    private static final String OUTCOME = "ACCEPTED";
    private static final String MALFORMED = "会议删除幂等结果格式非法";
    private static final int FIELD_COUNT = 9;

    private MeetingDeletionResultJson() {
    }

    /**
     * 编码首次受理后、尚未由清理 Worker 推进的稳定会议状态。
     *
     * @param meeting 首次事务准备持久化的权威删除待处理快照
     * @return 字段顺序固定的 canonical JSON 文本
     */
    static String encode(MeetingStatus meeting) {
        Objects.requireNonNull(meeting, "meeting");
        requireAcceptedState(meeting);
        return "{\"schemaVersion\":" + SCHEMA_VERSION
                + ",\"operation\":" + quote(OPERATION)
                + ",\"outcome\":" + quote(OUTCOME)
                + ",\"meetingId\":" + quote(meeting.meetingId())
                + ",\"state\":" + quote(meeting.state().name())
                + ",\"inputState\":" + quote(meeting.inputState().name())
                + ",\"finalizationReceipt\":" + quoteNullable(
                        meeting.finalizationReceipt())
                + ",\"manifestState\":" + quote(meeting.manifestState().name())
                + ",\"updatedAt\":" + quote(meeting.updatedAt().toString())
                + "}";
    }

    /**
     * 解码首次受理事实。
     *
     * <p>任何格式或不变量错误都折叠为固定异常，不保留原异常 cause，避免数据库
     * 中被篡改的 JSON、receipt 或字段名进入日志链。</p>
     */
    static MeetingStatus decode(String json) {
        try {
            return decodeStrict(json);
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException(MALFORMED);
        }
    }

    private static MeetingStatus decodeStrict(String json) {
        Reader reader = new Reader(json);
        Set<String> seen = new HashSet<>();
        Integer schemaVersion = null;
        String operation = null;
        String outcome = null;
        String meetingId = null;
        String state = null;
        String inputState = null;
        String finalizationReceipt = null;
        String manifestState = null;
        String updatedAt = null;

        reader.expect('{');
        while (reader.peek() != '}') {
            String field = reader.readString();
            if (!seen.add(field)) {
                throw reader.error();
            }
            reader.expect(':');
            switch (field) {
                case "schemaVersion" -> schemaVersion = reader.readInt();
                case "operation" -> operation = reader.readString();
                case "outcome" -> outcome = reader.readString();
                case "meetingId" -> meetingId = reader.readString();
                case "state" -> state = reader.readString();
                case "inputState" -> inputState = reader.readString();
                case "finalizationReceipt" ->
                        finalizationReceipt = reader.readNullableString();
                case "manifestState" -> manifestState = reader.readString();
                case "updatedAt" -> updatedAt = reader.readString();
                default -> throw reader.error();
            }
            if (reader.peek() == ',') {
                reader.expect(',');
                if (reader.peek() == '}') {
                    throw reader.error();
                }
            } else {
                break;
            }
        }
        reader.expect('}');
        reader.ensureEnd();
        if (seen.size() != FIELD_COUNT
                || !Integer.valueOf(SCHEMA_VERSION).equals(schemaVersion)
                || !OPERATION.equals(operation)
                || !OUTCOME.equals(outcome)
                || meetingId == null
                || state == null
                || inputState == null
                || manifestState == null
                || updatedAt == null) {
            throw reader.error();
        }

        FinalizationFact finalization = new FinalizationFact(
                InputState.valueOf(inputState), finalizationReceipt);
        MeetingStatus meeting = new MeetingStatus(
                meetingId,
                MeetingState.valueOf(state),
                finalization,
                ManifestStatus.valueOf(manifestState),
                Instant.parse(updatedAt));
        requireAcceptedState(meeting);
        return meeting;
    }

    private static void requireAcceptedState(MeetingStatus meeting) {
        if (meeting.state() != MeetingState.DELETION_PENDING
                || meeting.manifestState() != ManifestStatus.DELETION_PENDING) {
            throw new IllegalArgumentException(
                    "deletion result requires matching DELETION_PENDING states");
        }
    }

    private static String quoteNullable(String value) {
        return value == null ? "null" : quote(value);
    }

    private static String quote(String value) {
        Objects.requireNonNull(value, "value");
        requireValidUnicode(value);
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (current < 0x20) {
                        result.append(String.format("\\u%04x", (int) current));
                    } else {
                        result.append(current);
                    }
                }
            }
        }
        return result.append('"').toString();
    }

    private static void requireValidUnicode(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(++index))) {
                    throw new IllegalArgumentException("JSON string contains invalid Unicode");
                }
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException("JSON string contains invalid Unicode");
            }
        }
    }

    /** 仅实现固定删除结果对象所需的严格 JSON 子集。 */
    private static final class Reader {
        private final String input;
        private int index;

        private Reader(String input) {
            this.input = Objects.requireNonNull(input, "json");
        }

        private char peek() {
            skipWhitespace();
            if (index >= input.length()) {
                throw error();
            }
            return input.charAt(index);
        }

        private String readString() {
            skipWhitespace();
            if (index >= input.length() || input.charAt(index++) != '"') {
                throw error();
            }
            StringBuilder value = new StringBuilder();
            while (index < input.length()) {
                char current = input.charAt(index++);
                if (current == '"') {
                    String decoded = value.toString();
                    requireValidUnicode(decoded);
                    return decoded;
                }
                if (current == '\\') {
                    if (index >= input.length()) {
                        throw error();
                    }
                    char escaped = input.charAt(index++);
                    switch (escaped) {
                        case '"', '\\', '/' -> value.append(escaped);
                        case 'b' -> value.append('\b');
                        case 'f' -> value.append('\f');
                        case 'n' -> value.append('\n');
                        case 'r' -> value.append('\r');
                        case 't' -> value.append('\t');
                        case 'u' -> value.append(readUnicodeEscape());
                        default -> throw error();
                    }
                } else if (current < 0x20) {
                    throw error();
                } else {
                    value.append(current);
                }
            }
            throw error();
        }

        private char readUnicodeEscape() {
            if (index + 4 > input.length()) {
                throw error();
            }
            int value = 0;
            for (int offset = 0; offset < 4; offset++) {
                int digit = asciiHexDigit(input.charAt(index++));
                if (digit < 0) {
                    throw error();
                }
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private static int asciiHexDigit(char value) {
            if (value >= '0' && value <= '9') {
                return value - '0';
            }
            if (value >= 'a' && value <= 'f') {
                return value - 'a' + 10;
            }
            if (value >= 'A' && value <= 'F') {
                return value - 'A' + 10;
            }
            return -1;
        }

        private String readNullableString() {
            skipWhitespace();
            if (input.startsWith("null", index)) {
                index += 4;
                return null;
            }
            return readString();
        }

        private int readInt() {
            skipWhitespace();
            if (index >= input.length()) {
                throw error();
            }
            int start = index;
            char first = input.charAt(index);
            if (first == '0') {
                index++;
                if (index < input.length() && isAsciiDigit(input.charAt(index))) {
                    throw error();
                }
                return 0;
            }
            if (first < '1' || first > '9') {
                throw error();
            }
            index++;
            while (index < input.length() && isAsciiDigit(input.charAt(index))) {
                index++;
            }
            try {
                return Integer.parseInt(input.substring(start, index));
            } catch (NumberFormatException outOfRange) {
                throw error();
            }
        }

        private static boolean isAsciiDigit(char value) {
            return value >= '0' && value <= '9';
        }

        private void expect(char expected) {
            skipWhitespace();
            if (index >= input.length() || input.charAt(index++) != expected) {
                throw error();
            }
        }

        private void ensureEnd() {
            skipWhitespace();
            if (index != input.length()) {
                throw error();
            }
        }

        private void skipWhitespace() {
            while (index < input.length()) {
                char current = input.charAt(index);
                if (current != ' ' && current != '\t' && current != '\r' && current != '\n') {
                    return;
                }
                index++;
            }
        }

        private IllegalArgumentException error() {
            return new IllegalArgumentException(MALFORMED);
        }
    }
}

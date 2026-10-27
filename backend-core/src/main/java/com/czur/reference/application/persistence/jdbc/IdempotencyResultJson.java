package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingCommandResult;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingState;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 幂等响应的最小 JSON 编解码器。
 *
 * <p>参考仓保持 JDK-only，不引入 JSON 框架；因此这里仅处理
 * {@link MeetingCommandResult} 的固定字段，并对字符串执行完整 JSON 转义。
 * encoder 始终写 {@code schemaVersion=2} 和完整 ownership 字段；decoder 仍可
 * 严格读取历史无版本格式。历史结果只能恢复为 {@code LEGACY_UNOWNED}，不能
 * 用当前主体、Grant 或 Reservation 补齐。未知字段会被拒绝，避免把不兼容的
 * 数据静默当成成功重放。</p>
 */
final class IdempotencyResultJson {
    private static final int CURRENT_SCHEMA_VERSION = 2;

    private IdempotencyResultJson() {
    }

    static String encode(MeetingCommandResult result) {
        Objects.requireNonNull(result, "result");
        MeetingAggregate.MeetingSnapshot meeting = result.meeting();
        return "{\"schemaVersion\":" + CURRENT_SCHEMA_VERSION
                + ",\"operation\":" + quote(result.operation().name())
                + ",\"meeting\":{"
                + "\"meetingId\":" + quote(meeting.meetingId())
                + ",\"deploymentScope\":" + quote(meeting.deploymentScope())
                + ",\"clientSessionId\":" + quote(meeting.clientSessionId())
                + ",\"subjectScope\":" + quote(meeting.subjectScope())
                + ",\"grantId\":" + quoteNullable(meeting.grantId())
                + ",\"grantExpiresAt\":"
                + quoteNullable(meeting.grantExpiresAt() == null
                        ? null : meeting.grantExpiresAt().toString())
                + ",\"usageReservationId\":" + quoteNullable(meeting.usageReservationId())
                + ",\"profileId\":" + quote(meeting.profileId())
                + ",\"profileRevision\":" + meeting.profileRevision()
                + ",\"state\":" + quote(meeting.state().name())
                + ",\"createdAt\":" + quote(meeting.createdAt().toString())
                + ",\"updatedAt\":" + quote(meeting.updatedAt().toString())
                + "}}";
    }

    static MeetingCommandResult decode(String json) {
        try {
            return decodeStrict(json);
        } catch (RuntimeException malformed) {
            // JSON 正文可能来自历史版本或被外部工具破坏。这里刻意不保留 cause：
            // Enum、Instant、未知字段名和原始 JSON 都可能包含主体或攻击者文本。
            throw new IllegalArgumentException("幂等响应格式非法");
        }
    }

    private static MeetingCommandResult decodeStrict(String json) {
        Reader reader = new Reader(json);
        reader.expect('{');
        int schemaVersion = -1;
        MeetingCommandResult.Operation operation = null;
        Map<String, String> values = null;
        int profileRevision = -1;
        while (reader.peek() != '}') {
            String key = reader.readString();
            reader.expect(':');
            if ("schemaVersion".equals(key)) {
                if (schemaVersion >= 0) {
                    throw reader.error("重复 schemaVersion 字段");
                }
                schemaVersion = reader.readInt();
            } else if ("operation".equals(key)) {
                if (operation != null) {
                    throw reader.error("重复 operation 字段");
                }
                operation = MeetingCommandResult.Operation.valueOf(reader.readString());
            } else if ("meeting".equals(key)) {
                if (values != null) {
                    throw reader.error("重复 meeting 字段");
                }
                reader.expect('{');
                values = new LinkedHashMap<>();
                while (reader.peek() != '}') {
                    String meetingField = reader.readString();
                    reader.expect(':');
                    if ("profileRevision".equals(meetingField)) {
                        if (profileRevision >= 0) {
                            throw reader.error("重复 profileRevision 字段");
                        }
                        profileRevision = reader.readInt();
                    } else {
                        if (values.containsKey(meetingField)) {
                            throw reader.error("重复 meeting 字段: " + meetingField);
                        }
                        values.put(meetingField, reader.readNullableString());
                    }
                    if (reader.peek() == ',') {
                        reader.expect(',');
                    } else {
                        break;
                    }
                }
                reader.expect('}');
            } else {
                throw reader.error("未知顶层字段: " + key);
            }
            if (reader.peek() == ',') {
                reader.expect(',');
            } else {
                break;
            }
        }
        reader.expect('}');
        reader.ensureEnd();
        if (operation == null) {
            throw reader.error("缺少 operation 字段");
        }
        if (values == null) {
            throw reader.error("缺少 meeting 字段");
        }
        if (profileRevision <= 0) {
            throw reader.error("缺少有效 profileRevision 字段");
        }
        MeetingAggregate.MeetingSnapshot snapshot;
        if (schemaVersion < 0) {
            requireKeys(values, "meetingId", "deploymentScope", "clientSessionId", "profileId",
                    "state", "createdAt", "updatedAt");
            snapshot = MeetingAggregate.MeetingSnapshot.legacyUnowned(
                    requiredValue(values, "meetingId"),
                    requiredValue(values, "deploymentScope"),
                    requiredValue(values, "clientSessionId"),
                    requiredValue(values, "profileId"),
                    profileRevision,
                    MeetingState.valueOf(requiredValue(values, "state")),
                    Instant.parse(requiredValue(values, "createdAt")),
                    Instant.parse(requiredValue(values, "updatedAt")));
        } else {
            if (schemaVersion != CURRENT_SCHEMA_VERSION) {
                throw reader.error("不支持的 schemaVersion: " + schemaVersion);
            }
            requireKeys(values, "meetingId", "deploymentScope", "clientSessionId",
                    "subjectScope", "grantId", "grantExpiresAt", "usageReservationId",
                    "profileId", "state", "createdAt", "updatedAt");
            snapshot = new MeetingAggregate.MeetingSnapshot(
                    requiredValue(values, "meetingId"),
                    requiredValue(values, "deploymentScope"),
                    requiredValue(values, "clientSessionId"),
                    requiredValue(values, "subjectScope"),
                    values.get("grantId"),
                    optionalInstant(values.get("grantExpiresAt")),
                    values.get("usageReservationId"),
                    requiredValue(values, "profileId"),
                    profileRevision,
                    MeetingState.valueOf(requiredValue(values, "state")),
                    Instant.parse(requiredValue(values, "createdAt")),
                    Instant.parse(requiredValue(values, "updatedAt")));
        }
        return new MeetingCommandResult(operation, snapshot);
    }

    private static Instant optionalInstant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    private static String requiredValue(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("幂等响应字段不能为空: " + key);
        }
        return value;
    }

    private static void requireKeys(Map<String, String> values, String... expected) {
        for (String key : expected) {
            if (!values.containsKey(key)) {
                throw new IllegalArgumentException("幂等响应缺少字段: " + key);
            }
        }
        if (values.size() != expected.length) {
            throw new IllegalArgumentException("幂等响应包含未知字段");
        }
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 0x20) {
                        result.append(String.format("\\u%04x", (int) c));
                    } else {
                        result.append(c);
                    }
                }
            }
        }
        return result.append('"').toString();
    }

    private static String quoteNullable(String value) {
        return value == null ? "null" : quote(value);
    }

    private static final class Reader {
        private final String input;
        private int index;

        private Reader(String input) {
            this.input = Objects.requireNonNull(input, "json");
        }

        private char peek() {
            skipWhitespace();
            if (index >= input.length()) {
                throw error("意外结束");
            }
            return input.charAt(index);
        }

        private String readString() {
            skipWhitespace();
            if (index >= input.length() || input.charAt(index++) != '"') {
                throw error("需要 JSON 字符串");
            }
            StringBuilder value = new StringBuilder();
            while (index < input.length()) {
                char c = input.charAt(index++);
                if (c == '"') {
                    return value.toString();
                }
                if (c == '\\') {
                    if (index >= input.length()) {
                        throw error("字符串转义不完整");
                    }
                    char escaped = input.charAt(index++);
                    switch (escaped) {
                        case '"', '\\', '/' -> value.append(escaped);
                        case 'b' -> value.append('\b');
                        case 'f' -> value.append('\f');
                        case 'n' -> value.append('\n');
                        case 'r' -> value.append('\r');
                        case 't' -> value.append('\t');
                        case 'u' -> {
                            if (index + 4 > input.length()) {
                                throw error("Unicode 转义不完整");
                            }
                            value.append((char) Integer.parseInt(input.substring(index, index + 4), 16));
                            index += 4;
                        }
                        default -> throw error("未知字符串转义");
                    }
                } else {
                    value.append(c);
                }
            }
            throw error("字符串未闭合");
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
            int start = index;
            while (index < input.length() && Character.isDigit(input.charAt(index))) {
                index++;
            }
            if (start == index) {
                throw error("需要整数");
            }
            try {
                return Integer.parseInt(input.substring(start, index));
            } catch (NumberFormatException failure) {
                throw error("整数超出范围");
            }
        }

        private void expect(char expected) {
            skipWhitespace();
            if (index >= input.length() || input.charAt(index++) != expected) {
                throw error("需要字符 " + expected);
            }
        }

        private void ensureEnd() {
            skipWhitespace();
            if (index != input.length()) {
                throw error("JSON 末尾存在多余内容");
            }
        }

        private void skipWhitespace() {
            while (index < input.length() && Character.isWhitespace(input.charAt(index))) {
                index++;
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + "，位置=" + index);
        }
    }
}

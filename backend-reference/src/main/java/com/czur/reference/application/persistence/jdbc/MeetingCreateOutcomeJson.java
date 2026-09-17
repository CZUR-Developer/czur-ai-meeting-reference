package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.MeetingCreateFailure;
import com.czur.reference.application.port.MeetingCreateOutcome;
import com.czur.reference.application.port.OwnedMeetingCreationUnitOfWork.ErrorCode;

import java.util.Objects;

/**
 * authenticated-create 终态 Outcome 的严格 JSON 编解码器。
 *
 * <p>成功分支直接委托既有 {@link IdempotencyResultJson}，因此新代码写出的成功
 * JSON 与历史 schemaVersion=2 字节完全一致，并继续读取无版本 v1 和 v2。
 * 只有失败分支使用 schemaVersion=3，字段固定为
 * {@code schemaVersion/operation/outcome/errorCode/retryable}，其中 operation 必须
 * 是 {@code MEETING_CREATE}。未知、缺失、重复字段以及非法类型全部 fail closed，
 * 异常不携带原始 JSON 或攻击者输入。</p>
 */
final class MeetingCreateOutcomeJson {
    private static final int FAILURE_SCHEMA_VERSION = 3;
    private static final String FAILURE_OPERATION = "MEETING_CREATE";
    private static final String FAILURE_OUTCOME = "FAILURE";

    private MeetingCreateOutcomeJson() {
    }

    static String encode(MeetingCreateOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome instanceof MeetingCreateOutcome.Success success) {
            // 这里不得增加 wrapper 或重排字段；v2 成功正文必须保持旧编码字节。
            return IdempotencyResultJson.encode(success.result());
        }
        MeetingCreateFailure failure = ((MeetingCreateOutcome.Failure) outcome).failure();
        return "{\"schemaVersion\":" + FAILURE_SCHEMA_VERSION
                + ",\"operation\":\"" + FAILURE_OPERATION + "\""
                + ",\"outcome\":\"" + FAILURE_OUTCOME + "\""
                + ",\"errorCode\":\"" + failure.code().name() + "\""
                + ",\"retryable\":false}";
    }

    static MeetingCreateOutcome decode(String json) {
        Objects.requireNonNull(json, "json");
        try {
            var result = IdempotencyResultJson.decode(json);
            if (com.czur.reference.domain.meeting.MeetingOwnership
                    .LEGACY_UNOWNED_SUBJECT_SCOPE.equals(result.meeting().subjectScope())) {
                return MeetingCreateOutcome.restoreHistoricalSuccess(result);
            }
            return MeetingCreateOutcome.success(result);
        } catch (IllegalArgumentException notHistoricalSuccess) {
            try {
                return decodeFailure(json);
            } catch (RuntimeException malformedFailure) {
                // 两次解析错误都不保留 cause，避免 JSON 内动态正文进入异常链。
                throw new IllegalArgumentException("会议创建 Outcome 格式非法");
            }
        }
    }

    private static MeetingCreateOutcome decodeFailure(String json) {
        Reader reader = new Reader(json);
        reader.expect('{');
        Integer schemaVersion = null;
        String operation = null;
        String outcome = null;
        ErrorCode errorCode = null;
        Boolean retryable = null;
        while (reader.peek() != '}') {
            String key = reader.readString();
            reader.expect(':');
            switch (key) {
                case "schemaVersion" -> {
                    if (schemaVersion != null) {
                        throw reader.error("重复 schemaVersion");
                    }
                    schemaVersion = reader.readInt();
                }
                case "outcome" -> {
                    if (outcome != null) {
                        throw reader.error("重复 outcome");
                    }
                    outcome = reader.readString();
                }
                case "operation" -> {
                    if (operation != null) {
                        throw reader.error("重复 operation");
                    }
                    operation = reader.readString();
                }
                case "errorCode" -> {
                    if (errorCode != null) {
                        throw reader.error("重复 errorCode");
                    }
                    errorCode = ErrorCode.valueOf(reader.readString());
                }
                case "retryable" -> {
                    if (retryable != null) {
                        throw reader.error("重复 retryable");
                    }
                    retryable = reader.readBoolean();
                }
                default -> throw reader.error("未知字段");
            }
            if (reader.peek() == ',') {
                reader.expect(',');
            } else {
                break;
            }
        }
        reader.expect('}');
        reader.ensureEnd();
        if (!Integer.valueOf(FAILURE_SCHEMA_VERSION).equals(schemaVersion)
                || !FAILURE_OPERATION.equals(operation)
                || !FAILURE_OUTCOME.equals(outcome)
                || errorCode == null
                || retryable == null) {
            throw reader.error("失败 Outcome 字段不完整");
        }
        return MeetingCreateOutcome.failure(
                new MeetingCreateFailure(errorCode, retryable));
    }

    /** 只实现固定 v3 失败对象需要的 JSON 子集。 */
    private static final class Reader {
        private final String input;
        private int index;

        private Reader(String input) {
            this.input = input;
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
                throw error("需要字符串");
            }
            StringBuilder value = new StringBuilder();
            while (index < input.length()) {
                char current = input.charAt(index++);
                if (current == '"') {
                    return value.toString();
                }
                if (current == '\\') {
                    if (index >= input.length()) {
                        throw error("转义不完整");
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
                            try {
                                value.append((char) Integer.parseInt(
                                        input.substring(index, index + 4), 16));
                            } catch (NumberFormatException invalidUnicode) {
                                throw error("Unicode 转义非法");
                            }
                            index += 4;
                        }
                        default -> throw error("未知转义");
                    }
                } else if (current < 0x20) {
                    throw error("字符串包含控制字符");
                } else {
                    value.append(current);
                }
            }
            throw error("字符串未闭合");
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
            } catch (NumberFormatException outOfRange) {
                throw error("整数超出范围");
            }
        }

        private boolean readBoolean() {
            skipWhitespace();
            if (input.startsWith("true", index)) {
                index += 4;
                return true;
            }
            if (input.startsWith("false", index)) {
                index += 5;
                return false;
            }
            throw error("需要布尔值");
        }

        private void expect(char expected) {
            skipWhitespace();
            if (index >= input.length() || input.charAt(index++) != expected) {
                throw error("结构非法");
            }
        }

        private void ensureEnd() {
            skipWhitespace();
            if (index != input.length()) {
                throw error("存在尾随内容");
            }
        }

        private void skipWhitespace() {
            while (index < input.length() && Character.isWhitespace(input.charAt(index))) {
                index++;
            }
        }

        private IllegalArgumentException error(String reason) {
            return new IllegalArgumentException(reason);
        }
    }
}

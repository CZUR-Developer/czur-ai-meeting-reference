package com.czur.reference.protocol.json;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Create 事实 codec 使用的极小 JSON 子集。
 *
 * <p>这里故意没有复用旧幂等响应的 reader。Create 事实是独立的持久化 schema，
 * 需要在边界上同时检查 UTF-8、Unicode surrogate、JSON number 语法和字段集合。
 * 解析失败时上层 codec 会丢弃本类的内部错误，避免输入正文、会议标识或令牌进入
 * 异常消息。</p>
 */
final class CreateFactJsonSupport {
    private CreateFactJsonSupport() {
    }

    static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Create fact JSON 格式非法");
    }

    static String decodeUtf8(byte[] bytes) {
        Objects.requireNonNull(bytes, "json");
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException malformedUtf8) {
            throw invalid();
        }
    }

    /** 严格校验 JSON 文本仍可按 UTF-8 表示，拒绝孤立 surrogate。 */
    static String requireUnicode(String value) {
        Objects.requireNonNull(value, "json");
        try {
            StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            return value;
        } catch (CharacterCodingException malformedUnicode) {
            throw invalid();
        }
    }

    /** 按固定转义规则产生 canonical JSON 字符串。 */
    static String quote(String value) {
        requireUnicode(value);
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 0x20) {
                        result.append("\\u");
                        String hex = Integer.toHexString(character);
                        result.append("0000", 0, 4 - hex.length()).append(hex);
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        return result.append('"').toString();
    }

    /** 只允许 JSON RFC 8259 的四种空白，拒绝其它 Unicode 空白。 */
    static final class Reader {
        private final String input;
        private int index;

        Reader(String input) {
            this.input = requireUnicode(input);
        }

        void expect(char expected) {
            skipWhitespace();
            if (index >= input.length() || input.charAt(index++) != expected) {
                throw invalid();
            }
        }

        boolean nextIs(char value) {
            skipWhitespace();
            return index < input.length() && input.charAt(index) == value;
        }

        void consume(char value) {
            if (!nextIs(value)) {
                throw invalid();
            }
            index++;
        }

        void ensureEnd() {
            skipWhitespace();
            if (index != input.length()) {
                throw invalid();
            }
        }

        String readString() {
            skipWhitespace();
            if (index >= input.length() || input.charAt(index++) != '"') {
                throw invalid();
            }
            StringBuilder value = new StringBuilder();
            while (index < input.length()) {
                char character = input.charAt(index++);
                if (character == '"') {
                    return requireUnicode(value.toString());
                }
                if (character < 0x20) {
                    throw invalid();
                }
                if (character != '\\') {
                    value.append(character);
                    continue;
                }
                if (index >= input.length()) {
                    throw invalid();
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
                    default -> throw invalid();
                }
            }
            throw invalid();
        }

        String readNullableString() {
            if (nextIsLiteral("null")) {
                index += 4;
                return null;
            }
            return readString();
        }

        boolean readBoolean() {
            if (nextIsLiteral("true")) {
                index += 4;
                return true;
            }
            if (nextIsLiteral("false")) {
                index += 5;
                return false;
            }
            throw invalid();
        }

        boolean nextIsLiteralNull() {
            return nextIsLiteral("null");
        }

        void consumeLiteralNull() {
            if (!nextIsLiteralNull()) {
                throw invalid();
            }
            index += 4;
        }

        int readInt() {
            long value = readInteger();
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                throw invalid();
            }
            return (int) value;
        }

        long readLong() {
            return readInteger();
        }

        private long readInteger() {
            skipWhitespace();
            int start = index;
            if (index < input.length() && input.charAt(index) == '-') {
                index++;
            }
            if (index >= input.length() || !isDigit(input.charAt(index))) {
                throw invalid();
            }
            if (input.charAt(index) == '0') {
                index++;
                if (index < input.length() && isDigit(input.charAt(index))) {
                    throw invalid();
                }
            } else {
                while (index < input.length() && isDigit(input.charAt(index))) {
                    index++;
                }
            }
            // A JSON number that has a fraction or exponent is not an integer field.
            if (index < input.length() && (input.charAt(index) == '.'
                    || input.charAt(index) == 'e' || input.charAt(index) == 'E')) {
                throw invalid();
            }
            try {
                return Long.parseLong(input.substring(start, index));
            } catch (NumberFormatException outsideLongRange) {
                throw invalid();
            }
        }

        private char readUnicodeEscape() {
            if (index + 4 > input.length()) {
                throw invalid();
            }
            int value = 0;
            for (int count = 0; count < 4; count++) {
                int digit = Character.digit(input.charAt(index++), 16);
                if (digit < 0) {
                    throw invalid();
                }
                value = value * 16 + digit;
            }
            return (char) value;
        }

        private boolean nextIsLiteral(String literal) {
            skipWhitespace();
            if (!input.startsWith(literal, index)) {
                return false;
            }
            int end = index + literal.length();
            return end == input.length() || switch (input.charAt(end)) {
                case ',', '}', ']', ' ', '\t', '\n', '\r' -> true;
                default -> false;
            };
        }

        private void skipWhitespace() {
            while (index < input.length()) {
                char character = input.charAt(index);
                if (character == ' ' || character == '\t'
                        || character == '\n' || character == '\r') {
                    index++;
                } else {
                    break;
                }
            }
        }

        private static boolean isDigit(char value) {
            return value >= '0' && value <= '9';
        }
    }
}

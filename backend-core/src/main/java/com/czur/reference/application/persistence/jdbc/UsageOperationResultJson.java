package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.entitlement.AuthorizedProfileSnapshot;
import com.czur.reference.application.port.entitlement.UsageActual;
import com.czur.reference.application.port.entitlement.UsageEstimate;
import com.czur.reference.application.port.entitlement.UsageOperationResult;
import com.czur.reference.application.port.entitlement.UsageOperationScopes;
import com.czur.reference.application.port.entitlement.UsageReservation;
import com.czur.reference.application.port.entitlement.UsageUnit;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@link UsageOperationResult} 的独立、版本化 JSON 编解码器。
 *
 * <p>该格式只用于 {@code usage_operation.result_body}，不会复用会议命令幂等
 * JSON。编码器固定写 schemaVersion=2：除完整 Reservation 快照外，reserve 还
 * 保存首次授权 Profile/能力快照。解码器严格双读 v1/v2；v1 reserve 可稳定重放，
 * 但因缺少授权快照而不能继续创建新的归属会议。</p>
 *
 * <p>解码必须由调用方再次提供数据库查询所使用的 deployment、subject、
 * operation、idempotency key 和 request digest。正文中的任一范围与期望不一致
 * 都会 fail-fast，避免只凭 JSON 正文或 reservationId 跨主体重放。异常只包含固定
 * 字段名和解析位置，不包含 JSON 正文、摘要或动态字段值。</p>
 */
final class UsageOperationResultJson {
    private static final int CURRENT_SCHEMA_VERSION = 2;
    private static final Set<String> V1_TOP_LEVEL_FIELDS = Set.of(
            "schemaVersion", "deploymentScope", "subjectScope", "operationScope",
            "idempotencyKey", "requestDigest", "grantExpiresAt", "reservation");
    private static final Set<String> V2_TOP_LEVEL_FIELDS = Set.of(
            "schemaVersion", "deploymentScope", "subjectScope", "operationScope",
            "idempotencyKey", "requestDigest", "grantExpiresAt",
            "authorizedProfileSnapshot", "reservation");
    private static final Set<String> AUTHORIZED_PROFILE_FIELDS = Set.of(
            "profileId", "profileRevision", "requestedCapabilities",
            "grantedCapabilities", "maxDurationMs");
    private static final Set<String> RESERVATION_FIELDS = Set.of(
            "reservationId", "deploymentScope", "subjectScope", "grantId",
            "expectedMeetingId", "boundMeetingId", "boundAt", "reservedUsage",
            "actualUsage", "createdAt", "expiresAt", "state", "version",
            "committedAt", "cancelledAt", "cancellationReasonCode");
    private static final Set<String> USAGE_FIELDS = Set.of("unit", "amount");

    private UsageOperationResultJson() {
    }

    /** 编码完整、确定字段与能力顺序的 schemaVersion=2 结果。 */
    static String encode(UsageOperationResult result) {
        Objects.requireNonNull(result, "result");
        if (UsageOperationScopes.RESERVE.equals(result.operationScope())
                && !result.supportsOwnedMeetingCreation()) {
            throw new IllegalArgumentException(
                    "schemaVersion=2 reserve result requires authorized profile snapshot");
        }
        UsageReservation reservation = result.reservation();
        return "{\"schemaVersion\":" + CURRENT_SCHEMA_VERSION
                + ",\"deploymentScope\":" + quote(result.deploymentScope())
                + ",\"subjectScope\":" + quote(result.subjectScope())
                + ",\"operationScope\":" + quote(result.operationScope())
                + ",\"idempotencyKey\":" + quote(result.idempotencyKey())
                + ",\"requestDigest\":" + quote(result.requestDigest())
                + ",\"grantExpiresAt\":" + nullableInstant(result.grantExpiresAt())
                + ",\"authorizedProfileSnapshot\":"
                + encodeAuthorizedProfile(result.authorizedProfileSnapshot())
                + ",\"reservation\":{"
                + "\"reservationId\":" + quote(reservation.reservationId())
                + ",\"deploymentScope\":" + quote(reservation.deploymentScope())
                + ",\"subjectScope\":" + quote(reservation.subjectScope())
                + ",\"grantId\":" + quote(reservation.grantId())
                + ",\"expectedMeetingId\":" + quote(reservation.expectedMeetingId())
                + ",\"boundMeetingId\":" + nullableString(reservation.boundMeetingId())
                + ",\"boundAt\":" + nullableInstant(reservation.boundAt())
                + ",\"reservedUsage\":" + encodeUsage(
                        reservation.reservedUsage().unit(), reservation.reservedUsage().amount())
                + ",\"actualUsage\":" + (reservation.actualUsage() == null
                        ? "null"
                        : encodeUsage(reservation.actualUsage().unit(),
                                reservation.actualUsage().amount()))
                + ",\"createdAt\":" + quote(reservation.createdAt().toString())
                + ",\"expiresAt\":" + quote(reservation.expiresAt().toString())
                + ",\"state\":" + quote(reservation.state().name())
                + ",\"version\":" + reservation.version()
                + ",\"committedAt\":" + nullableInstant(reservation.committedAt())
                + ",\"cancelledAt\":" + nullableInstant(reservation.cancelledAt())
                + ",\"cancellationReasonCode\":"
                + nullableString(reservation.cancellationReasonCode())
                + "}}";
    }

    /**
     * 解码并再次校验数据库查询范围和摘要。
     *
     * <p>未知版本、重复/缺失/额外字段、非整数 long、非法 null、非法时间和
     * Reservation 状态组合都会被拒绝；不会静默使用默认值。</p>
     */
    static UsageOperationResult decode(
            String json,
            String expectedDeploymentScope,
            String expectedSubjectScope,
            String expectedOperationScope,
            String expectedIdempotencyKey,
            String expectedRequestDigest) {
        requireExpected("expectedDeploymentScope", expectedDeploymentScope);
        requireExpected("expectedSubjectScope", expectedSubjectScope);
        requireExpected("expectedOperationScope", expectedOperationScope);
        requireExpected("expectedIdempotencyKey", expectedIdempotencyKey);
        requireExpected("expectedRequestDigest", expectedRequestDigest);

        Reader reader = new Reader(json);
        Map<String, Object> top = reader.readObject();
        reader.ensureEnd();
        int schemaVersion = requireInt(top, "schemaVersion");
        if (schemaVersion == 1) {
            requireExactFields(top, V1_TOP_LEVEL_FIELDS, "v1 顶层");
        } else if (schemaVersion == CURRENT_SCHEMA_VERSION) {
            requireExactFields(top, V2_TOP_LEVEL_FIELDS, "v2 顶层");
        } else {
            throw new IllegalArgumentException("用量结果 JSON schemaVersion 不受支持");
        }

        String deploymentScope = requireString(top, "deploymentScope");
        String subjectScope = requireString(top, "subjectScope");
        String operationScope = requireString(top, "operationScope");
        String idempotencyKey = requireString(top, "idempotencyKey");
        String requestDigest = requireString(top, "requestDigest");
        Instant grantExpiresAt = nullableInstant(top, "grantExpiresAt");
        AuthorizedProfileSnapshot authorizedProfileSnapshot = schemaVersion == 1
                ? null
                : decodeAuthorizedProfile(nullableObject(top, "authorizedProfileSnapshot"));
        requireMatch("deploymentScope", expectedDeploymentScope, deploymentScope);
        requireMatch("subjectScope", expectedSubjectScope, subjectScope);
        requireMatch("operationScope", expectedOperationScope, operationScope);
        requireMatch("idempotencyKey", expectedIdempotencyKey, idempotencyKey);
        requireMatch("requestDigest", expectedRequestDigest, requestDigest);

        Map<String, Object> reservationValues = requireObject(top, "reservation");
        requireExactFields(reservationValues, RESERVATION_FIELDS, "reservation");
        UsageReservation reservation = decodeReservation(reservationValues);
        if (UsageOperationScopes.RESERVE.equals(operationScope)) {
            if (schemaVersion == 1) {
                return UsageOperationResult.restoreLegacyV1Reserve(
                        deploymentScope, subjectScope, idempotencyKey, requestDigest,
                        grantExpiresAt, reservation);
            }
            if (authorizedProfileSnapshot == null) {
                throw new IllegalArgumentException(
                        "用量结果 JSON reserve authorizedProfileSnapshot 缺失");
            }
            return UsageOperationResult.reserve(
                    deploymentScope, subjectScope, idempotencyKey, requestDigest,
                    grantExpiresAt, authorizedProfileSnapshot, reservation);
        }
        if (grantExpiresAt != null || authorizedProfileSnapshot != null) {
            throw new IllegalArgumentException(
                    "用量结果 JSON 终态 reserve 专属字段必须为空");
        }
        return UsageOperationResult.terminal(
                deploymentScope, subjectScope, operationScope,
                idempotencyKey, requestDigest, reservation);
    }

    private static String encodeAuthorizedProfile(AuthorizedProfileSnapshot snapshot) {
        if (snapshot == null) {
            return "null";
        }
        return "{\"profileId\":" + quote(snapshot.profileId())
                + ",\"profileRevision\":" + snapshot.profileRevision()
                + ",\"requestedCapabilities\":"
                + encodeCapabilities(snapshot.requestedCapabilities())
                + ",\"grantedCapabilities\":"
                + encodeCapabilities(snapshot.grantedCapabilities())
                + ",\"maxDurationMs\":" + snapshot.maxDurationMs()
                + '}';
    }

    private static AuthorizedProfileSnapshot decodeAuthorizedProfile(
            Map<String, Object> values) {
        if (values == null) {
            return null;
        }
        requireExactFields(values, AUTHORIZED_PROFILE_FIELDS, "authorizedProfileSnapshot");
        return new AuthorizedProfileSnapshot(
                requireString(values, "profileId"),
                requireInt(values, "profileRevision"),
                requireCapabilities(values, "requestedCapabilities"),
                requireCapabilities(values, "grantedCapabilities"),
                requireLong(values, "maxDurationMs"));
    }

    private static UsageReservation decodeReservation(Map<String, Object> values) {
        return new UsageReservation(
                requireString(values, "reservationId"),
                requireString(values, "deploymentScope"),
                requireString(values, "subjectScope"),
                requireString(values, "grantId"),
                requireString(values, "expectedMeetingId"),
                nullableString(values, "boundMeetingId"),
                nullableInstant(values, "boundAt"),
                decodeEstimate(requireObject(values, "reservedUsage")),
                decodeActual(nullableObject(values, "actualUsage")),
                requireInstant(values, "createdAt"),
                requireInstant(values, "expiresAt"),
                parseState(requireString(values, "state")),
                requireLong(values, "version"),
                nullableInstant(values, "committedAt"),
                nullableInstant(values, "cancelledAt"),
                nullableString(values, "cancellationReasonCode"));
    }

    private static UsageEstimate decodeEstimate(Map<String, Object> values) {
        requireExactFields(values, USAGE_FIELDS, "reservedUsage");
        return new UsageEstimate(parseUnit(requireString(values, "unit")),
                requireLong(values, "amount"));
    }

    private static UsageActual decodeActual(Map<String, Object> values) {
        if (values == null) {
            return null;
        }
        requireExactFields(values, USAGE_FIELDS, "actualUsage");
        return new UsageActual(parseUnit(requireString(values, "unit")),
                requireLong(values, "amount"));
    }

    private static UsageUnit parseUnit(String value) {
        try {
            return UsageUnit.valueOf(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("用量结果 JSON usage unit 非法");
        }
    }

    private static UsageReservation.State parseState(String value) {
        try {
            return UsageReservation.State.valueOf(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("用量结果 JSON reservation state 非法");
        }
    }

    private static String encodeUsage(UsageUnit unit, long amount) {
        return "{\"unit\":" + quote(unit.name()) + ",\"amount\":" + amount + "}";
    }

    private static String encodeCapabilities(Set<String> capabilities) {
        StringBuilder encoded = new StringBuilder("[");
        boolean first = true;
        for (String capability : capabilities) {
            if (!first) {
                encoded.append(',');
            }
            encoded.append(quote(capability));
            first = false;
        }
        return encoded.append(']').toString();
    }

    private static String nullableString(String value) {
        return value == null ? "null" : quote(value);
    }

    private static String nullableInstant(Instant value) {
        return value == null ? "null" : quote(value.toString());
    }

    private static void requireExactFields(
            Map<String, Object> values, Set<String> expected, String location) {
        for (String field : expected) {
            if (!values.containsKey(field)) {
                throw new IllegalArgumentException("用量结果 JSON " + location
                        + " 缺少字段: " + field);
            }
        }
        if (values.size() != expected.size()) {
            throw new IllegalArgumentException("用量结果 JSON " + location + " 包含额外字段");
        }
    }

    private static String requireString(Map<String, Object> values, String field) {
        Object value = values.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("用量结果 JSON 字段不是非空字符串: " + field);
        }
        return text;
    }

    private static String nullableString(Map<String, Object> values, String field) {
        Object value = values.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("用量结果 JSON nullable 字段非法: " + field);
        }
        return text;
    }

    private static int requireInt(Map<String, Object> values, String field) {
        long value = requireLong(values, field);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("用量结果 JSON 整数字段超出范围: " + field);
        }
        return (int) value;
    }

    private static long requireLong(Map<String, Object> values, String field) {
        Object value = values.get(field);
        if (!(value instanceof JsonInteger integer)) {
            throw new IllegalArgumentException("用量结果 JSON 字段不是精确整数: " + field);
        }
        try {
            return Long.parseLong(integer.text());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("用量结果 JSON long 字段超出范围: " + field);
        }
    }

    private static Set<String> requireCapabilities(
            Map<String, Object> values, String field) {
        Object value = values.get(field);
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(
                    "用量结果 JSON 能力字段不是数组: " + field);
        }
        TreeSet<String> capabilities = new TreeSet<>();
        for (Object entry : list) {
            if (!(entry instanceof String capability) || capability.isBlank()) {
                throw new IllegalArgumentException(
                        "用量结果 JSON 能力数组包含非法元素: " + field);
            }
            if (!capabilities.add(capability)) {
                throw new IllegalArgumentException(
                        "用量结果 JSON 能力数组包含重复元素: " + field);
            }
        }
        return Collections.unmodifiableSet(capabilities);
    }

    private static Instant requireInstant(Map<String, Object> values, String field) {
        return parseInstant(requireString(values, field), field);
    }

    private static Instant nullableInstant(Map<String, Object> values, String field) {
        String value = nullableString(values, field);
        return value == null ? null : parseInstant(value, field);
    }

    private static Instant parseInstant(String value, String field) {
        try {
            return Instant.parse(value);
        } catch (DateTimeException invalid) {
            throw new IllegalArgumentException("用量结果 JSON 时间字段非法: " + field);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireObject(Map<String, Object> values, String field) {
        Object value = values.get(field);
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("用量结果 JSON 字段不是对象: " + field);
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nullableObject(Map<String, Object> values, String field) {
        Object value = values.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("用量结果 JSON nullable 字段不是对象: " + field);
        }
        return (Map<String, Object>) value;
    }

    private static void requireExpected(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireMatch(String field, String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("用量结果 JSON 范围校验失败: " + field);
        }
    }

    private static String quote(String value) {
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
                        result.append(String.format("\\u%04x", (int) character));
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        return result.append('"').toString();
    }

    private record JsonInteger(String text) {
    }

    /** 只接受本协议需要的 JSON object/array/string/integer/null 子集。 */
    private static final class Reader {
        private final String input;
        private int index;

        private Reader(String input) {
            this.input = Objects.requireNonNull(input, "json");
        }

        private Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> values = new LinkedHashMap<>();
            if (peek() == '}') {
                expect('}');
                return values;
            }
            while (true) {
                String key = readString();
                if (values.containsKey(key)) {
                    throw error("存在重复字段");
                }
                expect(':');
                values.put(key, readValue());
                char next = peek();
                if (next == ',') {
                    expect(',');
                    continue;
                }
                if (next == '}') {
                    expect('}');
                    return values;
                }
                throw error("对象字段分隔符非法");
            }
        }

        private Object readValue() {
            char next = peek();
            if (next == '"') {
                return readString();
            }
            if (next == '{') {
                return readObject();
            }
            if (next == '[') {
                return readArray();
            }
            if (next == 'n') {
                expectLiteral("null");
                return null;
            }
            if (next == '-' || Character.isDigit(next)) {
                return readInteger();
            }
            throw error("字段值类型不受支持");
        }

        private List<Object> readArray() {
            expect('[');
            List<Object> values = new ArrayList<>();
            if (peek() == ']') {
                expect(']');
                return values;
            }
            while (true) {
                values.add(readValue());
                char next = peek();
                if (next == ',') {
                    expect(',');
                    continue;
                }
                if (next == ']') {
                    expect(']');
                    return values;
                }
                throw error("数组元素分隔符非法");
            }
        }

        private JsonInteger readInteger() {
            skipWhitespace();
            int start = index;
            if (input.charAt(index) == '-') {
                index++;
            }
            if (index >= input.length()) {
                throw error("整数不完整");
            }
            if (input.charAt(index) == '0') {
                index++;
                if (index < input.length() && Character.isDigit(input.charAt(index))) {
                    throw error("整数存在前导零");
                }
            } else if (input.charAt(index) >= '1' && input.charAt(index) <= '9') {
                while (index < input.length() && Character.isDigit(input.charAt(index))) {
                    index++;
                }
            } else {
                throw error("需要整数");
            }
            if (index < input.length()) {
                char suffix = input.charAt(index);
                if (suffix == '.' || suffix == 'e' || suffix == 'E' || suffix == '+') {
                    throw error("只允许精确十进制整数");
                }
            }
            return new JsonInteger(input.substring(start, index));
        }

        private String readString() {
            skipWhitespace();
            if (index >= input.length() || input.charAt(index++) != '"') {
                throw error("需要 JSON 字符串");
            }
            StringBuilder value = new StringBuilder();
            while (index < input.length()) {
                char character = input.charAt(index++);
                if (character == '"') {
                    return value.toString();
                }
                if (character < 0x20) {
                    throw error("字符串包含未转义控制字符");
                }
                if (character != '\\') {
                    value.append(character);
                    continue;
                }
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
                    case 'u' -> value.append(readUnicode());
                    default -> throw error("未知字符串转义");
                }
            }
            throw error("字符串未闭合");
        }

        private char readUnicode() {
            if (index + 4 > input.length()) {
                throw error("Unicode 转义不完整");
            }
            try {
                char decoded = (char) Integer.parseInt(input.substring(index, index + 4), 16);
                index += 4;
                return decoded;
            } catch (NumberFormatException invalid) {
                throw error("Unicode 转义非法");
            }
        }

        private void expectLiteral(String literal) {
            skipWhitespace();
            if (!input.startsWith(literal, index)) {
                throw error("JSON literal 非法");
            }
            index += literal.length();
        }

        private char peek() {
            skipWhitespace();
            if (index >= input.length()) {
                throw error("JSON 意外结束");
            }
            return input.charAt(index);
        }

        private void expect(char expected) {
            skipWhitespace();
            if (index >= input.length() || input.charAt(index++) != expected) {
                throw error("需要 JSON 结构字符");
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

        private IllegalArgumentException error(String reason) {
            return new IllegalArgumentException("用量结果 JSON 非法: " + reason
                    + "，位置=" + index);
        }
    }
}

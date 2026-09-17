package com.czur.reference.application.port;

import com.czur.reference.domain.meeting.MeetingOwnership;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * 新认证会议命令的 opaque（不透明）幂等范围编码器。
 *
 * <p>{@code idempotency_record.subject_scope} 是既有数据库主键的一部分，V003
 * 不拆列。新命令因此把部署范围、稳定主体和操作范围编码成一个不可由调用方
 * 手工拼接的字符串。格式固定为：</p>
 *
 * <pre>{@code
 * AIMS|1|<base64url长度>:<deployment><长度>:<subject><长度>:<operation>
 * }</pre>
 *
 * <p>每个组件先按 UTF-8 编码，再转为无填充 Base64 URL 文本；长度前缀以编码
 * 后的 ASCII 字符数计算。这样组件可以包含冒号、分隔符和 Unicode，同时解码
 * 不依赖 Java {@code char} 与其他语言字节长度的差异。magic、版本和长度前缀
 * 共同防止格式碰撞，也为未来升级保留拒绝未知版本的能力。</p>
 *
 * <p>旧命令使用的裸 {@code meeting.create}/{@code meeting.stop} 不带 magic，
 * 只能留在 legacy namespace。{@link #decode(String)} 会明确拒绝它们，因此旧
 * 结果绝不会被解释为某个真实主体的认证结果。</p>
 */
public final class IdempotencyScopeCodec {
    /** 新认证创建命令写入编码范围时使用的稳定操作名。 */
    public static final String MEETING_CREATE_OPERATION = "meeting.create.v1";

    /** 新认证停止命令写入编码范围时使用的稳定操作名。 */
    public static final String MEETING_STOP_OPERATION = "meeting.stop.v1";

    private static final String PREFIX = "AIMS|1|";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private IdempotencyScopeCodec() {
    }

    /**
     * 编码一个真实主体的命令范围。
     *
     * @param deploymentScope 服务端配置决定的稳定部署范围
     * @param subjectScope 身份 Provider 返回的稳定主体，不得使用 Token 或显示名
     * @param operationScope 由服务端代码冻结的版本化操作名
     * @return 可直接作为 {@code idempotency_record.subject_scope} 的 opaque key
     */
    public static String encode(
            String deploymentScope,
            String subjectScope,
            String operationScope) {
        String deployment = encodeComponent("deploymentScope", deploymentScope);
        String subject = encodeComponent(
                "subjectScope", MeetingOwnership.requireOwnedSubjectScope(subjectScope));
        String operation = encodeComponent("operationScope", operationScope);
        return PREFIX
                + deployment.length() + ':' + deployment
                + subject.length() + ':' + subject
                + operation.length() + ':' + operation;
    }

    /** 为新认证创建命令生成范围；旧兼容命令不得调用该方法后回写历史记录。 */
    public static String meetingCreate(String deploymentScope, String subjectScope) {
        return encode(deploymentScope, subjectScope, MEETING_CREATE_OPERATION);
    }

    /** 为新认证停止命令生成范围；归属校验必须发生在读取该范围的结果之前。 */
    public static String meetingStop(String deploymentScope, String subjectScope) {
        return encode(deploymentScope, subjectScope, MEETING_STOP_OPERATION);
    }

    /**
     * 严格解码新格式，主要用于诊断、迁移验证和契约测试。
     *
     * <p>业务查询不需要先解码：应使用 {@link #encode(String, String, String)}
     * 计算完整键后精确查询。该方法拒绝旧裸 scope、未知版本、非规范 Base64、
     * 非法 UTF-8、空白组件和尾随内容。</p>
     */
    public static Components decode(String encodedScope) {
        Objects.requireNonNull(encodedScope, "encodedScope");
        if (!encodedScope.startsWith(PREFIX)) {
            throw new IllegalArgumentException("unsupported idempotency scope format");
        }
        Cursor cursor = new Cursor(encodedScope, PREFIX.length());
        String deploymentScope = cursor.readComponent("deploymentScope");
        String subjectScope = cursor.readComponent("subjectScope");
        String operationScope = cursor.readComponent("operationScope");
        if (!cursor.isAtEnd()) {
            throw new IllegalArgumentException("idempotency scope contains trailing data");
        }
        return new Components(deploymentScope, subjectScope, operationScope);
    }

    /** 解码后的三元组；只承载稳定范围，不承载凭据、角色或权限。 */
    public record Components(
            String deploymentScope,
            String subjectScope,
            String operationScope) {
        public Components {
            requireText("deploymentScope", deploymentScope);
            MeetingOwnership.requireOwnedSubjectScope(subjectScope);
            requireText("operationScope", operationScope);
        }
    }

    private static String encodeComponent(String name, String value) {
        requireText(name, value);
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeComponent(String name, String encoded) {
        final byte[] bytes;
        try {
            bytes = DECODER.decode(encoded);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(name + " is not valid Base64 URL data");
        }
        String canonical = ENCODER.encodeToString(bytes);
        if (!canonical.equals(encoded)) {
            throw new IllegalArgumentException(name + " is not canonical Base64 URL data");
        }
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            requireText(name, value);
            return value;
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException(name + " is not valid UTF-8 data");
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static final class Cursor {
        private final String value;
        private int index;

        private Cursor(String value, int index) {
            this.value = value;
            this.index = index;
        }

        private String readComponent(String name) {
            int delimiter = value.indexOf(':', index);
            if (delimiter < 0 || delimiter == index) {
                throw new IllegalArgumentException(name + " length prefix is missing");
            }
            String lengthText = value.substring(index, delimiter);
            if (lengthText.length() > 1 && lengthText.charAt(0) == '0') {
                throw new IllegalArgumentException(name + " length prefix is not canonical");
            }
            final int length;
            try {
                length = Integer.parseInt(lengthText);
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException(name + " length prefix is invalid");
            }
            if (length <= 0) {
                throw new IllegalArgumentException(name + " length must be positive");
            }
            int start = delimiter + 1;
            final int end;
            try {
                end = Math.addExact(start, length);
            } catch (ArithmeticException failure) {
                throw new IllegalArgumentException(name + " length exceeds supported range");
            }
            if (end > value.length()) {
                throw new IllegalArgumentException(name + " data is truncated");
            }
            index = end;
            return decodeComponent(name, value.substring(start, end));
        }

        private boolean isAtEnd() {
            return index == value.length();
        }
    }
}

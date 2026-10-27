package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.domain.meeting.MeetingIdPolicy;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 * 使用部署级 Secret 签发会议列表 keyset 游标的 HMAC-SHA256 编解码器。
 *
 * <p>公开 token 只携带格式版本、{@code createdAt} 和 {@code meetingId}，不会把
 * 部署范围或主体编码进可被客户端还原的 Base64 正文。部署范围与主体以带长度
 * 前缀的 UTF-8 字节加入 HMAC 输入，因此同一 token 在另一个部署或主体下验签
 * 必然失败。格式前缀和每个字段都使用确定性二进制编码，避免使用分隔符拼接造成
 * 边界歧义。</p>
 *
 * <p>构造时要求至少 32 字节高熵 Secret；调用方必须从部署 Secret 管理设施加载
 * 并在进程重启后保持稳定，不能临时随机生成。该类不会记录、返回或在
 * {@link #toString()} 中输出 Secret、token 或主体。解码对畸形、未知版本、超长、
 * 非规范 Base64、错误范围和错误密钥统一返回空结果，由查询端口稳定映射为
 * {@code INVALID_PAGE_TOKEN}。</p>
 */
public final class HmacMeetingPageTokenCodec {
    private static final String ALGORITHM = "HmacSHA256";
    private static final byte FORMAT_VERSION = 1;
    private static final byte[] DOMAIN_SEPARATOR =
            "czur.ai.meeting.page-token.v1".getBytes(StandardCharsets.US_ASCII);
    private static final int MINIMUM_SECRET_BYTES = 32;
    private static final int SIGNATURE_BYTES = 32;
    private static final int MAX_PAYLOAD_BYTES =
            1 + Long.BYTES + Integer.BYTES + Integer.BYTES
                    + MeetingIdPolicy.MAX_UTF8_BYTES;
    private static final int MAX_TOKEN_CHARACTERS =
            base64UrlLength(MAX_PAYLOAD_BYTES) + 1 + base64UrlLength(SIGNATURE_BYTES);
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    /** 构造时复制，避免调用方随后修改原数组改变既有 token 的验签结果。 */
    private final byte[] secret;

    /**
     * 创建一个部署范围内稳定使用的分页 token 编解码器。
     *
     * @param deploymentSecret 至少 32 字节的部署级高熵 Secret；数组内容会被复制
     */
    public HmacMeetingPageTokenCodec(byte[] deploymentSecret) {
        Objects.requireNonNull(deploymentSecret, "deploymentSecret");
        if (deploymentSecret.length < MINIMUM_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "deploymentSecret must contain at least 32 bytes");
        }
        this.secret = Arrays.copyOf(deploymentSecret, deploymentSecret.length);
    }

    /**
     * 为当前认证范围签发一个不可跨范围复用的 opaque token。
     *
     * @param context 已验证身份和服务端部署范围
     * @param cursor 本页最后一条已返回记录的降序 keyset 游标
     */
    public String encode(MeetingCommandContext context, Cursor cursor) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(cursor, "cursor");
        byte[] payload = encodePayload(cursor);
        byte[] signature = sign(context, payload);
        return ENCODER.encodeToString(payload) + '.' + ENCODER.encodeToString(signature);
    }

    /**
     * 在当前认证范围内校验并解码 token。
     *
     * <p>先校验 token 分段和规范 Base64，再对签名执行常量时间比较，只有签名
     * 正确后才解析游标字段。payload 内的长度必须与剩余字节精确相等，拒绝截断
     * 和尾随数据。token 上限由共享的 1024 UTF-8 bytes MeetingId 策略和固定格式
     * 推导，不使用与领域/数据库脱节的私有魔数。</p>
     *
     * @return 成功时返回游标；任一校验失败时返回空
     */
    public Optional<Cursor> decode(MeetingCommandContext context, String token) {
        Objects.requireNonNull(context, "context");
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_CHARACTERS) {
            return Optional.empty();
        }
        int separator = token.indexOf('.');
        if (separator <= 0 || separator != token.lastIndexOf('.')
                || separator == token.length() - 1) {
            return Optional.empty();
        }

        try {
            String payloadText = token.substring(0, separator);
            String signatureText = token.substring(separator + 1);
            byte[] payload = decodeCanonical(payloadText);
            byte[] signature = decodeCanonical(signatureText);
            if (payload.length > MAX_PAYLOAD_BYTES
                    || signature.length != SIGNATURE_BYTES) {
                return Optional.empty();
            }
            byte[] expected = sign(context, payload);
            if (!MessageDigest.isEqual(expected, signature)) {
                return Optional.empty();
            }
            return Optional.of(decodePayload(payload));
        } catch (IllegalArgumentException | IOException | DateTimeException failure) {
            return Optional.empty();
        }
    }

    /** 固定诊断文本只说明配置有效，不输出 Secret 长度、摘要或 token。 */
    @Override
    public String toString() {
        return "HmacMeetingPageTokenCodec[configured=true, algorithm=HmacSHA256]";
    }

    private byte[] sign(MeetingCommandContext context, byte[] payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            mac.update(DOMAIN_SEPARATOR);
            updateLengthPrefixed(mac,
                    context.deploymentScope().getBytes(StandardCharsets.UTF_8));
            updateLengthPrefixed(mac,
                    context.identity().subject().subjectScope().getBytes(StandardCharsets.UTF_8));
            updateLengthPrefixed(mac, payload);
            return mac.doFinal();
        } catch (GeneralSecurityException failure) {
            // HmacSHA256 是 Java 平台必备算法；若运行环境缺失，必须明确停止，
            // 不能退化到无签名 token 或把 Secret 写入异常信息。
            throw new IllegalStateException("HMAC-SHA256 page token support unavailable");
        }
    }

    private static void updateLengthPrefixed(Mac mac, byte[] value) {
        mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        mac.update(value);
    }

    private static byte[] encodePayload(Cursor cursor) {
        byte[] meetingId = cursor.meetingId().getBytes(StandardCharsets.UTF_8);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeByte(FORMAT_VERSION);
                output.writeLong(cursor.createdAt().getEpochSecond());
                output.writeInt(cursor.createdAt().getNano());
                output.writeInt(meetingId.length);
                output.write(meetingId);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("unable to encode page token cursor");
        }
    }

    private static Cursor decodePayload(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readUnsignedByte() != FORMAT_VERSION) {
                throw new IllegalArgumentException("unknown page token version");
            }
            long epochSecond = input.readLong();
            int nano = input.readInt();
            int meetingIdLength = input.readInt();
            if (nano < 0 || nano > 999_999_999
                    || meetingIdLength < 1
                    || meetingIdLength > MeetingIdPolicy.MAX_UTF8_BYTES
                    || meetingIdLength != input.available()) {
                throw new IllegalArgumentException("invalid page token payload");
            }
            byte[] meetingIdBytes = input.readNBytes(meetingIdLength);
            if (meetingIdBytes.length != meetingIdLength || input.read() != -1) {
                throw new EOFException("incomplete page token payload");
            }
            String meetingId = decodeUtf8(meetingIdBytes);
            return new Cursor(Instant.ofEpochSecond(epochSecond, nano), meetingId);
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("meetingId is not valid UTF-8");
        }
    }

    private static byte[] decodeCanonical(String value) {
        byte[] decoded = DECODER.decode(value);
        if (!ENCODER.encodeToString(decoded).equals(value)) {
            throw new IllegalArgumentException("page token uses non-canonical Base64 URL data");
        }
        return decoded;
    }

    private static int base64UrlLength(int byteLength) {
        return Math.floorDiv(Math.multiplyExact(byteLength, 8) + 5, 6);
    }

    /**
     * 降序 keyset 游标。
     *
     * <p>{@code createdAt} 和 {@code meetingId} 必须来自同一条已返回数据库记录，
     * 不能使用客户端时间或额外探测的第 {@code limit + 1} 条记录。</p>
     */
    public record Cursor(Instant createdAt, String meetingId) {
        public Cursor {
            Objects.requireNonNull(createdAt, "createdAt");
            meetingId = MeetingIdPolicy.requireValid(meetingId);
        }

        @Override
        public String toString() {
            return "HmacMeetingPageTokenCodec.Cursor[createdAt=" + createdAt
                    + ", meetingIdPresent=true]";
        }
    }
}

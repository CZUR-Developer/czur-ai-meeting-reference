package com.czur.reference.protocol.json;

import com.czur.reference.application.port.RenewRealtimeTokenUnitOfWork;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * V014 续期 Grant 的唯一规范 JSON 编解码器。
 *
 * <p>字段只包含公开协议的 {@code meetingId/streamId/sessionToken/expiresAt}，固定
 * 顺序、无空白、UTC 微秒精度；数据库保存编码原文及 SHA-256，同一幂等键在未来
 * 版本重放时直接返回已存原文，绝不重新编码。解码拒绝额外/重复/缺失字段、
 * 非规范 JSON、非法 UTF-8/Unicode、非 256 位 Base64 URL Token 和非微秒时间。
 * 所有失败只抛固定消息，不在异常或日志中携带 Token 或 JSON 正文。</p>
 */
public final class RealtimeTokenGrantJson {
    private static final String INVALID = "realtime token grant JSON is invalid";
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder TOKEN_DECODER = Base64.getUrlDecoder();

    private RealtimeTokenGrantJson() {
    }

    /** 编码服务端已生成的 Grant；调用方只可把结果写入 V014 或续期响应。 */
    public static String encode(RenewRealtimeTokenUnitOfWork.Grant grant) {
        try {
            Objects.requireNonNull(grant, "grant");
            requireToken(grant.sessionToken());
            if (!grant.expiresAt().equals(grant.expiresAt().truncatedTo(ChronoUnit.MICROS))) {
                throw invalid();
            }
            return "{\"meetingId\":" + CreateFactJsonSupport.quote(grant.meetingId())
                    + ",\"streamId\":" + CreateFactJsonSupport.quote(grant.streamId())
                    + ",\"sessionToken\":" + CreateFactJsonSupport.quote(grant.sessionToken())
                    + ",\"expiresAt\":" + CreateFactJsonSupport.quote(grant.expiresAt().toString())
                    + '}';
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    /** 返回规范 JSON 的 UTF-8 字节；不得进入日志或错误 envelope。 */
    public static byte[] encodeUtf8(RenewRealtimeTokenUnitOfWork.Grant grant) {
        return encode(grant).getBytes(StandardCharsets.UTF_8);
    }

    /** 严格读取已持久化的规范 JSON；非规范但语义相近的变体也拒绝。 */
    public static RenewRealtimeTokenUnitOfWork.Grant decode(String json) {
        try {
            CreateFactJsonSupport.Reader reader = new CreateFactJsonSupport.Reader(json);
            reader.expect('{');
            String meetingId = null;
            String streamId = null;
            String sessionToken = null;
            Instant expiresAt = null;
            boolean meetingSeen = false;
            boolean streamSeen = false;
            boolean tokenSeen = false;
            boolean expirySeen = false;
            if (!reader.nextIs('}')) {
                while (true) {
                    String field = reader.readString();
                    reader.expect(':');
                    switch (field) {
                        case "meetingId" -> {
                            if (meetingSeen) throw invalid();
                            meetingSeen = true;
                            meetingId = reader.readString();
                        }
                        case "streamId" -> {
                            if (streamSeen) throw invalid();
                            streamSeen = true;
                            streamId = reader.readString();
                        }
                        case "sessionToken" -> {
                            if (tokenSeen) throw invalid();
                            tokenSeen = true;
                            sessionToken = reader.readString();
                        }
                        case "expiresAt" -> {
                            if (expirySeen) throw invalid();
                            expirySeen = true;
                            expiresAt = Instant.parse(reader.readString());
                        }
                        default -> throw invalid();
                    }
                    if (!reader.nextIs(',')) {
                        break;
                    }
                    reader.consume(',');
                }
            }
            reader.expect('}');
            reader.ensureEnd();
            if (!meetingSeen || !streamSeen || !tokenSeen || !expirySeen) {
                throw invalid();
            }
            var grant = new RenewRealtimeTokenUnitOfWork.Grant(
                    meetingId, streamId, sessionToken, expiresAt);
            if (!json.equals(encode(grant))) {
                throw invalid();
            }
            return grant;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    /** 严格 UTF-8 解码后执行同一规范校验。 */
    public static RenewRealtimeTokenUnitOfWork.Grant decodeUtf8(byte[] bytes) {
        try {
            return decode(CreateFactJsonSupport.decodeUtf8(bytes));
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private static void requireToken(String token) {
        // 32 个随机字节的无填充 Base64 URL 固定为 43 个 ASCII 字符；先限定
        // 长度，再解码，避免损坏的数据库正文诱发不受控的大额数组分配。
        if (token == null || token.length() != 43) {
            throw invalid();
        }
        byte[] decoded = TOKEN_DECODER.decode(token);
        try {
            if (decoded.length != 32 || !TOKEN_ENCODER.encodeToString(decoded).equals(token)) {
                throw invalid();
            }
        } finally {
            Arrays.fill(decoded, (byte) 0);
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(INVALID);
    }
}

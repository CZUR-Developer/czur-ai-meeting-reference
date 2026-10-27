package com.czur.reference.domain.meeting;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 会议标识在领域、数据库、公开协议和分页游标之间共享的长度策略。
 *
 * <p>数据库列按 UTF-8 字节限长，分页 token 也编码 UTF-8 字节，因此边界按字节而不是
 * Java UTF-16 字符数计算。公开 OpenAPI 的 MeetingId 当前限制为 ASCII 格式，
 * 其 {@code maxLength=1024} 与本字节上限等价；领域层仍按严格 UTF-8 校验，避免
 * 未配对 surrogate 被编码器静默替换后产生不同标识。</p>
 */
public final class MeetingIdPolicy {
    /** meetingId 编码为 UTF-8 后允许的最大字节数。 */
    public static final int MAX_UTF8_BYTES = 1024;

    private MeetingIdPolicy() {
    }

    /** 校验非空、严格 UTF-8 和共享字节上限，并原样返回标识。 */
    public static String requireValid(String meetingId) {
        if (meetingId == null || meetingId.isBlank()) {
            throw new IllegalArgumentException("meetingId must not be blank");
        }
        // 每个 UTF-16 code unit 编成 UTF-8 后至少占一个字节；先做这个不会误拒
        // 合法值的廉价检查，避免对明显超长的外部输入分配同等规模 ByteBuffer。
        if (meetingId.length() > MAX_UTF8_BYTES) {
            throw new IllegalArgumentException(
                    "meetingId must not exceed 1024 UTF-8 bytes");
        }
        int length = utf8Length(meetingId);
        if (length > MAX_UTF8_BYTES) {
            throw new IllegalArgumentException(
                    "meetingId must not exceed 1024 UTF-8 bytes");
        }
        return meetingId;
    }

    /**
     * 计算严格 UTF-8 字节数；非法 UTF-16 输入不会使用替换字符继续执行。
     */
    public static int utf8Length(String meetingId) {
        if (meetingId == null) {
            throw new IllegalArgumentException("meetingId must not be null");
        }
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(meetingId));
            return encoded.remaining();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("meetingId must be valid UTF-8 text");
        }
    }
}

package com.czur.reference.contract;

import com.czur.aimeeting.contract.realtime.v1.ClientEnvelope;
import com.czur.aimeeting.contract.realtime.v1.StreamStart;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 确认 Maven 从仓库根目录唯一的 realtime.proto 生成可编译的 Java 类。
 * 本测试只验证构建链；业务字段、二进制限制和版本校验由 T16-02 后续 codec 测试负责。
 */
class RealtimeProtoGenerationTest {
    @Test
    void generatedClassesCompileAndRoundTripWithoutSystemProtoc() throws Exception {
        ClientEnvelope message = ClientEnvelope.newBuilder()
                .setSpecVersion("0.1")
                .setMeetingId("mtg_build_probe")
                .setStreamId("mtg_build_probe")
                .setClientMessageId("msg_build_probe")
                .setStreamStart(StreamStart.getDefaultInstance())
                .build();

        assertEquals("realtime.proto", ClientEnvelope.getDescriptor().getFile().getName());
        assertEquals(message, ClientEnvelope.parseFrom(message.toByteArray()));
        assertEquals(ClientEnvelope.BodyCase.STREAM_START, message.getBodyCase());
        assertFalse(message.toByteArray().length == 0);
    }
}

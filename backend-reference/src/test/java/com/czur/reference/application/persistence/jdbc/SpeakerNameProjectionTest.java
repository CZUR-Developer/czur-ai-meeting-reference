package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.speaker.SpeakerNameEditPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 只测实际编辑规则，不用供应商或浏览器替身声称真实声纹识别通过。 */
class SpeakerNameProjectionTest {
    private ObjectNode transcript() throws Exception {
        return (ObjectNode) new ObjectMapper().readTree("""
                {"text":"原文不变","segments":[
                {"segmentId":"a","sessionSpeakerId":"1","text":"甲"},
                {"segmentId":"b","sessionSpeakerId":"1","text":"乙"},
                {"segmentId":"c","sessionSpeakerId":"2","text":"丙"}]}
                """);
    }
    @Test void singleKeepsOtherSegmentsAndFreezesPreviousUnnamedSpeaker() throws Exception {
        var body = transcript();
        assertEquals(1, JdbcSpeakerNameEditPort.applyNames(body, "1", null,
                new SpeakerNameEditPort.Edit("名字", SpeakerNameEditPort.Scope.SINGLE, "a", true)));
        assertEquals("名字", body.path("segments").get(0).path("speakerDisplayName").asText());
        assertTrue(body.path("segments").get(1).path("speakerDisplayName").isNull());
        assertFalse(body.path("segments").get(2).has("speakerDisplayName"));
        assertEquals("原文不变", body.path("text").asText());
    }
    @Test void allReplacesPriorOverridesOnlyForTheSameSessionSpeaker() throws Exception {
        var body = transcript();
        JdbcSpeakerNameEditPort.applyNames(body, "1", "旧姓名",
                new SpeakerNameEditPort.Edit("单段名字", SpeakerNameEditPort.Scope.SINGLE, "a", false));
        assertEquals("旧姓名", body.path("segments").get(1).path("speakerDisplayName").asText());
        assertEquals(2, JdbcSpeakerNameEditPort.applyNames(body, "1", "旧姓名",
                new SpeakerNameEditPort.Edit("统一姓名", SpeakerNameEditPort.Scope.ALL, null, false)));
        assertEquals("统一姓名", body.path("segments").get(1).path("speakerDisplayName").asText());
        assertFalse(body.path("segments").get(2).has("speakerDisplayName"));
    }
}

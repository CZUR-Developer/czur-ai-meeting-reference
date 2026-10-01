package com.czur.reference.protocol.json;

import com.czur.reference.application.port.RenewRealtimeTokenUnitOfWork;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** V014 Grant 原文编解码的固定向量、完整字段和脱敏失败门禁。 */
class RealtimeTokenGrantJsonTest {
    private static final String MEETING_ID = "mtg_realtime_grant_codec";
    private static final Instant EXPIRES = Instant.parse("2030-01-01T00:05:00.123456Z");
    private static final String TOKEN;

    static {
        byte[] secret = new byte[32];
        Arrays.fill(secret, (byte) 0x7a);
        TOKEN = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        Arrays.fill(secret, (byte) 0);
    }

    @Test
    void fixedCanonicalVectorRoundTripsWithoutChangingBytes() {
        var grant = new RenewRealtimeTokenUnitOfWork.Grant(
                MEETING_ID, MEETING_ID, TOKEN, EXPIRES);
        String expected = "{\"meetingId\":\"" + MEETING_ID + "\",\"streamId\":\""
                + MEETING_ID + "\",\"sessionToken\":\"" + TOKEN
                + "\",\"expiresAt\":\"2030-01-01T00:05:00.123456Z\"}";
        assertEquals(expected, RealtimeTokenGrantJson.encode(grant));
        assertEquals(grant, RealtimeTokenGrantJson.decode(expected));
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8),
                RealtimeTokenGrantJson.encodeUtf8(grant));
        assertEquals(grant, RealtimeTokenGrantJson.decodeUtf8(
                expected.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsUnknownDuplicateMissingAndNonCanonicalFields() {
        String valid = RealtimeTokenGrantJson.encode(new RenewRealtimeTokenUnitOfWork.Grant(
                MEETING_ID, MEETING_ID, TOKEN, EXPIRES));
        reject(valid.replace("\"streamId\"", "\"other\""));
        reject(valid.replace("\"streamId\":\"" + MEETING_ID + "\",", ""));
        reject(valid.replace("\"streamId\":", "\"meetingId\":"));
        reject(valid.replace("{\"meetingId\"", "{ \"meetingId\""));
        reject(valid.substring(0, valid.length() - 1) + ",}");
        reject(valid.replace("\"streamId\":\"" + MEETING_ID + "\"",
                "\"streamId\":\"mtg_other\""));
    }

    @Test
    void rejectsMalformedTokenTimeAndUtf8WithoutEchoingSecrets() {
        String valid = RealtimeTokenGrantJson.encode(new RenewRealtimeTokenUnitOfWork.Grant(
                MEETING_ID, MEETING_ID, TOKEN, EXPIRES));
        reject(valid.replace(TOKEN, TOKEN.substring(1)));
        reject(valid.replace(TOKEN, "invalid/" + TOKEN));
        reject(valid.replace(".123456Z", ".123456789Z"));
        reject(valid.replace("2030-01-01T00:05:00.123456Z", "invalid-time"));
        var malformed = new byte[]{'{', '"', (byte) 0xc3, '"', '}'};
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> RealtimeTokenGrantJson.decodeUtf8(malformed));
        assertFalse(failure.getMessage().contains(TOKEN));
        assertThrows(IllegalArgumentException.class,
                () -> RealtimeTokenGrantJson.encode(new RenewRealtimeTokenUnitOfWork.Grant(
                        MEETING_ID, MEETING_ID, TOKEN,
                        Instant.parse("2030-01-01T00:05:00.123456789Z"))));
    }

    private static void reject(String json) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> RealtimeTokenGrantJson.decode(json));
        assertFalse(failure.getMessage().contains(TOKEN));
        assertFalse(failure.getMessage().contains(MEETING_ID));
    }
}

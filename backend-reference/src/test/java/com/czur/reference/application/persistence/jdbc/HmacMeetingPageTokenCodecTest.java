package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.VerifiedIdentity;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HmacMeetingPageTokenCodecTest {
    private static final Instant CREATED = Instant.parse("2026-09-20T08:15:30.123456Z");
    private static final byte[] SECRET = secret((byte) 0x21);

    @Test
    void roundTripsCursorWithoutPublishingScopeOrSecret() {
        HmacMeetingPageTokenCodec codec = new HmacMeetingPageTokenCodec(SECRET);
        MeetingCommandContext context = context("deployment-private", "subject-private");
        HmacMeetingPageTokenCodec.Cursor expected =
                new HmacMeetingPageTokenCodec.Cursor(CREATED, "meeting-中文-42");

        String token = codec.encode(context, expected);

        assertEquals(expected, codec.decode(context, token).orElseThrow());
        String decodedPayload = new String(
                java.util.Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.'))),
                StandardCharsets.ISO_8859_1);
        assertFalse(decodedPayload.contains("deployment-private"));
        assertFalse(decodedPayload.contains("subject-private"));
        assertFalse(token.contains("deployment-private"));
        assertFalse(token.contains("subject-private"));
        assertFalse(codec.toString().contains(new String(SECRET, StandardCharsets.ISO_8859_1)));
    }

    @Test
    void rejectsTamperingCrossScopeAndWrongSecretWithoutExplainingWhichBoundaryFailed() {
        HmacMeetingPageTokenCodec codec = new HmacMeetingPageTokenCodec(SECRET);
        MeetingCommandContext owner = context("deployment-a", "subject-a");
        String token = codec.encode(owner,
                new HmacMeetingPageTokenCodec.Cursor(CREATED, "meeting-1"));

        int changedIndex = token.charAt(0) == 'A' ? 1 : 0;
        char replacement = token.charAt(changedIndex) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, changedIndex) + replacement
                + token.substring(changedIndex + 1);

        assertTrue(codec.decode(owner, tampered).isEmpty());
        assertTrue(codec.decode(context("deployment-b", "subject-a"), token).isEmpty());
        assertTrue(codec.decode(context("deployment-a", "subject-b"), token).isEmpty());
        assertTrue(new HmacMeetingPageTokenCodec(secret((byte) 0x67))
                .decode(owner, token).isEmpty());
    }

    @Test
    void rejectsMalformedNonCanonicalAndCorrectlySignedUnknownVersionTokens()
            throws Exception {
        HmacMeetingPageTokenCodec codec = new HmacMeetingPageTokenCodec(SECRET);
        MeetingCommandContext context = context("deployment-a", "subject-a");

        assertTrue(codec.decode(context, null).isEmpty());
        assertTrue(codec.decode(context, " ").isEmpty());
        assertTrue(codec.decode(context, "missing-dot").isEmpty());
        assertTrue(codec.decode(context, "a.b.c").isEmpty());
        assertTrue(codec.decode(context, "a=.AAAA").isEmpty());

        String valid = codec.encode(context,
                new HmacMeetingPageTokenCodec.Cursor(CREATED, "meeting-1"));
        String[] parts = valid.split("\\.");
        byte[] payload = java.util.Base64.getUrlDecoder().decode(parts[0]);
        payload[0] = 2;
        String unknownVersion = signedToken(context, payload, SECRET);
        assertTrue(codec.decode(context, unknownVersion).isEmpty());
    }

    @Test
    void requiresStrongSecretAndUsesTheSharedMeetingIdBoundary() {
        assertThrows(IllegalArgumentException.class,
                () -> new HmacMeetingPageTokenCodec(new byte[31]));
        HmacMeetingPageTokenCodec codec = new HmacMeetingPageTokenCodec(SECRET);
        String longMeetingId = "x".repeat(1024);
        MeetingCommandContext context = context("deployment", "subject");
        String token = codec.encode(context,
                new HmacMeetingPageTokenCodec.Cursor(CREATED, longMeetingId));
        assertEquals(longMeetingId,
                codec.decode(context, token).orElseThrow().meetingId());
        assertThrows(IllegalArgumentException.class,
                () -> new HmacMeetingPageTokenCodec.Cursor(
                        CREATED, "x".repeat(1025)));
        assertThrows(IllegalArgumentException.class,
                () -> new HmacMeetingPageTokenCodec.Cursor(CREATED, " "));
    }

    @Test
    void constructorCopiesSecretInsteadOfKeepingCallerArray() {
        byte[] mutable = secret((byte) 0x35);
        HmacMeetingPageTokenCodec codec = new HmacMeetingPageTokenCodec(mutable);
        MeetingCommandContext context = context("deployment", "subject");
        String token = codec.encode(context,
                new HmacMeetingPageTokenCodec.Cursor(CREATED, "meeting"));

        Arrays.fill(mutable, (byte) 0);

        assertTrue(codec.decode(context, token).isPresent());
    }

    private static byte[] secret(byte fill) {
        byte[] value = new byte[32];
        Arrays.fill(value, fill);
        return value;
    }

    /**
     * 测试侧按公开格式重签 payload，确保 unknown-version 用例验证的确实是版本
     * 拒绝，而不是因为修改正文后沿用旧签名导致的普通验签失败。
     */
    private static String signedToken(
            MeetingCommandContext context,
            byte[] payload,
            byte[] secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        mac.update("czur.ai.meeting.page-token.v1".getBytes(StandardCharsets.US_ASCII));
        updateLengthPrefixed(mac, context.deploymentScope().getBytes(StandardCharsets.UTF_8));
        updateLengthPrefixed(mac,
                context.identity().subject().subjectScope().getBytes(StandardCharsets.UTF_8));
        updateLengthPrefixed(mac, payload);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(payload) + '.' + encoder.encodeToString(mac.doFinal());
    }

    private static void updateLengthPrefixed(Mac mac, byte[] value) {
        mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        mac.update(value);
    }

    private static MeetingCommandContext context(String deployment, String subject) {
        Instant issued = Instant.parse("2026-09-20T00:00:00Z");
        return new MeetingCommandContext(
                deployment,
                new VerifiedIdentity(
                        new Subject(subject),
                        Set.of("USER"),
                        Set.of("meeting:read"),
                        issued,
                        issued.plusSeconds(3600),
                        "credential-reference"));
    }
}

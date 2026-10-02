package com.czur.reference.application.realtime;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.port.RealtimeCredentialVerifier;
import com.czur.reference.application.port.RenewRealtimeTokenUnitOfWork;
import com.czur.reference.application.port.identity.SecretCredential;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.VerifiedIdentity;
import com.czur.reference.application.port.model.AudioProfile;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 03A 纯 Java 端口的脱敏和非法结果门禁；持久行为由后续 JDBC 门禁验证。 */
class RealtimeCredentialContractsTest {
    private static final String MEETING_ID = "mtg_realtime_contract";
    private static final String TOKEN = "realtime-secret-contract-value";

    @Test
    void verifierRequestDoesNotExposeCredentialAndCallerCanCloseIt() {
        SecretCredential credential = SecretCredential.copyOf(
                TOKEN.getBytes(StandardCharsets.US_ASCII));
        var request = new RealtimeCredentialVerifier.Request(
                MEETING_ID, MEETING_ID, credential);
        assertFalse(request.toString().contains(TOKEN));
        assertFalse(request.toString().contains(MEETING_ID));
        credential.close();
        assertTrue(credential.isDestroyed());
        assertThrows(IllegalStateException.class,
                () -> credential.useBytes(bytes -> bytes.length));
    }

    @Test
    void grantAndSuccessDoNotExposeTokenOrMeetingIdentity() {
        var grant = new RenewRealtimeTokenUnitOfWork.Grant(
                MEETING_ID, MEETING_ID, TOKEN, Instant.parse("2030-01-01T00:05:00Z"));
        String persistedJson = "{\"meetingId\":\"" + MEETING_ID
                + "\",\"sessionToken\":\"" + TOKEN + "\"}";
        var success = new RenewRealtimeTokenUnitOfWork.Success(
                RenewRealtimeTokenUnitOfWork.SuccessCode.CREATED, persistedJson);
        for (String diagnostic : new String[]{grant.toString(), success.toString()}) {
            assertFalse(diagnostic.contains(TOKEN));
            assertFalse(diagnostic.contains(MEETING_ID));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new RenewRealtimeTokenUnitOfWork.Grant(
                        MEETING_ID, "str_other", TOKEN,
                        Instant.parse("2030-01-01T00:05:00Z")));
        assertThrows(NullPointerException.class,
                () -> new RealtimeCredentialVerifier.Granted(
                        null, Instant.parse("2030-01-01T00:05:00Z"),
                        new RealtimeCredentialVerifier.AudioContract(
                                AudioProfile.pcm16kMono(100), 3_200)));
        assertThrows(NullPointerException.class,
                () -> new RealtimeCredentialVerifier.Granted(
                        RealtimeCredentialVerifier.AccessMode.WRITE,
                        Instant.parse("2030-01-01T00:05:00Z"), null));
        assertEquals(persistedJson, success.persistedGrantJson());
    }

    @Test
    void trustedAudioContractRequiresSupportedProfileAndACompleteNominalFrame() {
        var profile = AudioProfile.pcm16kMono(100);
        var contract = new RealtimeCredentialVerifier.AudioContract(profile, 3_200);
        assertEquals(profile, contract.audioProfile());
        assertEquals(3_200, contract.maxAudioFrameBytes());
        assertThrows(IllegalArgumentException.class,
                () -> new RealtimeCredentialVerifier.AudioContract(profile, 3_199));
        assertThrows(IllegalArgumentException.class,
                () -> new RealtimeCredentialVerifier.AudioContract(
                        AudioProfile.pcm16kMono(201), 6_432));
        assertThrows(IllegalArgumentException.class,
                () -> new RealtimeCredentialVerifier.AudioContract(
                        new AudioProfile(AudioProfile.AudioEncoding.UNKNOWN, 16_000, 1, 100),
                        3_200));
    }

    @Test
    void renewalRequestCarriesOnlyVerifiedContextAndOpaqueKey() {
        var identity = new VerifiedIdentity(new Subject("subject-contract"),
                Set.of(), Set.of(), Instant.parse("2030-01-01T00:00:00Z"),
                Instant.parse("2030-01-01T01:00:00Z"), null);
        var context = new MeetingCommandContext("deployment-contract", identity);
        var request = new RenewRealtimeTokenUnitOfWork.Request(
                context, MEETING_ID, "idem_realtime_123456");
        assertFalse(request.toString().contains(MEETING_ID));
        assertFalse(request.toString().contains("subject-contract"));
        assertFalse(request.toString().contains("idem_realtime_123456"));
        assertThrows(IllegalArgumentException.class,
                () -> new RenewRealtimeTokenUnitOfWork.Request(context, MEETING_ID, "short"));
    }
}

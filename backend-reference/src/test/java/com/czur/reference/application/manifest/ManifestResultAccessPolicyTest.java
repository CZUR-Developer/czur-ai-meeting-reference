package com.czur.reference.application.manifest;

import com.czur.reference.application.port.FinalizationFact;
import com.czur.reference.application.port.InputState;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingManifest;
import com.czur.reference.application.port.MeetingManifestPort;
import com.czur.reference.application.port.MeetingStatus;
import com.czur.reference.domain.meeting.MeetingState;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Manifest 结果策略的纯 Java 门禁，不启动数据库或 Web。
 *
 * <p>这里冻结 genTime、删除撤权、Pending 和完整状态的一致性边界；JDBC
 * 集成测试只负责证明同一 statement 能够正确提供策略所需的事实。</p>
 */
class ManifestResultAccessPolicyTest {
    private static final String MEETING = "mtg_policy_contract";
    private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final MeetingManifestPort.Request REQUEST =
            new MeetingManifestPort.Request(MEETING, NOW.toEpochMilli() - 1_000);

    @Test
    void pendingRequiresNoRevisionAndPermanentLinksNeverExpire() {
        ManifestResultAccessPolicy policy = new ManifestResultAccessPolicy(
                CLOCK, Duration.ofSeconds(10));
        MeetingStatus pending = status(
                MeetingState.FINALIZING, ManifestStatus.GENERATING, InputState.INPUT_ACCEPTED);

        MeetingManifestPort.Result result = policy.evaluate(REQUEST, pending, Optional.empty());
        assertEquals(MeetingManifestPort.Status.PENDING, result.status());
        assertEquals(MeetingManifestPort.Status.PENDING,
                policy.evaluate(new MeetingManifestPort.Request(MEETING, -1), pending,
                        Optional.empty()).status());

        assertEquals(MeetingManifestPort.Status.CONFLICT,
                policy.evaluate(REQUEST, pending, Optional.of(manifest(1, ManifestStatus.READY)))
                        .status());
    }

    @Test
    void deletionIsDeniedBeforeExpiredOrMalformedResultCanBeObserved() {
        ManifestResultAccessPolicy policy = new ManifestResultAccessPolicy(
                CLOCK, Duration.ofSeconds(10));
        MeetingStatus deleted = status(
                MeetingState.DELETION_PENDING, ManifestStatus.DELETION_PENDING,
                InputState.INPUT_ACCEPTED);

        assertEquals(MeetingManifestPort.Status.ACCESS_DENIED,
                policy.evaluate(REQUEST, deleted, Optional.empty()).status());
    }

    @Test
    void nonPermanentLinkExpiresAtTtlBoundary() {
        ManifestResultAccessPolicy policy = new ManifestResultAccessPolicy(
                CLOCK, Duration.ofSeconds(1));
        long exactlyExpired = NOW.minusSeconds(1).toEpochMilli();
        MeetingManifestPort.Request request = new MeetingManifestPort.Request(
                MEETING, exactlyExpired);

        assertEquals(MeetingManifestPort.Status.ACCESS_DENIED,
                policy.evaluate(request,
                        status(MeetingState.READY, ManifestStatus.READY, InputState.INPUT_ACCEPTED),
                        Optional.empty()).status());
    }

    @Test
    void completeManifestRequiresMatchingStatusIdentityProfileAndAcceptedInput() {
        ManifestResultAccessPolicy policy = new ManifestResultAccessPolicy(
                CLOCK, Duration.ofHours(1));
        MeetingManifest ready = manifest(1, ManifestStatus.READY);
        MeetingStatus status = status(MeetingState.READY, ManifestStatus.READY,
                InputState.INPUT_ACCEPTED);
        assertEquals(MeetingManifestPort.Status.FOUND,
                policy.evaluate(REQUEST, status, Optional.of(ready)).status());

        assertEquals(MeetingManifestPort.Status.CONFLICT,
                policy.evaluate(REQUEST,
                        status(MeetingState.READY_WITH_WARNINGS,
                                ManifestStatus.READY_WITH_WARNINGS, InputState.INPUT_ACCEPTED),
                        Optional.of(ready)).status());
        assertEquals(MeetingManifestPort.Status.CONFLICT,
                policy.evaluate(REQUEST,
                        status(MeetingState.READY, ManifestStatus.READY, InputState.INPUT_OPEN),
                        Optional.of(ready)).status());
        assertEquals(MeetingManifestPort.Status.CONFLICT,
                policy.evaluate(new MeetingManifestPort.Request("mtg_other", -1), status,
                        Optional.of(ready)).status());
    }

    @Test
    void rejectsNegativeTtl() {
        assertThrows(IllegalArgumentException.class,
                () -> new ManifestResultAccessPolicy(CLOCK, Duration.ofMillis(-1)));
    }

    private static MeetingStatus status(
            MeetingState state, ManifestStatus manifestState, InputState inputState) {
        return new MeetingStatus(
                MEETING,
                state,
                inputState == InputState.INPUT_ACCEPTED
                        ? FinalizationFact.accepted("receipt-policy")
                        : FinalizationFact.pending(inputState),
                manifestState,
                NOW);
    }

    private static MeetingManifest manifest(int revision, ManifestStatus state) {
        return new MeetingManifest(
                "0.1",
                MEETING,
                revision,
                new MeetingManifest.Profile("profile-policy", 3),
                null,
                state,
                NOW.minusSeconds(60),
                NOW,
                60_000,
                new MeetingManifest.Languages("zh-CN", List.of()),
                List.of(),
                List.of());
    }
}

package com.czur.reference.application.http;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.port.FinalizationFact;
import com.czur.reference.application.port.ManifestStatus;
import com.czur.reference.application.port.MeetingManifest;
import com.czur.reference.application.port.MeetingManifestPort;
import com.czur.reference.application.port.MeetingQueryPort;
import com.czur.reference.application.port.MeetingStatus;
import com.czur.reference.application.port.identity.Subject;
import com.czur.reference.application.port.identity.VerifiedIdentity;
import com.czur.reference.domain.meeting.MeetingState;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResultEmailEndpointTest {
    private static final String ID = "mtg_email_test";
    private final AtomicInteger sent = new AtomicInteger();
    private final AtomicInteger readManifest = new AtomicInteger();

    @Test void sendsOnlyAnOwnedReadyResultLinkAndRejectsWrongOrExpiredLinks() {
        ResultEmailEndpoint endpoint = endpoint(MeetingState.READY, true);
        long now = System.currentTimeMillis();
        String url = "https://result.example.test/index.html?meetingId=" + ID
                + "&genTime=" + now;
        assertEquals(200, endpoint.handle(request(url), context()).status());
        assertEquals(1, sent.get());
        assertEquals(1, readManifest.get());

        assertEquals(200, endpoint(MeetingState.FINALIZING, true)
                .handle(request(url), context()).status());
        assertEquals(2, sent.get());

        assertEquals(400, endpoint.handle(request(url.replace(ID, "mtg_other")),
                context()).status());
        assertEquals(2, sent.get());
        assertEquals(403, endpoint( MeetingState.READY, false).handle(request(url),
                context()).status());
        assertEquals(2, sent.get());
    }

    @Test void doesNotSendBeforeStopOrWithoutAuthenticatedContext() {
        String url = "https://result.example.test/index.html?meetingId=" + ID
                + "&genTime=" + System.currentTimeMillis();
        ResultEmailEndpoint endpoint = endpoint(MeetingState.RUNNING, true);
        assertEquals(401, endpoint.handle(request(url), null).status());
        assertEquals(409, endpoint.handle(request(url), context()).status());
        assertEquals(0, sent.get());
    }

    private ResultEmailEndpoint endpoint(MeetingState state, boolean linkValid) {
        MeetingQueryPort meetings = new MeetingQueryPort() {
            @Override public Result get(GetRequest request) {
                return new Found(new MeetingStatus(ID, state,
                        FinalizationFact.accepted("receipt"), ManifestStatus.READY,
                        Instant.now()));
            }
            @Override public ListResult list(ListRequest request) {
                throw new AssertionError("list not used");
            }
        };
        MeetingManifestPort manifest = request -> {
            readManifest.incrementAndGet();
            if (!linkValid) return new MeetingManifestPort.AccessDenied();
            Instant now = Instant.now();
            return new MeetingManifestPort.Found(new MeetingManifest("0.1", ID, 1,
                    new MeetingManifest.Profile("reference-asr", 1), "参考会议",
                    ManifestStatus.READY, now, now, 0,
                    new MeetingManifest.Languages("zh-CN", List.of()), List.of(), List.of()));
        };
        return new ResultEmailEndpoint(meetings, manifest, (recipients, url) -> {
            assertEquals(List.of("viewer@example.test"), recipients);
            sent.incrementAndGet();
        }, new HttpErrorMapper());
    }

    private static HttpRequest request(String url) {
        return new HttpRequest("POST", "/v1/meetings/" + ID + ":sendResultEmail",
                Map.of(), "{\"recipients\":[\"viewer@example.test\"],\"resultUrl\":\""
                        + url + "\"}");
    }

    private static MeetingCommandContext context() {
        Instant now = Instant.now();
        return new MeetingCommandContext("deployment", new VerifiedIdentity(
                new Subject("subject"), Set.of(), Set.of("meeting:create"),
                now.minusSeconds(60), now.plusSeconds(60), "credential"));
    }
}

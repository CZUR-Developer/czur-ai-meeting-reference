package com.czur.aimeeting.reference.meeting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.czur.aimeeting.model.TranscriptEvent;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONObject;
import org.junit.Test;

/** 用本机 HTTP 服务核对 P4-2 的 Create、启动和字幕提交正常路径。 */
public final class HttpReferenceMeetingClientTest {
    @Test
    public void createsStartsAndAppendsOneCanonicalTranscript() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(201)
                    .setBody("{\"meetingId\":\"mtg_test\",\"capabilityDecisions\":["
                            + "{\"capability\":\"transcript.realtime\",\"availability\":\"AVAILABLE\"},"
                            + "{\"capability\":\"translation.realtime\",\"availability\":\"UNAVAILABLE\","
                            + "\"reasonCode\":\"PROVIDER_CAPABILITY_MISSING\"},"
                            + "{\"capability\":\"summary.structured\",\"availability\":\"UNAVAILABLE\"}]}"));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
            server.enqueue(new MockResponse().setResponseCode(201).setBody("{}"));
            server.enqueue(new MockResponse().setResponseCode(200)
                    .setBody("{\"inputState\":\"INPUT_ACCEPTED\"}"));
            server.start();
            ReferenceMeetingClient client = new HttpReferenceMeetingClient(
                    server.url("/").toString(), "local-demo-token");
            CreatedMeeting created = client.createMeeting();
            String meetingId = created.meetingId();
            client.startClient(meetingId);
            client.appendTranscript(meetingId, 1,
                    new TranscriptEvent("mock-segment-1", 1, 0, 0,
                            "zh-CN", "今天我们讨论 AI", false));
            assertTrue(client.stopMeeting(meetingId));

            RecordedRequest createRequest = server.takeRequest();
            RecordedRequest startRequest = server.takeRequest();
            RecordedRequest appendRequest = server.takeRequest();
            RecordedRequest stopRequest = server.takeRequest();
            assertEquals("mtg_test", meetingId);
            assertTrue(created.capabilities().canUse(MeetingCapabilityState.TRANSCRIPT,
                    java.util.Set.of(MeetingCapabilityState.TRANSCRIPT)));
            assertTrue(!created.capabilities().canUse(MeetingCapabilityState.TRANSLATION,
                    java.util.Set.of(MeetingCapabilityState.TRANSLATION)));
            assertTrue(!created.capabilities().canUse(MeetingCapabilityState.SUMMARY,
                    java.util.Set.of()));
            assertEquals(List.of("/v1/meetings", "/v1/meetings/mtg_test:startClient",
                    "/v1/meetings/mtg_test/events:append"),
                    List.of(createRequest.getPath(), startRequest.getPath(),
                            appendRequest.getPath()));
            assertEquals("Bearer local-demo-token", createRequest.getHeader("Authorization"));
            assertEquals("Bearer local-demo-token", startRequest.getHeader("Authorization"));
            assertEquals("Bearer local-demo-token", appendRequest.getHeader("Authorization"));
            JSONObject create = new JSONObject(createRequest.getBody().readUtf8());
            assertEquals("reference-asr", create.getJSONObject("profile").getString("id"));
            assertEquals("transcript.realtime",
                    create.getJSONArray("requestedCapabilities").getString(0));
            assertTrue(startRequest.getBody().readUtf8().isEmpty());
            JSONObject append = new JSONObject(appendRequest.getBody().readUtf8());
            assertEquals("mock-segment-1-r1", append.getString("clientEventId"));
            assertEquals(1, append.getInt("clientSequence"));
            assertEquals("UPSERT_OPERATION_UPSERT",
                    append.getJSONObject("transcriptUpsert").getString("operation"));
            assertEquals("今天我们讨论 AI",
                    append.getJSONObject("transcriptUpsert").getString("text"));
            assertEquals("/v1/meetings/mtg_test:stop", stopRequest.getPath());
            assertEquals("Bearer local-demo-token", stopRequest.getHeader("Authorization"));
            assertTrue(stopRequest.getBody().readUtf8().isEmpty());
            assertTrue(stopRequest.getHeader("Idempotency-Key") != null);
        }
    }

    @Test
    public void requestsSeparateModelSessionGrantAfterCreate() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(201)
                    .setBody("{\"meetingId\":\"mtg_real\"}"));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{"
                    + "\"meetingId\":\"mtg_real\","
                    + "\"adapterId\":\"tingwu-realtime\","
                    + "\"endpoint\":\"wss://example.test/signed\","
                    + "\"expiresAt\":\"2030-01-01T00:01:00Z\","
                    + "\"initParams\":{\"taskId\":\"task-1\"}}"));
            server.start();
            HttpReferenceMeetingClient client = new HttpReferenceMeetingClient(
                    server.url("/").toString(), "local-demo-token");
            String meetingId = client.createMeeting().meetingId();
            var grant = client.requestModelSession(meetingId);
            assertEquals("mtg_real", meetingId);
            assertEquals("tingwu-realtime", grant.adapterId());
            assertEquals("task-1", grant.initParams().get("taskId"));
            assertTrue(grant.token() == null);
            assertTrue(!grant.toString().contains("wss://"));
            RecordedRequest createRequest = server.takeRequest();
            RecordedRequest grantRequest = server.takeRequest();
            assertEquals("/v1/meetings", createRequest.getPath());
            assertEquals("/v1/meetings/mtg_real:modelSession", grantRequest.getPath());
            assertEquals("Bearer local-demo-token", grantRequest.getHeader("Authorization"));
            assertTrue(grantRequest.getBody().readUtf8().isEmpty());
        }
    }
}

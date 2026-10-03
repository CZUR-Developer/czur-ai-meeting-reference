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
                    .setBody("{\"meetingId\":\"mtg_test\"}"));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
            server.enqueue(new MockResponse().setResponseCode(201).setBody("{}"));
            server.enqueue(new MockResponse().setResponseCode(200)
                    .setBody("{\"inputState\":\"INPUT_ACCEPTED\"}"));
            server.start();
            ReferenceMeetingClient client = new HttpReferenceMeetingClient(
                    server.url("/").toString(), "local-demo-token");
            String meetingId = client.createMeeting();
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
}

package com.czur.aimeeting.reference.meeting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import com.czur.aimeeting.model.TranscriptEvent;
import com.czur.aimeeting.model.TranslationEvent;
import java.util.List;
import java.util.Base64;
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
                    .setBody("{\"meetingId\":\"mtg_test\",\"limits\":{\"maxMeetingDurationMs\":600000},"
                            + "\"capabilityDecisions\":["
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
            CreatedMeeting created = client.createMeeting(
                    MeetingSetup.transcript("en-US", "https://result.example.test/index.html"));
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
            assertEquals(600000L, created.maxMeetingDurationMs());
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
            assertEquals("en-US", create.getJSONObject("languages").getString("source"));
            assertEquals(0, create.getJSONObject("languages").getJSONArray("targets").length());
            assertEquals("transcript.realtime",
                    create.getJSONArray("requestedCapabilities").getString(0));
            assertTrue(!create.getJSONObject("artifactPolicy").getBoolean("presentationCapture"));
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
                    .setBody("{\"meetingId\":\"mtg_real\",\"limits\":{\"maxMeetingDurationMs\":600000}}"));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{"
                    + "\"meetingId\":\"mtg_real\","
                    + "\"adapterId\":\"tingwu-realtime\","
                    + "\"endpoint\":\"wss://example.test/signed\","
                    + "\"expiresAt\":\"2030-01-01T00:01:00Z\","
                    + "\"initParams\":{\"taskId\":\"task-1\"}}"));
            server.start();
            HttpReferenceMeetingClient client = new HttpReferenceMeetingClient(
                    server.url("/").toString(), "local-demo-token");
            String meetingId = client.createMeeting(
                    MeetingSetup.transcript("zh-CN", "https://result.example.test/index.html"))
                    .meetingId();
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

    @Test
    public void serializesFutureModeSettingsWithoutInventingResultDeliveryFields() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(201)
                    .setBody("{\"meetingId\":\"mtg_translation\",\"limits\":{\"maxMeetingDurationMs\":600000}}"));
            server.start();
            HttpReferenceMeetingClient client = new HttpReferenceMeetingClient(
                    server.url("/").toString(), "local-demo-token");
            MeetingSetup setup = new MeetingSetup(MeetingSetup.Mode.LIVE_TRANSLATION,
                    "zh-CN", "en-US", true, MeetingSetup.ResultDelivery.WEB,
                    "https://result.example.test/index.html");
            client.createMeeting(setup);

            JSONObject request = new JSONObject(server.takeRequest().getBody().readUtf8());
            assertEquals("zh-CN", request.getJSONObject("languages").getString("source"));
            assertEquals("en-US", request.getJSONObject("languages")
                    .getJSONArray("targets").getString(0));
            assertEquals("translation.realtime",
                    request.getJSONArray("requestedCapabilities").getString(1));
            assertTrue(request.getJSONObject("artifactPolicy").getBoolean("presentationCapture"));
            assertTrue(!request.has("resultDelivery"));
        }
    }

    @Test
    public void translationUsesSameEventSequenceAndOpenApiShapeBeforeStop() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(201).setBody("{}"));
            server.enqueue(new MockResponse().setResponseCode(201).setBody("{}"));
            server.enqueue(new MockResponse().setResponseCode(201).setBody("{}"));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
            server.start();
            ReferenceMeetingClient client = new HttpReferenceMeetingClient(
                    server.url("/").toString(), "local-demo-token");
            client.appendTranscript("mtg_translation", 1, new TranscriptEvent(
                    "source-1", 1, 0, 100, "en-US", "Hello", true));
            client.appendTranslation("mtg_translation", 2, new TranslationEvent(
                    "translation-1", "source-1", 1, "en-US", "zh-CN", "你好", false));
            client.appendTranslation("mtg_translation", 3, new TranslationEvent(
                    "translation-1", "source-1", 2, "en-US", "zh-CN", "你好。", true));
            assertTrue(client.stopMeeting("mtg_translation"));

            JSONObject transcript = new JSONObject(server.takeRequest().getBody().readUtf8());
            RecordedRequest partialRequest = server.takeRequest();
            JSONObject partial = new JSONObject(partialRequest.getBody().readUtf8());
            JSONObject finalTranslation = new JSONObject(server.takeRequest().getBody().readUtf8());
            RecordedRequest stop = server.takeRequest();
            assertEquals(1, transcript.getInt("clientSequence"));
            assertEquals(2, partial.getInt("clientSequence"));
            assertEquals(3, finalTranslation.getInt("clientSequence"));
            assertEquals("translation:translation-1:r1", partial.getString("clientEventId"));
            assertEquals("translation:translation-1:r2",
                    finalTranslation.getString("clientEventId"));
            assertEquals("/v1/meetings/mtg_translation/events:append", partialRequest.getPath());
            assertEquals("Bearer local-demo-token", partialRequest.getHeader("Authorization"));
            JSONObject upsert = partial.getJSONObject("translationUpsert");
            assertEquals("translation-1", upsert.getString("translationId"));
            assertEquals("source-1", upsert.getString("sourceSegmentId"));
            assertEquals(1, upsert.getInt("revision"));
            assertEquals("UPSERT_OPERATION_UPSERT", upsert.getString("operation"));
            assertEquals("en-US", upsert.getString("sourceLanguage"));
            assertEquals("zh-CN", upsert.getString("targetLanguage"));
            assertEquals("你好", upsert.getString("text"));
            assertTrue(!upsert.getBoolean("final"));
            assertEquals(0, upsert.getJSONArray("supersedesTranslationIds").length());
            assertTrue(!partial.has("transcriptUpsert"));
            assertTrue(finalTranslation.getJSONObject("translationUpsert").getBoolean("final"));
            assertEquals("/v1/meetings/mtg_translation:stop", stop.getPath());
        }
    }

    @Test
    public void uploadsOrderedPresentationJpegsBeforeStop() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(201).setBody("{}"));
            server.enqueue(new MockResponse().setResponseCode(201).setBody("{}"));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
            server.start();
            ReferenceMeetingClient client = new HttpReferenceMeetingClient(
                    server.url("/").toString(), "local-demo-token");
            byte[] firstJpeg = {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9};
            byte[] secondJpeg = {(byte) 0xff, (byte) 0xd8, 2, (byte) 0xff, (byte) 0xd9};
            client.appendPresentation("mtg_slides", 1, 10_000L, firstJpeg);
            client.appendPresentation("mtg_slides", 2, 20_000L, secondJpeg);
            assertTrue(client.stopMeeting("mtg_slides"));

            for (int expected = 1; expected <= 2; expected++) {
                RecordedRequest request = server.takeRequest();
                assertEquals("/v1/meetings/mtg_slides/presentation-captures:append",
                        request.getPath());
                assertEquals("Bearer local-demo-token", request.getHeader("Authorization"));
                JSONObject body = new JSONObject(request.getBody().readUtf8());
                assertEquals(expected, body.getInt("chunkSequence"));
                assertEquals(1, body.getJSONArray("frames").length());
                JSONObject frame = body.getJSONArray("frames").getJSONObject(0);
                assertEquals(expected * 10_000L, frame.getLong("offsetMs"));
                assertArrayEquals(expected == 1 ? firstJpeg : secondJpeg,
                        Base64.getDecoder().decode(frame.getString("jpegBase64")));
            }
            assertEquals("/v1/meetings/mtg_slides:stop", server.takeRequest().getPath());
        }
    }
}

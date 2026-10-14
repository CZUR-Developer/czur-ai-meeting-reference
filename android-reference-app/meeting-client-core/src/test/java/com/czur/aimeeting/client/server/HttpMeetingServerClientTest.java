package com.czur.aimeeting.client.server;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.czur.aimeeting.client.ClientIdentity;
import com.czur.aimeeting.client.MeetingClientConfig;
import com.czur.aimeeting.client.MeetingOptions;
import com.czur.aimeeting.client.MeetingProfile;
import com.czur.aimeeting.client.MeetingResult;
import com.czur.aimeeting.client.PresentationFrame;
import com.czur.aimeeting.client.result.ResultOptions;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** 真实本机 HTTP；无 Android 或新增 HTTP 测试依赖，验证正常协议及类型化结果。 */
public final class HttpMeetingServerClientTest {
    @Test public void coordinatorDiscoveryUsesExistingAnonymousCapabilitiesProtocol() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.reply(200, "{\"capabilities\":[\"transcript.realtime\"],\"resultLinkTtlMs\":86400000,"
                    + "\"resultEmailEnabled\":true,\"languages\":[\"en-US\",\"ja-JP\"]}");
            DeploymentOptions options = fixture.client().discover();
            assertEquals(Set.of("transcript.realtime"), options.capabilities());
            assertEquals(86400000, options.resultLinkTtlMs());
            assertTrue(options.resultEmailEnabled());
            assertEquals(List.of("en-US", "ja-JP"), options.languages());
            Request request = fixture.request();
            assertEquals("/v1/capabilities", request.path());
            assertNull(request.authorization());
        }
    }

    @Test public void createUsesConfiguredClientAndProfileWithoutModelOrDeliveryFields() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.reply(201, "{\"meetingId\":\"mtg_config\",\"limits\":{\"maxMeetingDurationMs\":600000}}");
            MeetingClientConfig config = new MeetingClientConfig(fixture.url(), "test-token",
                    new ClientIdentity("partner.app", "2.0", "v1", "device-7"),
                    new MeetingProfile("partner-meeting", 3), Runnable::run);
            var created = new HttpMeetingServerClient(config).createMeeting(options());
            Request request = fixture.request();
            JSONObject body = new JSONObject(request.body());
            assertEquals("/v1/meetings", request.path());
            assertEquals("Bearer test-token", request.authorization());
            assertEquals("partner.app", body.getJSONObject("client").getString("appId"));
            assertEquals("2.0", body.getJSONObject("client").getString("appVersion"));
            assertEquals("v1", body.getJSONObject("client").getString("contractVersion"));
            assertEquals("device-7", body.getJSONObject("client").getString("deviceId"));
            assertEquals("partner-meeting", body.getJSONObject("profile").getString("id"));
            assertEquals(3, body.getJSONObject("profile").getInt("revision"));
            assertEquals("参考会议", body.getString("title"));
            assertEquals("Asia/Shanghai", body.getString("timezone"));
            assertFalse(body.getJSONObject("artifactPolicy").getBoolean("retainAudio"));
            assertFalse(body.has("endpoint"));
            assertFalse(body.has("resultDelivery"));
            assertEquals("mtg_config", created.meetingId());
            assertEquals(600000, created.maxMeetingDurationMs());
            assertFalse(config.toString().contains("test-token"));
        }
    }

    @Test public void createUsesFrozenParametersAndOmitsAnAbsentTitle() throws Exception {
        try (Fixture fixture = new Fixture()) {
            MeetingOptions selected = new MeetingOptions(MeetingOptions.Mode.LIVE_TRANSLATION,
                    "en-US", "zh-CN", true, ResultOptions.action("partner.web"),
                    "https://result.example.test/index.html", List.of("fixture@example.test"), true);
            fixture.reply(201, "{\"meetingId\":\"mtg_parameters\",\"limits\":{\"maxMeetingDurationMs\":600000}}");
            fixture.client().createMeeting(selected.withCreateParameters("客户会议", "Europe/Paris", true));
            Request request = fixture.request();
            JSONObject body = new JSONObject(request.body());
            assertEquals("/v1/meetings", request.path());
            assertEquals("Bearer test-token", request.authorization());
            assertEquals("客户会议", body.getString("title"));
            assertEquals("Europe/Paris", body.getString("timezone"));
            assertTrue(body.getJSONObject("artifactPolicy").getBoolean("retainAudio"));
            assertTrue(body.getJSONObject("artifactPolicy").getBoolean("presentationCapture"));
            assertEquals("en-US", body.getJSONObject("languages").getString("source"));
            assertEquals(List.of("zh-CN"), body.getJSONObject("languages").getJSONArray("targets").toList());
            assertEquals(selected.requestedCapabilities(), body.getJSONArray("requestedCapabilities").toList());
            assertEquals(Set.of("clientSessionId", "profile", "client", "title", "timezone",
                    "languages", "audioProfile", "requestedCapabilities", "artifactPolicy"), body.keySet());

            // null标题是现有协议的正常可省略字段，不替调用者重新填入参考标题。
            fixture.reply(201, "{\"meetingId\":\"mtg_untitled\",\"limits\":{\"maxMeetingDurationMs\":600000}}");
            fixture.client().createMeeting(selected.withCreateParameters(null, "Europe/Paris", false));
            JSONObject untitled = new JSONObject(fixture.request().body());
            assertFalse(untitled.has("title"));
            assertEquals("Europe/Paris", untitled.getString("timezone"));
            assertFalse(untitled.getJSONObject("artifactPolicy").getBoolean("retainAudio"));
        }
    }

    @Test public void modelEndpointAndInitParamsComeOnlyFromServer() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.reply(200, "{\"meetingId\":\"mtg_model\",\"adapterId\":\"partner-format\","
                    + "\"endpoint\":\"wss://model.example.test/signed\",\"expiresAt\":\"2030-01-01T00:00:00Z\","
                    + "\"initParams\":{\"taskId\":\"task-1\"}}");
            var grant = fixture.client().requestModelSession("mtg_model");
            assertEquals("wss://model.example.test/signed", grant.endpoint());
            assertEquals("partner-format", grant.adapterId());
            assertEquals("task-1", grant.initParams().get("taskId"));
            assertEquals("/v1/meetings/mtg_model:modelSession", fixture.request().path());
        }
    }

    @Test public void stopAcceptanceKeepsManifestStateSeparateAndReusesSameStopKey() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.reply(202, "{\"inputState\":\"INPUT_COMPLETE_PENDING\",\"manifestState\":\"GENERATING\"}");
            fixture.reply(200, "{\"inputState\":\"INPUT_ACCEPTED\",\"finalizationReceipt\":\"receipt-1\","
                    + "\"manifestState\":\"GENERATING\"}");
            HttpMeetingServerClient client = fixture.client();
            assertFalse(client.stopMeeting("mtg_stop").accepted());
            StopMeetingResponse accepted = client.stopMeeting("mtg_stop");
            assertTrue(accepted.accepted());
            assertEquals("GENERATING", accepted.manifestState());
            assertEquals("receipt-1", accepted.finalizationReceipt());
            assertEquals(fixture.request().stopKey(), fixture.request().stopKey());
        }
    }

    @Test public void frozenUsageUsesSameAuthenticatedBodyForFirstSubmitAndReplay() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String confirmation = "{\"meetingId\":\"mtg_usage\",\"audioDurationMs\":1000,\"accepted\":true}";
            fixture.reply(201, confirmation);
            fixture.reply(200, confirmation);
            HttpMeetingServerClient client = fixture.client();
            client.submitUsage("mtg_usage", 1000);
            client.submitUsage("mtg_usage", 1000);
            Request first = fixture.request();
            Request replay = fixture.request();
            assertEquals("/v1/meetings/mtg_usage/usage:submit", first.path());
            assertEquals(first.path(), replay.path());
            assertEquals(first.body(), replay.body());
            assertEquals(1000, new JSONObject(first.body()).getLong("audioDurationMs"));
            assertEquals("Bearer test-token", first.authorization());
            assertEquals(first.authorization(), replay.authorization());
        }
    }

    @Test public void readyMetadataDoesNotDownloadArtifactsForWebOrEmail() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.reply(200, "{\"status\":\"READY_WITH_WARNINGS\",\"title\":\"本场\",\"artifacts\":["
                    + "{\"type\":\"presentation.capture\",\"status\":\"READY\",\"artifactId\":\"art_slides\"},"
                    + "{\"type\":\"summary.structured\",\"status\":\"PENDING\"}]}");
            MeetingResult result = fixture.client().readResult("mtg_web", 123, false);
            assertEquals(MeetingResult.Status.READY_WITH_WARNINGS, result.status());
            assertEquals(Set.of("presentation.capture"), result.artifactTypes());
            assertNull(result.content());
            assertEquals("/v1/meetings/mtg_web/manifest?genTime=123", fixture.request().path());
            assertTrue(fixture.requests.isEmpty());
        }
    }

    @Test public void failedManifestWithHttp200IsNotReady() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.reply(200, "{\"status\":\"FAILED\",\"artifacts\":[]}");
            IOException failure = assertThrows(IOException.class,
                    () -> fixture.client().readResult("mtg_failed", 123, false));
            assertTrue(failure.getMessage().contains("FAILED"));
        }
    }

    @Test public void pendingManifestWithHttp202KeepsPollingUntilReady() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.reply(202, "{\"meetingId\":\"mtg_pending\",\"state\":\"FINALIZING\","
                    + "\"inputState\":\"INPUT_ACCEPTED\",\"finalizationReceipt\":\"receipt-1\","
                    + "\"manifestState\":\"GENERATING\",\"updatedAt\":\"2026-10-07T00:00:00Z\"}");
            fixture.reply(200, "{\"status\":\"READY\",\"artifacts\":[]}");
            MeetingResult result = fixture.client().readResult("mtg_pending", 123, false);
            assertEquals(MeetingResult.Status.READY, result.status());
            assertNull(result.content());
            assertEquals("/v1/meetings/mtg_pending/manifest?genTime=123", fixture.request().path());
            assertEquals("/v1/meetings/mtg_pending/manifest?genTime=123", fixture.request().path());
            assertTrue(fixture.requests.isEmpty());
        }
    }

    @Test public void typedFinalContentsKeepLanguageAssociationSummaryAndOrderedImages() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.reply(200, "{\"status\":\"READY\",\"title\":null,\"artifacts\":["
                    + "{\"type\":\"presentation.capture\",\"status\":\"READY\",\"revision\":2,\"artifactId\":\"art_slide2\"},"
                    + "{\"type\":\"transcript.final\",\"status\":\"READY\",\"revision\":1,\"artifactId\":\"art_text\"},"
                    + "{\"type\":\"translation.final\",\"status\":\"READY\",\"revision\":1,\"artifactId\":\"art_translation\"},"
                    + "{\"type\":\"summary.structured\",\"status\":\"READY\",\"revision\":1,\"artifactId\":\"art_summary\"},"
                    + "{\"type\":\"presentation.capture\",\"status\":\"READY\",\"revision\":1,\"artifactId\":\"art_slide1\"}]}");
            fixture.reply(200, "{\"language\":\"en-US\",\"text\":\"Hello\",\"segments\":[{\"segmentId\":\"s1\","
                    + "\"startMs\":100,\"endMs\":200,\"language\":\"en-US\",\"text\":\"Hello\",\"sessionSpeakerId\":\"speaker-1\"}]}");
            fixture.reply(200, "{\"translations\":[{\"translationId\":\"t1\",\"sourceSegmentId\":\"s1\","
                    + "\"sourceLanguage\":\"en-US\",\"targetLanguage\":\"zh-CN\",\"text\":\"你好\"}]}");
            fixture.reply(200, "{\"language\":\"en-US\",\"title\":\"Summary\",\"overview\":\"Discussed plan\","
                    + "\"keyPoints\":[\"Start\"],\"actionItems\":[\"Review\"]}");
            fixture.reply(200, "{\"frames\":[{\"offsetMs\":1000,\"jpegBase64\":\"AQI=\"}]}");
            fixture.reply(200, "{\"frames\":[{\"offsetMs\":2000,\"jpegBase64\":\"AwQ=\"}]}");
            MeetingResult result = fixture.client().readResult("mtg_content", 123, true);
            var content = result.content();
            assertEquals("参考会议", result.title());
            assertEquals("Hello", content.transcriptText());
            assertEquals("en-US", content.transcriptSegments().get(0).language());
            assertEquals("speaker-1", content.transcriptSegments().get(0).sessionSpeakerId());
            assertEquals("s1", content.translations().get(0).sourceSegmentId());
            assertEquals("zh-CN", content.translations().get(0).targetLanguage());
            assertEquals(List.of("Review"), content.summary().actionItems());
            assertEquals(1000, content.presentationFrames().get(0).offsetMs());
            assertArrayEquals(new byte[]{1, 2}, content.presentationFrames().get(0).jpeg());
            assertEquals(2000, content.presentationFrames().get(1).offsetMs());
            for (int index = 0; index < 6; index++) assertTrue(fixture.request().path().endsWith("?genTime=123"));
        }
    }

    @Test public void presentationFrameOwnsAnImmutableCopyAcrossAsyncUse() {
        byte[] jpeg = {1, 2};
        PresentationFrame frame = new PresentationFrame(1000, jpeg);
        jpeg[0] = 7;
        byte[] returned = frame.jpeg();
        returned[1] = 8;
        assertArrayEquals(new byte[]{1, 2}, frame.jpeg());
    }

    @Test public void presentationFirstSaveAndReplayRequireSameSingleFrameAck() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String ack = "{\"artifactId\":\"art_slide1\",\"chunkSequence\":1,\"frameCount\":1}";
            fixture.reply(201, ack);
            fixture.reply(200, ack);
            HttpMeetingServerClient client = fixture.client();
            byte[] jpeg = {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9};
            client.appendPresentation("mtg_slide", 1, 1000, jpeg);
            client.appendPresentation("mtg_slide", 1, 1000, jpeg);
            Request first = fixture.request();
            Request replay = fixture.request();
            assertEquals("/v1/meetings/mtg_slide/presentation-captures:append", first.path());
            assertEquals("Bearer test-token", first.authorization());
            assertEquals(first.body(), replay.body());
            JSONObject body = new JSONObject(first.body());
            assertEquals(1, body.getInt("chunkSequence"));
            assertEquals(1, body.getJSONArray("frames").length());
            assertEquals(1000, body.getJSONArray("frames").getJSONObject(0).getLong("offsetMs"));
        }
    }

    @Test public void presentationAckMustMatchChunkSingleFrameAndNonemptyArtifact() throws Exception {
        try (Fixture fixture = new Fixture()) {
            List<String> unconfirmed = List.of(
                    "{\"artifactId\":\"art_slide2\",\"chunkSequence\":2,\"frameCount\":1}",
                    "{\"artifactId\":\"art_slide1\",\"chunkSequence\":1,\"frameCount\":2}",
                    "{\"artifactId\":\" \",\"chunkSequence\":1,\"frameCount\":1}",
                    "{}");
            HttpMeetingServerClient client = fixture.client();
            for (String response : unconfirmed) {
                fixture.reply(200, response);
                assertThrows(IOException.class,
                        () -> client.appendPresentation("mtg_slide", 1, 1000, new byte[]{1}));
                assertEquals("/v1/meetings/mtg_slide/presentation-captures:append", fixture.request().path());
            }
        }
    }

    @Test public void presentationJsonOverExistingBudgetFailsBeforeHttpRequest() throws Exception {
        try (Fixture fixture = new Fixture()) {
            IOException failure = assertThrows(IOException.class,
                    () -> fixture.client().appendPresentation("mtg_slide", 1, 1000, new byte[786432]));
            assertTrue(failure.getMessage().contains("1MiB"));
            assertTrue("本地超限不得发送HTTP请求", fixture.requests.isEmpty());
        }
    }

    @Test public void presentationCanonicalSchemaOverBudgetAlsoFailsBeforeHttpRequest() throws Exception {
        try (Fixture fixture = new Fixture()) {
            byte[] jpeg = new byte[786381];
            jpeg[0] = (byte) 0xff;
            jpeg[1] = (byte) 0xd8;
            jpeg[jpeg.length - 2] = (byte) 0xff;
            jpeg[jpeg.length - 1] = (byte) 0xd9;
            JSONArray frames = new JSONArray().put(new JSONObject().put("offsetMs", 10000)
                    .put("jpegBase64", Base64.getEncoder().encodeToString(jpeg)));
            JSONObject request = new JSONObject().put("chunkSequence", 1).put("frames", frames);
            assertEquals(1048573, request.toString().getBytes(StandardCharsets.UTF_8).length);
            // HTTP 请求尚在1MiB内，但后端补 schemaVersion 后已超限；发送前必须拒绝。
            JSONObject canonical = new JSONObject().put("schemaVersion", "presentation-capture/v1")
                    .put("chunkSequence", 1).put("frames", frames);
            assertEquals(1048615, canonical.toString().getBytes(StandardCharsets.UTF_8).length);
            HttpMeetingServerClient.validatePresentationRequestSize(1, 10000, new byte[750000]);
            IOException failure = assertThrows(IOException.class,
                    () -> fixture.client().appendPresentation("mtg_slide", 1, 10000, jpeg));
            assertTrue(failure.getMessage().contains("1MiB"));
            assertTrue("保存正文超限也不得发送HTTP请求", fixture.requests.isEmpty());
        }
    }

    private static MeetingOptions options() {
        return new MeetingOptions(MeetingOptions.Mode.LIVE_TRANSCRIPT, "en-US", "zh-CN", false,
                MeetingOptions.ResultDelivery.WEB, "https://result.example.test/index.html", List.of());
    }
    private record Reply(int status, String body) { }
    private record Request(String path, String body, String authorization, String stopKey) { }
    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final BlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
        final BlockingQueue<Request> requests = new LinkedBlockingQueue<>();
        Fixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                requests.add(new Request(exchange.getRequestURI().toString(),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("Idempotency-Key")));
                Reply reply = replies.poll();
                byte[] body = (reply == null ? "{}" : reply.body()).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(reply == null ? 500 : reply.status(), body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
                exchange.close();
            });
            server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        HttpMeetingServerClient client() {
            return new HttpMeetingServerClient(MeetingClientConfig.reference(url(), "test-token"));
        }
        void reply(int status, String body) { replies.add(new Reply(status, body)); }
        Request request() throws InterruptedException {
            Request request = requests.poll(3, TimeUnit.SECONDS);
            assertNotNull("正常HTTP请求应到达", request);
            return request;
        }
        @Override public void close() { server.stop(0); }
    }
}

package com.czur.aimeeting.client.server;

import com.czur.aimeeting.client.ClientIdentity;
import com.czur.aimeeting.client.MeetingClientConfig;
import com.czur.aimeeting.client.MeetingOptions;
import com.czur.aimeeting.client.MeetingProfile;
import com.czur.aimeeting.client.MeetingResult;
import com.czur.aimeeting.client.MeetingResultContent;
import com.czur.aimeeting.client.FinalTranscriptSegment;
import com.czur.aimeeting.client.FinalTranslation;
import com.czur.aimeeting.client.PresentationFrame;
import java.util.HashSet;
import java.util.Set;

import com.czur.aimeeting.client.MeetingCapabilityState;

import com.czur.aimeeting.model.ModelSessionGrant;
import com.czur.aimeeting.model.SummaryResult;
import com.czur.aimeeting.model.SummarySessionGrant;
import com.czur.aimeeting.model.TranscriptEvent;
import com.czur.aimeeting.model.TranslationEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** 参考会议后端的最小 HTTP 实现；Token 由登录接口签发，不写入 APK 或日志。 */
public final class HttpMeetingServerClient implements MeetingServerClient {
    // 与参考后端 HttpRequest.MAX_BODY_BYTES 的现行 UTF-8 JSON 预算保持一致。
    // 不以 JPEG 原始大小替代传输大小，Base64、请求字段和服务端保存时补充的
    // schemaVersion 都计入预算；请求能通过不代表规范化保存正文也能通过。
    private static final int MAX_PRESENTATION_REQUEST_BYTES = 1_048_576;
    private final String baseUrl;
    private final String bearerToken;
    private final ClientIdentity identity;
    private final MeetingProfile profile;
    private final String stopKey = UUID.randomUUID().toString();

    public HttpMeetingServerClient(MeetingClientConfig config) {
        String url = config.serverUrl();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.bearerToken = config.accessToken();
        this.identity = config.clientIdentity();
        this.profile = config.profile();
    }

    @Override public DeploymentOptions discover() throws IOException {
        return new HttpMeetingSessionClient(baseUrl).deploymentOptions();
    }

    @Override
    public CreatedMeeting createMeeting(MeetingOptions setup) throws IOException {
        try {
            JSONObject body = new JSONObject()
                    .put("clientSessionId", UUID.randomUUID().toString())
                    .put("profile", new JSONObject().put("id", profile.id()).put("revision", profile.revision()))
                    .put("client", new JSONObject()
                            .put("appId", identity.appId())
                            .put("appVersion", identity.appVersion())
                            .put("contractVersion", identity.contractVersion())
                            .put("deviceId", identity.deviceId()))
                    .put("title", setup.title())
                    .put("timezone", setup.timezone())
                    .put("languages", new JSONObject().put("source", setup.sourceLanguage())
                            .put("targets", new JSONArray(setup.targetLanguages())))
                    .put("audioProfile", new JSONObject()
                            .put("encoding", "PCM_S16LE")
                            .put("sampleRateHz", 16000)
                            .put("channelCount", 1)
                            .put("frameDurationMs", 100))
                    .put("requestedCapabilities", new JSONArray(setup.requestedCapabilities()))
                    .put("artifactPolicy", new JSONObject()
                            .put("retainAudio", setup.retainAudio())
                            .put("presentationCapture", setup.presentationCapture()));
            String response = post("/v1/meetings", body.toString(),
                    UUID.randomUUID().toString(), 201).body;
            JSONObject created = new JSONObject(response);
            return new CreatedMeeting(created.getString("meetingId"), null,
                    MeetingCapabilityState.fromCreateResponse(created),
                    created.getJSONObject("limits").getLong("maxMeetingDurationMs"));
        } catch (JSONException invalidResponse) {
            throw new IOException("创建会议响应格式错误", invalidResponse);
        }
    }

    @Override
    public ModelSessionGrant requestModelSession(String meetingId) throws IOException {
        try {
            String response = post("/v1/meetings/" + meetingId + ":modelSession",
                    null, null, 200).body;
            JSONObject model = new JSONObject(response);
            Map<String, String> initParams = new HashMap<>();
            JSONObject params = model.getJSONObject("initParams");
            var keys = params.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                initParams.put(key, params.getString(key));
            }
            if (!meetingId.equals(model.getString("meetingId"))) {
                throw new IOException("模型会话与会议 ID 不一致");
            }
            return new ModelSessionGrant(meetingId,
                    model.getString("adapterId"), model.getString("endpoint"),
                    model.getString("expiresAt"), model.optString("token", null), initParams);
        } catch (JSONException invalidResponse) {
            throw new IOException("模型会话响应格式错误", invalidResponse);
        }
    }

    @Override public SummarySessionGrant requestSummarySession(String meetingId)
            throws IOException {
        try {
            JSONObject body = new JSONObject(post("/v1/meetings/" + meetingId
                    + ":summarySession", null, null, 200).body);
            if (!meetingId.equals(body.getString("meetingId"))) {
                throw new IOException("文本模型材料与会议 ID 不一致");
            }
            return new SummarySessionGrant(body.getString("adapterId"),
                    body.getString("endpoint"), body.getString("expiresAt"),
                    body.getString("token"));
        } catch (JSONException invalidResponse) {
            throw new IOException("文本模型材料响应格式错误", invalidResponse);
        }
    }

    @Override
    public void startClient(String meetingId) throws IOException {
        post("/v1/meetings/" + meetingId + ":startClient", null, null, 200);
    }

    @Override
    public void appendTranscript(String meetingId, int clientSequence, TranscriptEvent event)
            throws IOException {
        try {
            JSONObject transcript = new JSONObject()
                    .put("segmentId", event.segmentId())
                    .put("revision", event.revision())
                    .put("operation", "UPSERT_OPERATION_UPSERT")
                    .put("startMs", event.startMs())
                    .put("endMs", event.endMs())
                    .put("language", event.language())
                    .put("text", event.text())
                    .put("final", event.isFinal());
            if (event.sessionSpeakerId() != null) {
                transcript.put("speaker", new JSONObject()
                        .put("sessionSpeakerId", event.sessionSpeakerId()));
            }
            JSONObject body = new JSONObject()
                    .put("clientEventId", event.segmentId() + "-r" + event.revision())
                    .put("clientSequence", clientSequence)
                    .put("transcriptUpsert", transcript);
            post("/v1/meetings/" + meetingId + "/events:append", body.toString(), null,
                    200, 201);
        } catch (JSONException invalidEvent) {
            throw new IOException("字幕事件格式错误", invalidEvent);
        }
    }

    @Override
    public void appendTranslation(String meetingId, int clientSequence, TranslationEvent event)
            throws IOException {
        try {
            // 客户端事件 ID 与字幕命名空间分开，重试同一修订时仍保持同一个 ID。
            JSONObject body = new JSONObject()
                    .put("clientEventId", "translation:" + event.translationId()
                            + ":r" + event.revision())
                    .put("clientSequence", clientSequence)
                    .put("translationUpsert", new JSONObject()
                            .put("translationId", event.translationId())
                            .put("sourceSegmentId", event.sourceSegmentId())
                            .put("revision", event.revision())
                            .put("operation", "UPSERT_OPERATION_UPSERT")
                            .put("sourceLanguage", event.sourceLanguage())
                            .put("targetLanguage", event.targetLanguage())
                            .put("text", event.text())
                            .put("final", event.isFinal())
                            .put("supersedesTranslationIds", new JSONArray()));
            post("/v1/meetings/" + meetingId + "/events:append", body.toString(), null,
                    200, 201);
        } catch (JSONException invalidEvent) {
            throw new IOException("译文事件格式错误", invalidEvent);
        }
    }

    @Override
    public void appendPresentation(String meetingId, int chunkSequence, long offsetMs, byte[] jpeg)
            throws IOException {
        try {
            String body = presentationBody(chunkSequence, offsetMs, jpeg);
            JSONObject response = parseJson(post(
                    "/v1/meetings/" + meetingId + "/presentation-captures:append",
                    body, null, 200, 201).body);
            // 201（首次保存）和200（同包重放）都必须确认这一包的一帧已被接管。
            // 不把空JSON、其他包的ACK或缺失产物ID当保存成功，不回显响应正文。
            if (response.getInt("chunkSequence") != chunkSequence
                    || response.getInt("frameCount") != 1
                    || response.getString("artifactId").isBlank()) {
                throw new IOException("演示截图保存确认与当前包不一致");
            }
        } catch (JSONException invalidResponse) {
            throw new IOException("演示截图保存确认格式错误", invalidResponse);
        }
    }

    /**
     * 组合层在保留未确认帧前使用同一编码器预检实际 UTF-8 JSON 长度，
     * 同时计入服务端规范化保存正文的 schemaVersion 字段。
     * 此方法只做当前协议的1MiB预算检查，不发送HTTP、不分配序号、不保留图片。
     */
    public static void validatePresentationRequestSize(int chunkSequence, long offsetMs, byte[] jpeg)
            throws IOException {
        presentationBody(chunkSequence, offsetMs, jpeg);
    }

    private static String presentationBody(int chunkSequence, long offsetMs, byte[] jpeg)
            throws IOException {
        try {
            JSONObject frame = new JSONObject().put("offsetMs", offsetMs)
                    .put("jpegBase64", Base64.getEncoder().encodeToString(jpeg));
            JSONArray frames = new JSONArray().put(frame);
            String body = new JSONObject().put("chunkSequence", chunkSequence)
                    .put("frames", frames).toString();
            // 复用同一帧，按当前后端 decodePresentationChunk 的真实保存结构计量。
            // 不把 schemaVersion 发到 HTTP 请求里，也不为它另建预算或恢复机制。
            String canonical = new JSONObject().put("schemaVersion", "presentation-capture/v1")
                    .put("chunkSequence", chunkSequence).put("frames", frames).toString();
            if (body.getBytes(StandardCharsets.UTF_8).length > MAX_PRESENTATION_REQUEST_BYTES
                    || canonical.getBytes(StandardCharsets.UTF_8).length > MAX_PRESENTATION_REQUEST_BYTES) {
                throw new IOException("演示截图请求或保存JSON超过1MiB");
            }
            return body;
        } catch (JSONException invalidFrame) {
            throw new IOException("演示截图格式错误", invalidFrame);
        }
    }

    @Override public void submitUsage(String meetingId, long audioDurationMs) throws IOException {
        try {
            JSONObject response = parseJson(post("/v1/meetings/" + meetingId + "/usage:submit",
                    new JSONObject().put("audioDurationMs", audioDurationMs).toString(),
                    null, 201, 200).body);
            if (!meetingId.equals(response.getString("meetingId"))
                    || audioDurationMs != response.getLong("audioDurationMs")
                    || !response.getBoolean("accepted")) {
                throw new IOException("采音用量确认与本场冻结值不一致");
            }
        } catch (JSONException invalidResponse) {
            throw new IOException("采音用量响应格式错误", invalidResponse);
        }
    }

    @Override public void submitSummary(String meetingId, SummaryResult summary)
            throws IOException {
        try {
            JSONObject body = new JSONObject()
                    .put("language", summary.language())
                    .put("title", summary.title())
                    .put("overview", summary.overview())
                    .put("keyPoints", new JSONArray(summary.keyPoints()))
                    .put("actionItems", new JSONArray(summary.actionItems()));
            post("/v1/meetings/" + meetingId + "/summary:submit", body.toString(),
                    null, 200, 201);
        } catch (JSONException invalidSummary) {
            throw new IOException("标准纪要格式错误", invalidSummary);
        }
    }

    @Override
    public StopMeetingResponse stopMeeting(String meetingId) throws IOException {
        Response response = post("/v1/meetings/" + meetingId + ":stop", null, stopKey, 200, 202);
        JSONObject body = parseJson(response.body);
        return new StopMeetingResponse(response.status == 200,
                body.optString("inputState", response.status == 200
                        ? "INPUT_ACCEPTED" : "INPUT_COMPLETE_PENDING"),
                body.optString("finalizationReceipt", null),
                body.optString("manifestState", "NOT_STARTED"));
    }

    @Override public void sendResultEmail(String meetingId, List<String> recipients,
            String resultUrl) throws IOException {
        try {
            JSONObject body = new JSONObject().put("recipients", new JSONArray(recipients))
                    .put("resultUrl", resultUrl);
            post("/v1/meetings/" + meetingId + ":sendResultEmail", body.toString(),
                    null, 200);
        } catch (JSONException invalid) {
            throw new IOException("邮箱结果请求格式错误", invalid);
        }
    }

    @Override public MeetingResult readResult(String meetingId, long genTime, boolean includeContent)
            throws IOException {
        String query = "?genTime=" + genTime;
        String encodedMeetingId = URLEncoder.encode(meetingId, StandardCharsets.UTF_8);
        JSONObject manifest = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            Response response = get("/v1/meetings/" + encodedMeetingId + "/manifest" + query, 200, 202);
            if (response.status == 200) {
                JSONObject current = parseJson(response.body);
                String currentStatus = current.optString("status");
                if ("READY".equals(currentStatus) || "READY_WITH_WARNINGS".equals(currentStatus)) {
                    manifest = current;
                    break;
                }
                if ("FAILED".equals(currentStatus)) {
                    throw new IOException("本场结果未就绪：FAILED");
                }
            }
            try {
                Thread.sleep(1500L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("等待本场结果时被中断", interrupted);
            }
        }
        if (manifest == null) throw new IOException("本场结果尚未就绪，请稍后重试");
        String status = manifest.optString("status");
        String title = manifest.isNull("title") ? "参考会议"
                : manifest.optString("title", "参考会议");
        JSONArray artifacts = manifest.optJSONArray("artifacts");
        List<JSONObject> ready = new ArrayList<>();
        Set<String> types = new HashSet<>();
        if (artifacts != null) for (int index = 0; index < artifacts.length(); index++) {
            JSONObject ref = artifacts.optJSONObject(index);
            if (ref != null && "READY".equals(ref.optString("status"))) {
                ready.add(ref);
                types.add(ref.optString("type"));
            }
        }
        // 网页/邮箱只查清单，不为生成 PDF 而下载字幕或全部截图。
        if (!includeContent) return new MeetingResult(meetingId, title, genTime,
                MeetingResult.Status.valueOf(status), types, null);
        ready.sort(Comparator.comparingInt(ref -> ref.optInt("revision", 0)));
        String transcriptText = "";
        List<FinalTranscriptSegment> segments = new ArrayList<>();
        List<FinalTranslation> translations = new ArrayList<>();
        SummaryResult summary = null;
        List<PresentationFrame> frames = new ArrayList<>();
        try {
            for (JSONObject ref : ready) {
                String type = ref.optString("type");
                if (!List.of("transcript.final", "translation.final", "summary.structured",
                        "presentation.capture").contains(type)) continue;
                String artifactId = ref.optString("artifactId");
                if (artifactId.isBlank()) throw new IOException("READY 产物缺少 artifactId");
                JSONObject content = parseJson(get("/v1/artifacts/"
                        + URLEncoder.encode(artifactId, StandardCharsets.UTF_8) + query, 200).body);
                switch (type) {
                    case "transcript.final" -> {
                        transcriptText = content.optString("text");
                        segments.clear();
                        JSONArray values = content.optJSONArray("segments");
                        if (values != null) for (int index = 0; index < values.length(); index++) {
                            JSONObject value = values.getJSONObject(index);
                            segments.add(new FinalTranscriptSegment(value.optString("segmentId"),
                                    value.optLong("startMs"), value.optLong("endMs"),
                                    value.optString("language", content.optString("language")),
                                    value.optString("text"), value.optString("sessionSpeakerId", null)));
                        }
                    }
                    case "translation.final" -> {
                        translations.clear();
                        JSONArray values = content.optJSONArray("translations");
                        if (values != null) for (int index = 0; index < values.length(); index++) {
                            JSONObject value = values.getJSONObject(index);
                            translations.add(new FinalTranslation(value.optString("translationId"),
                                    value.optString("sourceSegmentId"), value.optString("sourceLanguage"),
                                    value.optString("targetLanguage"), value.optString("text")));
                        }
                    }
                    case "summary.structured" -> summary = new SummaryResult(
                            content.optString("language"), content.optString("title"),
                            content.optString("overview"), strings(content.optJSONArray("keyPoints")),
                            strings(content.optJSONArray("actionItems")));
                    case "presentation.capture" -> {
                        JSONArray values = content.optJSONArray("frames");
                        if (values != null) for (int index = 0; index < values.length(); index++) {
                            JSONObject value = values.getJSONObject(index);
                            String jpeg = value.optString("jpegBase64");
                            if (!jpeg.isEmpty()) frames.add(new PresentationFrame(value.optLong("offsetMs"),
                                    Base64.getDecoder().decode(jpeg)));
                        }
                    }
                    default -> { }
                }
            }
        } catch (JSONException | IllegalArgumentException invalidContent) {
            throw new IOException("本场最终产物格式错误", invalidContent);
        }
        return new MeetingResult(meetingId, title, genTime, MeetingResult.Status.valueOf(status),
                types, new MeetingResultContent(types, transcriptText, segments, translations, summary, frames));
    }

    private static List<String> strings(JSONArray values) throws JSONException {
        List<String> result = new ArrayList<>();
        if (values != null) for (int index = 0; index < values.length(); index++) {
            result.add(values.getString(index));
        }
        return result;
    }

    private static JSONObject parseJson(String body) throws IOException {
        try {
            return new JSONObject(body);
        } catch (JSONException invalid) {
            throw new IOException("本场结果 JSON 格式错误", invalid);
        }
    }

    private Response get(String path, int... accepted) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path)
                .openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            for (int expected : accepted) {
                if (status == expected) {
                    try (InputStream input = connection.getInputStream()) {
                        return new Response(status, readUtf8(input));
                    }
                }
            }
            throw new IOException("读取本场结果失败，HTTP " + status);
        } finally {
            connection.disconnect();
        }
    }

    private Response post(String path, String jsonBody, String idempotencyKey, int... accepted)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path)
                .openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
            connection.setRequestProperty("Accept", "application/json");
            if (idempotencyKey != null) {
                connection.setRequestProperty("Idempotency-Key", idempotencyKey);
            }
            byte[] bytes = jsonBody == null ? new byte[0]
                    : jsonBody.getBytes(StandardCharsets.UTF_8);
            if (jsonBody != null) {
                connection.setRequestProperty("Content-Type", "application/json");
            }
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (var output = connection.getOutputStream()) {
                output.write(bytes);
            }

            int status = connection.getResponseCode();
            for (int expected : accepted) {
                if (status == expected) {
                    try (InputStream input = connection.getInputStream()) {
                        return new Response(status, readUtf8(input));
                    }
                }
            }
            throw new IOException("后端请求失败，HTTP " + status);
        } finally {
            connection.disconnect();
        }
    }

    private static String readUtf8(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static final class Response {
        private final int status;
        private final String body;

        private Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}

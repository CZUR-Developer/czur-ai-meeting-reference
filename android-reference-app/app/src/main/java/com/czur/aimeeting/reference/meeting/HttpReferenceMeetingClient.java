package com.czur.aimeeting.reference.meeting;

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
public final class HttpReferenceMeetingClient implements ReferenceMeetingClient {
    private final String baseUrl;
    private final String bearerToken;
    private final String stopKey = UUID.randomUUID().toString();

    public HttpReferenceMeetingClient(String baseUrl, String bearerToken) {
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.bearerToken = bearerToken;
    }

    @Override
    public CreatedMeeting createMeeting(MeetingSetup setup) throws IOException {
        try {
            JSONObject body = new JSONObject()
                    .put("clientSessionId", UUID.randomUUID().toString())
                    .put("profile", new JSONObject().put("id", "reference-asr").put("revision", 1))
                    .put("client", new JSONObject()
                            .put("appId", "com.czur.aimeeting.reference")
                            .put("appVersion", "0.1.0")
                            .put("contractVersion", "v1")
                            .put("deviceId", "reference-android"))
                    .put("title", "参考会议")
                    .put("timezone", "Asia/Shanghai")
                    .put("languages", new JSONObject().put("source", setup.sourceLanguage())
                            .put("targets", new JSONArray(setup.targetLanguages())))
                    .put("audioProfile", new JSONObject()
                            .put("encoding", "PCM_S16LE")
                            .put("sampleRateHz", 16000)
                            .put("channelCount", 1)
                            .put("frameDurationMs", 100))
                    .put("requestedCapabilities", new JSONArray(setup.requestedCapabilities()))
                    .put("artifactPolicy", new JSONObject()
                            .put("retainAudio", false)
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
            JSONObject frame = new JSONObject()
                    .put("offsetMs", offsetMs)
                    .put("jpegBase64", Base64.getEncoder().encodeToString(jpeg));
            JSONObject body = new JSONObject()
                    .put("chunkSequence", chunkSequence)
                    .put("frames", new JSONArray().put(frame));
            post("/v1/meetings/" + meetingId + "/presentation-captures:append",
                    body.toString(), null, 200, 201);
        } catch (JSONException invalidFrame) {
            throw new IOException("演示截图格式错误", invalidFrame);
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
    public boolean stopMeeting(String meetingId) throws IOException {
        return post("/v1/meetings/" + meetingId + ":stop", null, stopKey,
                200, 202).status == 200;
    }

    @Override public LocalMeetingResult readLocalResult(String meetingId, long genTime)
            throws IOException {
        String query = "?genTime=" + genTime;
        String encodedMeetingId = URLEncoder.encode(meetingId, StandardCharsets.UTF_8);
        JSONObject manifest = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            Response response = get("/v1/meetings/" + encodedMeetingId + "/manifest" + query,
                    200, 202);
            if (response.status == 200) {
                manifest = parseJson(response.body);
                break;
            }
            try {
                Thread.sleep(1500L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("等待本场结果时被中断", interrupted);
            }
        }
        if (manifest == null) throw new IOException("本场结果尚未就绪，请稍后重试");
        JSONArray artifacts = manifest.optJSONArray("artifacts");
        JSONObject transcript = null, translation = null, summary = null;
        List<JSONObject> captures = new ArrayList<>();
        if (artifacts != null) {
            List<JSONObject> ready = new ArrayList<>();
            for (int index = 0; index < artifacts.length(); index++) {
                JSONObject ref = artifacts.optJSONObject(index);
                if (ref != null && "READY".equals(ref.optString("status"))) ready.add(ref);
            }
            ready.sort(Comparator.comparingInt(ref -> ref.optInt("revision", 0)));
            for (JSONObject ref : ready) {
                String type = ref.optString("type");
                if (!List.of("transcript.final", "translation.final", "summary.structured",
                        "presentation.capture").contains(type)) continue;
                String artifactId = ref.optString("artifactId");
                if (artifactId.isBlank()) throw new IOException("READY 产物缺少 artifactId");
                JSONObject content = parseJson(get("/v1/artifacts/"
                        + URLEncoder.encode(artifactId, StandardCharsets.UTF_8) + query,
                        200).body);
                switch (type) {
                    case "transcript.final" -> transcript = content;
                    case "translation.final" -> translation = content;
                    case "summary.structured" -> summary = content;
                    case "presentation.capture" -> captures.add(content);
                    default -> { }
                }
            }
        }
        return new LocalMeetingResult(manifest.optString("title", "参考会议"), transcript,
                translation, summary, captures);
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

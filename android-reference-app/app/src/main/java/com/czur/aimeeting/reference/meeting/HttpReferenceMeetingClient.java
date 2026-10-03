package com.czur.aimeeting.reference.meeting;

import com.czur.aimeeting.model.TranscriptEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

/** 本地演示后端的最小 HTTP 实现；Token 由页面输入，不写入 APK 或日志。 */
public final class HttpReferenceMeetingClient implements ReferenceMeetingClient {
    private final String baseUrl;
    private final String bearerToken;

    public HttpReferenceMeetingClient(String baseUrl, String bearerToken) {
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.bearerToken = bearerToken;
    }

    @Override
    public String createMeeting() throws IOException {
        try {
            JSONObject body = new JSONObject()
                    .put("clientSessionId", UUID.randomUUID().toString())
                    .put("profile", new JSONObject().put("id", "reference-asr").put("revision", 1))
                    .put("client", new JSONObject()
                            .put("appId", "com.czur.aimeeting.reference")
                            .put("appVersion", "0.1.0")
                            .put("contractVersion", "v1")
                            .put("deviceId", "reference-android"))
                    .put("title", "参考模拟会议")
                    .put("timezone", "Asia/Shanghai")
                    .put("languages", new JSONObject().put("source", "zh-CN")
                            .put("targets", new org.json.JSONArray()))
                    .put("audioProfile", new JSONObject()
                            .put("encoding", "PCM_S16LE")
                            .put("sampleRateHz", 16000)
                            .put("channelCount", 1)
                            .put("frameDurationMs", 100))
                    .put("requestedCapabilities", new org.json.JSONArray()
                            .put("transcript.realtime"))
                    .put("artifactPolicy", new JSONObject()
                            .put("retainAudio", false)
                            .put("presentationCapture", false));
            String response = post("/v1/meetings", body.toString(),
                    UUID.randomUUID().toString(), 201);
            return new JSONObject(response).getString("meetingId");
        } catch (JSONException invalidResponse) {
            throw new IOException("创建会议响应格式错误", invalidResponse);
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
            JSONObject body = new JSONObject()
                    .put("clientEventId", event.segmentId() + "-r" + event.revision())
                    .put("clientSequence", clientSequence)
                    .put("transcriptUpsert", new JSONObject()
                            .put("segmentId", event.segmentId())
                            .put("revision", event.revision())
                            .put("operation", "UPSERT_OPERATION_UPSERT")
                            .put("startMs", event.startMs())
                            .put("endMs", event.endMs())
                            .put("language", event.language())
                            .put("text", event.text())
                            .put("final", event.isFinal()));
            post("/v1/meetings/" + meetingId + "/events:append", body.toString(), null,
                    200, 201);
        } catch (JSONException invalidEvent) {
            throw new IOException("字幕事件格式错误", invalidEvent);
        }
    }

    private String post(String path, String jsonBody, String idempotencyKey, int... accepted)
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
                        return readUtf8(input);
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
}

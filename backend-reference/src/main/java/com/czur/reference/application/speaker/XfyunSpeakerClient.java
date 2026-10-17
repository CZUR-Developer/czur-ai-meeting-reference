package com.czur.reference.application.speaker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 讯飞声纹私有协议适配；签名、Base64 与供应商引用不进入公共会议端口。 */
final class XfyunSpeakerClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);
    private final ReferenceSpeakerIdentityConfig config;
    private final Clock clock;
    private final Transport transport;

    XfyunSpeakerClient(ReferenceSpeakerIdentityConfig config, Clock clock) {
        this(config, clock, jdkTransport());
    }

    XfyunSpeakerClient(ReferenceSpeakerIdentityConfig config, Clock clock, Transport transport) {
        this.config = config;
        this.clock = clock;
        this.transport = transport;
    }

    void ensureGroup(String groupId) throws Exception {
        try {
            invoke("createGroup", groupId, null, null);
        } catch (ProviderFailure failure) {
            if (!failure.alreadyExists) throw failure;
        }
    }

    void createFeature(String groupId, String featureId, byte[] sample) throws Exception {
        try {
            invoke("createFeature", groupId, featureId, sample);
        } catch (ProviderFailure failure) {
            // 同一会议发言人的稳定 featureId 支持正常请求重放，不产生第二个人物。
            if (!failure.alreadyExists) throw failure;
        }
    }

    Match identify(String groupId, byte[] sample) throws Exception {
        JsonNode response = invoke("searchFea", groupId, null, sample);
        Match best = null;
        for (JsonNode item : response.path("scoreList")) {
            if (item.path("featureId").isTextual() && item.path("score").isNumber()) {
                Match candidate = new Match(item.path("featureId").asText(), item.path("score").asDouble());
                if (best == null || candidate.score() > best.score()) best = candidate;
            }
        }
        return best;
    }

    private JsonNode invoke(String operation, String groupId, String featureId, byte[] sample) throws Exception {
        ObjectNode root = JSON.createObjectNode();
        root.putObject("header").put("app_id", config.appId()).put("status", 3);
        ObjectNode parameter = root.putObject("parameter").putObject(config.serviceId());
        parameter.put("func", operation).put("groupId", groupId);
        parameter.putObject(operation + "Res").put("encoding", "utf8")
                .put("compress", "raw").put("format", "json");
        if ("createGroup".equals(operation)) parameter.put("groupName", groupId);
        if (featureId != null) parameter.put("featureId", featureId);
        if ("searchFea".equals(operation)) parameter.put("topK", 1);
        if (sample != null) {
            root.putObject("payload").putObject("resource").put("encoding", "raw")
                    .put("sample_rate", 16000).put("channels", 1).put("bit_depth", 16)
                    .put("status", 3).put("audio", Base64.getEncoder().encodeToString(sample));
        }
        String date = HTTP_DATE.format(clock.instant());
        URI signed = sign(config, date);
        Response response = transport.post(signed, root.toString(), date, config.timeout());
        if (response.status() < 200 || response.status() >= 300) throw new ProviderFailure(false);
        JsonNode body = JSON.readTree(response.body());
        if (body.path("header").path("code").asInt(-1) != 0) {
            throw providerFailure(body.path("header").path("message").asText());
        }
        JsonNode payload = body.path("payload").path(operation + "Res");
        String text = payload.path("text").asText();
        if (text.isBlank()) {
            if ("searchFea".equals(operation)) throw new ProviderFailure(false);
            return JSON.createObjectNode();
        }
        JsonNode decoded = JSON.readTree(Base64.getDecoder().decode(text));
        if (decoded.path("code").asInt(0) != 0) {
            throw providerFailure(decoded.path("message").asText(decoded.path("desc").asText()));
        }
        return decoded;
    }

    /** 只保留是否已存在这一协议分支，供应商消息与签名 URL 不向日志／调用方传播。 */
    private static ProviderFailure providerFailure(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        return new ProviderFailure((lower.contains("exist") && !lower.contains("not"))
                || message.contains("已存在"));
    }

    static URI sign(ReferenceSpeakerIdentityConfig config, String date) throws Exception {
        String host = config.endpoint().getHost();
        String origin = "host: " + host + "\ndate: " + date
                + "\nPOST " + config.endpoint().getRawPath() + " HTTP/1.1";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(config.apiSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getEncoder().encodeToString(mac.doFinal(origin.getBytes(StandardCharsets.UTF_8)));
        String auth = "api_key=\"" + config.apiKey()
                + "\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"" + signature + "\"";
        return URI.create(config.endpoint() + "?authorization=" + encode(Base64.getEncoder()
                .encodeToString(auth.getBytes(StandardCharsets.UTF_8)))
                + "&host=" + encode(host) + "&date=" + encode(date));
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private static Transport jdkTransport() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return (uri, body, date, timeout) -> {
            var request = HttpRequest.newBuilder(uri).timeout(timeout)
                    .header("Content-Type", "application/json").header("Date", date)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            var result = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(result.statusCode(), result.body());
        };
    }

    @FunctionalInterface
    interface Transport {
        Response post(URI uri, String body, String date, Duration timeout) throws IOException, InterruptedException;
    }

    record Response(int status, String body) { }
    record Match(String featureId, double score) { }

    private static final class ProviderFailure extends IOException {
        private final boolean alreadyExists;
        ProviderFailure(boolean alreadyExists) {
            super("voiceprint provider request failed");
            this.alreadyExists = alreadyExists;
        }
    }
}

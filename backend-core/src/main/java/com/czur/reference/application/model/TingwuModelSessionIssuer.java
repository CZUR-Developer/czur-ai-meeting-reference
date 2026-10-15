package com.czur.reference.application.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 服务端按 ACS3 签名调用听悟实时 CreateTask，并只将 TaskId 与 MeetingJoinUrl
 * 转为 Android Adapter 所需的临时材料。RAM 密钥不进入响应，音频由 App 直连听悟。
 */
public final class TingwuModelSessionIssuer implements ModelSessionIssuer {
    private static final Logger LOGGER = Logger.getLogger(TingwuModelSessionIssuer.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter DATE = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
    private static final String QUERY = "type=realtime&RegionId=cn-beijing";
    private static final String SIGNED_HEADERS =
            "content-type;host;x-acs-action;x-acs-appkey;x-acs-date;x-acs-version";
    private final TingwuModelSessionConfig config;
    private final Transport transport;
    private final Clock clock;

    public TingwuModelSessionIssuer(TingwuModelSessionConfig config, Clock clock) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.config = Objects.requireNonNull(config, "config");
        this.transport = request -> {
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new ProviderResponse(response.statusCode(), response.body());
        };
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 测试注入单次传输；正式配置始终要求 HTTPS CreateTask URL。 */
    TingwuModelSessionIssuer(TingwuModelSessionConfig config, Transport transport, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public Session issue(Request sessionRequest) {
        try {
            URI target = URI.create(config.createTaskUrl() + "?" + QUERY);
            String body = createBody(sessionRequest);
            String date = DATE.format(clock.instant());
            String host = target.getHost() + (target.getPort() < 0 || target.getPort() == 443
                    ? "" : ":" + target.getPort());
            Map<String, String> headers = new TreeMap<>();
            headers.put("content-type", "application/json");
            headers.put("host", host);
            headers.put("x-acs-action", "CreateTask");
            headers.put("x-acs-appkey", config.appKey());
            headers.put("x-acs-date", date);
            headers.put("x-acs-version", "2023-09-30");
            StringBuilder canonicalHeaders = new StringBuilder();
            headers.forEach((key, value) -> canonicalHeaders.append(key).append(':')
                    .append(value).append('\n'));
            String canonical = "PUT\n" + target.getRawPath()
                    + "\nRegionId=cn-beijing&type=realtime\n" + canonicalHeaders
                    + "\n" + SIGNED_HEADERS + "\n" + sha256(body);
            String authorization = "ACS3-HMAC-SHA256 Credential=" + config.accessKeyId()
                    + ",SignedHeaders=" + SIGNED_HEADERS + ",Signature="
                    + hmac(config.accessKeySecret(), "ACS3-HMAC-SHA256\n" + sha256(canonical));
            HttpRequest request = HttpRequest.newBuilder(target)
                    .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                    .header("X-Acs-Action", "CreateTask")
                    .header("X-Acs-AppKey", config.appKey())
                    .header("X-Acs-Date", date).header("X-Acs-Version", "2023-09-30")
                    .header("Authorization", authorization)
                    .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            ProviderResponse response = transport.send(request);
            if (response.statusCode() != 200) throw new SessionIssueException();
            JsonNode data = JSON.readTree(response.body()).path("Data");
            String taskId = data.path("TaskId").asText("");
            String endpoint = data.path("MeetingJoinUrl").asText("");
            URI join = URI.create(endpoint);
            if (taskId.isBlank() || !"wss".equals(join.getScheme())
                    || join.getHost() == null) throw new SessionIssueException();
            LOGGER.log(Level.INFO, "听悟模型会话签发成功 adapter=tingwu-realtime translation={0}",
                    sessionRequest.translationEnabled());
            // 启动消息仍由设备 Adapter 生成；只有本场能力决策已开放翻译时，才把开关
            // 随临时会话材料交给它。字幕会议沿用原先只有 taskId 的材料形状。
            Map<String, String> initParams = sessionRequest.translationEnabled()
                    ? Map.of("taskId", taskId, "translationEnabled", "true")
                    : Map.of("taskId", taskId);
            return new Session("tingwu-realtime", endpoint,
                    clock.instant().plus(config.connectTtl()), initParams);
        } catch (IOException | GeneralSecurityException | IllegalArgumentException failure) {
            LOGGER.log(Level.WARNING, "听悟模型会话签发失败 reason=PROVIDER_UNAVAILABLE");
            throw new SessionIssueException();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new SessionIssueException();
        }
    }

    /**
     * Create 保存标准语言码；听悟 CreateTask 使用 cn/en/ja/ko/de/fr/ru。
     * 此映射与现行产品的听悟实现保持一致；部署开放哪些语种由公共配置另行声明。
     */
    private static String providerLanguage(String sourceLanguage) {
        return switch (sourceLanguage) {
            case "zh-CN" -> "cn";
            case "en-US" -> "en";
            case "ja-JP" -> "ja";
            case "ko-KR" -> "ko";
            case "de-DE" -> "de";
            case "fr-FR" -> "fr";
            case "ru-RU" -> "ru";
            default -> throw new SessionIssueException();
        };
    }

    /**
     * 单语字幕沿用原有 CreateTask 请求；仅本场翻译能力 ENABLE 时，以同一个
     * multilingual 实时任务识别双语并向两种语言翻译。双目标包含原语言，
     * 因为说话人可以在一场会中任意切换所选的两种语言。
     */
    private String createBody(Request request) throws IOException {
        String source = providerLanguage(request.sourceLanguage());
        Map<String, Object> input = Map.of("SourceLanguage", source, "Format", "pcm",
                "SampleRate", 16000, "EnableWords", true);
        // 会议发言人数未知；显式采用不限人数配置，而不只设置说话人分离开关。
        Map<String, Object> transcription = Map.of("OutputLevel", 2,
                "RealtimeDiarizationEnabled", true, "DiarizationEnabled", true,
                "Diarization", Map.of("SpeakerCount", 0));
        Map<String, Object> parameters = Map.of("Transcription", transcription);
        if (request.translationEnabled()) {
            if (request.targetLanguages().size() != 1) throw new SessionIssueException();
            String other = providerLanguage(request.targetLanguages().get(0));
            if (source.equals(other)) throw new SessionIssueException();
            List<String> pair = List.of(source, other);
            input = Map.of("SourceLanguage", "multilingual", "LanguageHints", pair,
                    "Format", "pcm", "SampleRate", 16000, "EnableWords", true);
            parameters = Map.of("Transcription", transcription,
                    "TranslationEnabled", true,
                    "Translation", Map.of("TargetLanguages", pair, "OutputLevel", 2));
        }
        return JSON.writeValueAsString(Map.of("AppKey", config.appKey(),
                "Input", input, "Parameters", parameters));
    }

    private static String sha256(String value) throws GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hmac(String key, String value) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    /** 仅固定类型供 HTTP 映射，绝不暴露厂商响应或异常消息。 */
    public static final class SessionIssueException extends RuntimeException {
        public SessionIssueException() { super("model session unavailable"); }
    }

    @FunctionalInterface interface Transport {
        ProviderResponse send(HttpRequest request) throws IOException, InterruptedException;
    }

    record ProviderResponse(int statusCode, String body) {}
}

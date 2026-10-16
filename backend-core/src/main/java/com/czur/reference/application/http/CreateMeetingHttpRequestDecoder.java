package com.czur.reference.application.http;

import com.czur.reference.application.command.CreateMeetingRequestFact;
import com.czur.reference.application.command.CreateMeetingRequestSnapshot;
import com.czur.reference.application.command.MeetingRequestCanonicalizer;
import com.czur.reference.application.port.identity.IdentityVerificationRequest;
import com.czur.reference.application.port.identity.SecretCredential;
import com.czur.reference.application.port.model.AudioProfile;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 仅解码统一协议的 Create HTTP 请求，不验证身份或开放路由。
 *
 * <p>请求体必须与公开 OpenAPI 的 CreateMeetingRequest 字段完全一致；Header 中的
 * 幂等键是唯一键来源。audience/clientId 只能由可信装配传入，不能取自请求体中的
 * client.appId。先完成全部非秘密字段校验，再创建短生命周期凭据；调用方必须把
 * 成功结果交给会关闭凭据的认证应用服务，或自行关闭 {@link Decoded#close()}。
 * 失败只抛固定文本，不暴露请求正文、Token、设备标识或幂等键。</p>
 */
public final class CreateMeetingHttpRequestDecoder {
    private static final String INVALID = "invalid Create HTTP request";
    private static final String INVALID_AUTHENTICATION = "invalid Create authentication";
    private static final JsonFactory JSON_FACTORY = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final ObjectMapper JSON = new ObjectMapper(JSON_FACTORY);
    private static final Set<String> ROOT_REQUIRED = Set.of("clientSessionId", "profile",
            "client", "timezone", "languages", "audioProfile", "requestedCapabilities",
            "artifactPolicy");

    private final String audience;
    private final String clientId;
    /** 空集合表示由调用方自行承担语言能力校验；正式参考装配传入部署声明。 */
    private final Set<String> declaredLanguages;

    /**
     * @param audience 可信服务端配置中的 Token 受众
     * @param clientId 可信服务端配置中的身份客户端标识；与 body 的 appId 无关
     */
    public CreateMeetingHttpRequestDecoder(String audience, String clientId) {
        this(audience, clientId, Set.of());
    }

    /**
     * 绑定本部署公开的标准语言码。Provider 的厂商短码转换仍在 Provider 内部，
     * HTTP Create 只接受已经由部署发现接口公开的标准码。
     */
    public CreateMeetingHttpRequestDecoder(String audience, String clientId,
            java.util.Collection<String> declaredLanguages) {
        this.audience = requireTrusted(audience);
        this.clientId = requireTrusted(clientId);
        Objects.requireNonNull(declaredLanguages, "declaredLanguages");
        this.declaredLanguages = Set.copyOf(declaredLanguages);
    }

    /**
     * 严格解码单个 POST /v1/meetings 请求；不调用身份、权益或持久化端口。
     *
     * @return 完整规范请求事实和待验证身份请求；持有的凭据须在调用栈末关闭
     * @throws IllegalArgumentException 缺失/重复/未知字段、类型、大小或凭据格式无效
     */
    public Decoded decode(HttpRequest request) {
        try {
            Objects.requireNonNull(request, "request");
            if (!"POST".equals(request.method()) || !"/v1/meetings".equals(request.path())
                    || !request.query().isEmpty()) {
                throw invalid();
            }
            // 先检查认证头形状，使缺失/畸形 Bearer 与坏业务输入同时出现时稳定
            // 分类为认证错误；实际 Token 有效性仍由身份 Provider 验证。
            String bearer = BearerAuthorization.tokenOrNull(request.header("authorization"));
            if (bearer == null) {
                throw invalidAuthentication();
            }
            requireJsonContentType(request.header("content-type"));
            String idempotencyKey = requireHeaderValue(request.header("idempotency-key"), 16, 128);
            String requestId = request.header("x-request-id");
            if (requestId != null) {
                requireHeaderValue(requestId, 0, 128);
            }
            CreateMeetingRequestSnapshot snapshot = decodeBody(request.body(), idempotencyKey);
            if (!declaredLanguages.isEmpty()
                    && (!declaredLanguages.contains(snapshot.languages().source())
                    || snapshot.languages().targets().stream()
                    .anyMatch(language -> !declaredLanguages.contains(language)))) {
                throw invalid();
            }
            CreateMeetingRequestFact fact = new CreateMeetingRequestFact(snapshot,
                    MeetingRequestCanonicalizer.digest(snapshot),
                    MeetingRequestCanonicalizer.CREATE_SNAPSHOT_SCHEMA_VERSION);

            // 创建凭据是最后一步；复制后立即清除本地字节数组。构造失败也要关闭。
            byte[] temporary = bearer.getBytes(StandardCharsets.US_ASCII);
            SecretCredential credential = null;
            try {
                credential = SecretCredential.copyOf(temporary);
                IdentityVerificationRequest verification = new IdentityVerificationRequest(
                        IdentityVerificationRequest.CredentialType.BEARER_TOKEN,
                        credential, audience, clientId);
                return new Decoded(verification, fact);
            } catch (RuntimeException failure) {
                if (credential != null) {
                    credential.close();
                }
                throw failure;
            } finally {
                Arrays.fill(temporary, (byte) 0);
            }
        } catch (DecodeFailure failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            // Parser 和值对象的异常可能包含原始值，公共边界不保留 cause。
            throw invalid();
        }
    }

    /**
     * 传输层仅据固定类别区分 401 与 400；不解析异常正文或保留秘密 cause。
     * 认证类别只用于缺失/畸形 Bearer，身份 Provider 的实际拒绝另由应用层映射。
     */
    public static final class DecodeFailure extends IllegalArgumentException {
        public enum Kind { AUTHENTICATION, CONTRACT }

        private final Kind kind;

        private DecodeFailure(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public Kind kind() { return kind; }
    }

    /** 成功解码结果不提供自动字段展开的 toString，避免泄露请求信息。 */
    public record Decoded(IdentityVerificationRequest verificationRequest,
            CreateMeetingRequestFact requestFact) implements AutoCloseable {
        public Decoded {
            Objects.requireNonNull(verificationRequest, "verificationRequest");
            Objects.requireNonNull(requestFact, "requestFact");
        }

        /** 在请求未交给认证应用服务或调用完成后幂等清除凭据。 */
        @Override public void close() { verificationRequest.credential().close(); }

        @Override public String toString() { return "CreateMeetingHttpRequestDecoder.Decoded[REDACTED]"; }
    }

    private static CreateMeetingRequestSnapshot decodeBody(String body, String idempotencyKey)
            throws IOException {
        try (JsonParser parser = JSON_FACTORY.createParser(body)) {
            JsonNode document = JSON.readTree(parser);
            if (parser.nextToken() != null) {
                throw invalid();
            }
            ObjectNode root = object(document);
            fields(root, ROOT_REQUIRED, Set.of("title"));
            ObjectNode profile = object(root.get("profile"));
            fields(profile, Set.of("id", "revision"), Set.of());
            ObjectNode client = object(root.get("client"));
            fields(client, Set.of("appId", "appVersion", "contractVersion", "deviceId"), Set.of());
            ObjectNode languages = object(root.get("languages"));
            fields(languages, Set.of("source", "targets"), Set.of());
            ObjectNode audio = object(root.get("audioProfile"));
            fields(audio, Set.of("encoding", "sampleRateHz", "channelCount", "frameDurationMs"),
                    Set.of());
            ObjectNode policy = object(root.get("artifactPolicy"));
            fields(policy, Set.of("retainAudio", "presentationCapture"), Set.of());

            String title = root.has("title") ? text(root, "title") : null;
            return new CreateMeetingRequestSnapshot(
                    text(root, "clientSessionId"),
                    new CreateMeetingRequestSnapshot.ProfileRef(text(profile, "id"),
                            integer(profile, "revision")),
                    new CreateMeetingRequestSnapshot.ClientDescriptor(text(client, "appId"),
                            text(client, "appVersion"), text(client, "contractVersion"),
                            text(client, "deviceId")),
                    title, text(root, "timezone"),
                    new CreateMeetingRequestSnapshot.LanguageSelection(text(languages, "source"),
                            stringArray(languages.get("targets"), false)),
                    new AudioProfile(AudioProfile.AudioEncoding.valueOf(text(audio, "encoding")),
                            integer(audio, "sampleRateHz"), integer(audio, "channelCount"),
                            integer(audio, "frameDurationMs")),
                    new HashSet<>(stringArray(root.get("requestedCapabilities"), true)),
                    new CreateMeetingRequestSnapshot.ArtifactPolicy(
                            bool(policy, "retainAudio"), bool(policy, "presentationCapture")),
                    idempotencyKey);
        }
    }

    private static void fields(ObjectNode node, Set<String> required, Set<String> optional) {
        if (node.size() < required.size() || !node.fieldNames().hasNext()) {
            throw invalid();
        }
        for (String name : required) {
            if (!node.has(name)) {
                throw invalid();
            }
        }
        var names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!required.contains(name) && !optional.contains(name)) {
                throw invalid();
            }
        }
    }

    private static ObjectNode object(JsonNode value) {
        if (!(value instanceof ObjectNode result)) { throw invalid(); }
        return result;
    }

    private static String text(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) { throw invalid(); }
        return value.textValue();
    }

    private static int integer(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid();
        }
        return value.intValue();
    }

    private static boolean bool(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) { throw invalid(); }
        return value.booleanValue();
    }

    private static List<String> stringArray(JsonNode value, boolean rejectDuplicates) {
        if (!(value instanceof ArrayNode array)) { throw invalid(); }
        List<String> result = new ArrayList<>(array.size());
        Set<String> seen = new HashSet<>();
        for (JsonNode item : array) {
            if (!item.isTextual() || (rejectDuplicates && !seen.add(item.textValue()))) {
                throw invalid();
            }
            result.add(item.textValue());
        }
        return result;
    }

    private static String requireTrusted(String value) {
        if (value == null || value.isBlank()) { throw new IllegalArgumentException("trusted identity setting is invalid"); }
        return value;
    }

    private static void requireJsonContentType(String value) {
        if (value == null || !value.matches("(?i)application/json(?:[ \\t]*;[ \\t]*charset=utf-8)?")) {
            throw invalid();
        }
    }

    private static String requireHeaderValue(String value, int minimum, int maximum) {
        if (value == null || value.codePointCount(0, value.length()) < minimum
                || value.codePointCount(0, value.length()) > maximum) {
            throw invalid();
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) { throw invalid(); }
        }
        return value;
    }

    private static DecodeFailure invalid() {
        return new DecodeFailure(DecodeFailure.Kind.CONTRACT, INVALID);
    }

    private static DecodeFailure invalidAuthentication() {
        return new DecodeFailure(DecodeFailure.Kind.AUTHENTICATION, INVALID_AUTHENTICATION);
    }
}

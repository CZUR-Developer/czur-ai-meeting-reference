package com.czur.reference.application.command;

import com.czur.reference.application.port.model.AudioProfile;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 统一协议的完整创建请求值快照。
 *
 * <p>本类型只冻结客户端提交的字段及 HTTP 幂等键，不代表请求已经通过身份、权益、
 * 能力或音频协商，也不代表这些字段已经持久化。它与当前较窄的
 * {@link CreateMeetingIntent} 并行存在，不能直接接入既有创建事务，否则重试时
 * 未进入摘要和事实源的字段可能被静默丢弃。待创建事务、重放事实和响应来源一同
 * 扩展后，才允许从本快照生成业务命令并开放 HTTP Create 路由。</p>
 *
 * <p>字符串按严格 UTF-8 校验，拒绝不成对的代理项和 CR/LF。集合在构造时复制并
 * 返回不可变视图；调试输出只给出标题是否存在及能力数量，不打印标题、设备标识、
 * 会话标识、语言、幂等键或原始请求正文。</p>
 */
public final class CreateMeetingRequestSnapshot {
    private final String clientSessionId;
    private final ProfileRef profile;
    private final ClientDescriptor client;
    private final String title;
    private final String timezone;
    private final LanguageSelection languages;
    private final AudioProfile audioProfile;
    private final Set<String> requestedCapabilities;
    private final ArtifactPolicy artifactPolicy;
    private final String idempotencyKey;

    /**
     * 构造一次完整的协议请求；不做授权或业务降级判断。
     *
     * @param title 可省略；提供时最多 256 个 Unicode 码点
     * @param idempotencyKey HTTP 头中的键，长度为 16～128 个 Unicode 码点
     */
    public CreateMeetingRequestSnapshot(
            String clientSessionId,
            ProfileRef profile,
            ClientDescriptor client,
            String title,
            String timezone,
            LanguageSelection languages,
            AudioProfile audioProfile,
            Set<String> requestedCapabilities,
            ArtifactPolicy artifactPolicy,
            String idempotencyKey) {
        this.clientSessionId = requireText("clientSessionId", clientSessionId);
        this.profile = Objects.requireNonNull(profile, "profile");
        this.client = Objects.requireNonNull(client, "client");
        this.title = title == null ? null : requireText("title", title, 1, 256);
        this.timezone = requireText("timezone", timezone);
        this.languages = Objects.requireNonNull(languages, "languages");
        this.audioProfile = requireOpenApiAudioProfile(audioProfile);
        this.requestedCapabilities = immutableCapabilities(requestedCapabilities);
        this.artifactPolicy = Objects.requireNonNull(artifactPolicy, "artifactPolicy");
        this.idempotencyKey = requireText("idempotencyKey", idempotencyKey, 16, 128);
    }

    public String clientSessionId() { return clientSessionId; }
    public ProfileRef profile() { return profile; }
    public ClientDescriptor client() { return client; }
    public String title() { return title; }
    public String timezone() { return timezone; }
    public LanguageSelection languages() { return languages; }
    public AudioProfile audioProfile() { return audioProfile; }
    public Set<String> requestedCapabilities() { return requestedCapabilities; }
    public ArtifactPolicy artifactPolicy() { return artifactPolicy; }
    public String idempotencyKey() { return idempotencyKey; }

    /** 日志安全摘要，不恢复 Java record 的默认字段输出。 */
    @Override
    public String toString() {
        return "CreateMeetingRequestSnapshot[titlePresent=" + (title != null)
                + ", capabilityCount=" + requestedCapabilities.size() + ']';
    }

    /** 客户端指定的产品档位引用；完整档位由服务端按此版本查找并冻结。 */
    public record ProfileRef(String id, int revision) {
        public ProfileRef {
            id = requireText("profile.id", id);
            if (revision <= 0) {
                throw new IllegalArgumentException("profile.revision must be positive");
            }
        }

        @Override public String toString() { return "ProfileRef[redacted=true]"; }
    }

    /** 客户端和设备描述；设备标识不代替身份 Provider 已验证的主体。 */
    public record ClientDescriptor(
            String appId, String appVersion, String contractVersion, String deviceId) {
        public ClientDescriptor {
            appId = requireText("client.appId", appId);
            appVersion = requireText("client.appVersion", appVersion);
            contractVersion = requireText("client.contractVersion", contractVersion);
            deviceId = requireText("client.deviceId", deviceId);
        }

        @Override public String toString() { return "ClientDescriptor[redacted=true]"; }
    }

    /** 源语言和目标语言；目标集合允许为空，表示不请求翻译。 */
    public record LanguageSelection(String source, List<String> targets) {
        public LanguageSelection {
            source = requireText("languages.source", source);
            Objects.requireNonNull(targets, "languages.targets");
            TreeSet<String> unique = new TreeSet<>();
            for (String target : targets) {
                String valid = requireText("languages.targets item", target);
                if (!unique.add(valid)) {
                    throw new IllegalArgumentException("languages.targets contains duplicate");
                }
            }
            targets = List.copyOf(targets);
        }

        @Override public String toString() { return "LanguageSelection[targetCount="
                + targets.size() + ']'; }
    }

    /** 客户端请求的产物策略；实际允许范围仍由服务端 Profile 和授权决定。 */
    public record ArtifactPolicy(boolean retainAudio, boolean presentationCapture) {
    }

    private static AudioProfile requireOpenApiAudioProfile(AudioProfile value) {
        Objects.requireNonNull(value, "audioProfile");
        if (value.encoding() != AudioProfile.AudioEncoding.PCM_S16LE
                || value.sampleRateHz() != 16_000 || value.channelCount() != 1
                || value.frameDurationMs() < 20 || value.frameDurationMs() > 200) {
            throw new IllegalArgumentException("audioProfile is outside OpenAPI limits");
        }
        return value;
    }

    private static Set<String> immutableCapabilities(Set<String> values) {
        Objects.requireNonNull(values, "requestedCapabilities");
        TreeSet<String> copy = new TreeSet<>();
        for (String value : values) {
            copy.add(requireText("requestedCapabilities item", value));
        }
        return Collections.unmodifiableSet(copy);
    }

    private static String requireText(String name, String value) {
        return requireText(name, value, 1, Integer.MAX_VALUE);
    }

    private static String requireText(String name, String value, int minimum, int maximum) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(name + " contains line break");
        }
        strictUtf8(name, value);
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints < minimum || codePoints > maximum) {
            throw new IllegalArgumentException(name + " length is invalid");
        }
        return value;
    }

    private static void strictUtf8(String name, String value) {
        try {
            StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException(name + " is not valid Unicode");
        }
    }
}

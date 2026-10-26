package com.czur.reference.protocol.json;

import com.czur.reference.application.command.CreateMeetingRequestSnapshot;
import com.czur.reference.application.command.CreateMeetingResponseFact;
import com.czur.reference.application.port.model.AudioProfile;
import com.czur.reference.domain.capability.CapabilityDecision;
import com.czur.reference.domain.meeting.MeetingState;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 完整 Create 成功响应事实的独立 canonical JSON 编解码器。
 *
 * <p>该 schema 与旧幂等结果和 {@code MeetingCreateOutcomeJson} 完全隔离，固定
 * 保存首版 {@code CREATED} 响应所需的会议标识、Profile、授权能力、完整能力决策、
 * 音频、实时会话和限制。每个对象均要求精确字段集合，集合/决策以稳定顺序编码，
 * 不允许未知或重复字段、类型强制转换、尾逗号、数值溢出和不合法 Unicode。</p>
 *
 * <p>实时 session token 属于事实正文但不属于诊断信息。解析失败时统一抛出固定
 * 消息，不保留原始 JSON、URI、token、会议标识或 Provider 文本。</p>
 */
public final class CreateMeetingResponseFactJson {
    /** Create 响应事实的首版独立 JSON schema。 */
    public static final int SCHEMA_VERSION = 1;
    private static final String ENCODE_FAILURE = "Create response fact JSON encoding failed";

    private CreateMeetingResponseFactJson() {
    }

    /** 按固定字段顺序编码为 canonical JSON 文本。 */
    public static String encode(CreateMeetingResponseFact fact) {
        try {
            Objects.requireNonNull(fact, "fact");
            StringBuilder json = new StringBuilder(2048).append('{');
            field(json, "schemaVersion").append(SCHEMA_VERSION);
            comma(json);
            field(json, "meetingId").append(CreateFactJsonSupport.quote(fact.meetingId()));
            comma(json);
            field(json, "state").append(CreateFactJsonSupport.quote(fact.state().name()));
            comma(json);
            field(json, "acceptedProfile");
            encodeProfile(json, fact.acceptedProfile());
            comma(json);
            field(json, "grantedCapabilities");
            encodeStrings(json, fact.grantedCapabilities());
            comma(json);
            field(json, "capabilityDecisions");
            encodeDecisions(json, fact.capabilityDecisions());
            comma(json);
            field(json, "acceptedAudioProfile");
            encodeAudio(json, fact.acceptedAudioProfile());
            comma(json);
            field(json, "realtime");
            encodeRealtime(json, fact.realtime());
            comma(json);
            field(json, "limits");
            encodeLimits(json, fact.limits());
            return json.append('}').toString();
        } catch (RuntimeException failure) {
            throw new IllegalStateException(ENCODE_FAILURE);
        }
    }

    /** 编码为严格 UTF-8 字节。 */
    public static byte[] encodeUtf8(CreateMeetingResponseFact fact) {
        return encode(fact).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 解码 JSON 文本；输入顺序可任意，重新编码时恢复 canonical 顺序。 */
    public static CreateMeetingResponseFact decode(String json) {
        try {
            CreateFactJsonSupport.Reader reader = new CreateFactJsonSupport.Reader(json);
            reader.expect('{');
            Integer schemaVersion = null;
            String meetingId = null;
            MeetingState state = null;
            CreateMeetingRequestSnapshot.ProfileRef profile = null;
            Set<String> granted = null;
            List<CapabilityDecision> decisions = null;
            AudioProfile audio = null;
            CreateMeetingResponseFact.RealtimeSession realtime = null;
            CreateMeetingResponseFact.Limits limits = null;
            boolean schemaSeen = false;
            boolean meetingSeen = false;
            boolean stateSeen = false;
            boolean profileSeen = false;
            boolean grantedSeen = false;
            boolean decisionsSeen = false;
            boolean audioSeen = false;
            boolean realtimeSeen = false;
            boolean limitsSeen = false;
            while (!reader.nextIs('}')) {
                String key = reader.readString();
                reader.expect(':');
                switch (key) {
                    case "schemaVersion" -> {
                        requireOnce(schemaSeen);
                        schemaSeen = true;
                        schemaVersion = reader.readInt();
                    }
                    case "meetingId" -> {
                        requireOnce(meetingSeen);
                        meetingSeen = true;
                        meetingId = reader.readString();
                    }
                    case "state" -> {
                        requireOnce(stateSeen);
                        stateSeen = true;
                        state = readEnum(reader.readString(), MeetingState.class);
                    }
                    case "acceptedProfile" -> {
                        requireOnce(profileSeen);
                        profileSeen = true;
                        profile = decodeProfile(reader);
                    }
                    case "grantedCapabilities" -> {
                        requireOnce(grantedSeen);
                        grantedSeen = true;
                        granted = readStringSet(reader);
                    }
                    case "capabilityDecisions" -> {
                        requireOnce(decisionsSeen);
                        decisionsSeen = true;
                        decisions = decodeDecisions(reader);
                    }
                    case "acceptedAudioProfile" -> {
                        requireOnce(audioSeen);
                        audioSeen = true;
                        audio = decodeAudio(reader);
                    }
                    case "realtime" -> {
                        requireOnce(realtimeSeen);
                        realtimeSeen = true;
                        realtime = decodeRealtime(reader);
                    }
                    case "limits" -> {
                        requireOnce(limitsSeen);
                        limitsSeen = true;
                        limits = decodeLimits(reader);
                    }
                    default -> throw CreateFactJsonSupport.invalid();
                }
                commaOrEnd(reader);
            }
            reader.expect('}');
            reader.ensureEnd();
            if (!schemaSeen || !meetingSeen || !stateSeen || !profileSeen || !grantedSeen
                    || !decisionsSeen || !audioSeen || !realtimeSeen || !limitsSeen
                    || schemaVersion != SCHEMA_VERSION) {
                throw CreateFactJsonSupport.invalid();
            }
            return new CreateMeetingResponseFact(meetingId, state, profile, granted, decisions,
                    audio, realtime, limits);
        } catch (RuntimeException malformed) {
            throw CreateFactJsonSupport.invalid();
        }
    }

    /** 先严格解码 UTF-8，再执行与 String 入口相同的字段校验。 */
    public static CreateMeetingResponseFact decode(byte[] json) {
        try {
            return decode(CreateFactJsonSupport.decodeUtf8(json));
        } catch (RuntimeException malformed) {
            throw CreateFactJsonSupport.invalid();
        }
    }

    /** 与其它 protocol JSON codec 一致的严格 UTF-8 字节入口别名。 */
    public static CreateMeetingResponseFact decodeUtf8(byte[] json) {
        return decode(json);
    }

    private static void encodeProfile(StringBuilder json, CreateMeetingRequestSnapshot.ProfileRef value) {
        json.append('{');
        field(json, "id").append(CreateFactJsonSupport.quote(value.id()));
        comma(json);
        field(json, "revision").append(value.revision());
        json.append('}');
    }

    private static void encodeStrings(StringBuilder json, Iterable<String> values) {
        List<String> sorted = new ArrayList<>();
        for (String value : values) {
            sorted.add(value);
        }
        sorted.sort(CreateMeetingResponseFactJson::compareUtf8);
        json.append('[');
        for (int index = 0; index < sorted.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append(CreateFactJsonSupport.quote(sorted.get(index)));
        }
        json.append(']');
    }

    private static void encodeDecisions(StringBuilder json, List<CapabilityDecision> values) {
        List<CapabilityDecision> sorted = new ArrayList<>(values);
        sorted.sort((left, right) -> compareUtf8(left.capability(), right.capability()));
        json.append('[');
        for (int index = 0; index < sorted.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            CapabilityDecision value = sorted.get(index);
            json.append('{');
            field(json, "capability").append(CreateFactJsonSupport.quote(value.capability()));
            comma(json);
            field(json, "availability")
                    .append(CreateFactJsonSupport.quote(value.availability().name()));
            comma(json);
            field(json, "requiredByProfile").append(value.requiredByProfile());
            comma(json);
            field(json, "action").append(CreateFactJsonSupport.quote(value.action().name()));
            comma(json);
            field(json, "fallbackCapability");
            appendNullable(json, value.fallbackCapability());
            comma(json);
            field(json, "affectedWorkflows");
            encodeOrderedStrings(json, value.affectedWorkflows());
            comma(json);
            field(json, "producedArtifacts");
            encodeOrderedStrings(json, value.producedArtifacts());
            comma(json);
            field(json, "warningCode");
            appendNullable(json, value.warningCode());
            comma(json);
            field(json, "planRevision").append(value.planRevision());
            json.append('}');
        }
        json.append(']');
    }

    private static void encodeOrderedStrings(StringBuilder json, List<String> values) {
        json.append('[');
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append(CreateFactJsonSupport.quote(values.get(index)));
        }
        json.append(']');
    }

    private static void encodeAudio(StringBuilder json, AudioProfile value) {
        json.append('{');
        field(json, "encoding").append(CreateFactJsonSupport.quote(value.encoding().name()));
        comma(json);
        field(json, "sampleRateHz").append(value.sampleRateHz());
        comma(json);
        field(json, "channelCount").append(value.channelCount());
        comma(json);
        field(json, "frameDurationMs").append(value.frameDurationMs());
        json.append('}');
    }

    private static void encodeRealtime(StringBuilder json, CreateMeetingResponseFact.RealtimeSession value) {
        json.append('{');
        field(json, "url").append(CreateFactJsonSupport.quote(value.url().toString()));
        comma(json);
        field(json, "subprotocol").append(CreateFactJsonSupport.quote(value.subprotocol()));
        comma(json);
        field(json, "sessionToken").append(CreateFactJsonSupport.quote(value.sessionToken()));
        comma(json);
        field(json, "expiresAt").append(CreateFactJsonSupport.quote(value.expiresAt().toString()));
        json.append('}');
    }

    private static void encodeLimits(StringBuilder json, CreateMeetingResponseFact.Limits value) {
        json.append('{');
        field(json, "maxMeetingDurationMs").append(value.maxMeetingDurationMs());
        comma(json);
        field(json, "maxAudioFrameBytes").append(value.maxAudioFrameBytes());
        comma(json);
        field(json, "eventReplayWindow").append(value.eventReplayWindow());
        comma(json);
        field(json, "maxReplayAudioMs");
        if (value.maxReplayAudioMs() == null) {
            json.append("null");
        } else {
            json.append(value.maxReplayAudioMs());
        }
        comma(json);
        field(json, "heartbeatIntervalMs").append(value.heartbeatIntervalMs());
        json.append('}');
    }

    private static CreateMeetingRequestSnapshot.ProfileRef decodeProfile(CreateFactJsonSupport.Reader reader) {
        reader.expect('{');
        String id = null;
        int revision = 0;
        boolean idSeen = false;
        boolean revisionSeen = false;
        while (!reader.nextIs('}')) {
            String key = reader.readString();
            reader.expect(':');
            switch (key) {
                case "id" -> {
                    requireOnce(idSeen);
                    idSeen = true;
                    id = reader.readString();
                }
                case "revision" -> {
                    requireOnce(revisionSeen);
                    revisionSeen = true;
                    revision = reader.readInt();
                }
                default -> throw CreateFactJsonSupport.invalid();
            }
            commaOrEnd(reader);
        }
        reader.expect('}');
        if (!idSeen || !revisionSeen) {
            throw CreateFactJsonSupport.invalid();
        }
        return new CreateMeetingRequestSnapshot.ProfileRef(id, revision);
    }

    private static List<CapabilityDecision> decodeDecisions(CreateFactJsonSupport.Reader reader) {
        reader.expect('[');
        List<CapabilityDecision> values = new ArrayList<>();
        if (!reader.nextIs(']')) {
            while (true) {
                values.add(decodeDecision(reader));
                if (reader.nextIs(']')) {
                    break;
                }
                reader.consume(',');
                if (reader.nextIs(']')) {
                    throw CreateFactJsonSupport.invalid();
                }
            }
        }
        reader.expect(']');
        return values;
    }

    private static CapabilityDecision decodeDecision(CreateFactJsonSupport.Reader reader) {
        reader.expect('{');
        String capability = null;
        CapabilityDecision.Availability availability = null;
        boolean requiredByProfile = false;
        CapabilityDecision.Action action = null;
        String fallback = null;
        List<String> workflows = null;
        List<String> artifacts = null;
        String warning = null;
        long revision = 0;
        boolean capabilitySeen = false;
        boolean availabilitySeen = false;
        boolean requiredSeen = false;
        boolean actionSeen = false;
        boolean fallbackSeen = false;
        boolean workflowsSeen = false;
        boolean artifactsSeen = false;
        boolean warningSeen = false;
        boolean revisionSeen = false;
        while (!reader.nextIs('}')) {
            String key = reader.readString();
            reader.expect(':');
            switch (key) {
                case "capability" -> {
                    requireOnce(capabilitySeen);
                    capabilitySeen = true;
                    capability = reader.readString();
                }
                case "availability" -> {
                    requireOnce(availabilitySeen);
                    availabilitySeen = true;
                    availability = readEnum(reader.readString(), CapabilityDecision.Availability.class);
                }
                case "requiredByProfile" -> {
                    requireOnce(requiredSeen);
                    requiredSeen = true;
                    requiredByProfile = reader.readBoolean();
                }
                case "action" -> {
                    requireOnce(actionSeen);
                    actionSeen = true;
                    action = readEnum(reader.readString(), CapabilityDecision.Action.class);
                }
                case "fallbackCapability" -> {
                    requireOnce(fallbackSeen);
                    fallbackSeen = true;
                    fallback = reader.readNullableString();
                }
                case "affectedWorkflows" -> {
                    requireOnce(workflowsSeen);
                    workflowsSeen = true;
                    workflows = readStringList(reader);
                }
                case "producedArtifacts" -> {
                    requireOnce(artifactsSeen);
                    artifactsSeen = true;
                    artifacts = readStringList(reader);
                }
                case "warningCode" -> {
                    requireOnce(warningSeen);
                    warningSeen = true;
                    warning = reader.readNullableString();
                }
                case "planRevision" -> {
                    requireOnce(revisionSeen);
                    revisionSeen = true;
                    revision = reader.readLong();
                }
                default -> throw CreateFactJsonSupport.invalid();
            }
            commaOrEnd(reader);
        }
        reader.expect('}');
        if (!capabilitySeen || !availabilitySeen || !requiredSeen || !actionSeen
                || !fallbackSeen || !workflowsSeen || !artifactsSeen || !warningSeen
                || !revisionSeen) {
            throw CreateFactJsonSupport.invalid();
        }
        return new CapabilityDecision(capability, availability, requiredByProfile, action, fallback,
                workflows, artifacts, warning, revision);
    }

    private static AudioProfile decodeAudio(CreateFactJsonSupport.Reader reader) {
        reader.expect('{');
        String encoding = null;
        int sampleRate = 0;
        int channels = 0;
        int frameDuration = 0;
        boolean encodingSeen = false;
        boolean sampleRateSeen = false;
        boolean channelsSeen = false;
        boolean frameSeen = false;
        while (!reader.nextIs('}')) {
            String key = reader.readString();
            reader.expect(':');
            switch (key) {
                case "encoding" -> {
                    requireOnce(encodingSeen);
                    encodingSeen = true;
                    encoding = reader.readString();
                }
                case "sampleRateHz" -> {
                    requireOnce(sampleRateSeen);
                    sampleRateSeen = true;
                    sampleRate = reader.readInt();
                }
                case "channelCount" -> {
                    requireOnce(channelsSeen);
                    channelsSeen = true;
                    channels = reader.readInt();
                }
                case "frameDurationMs" -> {
                    requireOnce(frameSeen);
                    frameSeen = true;
                    frameDuration = reader.readInt();
                }
                default -> throw CreateFactJsonSupport.invalid();
            }
            commaOrEnd(reader);
        }
        reader.expect('}');
        if (!encodingSeen || !sampleRateSeen || !channelsSeen || !frameSeen) {
            throw CreateFactJsonSupport.invalid();
        }
        AudioProfile.AudioEncoding selected = readEnum(encoding, AudioProfile.AudioEncoding.class);
        return new AudioProfile(selected, sampleRate, channels, frameDuration);
    }

    private static CreateMeetingResponseFact.RealtimeSession decodeRealtime(
            CreateFactJsonSupport.Reader reader) {
        reader.expect('{');
        String url = null;
        String subprotocol = null;
        String token = null;
        String expiresAt = null;
        boolean urlSeen = false;
        boolean subprotocolSeen = false;
        boolean tokenSeen = false;
        boolean expiresSeen = false;
        while (!reader.nextIs('}')) {
            String key = reader.readString();
            reader.expect(':');
            switch (key) {
                case "url" -> {
                    requireOnce(urlSeen);
                    urlSeen = true;
                    url = reader.readString();
                }
                case "subprotocol" -> {
                    requireOnce(subprotocolSeen);
                    subprotocolSeen = true;
                    subprotocol = reader.readString();
                }
                case "sessionToken" -> {
                    requireOnce(tokenSeen);
                    tokenSeen = true;
                    token = reader.readString();
                }
                case "expiresAt" -> {
                    requireOnce(expiresSeen);
                    expiresSeen = true;
                    expiresAt = reader.readString();
                }
                default -> throw CreateFactJsonSupport.invalid();
            }
            commaOrEnd(reader);
        }
        reader.expect('}');
        if (!urlSeen || !subprotocolSeen || !tokenSeen || !expiresSeen) {
            throw CreateFactJsonSupport.invalid();
        }
        URI parsedUrl;
        Instant parsedExpires;
        try {
            parsedUrl = URI.create(url);
            parsedExpires = Instant.parse(expiresAt);
        } catch (RuntimeException invalidValue) {
            throw CreateFactJsonSupport.invalid();
        }
        return new CreateMeetingResponseFact.RealtimeSession(parsedUrl, subprotocol, token,
                parsedExpires);
    }

    private static CreateMeetingResponseFact.Limits decodeLimits(CreateFactJsonSupport.Reader reader) {
        reader.expect('{');
        long maxDuration = 0;
        int maxFrame = 0;
        int replayWindow = 0;
        Long maxReplay = null;
        int heartbeatInterval = 0;
        boolean durationSeen = false;
        boolean frameSeen = false;
        boolean windowSeen = false;
        boolean replaySeen = false;
        boolean heartbeatSeen = false;
        while (!reader.nextIs('}')) {
            String key = reader.readString();
            reader.expect(':');
            switch (key) {
                case "maxMeetingDurationMs" -> {
                    requireOnce(durationSeen);
                    durationSeen = true;
                    maxDuration = reader.readLong();
                }
                case "maxAudioFrameBytes" -> {
                    requireOnce(frameSeen);
                    frameSeen = true;
                    maxFrame = reader.readInt();
                }
                case "eventReplayWindow" -> {
                    requireOnce(windowSeen);
                    windowSeen = true;
                    replayWindow = reader.readInt();
                }
                case "maxReplayAudioMs" -> {
                    requireOnce(replaySeen);
                    replaySeen = true;
                    if (reader.nextIsLiteralNull()) {
                        reader.consumeLiteralNull();
                    } else {
                        maxReplay = reader.readLong();
                    }
                }
                case "heartbeatIntervalMs" -> {
                    requireOnce(heartbeatSeen);
                    heartbeatSeen = true;
                    heartbeatInterval = reader.readInt();
                }
                default -> throw CreateFactJsonSupport.invalid();
            }
            commaOrEnd(reader);
        }
        reader.expect('}');
        if (!durationSeen || !frameSeen || !windowSeen || !replaySeen || !heartbeatSeen) {
            throw CreateFactJsonSupport.invalid();
        }
        return new CreateMeetingResponseFact.Limits(maxDuration, maxFrame, replayWindow,
                maxReplay, heartbeatInterval);
    }

    private static Set<String> readStringSet(CreateFactJsonSupport.Reader reader) {
        List<String> values = readStringList(reader);
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (!result.add(value)) {
                throw CreateFactJsonSupport.invalid();
            }
        }
        return result;
    }

    private static List<String> readStringList(CreateFactJsonSupport.Reader reader) {
        reader.expect('[');
        List<String> values = new ArrayList<>();
        if (!reader.nextIs(']')) {
            while (true) {
                values.add(reader.readString());
                if (reader.nextIs(']')) {
                    break;
                }
                reader.consume(',');
                if (reader.nextIs(']')) {
                    throw CreateFactJsonSupport.invalid();
                }
            }
        }
        reader.expect(']');
        return values;
    }

    private static <E extends Enum<E>> E readEnum(String value, Class<E> type) {
        try {
            return Enum.valueOf(type, value);
        } catch (RuntimeException unknown) {
            throw CreateFactJsonSupport.invalid();
        }
    }

    private static void commaOrEnd(CreateFactJsonSupport.Reader reader) {
        if (reader.nextIs('}')) {
            return;
        }
        reader.consume(',');
        if (reader.nextIs('}')) {
            throw CreateFactJsonSupport.invalid();
        }
    }

    private static void requireOnce(boolean seen) {
        if (seen) {
            throw CreateFactJsonSupport.invalid();
        }
    }

    private static StringBuilder field(StringBuilder json, String name) {
        return json.append(CreateFactJsonSupport.quote(name)).append(':');
    }

    private static void comma(StringBuilder json) {
        json.append(',');
    }

    private static void appendNullable(StringBuilder json, String value) {
        json.append(value == null ? "null" : CreateFactJsonSupport.quote(value));
    }

    private static int compareUtf8(String left, String right) {
        byte[] leftBytes = left.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] rightBytes = right.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int shared = Math.min(leftBytes.length, rightBytes.length);
        for (int index = 0; index < shared; index++) {
            int comparison = Integer.compare(Byte.toUnsignedInt(leftBytes[index]),
                    Byte.toUnsignedInt(rightBytes[index]));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(leftBytes.length, rightBytes.length);
    }
}

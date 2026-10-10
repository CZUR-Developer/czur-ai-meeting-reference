package com.czur.reference.protocol.json;

import com.czur.reference.application.command.CreateMeetingRequestFact;
import com.czur.reference.application.command.CreateMeetingRequestSnapshot;
import com.czur.reference.application.command.MeetingRequestCanonicalizer;
import com.czur.reference.application.port.model.AudioProfile;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 完整 Create 请求事实的独立 canonical JSON 编解码器。
 *
 * <p>schema 只服务于 {@link CreateMeetingRequestFact}，不读取也不写入旧的
 * {@code IdempotencyResultJson} 或 {@code MeetingCreateOutcomeJson}。顶层固定为
 * {@code schemaVersion/canonicalRequestDigest/requestSnapshot}，快照和每个嵌套对象
 * 也都使用固定字段集合。编码字段顺序固定，集合按 UTF-8 字节顺序编码；解码器
 * 拒绝缺失、未知、重复字段、类型不符、尾逗号、非整数和超出 Java 数值范围的
 * 输入。</p>
 *
 * <p>String 解码先校验 Java 文本可严格表示为 UTF-8；byte[] 解码使用 REPORT 模式
 * 的 UTF-8 decoder，因而非法 UTF-8 和孤立 surrogate 都 fail closed。所有公开失败
 * 都使用固定消息，不把请求正文、标题、设备标识、幂等键或摘要放入异常。</p>
 */
public final class CreateMeetingRequestFactJson {
    /** Create 请求事实的首版独立 JSON schema。 */
    public static final int SCHEMA_VERSION = 1;
    private static final String ENCODE_FAILURE = "Create request fact JSON encoding failed";

    private CreateMeetingRequestFactJson() {
    }

    /** 按固定字段顺序编码为 canonical JSON 文本。 */
    public static String encode(CreateMeetingRequestFact fact) {
        try {
            Objects.requireNonNull(fact, "fact");
            if (fact.schemaVersion() != MeetingRequestCanonicalizer.CREATE_SNAPSHOT_SCHEMA_VERSION) {
                throw CreateFactJsonSupport.invalid();
            }
            StringBuilder json = new StringBuilder(1024).append('{');
            field(json, "schemaVersion").append(fact.schemaVersion());
            comma(json);
            field(json, "canonicalRequestDigest")
                    .append(CreateFactJsonSupport.quote(fact.canonicalRequestDigest()));
            comma(json);
            field(json, "requestSnapshot");
            encodeSnapshot(json, fact.requestSnapshot());
            return json.append('}').toString();
        } catch (RuntimeException failure) {
            throw new IllegalStateException(ENCODE_FAILURE);
        }
    }

    /** 编码为严格 UTF-8；用于需要持久化字节而不是 String 的端口。 */
    public static byte[] encodeUtf8(CreateMeetingRequestFact fact) {
        return encode(fact).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 解码严格 canonical JSON 文本；输入字段顺序可任意，输出会重新 canonicalize。 */
    public static CreateMeetingRequestFact decode(String json) {
        try {
            CreateFactJsonSupport.Reader reader = new CreateFactJsonSupport.Reader(json);
            reader.expect('{');
            Integer schemaVersion = null;
            String digest = null;
            CreateMeetingRequestSnapshot snapshot = null;
            boolean schemaSeen = false;
            boolean digestSeen = false;
            boolean snapshotSeen = false;
            while (!reader.nextIs('}')) {
                String key = reader.readString();
                reader.expect(':');
                switch (key) {
                    case "schemaVersion" -> {
                        if (schemaSeen) {
                            throw CreateFactJsonSupport.invalid();
                        }
                        schemaSeen = true;
                        schemaVersion = reader.readInt();
                    }
                    case "canonicalRequestDigest" -> {
                        if (digestSeen) {
                            throw CreateFactJsonSupport.invalid();
                        }
                        digestSeen = true;
                        digest = reader.readString();
                    }
                    case "requestSnapshot" -> {
                        if (snapshotSeen) {
                            throw CreateFactJsonSupport.invalid();
                        }
                        snapshotSeen = true;
                        snapshot = decodeSnapshot(reader);
                    }
                    default -> throw CreateFactJsonSupport.invalid();
                }
                commaOrEnd(reader);
            }
            reader.expect('}');
            reader.ensureEnd();
            if (!schemaSeen || !digestSeen || !snapshotSeen
                    || schemaVersion != SCHEMA_VERSION) {
                throw CreateFactJsonSupport.invalid();
            }
            return new CreateMeetingRequestFact(snapshot, digest, schemaVersion);
        } catch (RuntimeException malformed) {
            // 不保留 parser、构造器或 URI/时间异常，避免输入正文进入异常链。
            throw CreateFactJsonSupport.invalid();
        }
    }

    /** 先严格解码 UTF-8，再执行与 String 入口相同的字段校验。 */
    public static CreateMeetingRequestFact decode(byte[] json) {
        try {
            return decode(CreateFactJsonSupport.decodeUtf8(json));
        } catch (RuntimeException malformed) {
            throw CreateFactJsonSupport.invalid();
        }
    }

    /** 与其它 protocol JSON codec 一致的严格 UTF-8 字节入口别名。 */
    public static CreateMeetingRequestFact decodeUtf8(byte[] json) {
        return decode(json);
    }

    private static void encodeSnapshot(StringBuilder json, CreateMeetingRequestSnapshot value) {
        json.append('{');
        field(json, "clientSessionId").append(CreateFactJsonSupport.quote(value.clientSessionId()));
        comma(json);
        field(json, "profile");
        encodeProfile(json, value.profile());
        comma(json);
        field(json, "client");
        encodeClient(json, value.client());
        comma(json);
        field(json, "title");
        appendNullable(json, value.title());
        comma(json);
        field(json, "timezone").append(CreateFactJsonSupport.quote(value.timezone()));
        comma(json);
        field(json, "languages");
        encodeLanguages(json, value.languages());
        comma(json);
        field(json, "audioProfile");
        encodeAudio(json, value.audioProfile());
        comma(json);
        field(json, "requestedCapabilities");
        encodeStrings(json, value.requestedCapabilities());
        comma(json);
        field(json, "artifactPolicy");
        encodePolicy(json, value.artifactPolicy());
        comma(json);
        field(json, "idempotencyKey").append(CreateFactJsonSupport.quote(value.idempotencyKey()));
        json.append('}');
    }

    private static void encodeProfile(StringBuilder json, CreateMeetingRequestSnapshot.ProfileRef value) {
        json.append('{');
        field(json, "id").append(CreateFactJsonSupport.quote(value.id()));
        comma(json);
        field(json, "revision").append(value.revision());
        json.append('}');
    }

    private static void encodeClient(StringBuilder json, CreateMeetingRequestSnapshot.ClientDescriptor value) {
        json.append('{');
        field(json, "appId").append(CreateFactJsonSupport.quote(value.appId()));
        comma(json);
        field(json, "appVersion").append(CreateFactJsonSupport.quote(value.appVersion()));
        comma(json);
        field(json, "contractVersion").append(CreateFactJsonSupport.quote(value.contractVersion()));
        comma(json);
        field(json, "deviceId").append(CreateFactJsonSupport.quote(value.deviceId()));
        json.append('}');
    }

    private static void encodeLanguages(StringBuilder json, CreateMeetingRequestSnapshot.LanguageSelection value) {
        json.append('{');
        field(json, "source").append(CreateFactJsonSupport.quote(value.source()));
        comma(json);
        field(json, "targets");
        List<String> targets = new ArrayList<>(value.targets());
        targets.sort(CreateMeetingRequestFactJson::compareUtf8);
        encodeStrings(json, targets);
        json.append('}');
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

    private static void encodeStrings(StringBuilder json, Iterable<String> values) {
        List<String> sorted = new ArrayList<>();
        for (String value : values) {
            sorted.add(value);
        }
        sorted.sort(CreateMeetingRequestFactJson::compareUtf8);
        json.append('[');
        for (int index = 0; index < sorted.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append(CreateFactJsonSupport.quote(sorted.get(index)));
        }
        json.append(']');
    }

    private static void encodePolicy(StringBuilder json, CreateMeetingRequestSnapshot.ArtifactPolicy value) {
        json.append('{');
        field(json, "retainAudio").append(value.retainAudio());
        comma(json);
        field(json, "presentationCapture").append(value.presentationCapture());
        json.append('}');
    }

    private static CreateMeetingRequestSnapshot decodeSnapshot(CreateFactJsonSupport.Reader reader) {
        reader.expect('{');
        String clientSessionId = null;
        CreateMeetingRequestSnapshot.ProfileRef profile = null;
        CreateMeetingRequestSnapshot.ClientDescriptor client = null;
        String title = null;
        String timezone = null;
        CreateMeetingRequestSnapshot.LanguageSelection languages = null;
        AudioProfile audio = null;
        Set<String> capabilities = null;
        CreateMeetingRequestSnapshot.ArtifactPolicy policy = null;
        String idempotencyKey = null;
        boolean clientSessionSeen = false;
        boolean profileSeen = false;
        boolean clientSeen = false;
        boolean titleSeen = false;
        boolean timezoneSeen = false;
        boolean languagesSeen = false;
        boolean audioSeen = false;
        boolean capabilitiesSeen = false;
        boolean policySeen = false;
        boolean idempotencySeen = false;
        while (!reader.nextIs('}')) {
            String key = reader.readString();
            reader.expect(':');
            switch (key) {
                case "clientSessionId" -> {
                    requireOnce(clientSessionSeen);
                    clientSessionSeen = true;
                    clientSessionId = reader.readString();
                }
                case "profile" -> {
                    requireOnce(profileSeen);
                    profileSeen = true;
                    profile = decodeProfile(reader);
                }
                case "client" -> {
                    requireOnce(clientSeen);
                    clientSeen = true;
                    client = decodeClient(reader);
                }
                case "title" -> {
                    requireOnce(titleSeen);
                    titleSeen = true;
                    title = reader.readNullableString();
                }
                case "timezone" -> {
                    requireOnce(timezoneSeen);
                    timezoneSeen = true;
                    timezone = reader.readString();
                }
                case "languages" -> {
                    requireOnce(languagesSeen);
                    languagesSeen = true;
                    languages = decodeLanguages(reader);
                }
                case "audioProfile" -> {
                    requireOnce(audioSeen);
                    audioSeen = true;
                    audio = decodeAudio(reader);
                }
                case "requestedCapabilities" -> {
                    requireOnce(capabilitiesSeen);
                    capabilitiesSeen = true;
                    capabilities = readStringSet(reader);
                }
                case "artifactPolicy" -> {
                    requireOnce(policySeen);
                    policySeen = true;
                    policy = decodePolicy(reader);
                }
                case "idempotencyKey" -> {
                    requireOnce(idempotencySeen);
                    idempotencySeen = true;
                    idempotencyKey = reader.readString();
                }
                default -> throw CreateFactJsonSupport.invalid();
            }
            commaOrEnd(reader);
        }
        reader.expect('}');
        if (!clientSessionSeen || !profileSeen || !clientSeen || !titleSeen
                || !timezoneSeen || !languagesSeen || !audioSeen || !capabilitiesSeen
                || !policySeen || !idempotencySeen) {
            throw CreateFactJsonSupport.invalid();
        }
        return new CreateMeetingRequestSnapshot(clientSessionId, profile, client, title, timezone,
                languages, audio, capabilities, policy, idempotencyKey);
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

    private static CreateMeetingRequestSnapshot.ClientDescriptor decodeClient(CreateFactJsonSupport.Reader reader) {
        reader.expect('{');
        String appId = null;
        String appVersion = null;
        String contractVersion = null;
        String deviceId = null;
        boolean appIdSeen = false;
        boolean appVersionSeen = false;
        boolean contractVersionSeen = false;
        boolean deviceIdSeen = false;
        while (!reader.nextIs('}')) {
            String key = reader.readString();
            reader.expect(':');
            switch (key) {
                case "appId" -> {
                    requireOnce(appIdSeen);
                    appIdSeen = true;
                    appId = reader.readString();
                }
                case "appVersion" -> {
                    requireOnce(appVersionSeen);
                    appVersionSeen = true;
                    appVersion = reader.readString();
                }
                case "contractVersion" -> {
                    requireOnce(contractVersionSeen);
                    contractVersionSeen = true;
                    contractVersion = reader.readString();
                }
                case "deviceId" -> {
                    requireOnce(deviceIdSeen);
                    deviceIdSeen = true;
                    deviceId = reader.readString();
                }
                default -> throw CreateFactJsonSupport.invalid();
            }
            commaOrEnd(reader);
        }
        reader.expect('}');
        if (!appIdSeen || !appVersionSeen || !contractVersionSeen || !deviceIdSeen) {
            throw CreateFactJsonSupport.invalid();
        }
        return new CreateMeetingRequestSnapshot.ClientDescriptor(
                appId, appVersion, contractVersion, deviceId);
    }

    private static CreateMeetingRequestSnapshot.LanguageSelection decodeLanguages(
            CreateFactJsonSupport.Reader reader) {
        reader.expect('{');
        String source = null;
        List<String> targets = null;
        boolean sourceSeen = false;
        boolean targetsSeen = false;
        while (!reader.nextIs('}')) {
            String key = reader.readString();
            reader.expect(':');
            switch (key) {
                case "source" -> {
                    requireOnce(sourceSeen);
                    sourceSeen = true;
                    source = reader.readString();
                }
                case "targets" -> {
                    requireOnce(targetsSeen);
                    targetsSeen = true;
                    targets = readStringList(reader);
                }
                default -> throw CreateFactJsonSupport.invalid();
            }
            commaOrEnd(reader);
        }
        reader.expect('}');
        if (!sourceSeen || !targetsSeen) {
            throw CreateFactJsonSupport.invalid();
        }
        targets.sort(CreateMeetingRequestFactJson::compareUtf8);
        return new CreateMeetingRequestSnapshot.LanguageSelection(source, targets);
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
        AudioProfile.AudioEncoding selected;
        try {
            selected = AudioProfile.AudioEncoding.valueOf(encoding);
        } catch (RuntimeException unknownEncoding) {
            throw CreateFactJsonSupport.invalid();
        }
        return new AudioProfile(selected, sampleRate, channels, frameDuration);
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

    private static CreateMeetingRequestSnapshot.ArtifactPolicy decodePolicy(
            CreateFactJsonSupport.Reader reader) {
        reader.expect('{');
        boolean retain = false;
        boolean presentation = false;
        boolean retainSeen = false;
        boolean presentationSeen = false;
        while (!reader.nextIs('}')) {
            String key = reader.readString();
            reader.expect(':');
            switch (key) {
                case "retainAudio" -> {
                    requireOnce(retainSeen);
                    retainSeen = true;
                    retain = reader.readBoolean();
                }
                case "presentationCapture" -> {
                    requireOnce(presentationSeen);
                    presentationSeen = true;
                    presentation = reader.readBoolean();
                }
                default -> throw CreateFactJsonSupport.invalid();
            }
            commaOrEnd(reader);
        }
        reader.expect('}');
        if (!retainSeen || !presentationSeen) {
            throw CreateFactJsonSupport.invalid();
        }
        return new CreateMeetingRequestSnapshot.ArtifactPolicy(retain, presentation);
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

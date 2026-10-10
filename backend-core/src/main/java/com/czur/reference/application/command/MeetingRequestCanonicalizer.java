package com.czur.reference.application.command;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 为认证会议命令生成跨重试稳定的规范请求摘要。
 *
 * <p>编码使用大端定长数字和“UTF-8 字节长度 + 字节正文”，不会依赖 JSON 属性
 * 顺序、默认字符集、Java {@code char} 长度或字符串分隔符。每种操作具有独立的
 * 领域标记和 schemaVersion，随后按本文档固定顺序写入字段；最终使用 SHA-256
 * 并输出 64 位小写十六进制文本。字段边界由字节长度确定，因此包含冒号、换行、
 * Unicode 或看似字段名的内容也不能制造拼接碰撞。</p>
 *
 * <p>创建摘要只编码 clientSessionId、Profile ID/revision、按 UTF-8 字节顺序
 * 排列的能力，以及用量 unit/amount。停止摘要只编码 meetingId。部署、主体、
 * 幂等键、服务端 meetingId、时间、Grant 和 Reservation 均由类型边界排除。
 * 摘要本身也可能被用于幂等判断，调用方不得把它写入日志。</p>
 */
public final class MeetingRequestCanonicalizer {
    /** 首版创建意图编码版本。改变字段或编码方式时必须递增。 */
    public static final int CREATE_SCHEMA_VERSION = 1;

    /** 首版停止意图编码版本。改变字段或编码方式时必须递增。 */
    public static final int STOP_SCHEMA_VERSION = 1;

    /** 首版删除意图编码版本。改变字段或编码方式时必须递增。 */
    public static final int DELETE_SCHEMA_VERSION = 1;

    /** 完整 Create 请求快照的独立编码版本；不改变旧 CreateIntent 摘要。 */
    public static final int CREATE_SNAPSHOT_SCHEMA_VERSION = 1;

    private static final String DOMAIN = "czur.ai-meeting.request";
    private static final String CREATE_OPERATION = "meeting.create";
    private static final String STOP_OPERATION = "meeting.stop";
    private static final String DELETE_OPERATION = "meeting.delete";

    private MeetingRequestCanonicalizer() {
    }

    /**
     * 计算创建业务意图摘要。幂等键只用于存取记录，不参与本摘要。
     *
     * @param intent 已通过构造器校验并完成防御性复制的客户端意图
     * @return 64 位小写十六进制 SHA-256
     */
    public static String digest(CreateMeetingIntent intent) {
        Objects.requireNonNull(intent, "intent");
        return sha256(encode(output -> {
            writeText(output, DOMAIN);
            writeText(output, CREATE_OPERATION);
            output.writeInt(CREATE_SCHEMA_VERSION);
            writeText(output, intent.clientSessionId());
            writeText(output, intent.profileId());
            output.writeInt(intent.profileRevision());

            List<String> capabilities = new ArrayList<>(intent.requestedCapabilities());
            capabilities.sort(MeetingRequestCanonicalizer::compareUtf8);
            output.writeInt(capabilities.size());
            for (String capability : capabilities) {
                writeText(output, capability);
            }

            writeText(output, intent.estimatedUsage().unit().name());
            output.writeLong(intent.estimatedUsage().amount());
        }));
    }

    /**
     * 计算完整 Create 请求快照的业务摘要。
     *
     * <p>该 overload 只冻结新快照的编码契约，暂不接入现有创建服务。幂等键仍然
     * 不进入业务摘要，避免同一业务意图因为客户端重试更换 Header 而被判定为不同
     * 请求；它只用于幂等记录的定位。与旧 {@link CreateMeetingIntent} 使用独立
     * schemaVersion，后续接入持久化时不得把两种摘要混用。</p>
     */
    public static String digest(CreateMeetingRequestSnapshot request) {
        Objects.requireNonNull(request, "request");
        return sha256(encode(output -> {
            writeText(output, DOMAIN);
            writeText(output, CREATE_OPERATION);
            output.writeInt(CREATE_SNAPSHOT_SCHEMA_VERSION);
            writeText(output, request.clientSessionId());
            writeText(output, request.profile().id());
            output.writeInt(request.profile().revision());
            writeText(output, request.client().appId());
            writeText(output, request.client().appVersion());
            writeText(output, request.client().contractVersion());
            writeText(output, request.client().deviceId());
            output.writeBoolean(request.title() != null);
            if (request.title() != null) {
                writeText(output, request.title());
            }
            writeText(output, request.timezone());
            writeText(output, request.languages().source());
            List<String> targets = new ArrayList<>(request.languages().targets());
            targets.sort(MeetingRequestCanonicalizer::compareUtf8);
            output.writeInt(targets.size());
            for (String target : targets) {
                writeText(output, target);
            }
            // 使用稳定枚举名称而不是 ordinal，避免未来扩展枚举或重排常量破坏摘要。
            writeText(output, request.audioProfile().encoding().name());
            output.writeInt(request.audioProfile().sampleRateHz());
            output.writeInt(request.audioProfile().channelCount());
            output.writeInt(request.audioProfile().frameDurationMs());
            List<String> capabilities = new ArrayList<>(request.requestedCapabilities());
            capabilities.sort(MeetingRequestCanonicalizer::compareUtf8);
            output.writeInt(capabilities.size());
            for (String capability : capabilities) {
                writeText(output, capability);
            }
            output.writeBoolean(request.artifactPolicy().retainAudio());
            output.writeBoolean(request.artifactPolicy().presentationCapture());
        }));
    }

    /**
     * 计算停止业务意图摘要。除编码版本和 meetingId 外不包含任何请求字段。
     *
     * @param intent 已校验的客户端停止意图
     * @return 64 位小写十六进制 SHA-256
     */
    public static String digest(StopMeetingIntent intent) {
        Objects.requireNonNull(intent, "intent");
        return sha256(encode(output -> {
            writeText(output, DOMAIN);
            writeText(output, STOP_OPERATION);
            output.writeInt(STOP_SCHEMA_VERSION);
            writeText(output, intent.meetingId());
        }));
    }

    /**
     * 计算删除业务意图摘要：版本化 domain、operation 和 meetingId。
     *
     * <p>幂等键只用于定位记录；主体、部署和权限属于可信服务端上下文，均不进入
     * 意图摘要。输入按严格 UTF-8 长度前缀编码，摘要为 64 位小写 SHA-256。
     * 同一会议的不同幂等键产生同一摘要，不同会议产生不同摘要。</p>
     */
    public static String deleteDigest(String meetingId) {
        com.czur.reference.domain.meeting.MeetingIdPolicy.requireValid(meetingId);
        return sha256(encode(output -> {
            writeText(output, DOMAIN);
            writeText(output, DELETE_OPERATION);
            output.writeInt(DELETE_SCHEMA_VERSION);
            writeText(output, meetingId);
        }));
    }

    private static byte[] encode(Encoder encoder) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                encoder.writeTo(output);
            }
            return bytes.toByteArray();
        } catch (IOException impossibleForMemoryBuffer) {
            throw new IllegalStateException("canonical request encoding failed");
        }
    }

    private static void writeText(DataOutputStream output, String value) throws IOException {
        byte[] bytes = utf8(value);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    /** 按无符号 UTF-8 字节比较，避免依赖 JVM locale 或 UTF-16 代理项顺序。 */
    private static int compareUtf8(String left, String right) {
        byte[] leftBytes = utf8(left);
        byte[] rightBytes = utf8(right);
        int shared = Math.min(leftBytes.length, rightBytes.length);
        for (int index = 0; index < shared; index++) {
            int comparison = Integer.compare(
                    Byte.toUnsignedInt(leftBytes[index]), Byte.toUnsignedInt(rightBytes[index]));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(leftBytes.length, rightBytes.length);
    }

    /**
     * 严格编码 UTF-8；拒绝 Java String 中不成对的代理项，避免替换字符造成摘要碰撞。
     */
    private static byte[] utf8(String value) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException invalidUnicode) {
            throw new IllegalArgumentException(
                    "canonical request text is not valid Unicode");
        }
    }

    private static String sha256(byte[] canonicalBytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonicalBytes));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is not available");
        }
    }

    @FunctionalInterface
    private interface Encoder {
        void writeTo(DataOutputStream output) throws IOException;
    }
}

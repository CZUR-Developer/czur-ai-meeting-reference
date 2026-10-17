package com.czur.reference.application.http;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.port.ClientMeetingInputPort;
import com.czur.reference.application.port.MeetingArtifactMediaPort;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 参考后端录音上传和媒体读取的最小 HTTP 接线。
 *
 * <p>录音上传使用 {@code POST /v1/meetings/{meetingId}/audio-original} 的原始二进制
 * 正文，正文只通过 {@link ClientMeetingInputPort.AudioArtifact} 进入公共 ArtifactStore；
 * 现有 JSON 控制接口不承载音频。结果回放复用公开 Manifest 的 artifactId/genTime 访问
 * 规则，由 {@link MeetingArtifactMediaPort} 返回已核对的流式句柄。这里不创建录音表、
 * Manifest 或第二套状态机，也不暴露对象存储地址。</p>
 */
public final class MeetingArtifactHttpEndpoint {
    private static final Pattern UPLOAD_PATH = Pattern.compile(
            "^/v1/meetings/(mtg_[A-Za-z0-9_-]+)/audio-original$");
    private static final Pattern ARTIFACT_PATH = Pattern.compile(
            "^/v1/artifacts/(art_[A-Za-z0-9_-]+)$");
    private static final Pattern CHECKSUM = Pattern.compile("^sha256:[0-9a-f]{64}$");

    private final ClientMeetingInputPort clientInput;
    private final MeetingArtifactMediaPort media;
    private final HttpErrorMapper errors;

    public MeetingArtifactHttpEndpoint(ClientMeetingInputPort clientInput,
            MeetingArtifactMediaPort media, HttpErrorMapper errors) {
        this.clientInput = Objects.requireNonNull(clientInput, "clientInput");
        this.media = Objects.requireNonNull(media, "media");
        this.errors = Objects.requireNonNull(errors, "errors");
    }

    /** JDK transport 在读取正文前用此方法选择原始流入口。 */
    public static boolean matchesUploadPath(String method, String path) {
        return "POST".equals(method) && path != null && UPLOAD_PATH.matcher(path).matches();
    }

    /** JDK transport 在 JSON 适配器前用此方法选择媒体流回放入口。 */
    public boolean matchesDownload(HttpRequest request) {
        if (request == null || !"GET".equals(request.method())) {
            return false;
        }
        var matcher = ARTIFACT_PATH.matcher(request.path());
        if (!matcher.matches()) {
            return false;
        }
        // JSON 产物（原文、译文、总结和演示内容）必须继续走 MeetingHttpAdapter；
        // 只有录音产物交给流式媒体端口。当前参考客户端的录音 ID 由
        // JdbcClientMeetingInputPort 统一使用 art_client_audio_ 前缀生成。
        // 这样不会把 JSON 产物误当成音频并在 JDK transport 层返回 500。
        return matcher.group(1).startsWith("art_client_audio_");
    }

    /** 当前二进制上传是否属于本端点。 */
    public boolean matchesUpload(BinaryHttpRequest request) {
        return request != null && matchesUploadPath(request.method(), request.path());
    }

    /**
     * 处理已通过外层身份解析的原始音频上传。
     *
     * <p>本方法不会关闭正文流；调用方（网络 dispatcher）在响应映射完成后统一关闭
     * {@link BinaryHttpRequest}。同值重放由公共输入端口决定，端点不读取或缓存第二份
     * 正文。</p>
     */
    public HttpResponse upload(BinaryHttpRequest request, MeetingCommandContext context) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        if (!matchesUpload(request) || !request.query().isEmpty()) {
            throw new IllegalArgumentException("audio upload request is invalid");
        }
        String mediaType = mediaType(request.header("content-type"));
        String checksum = request.header("x-content-sha256");
        if (checksum == null || !CHECKSUM.matcher(checksum).matches()) {
            throw new IllegalArgumentException("audio checksum header is invalid");
        }
        String meetingId = UPLOAD_PATH.matcher(request.path()).matches()
                ? UPLOAD_PATH.matcher(request.path()).replaceFirst("$1") : "";
        var result = clientInput.appendAudio(context, meetingId,
                new ClientMeetingInputPort.AudioArtifact(mediaType, request.contentLength(),
                        checksum, request.content()));
        return errors.audioAppend(meetingId, result);
    }

    /** 打开已按 Manifest/genTime 访问规则核对的音频流；不把正文读入内存。 */
    public MeetingArtifactMediaPort.Result openDownload(HttpRequest request) {
        Objects.requireNonNull(request, "request");
        var matcher = ARTIFACT_PATH.matcher(request.path());
        if (!matchesDownload(request) || !request.body().isEmpty() || request.query().size() != 1
                || !request.query().containsKey("genTime")) {
            throw new IllegalArgumentException("media request is invalid");
        }
        long genTime = parseGenTime(request.query().get("genTime"));
        return media.open(new com.czur.reference.application.port.MeetingArtifactContentPort.Request(
                matcher.group(1), genTime));
    }

    private static String mediaType(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("audio content-type is missing");
        }
        String type = value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!(type.startsWith("audio/") || "application/octet-stream".equals(type))) {
            throw new IllegalArgumentException("audio content-type is invalid");
        }
        return type;
    }

    private static long parseGenTime(String value) {
        if (value == null || !value.matches("(?:-1|0|[1-9][0-9]*)")) {
            throw new IllegalArgumentException("genTime is invalid");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("genTime is invalid");
        }
    }
}

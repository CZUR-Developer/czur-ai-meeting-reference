package com.czur.reference.application.http;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.port.speaker.SpeakerNameEditPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** 可选结果写入口；仅接收标准段落／本场发言人标识，不接收供应商声纹编号。 */
public final class SpeakerNameEditEndpoint {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PATH = Pattern.compile("^/v1/meetings/(mtg_[A-Za-z0-9_-]+)/speakers/([^/:]+):rename$");
    private final SpeakerNameEditPort port;
    private final HttpErrorMapper errors;

    public SpeakerNameEditEndpoint(SpeakerNameEditPort port, HttpErrorMapper errors) {
        this.port = Objects.requireNonNull(port);
        this.errors = Objects.requireNonNull(errors);
    }
    public static boolean matches(HttpRequest request) {
        return "POST".equals(request.method()) && matchesPath(request.path());
    }
    static boolean matchesPath(String path) { return PATH.matcher(path).matches(); }

    public HttpResponse handle(HttpRequest request, MeetingCommandContext context) {
        try {
            var path = PATH.matcher(request.path());
            if (!path.matches() || !request.query().isEmpty()) return errors.invalidRoute();
            var body = JSON.readTree(request.body());
            if (body == null || !body.isObject() || !body.path("displayName").isTextual()
                    || !body.path("scope").isTextual() || !body.path("remember").isBoolean()
                    || body.has("segmentId") && !body.path("segmentId").isTextual())
                throw new IllegalArgumentException("speaker edit is invalid");
            var edit = new SpeakerNameEditPort.Edit(body.path("displayName").asText(),
                    SpeakerNameEditPort.Scope.valueOf(body.path("scope").asText()),
                    body.has("segmentId") ? body.path("segmentId").asText() : null,
                    body.path("remember").asBoolean());
            var result = port.edit(context, path.group(1), path.group(2), edit);
            if (result instanceof SpeakerNameEditPort.Updated updated)
                return HttpResponse.json(200, JSON.writeValueAsString(Map.of(
                        "manifestRevision", updated.manifestRevision(), "changedSegments", updated.changedSegments(),
                        "registration", updated.registration().name())));
            return errors.speakerEditingFailure(((SpeakerNameEditPort.Rejected) result).reason());
        } catch (Exception failure) { return errors.exception(failure); }
    }
}

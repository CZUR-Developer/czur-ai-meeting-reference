package com.czur.reference.application.http;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.port.MeetingQueryPort;
import com.czur.reference.application.port.MeetingManifestPort;
import com.czur.reference.application.port.InputState;
import com.czur.reference.domain.meeting.MeetingState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Stop 成功后向本场选定邮箱发送结果链接；不生成或附加 PDF。 */
public final class ResultEmailEndpoint {
    private static final Pattern PATH = Pattern.compile(
            "^/v1/meetings/(mtg_[A-Za-z0-9_-]+):sendResultEmail$");
    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final MeetingQueryPort meetings;
    private final MeetingManifestPort manifests;
    private final Sender sender;
    private final HttpErrorMapper errors;

    public interface Sender {
        void send(List<String> recipients, String resultUrl) throws IOException;
    }

    public ResultEmailEndpoint(MeetingQueryPort meetings, MeetingManifestPort manifests,
            Sender sender, HttpErrorMapper errors) {
        this.meetings = meetings;
        this.manifests = manifests;
        this.sender = sender;
        this.errors = errors;
    }

    public static boolean matches(HttpRequest request) {
        return "POST".equals(request.method()) && PATH.matcher(request.path()).matches();
    }

    public HttpResponse handle(HttpRequest request, MeetingCommandContext context) {
        if (context == null) return errors.missingContext();
        var match = PATH.matcher(request.path());
        if (!match.matches() || !request.query().isEmpty()) return errors.invalidRoute();
        String meetingId = match.group(1);
        List<String> recipients;
        String resultUrl;
        long genTime;
        try {
            JsonNode body = JSON.readTree(request.body());
            if (body == null || body.size() != 2 || !body.has("recipients")
                    || !body.has("resultUrl") || !body.get("recipients").isArray()) {
                return errors.exception(new IllegalArgumentException("invalid result email"));
            }
            recipients = new ArrayList<>();
            for (JsonNode entry : body.get("recipients")) {
                if (!entry.isTextual() || !EMAIL.matcher(entry.asText()).matches()
                        || recipients.contains(entry.asText())) {
                    return errors.exception(new IllegalArgumentException("invalid recipient"));
                }
                recipients.add(entry.asText());
            }
            resultUrl = body.get("resultUrl").asText();
            genTime = body.get("resultUrl").isTextual()
                    ? resultGenTime(resultUrl, meetingId) : -1;
            if (recipients.isEmpty() || recipients.size() > 10 || genTime < 0) {
                return errors.exception(new IllegalArgumentException("invalid result link"));
            }
        } catch (IOException | RuntimeException malformed) {
            return errors.exception(new IllegalArgumentException("invalid result email"));
        }
        try {
            MeetingQueryPort.Result result = meetings.get(new MeetingQueryPort.GetRequest(
                    context, meetingId));
            if (!(result instanceof MeetingQueryPort.Found found)) return errors.query(result);
            MeetingState state = found.meeting().state();
            if (found.meeting().inputState() != InputState.INPUT_ACCEPTED
                    || (state != MeetingState.FINALIZING && state != MeetingState.READY
                            && state != MeetingState.READY_WITH_WARNINGS)) {
                return HttpResponse.json(409, "{\"error\":\"RESULT_NOT_READY\"}");
            }
            var manifest = manifests.get(new MeetingManifestPort.Request(meetingId, genTime));
            if (!(manifest instanceof MeetingManifestPort.Found)
                    && !(manifest instanceof MeetingManifestPort.Pending)) {
                return errors.manifest(manifest);
            }
            sender.send(List.copyOf(recipients), resultUrl);
            return HttpResponse.json(200, "{\"sent\":true}");
        } catch (IOException failure) {
            return HttpResponse.json(503, "{\"error\":\"EMAIL_UNAVAILABLE\"}");
        } catch (RuntimeException failure) {
            return errors.internalFailure();
        }
    }

    private static long resultGenTime(String value, String meetingId) {
        try {
            URI url = URI.create(value);
            if (url.getHost() == null || url.getRawFragment() != null
                    || !("https".equals(url.getScheme())
                            || "http".equals(url.getScheme()))) return -1;
            Map<String, String> query = java.util.Arrays.stream(url.getRawQuery().split("&"))
                    .map(pair -> pair.split("=", 2))
                    .filter(pair -> pair.length == 2)
                    .collect(java.util.stream.Collectors.toMap(
                            pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                            pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8),
                            (first, second) -> second));
            if (!meetingId.equals(query.get("meetingId"))) return -1;
            long genTime = Long.parseLong(query.getOrDefault("genTime", ""));
            return genTime > 0 && genTime <= System.currentTimeMillis() ? genTime : -1;
        } catch (RuntimeException invalid) {
            return -1;
        }
    }
}

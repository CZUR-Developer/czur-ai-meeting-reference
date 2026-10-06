package com.czur.aimeeting.reference.meeting;

import com.czur.aimeeting.client.server.HttpMeetingSessionClient;
import java.io.IOException;
import java.net.URL;
import java.util.Set;

/** P6-6 迁移期薄转发：App 保留登录 UI，不再解析登录或能力发现 JSON。 */
public final class HttpReferenceSessionClient {
    private final HttpMeetingSessionClient delegate;
    public HttpReferenceSessionClient(String baseUrl) {
        delegate = new HttpMeetingSessionClient(baseUrl);
    }
    public ReferenceLoginSession login(String username, String password) throws IOException {
        var session = delegate.login(username, password);
        return new ReferenceLoginSession(session.accessToken(), session.expiresAt());
    }
    public Set<String> capabilities() throws IOException { return delegate.capabilities(); }
    public DeploymentOptions deploymentOptions() throws IOException {
        var options = delegate.deploymentOptions();
        return new DeploymentOptions(options.capabilities(), options.resultLinkTtlMs(),
                options.resultEmailEnabled());
    }
    public record DeploymentOptions(Set<String> capabilities, long resultLinkTtlMs,
            boolean resultEmailEnabled) { }
    static void validateLoginUrl(URL url) throws IOException {
        HttpMeetingSessionClient.validateLoginUrl(url);
    }
}

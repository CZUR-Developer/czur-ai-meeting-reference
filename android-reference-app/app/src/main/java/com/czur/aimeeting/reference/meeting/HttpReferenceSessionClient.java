package com.czur.aimeeting.reference.meeting;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 参考后端的终端用户登录客户端。部署者预置初始账号，App 只提交用户输入的凭据，
 * 并把后端签发的访问令牌交给会议客户端；账号发放和管理不属于设备系统身份。
 */
public final class HttpReferenceSessionClient {
    private final String baseUrl;

    public HttpReferenceSessionClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** 登录一次，返回用于后续会议 API 的会话；不会记录请求或响应中的敏感字段。 */
    public ReferenceLoginSession login(String username, String password) throws IOException {
        final byte[] body;
        try {
            body = new JSONObject().put("username", username).put("password", password)
                    .toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException invalidInput) {
            throw new IOException("登录请求格式错误", invalidInput);
        }

        URL loginUrl = new URL(baseUrl + "/v1/reference/session:login");
        validateLoginUrl(loginUrl);
        HttpURLConnection connection = (HttpURLConnection) loginUrl.openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            try (var output = connection.getOutputStream()) {
                output.write(body);
            }
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new IOException(status == 401 ? "账号或密码错误" : "登录失败，HTTP " + status);
            }
            try (InputStream input = connection.getInputStream()) {
                JSONObject response = new JSONObject(readUtf8(input));
                String token = response.getString("accessToken");
                String expiresAt = response.getString("expiresAt");
                if (token.isEmpty() || expiresAt.isEmpty()) {
                    throw new IOException("登录响应缺少会话信息");
                }
                return new ReferenceLoginSession(token, expiresAt);
            } catch (JSONException invalidResponse) {
                throw new IOException("登录响应格式错误", invalidResponse);
            }
        } finally {
            connection.disconnect();
        }
    }

    /** 按现有匿名发现契约读取部署静态能力；本场能力仍以 Create 响应为准。 */
    public Set<String> capabilities() throws IOException {
        return deploymentOptions().capabilities();
    }

    /** 同一能力响应还提供结果链接有效期及可用的邮箱交付方式。 */
    public DeploymentOptions deploymentOptions() throws IOException {
        URL url = new URL(baseUrl + "/v1/capabilities");
        validateLoginUrl(url);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("部署能力发现失败，HTTP " + status);
            try (InputStream input = connection.getInputStream()) {
                JSONObject response = new JSONObject(readUtf8(input));
                JSONArray values = response.getJSONArray("capabilities");
                Set<String> capabilities = new HashSet<>();
                for (int index = 0; index < values.length(); index++) {
                    capabilities.add(values.getString(index));
                }
                return new DeploymentOptions(Set.copyOf(capabilities),
                        response.optLong("resultLinkTtlMs", 0),
                        response.optBoolean("resultEmailEnabled", false));
            } catch (JSONException invalidResponse) {
                throw new IOException("部署能力响应格式错误", invalidResponse);
            }
        } finally {
            connection.disconnect();
        }
    }

    public record DeploymentOptions(Set<String> capabilities, long resultLinkTtlMs,
            boolean resultEmailEnabled) { }

    /**
     * 账号密码只经 HTTPS 发送。唯一的明文例外是开发机通过 adb reverse 暴露给设备的
     * loopback 地址；普通局域网 HTTP 地址不可用于登录。
     */
    static void validateLoginUrl(URL url) throws IOException {
        if ("https".equalsIgnoreCase(url.getProtocol())) return;
        String host = url.getHost();
        if ("http".equalsIgnoreCase(url.getProtocol())
                && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host))) {
            return;
        }
        throw new IOException("登录地址须使用 HTTPS；本机调试可使用 adb reverse 的 localhost");
    }

    private static String readUtf8(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}

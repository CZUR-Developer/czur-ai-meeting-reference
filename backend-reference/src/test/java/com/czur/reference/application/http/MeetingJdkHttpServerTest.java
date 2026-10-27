package com.czur.reference.application.http;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.port.DeleteRequest;
import com.czur.reference.application.port.DeleteResult;
import com.czur.reference.application.port.MeetingDeletionPort;
import com.czur.reference.application.port.MeetingQueryPort;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用真实 loopback Socket 字节检验网络入口，而非直接构造 HttpRequest。 */
class MeetingJdkHttpServerTest {
    private static final String HOST = "127.0.0.1";

    @Test
    void realNetworkPreservesDefaultCreateAndMissingContextResponses() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String create = fixture.send(request("POST", "/v1/meetings", "", "{}"));
            assertEquals(501, status(create));
            assertTrue(create.contains("ROUTE_NOT_IMPLEMENTED"));
            assertTrue(create.toLowerCase().contains("content-type: application/json"));
            assertEquals(501, status(fixture.send(request(
                    "POST", "/v1/meetings", "", "not JSON"))));

            String query = fixture.send(request("GET", "/v1/meetings?pageSize=7", "", ""));
            assertEquals(401, status(query));
            assertTrue(query.contains("AUTHENTICATION_REQUIRED"));
            assertEquals(0, fixture.queries);
        }
    }

    @Test
    void rawDuplicateHeadersAreRejectedBeforeRuntimeDispatch() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String duplicateAuthorization = fixture.send(request("POST", "/v1/meetings",
                    "Authorization: Bearer first\r\nauthorization: Bearer second\r\n", "{}"));
            assertEquals(400, status(duplicateAuthorization));
            assertTrue(duplicateAuthorization.contains("INVALID_REQUEST"));

            String duplicateKey = fixture.send(request("POST", "/v1/meetings",
                    "Idempotency-Key: first-key\r\nIdempotency-Key: second-key\r\n", "{}"));
            assertEquals(400, status(duplicateKey));
            assertEquals(0, fixture.queries);
        }
    }

    @Test
    void invalidUtf8AndOversizedBodyFailAtNetworkBoundary() throws Exception {
        try (Fixture fixture = new Fixture()) {
            byte[] prefix = ("POST /v1/meetings HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Length: 2\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII);
            byte[] request = new byte[prefix.length + 2];
            System.arraycopy(prefix, 0, request, 0, prefix.length);
            request[prefix.length] = (byte) 0xc3;
            request[prefix.length + 1] = (byte) 0x28;
            assertEquals(400, status(fixture.send(request)));

            String tooLarge = "x".repeat(HttpRequest.MAX_BODY_BYTES + 1);
            assertEquals(400, status(fixture.send(request(
                    "POST", "/v1/meetings", "", tooLarge))));
            assertEquals(0, fixture.queries);
        }
    }

    @Test
    void explicitMediaBudgetReachesNetworkBoundaryWithoutChangingJsonRequests() throws Exception {
        // 使用小预算验证实际 socket 接线；长会数值边界在 AudioArtifactBudgetTest 覆盖。
        try (Fixture fixture = new Fixture(8L)) {
            assertEquals(501, status(fixture.send(request("POST",
                    "/v1/meetings/mtg_budget/audio-original", "", "12345678"))));
            assertEquals(400, status(fixture.send(request("POST",
                    "/v1/meetings/mtg_budget/audio-original", "", "123456789"))));
            // JSON 仍走原 1 MiB 路径，不受媒体专用的八字节测试预算影响。
            assertEquals(501, status(fixture.send(request("POST", "/v1/meetings", "", "123456789"))));
        }
    }

    @Test
    void ambiguousUriAndOversizedHeadersNeverReachBusinessPort() throws Exception {
        try (Fixture fixture = new Fixture()) {
            for (String uri : new String[]{"/v1/%6deetings", "/v1//meetings",
                    "/v1/../meetings", "/v1/meetings?pageSize=%FF",
                    "/v1/meetings?pageSize=1&pageSize=2",
                    "/v1/meetings?pageSize=%0A",
                    "/v1/meetings?pageSize=%C2%80"}) {
                assertEquals(400, status(fixture.send(request("GET", uri, "", ""))), uri);
            }
            assertEquals(400, status(fixture.send(request("GET", "/v1/meetings",
                    "X-Padding: " + "x".repeat(HttpRequest.MAX_HEADER_BYTES) + "\r\n", ""))));
            StringBuilder manyHeaders = new StringBuilder();
            for (int i = 0; i < HttpRequest.MAX_HEADER_COUNT + 1; i++) {
                manyHeaders.append("X-Count-").append(i).append(": value\r\n");
            }
            String countResponse = fixture.send(request("GET", "/v1/meetings",
                    manyHeaders.toString(), ""));
            assertEquals(400, status(countResponse));
            assertTrue(countResponse.contains("INVALID_REQUEST"));
            byte[] headerPrefix = ("GET /v1/meetings HTTP/1.1\r\nHost: localhost\r\n"
                    + "X-Control: ").getBytes(StandardCharsets.US_ASCII);
            byte[] headerSuffix = ("\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII);
            byte[] controlHeader = new byte[headerPrefix.length + 1 + headerSuffix.length];
            System.arraycopy(headerPrefix, 0, controlHeader, 0, headerPrefix.length);
            controlHeader[headerPrefix.length] = (byte) 0x80;
            System.arraycopy(headerSuffix, 0, controlHeader, headerPrefix.length + 1,
                    headerSuffix.length);
            assertEquals(400, status(fixture.send(controlHeader)));
            assertEquals(0, fixture.queries);
        }
    }

    @Test
    void networkResponsePreservesProjectedHeadersAndExactUtf8BodyBytes() throws Exception {
        try (Fixture fixture = new Fixture()) {
            HttpResponse expected = new HttpErrorMapper(() -> "network-test-diag")
                    .notImplemented();
            byte[] raw = fixture.sendBytes(request("POST", "/v1/meetings", "", "{}"));
            int separator = findHeaderEnd(raw);
            String head = new String(raw, 0, separator, StandardCharsets.ISO_8859_1);
            byte[] body = Arrays.copyOfRange(raw, separator + 4, raw.length);

            assertEquals(expected.status(), status(head + "\r\n"));
            for (var header : expected.headers().entrySet()) {
                assertEquals(header.getValue(), headerValue(head, header.getKey()));
            }
            assertEquals(Integer.toString(body.length), headerValue(head, "content-length"));
            assertArrayEquals(expected.body().getBytes(StandardCharsets.UTF_8), body);
        }
    }

    @Test
    void networkLogsDoNotContainSensitiveRequestValues() throws Exception {
        String token = "SENTINEL_TOKEN_MUST_NOT_LOG";
        String key = "SENTINEL_IDEMPOTENCY_MUST_NOT_LOG";
        String body = "SENTINEL_BODY_MUST_NOT_LOG";
        String url = "/v1/meetings?private=SENTINEL_URL_MUST_NOT_LOG";
        CopyOnWriteArrayList<String> messages = new CopyOnWriteArrayList<>();
        SimpleFormatter formatter = new SimpleFormatter();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                messages.add(formatter.formatMessage(record));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger root = Logger.getLogger("");
        root.addHandler(capture);
        try (Fixture fixture = new Fixture()) {
            assertEquals(501, status(fixture.send(request("POST", url,
                    "Authorization: Bearer " + token + "\r\n"
                            + "Idempotency-Key: " + key + "\r\n", body))));
            assertEquals(400, status(fixture.send(request("POST", url,
                    "Authorization: Bearer " + token + "\r\n"
                            + "authorization: Bearer duplicate\r\n", body))));
        } finally {
            root.removeHandler(capture);
        }
        String captured = String.join("\n", messages);
        assertFalse(messages.isEmpty());
        for (String secret : new String[]{token, key, body, url, "SENTINEL_URL_MUST_NOT_LOG"}) {
            assertFalse(captured.contains(secret), "log exposed request secret");
        }
    }

    @Test
    void slowBodyConnectionsReleaseWorkersAfterReadDeadline() throws Exception {
        try (Fixture fixture = new Fixture(null, Duration.ofMillis(300))) {
            try (Socket first = slowUpload(fixture);
                 Socket second = slowUpload(fixture)) {
                long activeDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (fixture.server.liveWorkersForTest() < 2
                        && System.nanoTime() < activeDeadline) {
                    Thread.sleep(10);
                }
                assertEquals(2, fixture.server.liveWorkersForTest(),
                        "两个慢速正文请求应各占一个 worker");

                long started = System.nanoTime();
                String normal = fixture.send(request(
                        "GET", "/v1/meetings?pageSize=7", "", ""));
                long elapsedMs = (System.nanoTime() - started) / 1_000_000;
                assertEquals(401, status(normal));
                assertTrue(elapsedMs < 3_000,
                        "正文读取超时后 worker 必须释放，实际耗时 " + elapsedMs + "ms");

                // watchdog 关闭连接后，慢速客户端读到 EOF，不会把 worker 留到永久。
                first.setSoTimeout(3_000);
                second.setSoTimeout(3_000);
                try (InputStream ignored = first.getInputStream()) {
                    assertEquals(-1, ignored.read());
                }
                try (InputStream ignored = second.getInputStream()) {
                    assertEquals(-1, ignored.read());
                }
            }
        }
    }

    private static Socket slowUpload(Fixture fixture) throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(HOST, fixture.server.address().getPort()));
        OutputStream output = socket.getOutputStream();
        output.write(("POST /v1/meetings HTTP/1.1\r\nHost: localhost\r\n"
                + "Content-Length: 64\r\nConnection: close\r\n\r\nx")
                .getBytes(StandardCharsets.US_ASCII));
        output.flush();
        return socket;
    }

    private static int findHeaderEnd(byte[] response) {
        for (int i = 0; i <= response.length - 4; i++) {
            if (response[i] == '\r' && response[i + 1] == '\n'
                    && response[i + 2] == '\r' && response[i + 3] == '\n') {
                return i;
            }
        }
        throw new AssertionError("HTTP response has no header separator");
    }

    private static String headerValue(String head, String key) {
        String prefix = key.toLowerCase(Locale.ROOT) + ":";
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        throw new AssertionError("HTTP response has no expected header: " + key);
    }

    private static byte[] request(String method, String uri, String extraHeaders, String body) {
        byte[] content = body.getBytes(StandardCharsets.UTF_8);
        byte[] prefix = (method + " " + uri + " HTTP/1.1\r\nHost: localhost\r\n"
                + extraHeaders + "Content-Length: " + content.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] result = new byte[prefix.length + content.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(content, 0, result, prefix.length, content.length);
        return result;
    }

    private static int status(String response) {
        String[] words = response.substring(0, response.indexOf("\r\n")).split(" ");
        return Integer.parseInt(words[1]);
    }

    private static final class Fixture implements AutoCloseable {
        private final MeetingHttpRuntime runtime;
        private final MeetingJdkHttpServer server;
        private int queries;

        private Fixture() throws Exception {
            this(null, null);
        }

        private Fixture(Long mediaBudget) throws Exception {
            this(mediaBudget, null);
        }

        private Fixture(Long mediaBudget, Duration bodyReadTimeout) throws Exception {
            MeetingQueryPort query = new MeetingQueryPort() {
                @Override
                public Result get(GetRequest request) {
                    queries++;
                    return new NotFound();
                }

                @Override
                public ListResult list(ListRequest request) {
                    queries++;
                    return new ListNotFound();
                }
            };
            MeetingDeletionPort deletion = new MeetingDeletionPort() {
                @Override
                public DeleteResult delete(DeleteRequest request) {
                    throw new AssertionError("delete must not run");
                }
            };
            MeetingHttpAdapter.RequestDecoder decoder = new MeetingHttpAdapter.RequestDecoder() {
                @Override
                public DeleteRequest decodeDelete(HttpRequest request, MeetingCommandContext context) {
                    throw new AssertionError("delete decoder must not run");
                }
            };
            HttpErrorMapper errors = new HttpErrorMapper(() -> "network-test-diag");
            runtime = new MeetingHttpRuntime(new MeetingHttpAdapter(
                    query, deletion, decoder, errors), errors);
            var address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
            if (bodyReadTimeout != null) {
                server = new MeetingJdkHttpServer(address, runtime, errors, null, () -> true,
                        2, 16, bodyReadTimeout);
            } else if (mediaBudget == null) {
                server = new MeetingJdkHttpServer(address, runtime, errors);
            } else {
                server = new MeetingJdkHttpServer(address,
                        new MeetingHttpDispatcher(runtime, errors, null, null), () -> true, mediaBudget);
            }
            server.start();
        }

        private String send(byte[] request) throws Exception {
            return new String(sendBytes(request), StandardCharsets.UTF_8);
        }

        private byte[] sendBytes(byte[] request) throws Exception {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(HOST, server.address().getPort()));
                socket.setSoTimeout(10_000);
                socket.getOutputStream().write(request);
                socket.getOutputStream().flush();
                socket.shutdownOutput();
                ByteArrayOutputStream received = new ByteArrayOutputStream();
                socket.getInputStream().transferTo(received);
                return received.toByteArray();
            }
        }

        @Override
        public void close() {
            server.close();
            runtime.close();
        }
    }
}

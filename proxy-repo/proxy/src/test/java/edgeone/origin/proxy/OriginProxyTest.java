package edgeone.origin.proxy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edgeone.origin.common.TokenStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.s3.S3Client;

/** End-to-end: HTTP client -> Jetty -> OriginHandler -> AWS SDK -> fake S3. */
class OriginProxyTest {

    static final String TOKEN = "a".repeat(64);
    static final String SECRET = "{\"tokens\":[\"" + TOKEN + "\"]}";

    FakeS3 s3;
    S3Client client;
    Server server;
    URI base;
    final AtomicReference<String> secret = new AtomicReference<>(SECRET);
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        s3 = new FakeS3();
        client = s3.client();
        startProxy("no-store");
    }

    void startProxy(String defaultCacheControl) throws Exception {
        if (server != null) {
            server.stop();
        }
        TokenStore tokens = new TokenStore(() -> {
            String value = secret.get();
            if (value == null) {
                throw new IllegalStateException("secrets manager down");
            }
            return value;
        });
        ProxyConfig config = new ProxyConfig(FakeS3.BUCKET, "public/", "arn", "eu-central-1",
                defaultCacheControl, 0, Duration.ofSeconds(125), 16);
        server = OriginServer.create(config, new OriginHandler(tokens,
                new S3Origin(client, FakeS3.BUCKET, defaultCacheControl), config.prefix()));
        server.start();
        base = URI.create("http://127.0.0.1:" + ((ServerConnector) server.getConnectors()[0]).getLocalPort());
    }

    @AfterEach
    void stop() throws Exception {
        server.stop();
        client.close();
        s3.close();
        http.close();
    }

    HttpResponse<byte[]> send(String method, String path, String... headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (headers.length > 0) {
            builder.headers(headers);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    HttpResponse<byte[]> get(String path, String... headers) throws Exception {
        String[] all = new String[headers.length + 2];
        all[0] = OriginHandler.TOKEN_HEADER;
        all[1] = TOKEN;
        System.arraycopy(headers, 0, all, 2, headers.length);
        return send("GET", path, all);
    }

    /** Sends a request line verbatim (no client-side normalization) and returns the status code. */
    int raw(String target, String... extraHeaderLines) throws IOException {
        try (Socket socket = new Socket(base.getHost(), base.getPort())) {
            StringBuilder request = new StringBuilder("GET " + target + " HTTP/1.1\r\nHost: origin\r\nConnection: close\r\n");
            for (String line : extraHeaderLines) {
                request.append(line).append("\r\n");
            }
            OutputStream out = socket.getOutputStream();
            out.write(request.append("\r\n").toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) != -1 && c != '\n') {
                line.write(c);
            }
            return Integer.parseInt(line.toString(StandardCharsets.ISO_8859_1).split(" ")[1]);
        }
    }

    @Test
    void authenticationMethodsAndReservedPaths() throws Exception {
        assertEquals(403, send("GET", "/a", OriginHandler.TOKEN_HEADER, "wrong").statusCode());
        assertEquals(403, send("GET", "/a").statusCode());
        HttpResponse<byte[]> post = send("POST", "/a", OriginHandler.TOKEN_HEADER, TOKEN);
        assertEquals(405, post.statusCode());
        assertEquals("GET, HEAD", post.headers().firstValue("Allow").orElseThrow());
        assertEquals(404, get("/api/users").statusCode());
        assertEquals(404, get("/api").statusCode());
        HttpResponse<byte[]> health = send("GET", "/_origin_health");
        assertEquals(200, health.statusCode());
        assertEquals("ok", new String(health.body(), StandardCharsets.UTF_8));
        // Duplicated token header (client value not removed on the edge) is rejected.
        assertEquals(403, raw("/a", "X-Origin-Verify: " + TOKEN, "X-Origin-Verify: " + TOKEN));
        assertTrue(s3.received.isEmpty(), "nothing above may reach S3");
    }

    @Test
    void secretsOutageFailsClosedWith503() throws Exception {
        secret.set(null);
        HttpResponse<byte[]> response = get("/a");
        assertEquals(503, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }

    @ParameterizedTest
    @CsvSource({
        "/../private.txt, 400", "/%2e%2e/secret, 400", "/a/%2E%2E/b, 400", "/a/./b, 400",
        "/x%ff, 400", "/a%5Cb, 400", "/a%00b, 400"})
    void traversalAndMalformedPathsNeverReachS3(String target, int expected) throws Exception {
        assertEquals(expected, raw(target, "X-Origin-Verify: " + TOKEN));
        assertTrue(s3.received.isEmpty());
    }

    @Test
    void encodedCharactersAreDecodedOnce() throws Exception {
        s3.responder = r -> FakeS3.Reply.ok("x".getBytes(StandardCharsets.UTF_8), Map.of("Content-Type", "text/plain"));
        assertEquals(200, raw("/%C4%85.txt", "X-Origin-Verify: " + TOKEN));
        assertEquals("public/ą.txt", s3.last().key());
        assertEquals(200, raw("/a+b%20c", "X-Origin-Verify: " + TOKEN));
        assertEquals("public/a+b c", s3.last().key());
        int before = s3.received.size();
        // Jetty's default URI compliance rejects ambiguous encodings (%25, %2F) before the handler.
        assertEquals(400, raw("/a%2520c", "X-Origin-Verify: " + TOKEN));
        assertEquals(400, raw("/a%2Fb", "X-Origin-Verify: " + TOKEN));
        assertEquals(before, s3.received.size());
    }

    @Test
    void streamsBinaryRootIndexAndIgnoresQuery() throws Exception {
        byte[] payload = {0, (byte) 0xff, (byte) 0x80, 'a', 'b', 'c'};
        s3.responder = r -> FakeS3.Reply.ok(payload, Map.of("Content-Type", "application/octet-stream",
                "Cache-Control", "public, max-age=60", "ETag", "\"abc\"", "Last-Modified", "Wed, 21 Oct 2026 07:28:00 GMT"));
        HttpResponse<byte[]> response = get("/?versionId=evil&response-content-type=text/html");
        assertEquals(200, response.statusCode());
        assertArrayEquals(payload, response.body());
        assertEquals("6", response.headers().firstValue("Content-Length").orElseThrow());
        assertEquals("public, max-age=60", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("\"abc\"", response.headers().firstValue("ETag").orElseThrow());
        assertEquals("Wed, 21 Oct 2026 07:28:00 GMT", response.headers().firstValue("Last-Modified").orElseThrow());
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
        assertEquals("public/index.html", s3.last().key());
        assertFalse(s3.last().query() != null && s3.last().query().contains("versionId"));
        assertFalse(s3.last().query() != null && s3.last().query().contains("response-content-type"));
    }

    @Test
    void defaultCacheControlForObjectsWithoutMetadata() throws Exception {
        s3.responder = r -> FakeS3.Reply.ok(new byte[1], Map.of());
        assertEquals("no-store", send("HEAD", "/a", OriginHandler.TOKEN_HEADER, TOKEN).headers().firstValue("Cache-Control").orElseThrow());
        startProxy("public, max-age=300");
        assertEquals("public, max-age=300", send("HEAD", "/a", OriginHandler.TOKEN_HEADER, TOKEN).headers().firstValue("Cache-Control").orElseThrow());
    }

    @Test
    void headHasNoBody() throws Exception {
        s3.responder = r -> FakeS3.Reply.ok(new byte[100], Map.of("ETag", "\"abc\""));
        HttpResponse<byte[]> response = send("HEAD", "/a.txt", OriginHandler.TOKEN_HEADER, TOKEN);
        assertEquals(200, response.statusCode());
        assertEquals(0, response.body().length);
        assertEquals("100", response.headers().firstValue("Content-Length").orElseThrow());
        assertEquals("HEAD", s3.last().method());
    }

    @Test
    void singleRangeIsForwarded() throws Exception {
        s3.responder = r -> new FakeS3.Reply(206, Map.of("Content-Range", "bytes 0-1/100"), "ab".getBytes(StandardCharsets.UTF_8));
        HttpResponse<byte[]> response = get("/a", "Range", "bytes=0-1");
        assertEquals(206, response.statusCode());
        assertEquals("ab", new String(response.body(), StandardCharsets.UTF_8));
        assertEquals("bytes 0-1/100", response.headers().firstValue("Content-Range").orElseThrow());
        assertEquals("bytes=0-1", s3.last().header("Range"));
    }

    @Test
    void ifRangeAndMultiRangeFallBackToFullObject() throws Exception {
        s3.responder = r -> FakeS3.Reply.ok("abc".getBytes(StandardCharsets.UTF_8), Map.of());
        assertEquals("abc", new String(get("/a", "Range", "bytes=0-1", "If-Range", "\"old\"").body(), StandardCharsets.UTF_8));
        assertNull(s3.last().header("Range"));
        get("/a", "Range", "bytes=0-1,5-6");
        assertNull(s3.last().header("Range"));
    }

    @Test
    void notModifiedKeepsValidators() throws Exception {
        s3.responder = r -> new FakeS3.Reply(304, Map.of("ETag", "\"etag\""), new byte[0]);
        HttpResponse<byte[]> response = get("/a", "If-None-Match", "\"etag\"");
        assertEquals(304, response.statusCode());
        assertEquals(0, response.body().length);
        assertEquals("\"etag\"", response.headers().firstValue("ETag").orElseThrow());
        assertEquals("\"etag\"", s3.last().header("If-None-Match"));
    }

    @ParameterizedTest
    @CsvSource({"403, AccessDenied, 404", "404, NoSuchKey, 404", "500, InternalError, 502", "412, PreconditionFailed, 412"})
    void s3ErrorsAreMapped(int s3Status, String code, int expected) throws Exception {
        s3.responder = r -> FakeS3.Reply.error(s3Status, code);
        HttpResponse<byte[]> response = get("/a");
        assertEquals(expected, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }

    @Test
    void unsatisfiableRangeReportsSize() throws Exception {
        s3.responder = r -> "HEAD".equals(r.method())
                ? FakeS3.Reply.ok(new byte[10], Map.of())
                : FakeS3.Reply.error(416, "InvalidRange");
        HttpResponse<byte[]> response = get("/a", "Range", "bytes=100-200");
        assertEquals(416, response.statusCode());
        assertEquals("bytes */10", response.headers().firstValue("Content-Range").orElseThrow());
    }

    @Test
    void s3UnreachableIs502() throws Exception {
        s3.close();
        assertEquals(502, get("/a").statusCode());
    }

    @Test
    void overloadRejectsWithoutUpstreamCallAndRecovers() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(16);
        var release = new java.util.concurrent.CountDownLatch(1);
        s3.responder = r -> {
            entered.countDown();
            try {
                if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    return FakeS3.Reply.error(500, "TestTimeout");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return FakeS3.Reply.ok(new byte[1024], Map.of());
        };
        var requests = new java.util.ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<byte[]>>>();
        try {
            for (int i = 0; i < 16; i++) {
                requests.add(http.sendAsync(HttpRequest.newBuilder(base.resolve("/slow-" + i))
                        .header(OriginHandler.TOKEN_HEADER, TOKEN).timeout(Duration.ofSeconds(15)).build(),
                        HttpResponse.BodyHandlers.ofByteArray()));
            }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            int upstream = s3.received.size();
            HttpResponse<byte[]> rejected = get("/overflow");
            assertEquals(503, rejected.statusCode());
            assertEquals("1", rejected.headers().firstValue("Retry-After").orElseThrow());
            assertEquals("no-store", rejected.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals(upstream, s3.received.size());
            assertEquals(200, send("GET", "/_origin_health").statusCode());
            AdmissionHandler gate = (AdmissionHandler) server.getHandler();
            assertEquals(16, gate.active());
            assertEquals(16, gate.peak());
            assertEquals(1, gate.rejected());
        } finally {
            release.countDown();
        }
        for (var future : requests) {
            assertEquals(200, future.get(10, java.util.concurrent.TimeUnit.SECONDS).statusCode());
        }
        assertEquals(200, get("/after").statusCode());
        awaitIdle();
    }

    @Test
    void errorsAndDenialsReleaseAdmissionSlots() throws Exception {
        for (int i = 0; i < 32; i++) {
            assertEquals(403, send("GET", "/a").statusCode());
        }
        s3.responder = r -> FakeS3.Reply.error(500, "InternalError");
        for (int i = 0; i < 32; i++) {
            assertEquals(502, get("/a").statusCode());
        }
        awaitIdle();
        assertEquals(0, ((AdmissionHandler) server.getHandler()).rejected());
    }

    /**
     * The slot is released when Jetty completes the response callback, which can happen just
     * after the client has read the last byte. Poll instead of asserting immediately.
     */
    void awaitIdle() throws InterruptedException {
        AdmissionHandler gate = (AdmissionHandler) server.getHandler();
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (gate.active() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(0, gate.active(), "admission slots must be released");
    }

    @Test
    void idleTimeoutOutlivesAlb() {
        assertEquals(125_000, ((ServerConnector) server.getConnectors()[0]).getIdleTimeout());
    }

    @Test
    void invalidDefaultCacheControlFallsBackToNoStore() {
        assertEquals("no-store", ProxyConfig.cacheControl(null));
        assertEquals("no-store", ProxyConfig.cacheControl(""));
        assertEquals("no-store", ProxyConfig.cacheControl("x\r\nSet-Cookie: a=b"));
        assertEquals("public, max-age=300", ProxyConfig.cacheControl(" public, max-age=300 "));
    }
}

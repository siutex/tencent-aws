package edgeone.origin.proxy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Minimal S3 REST endpoint (path-style) for tests: the real AWS SDK talks HTTP to it, so status
 * codes, headers and error parsing are exercised exactly as against S3. No network beyond loopback.
 */
final class FakeS3 implements AutoCloseable {

    record Received(String method, String key, String query, Map<String, List<String>> headers) {
        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().getFirst()).findFirst().orElse(null);
        }
    }

    record Reply(int status, Map<String, String> headers, byte[] body) {
        static Reply ok(byte[] body, Map<String, String> headers) {
            return new Reply(200, headers, body);
        }

        static Reply error(int status, String code) {
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>" + code
                    + "</Code><Message>test</Message><RequestId>1</RequestId></Error>";
            return new Reply(status, Map.of("Content-Type", "application/xml"), xml.getBytes(StandardCharsets.UTF_8));
        }
    }

    static final String BUCKET = "assets-test";

    final List<Received> received = new CopyOnWriteArrayList<>();
    volatile Function<Received, Reply> responder = r -> Reply.error(404, "NoSuchKey");
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    FakeS3() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    S3Client client() {
        return S3Client.builder()
                .endpointOverride(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .forcePathStyle(true)
                .region(Region.EU_CENTRAL_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("testing", "testing")))
                .httpClientBuilder(Apache5HttpClient.builder())
                .overrideConfiguration(o -> o.retryStrategy(b -> b.maxAttempts(1)))
                .build();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String prefix = "/" + BUCKET + "/";
        String key = path.startsWith(prefix) ? path.substring(prefix.length()) : path;
        Received request = new Received(exchange.getRequestMethod(), key, exchange.getRequestURI().getRawQuery(),
                Map.copyOf(exchange.getRequestHeaders()));
        received.add(request);
        Reply reply = responder.apply(request);
        reply.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
        boolean noBody = "HEAD".equals(exchange.getRequestMethod()) || reply.status() == 304;
        if (noBody) {
            if (reply.body() != null && "HEAD".equals(exchange.getRequestMethod()) && reply.status() < 300) {
                exchange.getResponseHeaders().set("Content-Length", Integer.toString(reply.body().length));
            }
            exchange.sendResponseHeaders(reply.status(), -1);
        } else {
            exchange.sendResponseHeaders(reply.status(), reply.body().length == 0 ? -1 : reply.body().length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(reply.body());
            }
        }
        exchange.close();
    }

    Received last() {
        return received.getLast();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}

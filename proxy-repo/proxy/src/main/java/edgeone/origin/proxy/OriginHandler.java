package edgeone.origin.proxy;

import edgeone.origin.common.TokenStore;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import software.amazon.awssdk.core.ResponseInputStream;

/**
 * Read-only S3 origin: GET/HEAD, {@code /} = {@code <prefix>index.html}, a single Range,
 * 206/304/412/416, ETag and S3 metadata. No writes, no listing, no versions, no query overrides.
 * Blocking code is fine here: Jetty runs this handler on virtual threads.
 */
final class OriginHandler extends Handler.Abstract {

    static final String HEALTH_PATH = "/_origin_health";
    static final String TOKEN_HEADER = "X-Origin-Verify";
    private static final Logger LOG = System.getLogger(OriginHandler.class.getName());
    private static final int BUFFER = 64 * 1024;

    private final TokenStore tokens;
    private final S3Origin origin;
    private final String prefix;

    OriginHandler(TokenStore tokens, S3Origin origin, String prefix) {
        this.tokens = tokens;
        this.origin = origin;
        this.prefix = prefix;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) {
        String method = request.getMethod();
        boolean head = "HEAD".equals(method);
        String rawPath = request.getHttpURI().getPath();
        if (HEALTH_PATH.equals(rawPath) && (head || "GET".equals(method))) {
            // Process liveness only; does not check S3 access. Block this path on the edge.
            text(response, callback, head, 200, "ok", List.of());
            return true;
        }
        try {
            List<String> presented = request.getHeaders().getValuesList(TOKEN_HEADER);
            if (presented.size() != 1 || !tokens.allowed(presented.getFirst())) {
                text(response, callback, head, 403, "Forbidden", List.of());
                return true;
            }
        } catch (Exception e) {
            LOG.log(Level.ERROR, "origin_auth_unavailable");
            text(response, callback, head, 503, "Origin unavailable", List.of());
            return true;
        }
        if (!head && !"GET".equals(method)) {
            text(response, callback, false, 405, "Method not allowed", List.of(new S3Origin.Header("Allow", "GET, HEAD")));
            return true;
        }
        switch (ObjectPath.resolve(rawPath, prefix)) {
            case ObjectPath.Invalid invalid -> text(response, callback, head, 400, "Invalid path", List.of());
            case ObjectPath.Reserved reserved -> text(response, callback, head, 404, "Not found", List.of());
            case ObjectPath.Key key -> serve(query(request, key.key(), head), response, callback);
        }
        return true;
    }

    private static S3Origin.Query query(Request request, String key, boolean head) {
        HttpFields headers = request.getHeaders();
        String range = head ? null : headers.get(HttpHeader.RANGE);
        if (range != null && headers.get(HttpHeader.IF_RANGE) != null) {
            // Ignoring Range is permitted: a full 200 avoids a HEAD/GET race without If-Range logic.
            range = null;
        }
        if (range != null && (range.indexOf(',') >= 0 || !range.startsWith("bytes="))) {
            range = null; // S3 serves one range, not multipart/byteranges
        }
        return new S3Origin.Query(key, head, range,
                headers.get(HttpHeader.IF_NONE_MATCH), headers.get(HttpHeader.IF_MATCH),
                httpDate(headers.get(HttpHeader.IF_MODIFIED_SINCE)), httpDate(headers.get(HttpHeader.IF_UNMODIFIED_SINCE)));
    }

    private void serve(S3Origin.Query query, Response response, Callback callback) {
        switch (origin.fetch(query)) {
            case S3Origin.Found found -> {
                response.setStatus(found.status());
                setHeaders(response, found.headers());
                if (found.body() == null) {
                    callback.succeeded();
                } else {
                    stream(found.body(), response, callback);
                }
            }
            case S3Origin.NotModified notModified -> {
                response.setStatus(304);
                setHeaders(response, notModified.headers());
                callback.succeeded();
            }
            case S3Origin.Failed failed -> text(response, callback, query.head(), failed.status(), failed.message(), failed.headers());
        }
    }

    /** Streams the object; on any failure the S3 connection is aborted, not drained. */
    private static void stream(ResponseInputStream<?> body, Response response, Callback callback) {
        boolean complete = false;
        try (OutputStream out = Content.Sink.asOutputStream(response)) {
            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = body.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            complete = true;
        } catch (IOException | RuntimeException e) {
            body.abort();
            callback.failed(e);
            return;
        }
        if (complete) {
            try {
                body.close();
            } catch (IOException ignored) {
                // the response is complete; nothing to report to the client
            }
        }
        callback.succeeded();
    }

    private static void setHeaders(Response response, List<S3Origin.Header> headers) {
        HttpFields.Mutable fields = response.getHeaders();
        for (S3Origin.Header header : headers) {
            fields.put(new HttpField(header.name(), header.value()));
        }
    }

    static void text(Response response, Callback callback, boolean head, int status, String message,
                     List<S3Origin.Header> extra) {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        response.setStatus(status);
        HttpFields.Mutable fields = response.getHeaders();
        fields.put(HttpHeader.CONTENT_TYPE, "text/plain; charset=utf-8");
        fields.put(HttpHeader.CACHE_CONTROL, "no-store");
        setHeaders(response, extra);
        fields.put(HttpHeader.CONTENT_LENGTH, Integer.toString(bytes.length));
        if (head) {
            callback.succeeded();
        } else {
            response.write(true, ByteBuffer.wrap(bytes), callback);
        }
    }

    private static Instant httpDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            return null; // an invalid conditional header is ignored
        }
    }
}

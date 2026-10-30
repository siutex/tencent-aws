package edgeone.origin.proxy;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Read-only access to one bucket with the task role; maps S3 results to origin responses. */
final class S3Origin {

    record Header(String name, String value) {
    }

    /** One object read. Query strings never reach S3 (no versionId, no response-* overrides). */
    record Query(String key, boolean head, String range, String ifNoneMatch, String ifMatch,
                 Instant ifModifiedSince, Instant ifUnmodifiedSince) {
    }

    sealed interface Result permits Found, NotModified, Failed {
    }

    /** 200/206 from S3. {@code body} is null for HEAD; otherwise the caller must close or abort it. */
    record Found(int status, List<Header> headers, ResponseInputStream<GetObjectResponse> body) implements Result {
    }

    record NotModified(List<Header> headers) implements Result {
    }

    record Failed(int status, String message, List<Header> headers) implements Result {
    }

    private static final Logger LOG = System.getLogger(S3Origin.class.getName());
    /** S3 response headers passed to EdgeOne verbatim. */
    private static final List<String> PASS_THROUGH = List.of("Content-Type", "Content-Length", "ETag",
            "Content-Range", "Content-Encoding", "Content-Disposition", "Content-Language", "Last-Modified", "Expires");
    private static final List<String> NOT_MODIFIED = List.of("ETag", "Last-Modified", "Cache-Control", "Expires", "Vary");

    private final S3Client s3;
    private final String bucket;
    private final String defaultCacheControl;

    S3Origin(S3Client s3, String bucket, String defaultCacheControl) {
        this.s3 = s3;
        this.bucket = bucket;
        this.defaultCacheControl = defaultCacheControl;
    }

    Result fetch(Query query) {
        try {
            if (query.head()) {
                HeadObjectResponse response = s3.headObject(HeadObjectRequest.builder()
                        .bucket(bucket).key(query.key())
                        .ifNoneMatch(query.ifNoneMatch()).ifMatch(query.ifMatch())
                        .ifModifiedSince(query.ifModifiedSince()).ifUnmodifiedSince(query.ifUnmodifiedSince())
                        .build());
                return new Found(response.sdkHttpResponse().statusCode(), headers(response.sdkHttpResponse()), null);
            }
            ResponseInputStream<GetObjectResponse> body = s3.getObject(GetObjectRequest.builder()
                    .bucket(bucket).key(query.key()).range(query.range())
                    .ifNoneMatch(query.ifNoneMatch()).ifMatch(query.ifMatch())
                    .ifModifiedSince(query.ifModifiedSince()).ifUnmodifiedSince(query.ifUnmodifiedSince())
                    .build());
            SdkHttpResponse http = body.response().sdkHttpResponse();
            return new Found(http.statusCode(), headers(http), body);
        } catch (S3Exception e) {
            return failure(query, e);
        } catch (SdkException e) {
            LOG.log(Level.ERROR, "s3_transport_failure error={0}", e.getClass().getSimpleName());
            return new Failed(502, "Origin unavailable", List.of());
        }
    }

    private Result failure(Query query, S3Exception e) {
        int status = e.statusCode();
        return switch (status) {
            case 304 -> new NotModified(notModifiedHeaders(e));
            // Without s3:ListBucket, S3 returns 403 for missing keys too: both become 404.
            case 403, 404 -> new Failed(404, "Not Found", List.of());
            case 416 -> new Failed(416, "Range Not Satisfiable", contentRange(query.key()));
            case 400 -> new Failed(400, "Bad Request", List.of());
            case 412 -> new Failed(412, "Precondition Failed", List.of());
            default -> {
                LOG.log(Level.ERROR, "s3_origin_failure status={0}", String.valueOf(status));
                yield new Failed(502, "Bad Gateway", List.of());
            }
        };
    }

    private List<Header> headers(SdkHttpResponse http) {
        List<Header> headers = new ArrayList<>();
        headers.add(new Header("X-Content-Type-Options", "nosniff"));
        headers.add(new Header("Accept-Ranges", "bytes"));
        for (String name : PASS_THROUGH) {
            http.firstMatchingHeader(name).ifPresent(value -> headers.add(new Header(name, value)));
        }
        // Objects must opt into shared caching with explicit metadata (or the configured default).
        headers.add(new Header("Cache-Control", http.firstMatchingHeader("Cache-Control").orElse(defaultCacheControl)));
        return headers;
    }

    private static List<Header> notModifiedHeaders(S3Exception e) {
        if (e.awsErrorDetails() == null || e.awsErrorDetails().sdkHttpResponse() == null) {
            return List.of();
        }
        SdkHttpResponse http = e.awsErrorDetails().sdkHttpResponse();
        List<Header> headers = new ArrayList<>();
        for (String name : NOT_MODIFIED) {
            http.firstMatchingHeader(name).ifPresent(value -> headers.add(new Header(name, value)));
        }
        return headers;
    }

    private List<Header> contentRange(String key) {
        try {
            Long length = s3.headObject(b -> b.bucket(bucket).key(key)).contentLength();
            return length == null ? List.of() : List.of(new Header("Content-Range", "bytes */" + length));
        } catch (SdkException e) {
            return List.of();
        }
    }
}

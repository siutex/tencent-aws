package edgeone.origin.proxy;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Map;

/**
 * Proxy settings from the ECS task definition (Terraform). Required: BUCKET, SECRET_ARN, AWS_REGION.
 *
 * @param idleTimeout    connection idle timeout; must be longer than the ALB idle timeout, otherwise
 *                       the ALB can reuse a connection the proxy has just closed (sporadic 502s)
 * @param maxConcurrency concurrent S3 reads per task (size of the S3 connection pool)
 */
public record ProxyConfig(String bucket, String prefix, String secretArn, String region,
                          String defaultCacheControl, int port, Duration idleTimeout, int maxConcurrency) {

    private static final Logger LOG = System.getLogger(ProxyConfig.class.getName());

    public static ProxyConfig fromEnv(Map<String, String> env) {
        return new ProxyConfig(
                required(env, "BUCKET"),
                env.getOrDefault("PREFIX", "public/"),
                required(env, "SECRET_ARN"),
                required(env, "AWS_REGION"),
                cacheControl(env.get("DEFAULT_CACHE_CONTROL")),
                intValue(env, "PROXY_PORT", 8080, 1, 65535),
                Duration.ofSeconds(intValue(env, "PROXY_IDLE_TIMEOUT_SECONDS", 125, 1, 4000)),
                intValue(env, "PROXY_MAX_CONCURRENCY", 256, 1, 4096));
    }

    /**
     * Cache-Control for objects WITHOUT Cache-Control metadata. "no-store" keeps them out of shared
     * caches until publishers or EdgeOne cache rules opt them in explicitly. Invalid values (control
     * characters = header injection) fall back to "no-store".
     */
    static String cacheControl(String raw) {
        if (raw == null) {
            return "no-store";
        }
        String value = raw.strip();
        if (value.isEmpty() || value.length() > 256 || value.chars().anyMatch(c -> c < 32 || c == 127)) {
            LOG.log(Level.ERROR, "invalid_default_cache_control; using no-store");
            return "no-store";
        }
        return value;
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable " + name);
        }
        return value;
    }

    private static int intValue(Map<String, String> env, String name, int defaultValue, int min, int max) {
        String raw = env.get(name);
        int value = raw == null ? defaultValue : Integer.parseInt(raw.strip());
        if (value < min || value > max) {
            throw new IllegalStateException(name + " must be between " + min + " and " + max);
        }
        return value;
    }
}

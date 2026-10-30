package edgeone.origin.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Validates the {@code X-Origin-Verify} value against the active origin tokens.
 * Tokens are cached for 30 s, stale values are used for at most 60 s more, then access fails closed.
 * Never log request headers or secret values.
 */
public final class TokenStore {

    public static final Duration TTL = Duration.ofSeconds(30);
    public static final Duration MAX_STALE = Duration.ofSeconds(60);
    public static final Duration RETRY_AFTER = Duration.ofSeconds(5);
    public static final Duration WAIT_TIMEOUT = Duration.ofSeconds(10);

    private final RefreshingValue<List<String>> tokens;

    public TokenStore(SecretSource source) {
        this(source, TTL, MAX_STALE, RETRY_AFTER, WAIT_TIMEOUT, System::nanoTime);
    }

    public TokenStore(SecretSource source, Duration ttl, Duration maxStale, Duration retryAfter,
                      Duration waitTimeout, LongSupplier nanoClock) {
        this.tokens = new RefreshingValue<>("origin_tokens", () -> Tokens.parseSecret(source.fetch()),
                ttl, maxStale, retryAfter, waitTimeout, nanoClock);
    }

    /**
     * @return true if the candidate matches an active token
     * @throws ValueUnavailableException if no usable token set exists (caller must fail closed)
     */
    public boolean allowed(String candidate) throws ValueUnavailableException {
        if (!Tokens.wellFormed(candidate)) {
            return false;
        }
        byte[] presented = candidate.getBytes(StandardCharsets.US_ASCII);
        int matches = 0;
        // Compare against every token (both are active during rotation) without short-circuiting.
        for (String token : tokens.get()) {
            matches |= MessageDigest.isEqual(presented, token.getBytes(StandardCharsets.US_ASCII)) ? 1 : 0;
        }
        return matches == 1;
    }

    public void invalidate() {
        tokens.invalidate();
    }
}

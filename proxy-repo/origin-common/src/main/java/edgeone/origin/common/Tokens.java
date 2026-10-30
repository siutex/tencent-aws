package edgeone.origin.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Origin token format and secret layout: {@code {"tokens": ["<token>", ...]}} with one token, or
 * two during rotation. The same rules are used by the proxy, the authorizer and the tools, so a
 * value written by the tools is always accepted by the readers.
 */
public final class Tokens {

    public static final String SECRET_FIELD = "tokens";
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{32,256}");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Tokens() {
    }

    /** True if the candidate has the token shape. Header lists joined with commas never match. */
    public static boolean wellFormed(String candidate) {
        return candidate != null && TOKEN.matcher(candidate).matches();
    }

    /** Returns an immutable list of 1-2 well-formed, distinct tokens or throws. */
    public static List<String> validate(List<?> values) {
        if (values == null || values.isEmpty() || values.size() > 2) {
            throw new IllegalArgumentException("Invalid origin token configuration");
        }
        List<String> tokens = new ArrayList<>(values.size());
        for (Object value : values) {
            if (!(value instanceof String token) || !wellFormed(token)) {
                throw new IllegalArgumentException("Invalid origin token configuration");
            }
            tokens.add(token);
        }
        if (new HashSet<>(tokens).size() != tokens.size()) {
            throw new IllegalArgumentException("Invalid origin token configuration");
        }
        return List.copyOf(tokens);
    }

    /** Parses and validates a secret string. Never includes the input in exception messages. */
    public static List<String> parseSecret(String secretString) {
        JsonNode root;
        try {
            root = JSON.readTree(secretString);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid origin token configuration");
        }
        if (root == null || !root.isObject() || !(root.get(SECRET_FIELD) instanceof ArrayNode array)) {
            throw new IllegalArgumentException("Invalid origin token configuration");
        }
        List<Object> values = new ArrayList<>(array.size());
        for (JsonNode item : array) {
            values.add(item.isTextual() ? item.textValue() : item);
        }
        return validate(values);
    }

    public static String toSecretString(List<String> tokens) {
        try {
            return JSON.writeValueAsString(java.util.Map.of(SECRET_FIELD, validate(tokens)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** First 12 hex characters of SHA-256: lets two teams confirm a token without sharing it. */
    public static String fingerprint(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 384 random bits, base64url without padding: 64 characters. */
    public static String generate() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

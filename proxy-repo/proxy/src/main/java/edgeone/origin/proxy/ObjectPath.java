package edgeone.origin.proxy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Maps the raw (still percent-encoded) request path to an S3 key under the fixed prefix.
 * The path is decoded exactly once, as strict UTF-8; dot segments, backslashes and control
 * characters are rejected instead of normalized, so a key can never leave the prefix.
 */
final class ObjectPath {

    sealed interface Result permits Key, Invalid, Reserved {
    }

    record Key(String key) implements Result {
    }

    record Invalid() implements Result {
    }

    /** {@code /api} and {@code /api/*} belong to the API origin and must never fall through to S3. */
    record Reserved() implements Result {
    }

    private ObjectPath() {
    }

    static Result resolve(String rawPath, String prefix) {
        String path = decode(rawPath);
        if (path == null || !path.startsWith("/") || path.indexOf('\\') >= 0
                || path.chars().anyMatch(c -> c < 32 || c == 127)) {
            return new Invalid();
        }
        for (String segment : path.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return new Invalid();
            }
        }
        if (path.equals("/api") || path.startsWith("/api/")) {
            return new Reserved();
        }
        return new Key(prefix + (path.equals("/") ? "index.html" : path.substring(1)));
    }

    /** Percent-decodes once into strict UTF-8; {@code null} for malformed input. '+' stays '+'. */
    static String decode(String raw) {
        if (raw == null) {
            return null;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%') {
                if (i + 2 >= raw.length()) {
                    return null;
                }
                int hi = Character.digit(raw.charAt(i + 1), 16);
                int lo = Character.digit(raw.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    return null;
                }
                bytes.write((hi << 4) | lo);
                i += 2;
            } else if (c > 127) {
                return null; // raw non-ASCII is not a valid request-target
            } else {
                bytes.write(c);
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}

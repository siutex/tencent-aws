package edgeone.origin.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TokensAndCidrTest {

    @Test
    void secretRoundTripAndFingerprint() {
        String token = Tokens.generate();
        assertEquals(64, token.length());
        assertTrue(Tokens.wellFormed(token));
        assertNotEquals(token, Tokens.generate());
        String secret = Tokens.toSecretString(List.of(token));
        assertEquals("{\"tokens\":[\"" + token + "\"]}", secret);
        assertEquals(List.of(token), Tokens.parseSecret(secret));
        // printf %s TOKEN | sha256sum | cut -c1-12
        assertEquals("ffe054fe7ae0", Tokens.fingerprint("a".repeat(64)));
    }

    @Test
    void parseErrorsNeverEchoTheSecret() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Tokens.parseSecret("{\"tokens\":[\"leaked-value\"]}"));
        assertFalse(e.getMessage().contains("leaked"));
    }

    @Test
    void parsesAddressesStrictly() {
        assertEquals("203.0.113.7/32", Ipv4Cidr.parse("203.0.113.7").toString());
        assertEquals("8.8.8.0/24", Ipv4Cidr.parse("8.8.8.9/24").toString()); // host bits cleared
        assertEquals("0.0.0.0/0", Ipv4Cidr.parse("0.0.0.0/0").toString());
        assertTrue(Ipv4Cidr.tryParseAddress("255.255.255.255").isPresent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1.2.3", "1.2.3.4.5", "256.1.1.1", "01.2.3.4", "1.2.3.4/33", "1.2.3.4/08",
        "1.2.3.4/", "localhost", "2001:db8::1", "1.2.3.-1", " 1.2.3.4", "0x7f.0.0.1"})
    void rejectsNonLiteralInput(String text) {
        assertThrows(IllegalArgumentException.class, () -> Ipv4Cidr.parse(text));
    }

    @Test
    void containmentAndPublicRanges() {
        Ipv4Cidr net = Ipv4Cidr.parse("198.51.100.0/25");
        assertTrue(net.contains(Ipv4Cidr.parseAddress("198.51.100.127")));
        assertFalse(net.contains(Ipv4Cidr.parseAddress("198.51.100.128")));
        assertTrue(Ipv4Cidr.parse("43.0.0.0/24").isPublicUnicast());
        for (String nonPublic : List.of("10.0.0.0/8", "100.64.0.0/10", "192.168.1.0/24", "224.0.0.1", "8.0.0.0/4")) {
            assertFalse(Ipv4Cidr.parse(nonPublic).isPublicUnicast(), nonPublic);
        }
    }

    @Test
    void collapseMergesSiblingsAndDropsCovered() {
        List<Ipv4Cidr> in = List.of("1.0.0.128/25", "1.0.0.0/25", "9.9.9.9/32", "8.8.8.0/24", "8.8.8.4/30",
                "2.0.0.0/24", "2.0.1.0/24", "2.0.2.0/23").stream().map(Ipv4Cidr::parse).toList();
        assertEquals(List.of("1.0.0.0/24", "2.0.0.0/22", "8.8.8.0/24", "9.9.9.9/32"),
                Ipv4Cidr.collapse(in).stream().map(Ipv4Cidr::toString).toList());
        // Adjacent but not siblings: must not merge into a /23 starting at an unaligned address.
        assertEquals(List.of("1.0.1.0/24", "1.0.2.0/24"), Ipv4Cidr.collapse(List.of(
                Ipv4Cidr.parse("1.0.1.0/24"), Ipv4Cidr.parse("1.0.2.0/24"))).stream().map(Ipv4Cidr::toString).toList());
    }
}

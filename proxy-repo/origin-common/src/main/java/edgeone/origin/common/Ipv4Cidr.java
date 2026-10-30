package edgeone.origin.common;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.OptionalInt;

/**
 * IPv4 network in CIDR form. Parsing is strict and literal: no DNS lookups (unlike
 * {@code InetAddress.getByName}), no octal/hex forms, no leading zeros.
 *
 * @param network      network address as an unsigned 32-bit value stored in an int
 * @param prefixLength 0..32
 */
public record Ipv4Cidr(int network, int prefixLength) {

    /** Ranges that are never valid EdgeOne origin-pull sources (RFC 6890 special-purpose, multicast, reserved). */
    private static final List<Ipv4Cidr> NON_PUBLIC = List.of(
            parse("0.0.0.0/8"), parse("10.0.0.0/8"), parse("100.64.0.0/10"), parse("127.0.0.0/8"),
            parse("169.254.0.0/16"), parse("172.16.0.0/12"), parse("192.0.0.0/24"), parse("192.0.2.0/24"),
            parse("192.168.0.0/16"), parse("198.18.0.0/15"), parse("198.51.100.0/24"), parse("203.0.113.0/24"),
            parse("224.0.0.0/4"), parse("240.0.0.0/4"));

    private static final Comparator<Ipv4Cidr> BY_ADDRESS =
            Comparator.comparingLong(Ipv4Cidr::first).thenComparingInt(Ipv4Cidr::prefixLength);

    public Ipv4Cidr {
        if (prefixLength < 0 || prefixLength > 32) {
            throw new IllegalArgumentException("prefix length out of range");
        }
        if ((network & ~mask(prefixLength)) != 0) {
            throw new IllegalArgumentException("host bits set");
        }
    }

    /** Parses {@code a.b.c.d} (as /32) or {@code a.b.c.d/n}; host bits are cleared. */
    public static Ipv4Cidr parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("not an IPv4 address or CIDR");
        }
        int slash = text.indexOf('/');
        int address = parseAddress(slash < 0 ? text : text.substring(0, slash));
        int prefix = 32;
        if (slash >= 0) {
            String bits = text.substring(slash + 1);
            if (!bits.matches("0|[1-9][0-9]?") || Integer.parseInt(bits) > 32) {
                throw new IllegalArgumentException("not an IPv4 address or CIDR");
            }
            prefix = Integer.parseInt(bits);
        }
        return new Ipv4Cidr(address & mask(prefix), prefix);
    }

    /** Parses a dotted-quad IPv4 literal. */
    public static int parseAddress(String text) {
        if (text == null) {
            throw new IllegalArgumentException("not an IPv4 address");
        }
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("not an IPv4 address");
        }
        int address = 0;
        for (String part : parts) {
            if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255) {
                throw new IllegalArgumentException("not an IPv4 address");
            }
            address = (address << 8) | Integer.parseInt(part);
        }
        return address;
    }

    public static OptionalInt tryParseAddress(String text) {
        try {
            return OptionalInt.of(parseAddress(text));
        } catch (IllegalArgumentException e) {
            return OptionalInt.empty();
        }
    }

    public boolean contains(int address) {
        return (address & mask(prefixLength)) == network;
    }

    public boolean contains(Ipv4Cidr other) {
        return other.prefixLength >= prefixLength && contains(other.network);
    }

    public boolean overlaps(Ipv4Cidr other) {
        return contains(other) || other.contains(this);
    }

    /** False for private, loopback, link-local, documentation, CGNAT, multicast and reserved ranges. */
    public boolean isPublicUnicast() {
        return NON_PUBLIC.stream().noneMatch(this::overlaps);
    }

    public long first() {
        return Integer.toUnsignedLong(network);
    }

    public long size() {
        return 1L << (32 - prefixLength);
    }

    @Override
    public String toString() {
        return ((network >>> 24) & 0xff) + "." + ((network >>> 16) & 0xff) + "."
                + ((network >>> 8) & 0xff) + "." + (network & 0xff) + "/" + prefixLength;
    }

    /** Removes covered networks and merges adjacent siblings; result sorted by address. */
    public static List<Ipv4Cidr> collapse(Collection<Ipv4Cidr> networks) {
        List<Ipv4Cidr> sorted = networks.stream().sorted(BY_ADDRESS).toList();
        Deque<Ipv4Cidr> stack = new ArrayDeque<>();
        for (Ipv4Cidr next : sorted) {
            if (!stack.isEmpty() && stack.peekLast().contains(next)) {
                continue;
            }
            stack.addLast(next);
            while (stack.size() >= 2) {
                Ipv4Cidr right = stack.pollLast();
                Ipv4Cidr left = stack.peekLast();
                Ipv4Cidr merged = left.mergeWith(right);
                if (merged == null) {
                    stack.addLast(right);
                    break;
                }
                stack.pollLast();
                stack.addLast(merged);
            }
        }
        return new ArrayList<>(stack);
    }

    private Ipv4Cidr mergeWith(Ipv4Cidr right) {
        if (prefixLength != right.prefixLength || prefixLength == 0) {
            return null;
        }
        int parent = prefixLength - 1;
        if ((network & ~mask(parent)) != 0 || right.first() != first() + size()) {
            return null;
        }
        return new Ipv4Cidr(network, parent);
    }

    private static int mask(int prefixLength) {
        return prefixLength == 0 ? 0 : -1 << (32 - prefixLength);
    }
}

package edgeone.origin.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edgeone.origin.common.Ipv4Cidr;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Builds {@code infra/edgeone_cidrs.auto.tfvars.json} from EdgeOne Origin Protection lists.
 * During a 14-day change notice pass BOTH the current and the upcoming list: the result is their
 * union. After the change date run it again with the new list only. Never calls AWS or Tencent.
 */
@Command(name = "cidrs", mixinStandardHelpOptions = true,
        description = "Validate, merge and write the EdgeOne origin-pull IPv4 list for Terraform.")
final class CidrsCommand implements Callable<Integer> {

    static final String VARIABLE = "edgeone_ipv4_cidrs";
    private static final ObjectMapper JSON = new ObjectMapper();

    record Parsed(List<Ipv4Cidr> networks, List<String> notes) {
    }

    @Spec
    CommandSpec spec;

    @Parameters(arity = "1..*", paramLabel = "FILE",
            description = "Text files from the Tencent team: one IPv4 address or CIDR per line, '#' comments.")
    List<Path> files;

    @Option(names = {"-o", "--out"}, defaultValue = "infra/edgeone_cidrs.auto.tfvars.json",
            description = "Output file (default: ${DEFAULT-VALUE}, run from the repository root).")
    Path out;

    @Option(names = "--max-entries", defaultValue = "200", description = "edgeone_prefix_list_max_entries (default: ${DEFAULT-VALUE}).")
    int maxEntries;

    @Option(names = "--dry-run", description = "Print the result without writing the file.")
    boolean dryRun;

    @Override
    public Integer call() throws IOException {
        PrintWriter stdout = spec.commandLine().getOut();
        PrintWriter stderr = spec.commandLine().getErr();
        List<Ipv4Cidr> networks = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (Path file : files) {
            Parsed parsed;
            try {
                parsed = parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
            } catch (IllegalArgumentException e) {
                stderr.println(e.getMessage());
                return 1;
            }
            if (parsed.networks().isEmpty()) {
                stderr.println(file + ": no IPv4 entries");
                return 1;
            }
            networks.addAll(parsed.networks());
            notes.addAll(parsed.notes());
        }
        List<String> cidrs = build(networks);
        notes.forEach(stderr::println);

        List<String> previous = Files.exists(out) ? previous(out) : List.of();
        List<String> added = cidrs.stream().filter(c -> !previous.contains(c)).sorted().toList();
        List<String> removed = previous.stream().filter(c -> !cidrs.contains(c)).sorted().toList();
        stdout.printf("entries: %d (input %d), added %d, removed %d%n", cidrs.size(), networks.size(), added.size(), removed.size());
        added.forEach(c -> stdout.println("  + " + c));
        removed.forEach(c -> stdout.println("  - " + c));
        stdout.println("sha256: " + terraformSha256(cidrs));
        if (cidrs.size() > maxEntries) {
            stderr.printf("%d entries exceed --max-entries %d; raise edgeone_prefix_list_max_entries and the security "
                    + "group rules quota first.%n", cidrs.size(), maxEntries);
            return 1;
        }
        if (!dryRun) {
            Files.writeString(out, render(cidrs), StandardCharsets.UTF_8);
            stdout.println("written: " + out);
        }
        return 0;
    }

    /** Public IPv4 only, prefix /8 or longer. IPv6 entries are skipped (the ALB origin is IPv4 only). */
    static Parsed parse(String text, String source) {
        List<Ipv4Cidr> networks = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String content = lines[i];
            int hash = content.indexOf('#');
            if (hash >= 0) {
                content = content.substring(0, hash);
            }
            for (String item : content.strip().split("[\\s,]+")) {
                if (item.isEmpty()) {
                    continue;
                }
                String where = source + ":" + (i + 1) + ": ";
                if (item.indexOf(':') >= 0) {
                    notes.add(where + "IPv6 " + item + " skipped (ALB origin is IPv4 only)");
                    continue;
                }
                Ipv4Cidr network;
                try {
                    network = Ipv4Cidr.parse(item);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(where + "not an IP address or CIDR: '" + item + "'");
                }
                if (network.prefixLength() < 8) {
                    throw new IllegalArgumentException(where + "prefix shorter than /8 rejected: " + item);
                }
                if (!network.isPublicUnicast()) {
                    throw new IllegalArgumentException(where + "non-public range rejected: " + item);
                }
                if (item.indexOf('/') >= 0 && !network.toString().equals(item)) {
                    notes.add(where + item + " normalized to " + network);
                }
                networks.add(network);
            }
        }
        return new Parsed(networks, notes);
    }

    /** Collapsed CIDR strings in Terraform's sort() order (lexicographic). */
    static List<String> build(List<Ipv4Cidr> networks) {
        return new ArrayList<>(Ipv4Cidr.collapse(networks).stream().map(Ipv4Cidr::toString)
                .collect(Collectors.toCollection(TreeSet::new)));
    }

    /** Same value as sha256(jsonencode(sort(tolist(var.edgeone_ipv4_cidrs)))) in outputs.tf. */
    static String terraformSha256(List<String> cidrs) {
        String json = cidrs.stream().sorted().map(c -> "\"" + c + "\"").collect(Collectors.joining(",", "[", "]"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String render(List<String> cidrs) {
        String items = cidrs.stream().map(c -> "    \"" + c + "\"").collect(Collectors.joining(",\n"));
        return "{\n  \"" + VARIABLE + "\": [\n" + items + "\n  ]\n}\n";
    }

    private static List<String> previous(Path file) throws IOException {
        JsonNode values = JSON.readTree(file.toFile()).path(VARIABLE);
        List<String> result = new ArrayList<>();
        values.forEach(v -> result.add(v.asText()));
        return result;
    }
}

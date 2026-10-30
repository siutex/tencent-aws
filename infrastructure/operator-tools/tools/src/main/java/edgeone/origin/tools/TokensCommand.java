package edgeone.origin.tools;

import edgeone.origin.common.Tokens;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Supplier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * Origin tokens in Secrets Manager. Tokens are never printed; both teams compare fingerprints:
 * {@code printf %s "$TOKEN" | sha256sum | cut -c1-12}.
 */
@Command(name = "tokens", mixinStandardHelpOptions = true,
        description = "Initialize, inspect and rotate an origin token secret ({\"tokens\": [...]}).")
final class TokensCommand {

    @Spec
    CommandSpec spec;

    private final Supplier<SecretsManagerClient> secrets;

    TokensCommand(Supplier<SecretsManagerClient> secrets) {
        this.secrets = secrets;
    }

    @Command(name = "init", description = "First value: one random token (refuses if a value exists).")
    int init(@Parameters(paramLabel = "SECRET_ARN") String secretArn) {
        SecretTokens store = new SecretTokens(secrets.get(), secretArn);
        if (store.read().versionId() != null) {
            return fail("Secret already initialized; use 'add' and 'retire' to rotate.");
        }
        String token = Tokens.generate();
        report(store.write(List.of(token), null));
        out().println("Initialized. Active token fingerprint:");
        show(List.of(token), null);
        return 0;
    }

    @Command(name = "status", description = "Number of active tokens and their fingerprints.")
    int status(@Parameters(paramLabel = "SECRET_ARN") String secretArn) {
        SecretTokens.Current current = new SecretTokens(secrets.get(), secretArn).read();
        if (current.versionId() == null) {
            return fail("Secret has no value yet; run 'init'.");
        }
        out().printf("AWSCURRENT %s: %d active token(s)%n", current.versionId(), current.tokens().size());
        show(current.tokens(), null);
        return 0;
    }

    @Command(name = "add", description = "Rotation step 1: accept [current, new].")
    int add(@Parameters(paramLabel = "SECRET_ARN") String secretArn,
            @Option(names = "--out", paramLabel = "FILE",
                    description = "Write the NEW token to FILE (created with mode 0600, must not exist) for the approved secret channel.")
            Path out) throws IOException {
        SecretTokens store = new SecretTokens(secrets.get(), secretArn);
        SecretTokens.Current current = store.read();
        if (current.versionId() == null) {
            return fail("Secret has no value yet; run 'init'.");
        }
        if (current.tokens().size() != 1) {
            return fail("Two tokens are already active; finish the rotation with 'retire' first.");
        }
        String token = Tokens.generate();
        // The token is persisted under "<FILE>.pending" BEFORE any AWS mutation and renamed to FILE
        // only once AWSCURRENT is confirmed, so a file named FILE always holds an ACTIVE token.
        Path pending = null;
        if (out != null) {
            pending = out.resolveSibling(out.getFileName() + ".pending");
            if (Files.exists(out, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return fail("--out file already exists: " + out);
            }
            try {
                createPrivateFile(pending);
            } catch (FileAlreadyExistsException e) {
                return fail("Unresolved hand-over file exists: " + pending + ". Resolve it with 'tokens status' first.");
            }
            Files.writeString(pending, token + "\n", StandardCharsets.US_ASCII);
            try (var channel = java.nio.channels.FileChannel.open(pending, java.nio.file.StandardOpenOption.WRITE)) {
                channel.force(true);
            }
        }
        SecretTokens.WriteResult result;
        try {
            result = store.write(List.of(current.tokens().getFirst(), token), current.versionId());
        } catch (SecretTokens.NotApplied e) {
            spec.commandLine().getErr().println("Rotation NOT applied: AWSCURRENT is another version (version " + e.version + " was not activated)."
                    + (pending == null ? "" : " " + pending + " holds a token that is NOT active: do not hand it over; delete it."));
            return 4;
        } catch (SecretTokens.OutcomeUnknown e) {
            spec.commandLine().getErr().println("Rotation outcome UNKNOWN (version " + e.version + "). When AWS is reachable run 'tokens status'."
                    + (pending == null ? "" : " If fingerprint " + Tokens.fingerprint(token) + " is active, rename " + pending + " to " + out
                    + "; otherwise delete it. Do not run 'add' again before that."));
            return 3;
        }
        if (pending != null) {
            Files.move(pending, out); // fails instead of replacing if FILE appeared meanwhile
        }
        report(result);
        out().println("Both tokens are now accepted (after <= 90 s of cache refresh). Active fingerprints:");
        show(List.of(current.tokens().getFirst(), token), token);
        out().printf("Next: Tencent deploys the new token on all edges and confirms its fingerprint, then run "
                + "'tokens retire %s --keep %s'.%n", secretArn, Tokens.fingerprint(token));
        return 0;
    }

    @Command(name = "retire", description = "Rotation step 2: keep only the token with the given fingerprint.")
    int retire(@Parameters(paramLabel = "SECRET_ARN") String secretArn,
               @Option(names = "--keep", required = true, paramLabel = "FINGERPRINT",
                       description = "Fingerprint of the token that stays active.") String keep) {
        SecretTokens store = new SecretTokens(secrets.get(), secretArn);
        SecretTokens.Current current = store.read();
        if (current.tokens().size() != 2) {
            return fail("Nothing to retire: rotation is not in progress.");
        }
        List<String> kept = current.tokens().stream().filter(t -> Tokens.fingerprint(t).equals(keep)).toList();
        if (kept.size() != 1) {
            return fail("--keep does not match exactly one active token fingerprint.");
        }
        report(store.write(kept, current.versionId()));
        out().println("Retired. Remaining token:");
        show(kept, null);
        out().println("The retired token is rejected after <= 90 s. Verify with a negative test.");
        return 0;
    }

    private void report(SecretTokens.WriteResult result) {
        out().println("Committed AWSCURRENT version: " + result.versionId());
        if (result.cleanupPending()) {
            spec.commandLine().getErr().println("WARNING: committed; AWSPENDING cleanup failed. Do not repeat rotation; inspect staging labels.");
        }
    }

    private void show(List<String> tokens, String fresh) {
        for (String token : tokens) {
            out().println("  " + Tokens.fingerprint(token) + (token.equals(fresh) ? "  (new)" : ""));
        }
    }

    private int fail(String message) {
        spec.commandLine().getErr().println(message);
        return 1;
    }

    private PrintWriter out() {
        return spec.commandLine().getOut();
    }

    private void createPrivateFile(Path path) throws IOException {
        try {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
        } catch (UnsupportedOperationException e) {
            throw new IOException("Owner-only file permissions required; use a POSIX filesystem");
        }
    }
}

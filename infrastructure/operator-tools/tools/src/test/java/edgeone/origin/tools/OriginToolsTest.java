package edgeone.origin.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edgeone.origin.common.Tokens;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

class OriginToolsTest {

    static final String ARN = "arn:aws:secretsmanager:eu-central-1:123456789012:secret:x";
    static final String TOKEN = "a".repeat(64);
    static final String OTHER = "b".repeat(64);

    @TempDir
    Path tmp;
    FakeSecretsManager secrets;
    StringWriter out;
    StringWriter err;

    @BeforeEach
    void setUp() {
        secrets = new FakeSecretsManager();
    }

    int run(S3Client s3, String... args) {
        out = new StringWriter();
        err = new StringWriter();
        CommandLine cli = OriginTools.commandLine(new OriginTools.Clients(() -> secrets, () -> s3));
        cli.setOut(new PrintWriter(out, true));
        cli.setErr(new PrintWriter(err, true));
        return cli.execute(args);
    }

    int run(String... args) {
        return run(null, args);
    }

    static String secret(String... tokens) {
        return Tokens.toSecretString(List.of(tokens));
    }

    // --- tokens ---------------------------------------------------------------------------

    @Test
    void initCreatesOneTokenAndPrintsOnlyItsFingerprint() {
        assertEquals(0, run("tokens", "init", ARN));
        List<String> tokens = Tokens.parseSecret(secrets.current());
        assertEquals(1, tokens.size());
        assertFalse(out.toString().contains(tokens.getFirst()));
        assertTrue(out.toString().contains(Tokens.fingerprint(tokens.getFirst())));
        assertEquals(1, run("tokens", "init", ARN));
        assertTrue(err.toString().contains("already initialized"));
    }

    @Test
    void addWritesOwnerOnlyHandOverFile() throws Exception {
        secrets.seed(secret(TOKEN));
        Path file = tmp.resolve("new-token");
        assertEquals(0, run("tokens", "add", ARN, "--out", file.toString()));
        String fresh = Files.readString(file).strip();
        assertEquals(List.of(TOKEN, fresh), Tokens.parseSecret(secrets.current()));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file));
        assertNotEquals(TOKEN, fresh);
        assertFalse(out.toString().contains(fresh));
        assertTrue(out.toString().contains(Tokens.fingerprint(fresh) + "  (new)"));
        assertEquals(1, run("tokens", "add", ARN), "third token refused");
        assertEquals(1, run("tokens", "add", ARN, "--out", file.toString()));
    }

    @Test
    void concurrentChangeFailsAndPreservesRecoveryFile() {
        secrets.seed(secret(TOKEN));
        Path file = tmp.resolve("new-token");
        secrets.beforeUpdate = () -> {
            secrets.beforeUpdate = () -> { };
            secrets.seed(secret(OTHER)); // another operator wins the race
        };
        assertEquals(4, run("tokens", "add", ARN, "--out", file.toString()));
        assertFalse(Files.exists(file), "FILE must only ever hold an active token");
        assertTrue(Files.exists(tmp.resolve("new-token.pending")));
        assertTrue(err.toString().contains("NOT active"));
        assertEquals(List.of(OTHER), Tokens.parseSecret(secrets.current()));
        assertEquals(1, run("tokens", "add", ARN, "--out", file.toString()), "unresolved .pending blocks a new rotation");
    }

    @Test
    void retireKeepsSelectedToken() {
        secrets.seed(secret(TOKEN, OTHER));
        assertEquals(1, run("tokens", "retire", ARN, "--keep", "000000000000"));
        assertEquals(0, run("tokens", "retire", ARN, "--keep", Tokens.fingerprint(OTHER)));
        assertEquals(List.of(OTHER), Tokens.parseSecret(secrets.current()));
        assertEquals(1, run("tokens", "retire", ARN, "--keep", Tokens.fingerprint(OTHER)), "rotation not in progress");
        assertEquals(0, run("tokens", "status", ARN));
        assertTrue(out.toString().contains("1 active token(s)"));
        assertTrue(secrets.versions.values().stream().noneMatch(v -> v.stages().contains("AWSPENDING")));
    }

    @Test
    void cleanupFailureIsCommittedAndHandoverSurvives() throws Exception {
        secrets.seed(secret(TOKEN));
        secrets.failCleanup = true;
        Path file = tmp.resolve("recovery-token");
        assertEquals(0, run("tokens", "add", ARN, "--out", file.toString()));
        assertTrue(Tokens.parseSecret(secrets.current()).contains(Files.readString(file).strip()));
        assertTrue(err.toString().contains("committed"));
        assertTrue(out.toString().contains("AWSCURRENT version"));
    }

    @Test
    void lostCommitResponseIsReconciled() throws Exception {
        secrets.seed(secret(TOKEN));
        secrets.loseCommitResponse = true;
        Path file = tmp.resolve("recovery-token");
        assertEquals(0, run("tokens", "add", ARN, "--out", file.toString()));
        assertTrue(Tokens.parseSecret(secrets.current()).contains(Files.readString(file).strip()));
    }

    @Test
    void unknownCommitOutcomePreservesTokenAndVersionForRecovery() throws Exception {
        secrets.seed(secret(TOKEN));
        secrets.loseCommitResponse = true;
        secrets.failReadsAfterCommit = true;
        Path file = tmp.resolve("recovery-token");
        assertEquals(3, run("tokens", "add", ARN, "--out", file.toString()));
        Path pending = tmp.resolve("recovery-token.pending");
        assertFalse(Files.exists(file));
        assertTrue(Tokens.parseSecret(secrets.current()).contains(Files.readString(pending).strip()));
        assertTrue(err.toString().contains("UNKNOWN"));
        assertFalse(err.toString().contains(Files.readString(pending).strip()));
    }

    @Test
    void existingHandoverFilePreventsRemoteMutation() throws Exception {
        secrets.seed(secret(TOKEN));
        Path file = Files.writeString(tmp.resolve("exists"), "do not replace");
        int versions = secrets.versions.size();
        assertNotEquals(0, run("tokens", "add", ARN, "--out", file.toString()));
        assertEquals(versions, secrets.versions.size());
        assertEquals("do not replace", Files.readString(file));
    }

    // --- cidrs ----------------------------------------------------------------------------

    @Test
    void cidrsParseNormalizeCollapse() {
        CidrsCommand.Parsed parsed = CidrsCommand.parse("# current\n1.0.0.0/25, 1.0.0.128/25\n8.8.8.9/24 2001:db8::/32\n9.9.9.9\n", "in");
        assertEquals(List.of("1.0.0.0/24", "8.8.8.0/24", "9.9.9.9/32"), CidrsCommand.build(parsed.networks()));
        assertTrue(parsed.notes().stream().anyMatch(n -> n.contains("normalized")));
        assertTrue(parsed.notes().stream().anyMatch(n -> n.contains("IPv6")));
        // Terraform sort() is lexicographic, not numeric.
        assertEquals(List.of("101.0.0.0/22", "43.0.0.0/24"),
                CidrsCommand.build(CidrsCommand.parse("43.0.0.0/24\n101.0.0.0/22", "in").networks()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.0.0.0/8", "100.64.0.0/10", "1.0.0.0/7", "0.0.0.0/0", "nonsense", "224.0.0.0/24"})
    void cidrsRejectUnsafeInput(String bad) {
        assertThrows(IllegalArgumentException.class, () -> CidrsCommand.parse(bad, "in"));
    }

    @Test
    void cidrsUnionDiffAndTerraformHash() throws Exception {
        Path out = tmp.resolve("edgeone_cidrs.auto.tfvars.json");
        Files.writeString(out, "{\"edgeone_ipv4_cidrs\": [\"1.0.0.0/24\", \"5.5.5.0/24\"]}");
        Path current = Files.writeString(tmp.resolve("current.txt"), "1.0.0.0/24\n");
        Path upcoming = Files.writeString(tmp.resolve("upcoming.txt"), "8.8.8.0/24\n");
        assertEquals(0, run("cidrs", current.toString(), upcoming.toString(), "--out", out.toString()));
        assertEquals("{\n  \"edgeone_ipv4_cidrs\": [\n    \"1.0.0.0/24\",\n    \"8.8.8.0/24\"\n  ]\n}\n", Files.readString(out));
        assertTrue(this.out.toString().contains("+ 8.8.8.0/24"));
        assertTrue(this.out.toString().contains("- 5.5.5.0/24"));
        // sha256(jsonencode(sort(...))) as computed by Terraform: compact JSON array.
        String expected = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest("[\"1.0.0.0/24\",\"8.8.8.0/24\"]".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(this.out.toString().contains("sha256: " + expected));
        assertEquals(1, run("cidrs", current.toString(), upcoming.toString(), "--out", out.toString(), "--max-entries", "1"));
        Path bad = Files.writeString(tmp.resolve("bad.txt"), "192.168.0.0/16\n");
        assertEquals(1, run("cidrs", bad.toString(), "--out", out.toString()));
        assertTrue(err.toString().contains("bad.txt:1: non-public range rejected"));
    }

    // --- audit-cache-control --------------------------------------------------------------

    static final class FakeS3 implements S3Client {
        final Map<String, HeadObjectResponse> heads = Map.of(
                "public/index.html", HeadObjectResponse.builder().contentType("text/html").build(),
                "public/app.3f2a.js", HeadObjectResponse.builder().cacheControl("public,max-age=31536000,immutable").build(),
                "public/logo.png", HeadObjectResponse.builder().contentType("image/png").build());
        final List<String> headed = new ArrayList<>();

        @Override
        public ListObjectsV2Response listObjectsV2(ListObjectsV2Request request) {
            return ListObjectsV2Response.builder().contents(
                    S3Object.builder().key("public/").size(0L).build(),
                    S3Object.builder().key("public/index.html").size(5L).build(),
                    S3Object.builder().key("public/app.3f2a.js").size(9L).build(),
                    S3Object.builder().key("public/logo.png").size(7L).build()).isTruncated(false).build();
        }

        @Override
        public synchronized HeadObjectResponse headObject(HeadObjectRequest request) {
            headed.add(request.key());
            return heads.get(request.key());
        }

        @Override
        public String serviceName() {
            return "s3";
        }

        @Override
        public void close() {
        }
    }

    @Test
    void auditReportsObjectsWithoutCacheControl() throws Exception {
        Path csv = tmp.resolve("missing.csv");
        FakeS3 s3 = new FakeS3();
        assertEquals(2, run(s3, "audit-cache-control", "bucket", "--csv", csv.toString(), "--fail-on-missing", "--workers", "2"));
        assertTrue(out.toString().contains("total: 3 objects, 2 without Cache-Control"));
        String report = Files.readString(csv);
        assertTrue(report.contains("public/index.html,text/html"));
        assertFalse(report.contains("app.3f2a.js"));
        assertEquals(3, s3.headed.size(), "folder placeholder skipped");
        assertEquals(0, run(s3, "audit-cache-control", "bucket"));
        assertEquals(".js", AuditCacheControlCommand.extension("public/app.3f2a.JS"));
        assertEquals("(none)", AuditCacheControlCommand.extension("public/.hidden"));
    }
}

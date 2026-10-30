package edgeone.origin.tools;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Reports which published objects have no Cache-Control metadata. The proxy sends
 * {@code default_cache_control} ("no-store" by default) for them, so EdgeOne will not cache them
 * unless an EdgeOne rule forces a TTL. Read-only. Run with the publisher/operator role
 * (s3:ListBucket, s3:GetObject on the prefix), never with the proxy task role.
 */
@Command(name = "audit-cache-control", mixinStandardHelpOptions = true,
        description = "Count objects without Cache-Control metadata, per extension.")
final class AuditCacheControlCommand implements Callable<Integer> {

    record Row(String key, String cacheControl, String contentType) {
    }

    static final class Stats {
        int total;
        int missing;
        final Map<String, Integer> values = new HashMap<>();
    }

    @Spec
    CommandSpec spec;

    @Parameters(paramLabel = "BUCKET")
    String bucket;

    @Option(names = "--prefix", defaultValue = "public/", description = "Key prefix (default: ${DEFAULT-VALUE}).")
    String prefix;

    @Option(names = "--max-objects", defaultValue = "0", description = "Sample size; 0 = all objects.")
    int maxObjects;

    @Option(names = "--workers", defaultValue = "16", description = "Concurrent HEAD requests (default: ${DEFAULT-VALUE}).")
    int workers;

    @Option(names = "--csv", paramLabel = "FILE", description = "Write keys without Cache-Control to this CSV file.")
    Path csv;

    @Option(names = "--fail-on-missing", description = "Exit 2 if any object lacks Cache-Control.")
    boolean failOnMissing;

    private final Supplier<S3Client> s3;

    AuditCacheControlCommand(Supplier<S3Client> s3) {
        this.s3 = s3;
    }

    @Override
    public Integer call() throws IOException, InterruptedException, ExecutionException {
        List<Row> rows = scan(s3.get());
        Map<String, Stats> byExtension = new HashMap<>();
        List<Row> missing = new ArrayList<>();
        for (Row row : rows) {
            Stats stats = byExtension.computeIfAbsent(extension(row.key()), e -> new Stats());
            stats.total++;
            if (row.cacheControl() == null) {
                stats.missing++;
                missing.add(row);
            } else {
                stats.values.merge(row.cacheControl(), 1, Integer::sum);
            }
        }
        PrintWriter out = spec.commandLine().getOut();
        out.printf("%-12s %9s %17s  most common value%n", "extension", "objects", "no Cache-Control");
        byExtension.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, Stats> e) -> -e.getValue().total).thenComparing(Map.Entry::getKey))
                .forEach(e -> out.printf("%-12s %9d %17d  %s%n", e.getKey(), e.getValue().total, e.getValue().missing,
                        e.getValue().values.entrySet().stream().max(Map.Entry.comparingByValue())
                                .map(Map.Entry::getKey).orElse("-")));
        out.printf("total: %d objects, %d without Cache-Control%n", rows.size(), missing.size());
        if (csv != null) {
            StringBuilder text = new StringBuilder("key,content_type\n");
            missing.stream().sorted(Comparator.comparing(Row::key))
                    .forEach(r -> text.append(csvField(r.key())).append(',').append(csvField(r.contentType())).append('\n'));
            Files.writeString(csv, text, StandardCharsets.UTF_8);
        }
        return failOnMissing && !missing.isEmpty() ? 2 : 0;
    }

    private List<Row> scan(S3Client client) throws InterruptedException, ExecutionException {
        Semaphore permits = new Semaphore(Math.max(1, workers));
        List<Future<Row>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (S3Object object : client.listObjectsV2Paginator(r -> r.bucket(bucket).prefix(prefix)).contents()) {
                if (object.key().endsWith("/") && (object.size() == null || object.size() == 0)) {
                    continue; // folder placeholder
                }
                String key = object.key();
                futures.add(pool.submit(() -> {
                    permits.acquire();
                    try {
                        HeadObjectResponse head = client.headObject(r -> r.bucket(bucket).key(key));
                        return new Row(key, head.cacheControl(), head.contentType() == null ? "" : head.contentType());
                    } finally {
                        permits.release();
                    }
                }));
                if (maxObjects > 0 && futures.size() >= maxObjects) {
                    break;
                }
            }
            List<Row> rows = new ArrayList<>(futures.size());
            for (Future<Row> future : futures) {
                rows.add(future.get());
            }
            return rows;
        }
    }

    static String extension(String key) {
        String name = key.substring(key.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot <= 0 || dot == name.length() - 1 ? "(none)" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static String csvField(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}

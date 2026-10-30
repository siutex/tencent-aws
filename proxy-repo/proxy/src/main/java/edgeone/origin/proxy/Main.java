package edgeone.origin.proxy;

import edgeone.origin.common.SecretSource;
import edgeone.origin.common.TokenStore;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.logging.LogManager;
import org.eclipse.jetty.server.Server;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * Entry point of the S3 origin proxy (ECS Fargate). AWS credentials come from the IAM task role
 * through the SDK default provider chain; no static keys exist anywhere.
 */
public final class Main {

    private static final Logger LOG = System.getLogger(Main.class.getName());

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        configureLogging();
        ProxyConfig config = ProxyConfig.fromEnv(System.getenv());
        Region region = Region.of(config.region());

        S3Client s3 = S3Client.builder()
                .region(region)
                .httpClientBuilder(Apache5HttpClient.builder()
                        .maxConnections(config.maxConcurrency())
                        .connectionTimeout(Duration.ofSeconds(2))
                        .socketTimeout(Duration.ofSeconds(30))
                        .connectionAcquisitionTimeout(Duration.ofMillis(250)))
                .overrideConfiguration(o -> o.addMetricPublisher(S3PoolMetrics.INSTANCE)
                        .apiCallTimeout(Duration.ofSeconds(15))
                        .apiCallAttemptTimeout(Duration.ofSeconds(7))
                        .retryStrategy(b -> b.maxAttempts(2)))
                .build();
        SecretsManagerClient secrets = SecretsManagerClient.builder()
                .region(region)
                .httpClientBuilder(Apache5HttpClient.builder()
                        .maxConnections(4)
                        .connectionAcquisitionTimeout(Duration.ofMillis(250))
                        .connectionTimeout(Duration.ofSeconds(1))
                        .socketTimeout(Duration.ofSeconds(2)))
                .overrideConfiguration(o -> o.apiCallTimeout(Duration.ofSeconds(3))
                        .apiCallAttemptTimeout(Duration.ofSeconds(2))
                        .retryStrategy(b -> b.maxAttempts(1)))
                .build();

        TokenStore tokens = new TokenStore(SecretSource.secretsManager(secrets, config.secretArn()));
        S3Origin origin = new S3Origin(s3, config.bucket(), config.defaultCacheControl());
        Server server = OriginServer.create(config, new OriginHandler(tokens, origin, config.prefix()));
        server.start();
        LOG.log(Level.INFO, "origin_proxy_started port={0} idle_timeout_s={1} max_concurrency={2}",
                String.valueOf(config.port()), String.valueOf(config.idleTimeout().toSeconds()),
                String.valueOf(config.maxConcurrency()));
        server.join();
    }

    private static void configureLogging() {
        try (InputStream in = Main.class.getResourceAsStream("/logging.properties")) {
            if (in != null) {
                LogManager.getLogManager().readConfiguration(in);
            }
        } catch (IOException e) {
            // keep JDK defaults
        }
    }
}

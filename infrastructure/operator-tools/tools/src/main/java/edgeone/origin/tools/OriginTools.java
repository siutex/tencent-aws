package edgeone.origin.tools;

import java.util.function.Supplier;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * Operator CLI. Uses the AWS SDK default credential chain (e.g. {@code aws sso login} and
 * {@code AWS_PROFILE}); never the proxy or authorizer roles.
 *
 * <pre>
 * java -jar origin-tools.jar tokens init|status|add|retire SECRET_ARN ...
 * java -jar origin-tools.jar cidrs current.txt [upcoming.txt]
 * java -jar origin-tools.jar audit-cache-control BUCKET --prefix public/
 * </pre>
 */
@Command(name = "origin-tools", mixinStandardHelpOptions = true, version = "origin-tools 1.0",
        description = "EdgeOne origin operator tools: origin tokens, EdgeOne CIDR list, Cache-Control audit.")
public final class OriginTools implements Runnable {

    /** Lazily created clients, so commands that need no AWS access never build one. */
    record Clients(Supplier<SecretsManagerClient> secrets, Supplier<S3Client> s3) {
    }

    public static void main(String[] args) {
        Clients clients = new Clients(
                memoize(SecretsManagerClient::create),
                memoize(() -> S3Client.builder().httpClientBuilder(Apache5HttpClient.builder().maxConnections(64)).build()));
        System.exit(commandLine(clients).execute(args));
    }

    static CommandLine commandLine(Clients clients) {
        CommandLine cli = new CommandLine(new OriginTools());
        cli.addSubcommand(new CommandLine(new TokensCommand(clients.secrets())));
        cli.addSubcommand(new CommandLine(new CidrsCommand()));
        cli.addSubcommand(new CommandLine(new AuditCacheControlCommand(clients.s3())));
        return cli;
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    private static <T> Supplier<T> memoize(Supplier<T> supplier) {
        return new Supplier<>() {
            private T value;

            @Override
            public synchronized T get() {
                if (value == null) {
                    value = supplier.get();
                }
                return value;
            }
        };
    }
}

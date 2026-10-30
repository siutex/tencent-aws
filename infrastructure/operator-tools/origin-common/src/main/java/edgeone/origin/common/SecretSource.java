package edgeone.origin.common;

import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/** Returns the current secret string (AWSCURRENT). */
@FunctionalInterface
public interface SecretSource {

    String fetch() throws Exception;

    static SecretSource secretsManager(SecretsManagerClient client, String secretArn) {
        return () -> client.getSecretValue(r -> r.secretId(secretArn)).secretString();
    }
}

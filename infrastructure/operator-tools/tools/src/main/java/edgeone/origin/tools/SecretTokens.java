package edgeone.origin.tools;

import edgeone.origin.common.Tokens;
import java.util.List;
import java.util.UUID;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

/** AWSCURRENT is the commit point. Cleanup failure must never report a rollback. */
final class SecretTokens {
    static final String CURRENT = "AWSCURRENT";
    static final String PENDING = "AWSPENDING";
    record Current(String versionId, List<String> tokens) { }
    record WriteResult(String versionId, boolean cleanupPending) { }

    /** AWSCURRENT could not be read after a failed write: the new version may or may not be active. */
    static final class OutcomeUnknown extends RuntimeException {
        final String version;
        OutcomeUnknown(String version) {
            super("Rotation outcome UNKNOWN; inspect version " + version);
            this.version = version;
        }
    }

    /** AWSCURRENT is another version (lost race or rejected move): the new token is NOT active. */
    static final class NotApplied extends RuntimeException {
        final String version;
        NotApplied(String version) {
            super("Rotation version " + version + " is NOT CURRENT");
            this.version = version;
        }
    }
    private final SecretsManagerClient secrets;
    private final String secretArn;
    SecretTokens(SecretsManagerClient secrets, String secretArn) {
        this.secrets = secrets;
        this.secretArn = secretArn;
    }
    Current read() {
        try {
            GetSecretValueResponse response = secrets.getSecretValue(r -> r.secretId(secretArn).versionStage(CURRENT));
            return new Current(response.versionId(), Tokens.parseSecret(response.secretString()));
        } catch (ResourceNotFoundException e) {
            return new Current(null, List.of());
        }
    }
    WriteResult write(List<String> tokens, String expectedVersion) {
        String payload = Tokens.toSecretString(tokens);
        String version = UUID.randomUUID().toString();
        try {
            secrets.putSecretValue(r -> r.secretId(secretArn).secretString(payload)
                    .clientRequestToken(version).versionStages(PENDING));
            // AWS attaches AWSCURRENT automatically to the very first version.
            if (expectedVersion != null || !version.equals(read().versionId())) {
                secrets.updateSecretVersionStage(r -> {
                    r.secretId(secretArn).versionStage(CURRENT).moveToVersionId(version);
                    if (expectedVersion != null) {
                        r.removeFromVersionId(expectedVersion);
                    }
                });
            }
        } catch (RuntimeException failure) {
            // A lost response is not proof the mutation failed. Reconcile against AWSCURRENT.
            Current observed;
            try {
                observed = read();
            } catch (RuntimeException unavailable) {
                throw new OutcomeUnknown(version);
            }
            if (!version.equals(observed.versionId())) {
                throw new NotApplied(version);
            }
        }
        boolean cleanupPending = false;
        try {
            secrets.updateSecretVersionStage(r -> r.secretId(secretArn).versionStage(PENDING).removeFromVersionId(version));
        } catch (RuntimeException failure) {
            cleanupPending = true;
        }
        return new WriteResult(version, cleanupPending);
    }
}

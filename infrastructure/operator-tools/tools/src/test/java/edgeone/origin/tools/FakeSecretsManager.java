package edgeone.origin.tools;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.InvalidParameterException;
import software.amazon.awssdk.services.secretsmanager.model.PutSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.PutSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;
import software.amazon.awssdk.services.secretsmanager.model.UpdateSecretVersionStageRequest;
import software.amazon.awssdk.services.secretsmanager.model.UpdateSecretVersionStageResponse;

/** In-memory Secrets Manager with the staging-label semantics the CAS logic relies on. */
final class FakeSecretsManager implements SecretsManagerClient {

    record Version(String secretString, Set<String> stages) {
    }

    final Map<String, Version> versions = new LinkedHashMap<>();
    /** Runs before each UpdateSecretVersionStage call: lets a test simulate a concurrent writer. */
    Runnable beforeUpdate = () -> { };
    boolean failCleanup;
    boolean loseCommitResponse;
    boolean failReadsAfterCommit;
    boolean committed;

    String current() {
        return versions.entrySet().stream().filter(e -> e.getValue().stages().contains("AWSCURRENT"))
                .map(e -> e.getValue().secretString()).findFirst().orElse(null);
    }

    String seed(String secretString) {
        String id = UUID.randomUUID().toString();
        versions.values().forEach(v -> v.stages().remove("AWSCURRENT"));
        versions.put(id, new Version(secretString, new HashSet<>(Set.of("AWSCURRENT"))));
        return id;
    }

    @Override
    public GetSecretValueResponse getSecretValue(GetSecretValueRequest request) {
        if (committed && failReadsAfterCommit) { throw new IllegalStateException("read unavailable"); }
        String stage = request.versionStage() == null ? "AWSCURRENT" : request.versionStage();
        return versions.entrySet().stream().filter(e -> e.getValue().stages().contains(stage)).findFirst()
                .map(e -> GetSecretValueResponse.builder().versionId(e.getKey()).secretString(e.getValue().secretString()).build())
                .orElseThrow(() -> ResourceNotFoundException.builder().message("no " + stage).build());
    }

    @Override
    public PutSecretValueResponse putSecretValue(PutSecretValueRequest request) {
        String id = request.clientRequestToken() == null ? UUID.randomUUID().toString() : request.clientRequestToken();
        Set<String> stages = new HashSet<>(request.hasVersionStages() ? request.versionStages() : Set.of("AWSCURRENT"));
        if (versions.isEmpty()) { stages.add("AWSCURRENT"); }
        versions.values().forEach(v -> v.stages().removeAll(stages));
        versions.put(id, new Version(request.secretString(), stages));
        return PutSecretValueResponse.builder().versionId(id).build();
    }

    @Override
    public UpdateSecretVersionStageResponse updateSecretVersionStage(UpdateSecretVersionStageRequest request) {
        beforeUpdate.run();
        if (failCleanup && request.moveToVersionId() == null) { throw new IllegalStateException("cleanup unavailable"); }
        String stage = request.versionStage();
        String holder = versions.entrySet().stream().filter(e -> e.getValue().stages().contains(stage))
                .map(Map.Entry::getKey).findFirst().orElse(null);
        if (request.moveToVersionId() != null) {
            if (holder != null && !holder.equals(request.moveToVersionId())
                    && !holder.equals(request.removeFromVersionId())) {
                throw InvalidParameterException.builder().message("stage attached to another version").build();
            }
            if (holder != null) {
                versions.get(holder).stages().remove(stage);
            }
            versions.get(request.moveToVersionId()).stages().add(stage);
        } else if (request.removeFromVersionId() != null) {
            if (!request.removeFromVersionId().equals(holder)) {
                throw InvalidParameterException.builder().message("stage not attached").build();
            }
            versions.get(holder).stages().remove(stage);
        }
        if ("AWSCURRENT".equals(stage) && request.moveToVersionId() != null) {
            committed = true;
            if (loseCommitResponse) { throw new IllegalStateException("response lost"); }
        }
        return UpdateSecretVersionStageResponse.builder().build();
    }

    @Override
    public String serviceName() {
        return "secretsmanager";
    }

    @Override
    public void close() {
    }
}

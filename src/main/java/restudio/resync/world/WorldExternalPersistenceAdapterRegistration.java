package restudio.resync.world;

import restudio.resync.migration.MigrationPaths;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

public final class WorldExternalPersistenceAdapterRegistration {
    private final String capabilityId;
    private final String adapterId;
    private final String signingKey;
    private final Set<String> verifiedOperations;
    private final Object adapterIdentity;
    private final String probeManifestHash;
    private final Path probeManifest;

    WorldExternalPersistenceAdapterRegistration(String capabilityId, String adapterId, String signingKey,
                                                Set<String> verifiedOperations, Object adapterIdentity,
                                                String probeManifestHash, Object issuer) {
        this(capabilityId, adapterId, signingKey, verifiedOperations, adapterIdentity, probeManifestHash, null, issuer);
    }

    WorldExternalPersistenceAdapterRegistration(String capabilityId, String adapterId, String signingKey,
                                                Set<String> verifiedOperations, Object adapterIdentity,
                                                String probeManifestHash, Path probeManifest, Object issuer) {
        if (issuer != WorldExternalPersistenceAdapterRegistry.issuer()) {
            throw new SecurityException("External World Adapter Registration Must Be Issued By The Coordinator");
        }
        this.capabilityId = requireText(capabilityId, "capabilityId");
        this.adapterId = requireText(adapterId, "adapterId");
        this.signingKey = requireText(signingKey, "signingKey");
        this.verifiedOperations = Set.copyOf(Objects.requireNonNull(verifiedOperations, "verifiedOperations"));
        this.adapterIdentity = Objects.requireNonNull(adapterIdentity, "adapterIdentity");
        this.probeManifestHash = requireText(probeManifestHash, "probeManifestHash");
        this.probeManifest = probeManifest == null ? null : MigrationPaths.requirePath(probeManifest, "probeManifest");
    }

    public String capabilityId() {
        return capabilityId;
    }

    public String adapterId() {
        return adapterId;
    }

    public Set<String> verifiedOperations() {
        return verifiedOperations;
    }

    public String probeManifestHash() {
        return probeManifestHash;
    }

    public Path probeManifest() {
        return probeManifest;
    }

    boolean verifies(String operation) {
        return verifiedOperations.contains(operation);
    }

    boolean matches(Object adapter) {
        return adapterIdentity == adapter;
    }

    String signingKey() {
        return signingKey;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(field + " Must Be Exact And Non-Blank");
        }
        return value;
    }
}

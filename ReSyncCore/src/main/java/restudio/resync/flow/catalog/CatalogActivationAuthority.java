package restudio.resync.flow.catalog;

import restudio.resync.migration.MigrationActivationMarker;
import restudio.resync.migration.PersistenceRootReadiness;
import restudio.resync.migration.ProductionAuthorityBundle;
import restudio.resync.flow.runtime.RuntimeBindingManifest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

public final class CatalogActivationAuthority {
    private final State state;
    private final String approvalReference;
    private final MigrationActivationMarker.Values marker;

    private CatalogActivationAuthority(State state, String approvalReference, MigrationActivationMarker.Values marker) {
        this.state = Objects.requireNonNull(state, "state");
        this.approvalReference = approvalReference;
        this.marker = marker;
    }

    public static CatalogActivationAuthority forbidden() {
        return new CatalogActivationAuthority(State.FORBIDDEN, null, null);
    }

    public static CatalogActivationAuthority freshInstall() {
        return new CatalogActivationAuthority(State.APPROVED, "fresh-install", null);
    }

    public static CatalogActivationAuthority fromCommittedMigration(Path dataFolder) {
        Objects.requireNonNull(dataFolder, "dataFolder");
        try {
            MigrationActivationMarker.Values marker = MigrationActivationMarker.read(dataFolder);
            return new CatalogActivationAuthority(State.APPROVED, "offline-upgrade:" + marker.replacementRootHash(), marker);
        } catch (IOException | RuntimeException exception) {
            return forbidden();
        }
    }

    public static Decision evaluate(Collection<CatalogContribution> contributions, CatalogActivationAuthority authority) {
        if (contributions == null) {
            return new Decision(false, "CATALOG.CONTRIBUTION_REJECTED", "Catalog contributions are required");
        }
        if (!requiresGate2Approval(contributions)) {
            return new Decision(true, "CATALOG.LEGACY_AUTHORITY_NOT_REQUIRED", "The candidate contains no replacement-authored definitions");
        }
        if (authority == null) {
            return new Decision(false, "CATALOG.ACTIVATION_AUTHORITY_MISSING", "An explicit approved Gate 2 activation authority is required");
        }
        if (!authority.approved()) {
            return new Decision(false, "CATALOG.ACTIVATION_FORBIDDEN", "Gate 2 replacement activation remains forbidden");
        }
        if (authority.requiresBindingContext()) {
            return new Decision(false, "CATALOG.ACTIVATION_BINDING_CONTEXT_REQUIRED", "The committed replacement marker must be checked against the exact catalog, runtime binding manifest, and participant readiness report");
        }
        return new Decision(true, "CATALOG.GATE2_AUTHORITY_APPROVED", "The explicit Gate 2 activation authority is approved");
    }

    public static Decision evaluate(CatalogSnapshot snapshot, RuntimeBindingManifest runtimeManifest,
                                    PersistenceRootReadiness readiness, CatalogActivationAuthority authority) {
        if (snapshot == null) {
            return new Decision(false, "CATALOG.ACTIVATION_SNAPSHOT_MISSING", "A compiled replacement catalog snapshot is required");
        }
        if (!requiresGate2Approval(snapshot)) {
            return new Decision(true, "CATALOG.LEGACY_AUTHORITY_NOT_REQUIRED", "The candidate contains no replacement-authored definitions");
        }
        if (authority == null) {
            return new Decision(false, "CATALOG.ACTIVATION_AUTHORITY_MISSING", "An explicit approved Gate 2 activation authority is required");
        }
        if (!authority.approved()) {
            return new Decision(false, "CATALOG.ACTIVATION_FORBIDDEN", "Gate 2 replacement activation remains forbidden");
        }
        if (!authority.requiresBindingContext() && !authority.isFreshInstall()) {
            return new Decision(true, "CATALOG.GATE2_AUTHORITY_APPROVED", "The explicit Gate 2 activation authority is approved");
        }
        if (runtimeManifest == null || readiness == null) {
            return new Decision(false, "CATALOG.ACTIVATION_BINDING_CONTEXT_MISSING", "The runtime binding manifest and complete participant readiness report are required");
        }
        MigrationActivationMarker.Values marker = authority.marker;
        if (marker == null) {
            if (!snapshot.bindingManifestHash().equals(runtimeManifest.bindingManifestHash())) {
                return new Decision(false, "CATALOG.ACTIVATION_RUNTIME_BINDING_MISMATCH", "The fresh replacement catalog does not bind the exact runtime binding manifest");
            }
            if (!readiness.complete()) {
                return new Decision(false, "CATALOG.ACTIVATION_PARTICIPANT_READINESS_MISMATCH", "A fresh replacement installation requires a complete participant readiness report");
            }
            return new Decision(true, "CATALOG.FRESH_INSTALL_AUTHORITY_APPROVED", "The fresh replacement installation binds the live runtime and participant readiness state");
        }
        if (!marker.replacementCatalogHash().equals(snapshot.contentChecksum().canonicalText())) {
            return new Decision(false, "CATALOG.ACTIVATION_CATALOG_MISMATCH", "The committed marker does not bind the exact replacement catalog checksum");
        }
        if (marker.runtimeBindingManifestVersion() != runtimeManifest.version()
            || !marker.runtimeBindingManifestHash().equals(runtimeManifest.bindingManifestHash().canonicalText())) {
            return new Decision(false, "CATALOG.ACTIVATION_RUNTIME_BINDING_MISMATCH", "The committed marker does not bind the exact runtime binding manifest version and hash");
        }
        if (!readiness.complete() || !marker.participantReadinessComplete()
            || marker.participantReadinessVersion() != readiness.reportVersion()
            || !marker.participantReadinessHash().equals(readiness.reportHash())) {
            return new Decision(false, "CATALOG.ACTIVATION_PARTICIPANT_READINESS_MISMATCH", "The committed marker does not bind a complete participant readiness report");
        }
        return new Decision(true, "CATALOG.GATE2_AUTHORITY_APPROVED", "The committed replacement marker binds the exact catalog, runtime, and participant readiness state");
    }

    public static Decision evaluateBindingContext(CatalogSnapshot snapshot, RuntimeBindingManifest runtimeManifest,
                                                   PersistenceRootReadiness readiness, CatalogActivationAuthority authority) {
        if (snapshot == null || runtimeManifest == null || readiness == null) {
            return new Decision(false, "CATALOG.ACTIVATION_BINDING_CONTEXT_MISSING", "The catalog, runtime binding manifest, and participant readiness report are required");
        }
        if (authority == null || !authority.approved()) {
            return new Decision(false, "CATALOG.ACTIVATION_FORBIDDEN", "A committed replacement activation authority is required");
        }
        if (!authority.requiresBindingContext()) {
            return new Decision(false, "CATALOG.ACTIVATION_BINDING_CONTEXT_REQUIRED", "The committed replacement marker is required for authority export");
        }
        MigrationActivationMarker.Values marker = authority.marker;
        if (!marker.replacementCatalogHash().equals(snapshot.contentChecksum().canonicalText())) {
            return new Decision(false, "CATALOG.ACTIVATION_CATALOG_MISMATCH", "The committed marker does not bind the exact replacement catalog checksum");
        }
        if (marker.runtimeBindingManifestVersion() != runtimeManifest.version()
            || !marker.runtimeBindingManifestHash().equals(runtimeManifest.bindingManifestHash().canonicalText())) {
            return new Decision(false, "CATALOG.ACTIVATION_RUNTIME_BINDING_MISMATCH", "The committed marker does not bind the exact runtime binding manifest version and hash");
        }
        if (!readiness.complete() || !marker.participantReadinessComplete()
            || marker.participantReadinessVersion() != readiness.reportVersion()
            || !marker.participantReadinessHash().equals(readiness.reportHash())) {
            return new Decision(false, "CATALOG.ACTIVATION_PARTICIPANT_READINESS_MISMATCH", "The committed marker does not bind a complete participant readiness report");
        }
        return new Decision(true, "CATALOG.GATE2_AUTHORITY_APPROVED", "The committed replacement marker binds the exact catalog, runtime, and participant readiness state");
    }

    public static Decision evaluateReplacement(boolean replacementDerived, CatalogActivationAuthority authority) {
        if (!replacementDerived) {
            return new Decision(true, "CATALOG.LEGACY_AUTHORITY_NOT_REQUIRED", "The candidate contains no replacement-derived definitions");
        }
        if (authority == null) {
            return new Decision(false, "CATALOG.ACTIVATION_AUTHORITY_MISSING", "An explicit approved Gate 2 activation authority is required");
        }
        if (!authority.approved()) {
            return new Decision(false, "CATALOG.ACTIVATION_FORBIDDEN", "Gate 2 replacement activation remains forbidden");
        }
        if (authority.requiresBindingContext()) {
            return new Decision(false, "CATALOG.ACTIVATION_BINDING_CONTEXT_REQUIRED", "The committed replacement marker must be checked against the exact catalog, runtime binding manifest, and participant readiness report");
        }
        return new Decision(true, "CATALOG.GATE2_AUTHORITY_APPROVED", "The explicit Gate 2 activation authority is approved");
    }

    public static void requireReplacement(boolean replacementDerived, CatalogActivationAuthority authority) {
        Decision decision = evaluateReplacement(replacementDerived, authority);
        if (!decision.allowed()) {
            throw new IllegalStateException(decision.code() + ": " + decision.detail());
        }
    }

    public static void require(Collection<CatalogContribution> contributions, CatalogActivationAuthority authority) {
        Decision decision = evaluate(contributions, authority);
        if (!decision.allowed()) {
            throw new IllegalStateException(decision.code() + ": " + decision.detail());
        }
    }

    public static boolean requiresGate2Approval(CatalogSnapshot snapshot) {
        return snapshot != null && requiresGate2Approval(snapshot.contributions());
    }

    public static boolean requiresGate2Approval(Collection<CatalogContribution> contributions) {
        Objects.requireNonNull(contributions, "contributions");
        return contributions.stream()
            .filter(Objects::nonNull)
            .flatMap(value -> value.definitions().stream())
            .anyMatch(CatalogActivationAuthority::hasAuthoredSource);
    }

    public State state() {
        return state;
    }

    public boolean approved() {
        return state == State.APPROVED && approvalReference != null;
    }

    public String approvalReference() {
        return approvalReference;
    }

    public boolean requiresBindingContext() {
        return approved() && marker != null;
    }

    public Optional<ProductionAuthorityBundle> authorityBundle() {
        return marker == null ? Optional.empty() : Optional.ofNullable(marker.authorityBundle());
    }

    public boolean isFreshInstall() {
        return approved() && marker == null && "fresh-install".equals(approvalReference);
    }

    private static boolean hasAuthoredSource(CatalogNodeDescriptor descriptor) {
        return descriptor != null && descriptor.metadata().containsKey("authoredSource");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value.strip();
    }

    public enum State {
        FORBIDDEN,
        APPROVED
    }

    public record Decision(boolean allowed, String code, String detail) {
        public Decision {
            code = requireText(code, "code");
            detail = requireText(detail, "detail");
        }
    }
}

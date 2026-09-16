package restudio.resync.migration;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogActivationAuthority;
import restudio.resync.flow.catalog.CatalogCanonicalizer;
import restudio.resync.flow.catalog.CatalogRuntimeActivation;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.SnapshotId;
import restudio.resync.flow.identity.UuidIdentity;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.runtime.RuntimeBinding;
import restudio.resync.flow.runtime.RuntimeBindingManifest;
import restudio.resync.flow.runtime.RuntimeProviderState;
import restudio.resync.flow.runtime.RuntimeRegistrySnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class ProductionAuthorityBundleExporter {
    public static final String SNAPSHOT_ID_DOMAIN = "resync.authority-bundle.snapshot";
    private static final String TRUST_CONTEXT_MIGRATION_ID = "production-authority-bundle-export";

    private ProductionAuthorityBundleExporter() {
    }

    public static ProductionAuthorityBundle emit(Source source, Path target) throws IOException {
        return emit(source, target, Clock.systemUTC());
    }

    static ProductionAuthorityBundle emit(Source source, Path target, Clock clock) throws IOException {
        Source checked = Objects.requireNonNull(source, "Authority Bundle Source Is Required");
        Path expectedTarget = ProductionAuthorityBundle.path(checked.persistence().dataRoot());
        Path normalizedTarget = MigrationPaths.requirePath(target, "authorityBundlePath");
        if (!expectedTarget.equals(normalizedTarget)) {
            throw new MigrationException("Authority Bundle Target Must Be " + expectedTarget);
        }
        try (MigrationFence.MigrationLease ignored = checked.persistence().fence().acquireMigration()) {
            synchronized (checked.activation()) {
                ProductionAuthorityBundle bundle = create(checked, clock);
                if (Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.isSymbolicLink(normalizedTarget)
                        || !Files.isRegularFile(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
                        throw new MigrationException("Authority Bundle Target Is Not A Regular File: " + normalizedTarget);
                    }
                    ProductionAuthorityBundlePersistenceParticipant regenerableAuthority = regenerationAuthority(checked);
                    ProductionAuthorityBundle existing;
                    try {
                        existing = ProductionAuthorityBundle.read(normalizedTarget);
                    } catch (IOException | RuntimeException exception) {
                        if (regenerableAuthority == null) {
                            throw new MigrationException("Authority Bundle Target Is Invalid And Cannot Be Regenerated: " + normalizedTarget,
                                exception);
                        }
                        return replaceExisting(checked, normalizedTarget, bundle);
                    }
                    if (bundle.equals(existing)) {
                        return existing;
                    }
                    if (regenerableAuthority == null) {
                        throw new MigrationException("Authority Bundle Target Already Contains Different Content: " + normalizedTarget);
                    }
                    return replaceExisting(checked, normalizedTarget, bundle);
                }
                bundle.write(normalizedTarget);
                return bundle;
            }
        }
    }

    public static Path path(Path dataRoot) {
        return ProductionAuthorityBundle.path(dataRoot);
    }

    public static ProductionAuthorityBundle create(Source source) throws IOException {
        return create(source, Clock.systemUTC());
    }

    static ProductionAuthorityBundle create(Source source, Clock clock) throws IOException {
        Source checked = Objects.requireNonNull(source, "Authority Bundle Source Is Required");
        Clock checkedClock = Objects.requireNonNull(clock, "Authority Bundle Clock Is Required");
        CatalogRuntimeActivation activation = checked.activation();
        synchronized (activation) {
            CatalogRuntimeActivation.ActivationRecord captured = activation.active();
            CatalogSnapshot catalog = captured.catalog();
            RuntimeRegistrySnapshot runtime = captured.runtime();
            RuntimeBindingManifest manifest = captured.runtimeManifest();
            validateSigner(checked);
            validateActivation(catalog, runtime, manifest);
            validateReadiness(checked, catalog, manifest);
            validateStableIdentity(checked);
            validatePersistedActivation(checked.persistence(), catalog, manifest, checked.readiness());
            SnapshotId snapshotId = snapshotId(checked.serverId(), catalog, manifest, checked.readiness());
            ProductionAuthorityBundle.TrustContext trustContext = trustContext(checked, snapshotId);
            Instant timestamp = checkedClock.instant();
            ProductionAuthorityBundle retained = validateAuthority(
                checked.activationAuthority(), trustContext, checked.serverId(), snapshotId, catalog, manifest, checked.readiness(), timestamp);
            ProductionAuthorityBundle bundle = retained == null ? ProductionAuthorityBundle.create(
                checked.signer(), checked.serverId(), snapshotId, timestamp, catalog.contentChecksum(), catalog.generation(), catalog.contractVersion(),
                manifest.bindingManifestHash(), manifest.version(), checked.readiness().reportHash(), checked.readiness().reportVersion()) : retained;
            if (activation.active() != captured) {
                throw new MigrationException("Catalog Runtime Activation Became Stale During Authority Bundle Export");
            }
            return bundle;
        }
    }

    private static void validateActivation(
        CatalogSnapshot catalog,
        RuntimeRegistrySnapshot runtime,
        RuntimeBindingManifest manifest
    ) throws IOException {
        if (catalog == null || runtime == null || manifest == null) {
            throw new MigrationException("Catalog Runtime Activation Is Incomplete");
        }
        if (catalog.generation() < 1 || manifest.version() < 1 || manifest.version() != RuntimeBindingManifest.VERSION) {
            throw new MigrationException("Catalog Runtime Activation Version Is Invalid");
        }
        if (!catalog.bindingManifestHash().equals(runtime.bindingManifestHash())
            || !catalog.bindingManifestHash().equals(manifest.bindingManifestHash())) {
            throw new MigrationException("Catalog Runtime Activation Uses Mixed Binding Generations");
        }
        if (!CatalogCanonicalizer.checksumForCanonicalContent(catalog.canonicalContent()).equals(catalog.contentChecksum())) {
            throw new MigrationException("Active Catalog Content Checksum Does Not Match Canonical Content");
        }
        if (catalog.diagnostics().stream().anyMatch(diagnostic -> diagnostic.severity() == DiagnosticSeverity.ERROR)) {
            throw new MigrationException("Active Catalog Contains Error Diagnostics");
        }
        try {
            if (!CanonicalCodec.decode(catalog.canonicalBytes()).canonicalText().equals(catalog.canonicalContent())) {
                throw new MigrationException("Active Catalog Content Is Not Canonical");
            }
            RuntimeBindingManifest decoded = RuntimeBindingManifest.fromCanonical(manifest.wireCanonicalForm());
            if (!decoded.bindingManifestHash().equals(manifest.bindingManifestHash())) {
                throw new MigrationException("Active Runtime Binding Manifest Hash Does Not Match Canonical Content");
            }
        } catch (RuntimeException exception) {
            throw new MigrationException("Active Catalog Runtime Canonical Content Is Invalid", exception);
        }
        if (runtime.providers().values().stream().anyMatch(provider -> provider.state() != RuntimeProviderState.ACTIVE)) {
            throw new MigrationException("Active Runtime Binding Manifest Contains A Non-Active Provider");
        }
        if (runtime.bindingValues().stream().anyMatch(binding -> !binding.available())) {
            throw new MigrationException("Active Runtime Binding Manifest Contains An Unavailable Binding");
        }
        if (manifest.diagnostics().stream().anyMatch(diagnostic -> diagnostic.severity() == DiagnosticSeverity.ERROR)) {
            throw new MigrationException("Active Runtime Binding Manifest Contains Error Diagnostics");
        }
        for (RuntimeBinding binding : runtime.bindingValues()) {
            if (!runtime.provider(binding.provider()).map(provider -> provider.state() == RuntimeProviderState.ACTIVE).orElse(false)) {
                throw new MigrationException("Active Runtime Binding Provider Is Not Active: " + binding.provider().canonicalText());
            }
        }
    }

    private static void validateReadiness(Source source, CatalogSnapshot catalog, RuntimeBindingManifest manifest) throws IOException {
        PersistenceRootReadiness readiness = source.readiness();
        ReSyncPersistenceCoordinator persistence = source.persistence();
        boolean startupProof = exactStartupProof(source);
        if (!persistence.sealed() || persistence.shutdownStarted()) {
            throw new MigrationException("Persistence Coordinator Is Not Sealed And Active");
        }
        if (!readiness.complete() || !readiness.writerInventoryComplete() || !readiness.requiredGaps().isEmpty()) {
            throw new MigrationException("Persistence Root Readiness Is Incomplete");
        }
        AuthorityReadiness authorityReadiness = authorityReadiness(source);
        ProductionAuthorityBundlePersistenceParticipant regenerableAuthority = authorityReadiness.regenerationParticipant();
        if (startupProof && !source.activationAuthority().requiresBindingContext()) {
            throw new MigrationException("Production Authority Startup Proof Requires A Binding-Context Activation");
        }
        if (startupProof && regenerableAuthority == null && authorityReadiness.refreshedParticipant() == null) {
            throw new MigrationException("Production Authority Startup Proof Requires The Scoped Authority Participant");
        }
        PersistenceRootReadiness participantReadiness = authorityReadiness.refreshedParticipant() == null
            ? readiness : refreshedReadiness(readiness, authorityReadiness.refreshedParticipant());
        if (!readiness.unavailableOwners().isEmpty()
            && regenerableAuthority == null && authorityReadiness.refreshedParticipant() == null) {
            throw new MigrationException("Persistence Root Readiness Contains Unavailable Owners: "
                + readiness.unavailableOwners().stream().map(PersistenceRootReadiness.Owner::owner).toList());
        }
        Set<String> reportOwners = participantReadiness.owners().stream()
            .filter(owner -> owner.state() == PersistenceRootReadiness.State.REGISTERED
                || regenerableAuthority != null
                && owner.owner().equals(ProductionAuthorityBundlePersistenceParticipant.OWNER))
            .map(PersistenceRootReadiness.Owner::owner)
            .collect(Collectors.toUnmodifiableSet());
        Set<String> participantOwners = persistence.registeredParticipants().stream()
            .map(PersistenceParticipant::owner)
            .collect(Collectors.toUnmodifiableSet());
        if (!reportOwners.equals(participantOwners)) {
            throw new MigrationException("Persistence Root Readiness Does Not Match The Sealed Participant Registry");
        }
        Map<String, String> reportBindings = participantReadiness.owners().stream()
            .filter(owner -> owner.state() == PersistenceRootReadiness.State.REGISTERED
                || regenerableAuthority != null
                && owner.owner().equals(ProductionAuthorityBundlePersistenceParticipant.OWNER))
            .collect(Collectors.toUnmodifiableMap(PersistenceRootReadiness.Owner::owner,
                owner -> MigrationPaths.requirePath(owner.root(), "readiness owner root") + "|" + owner.classification()));
        Map<String, String> participantBindings = persistence.registeredParticipants().stream()
            .collect(Collectors.toUnmodifiableMap(PersistenceParticipant::owner,
                participant -> MigrationPaths.requirePath(participant.root(), "participant root") + "|" + participant.classification()));
        if (!reportBindings.equals(participantBindings)) {
            throw new MigrationException("Persistence Root Readiness Does Not Match The Sealed Participant Roots");
        }
        Map<String, PersistenceExternalInput.Input> reportInputs = readiness.externalInputs().stream()
            .collect(Collectors.toUnmodifiableMap(PersistenceExternalInput.Input::id, input -> input));
        Map<String, PersistenceExternalInput.Input> participantInputs = persistence.participants().externalInputs().stream()
            .collect(Collectors.toUnmodifiableMap(PersistenceExternalInput.Input::id, input -> input));
        if (!reportInputs.equals(participantInputs)) {
            throw new MigrationException("Persistence Root Readiness Does Not Match The Sealed External Inputs");
        }
        if (!startupProof) {
            for (PersistenceParticipant participant : persistence.registeredParticipants()) {
                if (participant == regenerableAuthority) {
                    continue;
                }
                try {
                    participant.healthCheck();
                } catch (IOException | RuntimeException exception) {
                    throw new MigrationException("Persistence Participant Is Unavailable: " + participant.owner(), exception);
                }
            }
        }
        if (!ContentHash.of(MigrationCanonical.sha256(readiness.canonicalReport())).equals(ContentHash.of(readiness.reportHash()))) {
            throw new MigrationException("Persistence Root Readiness Report Hash Does Not Match Canonical Content");
        }
        CatalogActivationAuthority.Decision decision = CatalogActivationAuthority.evaluateBindingContext(
            catalog, manifest, readiness, source.activationAuthority());
        if (!decision.allowed()) {
            throw new MigrationException(decision.code() + ": " + decision.detail());
        }
    }

    private static boolean exactStartupProof(Source source) throws IOException {
        ReSyncPersistenceCoordinator.ReadinessProof proof = source.readinessProof();
        if (proof == null) {
            return false;
        }
        if (proof.readiness() != source.readiness()
            || source.persistence().currentValidatedReadinessProof(source.readiness())
                .filter(current -> current == proof).isEmpty()) {
            throw new MigrationException("Production Authority Startup Readiness Proof Is Stale");
        }
        return true;
    }

    private static AuthorityReadiness authorityReadiness(Source source) {
        PersistenceRootReadiness readiness = source.readiness();
        if (readiness.unavailableOwners().size() != 1) {
            return AuthorityReadiness.none();
        }
        PersistenceRootReadiness.Owner owner = readiness.unavailableOwners().getFirst();
        Path expectedRoot = MigrationPaths.requirePath(source.persistence().dataRoot()
            .resolve(ProductionAuthorityBundlePersistenceParticipant.DIRECTORY), "authority bundle participant root");
        if (owner.state() != PersistenceRootReadiness.State.UNAVAILABLE
            || !owner.owner().equals(ProductionAuthorityBundlePersistenceParticipant.OWNER)
            || owner.required()
            || owner.classification() != PersistenceParticipantClassification.DERIVED_CACHE
            || !MigrationPaths.requirePath(owner.root(), "readiness owner root").equals(expectedRoot)) {
            return AuthorityReadiness.none();
        }
        ProductionAuthorityBundlePersistenceParticipant candidate = source.persistence().registeredParticipants().stream()
            .filter(ProductionAuthorityBundlePersistenceParticipant.class::isInstance)
            .map(ProductionAuthorityBundlePersistenceParticipant.class::cast)
            .filter(participant -> participant.owner().equals(owner.owner())
                && participant.classification() == PersistenceParticipantClassification.DERIVED_CACHE
                && MigrationPaths.requirePath(participant.root(), "authority participant root").equals(expectedRoot))
            .findFirst()
            .orElse(null);
        if (candidate == null) {
            return AuthorityReadiness.none();
        }
        ProductionAuthorityBundlePersistenceParticipant.Health health = candidate.health();
        if (!health.available() && health.regenerationRequired() && health.bundle() == null) {
            return new AuthorityReadiness(candidate, null);
        }
        if (health.available() && health.bundle() != null) {
            return new AuthorityReadiness(null, candidate);
        }
        return AuthorityReadiness.none();
    }

    private static ProductionAuthorityBundlePersistenceParticipant regenerationAuthority(Source source) {
        return authorityReadiness(source).regenerationParticipant();
    }

    private static PersistenceRootReadiness refreshedReadiness(
        PersistenceRootReadiness readiness,
        ProductionAuthorityBundlePersistenceParticipant participant
    ) {
        return new PersistenceRootReadiness(
            readiness.owners().stream()
                .map(owner -> owner.owner().equals(ProductionAuthorityBundlePersistenceParticipant.OWNER)
                    ? PersistenceRootReadiness.Owner.registered(owner.owner(), participant.root(), owner.required(), owner.classification())
                    : owner)
                .toList(),
            readiness.uncoveredWriters(), readiness.externalInputs());
    }

    private static ProductionAuthorityBundle replaceExisting(Source source, Path target,
                                                              ProductionAuthorityBundle bundle) throws IOException {
        byte[] existing = Files.readAllBytes(target);
        Path quarantine = MigrationPaths.resolveInside(source.persistence().dataRoot(),
            ".quarantine/authority-bundle/" + MigrationCanonical.sha256(existing) + ".json");
        preserveExisting(quarantine, existing);
        byte[] replacement = bundle.canonicalBytes();
        AtomicFiles.write(target, replacement);
        if (!Arrays.equals(replacement, Files.readAllBytes(target))) {
            throw new MigrationException("Authority Bundle Replacement Verification Failed: " + target);
        }
        return bundle;
    }

    private static void preserveExisting(Path quarantine, byte[] existing) throws IOException {
        if (Files.exists(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(quarantine) || !Files.isRegularFile(quarantine, LinkOption.NOFOLLOW_LINKS)
                || !Arrays.equals(existing, Files.readAllBytes(quarantine))) {
                throw new MigrationException("Authority Bundle Quarantine Target Contains Different Content: " + quarantine);
            }
            return;
        }
        try {
            AtomicFiles.writeNew(quarantine, existing);
        } catch (FileAlreadyExistsException exception) {
            if (Files.isRegularFile(quarantine, LinkOption.NOFOLLOW_LINKS)
                && Arrays.equals(existing, Files.readAllBytes(quarantine))) {
                return;
            }
            throw new MigrationException("Authority Bundle Quarantine Target Already Contains Different Content: " + quarantine,
                exception);
        }
    }

    private static ProductionAuthorityBundle validateAuthority(
        CatalogActivationAuthority authority,
        ProductionAuthorityBundle.TrustContext trustContext,
        ServerId serverId,
        SnapshotId snapshotId,
        CatalogSnapshot catalog,
        RuntimeBindingManifest manifest,
        PersistenceRootReadiness readiness,
        Instant now
    ) throws IOException {
        if (authority == null || !authority.approved() || !authority.requiresBindingContext()) {
            throw new MigrationException("A Committed Replacement Activation Authority Is Required");
        }
        CatalogActivationAuthority.Decision decision = CatalogActivationAuthority.evaluateBindingContext(
            catalog, manifest, readiness, authority);
        if (!decision.allowed()) {
            throw new MigrationException(decision.code() + ": " + decision.detail());
        }
        ProductionAuthorityBundle retained = authority.authorityBundle().orElse(null);
        if (retained == null) {
            return null;
        }
        if (!retained.serverId().equals(serverId)
            || !retained.snapshotId().equals(snapshotId)
            || !retained.catalogContentChecksum().equals(catalog.contentChecksum())
            || retained.catalogGeneration() != catalog.generation()
            || !retained.catalogContractVersion().equals(catalog.contractVersion())
            || !retained.runtimeBindingManifestHash().equals(manifest.bindingManifestHash())
            || retained.runtimeBindingManifestVersion() != manifest.version()
            || !retained.readinessReportHash().equals(readiness.reportHash())
            || retained.readinessReportVersion() != readiness.reportVersion()) {
            throw new MigrationException("Committed Authority Bundle Does Not Match Active Authorities");
        }
        if (!retained.freshAt(now)) {
            throw new MigrationException("Committed Authority Bundle Is Outside The Freshness Window");
        }
        if (!retained.verifySignature(trustContext)) {
            throw new MigrationException("Committed Authority Bundle Signature Does Not Match Trusted Durable Install Authority");
        }
        return retained;
    }

    private static ProductionAuthorityBundle.TrustContext trustContext(Source source, SnapshotId snapshotId) throws IOException {
        Path dataRoot = source.persistence().dataRoot();
        ProductionAuthorityBundle.TrustedAuthority trustedAuthority = ProductionAuthorityBundle.TrustedAuthority.from(
            dataRoot, source.signer());
        return new ProductionAuthorityBundle.TrustContext(trustedAuthority, dataRoot, snapshotId, TRUST_CONTEXT_MIGRATION_ID);
    }

    private static void validateSigner(Source source) throws IOException {
        ProductionAuthoritySigner signer = source.signer();
        if (signer == null || !source.serverId().equals(signer.serverId())) {
            throw new MigrationException("A Stable Production Authority Signer Is Required");
        }
        String durableHash = ProductionAuthorityBundle.installAuthorityDigest(source.persistence().dataRoot());
        if (!durableHash.equals(MigrationCanonical.requireDigest(signer.installAuthorityHash(), "installAuthorityHash"))) {
            throw new MigrationException("Production Authority Signer Is Not Bound To Durable Install Authority");
        }
    }

    private static void validatePersistedActivation(
        ReSyncPersistenceCoordinator persistence,
        CatalogSnapshot catalog,
        RuntimeBindingManifest manifest,
        PersistenceRootReadiness readiness
    ) throws IOException {
        ReplacementActivationRecord.Values record;
        try {
            record = ReplacementActivationRecord.read(persistence.dataRoot());
        } catch (IOException | RuntimeException exception) {
            throw new MigrationException("Persisted Replacement Activation Record Is Unavailable", exception);
        }
        if (record.catalogGeneration() != catalog.generation()
            || !record.catalogChecksum().equals(catalog.contentChecksum().canonicalText())
            || record.runtimeBindingManifestVersion() != manifest.version()
            || !record.runtimeBindingManifestHash().equals(manifest.bindingManifestHash().canonicalText())
            || record.participantReadinessVersion() != readiness.reportVersion()
            || !record.participantReadinessHash().equals(readiness.reportHash())) {
            throw new MigrationException("Persisted Replacement Activation Record Does Not Bind The Active Authorities");
        }
    }

    private static void validateStableIdentity(Source source) throws IOException {
        Path identityPath = MigrationPaths.requirePath(source.persistence().dataRoot().resolve("server-id"), "server identity path");
        if (!Files.exists(identityPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Stable Server Identity Projection Is Missing");
        }
        if (Files.isSymbolicLink(identityPath) || !Files.isRegularFile(identityPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Stable Server Identity Projection Is Invalid");
        }
        String text = Files.readString(identityPath, StandardCharsets.UTF_8);
        if (!text.endsWith("\n") || text.indexOf('\r') >= 0
            || !text.substring(0, text.length() - 1).equals(source.serverId().canonicalText())) {
            throw new MigrationException("Stable Server Identity Does Not Match The Authority Bundle Source");
        }
        try {
            if (!ServerId.parseCanonicalText(text.substring(0, text.length() - 1)).equals(source.serverId())) {
                throw new MigrationException("Stable Server Identity Does Not Match The Authority Bundle Source");
            }
        } catch (IllegalArgumentException exception) {
            throw new MigrationException("Stable Server Identity Projection Is Invalid", exception);
        }
    }

    private static SnapshotId snapshotId(
        ServerId serverId,
        CatalogSnapshot catalog,
        RuntimeBindingManifest manifest,
        PersistenceRootReadiness readiness
    ) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("serverId", serverId.canonicalText());
        identity.put("catalogChecksum", catalog.contentChecksum().canonicalText());
        identity.put("catalogGeneration", catalog.generation());
        identity.put("catalogContractVersion", Map.of("generation", catalog.contractVersion().generation(), "minor", catalog.contractVersion().minor()));
        identity.put("runtimeBindingManifestHash", manifest.bindingManifestHash().canonicalText());
        identity.put("runtimeBindingManifestVersion", manifest.version());
        identity.put("readinessReportHash", readiness.reportHash());
        identity.put("readinessReportVersion", readiness.reportVersion());
        String seed = CanonicalJson.canonicalize(identity);
        UUID value = UuidIdentity.deterministic(UuidIdentity.MIGRATION_NAMESPACE, SNAPSHOT_ID_DOMAIN, seed);
        return SnapshotId.of(value);
    }

    private record AuthorityReadiness(
        ProductionAuthorityBundlePersistenceParticipant regenerationParticipant,
        ProductionAuthorityBundlePersistenceParticipant refreshedParticipant
    ) {
        private static AuthorityReadiness none() {
            return new AuthorityReadiness(null, null);
        }
    }

    public record Source(
        CatalogRuntimeActivation activation,
        CatalogActivationAuthority activationAuthority,
        PersistenceRootReadiness readiness,
        ReSyncPersistenceCoordinator persistence,
        ServerId serverId,
        ProductionAuthoritySigner signer,
        ReSyncPersistenceCoordinator.ReadinessProof readinessProof
    ) {
        public Source(
            CatalogRuntimeActivation activation,
            CatalogActivationAuthority activationAuthority,
            PersistenceRootReadiness readiness,
            ReSyncPersistenceCoordinator persistence,
            ServerId serverId
        ) {
            this(activation, activationAuthority, readiness, persistence, serverId, null, null);
        }

        public Source(
            CatalogRuntimeActivation activation,
            CatalogActivationAuthority activationAuthority,
            PersistenceRootReadiness readiness,
            ReSyncPersistenceCoordinator persistence,
            ServerId serverId,
            ProductionAuthoritySigner signer
        ) {
            this(activation, activationAuthority, readiness, persistence, serverId, signer, null);
        }

        public Source {
            activation = Objects.requireNonNull(activation, "Catalog Runtime Activation Is Required");
            activationAuthority = Objects.requireNonNull(activationAuthority, "Catalog Activation Authority Is Required");
            readiness = Objects.requireNonNull(readiness, "Persistence Root Readiness Is Required");
            persistence = Objects.requireNonNull(persistence, "Persistence Coordinator Is Required");
            serverId = Objects.requireNonNull(serverId, "Stable Server ID Is Required");
        }
    }
}

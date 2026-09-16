package restudio.resync.migration;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

@FunctionalInterface
public interface ActivationMarkerWriter {
    void write(MigrationPlan plan, StagedMigration staged) throws IOException;

    default Optional<Binding> binding() {
        return Optional.empty();
    }

    static ActivationMarkerWriter none() {
        return (plan, staged) -> {
        };
    }

    static ActivationMarkerWriter replacementRoot(Binding binding) {
        Objects.requireNonNull(binding, "binding");
        return new ActivationMarkerWriter() {
            @Override
            public void write(MigrationPlan plan, StagedMigration staged) throws IOException {
                binding.requireAuthenticated(plan);
                MigrationActivationMarker.write(
                    staged.root(),
                    new MigrationActivationMarker.Values(
                        plan.sourceManifestHash(),
                        plan.planHash(),
                        staged.contentHash(),
                        archivedSourceDigest(staged),
                        binding.replacementCatalogHash(),
                        binding.runtimeBindingManifestHash(),
                        binding.runtimeBindingManifestVersion(),
                        binding.participantReadinessHash(),
                        binding.participantReadinessVersion(),
                        true,
                        binding.authorityBundle(),
                        binding.authorityTrustAnchorHash(),
                        binding.authorityUseGrant()));
            }

            private String archivedSourceDigest(StagedMigration staged) throws IOException {
                if (staged.previousRoot().isEmpty()) {
                    return MigrationActivationMarker.NO_ARCHIVED_SOURCE_DIGEST;
                }
                return TreeDigest.of(staged.previousRoot().orElseThrow());
            }

            @Override
            public Optional<Binding> binding() {
                return Optional.of(binding);
            }
        };
    }

    static ActivationMarkerWriter replacementRoot(ProductionAuthorityBundle authorityBundle,
                                                  ProductionAuthorityBundle.TrustContext trustContext) {
        return replacementRoot(new Binding(authorityBundle, trustContext));
    }

    static ActivationMarkerWriter replacementRoot(ProductionAuthorityBundle authorityBundle,
                                                  ProductionAuthorityTrustAnchor trustAnchor,
                                                  AuthorityUseGrant authorityUseGrant) {
        return replacementRoot(new Binding(authorityBundle, trustAnchor, authorityUseGrant));
    }

    public record Binding(String replacementCatalogHash, String runtimeBindingManifestHash,
                          int runtimeBindingManifestVersion, String participantReadinessHash,
                          int participantReadinessVersion, ProductionAuthorityBundle authorityBundle,
                          ProductionAuthorityBundle.TrustContext trustContext,
                          ProductionAuthorityTrustAnchor trustAnchor,
                          AuthorityUseGrant authorityUseGrant) {
        public Binding(String replacementCatalogHash, String runtimeBindingManifestHash,
                       int runtimeBindingManifestVersion, String participantReadinessHash,
                       int participantReadinessVersion) {
            this(replacementCatalogHash, runtimeBindingManifestHash, runtimeBindingManifestVersion,
                participantReadinessHash, participantReadinessVersion, null, null, null, null);
        }

        public Binding(String replacementCatalogHash, String runtimeBindingManifestHash,
                       int runtimeBindingManifestVersion, String participantReadinessHash,
                       int participantReadinessVersion, ProductionAuthorityBundle authorityBundle) {
            this(replacementCatalogHash, runtimeBindingManifestHash, runtimeBindingManifestVersion,
                participantReadinessHash, participantReadinessVersion, authorityBundle, null, null, null);
        }

        public Binding(ProductionAuthorityBundle authorityBundle) {
            this(Objects.requireNonNull(authorityBundle, "authorityBundle").catalogContentChecksum().canonicalText(),
                authorityBundle.runtimeBindingManifestHash().canonicalText(), authorityBundle.runtimeBindingManifestVersion(),
                authorityBundle.readinessReportHash(), authorityBundle.readinessReportVersion(), authorityBundle, null, null, null);
        }

        public Binding(ProductionAuthorityBundle authorityBundle, ProductionAuthorityBundle.TrustContext trustContext) {
            this(Objects.requireNonNull(authorityBundle, "authorityBundle").catalogContentChecksum().canonicalText(),
                authorityBundle.runtimeBindingManifestHash().canonicalText(), authorityBundle.runtimeBindingManifestVersion(),
                authorityBundle.readinessReportHash(), authorityBundle.readinessReportVersion(), authorityBundle, trustContext,
                trustAnchor(trustContext), null);
        }

        public Binding(ProductionAuthorityBundle authorityBundle, ProductionAuthorityTrustAnchor trustAnchor,
                       AuthorityUseGrant authorityUseGrant) {
            this(Objects.requireNonNull(authorityBundle, "authorityBundle").catalogContentChecksum().canonicalText(),
                authorityBundle.runtimeBindingManifestHash().canonicalText(), authorityBundle.runtimeBindingManifestVersion(),
                authorityBundle.readinessReportHash(), authorityBundle.readinessReportVersion(), authorityBundle, null,
                Objects.requireNonNull(trustAnchor, "trustAnchor"), Objects.requireNonNull(authorityUseGrant, "authorityUseGrant"));
        }

        public static Binding authenticated(ProductionAuthorityBundle authorityBundle,
                                             ProductionAuthorityBundle.TrustContext trustContext) {
            return new Binding(authorityBundle, trustContext);
        }

        public static Binding authenticated(ProductionAuthorityBundle authorityBundle,
                                             ProductionAuthorityTrustAnchor trustAnchor,
                                             AuthorityUseGrant authorityUseGrant) {
            return new Binding(authorityBundle, trustAnchor, authorityUseGrant);
        }

        public Binding {
            replacementCatalogHash = MigrationCanonical.requireDigest(replacementCatalogHash, "replacementCatalogHash");
            runtimeBindingManifestHash = MigrationCanonical.requireDigest(runtimeBindingManifestHash, "runtimeBindingManifestHash");
            if (runtimeBindingManifestVersion < 1) {
                throw new IllegalArgumentException("runtimeBindingManifestVersion Must Be Positive");
            }
            participantReadinessHash = MigrationCanonical.requireDigest(participantReadinessHash, "participantReadinessHash");
            if (participantReadinessVersion < 1) {
                throw new IllegalArgumentException("participantReadinessVersion Must Be Positive");
            }
            if (authorityBundle == null && trustContext != null) {
                throw new IllegalArgumentException("A Trusted Activation Context Requires An Authority Bundle");
            }
            if (authorityUseGrant != null && (authorityBundle == null || trustAnchor == null)) {
                throw new IllegalArgumentException("A Signed Authority Use Grant Requires A Pinned Trust Anchor And Bundle");
            }
            if (trustAnchor != null && authorityBundle == null) {
                throw new IllegalArgumentException("A Pinned Trust Anchor Requires An Authority Bundle");
            }
            if (authorityUseGrant != null && (!authorityUseGrant.bundleHash().equals(authorityBundle.bundleHash())
                || !authorityUseGrant.serverId().equals(authorityBundle.serverId())
                || !authorityUseGrant.installationId().equals(authorityBundle.installationId())
                || !authorityUseGrant.installAuthorityHash().equals(authorityBundle.installAuthorityHash())
                || !authorityUseGrant.snapshotId().equals(authorityBundle.snapshotId()))) {
                throw new IllegalArgumentException("Authority Use Grant Does Not Match Activation Authority");
            }
            if (trustAnchor != null && !trustAnchor.matches(authorityBundle)) {
                throw new IllegalArgumentException("Pinned Trust Anchor Does Not Match Activation Authority");
            }
            if (authorityBundle != null && (!authorityBundle.catalogContentChecksum().canonicalText().equals(replacementCatalogHash)
                || !authorityBundle.runtimeBindingManifestHash().canonicalText().equals(runtimeBindingManifestHash)
                || authorityBundle.runtimeBindingManifestVersion() != runtimeBindingManifestVersion
                || !authorityBundle.readinessReportHash().equals(participantReadinessHash)
                || authorityBundle.readinessReportVersion() != participantReadinessVersion)) {
                throw new IllegalArgumentException("Authority Bundle Does Not Match Activation Binding");
            }
        }

        public Binding withTrustContext(ProductionAuthorityBundle.TrustContext context) {
            return new Binding(replacementCatalogHash, runtimeBindingManifestHash, runtimeBindingManifestVersion,
                participantReadinessHash, participantReadinessVersion, authorityBundle, context,
                trustAnchor(context), authorityUseGrant);
        }

        public Binding withAuthorityContext(ProductionAuthorityBundle.TrustContext context) {
            return withTrustContext(context);
        }

        public ProductionAuthorityBundle.TrustContext trustedContext() {
            return trustContext;
        }

        public String authorityTrustAnchorHash() {
            return trustAnchor == null ? "" : MigrationCanonical.sha256(trustAnchor.canonicalBytes());
        }

        public void requireAuthenticated(MigrationPlan plan) throws IOException {
            Objects.requireNonNull(plan, "plan");
            ProductionAuthorityBundle authority = authorityBundle;
            if (authority == null || trustAnchor == null || authorityUseGrant == null) {
                throw new MigrationException("Activation Marker Requires A Pinned Trust Anchor And Signed Authority Use Grant");
            }
            if (!authority.snapshotId().canonicalText().equals(plan.sourceSnapshotId())) {
                throw new MigrationException("Activation Marker Authority Snapshot Does Not Match Migration Plan");
            }
            authority.requireAuthenticated(trustAnchor);
            if (!authorityUseGrant.bundleHash().equals(authority.bundleHash())) {
                throw new MigrationException("Activation Marker Authority Requires A Matching Signed Authority Use Grant");
            }
        }

        private static ProductionAuthorityTrustAnchor trustAnchor(ProductionAuthorityBundle.TrustContext context) {
            return context == null ? null : context.trustedAuthority().trustAnchor();
        }
    }
}

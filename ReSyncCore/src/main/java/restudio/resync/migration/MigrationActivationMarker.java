package restudio.resync.migration;

import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.SnapshotId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

public final class MigrationActivationMarker {
    public static final String MARKER_FILE = "assets/.migrations/replacement-activation.marker";
    public static final int FORMAT_VERSION = 2;
    public static final int AUTHORITY_FORMAT_VERSION = 4;
    public static final int AUTHORITY_GRANT_FORMAT_VERSION = 5;
    public static final String NO_ARCHIVED_SOURCE_DIGEST = "0".repeat(64);

    public record Values(String sourceManifestHash, String planHash, String replacementRootHash,
                         String archivedSourceDigest, String replacementCatalogHash, String runtimeBindingManifestHash,
                         int runtimeBindingManifestVersion, String participantReadinessHash,
                         int participantReadinessVersion, boolean participantReadinessComplete,
                         ProductionAuthorityBundle authorityBundle,
                         String authorityTrustAnchorHash,
                         AuthorityUseGrant authorityUseGrant) {
        public Values(String sourceManifestHash, String planHash, String replacementRootHash,
                      String replacementCatalogHash, String runtimeBindingManifestHash,
                      int runtimeBindingManifestVersion, String participantReadinessHash,
                      int participantReadinessVersion, boolean participantReadinessComplete) {
            this(sourceManifestHash, planHash, replacementRootHash, NO_ARCHIVED_SOURCE_DIGEST,
                replacementCatalogHash, runtimeBindingManifestHash, runtimeBindingManifestVersion,
                participantReadinessHash, participantReadinessVersion, participantReadinessComplete, null, "", null);
        }

        public Values(String sourceManifestHash, String planHash, String replacementRootHash,
                      String archivedSourceDigest, String replacementCatalogHash, String runtimeBindingManifestHash,
                      int runtimeBindingManifestVersion, String participantReadinessHash,
                      int participantReadinessVersion, boolean participantReadinessComplete,
                      ProductionAuthorityBundle authorityBundle) {
            this(sourceManifestHash, planHash, replacementRootHash, archivedSourceDigest, replacementCatalogHash,
                runtimeBindingManifestHash, runtimeBindingManifestVersion, participantReadinessHash,
                participantReadinessVersion, participantReadinessComplete, authorityBundle, "", null);
        }

        public Values(String sourceManifestHash, String planHash, String replacementRootHash,
                      String archivedSourceDigest, String replacementCatalogHash, String runtimeBindingManifestHash,
                      int runtimeBindingManifestVersion, String participantReadinessHash,
                      int participantReadinessVersion, boolean participantReadinessComplete) {
            this(sourceManifestHash, planHash, replacementRootHash, archivedSourceDigest,
                replacementCatalogHash, runtimeBindingManifestHash, runtimeBindingManifestVersion,
                participantReadinessHash, participantReadinessVersion, participantReadinessComplete, null, "", null);
        }

        public Values {
            sourceManifestHash = MigrationCanonical.requireDigest(sourceManifestHash, "sourceManifestHash");
            planHash = MigrationCanonical.requireDigest(planHash, "planHash");
            replacementRootHash = MigrationCanonical.requireDigest(replacementRootHash, "replacementRootHash");
            archivedSourceDigest = MigrationCanonical.requireDigest(archivedSourceDigest, "archivedSourceDigest");
            replacementCatalogHash = MigrationCanonical.requireDigest(replacementCatalogHash, "replacementCatalogHash");
            runtimeBindingManifestHash = MigrationCanonical.requireDigest(runtimeBindingManifestHash, "runtimeBindingManifestHash");
            if (runtimeBindingManifestVersion < 1) {
                throw new IllegalArgumentException("runtimeBindingManifestVersion Must Be Positive");
            }
            participantReadinessHash = MigrationCanonical.requireDigest(participantReadinessHash, "participantReadinessHash");
            if (participantReadinessVersion < 1) {
                throw new IllegalArgumentException("participantReadinessVersion Must Be Positive");
            }
            if (!participantReadinessComplete) {
                throw new IllegalArgumentException("participantReadinessComplete Must Be True");
            }
            authorityTrustAnchorHash = authorityTrustAnchorHash == null || authorityTrustAnchorHash.isBlank()
                ? "" : MigrationCanonical.requireDigest(authorityTrustAnchorHash, "authorityTrustAnchorHash");
            if (authorityUseGrant != null && (authorityBundle == null || authorityTrustAnchorHash.isEmpty())) {
                throw new IllegalArgumentException("Authority Use Grant Marker Fields Are Incomplete");
            }
            if (authorityUseGrant != null && (authorityBundle == null
                || !authorityUseGrant.bundleHash().equals(authorityBundle.bundleHash())
                || !authorityUseGrant.serverId().equals(authorityBundle.serverId())
                || !authorityUseGrant.installationId().equals(authorityBundle.installationId())
                || !authorityUseGrant.installAuthorityHash().equals(authorityBundle.installAuthorityHash())
                || !authorityUseGrant.snapshotId().equals(authorityBundle.snapshotId()))) {
                throw new IllegalArgumentException("Authority Use Grant Does Not Match Activation Marker Bundle");
            }
        }

        public String participantReadinessReportHash() {
            return participantReadinessHash;
        }
    }

    private MigrationActivationMarker() {
    }

    public static Path markerPath(Path root) {
        return MigrationPaths.resolveInside(MigrationPaths.requirePath(root, "root"), MARKER_FILE);
    }

    public static void write(Path root, Values values) throws IOException {
        MigrationPaths.requireDirectory(root, "root");
        Values verified = new Values(values.sourceManifestHash(), values.planHash(), values.replacementRootHash(),
            values.archivedSourceDigest(), values.replacementCatalogHash(), values.runtimeBindingManifestHash(), values.runtimeBindingManifestVersion(),
            values.participantReadinessHash(), values.participantReadinessVersion(), values.participantReadinessComplete(),
            values.authorityBundle(), values.authorityTrustAnchorHash(), values.authorityUseGrant());
        String canonical = canonical(verified);
        AtomicFiles.write(markerPath(root), (canonical + "marker-hash=" + MigrationCanonical.sha256(canonical) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    public static Values read(Path root) throws IOException {
        MigrationPaths.requireDirectory(root, "root");
        Path marker = markerPath(root);
        if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Activation Marker Is Missing");
        }
        if (Files.size(marker) > 256 * 1024) {
            throw new MigrationException("Activation Marker Is Too Large");
        }
        byte[] bytes = Files.readAllBytes(marker);
        String content = decodeUtf8(bytes);
        if (content.indexOf('\r') >= 0 || !content.endsWith("\n")) {
            throw new MigrationException("Activation Marker Header Is Invalid");
        }
        List<String> lines = Arrays.asList(content.split("\n", -1));
        if (lines.getFirst().equals("format=1")) {
            throw new MigrationException("Legacy Activation Marker Format 1 Is Not Accepted");
        }
        boolean authorityFormat = ("format=" + AUTHORITY_FORMAT_VERSION).equals(lines.getFirst())
            || ("format=" + AUTHORITY_GRANT_FORMAT_VERSION).equals(lines.getFirst());
        boolean authorityGrantFormat = ("format=" + AUTHORITY_GRANT_FORMAT_VERSION).equals(lines.getFirst());
        if ((!authorityFormat && !("format=" + FORMAT_VERSION).equals(lines.getFirst())) || !"state=COMMITTED".equals(lines.get(1))) {
            throw new MigrationException("Activation Marker Header Is Invalid");
        }
        String source = value(lines, 2, "source-manifest-hash=");
        String plan = value(lines, 3, "plan-hash=");
        String replacement = value(lines, 4, "replacement-root-hash=");
        String archivedSource = value(lines, 5, "archived-source-digest=");
        String catalog = value(lines, 6, "replacement-catalog-hash=");
        int runtimeVersion = integer(lines, 7, "runtime-binding-manifest-version=");
        String runtimeHash = value(lines, 8, "runtime-binding-manifest-hash=");
        int readinessVersion = integer(lines, 9, "participant-readiness-version=");
        String readinessHash = value(lines, 10, "participant-readiness-hash=");
        if (!"participant-readiness-complete=true".equals(lines.get(11))) {
            throw new MigrationException("Activation Marker Participant Readiness Is Incomplete");
        }
        if (!"verification=offline-upgrader".equals(lines.get(12))) {
            throw new MigrationException("Activation Marker Verification Is Invalid");
        }
        ProductionAuthorityBundle authority = authorityFormat
            ? authority(lines, 13, authorityGrantFormat)
            : null;
        String anchorHash = "";
        AuthorityUseGrant grant = null;
        if (authorityGrantFormat) {
            anchorHash = MigrationCanonical.requireDigest(value(lines, 31, "authority-trust-anchor-hash="), "authorityTrustAnchorHash");
            try {
                grant = AuthorityUseGrant.fromCanonical(MigrationCanonical.decode(value(lines, 32, "authority-use-grant=")));
            } catch (RuntimeException exception) {
                throw new MigrationException("Activation Marker Authority Use Grant Is Invalid", exception);
            }
        }
        int markerIndex = authorityGrantFormat ? 33 : authorityFormat ? 28 : 13;
        if ((!authorityFormat && lines.size() != 15) || (authorityFormat && !authorityGrantFormat && lines.size() != 30)
            || (authorityGrantFormat && lines.size() != 35)) {
            throw new MigrationException("Activation Marker Field Count Is Invalid");
        }
        String markerHash = value(lines, markerIndex, "marker-hash=");
        Values values = new Values(source, plan, replacement, archivedSource, catalog, runtimeHash, runtimeVersion,
            readinessHash, readinessVersion, true, authority, anchorHash, grant);
        MigrationCanonical.requireDigest(markerHash, "markerHash");
        String expected = canonical(values) + "marker-hash=" + MigrationCanonical.sha256(canonical(values)) + "\n";
        if (!expected.equals(content) || !MigrationCanonical.sha256(canonical(values)).equals(markerHash)) {
            throw new MigrationException("Activation Marker Hash Does Not Match");
        }
        return values;
    }

    private static String canonical(Values values) {
        int format = values.authorityUseGrant() == null
            ? values.authorityBundle() == null ? FORMAT_VERSION : AUTHORITY_FORMAT_VERSION
            : AUTHORITY_GRANT_FORMAT_VERSION;
        StringBuilder content = new StringBuilder("format=" + format + "\nstate=COMMITTED\nsource-manifest-hash=")
            .append(values.sourceManifestHash())
            .append("\nplan-hash=").append(values.planHash()).append("\nreplacement-root-hash=").append(values.replacementRootHash())
            .append("\narchived-source-digest=").append(values.archivedSourceDigest())
            .append("\nreplacement-catalog-hash=").append(values.replacementCatalogHash())
            .append("\nruntime-binding-manifest-version=").append(values.runtimeBindingManifestVersion())
            .append("\nruntime-binding-manifest-hash=").append(values.runtimeBindingManifestHash())
            .append("\nparticipant-readiness-version=").append(values.participantReadinessVersion())
            .append("\nparticipant-readiness-hash=").append(values.participantReadinessHash())
            .append("\nparticipant-readiness-complete=true\nverification=offline-upgrader\n");
        if (values.authorityBundle() != null) {
            ProductionAuthorityBundle authority = values.authorityBundle();
            content.append("authority-server-id=").append(authority.serverId().canonicalText()).append('\n')
                .append("authority-snapshot-id=").append(authority.snapshotId().canonicalText()).append('\n')
                .append("authority-timestamp=").append(authority.timestamp()).append('\n')
                .append("authority-catalog-content-checksum=").append(authority.catalogContentChecksum().canonicalText()).append('\n')
                .append("authority-catalog-generation=").append(authority.catalogGeneration()).append('\n')
                .append("authority-catalog-contract-generation=").append(authority.catalogContractVersion().generation()).append('\n')
                .append("authority-catalog-contract-minor=").append(authority.catalogContractVersion().minor()).append('\n')
                .append("authority-runtime-binding-manifest-hash=").append(authority.runtimeBindingManifestHash().canonicalText()).append('\n')
                .append("authority-runtime-binding-manifest-version=").append(authority.runtimeBindingManifestVersion()).append('\n')
                .append("authority-readiness-report-hash=").append(authority.readinessReportHash()).append('\n')
                .append("authority-readiness-report-version=").append(authority.readinessReportVersion()).append('\n')
                .append("authority-install-authority-hash=").append(authority.installAuthorityHash()).append('\n')
                .append("authority-signing-public-key=").append(authority.signingPublicKey()).append('\n')
                .append("authority-signature=").append(authority.signature()).append('\n')
                .append("authority-bundle-hash=").append(authority.bundleHash()).append('\n');
            if (values.authorityUseGrant() != null) {
                content.append("authority-installation-id=").append(MigrationCanonical.encode(authority.installationId())).append('\n')
                    .append("authority-key-id=").append(MigrationCanonical.encode(authority.authorityKeyId())).append('\n')
                    .append("authority-public-key-fingerprint=").append(authority.publicKeyFingerprint()).append('\n');
                content.append("authority-trust-anchor-hash=").append(values.authorityTrustAnchorHash()).append('\n')
                    .append("authority-use-grant=").append(MigrationCanonical.encode(values.authorityUseGrant().canonical())).append('\n');
            }
        }
        return content.toString();
    }

    private static ProductionAuthorityBundle authority(List<String> lines, int index, boolean grantFormat) throws MigrationException {
        try {
            ServerId serverId = ServerId.parseCanonicalText(value(lines, index++, "authority-server-id="));
            SnapshotId snapshotId = SnapshotId.parseCanonicalText(value(lines, index++, "authority-snapshot-id="));
            String timestampText = value(lines, index++, "authority-timestamp=");
            Instant timestamp = Instant.parse(timestampText);
            if (!timestamp.toString().equals(timestampText)) {
                throw new MigrationException("Activation Marker Authority Timestamp Is Not Canonical");
            }
            ContentHash catalogChecksum = ContentHash.parseCanonicalText(value(lines, index++, "authority-catalog-content-checksum="));
            long catalogGeneration = Long.parseLong(value(lines, index++, "authority-catalog-generation="));
            int contractGeneration = Integer.parseInt(value(lines, index++, "authority-catalog-contract-generation="));
            int contractMinor = Integer.parseInt(value(lines, index++, "authority-catalog-contract-minor="));
            ContentHash runtimeHash = ContentHash.parseCanonicalText(value(lines, index++, "authority-runtime-binding-manifest-hash="));
            int runtimeVersion = Integer.parseInt(value(lines, index++, "authority-runtime-binding-manifest-version="));
            String readinessHash = MigrationCanonical.requireDigest(value(lines, index++, "authority-readiness-report-hash="), "authorityReadinessReportHash");
            int readinessVersion = Integer.parseInt(value(lines, index++, "authority-readiness-report-version="));
            String installAuthorityHash = MigrationCanonical.requireDigest(value(lines, index++, "authority-install-authority-hash="), "installAuthorityHash");
            String signingPublicKey = value(lines, index++, "authority-signing-public-key=");
            String signature = value(lines, index++, "authority-signature=");
            String bundleHash = MigrationCanonical.requireDigest(value(lines, index++, "authority-bundle-hash="), "authorityBundleHash");
            String installationId = serverId.canonicalText();
            String authorityKeyId = ProductionAuthorityTrustAnchor.fingerprint(signingPublicKey);
            String publicKeyFingerprint = authorityKeyId;
            if (grantFormat) {
                installationId = MigrationCanonical.decode(value(lines, index++, "authority-installation-id="));
                authorityKeyId = MigrationCanonical.decode(value(lines, index++, "authority-key-id="));
                publicKeyFingerprint = MigrationCanonical.requireDigest(value(lines, index++, "authority-public-key-fingerprint="),
                    "authorityPublicKeyFingerprint");
            }
            ProductionAuthorityBundle expected = ProductionAuthorityBundle.create(serverId, installationId, authorityKeyId,
                publicKeyFingerprint, snapshotId, timestamp, catalogChecksum, catalogGeneration,
                new CatalogVersion(contractGeneration, contractMinor), runtimeHash, runtimeVersion, readinessHash,
                readinessVersion, installAuthorityHash, signingPublicKey, signature);
            if (!expected.bundleHash().equals(bundleHash)) {
                throw new MigrationException("Activation Marker Authority Bundle Hash Does Not Match");
            }
            return expected;
        } catch (MigrationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MigrationException("Activation Marker Authority Bundle Is Invalid", exception);
        }
    }

    private static String value(List<String> lines, int index, String prefix) throws MigrationException {
        if (index >= lines.size() || !lines.get(index).startsWith(prefix)) {
            throw new MigrationException("Missing Activation Marker Field: " + prefix);
        }
        return lines.get(index).substring(prefix.length());
    }

    private static int integer(List<String> lines, int index, String prefix) throws MigrationException {
        try {
            return Integer.parseInt(value(lines, index, prefix));
        } catch (NumberFormatException exception) {
            throw new MigrationException("Invalid Activation Marker Number: " + prefix, exception);
        }
    }

    private static String decodeUtf8(byte[] bytes) throws MigrationException {
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString();
            if (!java.util.Arrays.equals(value.getBytes(StandardCharsets.UTF_8), bytes)) {
                throw new MigrationException("Activation Marker Encoding Is Not Canonical");
            }
            return value;
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw new MigrationException("Activation Marker Encoding Is Invalid", exception);
        }
    }
}

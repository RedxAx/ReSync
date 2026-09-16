package restudio.resync.migration;

import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.SnapshotId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class AuthorityBundleConsumptionLedger {
    public static final int FORMAT_VERSION = 3;
    public static final String DIRECTORY = "authority-consumption";
    private static final String FILE_SUFFIX = ".ledger";
    private static final int BODY_LINE_COUNT = 17;
    private static final int MAX_BYTES = 256 * 1024;

    private AuthorityBundleConsumptionLedger() {
    }

    public static Reservation reserve(Binding binding) throws IOException {
        Binding checked = Objects.requireNonNull(binding, "binding");
        checked.requireSourceBinding();
        Path target = path(checked.sourceRoot(), checked.grant().grantHash());
        byte[] reserved = canonical(State.RESERVED, checked);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return verifyExisting(target, checked);
        }
        try {
            AtomicFiles.writeNew(target, reserved);
            return new Reservation(State.RESERVED, false);
        } catch (FileAlreadyExistsException exception) {
            return verifyExisting(target, checked);
        }
    }

    public static void commit(Binding binding) throws IOException {
        Binding checked = Objects.requireNonNull(binding, "binding");
        checked.requireSourceBinding();
        Path target = path(checked.sourceRoot(), checked.grant().grantHash());
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Authority Use Grant Consumption Ledger Is Missing");
        }
        Reservation reservation = verifyExisting(target, checked);
        if (reservation.state() == State.COMMITTED) {
            return;
        }
        AtomicFiles.write(target, canonical(State.COMMITTED, checked));
    }

    public static Path path(Path sourceRoot, String grantHash) throws IOException {
        Path source = canonicalSourcePath(sourceRoot);
        Path parent = source.getParent();
        if (parent == null) {
            throw new MigrationException("Source Root Has No Durable Authority Parent");
        }
        Path directory = authorityDirectory(parent);
        String normalizedHash = MigrationCanonical.requireDigest(grantHash, "grantHash");
        return MigrationPaths.resolveInside(directory, normalizedHash + FILE_SUFFIX);
    }

    public static String sourceIdentityDigest(Path sourceRoot, ProductionAuthorityBundle bundle) throws IOException {
        return AuthorityUseGrant.sourceIdentityDigest(sourceRoot, Objects.requireNonNull(bundle, "bundle"));
    }

    private static Reservation verifyExisting(Path target, Binding binding) throws IOException {
        if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Authority Use Grant Consumption Ledger Is Invalid");
        }
        if (Files.size(target) > MAX_BYTES) {
            throw new MigrationException("Authority Use Grant Consumption Ledger Is Too Large");
        }
        String content = Files.readString(target, StandardCharsets.UTF_8);
        Values values;
        try {
            values = parse(content);
        } catch (MigrationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MigrationException("Authority Use Grant Consumption Ledger Is Invalid", exception);
        }
        if (!values.matches(binding)) {
            throw new MigrationException("Authority Use Grant Consumption Ledger Is Bound To A Different Signed Grant");
        }
        return new Reservation(values.state(), true);
    }

    private static byte[] canonical(State state, Binding binding) {
        return body(state, binding).getBytes(StandardCharsets.UTF_8);
    }

    private static String body(State state, Binding binding) {
        AuthorityUseGrant grant = binding.grant();
        ProductionAuthorityBundle bundle = binding.bundle();
        return new StringBuilder()
            .append("format=").append(FORMAT_VERSION).append('\n')
            .append("state=").append(state).append('\n')
            .append("grant-hash=").append(grant.grantHash()).append('\n')
            .append("grant-canonical=").append(MigrationCanonical.encode(grant.canonical())).append('\n')
            .append("bundle-hash=").append(bundle.bundleHash()).append('\n')
            .append("server-id=").append(bundle.serverId().canonicalText()).append('\n')
            .append("installation-id=").append(MigrationCanonical.encode(bundle.installationId())).append('\n')
            .append("install-authority-hash=").append(bundle.installAuthorityHash()).append('\n')
            .append("snapshot-id=").append(bundle.snapshotId().canonicalText()).append('\n')
            .append("canonical-source-path=").append(MigrationCanonical.encode(grant.canonicalSourcePath())).append('\n')
            .append("source-identity-hash=").append(grant.sourceIdentityHash()).append('\n')
            .append("migration-id=").append(MigrationCanonical.encode(grant.migrationId())).append('\n')
            .append("invocation-hash=").append(grant.invocationHash()).append('\n')
            .append("plan-preimage-hash=").append(grant.planPreimageHash()).append('\n')
            .append("issued-at=").append(grant.issuedAt()).append('\n')
            .append("expires-at=").append(grant.expiresAt()).append('\n')
            .append("grant-id=").append(MigrationCanonical.encode(grant.grantId())).append('\n')
            .toString();
    }

    private static Values parse(String content) throws IOException {
        if (content == null || content.indexOf('\r') >= 0 || !content.endsWith("\n")) {
            throw new MigrationException("Authority Use Grant Consumption Ledger Encoding Is Invalid");
        }
        List<String> lines = Arrays.asList(content.split("\n", -1));
        if (lines.size() != BODY_LINE_COUNT + 1 || !lines.getLast().isEmpty()) {
            throw new MigrationException("Authority Use Grant Consumption Ledger Field Count Is Invalid");
        }
        int format = integer(lines.get(0), "format=");
        if (format != FORMAT_VERSION) {
            throw new MigrationException("Authority Use Grant Consumption Ledger Format Is Unsupported");
        }
        State state;
        try {
            state = State.valueOf(raw(lines.get(1), "state="));
        } catch (IllegalArgumentException exception) {
            throw new MigrationException("Authority Use Grant Consumption Ledger State Is Invalid", exception);
        }
        String grantHash = MigrationCanonical.requireDigest(raw(lines.get(2), "grant-hash="), "grantHash");
        AuthorityUseGrant grant;
        try {
            grant = AuthorityUseGrant.fromCanonical(MigrationCanonical.decode(raw(lines.get(3), "grant-canonical=")));
        } catch (RuntimeException exception) {
            throw new MigrationException("Authority Use Grant Consumption Ledger Grant Is Invalid", exception);
        }
        if (!grantHash.equals(grant.grantHash())) {
            throw new MigrationException("Authority Use Grant Consumption Ledger Grant Hash Does Not Match");
        }
        String bundleHash = MigrationCanonical.requireDigest(raw(lines.get(4), "bundle-hash="), "bundleHash");
        String serverId = ServerId.parseCanonicalText(raw(lines.get(5), "server-id=")).canonicalText();
        if (!serverId.equals(grant.serverId().canonicalText())) {
            throw new MigrationException("Authority Use Grant Consumption Ledger Server Identity Does Not Match");
        }
        String installationId = MigrationCanonical.decode(raw(lines.get(6), "installation-id="));
        String installAuthorityHash = MigrationCanonical.requireDigest(raw(lines.get(7), "install-authority-hash="), "installAuthorityHash");
        String snapshotId = SnapshotId.parseCanonicalText(raw(lines.get(8), "snapshot-id=")).canonicalText();
        String canonicalSourcePath = MigrationCanonical.decode(raw(lines.get(9), "canonical-source-path="));
        String sourceIdentityHash = MigrationCanonical.requireDigest(raw(lines.get(10), "source-identity-hash="), "sourceIdentityHash");
        String migrationId = MigrationCanonical.decode(raw(lines.get(11), "migration-id="));
        String invocationHash = MigrationCanonical.requireDigest(raw(lines.get(12), "invocation-hash="), "invocationHash");
        String planPreimageHash = MigrationCanonical.requireDigest(raw(lines.get(13), "plan-preimage-hash="), "planPreimageHash");
        Instant issuedAt = instant(raw(lines.get(14), "issued-at="), "issuedAt");
        Instant expiresAt = instant(raw(lines.get(15), "expires-at="), "expiresAt");
        String grantId = MigrationCanonical.decode(raw(lines.get(16), "grant-id="));
        return new Values(state, grant, bundleHash, serverId, installationId, installAuthorityHash, snapshotId,
            canonicalSourcePath, sourceIdentityHash, migrationId, invocationHash, planPreimageHash, issuedAt,
            expiresAt, grantId);
    }

    private static Instant instant(String value, String field) throws MigrationException {
        try {
            Instant parsed = Instant.parse(value);
            if (!parsed.toString().equals(value)) {
                throw new MigrationException("Authority Use Grant Consumption Ledger Instant Is Not Canonical: " + field);
            }
            return parsed;
        } catch (MigrationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MigrationException("Authority Use Grant Consumption Ledger Instant Is Invalid: " + field, exception);
        }
    }

    private static Path authorityDirectory(Path parent) throws IOException {
        Path directory = MigrationPaths.resolveInside(parent, DIRECTORY);
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
            && (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))) {
            throw new MigrationException("Authority Use Grant Consumption Directory Is Invalid");
        }
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Authority Use Grant Consumption Directory Is Invalid");
        }
        return directory;
    }

    private static Path canonicalSourcePath(Path path) throws IOException {
        Path normalized = MigrationPaths.requirePath(path, "sourceRoot");
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return MigrationPaths.requireDirectory(normalized, "sourceRoot").toRealPath();
        }
        Path absolute = normalized.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Source Root Parent Is Missing");
        }
        return absolute;
    }

    private static String raw(String value, String prefix) throws MigrationException {
        if (!value.startsWith(prefix)) {
            throw new MigrationException("Authority Use Grant Consumption Ledger Field Is Missing: " + prefix);
        }
        return value.substring(prefix.length());
    }

    private static int integer(String value, String prefix) throws MigrationException {
        try {
            return Integer.parseInt(raw(value, prefix));
        } catch (NumberFormatException exception) {
            throw new MigrationException("Authority Use Grant Consumption Ledger Number Is Invalid", exception);
        }
    }

    public enum State {
        RESERVED,
        COMMITTED
    }

    public record Reservation(State state, boolean existing) {
        public Reservation {
            state = Objects.requireNonNull(state, "state");
        }
    }

    public record Binding(Path sourceRoot, ProductionAuthorityBundle bundle, AuthorityUseGrant grant) {
        public Binding {
            sourceRoot = Objects.requireNonNull(sourceRoot, "sourceRoot").toAbsolutePath().normalize();
            bundle = Objects.requireNonNull(bundle, "bundle");
            grant = Objects.requireNonNull(grant, "grant");
            if (!grant.bundleHash().equals(bundle.bundleHash())
                || !grant.serverId().equals(bundle.serverId())
                || !grant.installationId().equals(bundle.installationId())
                || !grant.installAuthorityHash().equals(bundle.installAuthorityHash())
                || !grant.snapshotId().equals(bundle.snapshotId())
                || !grant.authorityKeyId().equals(bundle.authorityKeyId())
                || !grant.publicKeyFingerprint().equals(bundle.publicKeyFingerprint())) {
                throw new IllegalArgumentException("Authority Use Grant Does Not Match Authority Bundle");
            }
        }

        private void requireSourceBinding() throws IOException {
            String canonical = canonicalSourcePath(sourceRoot).toString();
            if (!canonical.equals(grant.canonicalSourcePath())) {
                throw new MigrationException("Authority Use Grant Consumption Source Path Does Not Match");
            }
            if (Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS)) {
                String expected = AuthorityUseGrant.sourceIdentityDigest(sourceRoot, bundle);
                if (!expected.equals(grant.sourceIdentityHash())) {
                    throw new MigrationException("Authority Use Grant Consumption Source Identity Does Not Match");
                }
            }
        }
    }

    private record Values(
        State state,
        AuthorityUseGrant grant,
        String bundleHash,
        String serverId,
        String installationId,
        String installAuthorityHash,
        String snapshotId,
        String canonicalSourcePath,
        String sourceIdentityHash,
        String migrationId,
        String invocationHash,
        String planPreimageHash,
        Instant issuedAt,
        Instant expiresAt,
        String grantId
    ) {
        private boolean matches(Binding binding) {
            AuthorityUseGrant expected = binding.grant();
            ProductionAuthorityBundle bundle = binding.bundle();
            return grant.equals(expected)
                && bundleHash.equals(bundle.bundleHash())
                && serverId.equals(bundle.serverId().canonicalText())
                && installationId.equals(bundle.installationId())
                && installAuthorityHash.equals(bundle.installAuthorityHash())
                && snapshotId.equals(bundle.snapshotId().canonicalText())
                && canonicalSourcePath.equals(expected.canonicalSourcePath())
                && sourceIdentityHash.equals(expected.sourceIdentityHash())
                && migrationId.equals(expected.migrationId())
                && invocationHash.equals(expected.invocationHash())
                && planPreimageHash.equals(expected.planPreimageHash())
                && issuedAt.equals(expected.issuedAt())
                && expiresAt.equals(expected.expiresAt())
                && grantId.equals(expected.grantId());
        }
    }
}

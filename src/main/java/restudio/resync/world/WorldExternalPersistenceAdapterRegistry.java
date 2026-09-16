package restudio.resync.world;

import restudio.resync.migration.MigrationPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

public final class WorldExternalPersistenceAdapterRegistry {
    private static final Object ISSUER = new Object();
    private static final String AUTHORITY_DIRECTORY = ".resync-external-persistence";
    private static final String MANIFEST_FILE = "probe-manifest";
    private static volatile String crashCut;

    private WorldExternalPersistenceAdapterRegistry() {
    }

    static Object issuer() {
        return ISSUER;
    }

    static void crashAfter(String phase) {
        crashCut = phase;
    }

    public static WorldExternalPersistenceAdapterRegistration register(WorldExternalPersistenceAdapter adapter,
                                                                        Collection<WorldExternalPersistenceCapability.WorldRoot> roots)
        throws IOException {
        Objects.requireNonNull(adapter, "adapter");
        String adapterId = requireText(adapter.id(), "adapterId");
        List<WorldExternalPersistenceCapability.WorldRoot> candidateRoots = validateRoots(roots);
        WorldExternalPersistenceCapability.WorldRoot root = candidateRoots.getFirst();
        Path authority = root.root().getParent().resolve(AUTHORITY_DIRECTORY).toAbsolutePath().normalize();
        Files.createDirectories(authority);
        MigrationPaths.requireNoSymlinkTree(authority);
        boolean recoveredPending = recoverPending(adapter, candidateRoots, authority);
        Path attempt = authority.resolve("attempt-" + UUID.randomUUID()).toAbsolutePath().normalize();
        Files.createDirectories(attempt);
        Path manifest = attempt.resolve(MANIFEST_FILE).toAbsolutePath().normalize();
        Path probeParent = attempt.resolve("probe-roots").toAbsolutePath().normalize();
        Path changedParent = attempt.resolve("changed-roots").toAbsolutePath().normalize();
        Path destination = attempt.resolve("snapshot-" + root.name()).toAbsolutePath().normalize();
        Map<String, CoordinatorReceipt> receipts = new LinkedHashMap<>();
        Set<String> rolledBack = new LinkedHashSet<>();
        Set<String> verified = new LinkedHashSet<>();
        writeHeader(manifest, adapterId, attempt, candidateRoots);
        if (recoveredPending) {
            appendStatus(manifest, "RECOVERED");
        }
        try {
            execute(adapter, () -> {
                adapter.healthCheck(candidateRoots);
                return null;
            });
            List<WorldExternalPersistenceCapability.WorldRoot> probeRoots = createProbeRoots(probeParent, candidateRoots);
            WorldExternalPersistenceCapability.WorldRoot probeRoot = probeRoots.getFirst();
            ProbeRun saveProbe = prepare("save", probeRoots, null);
            executeReceipt(adapter, "save", saveProbe, manifest, receipts,
                () -> adapter.save(probeRoots, saveProbe.context()));
            verified.add("save");
            cut("save");
            ProbeRun quiesceProbe = prepare("quiesce", probeRoots, null);
            executeReceipt(adapter, "quiesce", quiesceProbe, manifest, receipts,
                () -> adapter.quiesce(probeRoots, quiesceProbe.context()));
            verified.add("quiesce");
            cut("quiesce");
            ProbeRun snapshotProbe = prepare("snapshot", List.of(probeRoot), destination);
            WorldExternalPersistenceAdapter.SnapshotArtifact artifact = executeSnapshot(adapter, snapshotProbe, manifest, receipts,
                rolledBack, probeRoot, destination);
            verified.add("snapshot");
            requireArtifact(artifact, destination);
            cut("snapshot");
            WorldExternalPersistenceCapability.ExternalWorldSnapshot probeSnapshot =
                new WorldExternalPersistenceCapability.ExternalWorldSnapshot(artifact.snapshotId(), "probe-capability", adapterId,
                    probeRoot.name(), probeRoot.root(), destination, 0L, "probe", "probe", "probe", "probe", false,
                    artifact.transactionToken());
            ProbeRun restoreProbe = prepare("restore", List.of(probeRoot), null);
            executeReceipt(adapter, "restore", restoreProbe, manifest, receipts,
                () -> adapter.restore(probeRoot, probeSnapshot, restoreProbe.context()));
            verified.add("restore");
            cut("restore");
            Map<String, WorldExternalPersistenceCapability.WorldRoot> probeBinding = rootsByName(probeRoots);
            executeRebind(adapter, "rebind", probeBinding, manifest, receipts);
            verified.add("rebind");
            cut("rebind");
            List<WorldExternalPersistenceCapability.WorldRoot> changedRoots = createProbeRoots(changedParent, candidateRoots);
            Map<String, WorldExternalPersistenceCapability.WorldRoot> changedBinding = rootsByName(changedRoots);
            executeRebind(adapter, "rebind.changed", changedBinding, manifest, receipts);
            cut("rebind.changed");
            execute(adapter, () -> {
                adapter.healthCheck(changedRoots);
                return null;
            });
            ProbeRun resumeProbe = prepare("resume", changedRoots, null);
            executeReceipt(adapter, "resume", resumeProbe, manifest, receipts,
                () -> adapter.resume(changedRoots, resumeProbe.context()));
            execute(adapter, () -> {
                adapter.healthCheck(changedRoots);
                return null;
            });
            verified.add("resume");
            cut("resume");
            if (!verified.equals(Set.of("save", "quiesce", "snapshot", "restore", "rebind", "resume"))) {
                throw new IOException("External World Adapter Behavioral Probe Did Not Cover Every Operation");
            }
            verifyUniqueTokens(receipts.values());
            verifyManifest(manifest, receipts.values());
            IOException rollbackFailure = rollback(adapter, receipts.values(), manifest, rolledBack);
            if (rollbackFailure != null) {
                throw new IOException("External World Adapter Behavioral Rollback Verification Failed", rollbackFailure);
            }
            execute(adapter, () -> {
                adapter.healthCheck(candidateRoots);
                return null;
            });
            appendStatus(manifest, "ROLLED_BACK");
            appendStatus(manifest, "COMMITTED");
            WorldExternalPersistenceAdapterRegistration registration = new WorldExternalPersistenceAdapterRegistration(UUID.randomUUID().toString(), adapterId,
                UUID.randomUUID().toString(), Set.of("save", "quiesce", "resume", "snapshot", "restore", "rebind"), adapter,
                hashFile(manifest), manifest, ISSUER);
            return registration;
        } catch (CrashCutException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            IOException rollbackFailure = rollback(adapter, receipts.values(), manifest, rolledBack);
            ManifestState manifestState = readManifest(manifest);
            if (rollbackFailure != null || manifestState.begins().size() != manifestState.receipts().size()) {
                if (rollbackFailure != null) {
                    exception.addSuppressed(rollbackFailure);
                }
                appendStatus(manifest, "RECOVERY_FAILED");
            } else {
                appendStatus(manifest, "ROLLED_BACK");
            }
            if (exception instanceof IOException ioException) {
                throw ioException;
            }
            throw exception;
        }
    }

    private static boolean recoverPending(WorldExternalPersistenceAdapter adapter,
                                          Collection<WorldExternalPersistenceCapability.WorldRoot> roots,
                                          Path authority) throws IOException {
        if (!Files.isDirectory(authority, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        boolean recovered = false;
        try (Stream<Path> attempts = Files.list(authority)) {
            for (Path attempt : attempts.sorted().toList()) {
                if (!Files.isDirectory(attempt, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                Path manifest = attempt.resolve(MANIFEST_FILE);
                if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                ManifestState state = readManifest(manifest);
                if ("COMMITTED".equals(state.status()) || "RECOVERED".equals(state.status())) {
                    continue;
                }
                if (!adapter.id().equals(state.adapterId())) {
                    throw new IOException("Pending External Adapter Probe Belongs To A Different Adapter");
                }
                IOException failure = rollback(adapter, state.receipts(), manifest, state.rolledBack());
                if (failure != null || state.begins().size() != state.receipts().size()) {
                    appendStatus(manifest, "RECOVERY_FAILED");
                    throw new IOException("External Adapter Probe Recovery Failed", failure);
                }
                appendStatus(manifest, "RECOVERED");
                recovered = true;
                execute(adapter, () -> {
                    adapter.healthCheck(roots);
                    return null;
                });
            }
        }
        return recovered;
    }

    private static List<WorldExternalPersistenceCapability.WorldRoot> createProbeRoots(
        Path parent, Collection<WorldExternalPersistenceCapability.WorldRoot> candidates) throws IOException {
        Files.createDirectories(parent);
        List<WorldExternalPersistenceCapability.WorldRoot> roots = new ArrayList<>();
        for (WorldExternalPersistenceCapability.WorldRoot candidate : candidates) {
            Path path = parent.resolve(candidate.name()).toAbsolutePath().normalize();
            Files.createDirectories(path);
            Path marker = path.resolve("coordinator-marker");
            Files.writeString(marker, "before:" + candidate.name(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            roots.add(new WorldExternalPersistenceCapability.WorldRoot(candidate.name(), path));
        }
        MigrationPaths.requireNoSymlinkTree(parent);
        return List.copyOf(roots);
    }

    private static Map<String, WorldExternalPersistenceCapability.WorldRoot> rootsByName(
        Collection<WorldExternalPersistenceCapability.WorldRoot> roots) {
        Map<String, WorldExternalPersistenceCapability.WorldRoot> output = new LinkedHashMap<>();
        for (WorldExternalPersistenceCapability.WorldRoot root : roots) {
            output.put(root.name(), root);
        }
        return Map.copyOf(output);
    }

    private static ProbeRun prepare(String operation,
                                    Collection<WorldExternalPersistenceCapability.WorldRoot> roots,
                                    Path artifact) throws IOException {
        Map<String, Path> markers = new LinkedHashMap<>();
        for (WorldExternalPersistenceCapability.WorldRoot root : roots) {
            Path marker = root.root().resolve("coordinator-marker").toAbsolutePath().normalize();
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Coordinator Probe Marker Is Missing: " + marker);
            }
            markers.put(root.name(), marker);
        }
        WorldExternalPersistenceAdapter.BehavioralProbe context =
            new WorldExternalPersistenceAdapter.BehavioralProbe(operation, markers, artifact);
        return new ProbeRun(operation, roots.stream().toList(), artifact, beginEvidence(operation, roots, artifact), context);
    }

    private static ProbeEvidence beginEvidence(String operation,
                                                Collection<WorldExternalPersistenceCapability.WorldRoot> roots,
                                                Path artifact) throws IOException {
        List<RootEvidence> states = new ArrayList<>();
        for (WorldExternalPersistenceCapability.WorldRoot root : roots) {
            Path marker = root.root().resolve("coordinator-marker").toAbsolutePath().normalize();
            states.add(new RootEvidence(root.name(), root.root(), marker, hashTree(root.root()), hashFile(marker), "", ""));
        }
        boolean artifactExists = artifact != null && Files.exists(artifact, LinkOption.NOFOLLOW_LINKS);
        String artifactHash = artifactExists && Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS) ? hashFile(artifact) : "";
        return new ProbeEvidence(operation, List.copyOf(states), artifact, artifactExists, artifactHash, "", false);
    }

    private static CoordinatorReceipt executeReceipt(WorldExternalPersistenceAdapter adapter,
                                                      String expectedOperation,
                                                      ProbeRun run,
                                                      Path manifest,
                                                      Map<String, CoordinatorReceipt> receipts,
                                                      ReceiptCall call) throws IOException {
        appendBegin(manifest, run);
        try {
            return trackReceipt(manifest, run, execute(adapter, call::call), expectedOperation, receipts);
        } catch (WorldExternalPersistenceAdapter.TransactionFailure failure) {
            if (failure.receipt() != null) {
                trackReceipt(manifest, run, failure.receipt(), expectedOperation, receipts);
            }
            throw failure;
        }
    }

    private static WorldExternalPersistenceAdapter.SnapshotArtifact executeSnapshot(
        WorldExternalPersistenceAdapter adapter,
        ProbeRun run,
        Path manifest,
        Map<String, CoordinatorReceipt> receipts,
        Set<String> rolledBack,
        WorldExternalPersistenceCapability.WorldRoot root,
        Path destination) throws IOException {
        appendBegin(manifest, run);
        try {
            WorldExternalPersistenceAdapter.SnapshotArtifact artifact = execute(adapter,
                () -> adapter.snapshot(root, destination, 0L, run.context()));
            if (artifact == null) {
                throw new IOException("External World Adapter Snapshot Probe Returned No Artifact");
            }
            trackReceipt(manifest, run,
                new WorldExternalPersistenceAdapter.TransactionReceipt("snapshot", artifact.transactionToken()), "snapshot", receipts);
            return artifact;
        } catch (WorldExternalPersistenceAdapter.TransactionFailure failure) {
            compensateSnapshotFailure(adapter, run, manifest, receipts, rolledBack, failure);
            throw failure;
        }
    }

    private static void compensateSnapshotFailure(WorldExternalPersistenceAdapter adapter,
                                                  ProbeRun run,
                                                  Path manifest,
                                                  Map<String, CoordinatorReceipt> receipts,
                                                  Set<String> rolledBack,
                                                  WorldExternalPersistenceAdapter.TransactionFailure failure) {
        String token = failure.receipt().token();
        CoordinatorReceipt tracked = receipts.get(token);
        if (tracked == null) {
            try {
                trackReceipt(manifest, run, failure.receipt(), "snapshot", receipts);
            } catch (IOException trackingFailure) {
                failure.addSuppressed(trackingFailure);
            }
            tracked = receipts.get(token);
        }
        if (tracked == null) {
            return;
        }
        IOException rollbackFailure = rollbackOne(adapter, tracked);
        if (rollbackFailure != null) {
            failure.addSuppressed(rollbackFailure);
            return;
        }
        try {
            appendLine(manifest, "rollback-ok\t" + encode(token));
            rolledBack.add(token);
        } catch (IOException appendFailure) {
            failure.addSuppressed(appendFailure);
        }
    }

    private static CoordinatorReceipt executeRebind(WorldExternalPersistenceAdapter adapter,
                                                     String operation,
                                                     Map<String, WorldExternalPersistenceCapability.WorldRoot> roots,
                                                     Path manifest,
                                                     Map<String, CoordinatorReceipt> receipts) throws IOException {
        List<WorldExternalPersistenceCapability.WorldRoot> values = List.copyOf(roots.values());
        ProbeRun run = prepare(operation, values, null);
        return executeReceipt(adapter, "rebind", run, manifest, receipts,
            () -> adapter.rebind(roots, run.context()));
    }

    private static CoordinatorReceipt trackReceipt(Path manifest,
                                                   ProbeRun run,
                                                   WorldExternalPersistenceAdapter.TransactionReceipt receipt,
                                                   String expectedOperation,
                                                   Map<String, CoordinatorReceipt> receipts) throws IOException {
        if (receipt == null || receipt.token().isBlank()) {
            throw new IOException("External World Adapter Did Not Return A Recoverable " + expectedOperation + " Transaction");
        }
        if (receipts.containsKey(receipt.token())) {
            throw new IOException("External World Adapter Reused A Transaction Token During Probe");
        }
        ProbeEvidence observed;
        try {
            observed = observe(run.before());
        } catch (IOException exception) {
            CoordinatorReceipt tracked = new CoordinatorReceipt(run.operation(), receipt,
                run.before().withTransitionValid(false));
            appendReceipt(manifest, tracked);
            receipts.put(receipt.token(), tracked);
            throw exception;
        }
        boolean valid = observed.transitionValid() && expectedOperation.equals(receipt.operation());
        ProbeEvidence evidence = observed.withTransitionValid(valid);
        CoordinatorReceipt tracked = new CoordinatorReceipt(run.operation(), receipt, evidence);
        appendReceipt(manifest, tracked);
        receipts.put(receipt.token(), tracked);
        if (!valid) {
            throw new IOException("External World Adapter Failed Coordinator Behavioral Probe For " + run.operation());
        }
        return tracked;
    }

    private static ProbeEvidence observe(ProbeEvidence before) throws IOException {
        List<RootEvidence> states = new ArrayList<>();
        boolean changed = true;
        for (RootEvidence state : before.roots()) {
            String treeHash = hashTree(state.root());
            String markerHash = hashFile(state.marker());
            changed &= !state.beforeTreeHash().equals(treeHash) && !state.beforeMarkerHash().equals(markerHash);
            states.add(state.withAfter(treeHash, markerHash));
        }
        String artifactAfterHash = "";
        if (before.artifact() != null) {
            if (!Files.isRegularFile(before.artifact(), LinkOption.NOFOLLOW_LINKS)) {
                changed = false;
            } else {
                artifactAfterHash = hashFile(before.artifact());
                changed &= !before.artifactBeforeExists() || !before.artifactBeforeHash().equals(artifactAfterHash);
            }
        }
        return new ProbeEvidence(before.operation(), List.copyOf(states), before.artifact(), before.artifactBeforeExists(),
            before.artifactBeforeHash(), artifactAfterHash, changed);
    }

    private static IOException rollback(WorldExternalPersistenceAdapter adapter,
                                        Collection<CoordinatorReceipt> receipts,
                                        Path manifest) {
        return rollback(adapter, receipts, manifest, Set.of());
    }

    private static IOException rollback(WorldExternalPersistenceAdapter adapter,
                                        Collection<CoordinatorReceipt> receipts,
                                        Path manifest,
                                        Set<String> rolledBack) {
        IOException failure = null;
        for (CoordinatorReceipt receipt : receipts.stream().toList().reversed()) {
            if (rolledBack.contains(receipt.adapterReceipt().token())) {
                continue;
            }
            IOException exception = rollbackOne(adapter, receipt);
            if (exception == null) {
                try {
                    appendLine(manifest, "rollback-ok\t" + encode(receipt.adapterReceipt().token()));
                } catch (IOException appendFailure) {
                    exception = appendFailure;
                }
            }
            if (exception != null) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        return failure;
    }

    private static IOException rollbackOne(WorldExternalPersistenceAdapter adapter, CoordinatorReceipt receipt) {
        try {
            execute(adapter, () -> {
                adapter.rollback(receipt.adapterReceipt());
                return null;
            });
            verifyRollback(receipt.evidence());
            return null;
        } catch (IOException exception) {
            return exception;
        } catch (RuntimeException exception) {
            return new IOException("External World Adapter Rollback Failed", exception);
        }
    }

    private static void verifyRollback(ProbeEvidence evidence) throws IOException {
        for (RootEvidence state : evidence.roots()) {
            String currentTreeHash = hashTree(state.root());
            String currentMarkerHash = hashFile(state.marker());
            if (!state.beforeTreeHash().equals(currentTreeHash) || !state.beforeMarkerHash().equals(currentMarkerHash)) {
                throw new IOException("External World Adapter Rollback Did Not Restore Coordinator Probe Tree For "
                    + evidence.operation() + ":" + state.name() + ":" + state.beforeTreeHash() + ":" + currentTreeHash
                    + ":" + state.beforeMarkerHash() + ":" + currentMarkerHash);
            }
        }
        if (evidence.artifact() != null) {
            boolean exists = Files.exists(evidence.artifact(), LinkOption.NOFOLLOW_LINKS);
            if (evidence.artifactBeforeExists() != exists) {
                throw new IOException("External World Adapter Rollback Did Not Restore Coordinator Probe Artifact");
            }
            if (exists && !evidence.artifactBeforeHash().equals(hashFile(evidence.artifact()))) {
                throw new IOException("External World Adapter Rollback Changed Coordinator Probe Artifact");
            }
        }
    }

    private static void requireArtifact(WorldExternalPersistenceAdapter.SnapshotArtifact artifact, Path destination) throws IOException {
        if (!destination.equals(MigrationPaths.requirePath(artifact.path(), "snapshotPath"))
            || !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("External World Adapter Snapshot Probe Did Not Create The Coordinator Artifact");
        }
    }

    private static void verifyUniqueTokens(Collection<CoordinatorReceipt> receipts) throws IOException {
        Set<String> tokens = new LinkedHashSet<>();
        for (CoordinatorReceipt receipt : receipts) {
            if (!tokens.add(receipt.adapterReceipt().token())) {
                throw new IOException("External World Adapter Reused A Transaction Token During Probe");
            }
        }
    }

    private static void verifyManifest(Path manifest, Collection<CoordinatorReceipt> receipts) throws IOException {
        ManifestState state = readManifest(manifest);
        if (state.adapterId().isBlank() || state.begins().size() != state.receipts().size()
            || state.receipts().size() < receipts.size()) {
            throw new IOException("External World Coordinator Manifest Is Incomplete");
        }
        for (CoordinatorReceipt receipt : receipts) {
            if (state.receipts().stream().noneMatch(candidate -> candidate.adapterReceipt().token().equals(receipt.adapterReceipt().token()))) {
                throw new IOException("External World Coordinator Manifest Is Missing " + receipt.operation() + " Evidence");
            }
        }
    }

    private static void writeHeader(Path manifest, String adapterId, Path attempt,
                                    Collection<WorldExternalPersistenceCapability.WorldRoot> roots) throws IOException {
        StringBuilder header = new StringBuilder();
        header.append("version=2\nstatus=ACTIVE\nadapterId=").append(encode(adapterId)).append("\nattempt=")
            .append(encode(attempt.toString())).append('\n');
        for (WorldExternalPersistenceCapability.WorldRoot root : roots) {
            header.append("root=").append(encode(root.name() + "\u0000" + root.root())).append('\n');
        }
        Files.writeString(manifest, header, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        force(manifest);
    }

    private static void appendReceipt(Path manifest, CoordinatorReceipt receipt) throws IOException {
        appendLine(manifest, "receipt\t" + encode(receipt.operation()) + "\t" + encode(receipt.adapterReceipt().operation())
            + "\t" + encode(receipt.adapterReceipt().token()) + "\t" + encode(serialize(receipt.evidence())));
    }

    private static void appendBegin(Path manifest, ProbeRun run) throws IOException {
        appendLine(manifest, "begin\t" + encode(run.operation()) + "\t" + encode(serialize(run.before())));
    }

    private static void appendStatus(Path manifest, String status) throws IOException {
        appendLine(manifest, "status=" + status);
    }

    private static void appendLine(Path manifest, String line) throws IOException {
        Files.writeString(manifest, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        force(manifest);
    }

    private static void force(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static String serialize(ProbeEvidence evidence) {
        List<String> fields = new ArrayList<>();
        fields.add(evidence.operation());
        fields.add(evidence.artifact() == null ? "" : evidence.artifact().toString());
        fields.add(Boolean.toString(evidence.artifactBeforeExists()));
        fields.add(evidence.artifactBeforeHash());
        fields.add(evidence.artifactAfterHash());
        fields.add(Boolean.toString(evidence.transitionValid()));
        fields.add(Integer.toString(evidence.roots().size()));
        for (RootEvidence root : evidence.roots()) {
            fields.add(root.name());
            fields.add(root.root().toString());
            fields.add(root.marker().toString());
            fields.add(root.beforeTreeHash());
            fields.add(root.beforeMarkerHash());
            fields.add(root.afterTreeHash());
            fields.add(root.afterMarkerHash());
        }
        return String.join("\n", fields);
    }

    private static ProbeEvidence deserialize(String value) throws IOException {
        try {
            String[] fields = value.split("\n", -1);
            if (fields.length < 7) {
                throw new IOException("External World Coordinator Probe Evidence Is Incomplete");
            }
            int index = 0;
            String operation = fields[index++];
            String artifactValue = fields[index++];
            Path artifact = artifactValue.isBlank() ? null : Path.of(artifactValue);
            boolean artifactBeforeExists = Boolean.parseBoolean(fields[index++]);
            String artifactBeforeHash = fields[index++];
            String artifactAfterHash = fields[index++];
            boolean transitionValid = Boolean.parseBoolean(fields[index++]);
            int rootCount = Integer.parseInt(fields[index++]);
            if (rootCount <= 0 || fields.length != 7 + rootCount * 7) {
                throw new IOException("External World Coordinator Probe Evidence Roots Are Invalid");
            }
            List<RootEvidence> roots = new ArrayList<>();
            for (int count = 0; count < rootCount; count++) {
                roots.add(new RootEvidence(fields[index++], Path.of(fields[index++]), Path.of(fields[index++]), fields[index++],
                    fields[index++], fields[index++], fields[index++]));
            }
            return new ProbeEvidence(operation, List.copyOf(roots), artifact, artifactBeforeExists, artifactBeforeHash,
                artifactAfterHash, transitionValid);
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("External World Coordinator Probe Evidence Is Invalid", exception);
        }
    }

    private static ManifestState readManifest(Path manifest) throws IOException {
        Path normalizedManifest;
        try {
            normalizedManifest = MigrationPaths.requirePath(manifest, "probeManifest");
        } catch (RuntimeException exception) {
            throw new IOException("External World Coordinator Manifest Path Is Invalid", exception);
        }
        if (!Files.isRegularFile(normalizedManifest, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("External World Coordinator Manifest Is Missing");
        }
        Path attempt = normalizedManifest.getParent();
        if (attempt == null || !Files.isDirectory(attempt, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("External World Coordinator Manifest Authority Is Invalid");
        }
        MigrationPaths.requireNoSymlinkTree(attempt);
        String adapterId = "";
        String status = "ACTIVE";
        List<String> begins = new ArrayList<>();
        List<CoordinatorReceipt> receipts = new ArrayList<>();
        Set<String> rolledBack = new LinkedHashSet<>();
        try {
            for (String line : Files.readAllLines(normalizedManifest, StandardCharsets.UTF_8)) {
                if (line.startsWith("adapterId=")) {
                    adapterId = decode(line.substring("adapterId=".length()));
                } else if (line.startsWith("status=")) {
                    status = line.substring("status=".length());
                } else if (line.startsWith("status\t")) {
                    String[] fields = line.split("\t", -1);
                    if (fields.length != 2) {
                        throw new IOException("External World Coordinator Manifest Status Is Invalid");
                    }
                    status = decode(fields[1]);
                } else if (line.startsWith("rollback-ok\t")) {
                    String[] fields = line.split("\t", -1);
                    if (fields.length != 2) {
                        throw new IOException("External World Coordinator Manifest Rollback Is Invalid");
                    }
                    rolledBack.add(decode(fields[1]));
                } else if (line.startsWith("begin\t")) {
                    String[] fields = line.split("\t", -1);
                    if (fields.length != 3) {
                        throw new IOException("External World Coordinator Manifest Begin Is Invalid");
                    }
                    String operation = decode(fields[1]);
                    ProbeEvidence evidence = deserialize(decode(fields[2]));
                    validateEvidence(normalizedManifest, operation, evidence);
                    begins.add(operation);
                } else if (line.startsWith("receipt\t")) {
                    String[] fields = line.split("\t", -1);
                    if (fields.length != 5) {
                        throw new IOException("External World Coordinator Manifest Receipt Is Invalid");
                    }
                    String operation = decode(fields[1]);
                    String receiptOperation = decode(fields[2]);
                    String token = decode(fields[3]);
                    ProbeEvidence evidence = deserialize(decode(fields[4]));
                    validateEvidence(normalizedManifest, operation, evidence);
                    String expectedReceiptOperation = "rebind.changed".equals(operation) ? "rebind" : operation;
                    if (!expectedReceiptOperation.equals(receiptOperation)
                        || !operation.equals(evidence.operation())) {
                        throw new IOException("External World Coordinator Manifest Receipt Operation Is Invalid");
                    }
                    receipts.add(new CoordinatorReceipt(operation,
                        new WorldExternalPersistenceAdapter.TransactionReceipt(receiptOperation, token), evidence));
                }
            }
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("External World Coordinator Manifest Is Invalid", exception);
        }
        return new ManifestState(adapterId, status, List.copyOf(begins), List.copyOf(receipts), Set.copyOf(rolledBack));
    }

    private static void validateEvidence(Path manifest, String operation, ProbeEvidence evidence) throws IOException {
        if (!operation.equals(evidence.operation())) {
            throw new IOException("External World Coordinator Probe Evidence Operation Is Invalid");
        }
        Path attempt = manifest.getParent();
        if (attempt == null) {
            throw new IOException("External World Coordinator Manifest Authority Is Invalid");
        }
        for (RootEvidence root : evidence.roots()) {
            Path rootPath = MigrationPaths.requirePath(root.root(), "probeRoot");
            Path marker = MigrationPaths.requirePath(root.marker(), "probeMarker");
            if (!rootPath.startsWith(attempt) || rootPath.equals(attempt)
                || !marker.startsWith(rootPath) || !marker.equals(rootPath.resolve("coordinator-marker"))
                || !rootPath.getFileName().toString().equals(root.name())) {
                throw new IOException("External World Coordinator Probe Evidence Escapes Its Authority");
            }
        }
        if (evidence.artifact() != null) {
            Path artifact = MigrationPaths.requirePath(evidence.artifact(), "probeArtifact");
            if (!artifact.startsWith(attempt) || artifact.equals(attempt)) {
                throw new IOException("External World Coordinator Probe Artifact Escapes Its Authority");
            }
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static void cut(String phase) {
        if (phase.equals(crashCut)) {
            crashCut = null;
            throw new CrashCutException(phase);
        }
    }

    private static String hashTree(Path root) throws IOException {
        MessageDigest digest = digest();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.comparing(Path::toString)).toList()) {
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("External World Probe Root Contains A Symbolic Link: " + path);
                }
                Path relative = root.relativize(path);
                boolean directory = Files.isDirectory(path);
                boolean regular = Files.isRegularFile(path);
                String kind = directory ? "d" : regular ? "f" : "o";
                update(digest, kind + "\n" + relative + "\n");
                if (regular) {
                    update(digest, Long.toString(Files.size(path)) + "\n");
                    digest.update(Files.readAllBytes(path));
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String hashFile(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (GeneralSecurityException exception) {
            throw new IOException("External World Probe Manifest Hash Failed", exception);
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 Is Not Available", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private record CoordinatorReceipt(String operation,
                                      WorldExternalPersistenceAdapter.TransactionReceipt adapterReceipt,
                                      ProbeEvidence evidence) {
    }

    private record ProbeRun(String operation,
                            List<WorldExternalPersistenceCapability.WorldRoot> roots,
                            Path artifact,
                            ProbeEvidence before,
                            WorldExternalPersistenceAdapter.BehavioralProbe context) {
    }

    private record ProbeEvidence(String operation,
                                 List<RootEvidence> roots,
                                 Path artifact,
                                 boolean artifactBeforeExists,
                                 String artifactBeforeHash,
                                 String artifactAfterHash,
                                 boolean transitionValid) {
        private ProbeEvidence withTransitionValid(boolean valid) {
            return new ProbeEvidence(operation, roots, artifact, artifactBeforeExists, artifactBeforeHash, artifactAfterHash, valid);
        }
    }

    private record RootEvidence(String name,
                                Path root,
                                Path marker,
                                String beforeTreeHash,
                                String beforeMarkerHash,
                                String afterTreeHash,
                                String afterMarkerHash) {
        private RootEvidence withAfter(String treeHash, String markerHash) {
            return new RootEvidence(name, root, marker, beforeTreeHash, beforeMarkerHash, treeHash, markerHash);
        }
    }

    private record ManifestState(String adapterId,
                                 String status,
                                 List<String> begins,
                                 List<CoordinatorReceipt> receipts,
                                 Set<String> rolledBack) {
    }

    @FunctionalInterface
    private interface ReceiptCall {
        WorldExternalPersistenceAdapter.TransactionReceipt call() throws IOException;
    }

    private static final class CrashCutException extends RuntimeException {
        private CrashCutException(String phase) {
            super("External World Adapter Probe Crash Cut: " + phase);
        }
    }

    private static List<WorldExternalPersistenceCapability.WorldRoot> validateRoots(
        Collection<WorldExternalPersistenceCapability.WorldRoot> roots) throws IOException {
        if (roots == null || roots.isEmpty()) {
            throw new IllegalArgumentException("At Least One Probe Root Is Required");
        }
        Map<String, WorldExternalPersistenceCapability.WorldRoot> validated = new LinkedHashMap<>();
        for (WorldExternalPersistenceCapability.WorldRoot root : roots) {
            if (root == null) {
                throw new IllegalArgumentException("Probe Root Is Required");
            }
            requireWorldName(root.name());
            Path path = MigrationPaths.requireDirectory(root.root(), "worldRoot");
            if (!path.getFileName().toString().equals(root.name())) {
                throw new IllegalArgumentException("Probe Root Must Be Named Exactly " + root.name());
            }
            MigrationPaths.requireNoSymlinkTree(path);
            if (validated.put(root.name(), new WorldExternalPersistenceCapability.WorldRoot(root.name(), path)) != null) {
                throw new IllegalArgumentException("Duplicate Probe World Identity: " + root.name());
            }
        }
        List<WorldExternalPersistenceCapability.WorldRoot> values = List.copyOf(validated.values());
        for (int index = 0; index < values.size(); index++) {
            for (int previous = 0; previous < index; previous++) {
                MigrationPaths.requireDistinctRoots(values.get(index).root(), values.get(previous).root());
            }
        }
        return values;
    }

    private static void requireReceipt(WorldExternalPersistenceAdapter.TransactionReceipt receipt, String operation) throws IOException {
        if (receipt == null || !operation.equals(receipt.operation()) || receipt.token().isBlank()) {
            throw new IOException("External World Adapter Did Not Return A Recoverable " + operation + " Transaction");
        }
    }

    private static <T> T execute(WorldExternalPersistenceAdapter adapter,
                                 WorldExternalPersistenceAdapter.CheckedOperation<T> operation) throws IOException {
        try {
            return adapter.executionContract().execute(operation);
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("External World Adapter Execution Failed", exception);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(field + " Must Be Exact And Non-Blank");
        }
        return value;
    }

    private static String requireWorldName(String value) {
        requireText(value, "worldName");
        if (value.indexOf('\u0000') >= 0 || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
            || value.equals(".") || value.equals("..") || value.contains("..")) {
            throw new IllegalArgumentException("Probe World Identity Is Not A Named World");
        }
        try {
            Path parsed = Path.of(value);
            if (parsed.isAbsolute() || parsed.getNameCount() != 1 || !value.equals(parsed.getFileName().toString())) {
                throw new IllegalArgumentException("Probe World Identity Is Not A Named World");
            }
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("Probe World Identity Is Not A Named World", exception);
        }
        return value;
    }
}

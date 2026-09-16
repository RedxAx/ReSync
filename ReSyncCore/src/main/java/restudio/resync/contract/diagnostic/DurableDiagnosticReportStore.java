package restudio.resync.contract.diagnostic;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.CanonicalHash;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.canonical.CanonicalLimits;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticSet;
import restudio.resync.migration.MigrationPaths;

public final class DurableDiagnosticReportStore {
    public static final String QUARANTINE_DIRECTORY = ".quarantine/diagnostic-report-temps";
    public static final int MAX_REPORT_BYTES = 4 * 1024 * 1024;
    public static final int MAX_TEMP_BYTES = MAX_REPORT_BYTES;
    private static final String KIND = "diagnostic-report";
    private static final int VERSION = 1;
    private static final String QUARANTINE_CONTAINER = ".quarantine";
    private static final String FILE_SUFFIX = ".json";
    private static final String ATOMIC_TEMP_SUFFIX = ".tmp";
    private static final String LEGACY_TEMP_PREFIX = ".diagnostic-report-";
    private static final String MALFORMED_EVIDENCE_PREFIX = "malformed-";
    private static final String FAILED_EVIDENCE_PREFIX = "failed-";
    private static final String EVIDENCE_SUFFIX = ".evidence";
    private static final String REVISION_FIELD = "revision";
    private static final String LOCK_DIRECTORY = ".locks";
    private static final String STORE_LOCK_NAME = "diagnostic-report-store.lock";
    private static final Map<Path, Object> PROCESS_LOCKS = new ConcurrentHashMap<>();
    private static final CanonicalLimits REPORT_LIMITS = reportLimits();
    private final Path directory;

    private static CanonicalLimits reportLimits() {
        CanonicalLimits standard = CanonicalLimits.standard();
        return new CanonicalLimits(
            standard.inputBytes(),
            MAX_REPORT_BYTES,
            standard.depth(),
            standard.tokens(),
            standard.stringCodePoints(),
            standard.opaqueSubtreeBytes(),
            standard.numericTokenCodePoints(),
            standard.numericPrecisionDigits(),
            standard.canonicalNumericExpansionCodePoints(),
            standard.decimalPrecisionDigits(),
            standard.decimalScale(),
            standard.rejectBom());
    }

    public DurableDiagnosticReportStore(Path directory) {
        this.directory = MigrationPaths.requirePath(
            Objects.requireNonNull(directory, "Diagnostic report directory is required"),
            "Diagnostic report directory");
        try {
            ensureDirectory(this.directory);
            withStoreLock(() -> {
                recover();
                return null;
            });
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Diagnostic reports cannot be recovered: " + this.directory, exception);
        }
    }

    public Path directory() {
        return directory;
    }

    public StoredReport persist(UUID reportId, Collection<? extends Diagnostic> diagnostics) {
        Objects.requireNonNull(reportId, "Diagnostic report ID is required");
        try {
            return withStoreLock(() -> {
                recoverTarget(reportId);
                StoredReport report = create(reportId, diagnostics, Map.of(), nextRevision(reportId));
                write(report);
                return report;
            });
        } catch (IOException exception) {
            throw new IllegalStateException("Diagnostic report cannot be persisted: " + reportId, exception);
        }
    }

    public StoredReport persist(UUID reportId, DiagnosticSet diagnostics) {
        return persist(reportId, diagnostics == null ? List.of() : diagnostics.diagnostics());
    }

    public StoredReport persist(Collection<? extends Diagnostic> diagnostics) {
        return persist(UUID.randomUUID(), diagnostics);
    }

    public StoredReport save(UUID reportId, Collection<? extends Diagnostic> diagnostics) {
        return persist(reportId, diagnostics);
    }

    public StoredReport save(StoredReport report) {
        Objects.requireNonNull(report, "Diagnostic report is required");
        byte[] suppliedBytes = report.canonicalBytes();
        StoredReport decoded = decode(suppliedBytes);
        if (!decoded.reportId().equals(report.reportId())
            || !decoded.createdAt().equals(report.createdAt())
            || !decoded.diagnostics().diagnostics().equals(report.diagnostics().diagnostics())
            || !decoded.unknown().equals(report.unknown())
            || !decoded.contentHash().equals(report.contentHash())
            || decoded.revision() != report.revision()
            || !Arrays.equals(decoded.canonicalBytes(), suppliedBytes)) {
            throw new IllegalArgumentException("Diagnostic report metadata does not match its canonical content");
        }
        try {
            return withStoreLock(() -> {
                recoverTarget(decoded.reportId());
                write(decoded);
                return decoded;
            });
        } catch (IOException exception) {
            throw new IllegalStateException("Diagnostic report cannot be persisted: " + report.reportId(), exception);
        }
    }

    public Optional<StoredReport> load(UUID reportId) {
        Objects.requireNonNull(reportId, "Diagnostic report ID is required");
        Path path = path(reportId);
        try {
            return withStoreLock(() -> loadLocked(reportId, path));
        } catch (IOException exception) {
            throw new IllegalStateException("Diagnostic report cannot be read: " + path, exception);
        }
    }

    public StoredReport require(UUID reportId) {
        return load(reportId).orElseThrow(() -> new IllegalArgumentException("Diagnostic report is not present: " + reportId));
    }

    public Optional<StoredReport> get(UUID reportId) {
        return load(reportId);
    }

    public List<StoredReport> list() {
        try {
            return withStoreLock(this::listLocked);
        } catch (IOException exception) {
            throw new IllegalStateException("Diagnostic reports cannot be listed: " + directory, exception);
        }
    }

    public List<StoredReport> reports() {
        return list();
    }

    public byte[] export(UUID reportId) {
        return require(reportId).canonicalBytes();
    }

    public byte[] exportReport(UUID reportId) {
        return export(reportId);
    }

    public String exportText(UUID reportId) {
        return new String(export(reportId), StandardCharsets.UTF_8);
    }

    public String exportJson(UUID reportId) {
        return exportText(reportId);
    }

    public boolean delete(UUID reportId) {
        Objects.requireNonNull(reportId, "Diagnostic report ID is required");
        try {
            return withStoreLock(() -> deleteLocked(reportId));
        } catch (IOException exception) {
            throw new IllegalStateException("Diagnostic report cannot be removed: " + reportId, exception);
        }
    }

    private Optional<StoredReport> loadLocked(UUID reportId, Path path) throws IOException {
        recover();
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        requireRegularFile(path, "Diagnostic report");
        StoredReport report = readReport(path);
        if (!report.reportId().equals(reportId)) {
            throw new IllegalArgumentException("Diagnostic report ID does not match its storage path");
        }
        return Optional.of(report);
    }

    private List<StoredReport> listLocked() throws IOException {
        List<StoredReport> reports = new ArrayList<>(recover().values());
        reports.sort(Comparator.comparing(report -> report.reportId().toString()));
        return List.copyOf(reports);
    }

    private boolean deleteLocked(UUID reportId) throws IOException {
        recover();
        Path target = path(reportId);
        if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        requireRegularFile(target, "Diagnostic report");
        boolean deleted = Files.deleteIfExists(target);
        if (deleted) {
            forceDirectory(directory);
        }
        return deleted;
    }

    private <T> T withStoreLock(LockedOperation<T> operation) throws IOException {
        Path lockDirectory = directory.resolve(LOCK_DIRECTORY).toAbsolutePath().normalize();
        ensureDirectory(lockDirectory);
        Path lockPath = lockDirectory.resolve(STORE_LOCK_NAME).toAbsolutePath().normalize();
        if (!lockPath.getParent().equals(lockDirectory)) {
            throw new IOException("Diagnostic report lock path escaped its directory");
        }
        Object monitor = PROCESS_LOCKS.computeIfAbsent(lockPath, key -> new Object());
        synchronized (monitor) {
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                FileLock lock = channel.lock()) {
                return operation.run();
            }
        }
    }

    @FunctionalInterface
    private interface LockedOperation<T> {
        T run() throws IOException;
    }

    private StoredReport create(UUID reportId, Collection<? extends Diagnostic> diagnostics, Map<String, ?> unknown,
        long revision) {
        DiagnosticSet normalized = new DiagnosticSet(diagnostics == null ? List.of() : diagnostics);
        Instant createdAt = Instant.now();
        Map<String, Object> reportUnknown = immutableMap(unknown, "report unknown");
        Map<String, Object> base = baseMap(reportId, createdAt, normalized, reportUnknown, revision);
        String contentHash = CanonicalHash.sha256(canonicalReportBytes(JsonValue.fromJava(base)));
        Map<String, Object> document = new LinkedHashMap<>(base);
        document.put("contentHash", contentHash);
        byte[] bytes = canonicalReportBytes(JsonValue.fromJava(document));
        requireByteArraySize(bytes, "Diagnostic report");
        return new StoredReport(reportId, createdAt, normalized, reportUnknown, contentHash, revision, bytes);
    }

    private void write(StoredReport report) {
        Path target = path(report.reportId());
        Path temporary = null;
        byte[] bytes = null;
        try {
            bytes = report.canonicalBytes();
            requireByteArraySize(bytes, "Diagnostic report");
            requireDirectory(directory, "Diagnostic report directory");
            requirePublicationAuthority(report, target);
            temporary = reserveAtomicTemp(target);
            writeAtomicTemp(temporary, bytes);
            requireAtomicTempFile(temporary);
            verifyBytes(temporary, bytes, "Diagnostic report atomic temp");
            requireTargetForPublication(target);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
            requireRegularFile(target, "Diagnostic report");
            verifyBytes(target, bytes, "Diagnostic report");
            forceDirectory(directory);
        } catch (IOException | RuntimeException exception) {
            if (temporary != null && (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(temporary))) {
                try {
                    quarantineFailedTemp(temporary);
                } catch (IOException | RuntimeException quarantineFailure) {
                    exception.addSuppressed(quarantineFailure);
                }
            }
            throw new IllegalStateException("Diagnostic report cannot be persisted: " + target, exception);
        }
    }

    private long nextRevision(UUID reportId) {
        Path target = path(reportId);
        try {
            if (!isInspectablePublicationTarget(target)) {
                return 1L;
            }
            if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
                return 1L;
            }
            requireRegularFile(target, "Diagnostic report");
            StoredReport current = readReport(target);
            if (!reportId.equals(current.reportId())) {
                throw new IllegalArgumentException("Diagnostic report ID does not match its storage path: " + target);
            }
            if (current.revision() == Long.MAX_VALUE) {
                throw new IOException("Diagnostic report revision cannot be incremented: " + target);
            }
            return Math.max(0L, current.revision()) + 1L;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Diagnostic report revision cannot be allocated: " + target, exception);
        }
    }

    private static boolean isInspectablePublicationTarget(Path target) {
        if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        return Files.exists(target, LinkOption.NOFOLLOW_LINKS)
            && !Files.isSymbolicLink(target)
            && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS);
    }

    private static void requirePublicationAuthority(StoredReport report, Path target) throws IOException {
        if (!isInspectablePublicationTarget(target)) {
            return;
        }
        if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        requireRegularFile(target, "Diagnostic report");
        StoredReport active = readReport(target);
        if (!report.reportId().equals(active.reportId())) {
            throw new IOException("Diagnostic report ID does not match its storage path: " + target);
        }
        int comparison = compareAuthority(report, active);
        if (comparison < 0 || (comparison == 0 && !sameContent(report, active))) {
            throw new IOException("Diagnostic report publication is stale or conflicting: " + target);
        }
    }

    private void recoverTarget(UUID reportId) throws IOException {
        requireDirectory(directory, "Diagnostic report directory");
        List<Candidate> candidates = new ArrayList<>();
        List<Path> malformed = new ArrayList<>();
        Path temporaryPath = targetTempPath(reportId);
        if (Files.exists(temporaryPath, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(temporaryPath)) {
            String name = temporaryPath.getFileName().toString();
            if (Files.isSymbolicLink(temporaryPath)) {
                malformed.add(temporaryPath);
            } else {
                TempName temporary;
                try {
                    temporary = parseTempName(name);
                } catch (RuntimeException exception) {
                    temporary = null;
                }
                if (temporary == null || !reportId.equals(temporary.reportId())) {
                    malformed.add(temporaryPath);
                } else {
                    try {
                        requireAtomicTempFile(temporaryPath);
                        byte[] bytes = readBytes(temporaryPath, "Diagnostic report atomic temp");
                        StoredReport report = decode(bytes);
                        if (!reportId.equals(report.reportId())) {
                            throw new IllegalArgumentException(
                                "Diagnostic report ID does not match its temporary path: " + temporaryPath);
                        }
                        candidates.add(new Candidate(temporaryPath, report, bytes));
                    } catch (IOException | RuntimeException exception) {
                        malformed.add(temporaryPath);
                    }
                }
            }
        }
        quarantineMalformed(malformed);
        if (!candidates.isEmpty()) {
            requireTargetIdentity(reportId);
            recoverCandidates(reportId, candidates);
        }
    }

    private Path targetTempPath(UUID reportId) throws IOException {
        Path target = path(reportId).toAbsolutePath().normalize();
        Path temporary = target.resolveSibling(target.getFileName() + "-" + reportId + ATOMIC_TEMP_SUFFIX)
            .toAbsolutePath().normalize();
        if (!temporary.getParent().equals(target.getParent())) {
            throw new IOException("Diagnostic report atomic temp path escaped its directory");
        }
        return temporary;
    }

    private void requireTargetIdentity(UUID reportId) throws IOException {
        Path target = path(reportId);
        if (!isInspectablePublicationTarget(target) || Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        StoredReport active = readReport(target);
        if (!reportId.equals(active.reportId())) {
            throw new IOException("Diagnostic report ID does not match its storage path: " + target);
        }
    }

    private Map<UUID, StoredReport> recover() throws IOException {
        ensureDirectory(directory);
        validateLockDirectory(directory);
        validateQuarantine(directory);
        Map<UUID, List<Candidate>> candidates = new LinkedHashMap<>();
        Map<UUID, StoredReport> reports = new LinkedHashMap<>();
        List<Path> malformed = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (name.equals(QUARANTINE_CONTAINER)) {
                    continue;
                }
                if (name.equals(LOCK_DIRECTORY)) {
                    requireDirectory(path, "Diagnostic report lock directory");
                    continue;
                }
                if (isRootEvidenceName(name)) {
                    requireEvidenceEntry(path);
                    continue;
                }
                if (Files.isSymbolicLink(path)) {
                    if (isPotentialTempName(name)) {
                        malformed.add(path);
                        continue;
                    }
                    throw new IOException("Diagnostic report directory contains a symbolic link: " + path);
                }
                if (isReportName(name)) {
                    requireRegularFile(path, "Diagnostic report");
                    StoredReport report = readReport(path);
                    UUID reportId = reportIdFromName(name);
                    if (!report.reportId().equals(reportId)) {
                        throw new IllegalArgumentException("Diagnostic report ID does not match its storage path: " + path);
                    }
                    reports.put(reportId, report);
                    continue;
                }
                TempName temporary;
                try {
                    temporary = parseTempName(name);
                } catch (RuntimeException exception) {
                    if (isPotentialTempName(name)) {
                        malformed.add(path);
                        continue;
                    }
                    throw exception;
                }
                if (temporary != null) {
                    try {
                        requireAtomicTempFile(path);
                        byte[] bytes = readBytes(path, "Diagnostic report atomic temp");
                        StoredReport report = decode(bytes);
                        if (temporary.reportId() != null && !temporary.reportId().equals(report.reportId())) {
                            throw new IllegalArgumentException("Diagnostic report ID does not match its temporary path: " + path);
                        }
                        candidates.computeIfAbsent(report.reportId(), ignored -> new ArrayList<>())
                            .add(new Candidate(path, report, bytes));
                    } catch (IOException | RuntimeException exception) {
                        malformed.add(path);
                    }
                    continue;
                }
                if (isPotentialTempName(name)) {
                    malformed.add(path);
                    continue;
                }
                if (looksLikeManagedArtifact(name)) {
                    throw new IllegalArgumentException("Diagnostic report directory contains an unknown exact-file artifact: " + path);
                }
            }
        }
        quarantineMalformed(malformed);
        for (Map.Entry<UUID, List<Candidate>> entry : candidates.entrySet()) {
            recoverCandidates(entry.getKey(), entry.getValue());
            reports.put(entry.getKey(), readReport(path(entry.getKey())));
        }
        return Map.copyOf(reports);
    }

    private void recoverCandidates(UUID reportId, List<Candidate> candidates) throws IOException {
        Path target = path(reportId);
        boolean targetPresent = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (!targetPresent && !Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Diagnostic report target could not be inspected: " + target);
        }
        if (!targetPresent) {
            promoteMissingTarget(target, candidates);
            return;
        }
        requireRegularFile(target, "Diagnostic report");
        StoredReport targetReport = readReport(target);
        List<Candidate> newer = candidates.stream()
            .filter(candidate -> compareAuthority(candidate.report(), targetReport) > 0)
            .toList();
        List<Candidate> equalAuthorityConflicts = candidates.stream()
            .filter(candidate -> compareAuthority(candidate.report(), targetReport) == 0
                && !sameContent(candidate.report(), targetReport))
            .toList();
        if (!equalAuthorityConflicts.isEmpty()) {
            throw new IOException("Diagnostic report atomic temps conflict with the active revision");
        }
        if (newer.isEmpty()) {
            quarantineCandidates(candidates);
            return;
        }
        Candidate selected = selectUnambiguousNewer(newer);
        List<Candidate> evidence = candidates.stream()
            .filter(candidate -> candidate != selected)
            .toList();
        validateQuarantineDestinations(evidence);
        promoteCandidate(selected, target, true);
        quarantineCandidates(evidence);
    }

    private void promoteMissingTarget(Path target, List<Candidate> candidates) throws IOException {
        Candidate selected = selectUnambiguousMissing(candidates);
        List<Candidate> evidence = candidates.stream()
            .filter(candidate -> candidate != selected)
            .toList();
        validateQuarantineDestinations(evidence);
        promoteCandidate(selected, target, false);
        quarantineCandidates(evidence);
    }

    private Candidate selectUnambiguousNewer(List<Candidate> candidates) throws IOException {
        long highestRevision = candidates.stream()
            .mapToLong(candidate -> candidate.report().revision())
            .max()
            .orElseThrow(() -> new IOException("Diagnostic report crash candidate is missing"));
        List<Candidate> highest = candidates.stream()
            .filter(candidate -> candidate.report().revision() == highestRevision)
            .toList();
        if (!sameContent(highest)) {
            throw new IOException("Diagnostic report atomic temps contain multiple newer candidates");
        }
        return selectLatest(highest);
    }

    private Candidate selectUnambiguousMissing(List<Candidate> candidates) throws IOException {
        long highestRevision = candidates.stream()
            .mapToLong(candidate -> candidate.report().revision())
            .max()
            .orElseThrow(() -> new IOException("Diagnostic report crash candidate is missing"));
        List<Candidate> highest = candidates.stream()
            .filter(candidate -> candidate.report().revision() == highestRevision)
            .toList();
        if (highestRevision == 0L && !sameContent(candidates)) {
            throw new IOException("Diagnostic report atomic temps contain conflicting crash candidates");
        }
        if (!sameContent(highest)) {
            throw new IOException("Diagnostic report atomic temps contain conflicting crash candidates");
        }
        return selectLatest(highest);
    }

    private Candidate selectLatest(List<Candidate> candidates) throws IOException {
        return candidates.stream()
            .max(Comparator.comparing((Candidate candidate) -> candidate.report().contentHash())
                .thenComparing(candidate -> candidate.path().getFileName().toString()))
            .orElseThrow(() -> new IOException("Diagnostic report crash candidate is missing"));
    }

    private static int compareAuthority(StoredReport first, StoredReport second) {
        return Long.compare(first.revision(), second.revision());
    }

    private boolean sameContent(List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            return false;
        }
        Candidate first = candidates.getFirst();
        return candidates.stream().allMatch(candidate -> sameContent(candidate.report(), first.report())
            && Arrays.equals(candidate.bytes(), first.bytes()));
    }

    private static boolean sameContent(StoredReport first, StoredReport second) {
        return first.contentHash().equals(second.contentHash())
            && Arrays.equals(first.canonicalBytes(), second.canonicalBytes());
    }

    private void promoteCandidate(Candidate candidate, Path target, boolean replaceExisting) throws IOException {
        requireAtomicTempFile(candidate.path());
        verifyBytes(candidate.path(), candidate.bytes(), "Diagnostic report atomic temp");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            requireRegularFile(target, "Diagnostic report");
            if (!replaceExisting) {
                throw new FileAlreadyExistsException(target.toString());
            }
        } else if (!Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Diagnostic report target could not be inspected: " + target);
        }
        if (replaceExisting) {
            Files.move(candidate.path(), target, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.move(candidate.path(), target, StandardCopyOption.ATOMIC_MOVE);
        }
        requireRegularFile(target, "Diagnostic report");
        verifyBytes(target, candidate.bytes(), "Diagnostic report recovered file");
        forceDirectory(directory);
    }

    private void quarantineCandidates(List<Candidate> candidates) throws IOException {
        if (candidates.isEmpty()) {
            return;
        }
        validateQuarantineDestinations(candidates);
        Path quarantine = quarantineDirectory(directory);
        List<Candidate> ordered = candidates.stream()
            .sorted(Comparator.comparing(candidate -> candidate.path().getFileName().toString()))
            .toList();
        for (Candidate candidate : ordered) {
            requireAtomicTempFile(candidate.path());
            Path destination = quarantine.resolve(candidate.path().getFileName().toString()).normalize();
            Files.move(candidate.path(), destination, StandardCopyOption.ATOMIC_MOVE);
            requireAtomicTempFile(destination);
            verifyBytes(destination, candidate.bytes(), "Diagnostic report quarantine evidence");
        }
        forceDirectory(quarantine);
        forceDirectory(quarantine.getParent());
        forceDirectory(directory);
        validateQuarantine(directory);
    }

    private void quarantineMalformed(List<Path> malformed) throws IOException {
        if (malformed.isEmpty()) {
            return;
        }
        Path quarantine = quarantineDirectory(directory);
        List<Path> ordered = malformed.stream()
            .sorted(Comparator.comparing(path -> path.getFileName().toString()))
            .toList();
        for (Path source : ordered) {
            if (Files.notExists(source, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(source)) {
                continue;
            }
            Path destination = reserveEvidencePath(quarantine, MALFORMED_EVIDENCE_PREFIX);
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
            requireEvidenceEntry(destination);
        }
        forceDirectory(quarantine);
        forceDirectory(quarantine.getParent());
        forceDirectory(directory);
        validateQuarantine(directory);
    }

    private void quarantineFailedTemp(Path temporary) throws IOException {
        try {
            quarantineEvidence(temporary, FAILED_EVIDENCE_PREFIX);
        } catch (IOException primary) {
            try {
                markRootEvidence(temporary, FAILED_EVIDENCE_PREFIX);
            } catch (IOException fallback) {
                primary.addSuppressed(fallback);
                throw primary;
            }
        }
    }

    private void quarantineEvidence(Path source, String prefix) throws IOException {
        requireAtomicTempFile(source);
        Path quarantine = quarantineDirectory(directory);
        Path destination = reserveEvidencePath(quarantine, prefix);
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        requireEvidenceEntry(destination);
        forceDirectory(quarantine);
        forceDirectory(quarantine.getParent());
        forceDirectory(directory);
        validateQuarantine(directory);
    }

    private void markRootEvidence(Path source, String prefix) throws IOException {
        requireAtomicTempFile(source);
        Path destination = reserveEvidencePath(directory, prefix);
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        requireEvidenceEntry(destination);
        forceDirectory(directory);
    }

    private static Path reserveEvidencePath(Path parent, String prefix) throws IOException {
        Path normalizedParent = MigrationPaths.requirePath(parent, "Diagnostic report evidence directory");
        for (int attempt = 0; attempt < 128; attempt++) {
            Path destination = normalizedParent.resolve(prefix + UUID.randomUUID() + EVIDENCE_SUFFIX)
                .toAbsolutePath().normalize();
            if (!destination.getParent().equals(normalizedParent)) {
                throw new IOException("Diagnostic report evidence path escaped its directory");
            }
            if (Files.notExists(destination, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(destination)) {
                return destination;
            }
        }
        throw new IOException("Unable to reserve diagnostic report evidence");
    }

    private void validateQuarantineDestinations(List<Candidate> candidates) throws IOException {
        if (candidates.isEmpty()) {
            return;
        }
        Path quarantine = quarantineDirectory(directory);
        for (Candidate candidate : candidates) {
            Path destination = quarantine.resolve(candidate.path().getFileName().toString()).normalize();
            if (!destination.getParent().equals(quarantine)
                || Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(destination)) {
                throw new IOException("Diagnostic report quarantine target collides: " + destination);
            }
        }
    }

    private static Path reserveAtomicTemp(Path target) throws IOException {
        Path normalizedTarget = MigrationPaths.requirePath(target, "Diagnostic report");
        Path parent = normalizedTarget.getParent();
        if (parent == null) {
            throw new IOException("Diagnostic report has no parent");
        }
        requireDirectory(parent, "Diagnostic report directory");
        String targetName = normalizedTarget.getFileName().toString();
        if (!isReportName(targetName)) {
            throw new IOException("Diagnostic report target name is invalid: " + normalizedTarget);
        }
        Path temporary = parent.resolve(targetName + "-" + reportIdFromName(targetName) + ATOMIC_TEMP_SUFFIX)
            .toAbsolutePath().normalize();
        if (!temporary.getParent().equals(parent)) {
            throw new IOException("Diagnostic report atomic temp path escaped its directory");
        }
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
        }
        requireAtomicTempFile(temporary);
        return temporary;
    }

    private static void writeAtomicTemp(Path temporary, byte[] bytes) throws IOException {
        requireByteArraySize(bytes, "Diagnostic report atomic temp");
        requireAtomicTempFile(temporary);
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                int before = buffer.position();
                int written = channel.write(buffer);
                if (written <= 0 || buffer.position() == before) {
                    throw new IOException("Diagnostic report atomic temp write made no progress");
                }
            }
            channel.force(true);
        }
    }

    private static void requireTargetForPublication(Path target) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            requireRegularFile(target, "Diagnostic report");
        } else if (!Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Diagnostic report target could not be inspected: " + target);
        }
    }

    private static Path quarantineDirectory(Path root) throws IOException {
        Path normalizedRoot = requireDirectory(root, "Diagnostic report directory");
        Path container = normalizedRoot.resolve(QUARANTINE_CONTAINER).toAbsolutePath().normalize();
        if (Files.notExists(container, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(container);
        }
        requireDirectory(container, "Diagnostic report quarantine container");
        Path quarantine = normalizedRoot.resolve(QUARANTINE_DIRECTORY).toAbsolutePath().normalize();
        if (Files.notExists(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(quarantine);
        }
        requireDirectory(quarantine, "Diagnostic report quarantine");
        return quarantine;
    }

    private static void validateLockDirectory(Path root) throws IOException {
        Path normalizedRoot = requireDirectory(root, "Diagnostic report directory");
        Path locks = normalizedRoot.resolve(LOCK_DIRECTORY).toAbsolutePath().normalize();
        if (Files.notExists(locks, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        requireDirectory(locks, "Diagnostic report lock directory");
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(locks)) {
            for (Path child : stream) {
                if (!child.getFileName().toString().equals(STORE_LOCK_NAME)) {
                    throw new IOException("Diagnostic report lock directory contains an unknown entry: " + child);
                }
                requireRegularFile(child, "Diagnostic report lock file");
            }
        }
    }

    private static void validateQuarantine(Path root) throws IOException {
        Path normalizedRoot = requireDirectory(root, "Diagnostic report directory");
        Path container = normalizedRoot.resolve(QUARANTINE_CONTAINER).toAbsolutePath().normalize();
        if (Files.notExists(container, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        requireDirectory(container, "Diagnostic report quarantine container");
        Path quarantine = normalizedRoot.resolve(QUARANTINE_DIRECTORY).toAbsolutePath().normalize();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(container)) {
            for (Path child : stream) {
                if (!child.equals(quarantine)) {
                    throw new IOException("Diagnostic report quarantine container contains an unknown entry: " + child);
                }
            }
        }
        if (Files.notExists(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Diagnostic report quarantine is missing: " + quarantine);
        }
        requireDirectory(quarantine, "Diagnostic report quarantine");
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(quarantine)) {
            for (Path child : stream) {
                String name = child.getFileName().toString();
                if (isEvidenceName(name)) {
                    requireEvidenceEntry(child);
                    continue;
                }
                if (!isQuarantineTempName(name)) {
                    throw new IOException("Diagnostic report quarantine contains an unknown entry: " + child);
                }
                requireAtomicTempFile(child);
            }
        }
    }

    private static StoredReport readReport(Path path) throws IOException {
        requireRegularFile(path, "Diagnostic report");
        return decode(readBytes(path, "Diagnostic report"));
    }

    private static byte[] readBytes(Path path, String label) throws IOException {
        requireRegularFile(path, label);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            requireFileSize(size, label);
            byte[] bytes = new byte[(int) size];
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                int before = buffer.position();
                int read = channel.read(buffer);
                if (read <= 0 || buffer.position() == before) {
                    throw new IOException(label + " read made no progress: " + path);
                }
            }
            if (channel.size() != size) {
                throw new IOException(label + " changed while it was being read: " + path);
            }
            return bytes;
        }
    }

    private static void verifyBytes(Path path, byte[] expected, String label) throws IOException {
        byte[] actual = readBytes(path, label);
        if (actual.length != expected.length || !Arrays.equals(actual, expected)) {
            throw new IOException(label + " content verification failed: " + path);
        }
    }

    private static void requireFileSize(long size, String label) throws IOException {
        if (size < 0L || size > MAX_TEMP_BYTES) {
            throw new IOException(label + " exceeds the maximum size of " + MAX_TEMP_BYTES + " bytes");
        }
    }

    private static void requireByteArraySize(byte[] bytes, String label) {
        Objects.requireNonNull(bytes, label + " bytes are required");
        if (bytes.length > MAX_REPORT_BYTES) {
            throw new IllegalArgumentException(label + " exceeds the maximum size of " + MAX_REPORT_BYTES + " bytes");
        }
    }

    private static void forceDirectory(Path directory) {
        if (directory == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
        }
    }

    private static void ensureDirectory(Path path) throws IOException {
        Path normalized = MigrationPaths.requirePath(path, "Diagnostic report directory");
        if (Files.notExists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(normalized);
        }
        requireDirectory(normalized, "Diagnostic report directory");
    }

    private static Path requireDirectory(Path path, String label) throws IOException {
        Path normalized = MigrationPaths.requirePath(path, label);
        if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " must be a regular non-symbolic-link directory: " + normalized);
        }
        return normalized;
    }

    private static void requireRegularFile(Path path, String label) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " must be a regular non-symbolic-link file: " + path);
        }
    }

    private static void requireAtomicTempFile(Path path) throws IOException {
        requireRegularFile(path, "Diagnostic report atomic temp");
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            requireFileSize(channel.size(), "Diagnostic report atomic temp");
        }
    }

    private static void requireEvidenceEntry(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            return;
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Diagnostic report evidence must be a regular file, directory, or symbolic link: " + path);
        }
    }

    private static boolean isReportName(String name) {
        if (name == null || !name.endsWith(FILE_SUFFIX)) {
            return false;
        }
        String value = name.substring(0, name.length() - FILE_SUFFIX.length());
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static UUID reportIdFromName(String name) {
        return UUID.fromString(name.substring(0, name.length() - FILE_SUFFIX.length()));
    }

    private static TempName parseTempName(String name) {
        if (name == null || !name.endsWith(ATOMIC_TEMP_SUFFIX)) {
            return null;
        }
        int targetEnd = name.indexOf(FILE_SUFFIX + "-");
        if (targetEnd > 0) {
            String targetName = name.substring(0, targetEnd + FILE_SUFFIX.length());
            if (!isReportName(targetName)) {
                throw new IllegalArgumentException("Diagnostic report atomic temp name is invalid: " + name);
            }
            if (name.charAt(targetName.length()) != '-') {
                throw new IllegalArgumentException("Diagnostic report atomic temp name is invalid: " + name);
            }
            String token = name.substring(targetName.length() + 1, name.length() - ATOMIC_TEMP_SUFFIX.length());
            if (!isCanonicalUuid(token)) {
                throw new IllegalArgumentException("Diagnostic report atomic temp name is invalid: " + name);
            }
            return new TempName(reportIdFromName(targetName));
        }
        if (name.startsWith(LEGACY_TEMP_PREFIX)) {
            String token = name.substring(LEGACY_TEMP_PREFIX.length(), name.length() - ATOMIC_TEMP_SUFFIX.length());
            if (!isSafeGeneratedToken(token)) {
                throw new IllegalArgumentException("Diagnostic report atomic temp name is invalid: " + name);
            }
            return new TempName(null);
        }
        return null;
    }

    private static boolean isQuarantineTempName(String name) {
        try {
            return parseTempName(name) != null;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean isEvidenceName(String name) {
        return isEvidenceName(name, MALFORMED_EVIDENCE_PREFIX)
            || isEvidenceName(name, FAILED_EVIDENCE_PREFIX);
    }

    private static boolean isEvidenceName(String name, String prefix) {
        if (name == null || !name.startsWith(prefix) || !name.endsWith(EVIDENCE_SUFFIX)) {
            return false;
        }
        return isCanonicalUuid(name.substring(prefix.length(), name.length() - EVIDENCE_SUFFIX.length()));
    }

    private static boolean isRootEvidenceName(String name) {
        return isEvidenceName(name);
    }

    private static boolean isPotentialTempName(String name) {
        return name != null && (name.startsWith(LEGACY_TEMP_PREFIX) || name.contains(FILE_SUFFIX + "-"));
    }

    private static boolean isCanonicalUuid(String value) {
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isSafeGeneratedToken(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= '0' && character <= '9') && !(character >= 'a' && character <= 'z')
                && !(character >= 'A' && character <= 'Z') && character != '_' && character != '-') {
                return false;
            }
        }
        return true;
    }

    private static boolean looksLikeManagedArtifact(String name) {
        return name.endsWith(FILE_SUFFIX) || name.endsWith(ATOMIC_TEMP_SUFFIX)
            || name.contains(FILE_SUFFIX) || name.startsWith(LEGACY_TEMP_PREFIX);
    }

    private record Candidate(Path path, StoredReport report, byte[] bytes) {
        private Candidate {
            path = path.toAbsolutePath().normalize();
            report = Objects.requireNonNull(report, "Diagnostic report candidate is required");
            bytes = Objects.requireNonNull(bytes, "Diagnostic report candidate bytes are required").clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private record TempName(UUID reportId) {
    }

    private static StoredReport decode(byte[] bytes) {
        requireByteArraySize(bytes, "Diagnostic report");
        JsonValue value = CanonicalCodec.decode(bytes, REPORT_LIMITS);
        Map<String, Object> document = map(value.toJava(), "diagnostic report");
        if (!KIND.equals(text(document.get("kind"), "kind")) || integer(document.get("version"), "version") != VERSION) {
            throw new IllegalArgumentException("Unsupported diagnostic report document");
        }
        UUID reportId = UUID.fromString(text(document.get("reportId"), "reportId"));
        Instant createdAt = Instant.parse(text(document.get("createdAt"), "createdAt"));
        long revision = document.containsKey(REVISION_FIELD) ? longValue(document.get(REVISION_FIELD), REVISION_FIELD) : 0L;
        if (revision < 0L) {
            throw new IllegalArgumentException("Diagnostic report revision must not be negative");
        }
        Object rawDiagnostics = document.get("diagnostics");
        if (!(rawDiagnostics instanceof List<?> values)) {
            throw new IllegalArgumentException("Diagnostic report diagnostics must be an array");
        }
        List<Diagnostic> diagnostics = values.stream().map(item -> decodeDiagnostic(map(item, "diagnostic"))).toList();
        Map<String, Object> unknown = new LinkedHashMap<>();
        document.forEach((key, item) -> {
            if (!Set.of("kind", "version", "reportId", "createdAt", "diagnostics", "contentHash", REVISION_FIELD).contains(key)) {
                unknown.put(key, item);
            }
        });
        DiagnosticSet normalizedDiagnostics = new DiagnosticSet(diagnostics);
        Map<String, Object> base = baseMap(reportId, createdAt, normalizedDiagnostics, unknown, revision);
        String expectedHash = CanonicalHash.sha256(canonicalReportBytes(JsonValue.fromJava(base)));
        String declaredHash = text(document.get("contentHash"), "contentHash");
        if (!expectedHash.equals(declaredHash)) {
            throw new IllegalArgumentException("Diagnostic report content hash does not match its canonical content");
        }
        Map<String, Object> normalizedDocument = new LinkedHashMap<>(base);
        normalizedDocument.put("contentHash", expectedHash);
        byte[] canonicalBytes = canonicalReportBytes(JsonValue.fromJava(normalizedDocument));
        if (!Arrays.equals(canonicalBytes, bytes)) {
            throw new IllegalArgumentException("Diagnostic report bytes do not match its normalized canonical content");
        }
        return new StoredReport(reportId, createdAt, normalizedDiagnostics, unknown, declaredHash, revision, canonicalBytes);
    }

    private static byte[] canonicalReportBytes(JsonValue value) {
        return Objects.requireNonNull(value, "Diagnostic report JSON is required").canonicalBytes(REPORT_LIMITS);
    }

    private static Map<String, Object> baseMap(UUID reportId, Instant createdAt, DiagnosticSet diagnostics,
        Map<String, Object> unknown, long revision) {
        if (revision < 0L) {
            throw new IllegalArgumentException("Diagnostic report revision must not be negative");
        }
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("kind", KIND);
        base.put("version", VERSION);
        base.put("reportId", reportId.toString());
        base.put("createdAt", createdAt.toString());
        base.put("diagnostics", diagnostics.toMaps());
        if (revision > 0L) {
            base.put(REVISION_FIELD, revision);
        }
        for (Map.Entry<String, Object> entry : unknown.entrySet()) {
            if (base.containsKey(entry.getKey()) || "contentHash".equals(entry.getKey())) {
                throw new IllegalArgumentException("Diagnostic report unknown field collides with known field: " + entry.getKey());
            }
            base.put(entry.getKey(), entry.getValue());
        }
        return base;
    }

    private Path path(UUID reportId) {
        return directory.resolve(reportId.toString() + ".json");
    }

    private static Diagnostic decodeDiagnostic(Map<String, Object> value) {
        return Diagnostic.fromCanonical(value);
    }

    private static Map<String, Object> map(Object value, String field) {
        if (!(value instanceof Map<?, ?> source)) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException(field + " keys must be strings");
            }
            result.put(text, item);
        });
        return result;
    }

    private static Map<String, Object> immutableMap(Map<String, ?> value, String field) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        Object normalized = JsonValue.fromJava(value).toJava();
        return immutableObjectMap(normalized, field);
    }

    private static Map<String, Object> immutableObjectMap(Object value, String field) {
        Map<String, Object> source = map(value, field);
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(key, immutableValue(item, field + "." + key)));
        return Collections.unmodifiableMap(result);
    }

    private static Object immutableValue(Object value, String field) {
        if (value instanceof Map<?, ?>) {
            return immutableObjectMap(value, field);
        }
        if (value instanceof List<?> values) {
            List<Object> immutable = new ArrayList<>(values.size());
            for (Object item : values) {
                immutable.add(immutableValue(item, field + "[]"));
            }
            return Collections.unmodifiableList(immutable);
        }
        return value;
    }

    private static String text(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(field + " must be nonblank text");
        }
        return text;
    }

    private static boolean booleanValue(Object value, String field) {
        if (!(value instanceof Boolean result)) {
            throw new IllegalArgumentException(field + " must be boolean");
        }
        return result;
    }

    private static int integer(Object value, String field) {
        long number = longValue(value, field);
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " is outside integer range");
        }
        return (int) number;
    }

    private static long longValue(Object value, String field) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        try {
            return new BigDecimal(number.toString()).longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(field + " must be an integer", exception);
        }
    }

    private static <E> E enumValue(Object value, E[] values, Function<E, String> wire, String field) {
        String expected = text(value, field);
        for (E candidate : values) {
            if (wire.apply(candidate).equals(expected)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unknown " + field + ": " + expected);
    }

    public record StoredReport(
        UUID reportId,
        Instant createdAt,
        DiagnosticSet diagnostics,
        Map<String, Object> unknown,
        String contentHash,
        long revision,
        byte[] canonicalBytes
    ) {
        public StoredReport(UUID reportId, Instant createdAt, DiagnosticSet diagnostics, Map<String, Object> unknown,
            String contentHash, byte[] canonicalBytes) {
            this(reportId, createdAt, diagnostics, unknown, contentHash, 0L, canonicalBytes);
        }

        public StoredReport {
            reportId = Objects.requireNonNull(reportId, "Diagnostic report ID is required");
            createdAt = Objects.requireNonNull(createdAt, "Diagnostic report creation time is required");
            diagnostics = Objects.requireNonNull(diagnostics, "Diagnostic report diagnostics are required");
            unknown = immutableMap(unknown, "report unknown");
            contentHash = Objects.requireNonNull(contentHash, "Diagnostic report content hash is required");
            if (revision < 0L) {
                throw new IllegalArgumentException("Diagnostic report revision must not be negative");
            }
            canonicalBytes = Objects.requireNonNull(canonicalBytes, "Diagnostic report canonical bytes are required");
            requireByteArraySize(canonicalBytes, "Diagnostic report canonical bytes");
            canonicalBytes = canonicalBytes.clone();
        }

        @Override
        public byte[] canonicalBytes() {
            return canonicalBytes.clone();
        }

        public String canonicalText() {
            return new String(canonicalBytes, StandardCharsets.UTF_8);
        }

        public String exportText() {
            return canonicalText();
        }
    }
}

package restudio.resync.upgrade.lifecycle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.QuarantineRecord;
import restudio.resync.upgrade.TypedLifecycleMigrationAdapter;

public final class ManagedFileMigrationAdapter implements TypedLifecycleMigrationAdapter {
    public static final String CODE_TARGET_COLLISION = "MIGRATION.MANAGED_FILE_TARGET_COLLISION";
    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
        "con", "prn", "aux", "nul", "clock$",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    private final String adapterId;
    private final List<Policy> policies;

    public ManagedFileMigrationAdapter(String adapterId, Collection<Policy> policies) {
        this.adapterId = requireText(adapterId, "adapterId");
        List<Policy> sorted = new ArrayList<>(policies == null ? List.of() : policies);
        sorted.forEach(policy -> Objects.requireNonNull(policy, "policy"));
        sorted.sort(Comparator.comparing(Policy::sourcePath).thenComparing(Policy::targetPath).thenComparing(Policy::owner));
        Set<String> sources = new HashSet<>();
        Set<String> targets = new HashSet<>();
        for (Policy policy : sorted) {
            if (!sources.add(collisionKey(policy.sourcePath()))) {
                throw new IllegalArgumentException("Duplicate Managed File Source Policy: " + policy.sourcePath());
            }
            if (!targets.add(collisionKey(policy.targetPath()))) {
                throw new IllegalArgumentException("Duplicate Managed File Target Policy: " + policy.targetPath());
            }
        }
        for (Policy policy : sorted) {
            String target = collisionKey(policy.targetPath());
            if (policy.relocates() && sources.contains(target)) {
                throw new IllegalArgumentException("Managed File Target Is Another Policy Source: " + policy.targetPath());
            }
        }
        for (int first = 0; first < sorted.size(); first++) {
            for (int second = first + 1; second < sorted.size(); second++) {
                Policy left = sorted.get(first);
                Policy right = sorted.get(second);
                if (hierarchicalCollision(left.sourcePath(), right.sourcePath())
                    || hierarchicalCollision(left.sourcePath(), right.targetPath())
                    || hierarchicalCollision(left.targetPath(), right.sourcePath())
                    || hierarchicalCollision(left.targetPath(), right.targetPath())) {
                    throw new IllegalArgumentException("Managed File Policies Have A File And Directory Collision");
                }
            }
        }
        this.policies = List.copyOf(sorted);
    }

    @Override
    public String adapterId() {
        return adapterId;
    }

    public List<Policy> policies() {
        return policies;
    }

    @Override
    public Adaptation adapt(Input input) throws IOException {
        Objects.requireNonNull(input, "input");
        List<Claim> claims = new ArrayList<>();
        List<Change> changes = new ArrayList<>();
        List<QuarantineRecord> quarantine = new ArrayList<>();
        for (Policy policy : policies) {
            SourceFile source = input.file(policy.sourcePath()).orElse(null);
            if (source == null) {
                adaptCompletedTarget(input, policy, claims);
                continue;
            }
            claims.add(new Claim(source.relativePath(), policy.owner()));
            if (!source.owner().equals(policy.owner())) {
                continue;
            }
            byte[] bytes = input.read(source);
            SourceFile target = policy.relocates() ? input.file(policy.targetPath()).orElse(null) : null;
            if (target != null) {
                claims.add(new Claim(target.relativePath(), policy.owner()));
                if (target.owner().equals(policy.owner())) {
                    input.read(target);
                }
                quarantine.add(targetCollision(policy, source, target));
                continue;
            }
            if (policy.relocates()) {
                SourceFile hierarchicalTarget = input.files().stream()
                    .filter(candidate -> hierarchicalCollision(policy.targetPath(), candidate.relativePath()))
                    .findFirst()
                    .orElse(null);
                if (hierarchicalTarget != null) {
                    quarantine.add(hierarchicalTargetCollision(policy, source, hierarchicalTarget));
                    continue;
                }
                Path targetPath = MigrationPaths.resolveInside(input.root(), policy.targetPath());
                Path conflictingAncestor = physicalAncestorConflict(input.root(), targetPath);
                if (conflictingAncestor != null) {
                    quarantine.add(physicalTargetCollision(policy, source, conflictingAncestor));
                    continue;
                }
                if (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)) {
                    quarantine.add(physicalTargetCollision(policy, source, targetPath));
                    continue;
                }
            }
            if (policy.relocates()) {
                changes.add(new Change("move", source.relativePath(), policy.targetPath(), bytes));
            }
        }
        return new Adaptation(claims, changes, quarantine);
    }

    private static void adaptCompletedTarget(Input input, Policy policy, List<Claim> claims) throws IOException {
        if (!policy.relocates()) {
            return;
        }
        SourceFile target = input.file(policy.targetPath()).orElse(null);
        if (target == null) {
            return;
        }
        claims.add(new Claim(target.relativePath(), policy.owner()));
        if (!target.owner().equals(policy.owner())) {
            return;
        }
        input.read(target);
    }

    private static QuarantineRecord targetCollision(Policy policy, SourceFile source, SourceFile target) {
        return quarantine(
            CODE_TARGET_COLLISION,
            source.relativePath(),
            "The managed file target already exists, so the relocation is ambiguous.",
            List.of(source.relativePath(), target.relativePath()),
            "Resolve the target collision and retain exactly one authoritative managed file.",
            source.sha256(),
            policy,
            source.sha256(),
            target.sha256());
    }

    private static QuarantineRecord physicalTargetCollision(Policy policy, SourceFile source, Path target) {
        String targetKind = Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
            ? "directory"
            : Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) ? "unverified-file" : "non-regular-file";
        return quarantine(
            CODE_TARGET_COLLISION,
            source.relativePath(),
            "The managed file target exists outside the verified file manifest.",
            List.of(source.relativePath(), policy.targetPath()),
            "Remove the unverified target or include it in the authoritative snapshot before migrating.",
            source.sha256(),
            policy,
            source.sha256(),
            targetKind);
    }

    private static QuarantineRecord hierarchicalTargetCollision(Policy policy, SourceFile source, SourceFile conflictingSource) {
        return quarantine(
            CODE_TARGET_COLLISION,
            source.relativePath(),
            "The managed file target conflicts with an existing verified file hierarchy.",
            List.of(source.relativePath(), conflictingSource.relativePath(), policy.targetPath()),
            "Choose a target that is neither a parent nor a child of any persisted file.",
            source.sha256(),
            policy,
            source.sha256(),
            conflictingSource.sha256());
    }

    private static boolean hierarchicalCollision(String first, String second) {
        String left = collisionKey(first);
        String right = collisionKey(second);
        return left.equals(right) || left.startsWith(right + "/") || right.startsWith(left + "/");
    }

    private static Path physicalAncestorConflict(Path root, Path target) {
        Path current = target.getParent();
        while (current != null && !current.equals(root)) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private static String requirePolicyPath(String value) {
        String path = MigrationPaths.requireRelative(value);
        if (!path.equals(Normalizer.normalize(path, Normalizer.Form.NFC))) {
            throw new IllegalArgumentException("Managed File Policy Path Must Be NFC Normalized");
        }
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (!segment.equals(segment.strip()) || segment.endsWith(".") || segment.endsWith(" ")) {
                throw new IllegalArgumentException("Managed File Policy Path Contains An Unsafe Segment");
            }
            String folded = segment.toLowerCase(Locale.ROOT);
            String windowsName = folded.contains(".") ? folded.substring(0, folded.indexOf('.')) : folded;
            if (WINDOWS_RESERVED_NAMES.contains(windowsName)) {
                throw new IllegalArgumentException("Managed File Policy Path Uses A Reserved Name");
            }
            for (int index = 0; index < segment.length(); index++) {
                char current = segment.charAt(index);
                if (Character.isISOControl(current) || current == ':' || current == '*' || current == '?'
                    || current == '"' || current == '<' || current == '>' || current == '|') {
                    throw new IllegalArgumentException("Managed File Policy Path Contains An Unsafe Character");
                }
            }
        }
        String folded = collisionKey(path);
        if (folded.equals(".quarantine") || folded.startsWith(".quarantine/")) {
            throw new IllegalArgumentException("Managed File Policy Path Uses The Reserved Quarantine Root");
        }
        return path;
    }

    private static String collisionKey(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static QuarantineRecord quarantine(String code,
                                               String sourceLocation,
                                               String reason,
                                               List<String> affectedReferences,
                                               String action,
                                               String sourceHash,
                                               Policy policy,
                                               String firstEvidence,
                                               String secondEvidence) {
        String recordId = "managed-file-" + CanonicalJson.sha256(
            "migration.managed-file-quarantine",
            List.of(code, policy.owner(), policy.sourcePath(), policy.targetPath(), policy.mode().name(), firstEvidence, secondEvidence)).substring(0, 24);
        return new QuarantineRecord(recordId, code, sourceLocation, reason, affectedReferences, action, sourceHash);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || value.indexOf('\u0000') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " Is Invalid");
        }
        return value;
    }

    public enum Mode {
        PRESERVE,
        RELOCATE
    }

    public record Policy(String owner, String sourcePath, String targetPath, Mode mode) {
        public Policy {
            owner = requireText(owner, "owner");
            sourcePath = requirePolicyPath(sourcePath);
            targetPath = requirePolicyPath(targetPath);
            mode = Objects.requireNonNull(mode, "mode");
            if (mode == Mode.PRESERVE && !sourcePath.equals(targetPath)) {
                throw new IllegalArgumentException("Preserved Managed File Paths Must Match");
            }
            if (mode == Mode.RELOCATE && sourcePath.equals(targetPath)) {
                throw new IllegalArgumentException("Relocated Managed File Paths Must Differ");
            }
        }

        public static Policy preserve(String owner, String relativePath) {
            return new Policy(owner, relativePath, relativePath, Mode.PRESERVE);
        }

        public static Policy relocate(String owner, String sourcePath, String targetPath) {
            return new Policy(owner, sourcePath, targetPath, Mode.RELOCATE);
        }

        public boolean relocates() {
            return mode == Mode.RELOCATE;
        }
    }
}

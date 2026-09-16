package restudio.resync.upgrade;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.LinkedHashMap;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticRedaction;
import restudio.resync.flow.diagnostic.DiagnosticProvenance;
import restudio.resync.flow.diagnostic.DiagnosticSet;
import restudio.resync.flow.diagnostic.DiagnosticSourceKind;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceKey;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.migration.MigrationOperation;
import restudio.resync.migration.MigrationOperationType;
import restudio.resync.migration.MigrationPlan;
import restudio.resync.migration.QuarantineRecord;
import restudio.resync.migration.QuarantineReport;
import restudio.resync.migration.Snapshot;

public final class OfflineSnapshotUpgradePlanner implements UpgradePlanner {
    private static final ContractRef<OperationId> MESSAGE_KEY = ContractRef.of(OwnerId.of("restudio.resync"), OperationId.of("upgrade"));
    private static final String ADAPTER_PREFIX = "resync.identity-copy/";

    private final List<Rule> rules;
    private final GraphFamily graphFamily;

    public OfflineSnapshotUpgradePlanner(Collection<? extends Rule> rules) {
        List<Rule> sorted = new ArrayList<>(rules == null ? List.of() : rules);
        sorted.sort(Comparator.comparing((Rule value) -> value.sourcePath())
            .thenComparing((Rule value) -> value.targetPath())
            .thenComparing((Rule value) -> value.adapterId()));
        Set<String> paths = new HashSet<>();
        for (Rule rule : sorted) {
            if (!paths.add(rule.adapterId() + "\u0000" + rule.sourcePath() + "\u0000" + rule.targetPath())) {
                throw new IllegalArgumentException("Duplicate Offline Rule: " + rule.sourcePath() + " -> " + rule.targetPath());
            }
        }
        this.rules = List.copyOf(sorted);
        this.graphFamily = null;
    }

    public OfflineSnapshotUpgradePlanner(GraphFamily graphFamily) {
        this.rules = List.of();
        this.graphFamily = Objects.requireNonNull(graphFamily, "graphFamily");
    }

    public static OfflineSnapshotUpgradePlanner forGraphFamily(GraphFamily graphFamily) {
        return new OfflineSnapshotUpgradePlanner(graphFamily);
    }

    public static GraphSource graphSource(String adapterId, String sourcePrefix, String targetPrefix, String owner, int precedence) {
        return new GraphSource(adapterId, sourcePrefix, targetPrefix, owner, precedence);
    }

    public static GraphFamily graphFamily(ContractRef<ResourceTypeId> resourceType, Collection<? extends GraphSource> sources) {
        return new GraphFamily(resourceType, new ArrayList<>(sources == null ? List.of() : sources));
    }

    public static IdentityRule exact(String relativePath, String owner) {
        return new IdentityRule(ADAPTER_PREFIX + relativePath.replace('/', '.'), relativePath, owner);
    }

    public static DirectoryRule directory(String sourcePath, String targetPath, String owner) {
        return new DirectoryRule(ADAPTER_PREFIX + "directory/" + sourcePath.replace('/', '.') + "->" + targetPath.replace('/', '.'), sourcePath, targetPath, owner, "copy");
    }

    public static DirectoryRule graphDirectory(String sourcePath, String targetPath, String owner) {
        return new DirectoryRule(ADAPTER_PREFIX + "graph/" + sourcePath.replace('/', '.') + "->" + targetPath.replace('/', '.'), sourcePath, targetPath, owner, "graph-copy");
    }

    public static DirectoryRule resourceDirectory(String sourcePath, String targetPath, String owner) {
        return new DirectoryRule(ADAPTER_PREFIX + "resource/" + sourcePath.replace('/', '.') + "->" + targetPath.replace('/', '.'), sourcePath, targetPath, owner, "resource-copy");
    }

    public List<Rule> rules() {
        return rules;
    }

    @Override
    public UpgradeProposal plan(Snapshot sourceSnapshot, UpgradeSourceWindow sourceWindow) throws IOException {
        Objects.requireNonNull(sourceWindow, "sourceWindow");
        ImmutableSnapshotAdapter.View source = ImmutableSnapshotAdapter.adapt(sourceSnapshot);
        sourceWindow.requireSupported(source.metadata());
        if (graphFamily != null) {
            return planGraphFamily(sourceSnapshot, source, sourceWindow);
        }
        List<PlannedOperation> planned = new ArrayList<>();
        List<QuarantineRecord> quarantine = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (ImmutableSnapshotAdapter.Entry entry : source.entries()) {
            List<Rule> matching = matchingRules(entry.relativePath());
            if (matching.isEmpty()) {
                quarantine.add(quarantine(entry, source, "No deterministic offline adapter is registered for this source file.", "Register an adapter that preserves this path and its typed identity."));
                diagnostics.add(unsupportedDiagnostic(entry, source, sourceWindow, "No offline adapter is registered for this source file."));
                continue;
            }
            if (matching.size() > 1) {
                quarantine.add(quarantine(entry, source, "Multiple offline adapters match this source file.", "Register exactly one adapter for this source path."));
                diagnostics.add(unsupportedDiagnostic(entry, source, sourceWindow, "Multiple offline adapters match this source file."));
                continue;
            }
            Rule rule = matching.getFirst();
            if (!rule.owner().equals(entry.owner())) {
                quarantine.add(quarantine(entry, source, "The registered adapter owner does not match the snapshot owner.", "Register the adapter against the participant that owns this file."));
                diagnostics.add(unsupportedDiagnostic(entry, source, sourceWindow, "The registered adapter owner does not match the snapshot owner."));
                continue;
            }
            planned.add(new PlannedOperation(entry, rule));
        }
        Map<String, List<PlannedOperation>> byTarget = new LinkedHashMap<>();
        planned.forEach(operation -> byTarget.computeIfAbsent(operation.targetPath(), ignored -> new ArrayList<>()).add(operation));
        List<MigrationOperation> operations = new ArrayList<>();
        for (List<PlannedOperation> candidates : byTarget.values()) {
            if (candidates.size() > 1) {
                candidates.sort(Comparator.comparing((PlannedOperation value) -> value.entry().relativePath()));
                for (PlannedOperation candidate : candidates) {
                    quarantine.add(quarantine(candidate.entry(), source, "Multiple source files map to the same replacement path.", "Provide a unique target path for every source file."));
                    diagnostics.add(unsupportedDiagnostic(candidate.entry(), source, sourceWindow, "Multiple source files map to the same replacement path."));
                }
                continue;
            }
            PlannedOperation candidate = candidates.getFirst();
            operations.add(new MigrationOperation(candidate.rule().operationKind(), candidate.rule().adapterId(), candidate.entry().relativePath(), candidate.targetPath(), candidate.entry().sha256(), candidate.entry().sha256()));
        }
        QuarantineReport report = new QuarantineReport(quarantine);
        MigrationPlan plan = new MigrationPlan(
            source.metadata().snapshotId(),
            source.manifestHash(),
            sourceWindow.sourceFormatVersion(),
            sourceWindow.targetFormatVersion(),
            report.reportHash(),
            operations);
        return new UpgradeProposal(plan, report, new DiagnosticSet(diagnostics));
    }

    private UpgradeProposal planGraphFamily(Snapshot sourceSnapshot, ImmutableSnapshotAdapter.View source, UpgradeSourceWindow sourceWindow) {
        List<GraphCandidate> candidates = new ArrayList<>();
        List<QuarantineRecord> quarantine = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        Set<String> candidatePaths = new HashSet<>();
        for (ImmutableSnapshotAdapter.Entry entry : source.entries()) {
            List<GraphSource> matching = graphFamily.sources().stream().filter(rule -> rule.matchesPrefix(entry.relativePath())).toList();
            if (matching.isEmpty()) {
                addGraphQuarantine(source, sourceWindow, entry, quarantine, diagnostics, "The file is outside the supported graph family.", "Move it through its owning resource migration.");
                continue;
            }
            if (matching.size() != 1) {
                addGraphQuarantine(source, sourceWindow, entry, quarantine, diagnostics, "The file matches more than one graph source window.", "Define one unambiguous graph source window.");
                continue;
            }
            GraphSource rule = matching.getFirst();
            if (!rule.matchesGraphPath(entry.relativePath()) || !rule.owner().equals(entry.owner())) {
                addGraphQuarantine(source, sourceWindow, entry, quarantine, diagnostics, "The file is not a supported typed graph owned by the declared participant.", "Preserve the file under its owning graph participant and supported source window.");
                continue;
            }
            try {
                Path path = restudio.resync.migration.MigrationPaths.resolveInside(sourceSnapshot.root(), entry.relativePath());
                Map<?, ?> document = requireObject(CanonicalJson.parse(Files.readAllBytes(path)));
                String id = requireString(document.get("id"), "id");
                String resourceType = requireString(document.get("resourceType"), "resourceType");
                String fileId = rule.resourceId(entry.relativePath());
                if (!id.equals(fileId) || !resourceType.equals(graphFamily.resourceType().id().canonicalText())) {
                    throw new IllegalArgumentException("Graph Identity Does Not Match Its Typed Source Path");
                }
                ResourceKey resource = new ResourceKey(graphFamily.resourceType(), id);
                if (!candidatePaths.add(entry.relativePath())) {
                    throw new IllegalArgumentException("Duplicate Graph Source Path");
                }
                candidates.add(new GraphCandidate(entry, rule, resource));
            } catch (RuntimeException | IOException exception) {
                addGraphQuarantine(source, sourceWindow, entry, quarantine, diagnostics, "The graph document cannot be validated as the declared typed resource.", "Repair the graph identity and rerun the standalone migration.");
            }
        }

        Map<ResourceKey, List<GraphCandidate>> byResource = new LinkedHashMap<>();
        candidates.forEach(candidate -> byResource.computeIfAbsent(candidate.resource(), ignored -> new ArrayList<>()).add(candidate));
        List<GraphCandidate> winners = new ArrayList<>();
        for (List<GraphCandidate> duplicateSet : byResource.values()) {
            duplicateSet.sort(Comparator.comparingInt((GraphCandidate candidate) -> candidate.source().precedence())
                .thenComparing(candidate -> candidate.entry().relativePath())
                .thenComparing(candidate -> candidate.entry().sha256()));
            int winnerPrecedence = duplicateSet.getFirst().source().precedence();
            List<GraphCandidate> preferred = duplicateSet.stream().filter(candidate -> candidate.source().precedence() == winnerPrecedence).toList();
            if (preferred.size() != 1) {
                for (GraphCandidate duplicate : duplicateSet) {
                    addGraphQuarantine(source, sourceWindow, duplicate.entry(), quarantine, diagnostics, "Multiple graph sources have the same explicit precedence for one typed resource.", "Choose one canonical source precedence for the resource.");
                }
                continue;
            }
            GraphCandidate winner = preferred.getFirst();
            winners.add(winner);
            for (GraphCandidate duplicate : duplicateSet) {
                if (duplicate != winner) {
                    addGraphQuarantine(source, sourceWindow, duplicate.entry(), quarantine, diagnostics, "A higher-precedence canonical graph source was selected for this typed resource.", "Retain the canonical source and review the duplicate in quarantine.");
                }
            }
        }

        Map<String, List<GraphCandidate>> byTarget = new LinkedHashMap<>();
        winners.forEach(candidate -> byTarget.computeIfAbsent(candidate.targetPath(), ignored -> new ArrayList<>()).add(candidate));
        List<MigrationOperation> operations = new ArrayList<>();
        for (List<GraphCandidate> targetSet : byTarget.values()) {
            if (targetSet.size() != 1) {
                for (GraphCandidate collision : targetSet) {
                    addGraphQuarantine(source, sourceWindow, collision.entry(), quarantine, diagnostics, "Multiple typed graphs map to the same replacement path.", "Define unique replacement paths for the graph resources.");
                }
                continue;
            }
            GraphCandidate candidate = targetSet.getFirst();
            operations.add(new MigrationOperation(
                MigrationOperationType.COPY,
                candidate.source().adapterId(),
                candidate.entry().relativePath(),
                candidate.targetPath(),
                candidate.entry().sha256(),
                candidate.entry().sha256(),
                candidate.resource()));
        }
        QuarantineReport report = new QuarantineReport(quarantine);
        MigrationPlan plan = new MigrationPlan(
            source.metadata().snapshotId(),
            source.manifestHash(),
            sourceWindow.sourceFormatVersion(),
            sourceWindow.targetFormatVersion(),
            report.reportHash(),
            operations);
        return new UpgradeProposal(plan, report, new DiagnosticSet(diagnostics));
    }

    private static void addGraphQuarantine(ImmutableSnapshotAdapter.View source, UpgradeSourceWindow window, ImmutableSnapshotAdapter.Entry entry, List<QuarantineRecord> quarantine, List<Diagnostic> diagnostics, String reason, String action) {
        quarantine.add(quarantine(entry, source, reason, action));
        diagnostics.add(unsupportedDiagnostic(entry, source, window, reason));
    }

    private static Map<?, ?> requireObject(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Graph Document Must Be A JSON Object");
        }
        return map;
    }

    private static String requireString(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Graph " + field + " Must Be A Non-Blank String");
        }
        return text;
    }

    private List<Rule> matchingRules(String path) {
        return rules.stream().filter(rule -> rule.matches(path)).toList();
    }

    private static QuarantineRecord quarantine(ImmutableSnapshotAdapter.Entry entry, ImmutableSnapshotAdapter.View source, String reason, String action) {
        String recordId = "unsupported-" + CanonicalJson.sha256("migration.quarantine", List.of(source.manifestHash(), entry.relativePath(), entry.sha256())).substring(0, 24);
        return new QuarantineRecord(recordId, "MIGRATION.RUNTIME_LEGACY_INPUT", entry.relativePath(), reason, List.of(), action, entry.sha256());
    }

    private static Diagnostic unsupportedDiagnostic(ImmutableSnapshotAdapter.Entry entry, ImmutableSnapshotAdapter.View source, UpgradeSourceWindow window, String reason) {
        Map<String, Object> evidence = Map.of(
            "relativePath", entry.relativePath(),
            "owner", entry.owner(),
            "sourceHash", entry.sha256(),
            "manifestHash", source.manifestHash());
        String evidenceHash = CanonicalJson.sha256("migration.diagnostic-evidence", evidence);
        UUID correlation = UUID.nameUUIDFromBytes((entry.relativePath() + "\n" + entry.sha256() + "\n" + evidenceHash).getBytes(StandardCharsets.UTF_8));
        return Diagnostic.builder()
            .code("MIGRATION.RUNTIME_LEGACY_INPUT")
            .messageKey(MESSAGE_KEY)
            .arguments(Map.of("relativePath", entry.relativePath()))
            .evidence(Map.of("reason", reason, "sourceHash", entry.sha256(), "owner", entry.owner(), "manifestHash", source.manifestHash()))
            .correlationId(correlation)
            .provenance(new DiagnosticProvenance(
                OwnerId.of("restudio.resync"),
                DiagnosticSourceKind.LOCAL,
                "snapshot://" + source.metadata().snapshotId() + "/" + entry.relativePath(),
                source.manifestHash(),
                window.sourceBuild(),
                window.upgraderVersion().value(),
                null))
            .durable(true)
            .redaction(DiagnosticRedaction.TECHNICAL)
            .build();
    }

    public interface Rule {
        String adapterId();

        String sourcePath();

        String targetPath();

        String owner();

        String operationKind();

        boolean matches(String relativePath);

        String targetPath(String relativePath);
    }

    public record GraphFamily(ContractRef<ResourceTypeId> resourceType, List<GraphSource> sources) {
        public GraphFamily {
            resourceType = Objects.requireNonNull(resourceType, "resourceType");
            List<GraphSource> sorted = new ArrayList<>(sources == null ? List.of() : sources);
            sorted.sort(Comparator.comparingInt(GraphSource::precedence).thenComparing(GraphSource::sourcePrefix));
            if (sorted.isEmpty()) {
                throw new IllegalArgumentException("At Least One Graph Source Is Required");
            }
            Set<String> identities = new HashSet<>();
            for (GraphSource source : sorted) {
                if (!identities.add(source.adapterId())) {
                    throw new IllegalArgumentException("Duplicate Graph Source Adapter: " + source.adapterId());
                }
            }
            sources = List.copyOf(sorted);
        }
    }

    public record GraphSource(String adapterId, String sourcePrefix, String targetPrefix, String owner, int precedence) {
        public GraphSource {
            adapterId = requireText(adapterId, "adapterId");
            sourcePrefix = requirePath(sourcePrefix);
            targetPrefix = requirePath(targetPrefix);
            owner = requireText(owner, "owner");
            if (precedence < 0) {
                throw new IllegalArgumentException("Graph Source Precedence Must Be Non-Negative");
            }
        }

        public boolean matchesPrefix(String path) {
            return path != null && (path.equals(sourcePrefix) || path.startsWith(sourcePrefix + "/"));
        }

        public boolean matchesGraphPath(String path) {
            if (!matchesPrefix(path) || path.equals(sourcePrefix)) {
                return false;
            }
            String suffix = path.substring(sourcePrefix.length() + 1);
            return suffix.endsWith(".json") && !suffix.substring(0, suffix.length() - 5).contains("/");
        }

        public String resourceId(String path) {
            if (!matchesGraphPath(path)) {
                throw new IllegalArgumentException("Path Is Not A Graph Source File");
            }
            String suffix = path.substring(sourcePrefix.length() + 1);
            return suffix.substring(0, suffix.length() - 5);
        }

        public String targetPath(String resourceId) {
            return targetPrefix + "/" + requireText(resourceId, "resourceId") + ".json";
        }
    }

    private static String requirePath(String value) {
        if (value == null || value.isBlank() || value.indexOf('\\') >= 0 || value.indexOf('\u0000') >= 0 || value.startsWith("/")) {
            throw new IllegalArgumentException("relativePath Is Invalid");
        }
        String[] segments = value.split("/", -1);
        for (String segment : segments) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("relativePath Contains An Unsafe Segment");
            }
        }
        if (segments[0].equals(".quarantine")) {
            throw new IllegalArgumentException("relativePath Uses The Reserved Quarantine Root");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || value.indexOf('\u0000') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " Is Invalid");
        }
        return value;
    }

    public record IdentityRule(String adapterId, String relativePath, String owner) implements Rule {
        public IdentityRule {
            adapterId = requireText(adapterId, "adapterId");
            relativePath = requirePath(relativePath);
            owner = requireText(owner, "owner");
        }

        @Override
        public String sourcePath() {
            return relativePath;
        }

        @Override
        public String targetPath() {
            return relativePath;
        }

        @Override
        public String operationKind() {
            return "copy";
        }

        @Override
        public boolean matches(String path) {
            return relativePath.equals(path);
        }

        @Override
        public String targetPath(String path) {
            if (!matches(path)) {
                throw new IllegalArgumentException("Path Does Not Match Offline Rule");
            }
            return targetPath();
        }
    }

    public record DirectoryRule(String adapterId, String sourcePath, String targetPath, String owner, String operationKind) implements Rule {
        public DirectoryRule {
            adapterId = requireText(adapterId, "adapterId");
            sourcePath = requirePath(sourcePath);
            targetPath = requirePath(targetPath);
            owner = requireText(owner, "owner");
            operationKind = requireText(operationKind, "operationKind");
        }

        @Override
        public boolean matches(String path) {
            return path != null && path.startsWith(sourcePath + "/");
        }

        @Override
        public String targetPath(String path) {
            if (!matches(path)) {
                throw new IllegalArgumentException("Path Does Not Match Offline Directory Rule");
            }
            return targetPath + path.substring(sourcePath.length());
        }

    }

    private record PlannedOperation(ImmutableSnapshotAdapter.Entry entry, Rule rule) {
        private String targetPath() {
            return rule.targetPath(entry.relativePath());
        }
    }

    private record GraphCandidate(ImmutableSnapshotAdapter.Entry entry, GraphSource source, ResourceKey resource) {
        private String targetPath() {
            return source.targetPath(resource.id());
        }
    }
}

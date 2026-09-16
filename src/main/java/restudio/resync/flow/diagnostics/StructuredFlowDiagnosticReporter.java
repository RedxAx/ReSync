package restudio.resync.flow.diagnostics;

import restudio.flow.data.FlowGraph;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.contract.canonical.CanonicalHash;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.diagnostic.DiagnosticCodeCatalog;
import restudio.resync.contract.diagnostic.DurableDiagnosticReportStore;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticProvenance;
import restudio.resync.flow.diagnostic.DiagnosticRedaction;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.diagnostic.DiagnosticSourceKind;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.registry.NodeDefinitionDiagnostic;
import restudio.resync.flow.validation.FlowGraphDiagnostic;
import restudio.resync.flow.validation.FlowGraphValidationResult;
import restudio.resync.server.TemporaryLifecycleDiagnostics;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class StructuredFlowDiagnosticReporter {
    private static final OwnerId OWNER = OwnerId.of("resync");
    private static final ContractRef<OperationId> MESSAGE_KEY = ContractRef.of(OWNER, OperationId.of("flow-diagnostics"));
    private static final String ZERO_HASH = "0".repeat(64);
    private volatile DurableDiagnosticReportStore store;
    private final DiagnosticCodeCatalog catalog;
    private boolean quiesced;
    private volatile Observation observation = new Observation(0, true, false);
    private volatile Observation validatedHealth;
    private int observationChanges;

    public record Observation(long revision, boolean stable, boolean quiesced) {
    }

    public Observation observation() {
        return observation;
    }

    public boolean isCurrent(Observation expected) {
        return expected != null && expected.stable() && observation == expected;
    }

    public Observation validatedHealthObservation() {
        Observation current = observation;
        return validatedHealth == current && current.stable() && !current.quiesced() ? current : null;
    }

    public boolean isHealthCurrent(Observation expected) {
        return expected != null && expected == validatedHealthObservation();
    }

    private ObservationChange observeChange() {
        validatedHealth = null;
        observationChanges++;
        observation = new Observation(observation.revision() + 1, false, quiesced);
        return new ObservationChange();
    }

    private final class ObservationChange implements AutoCloseable {
        @Override
        public void close() {
            observationChanges--;
            observation = new Observation(observation.revision() + 1, observationChanges == 0, quiesced);
        }
    }

    public StructuredFlowDiagnosticReporter(Path directory) {
        this(new DurableDiagnosticReportStore(Objects.requireNonNull(directory, "Diagnostic report directory is required")), null);
    }

    public StructuredFlowDiagnosticReporter(Path directory, DiagnosticCodeCatalog catalog) {
        this(new DurableDiagnosticReportStore(Objects.requireNonNull(directory, "Diagnostic report directory is required")), catalog);
    }

    public StructuredFlowDiagnosticReporter(DurableDiagnosticReportStore store, DiagnosticCodeCatalog catalog) {
        this.store = Objects.requireNonNull(store, "Diagnostic report store is required");
        this.catalog = catalog;
        validatedHealth = observation;
    }

    public DurableDiagnosticReportStore store() {
        return store;
    }

    public synchronized void flush() {
    }

    public synchronized void quiesce() {
        try (ObservationChange change = observeChange()) {
            quiesced = true;
        }
    }

    public synchronized void resume() {
        try (ObservationChange change = observeChange()) {
            quiesced = false;
        }
    }

    public synchronized void rebind(Path directory) {
        try (ObservationChange change = observeChange()) {
            if (!quiesced) {
                throw new IllegalStateException("Structured flow diagnostics must be quiesced before rebind");
            }
            Path target = Objects.requireNonNull(directory, "Diagnostic report directory is required").toAbsolutePath().normalize();
            if (!target.equals(store.directory())) {
                store = new DurableDiagnosticReportStore(target);
            }
        }
    }

    public synchronized void healthCheck() {
        long started = TemporaryLifecycleDiagnostics.start();
        Observation before = observation;
        try {
            int reports = store.list().size();
            if (observation == before && before.stable()) {
                validatedHealth = before;
            }
            TemporaryLifecycleDiagnostics.event("diagnostic_report_health", started,
                Map.of("outcome", "complete", "reportCount", reports, "revision", observation.revision()));
        } catch (RuntimeException | Error failure) {
            validatedHealth = null;
            observation = new Observation(observation.revision() + 1, observationChanges == 0, quiesced);
            TemporaryLifecycleDiagnostics.event("diagnostic_report_health", started,
                Map.of("outcome", "failed", "errorType", failure.getClass().getSimpleName(), "revision", observation.revision()));
            throw failure;
        }
    }

    public DurableDiagnosticReportStore.StoredReport reportGraphValidation(FlowGraph graph, FlowGraphValidationResult result) {
        if (result == null || result.diagnostics() == null || result.diagnostics().stream().noneMatch(this::isFailure)) {
            return null;
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (FlowGraphDiagnostic diagnostic : result.diagnostics()) {
            if (isFailure(diagnostic)) {
                diagnostics.add(graphDiagnostic(graph, diagnostic));
            }
        }
        return persist(diagnostics);
    }

    public DurableDiagnosticReportStore.StoredReport reportNodeDefinitionDiagnostics(Collection<NodeDefinitionDiagnostic> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<Diagnostic> diagnostics = values.stream()
            .filter(Objects::nonNull)
            .filter(this::isFailure)
            .map(this::nodeDefinitionDiagnostic)
            .toList();
        return persist(diagnostics);
    }

    public DurableDiagnosticReportStore.StoredReport reportCatalogDiagnostics(Collection<OptionCatalogRegistry.RegistrationDiagnostic> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<Diagnostic> diagnostics = values.stream()
            .filter(Objects::nonNull)
            .map(this::catalogDiagnostic)
            .toList();
        return persist(diagnostics);
    }

    public DurableDiagnosticReportStore.StoredReport reportDiagnostics(Collection<? extends Diagnostic> diagnostics) {
        return persist(diagnostics);
    }

    public DurableDiagnosticReportStore.StoredReport reportDiagnostics(UUID reportId,
                                                                         Collection<? extends Diagnostic> diagnostics) {
        return persist(Objects.requireNonNull(reportId, "Diagnostic report ID is required"), diagnostics);
    }

    private synchronized DurableDiagnosticReportStore.StoredReport persist(Collection<? extends Diagnostic> diagnostics) {
        return persist(UUID.randomUUID(), diagnostics);
    }

    private synchronized DurableDiagnosticReportStore.StoredReport persist(UUID reportId,
                                                                             Collection<? extends Diagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return null;
        }
        if (quiesced) {
            throw new IllegalStateException("Structured flow diagnostics are quiesced");
        }
        Observation before = observation;
        boolean carriedHealth = validatedHealth == before && before.stable() && !before.quiesced();
        DurableDiagnosticReportStore.StoredReport persisted;
        try (ObservationChange change = observeChange()) {
            persisted = store.persist(reportId, diagnostics);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Structured flow diagnostics could not be durably persisted", exception);
        }
        if (carriedHealth) {
            validatedHealth = observation;
        }
        return persisted;
    }

    private Diagnostic graphDiagnostic(FlowGraph graph, FlowGraphDiagnostic diagnostic) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("legacyCode", diagnostic.code());
        evidence.put("legacyMessage", diagnostic.message());
        evidence.put("graphId", value(diagnostic.graphId(), graph == null ? "" : graph.getId()));
        evidence.put("nodeId", diagnostic.nodeId());
        evidence.put("pin", diagnostic.pin());
        if (graph != null) {
            evidence.put("resourceType", graph.getResourceType());
            evidence.put("resourceRevision", graph.getResourceRevision());
            evidence.put("graphVersion", graph.getVersion());
        }
        String code = graphCode(diagnostic.code(), diagnostic.severity());
        DiagnosticSeverity severity = diagnostic.severity() == FlowGraphDiagnostic.Severity.WARNING
            ? DiagnosticSeverity.WARNING : DiagnosticSeverity.ERROR;
        return build(code, severity, "graph-validation", evidence, "graph-validation");
    }

    private Diagnostic nodeDefinitionDiagnostic(NodeDefinitionDiagnostic diagnostic) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("legacyCode", diagnostic.code());
        evidence.put("legacyMessage", diagnostic.message());
        evidence.put("source", diagnostic.source());
        evidence.put("index", diagnostic.index());
        evidence.put("nodeId", diagnostic.nodeId());
        String code = catalogCode(diagnostic.code(), diagnostic.severity() == NodeDefinitionDiagnostic.Severity.WARNING);
        return build(code, diagnostic.severity() == NodeDefinitionDiagnostic.Severity.WARNING ? DiagnosticSeverity.WARNING : DiagnosticSeverity.ERROR,
            "catalog", evidence, "node-definition");
    }

    private Diagnostic catalogDiagnostic(OptionCatalogRegistry.RegistrationDiagnostic diagnostic) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("legacyCode", diagnostic.code());
        evidence.put("legacyMessage", diagnostic.message());
        evidence.put("sourceId", diagnostic.sourceId());
        String code = switch (diagnostic.code()) {
            case "DUPLICATE_SOURCE", "DUPLICATE_RUNTIME_DATA_ADAPTER" -> "CATALOG.IDENTITY_COLLISION";
            default -> "CATALOG.CONTRIBUTION_REJECTED";
        };
        return build(code, DiagnosticSeverity.ERROR, "catalog", evidence, "option-catalog");
    }

    private Diagnostic build(String code, DiagnosticSeverity severity, String legacyStage, Map<String, Object> evidence, String source) {
        DiagnosticCodeCatalog selected = catalog == null ? DiagnosticCodeCatalog.defaultCatalog() : catalog;
        DiagnosticCodeCatalog.Definition definition = selected.require(code);
        Map<String, Object> normalizedEvidence = Map.copyOf(evidence);
        String sourceHash = hash(normalizedEvidence);
        UUID correlationId = UUID.nameUUIDFromBytes((source + "|" + code + "|" + sourceHash).getBytes(StandardCharsets.UTF_8));
        return Diagnostic.builder(code, definition.severity(), definition.phase(), definition.stage())
            .catalog(selected)
            .messageKey(MESSAGE_KEY)
            .evidence(normalizedEvidence)
            .correlationId(correlationId)
            .provenance(new DiagnosticProvenance(OWNER, DiagnosticSourceKind.LOCAL, source, sourceHash, "legacy-flow", "resync", null))
            .durable(definition.durable())
            .redaction(DiagnosticRedaction.PUBLIC)
            .build();
    }

    private String graphCode(String legacyCode, FlowGraphDiagnostic.Severity severity) {
        if (legacyCode != null && legacyCode.startsWith("GRAPH.") && hasCode(legacyCode)) {
            return legacyCode;
        }
        if (legacyCode == null) {
            return severity == FlowGraphDiagnostic.Severity.WARNING ? "GRAPH.READ_ONLY" : "GRAPH.NULL";
        }
        String normalized = legacyCode.toUpperCase(Locale.ROOT);
        if (normalized.contains("DEFINITION") && normalized.contains("MISSING")) {
            return "GRAPH.DEFINITION_MISSING";
        }
        if (normalized.contains("DEFINITION") && (normalized.contains("VERSION") || normalized.contains("UNSUPPORTED"))) {
            return "GRAPH.DEFINITION_VERSION_MISMATCH";
        }
        if (normalized.contains("PIN") && normalized.contains("TYPE")) {
            return "GRAPH.PIN_TYPE_MISMATCH";
        }
        if (normalized.contains("PIN") && normalized.contains("CONVERSION") && normalized.contains("AMBIGUOUS")) {
            return "GRAPH.PIN_CONVERSION_AMBIGUOUS";
        }
        if (normalized.contains("PIN") && normalized.contains("CONVERSION")) {
            return "GRAPH.PIN_CONVERSION_MISSING";
        }
        if (normalized.contains("PIN")) {
            return "GRAPH.PIN_UNDECLARED";
        }
        if (normalized.contains("CONNECTION") && normalized.contains("DUPLICATE")) {
            return "GRAPH.DUPLICATE_CONNECTION";
        }
        if (normalized.contains("ENDPOINT") && normalized.contains("NODE")) {
            return "GRAPH.ENDPOINT_NODE_MISSING";
        }
        if (normalized.contains("ENDPOINT") && normalized.contains("PIN")) {
            return "GRAPH.ENDPOINT_PIN_MISSING";
        }
        if (normalized.contains("DUPLICATE") && normalized.contains("NODE")) {
            return "GRAPH.DUPLICATE_NODE";
        }
        if (normalized.contains("CYCLE")) {
            return "GRAPH.PIN_TYPE_MISMATCH";
        }
        return severity == FlowGraphDiagnostic.Severity.WARNING ? "GRAPH.READ_ONLY" : "GRAPH.DEFINITION_UNAVAILABLE";
    }

    private String catalogCode(String legacyCode, boolean warning) {
        if (legacyCode != null) {
            String normalized = legacyCode.toUpperCase(Locale.ROOT);
            if (normalized.contains("DUPLICATE") || normalized.contains("COLLISION")) {
                return "CATALOG.IDENTITY_COLLISION";
            }
            if (normalized.contains("UNAVAILABLE") || normalized.contains("MISSING")) {
                return "CATALOG.CAPABILITY_UNRESOLVED";
            }
            if (normalized.startsWith("CATALOG.") && hasCode(normalized)) {
                return normalized;
            }
        }
        return warning ? "CATALOG.PREVIEW_UNRESOLVED" : "CATALOG.CONTRIBUTION_REJECTED";
    }

    private boolean hasCode(String code) {
        return (catalog == null ? DiagnosticCodeCatalog.defaultCatalog() : catalog).contains(code);
    }

    private boolean isFailure(FlowGraphDiagnostic diagnostic) {
        return diagnostic != null && diagnostic.severity() == FlowGraphDiagnostic.Severity.ERROR;
    }

    private boolean isFailure(NodeDefinitionDiagnostic diagnostic) {
        return diagnostic != null && diagnostic.severity() == NodeDefinitionDiagnostic.Severity.ERROR;
    }

    private String hash(Map<String, Object> evidence) {
        try {
            return CanonicalHash.sha256(JsonValue.fromJava(evidence));
        } catch (RuntimeException exception) {
            return ZERO_HASH;
        }
    }

    private String value(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback == null ? "" : fallback : preferred;
    }
}

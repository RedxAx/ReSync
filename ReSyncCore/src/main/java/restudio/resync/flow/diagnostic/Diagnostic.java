package restudio.resync.flow.diagnostic;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.diagnostic.DiagnosticCodeCatalog;
import restudio.resync.contract.identity.IdentityCodec;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.LocalId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.identity.TraceId;

public record Diagnostic(
    String code,
    DiagnosticSeverity severity,
    DiagnosticPhase phase,
    String stage,
    ContractRef<? extends LocalId> messageKey,
    String message,
    Map<String, Object> arguments,
    Map<String, Object> evidence,
    String remediation,
    UUID correlationId,
    UUID traceId,
    DiagnosticContext context,
    Map<String, Object> unknown,
    boolean durable,
    DiagnosticRedaction redaction,
    DiagnosticMetricPolicy metricPolicy
) {
    public Diagnostic {
        code = DiagnosticValidation.code(code);
        DiagnosticCodeCatalog.Definition definition = DiagnosticCodeCatalog.defaultCatalog().require(code);
        severity = Objects.requireNonNull(severity, "severity");
        phase = Objects.requireNonNull(phase, "phase");
        stage = DiagnosticValidation.localId(stage, "stage");
        messageKey = Objects.requireNonNull(messageKey, "messageKey");
        message = DiagnosticValidation.text(message, "message", 512);
        arguments = DiagnosticValidation.immutableMap(arguments, "arguments");
        definition.validateArguments(arguments);
        evidence = DiagnosticValidation.immutableMap(evidence, "evidence");
        remediation = DiagnosticValidation.text(remediation, "remediation", 2048);
        correlationId = DiagnosticValidation.uuid(correlationId, "correlationId");
        traceId = DiagnosticValidation.optionalUuid(traceId, "traceId");
        context = context == null ? DiagnosticContext.empty() : context;
        unknown = DiagnosticValidation.immutableMap(unknown, "unknown");
        DiagnosticValidation.rejectUnknownCollisions(unknown, "diagnostic", Set.of(
            "code", "severity", "phase", "stage", "messageKey", "message", "arguments", "evidence", "remediation", "correlationId", "traceId",
            "serverId", "resource", "nodeId", "pinId", "ownerId", "catalogGeneration", "provenance", "location", "durable", "redaction", "metricPolicy"
        ));
        redaction = Objects.requireNonNull(redaction, "redaction");
        metricPolicy = metricPolicy == null ? DiagnosticMetricPolicy.NONE : metricPolicy;
        if (severity != definition.severity() || phase != definition.phase() || !stage.equals(definition.stage())
            || !messageKey.id().canonicalText().equals(definition.messageKey()) || !message.equals(definition.message())
            || !remediation.equals(definition.remediation()) || durable != definition.durable()) {
            throw new IllegalArgumentException("Diagnostic fields do not match the frozen definition for " + code);
        }
    }

    public Diagnostic(
        String code,
        DiagnosticSeverity severity,
        DiagnosticPhase phase,
        String stage,
        ContractRef<? extends LocalId> messageKey,
        String message,
        Map<String, Object> arguments,
        Map<String, Object> evidence,
        String remediation,
        UUID correlationId,
        UUID traceId,
        DiagnosticContext context,
        boolean durable,
        DiagnosticRedaction redaction,
        DiagnosticMetricPolicy metricPolicy
    ) {
        this(code, severity, phase, stage, messageKey, message, arguments, evidence, remediation, correlationId, traceId, context, Map.of(), durable, redaction, metricPolicy);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(String code, DiagnosticSeverity severity, DiagnosticPhase phase, String stage) {
        return new Builder().code(code).severity(severity).phase(phase).stage(stage);
    }

    public static Diagnostic fromCanonical(Map<String, ?> value) {
        Objects.requireNonNull(value, "Diagnostic Canonical Value Is Required");
        Object normalized = JsonValue.fromJava(value).toJava();
        Map<String, Object> document = map(normalized, "diagnostic");
        DiagnosticCodeCatalog.Definition definition = DiagnosticCodeCatalog.defaultCatalog().require(text(document.get("code"), "code"));
        DiagnosticSeverity severity = document.containsKey("severity")
            ? enumValue(document.get("severity"), DiagnosticSeverity.values(), DiagnosticSeverity::wireName, "severity")
            : definition.severity();
        DiagnosticPhase phase = document.containsKey("phase")
            ? enumValue(document.get("phase"), DiagnosticPhase.values(), DiagnosticPhase::wireName, "phase")
            : definition.phase();
        String stage = document.containsKey("stage") ? text(document.get("stage"), "stage") : definition.stage();
        Map<String, Object> messageKeyValue = map(document.get("messageKey"), "messageKey");
        ContractRef<CapabilityId> messageKey = IdentityCodec.decodeReference(JsonValue.fromJava(messageKeyValue), CapabilityId::new);
        String message = document.containsKey("message") ? text(document.get("message"), "message") : definition.message();
        Map<String, Object> arguments = optionalMap(document, "arguments");
        Map<String, Object> evidence = optionalMap(document, "evidence");
        String remediation = document.containsKey("remediation")
            ? text(document.get("remediation"), "remediation") : definition.remediation();
        UUID correlationId = UUID.fromString(text(document.get("correlationId"), "correlationId"));
        UUID traceId = document.containsKey("traceId") ? UUID.fromString(text(document.get("traceId"), "traceId")) : null;
        ServerId serverId = document.containsKey("serverId")
            ? ServerId.parseCanonicalText(text(document.get("serverId"), "serverId")) : null;
        ServerResourceLocator resource = document.containsKey("resource")
            ? IdentityCodec.decodeLocator(JsonValue.fromJava(map(document.get("resource"), "resource"))) : null;
        NodeId nodeId = document.containsKey("nodeId") ? new NodeId(text(document.get("nodeId"), "nodeId")) : null;
        PinId pinId = document.containsKey("pinId") ? new PinId(text(document.get("pinId"), "pinId")) : null;
        Long catalogGeneration = document.containsKey("catalogGeneration")
            ? integer(document.get("catalogGeneration"), "catalogGeneration") : null;
        DiagnosticProvenance provenance = document.containsKey("provenance")
            ? provenance(map(document.get("provenance"), "provenance")) : null;
        if (document.containsKey("ownerId")) {
            OwnerId ownerId = new OwnerId(text(document.get("ownerId"), "ownerId"));
            if (provenance == null || !ownerId.equals(provenance.ownerId())) {
                throw new IllegalArgumentException("Diagnostic ownerId must match provenance.ownerId");
            }
        }
        boolean durable = document.containsKey("durable")
            ? booleanValue(document.get("durable"), "durable") : definition.durable();
        DiagnosticRedaction redaction = document.containsKey("redaction")
            ? enumValue(document.get("redaction"), DiagnosticRedaction.values(), DiagnosticRedaction::wireName, "redaction")
            : DiagnosticRedaction.PUBLIC;
        DiagnosticMetricPolicy metricPolicy = document.containsKey("metricPolicy")
            ? enumValue(document.get("metricPolicy"), DiagnosticMetricPolicy.values(), DiagnosticMetricPolicy::wireName, "metricPolicy")
            : DiagnosticMetricPolicy.NONE;
        if (severity != definition.severity() || phase != definition.phase() || !stage.equals(definition.stage())
            || !messageKey.id().canonicalText().equals(definition.messageKey()) || !message.equals(definition.message())
            || !remediation.equals(definition.remediation()) || durable != definition.durable()) {
            throw new IllegalArgumentException("Diagnostic fields do not match the frozen definition for " + definition.code());
        }
        return Diagnostic.builder(definition.code(), severity, phase, stage)
            .messageKey(messageKey)
            .message(message)
            .arguments(arguments)
            .evidence(evidence)
            .remediation(remediation)
            .correlationId(correlationId)
            .traceId(traceId)
            .serverId(serverId)
            .resource(resource)
            .nodeId(nodeId)
            .pinId(pinId)
            .catalogGeneration(catalogGeneration)
            .provenance(provenance)
            .unknown(unknown(document))
            .durable(durable)
            .redaction(redaction)
            .metricPolicy(metricPolicy)
            .build();
    }

    public OwnerId ownerId() {
        return context.ownerId();
    }

    public CorrelationId correlationIdentity() {
        return CorrelationId.of(correlationId);
    }

    public TraceId traceIdentity() {
        return traceId == null ? null : TraceId.of(traceId);
    }

    public ServerId serverId() {
        return context.serverId();
    }

    public ServerResourceLocator resource() {
        return context.resource();
    }

    public NodeId nodeId() {
        return context.nodeId();
    }

    public PinId pinId() {
        return context.pinId();
    }

    public Long catalogGeneration() {
        return context.catalogGeneration();
    }

    public DiagnosticProvenance provenance() {
        return context.provenance();
    }

    public DiagnosticCodeCatalog.Definition definition() {
        return DiagnosticCodeCatalog.defaultCatalog().require(code);
    }

    public DiagnosticCodeCatalog.Retryability retryability() {
        return definition().retryability();
    }

    public Map<String, DiagnosticCodeCatalog.ArgumentType> argumentTypes() {
        return definition().argumentTypes();
    }

    public Map<String, Object> toMap() {
        return toMap(redaction, false);
    }

    public Map<String, Object> toRedactedMap() {
        return toRedactedMap(DiagnosticRedaction.PUBLIC);
    }

    public Map<String, Object> toRedactedMap(DiagnosticRedaction exportRedaction) {
        return toMap(Objects.requireNonNull(exportRedaction, "exportRedaction"), true);
    }

    public String toJson() {
        return DiagnosticJson.write(toMap());
    }

    public String toRedactedJson() {
        return DiagnosticJson.write(toRedactedMap());
    }

    public String toRedactedJson(DiagnosticRedaction exportRedaction) {
        return DiagnosticJson.write(toRedactedMap(exportRedaction));
    }

    private Map<String, Object> toMap(DiagnosticRedaction exportRedaction, boolean redact) {
        Map<String, Object> value = new LinkedHashMap<>();
        boolean hideDetails = redact && redaction.ordinal() > exportRedaction.ordinal();
        value.put("code", code);
        value.put("severity", severity.wireName());
        value.put("phase", phase.wireName());
        value.put("stage", stage);
        value.put("messageKey", messageKeyMap(messageKey));
        value.put("message", message);
        if (!arguments.isEmpty() && (!redact || !hideDetails)) {
            value.put("arguments", redact ? DiagnosticValidation.redactedMap(arguments, exportRedaction) : arguments);
        }
        value.put("evidence", redact && !hideDetails ? DiagnosticValidation.redactedMap(evidence, exportRedaction) : redact ? Map.of() : evidence);
        value.put("remediation", remediation);
        value.put("correlationId", correlationId.toString());
        if (traceId != null && (!redact || !hideDetails) && (!redact || exportRedaction.ordinal() < DiagnosticRedaction.SECRET.ordinal())) {
            value.put("traceId", traceId.toString());
        }
        if (context.serverId() != null) {
            value.put("serverId", context.serverId().canonicalText());
        }
        if (context.resource() != null) {
            value.put("resource", resourceMap(context.resource()));
        }
        if (context.nodeId() != null) {
            value.put("nodeId", context.nodeId().canonicalText());
        }
        if (context.pinId() != null) {
            value.put("pinId", context.pinId().canonicalText());
        }
        if (context.ownerId() != null) {
            value.put("ownerId", context.ownerId().canonicalText());
        }
        if (context.catalogGeneration() != null) {
            value.put("catalogGeneration", context.catalogGeneration());
        }
        if (context.provenance() != null) {
            value.put("provenance", redact
                ? context.provenance().toRedactedMap(exportRedaction, hideDetails)
                : context.provenance().toMap());
        }
        value.put("durable", durable);
        value.put("redaction", redaction.wireName());
        value.put("metricPolicy", metricPolicy.wireName());
        for (Map.Entry<String, Object> entry : unknown.entrySet()) {
            if (value.containsKey(entry.getKey())) {
                throw new IllegalArgumentException("Diagnostic unknown field collides with known field: " + entry.getKey());
            }
            value.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(value);
    }

    private static Map<String, Object> messageKeyMap(ContractRef<? extends LocalId> key) {
        return key.canonicalValue();
    }

    private static Map<String, Object> resourceMap(ServerResourceLocator locator) {
        return locator.canonicalValue();
    }

    private static final Set<String> KNOWN_FIELDS = Set.of(
        "code", "severity", "phase", "stage", "messageKey", "message", "arguments", "evidence", "remediation",
        "correlationId", "traceId", "serverId", "resource", "nodeId", "pinId", "ownerId", "catalogGeneration",
        "provenance", "durable", "redaction", "metricPolicy"
    );

    private static Map<String, Object> unknown(Map<String, Object> value) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> {
            if (!KNOWN_FIELDS.contains(key)) {
                result.put(key, item);
            }
        });
        return result;
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

    private static Map<String, Object> optionalMap(Map<String, Object> value, String field) {
        return value.containsKey(field) ? map(value.get(field), field) : Map.of();
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

    private static long integer(Object value, String field) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        try {
            return new BigDecimal(number.toString()).longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(field + " must be an integer", exception);
        }
    }

    private static DiagnosticProvenance provenance(Map<String, Object> value) {
        return new DiagnosticProvenance(
            value.containsKey("ownerId") ? new OwnerId(text(value.get("ownerId"), "provenance.ownerId")) : null,
            enumValue(value.get("sourceKind"), DiagnosticSourceKind.values(), DiagnosticSourceKind::wireName, "provenance.sourceKind"),
            text(value.get("sourceUri"), "provenance.sourceUri"),
            text(value.get("sourceHash"), "provenance.sourceHash"),
            text(value.get("sourceVersion"), "provenance.sourceVersion"),
            text(value.get("buildId"), "provenance.buildId"),
            value.containsKey("loadedAt") ? Instant.parse(text(value.get("loadedAt"), "provenance.loadedAt")) : null,
            unknown(value, Set.of("ownerId", "sourceKind", "sourceUri", "sourceHash", "sourceVersion", "buildId", "loadedAt"))
        );
    }

    private static Map<String, Object> unknown(Map<String, Object> value, Set<String> known) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> {
            if (!known.contains(key)) {
                result.put(key, item);
            }
        });
        return result;
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

    public static final class Builder {
        private String code;
        private DiagnosticSeverity severity;
        private DiagnosticPhase phase;
        private String stage;
        private ContractRef<? extends LocalId> messageKey;
        private String message;
        private Map<String, Object> arguments = Map.of();
        private Map<String, Object> evidence = Map.of();
        private String remediation;
        private UUID correlationId;
        private UUID traceId;
        private ServerId serverId;
        private ServerResourceLocator resource;
        private NodeId nodeId;
        private PinId pinId;
        private Long catalogGeneration;
        private DiagnosticProvenance provenance;
        private Map<String, Object> unknown = Map.of();
        private boolean durable;
        private DiagnosticRedaction redaction = DiagnosticRedaction.PUBLIC;
        private DiagnosticMetricPolicy metricPolicy = DiagnosticMetricPolicy.NONE;
        private DiagnosticCodeCatalog catalog;

        public Builder code(String value) {
            code = value;
            return this;
        }

        public Builder severity(DiagnosticSeverity value) {
            severity = value;
            return this;
        }

        public Builder phase(DiagnosticPhase value) {
            phase = value;
            return this;
        }

        public Builder stage(String value) {
            stage = value;
            return this;
        }

        public Builder messageKey(ContractRef<? extends LocalId> value) {
            messageKey = value;
            return this;
        }

        public Builder message(String value) {
            message = value;
            return this;
        }

        public Builder arguments(Map<String, ?> value) {
            arguments = DiagnosticValidation.immutableMap(value, "arguments");
            return this;
        }

        public Builder evidence(Map<String, ?> value) {
            evidence = DiagnosticValidation.immutableMap(value, "evidence");
            return this;
        }

        public Builder remediation(String value) {
            remediation = value;
            return this;
        }

        public Builder correlationId(UUID value) {
            correlationId = value;
            return this;
        }

        public Builder correlationId(CorrelationId value) {
            return correlationId(Objects.requireNonNull(value, "correlationId").value());
        }

        public Builder traceId(UUID value) {
            traceId = value;
            return this;
        }

        public Builder traceId(TraceId value) {
            return traceId(value == null ? null : value.value());
        }

        public Builder serverId(ServerId value) {
            serverId = value;
            return this;
        }

        public Builder resource(ServerResourceLocator value) {
            resource = value;
            return this;
        }

        public Builder nodeId(NodeId value) {
            nodeId = value;
            return this;
        }

        public Builder pinId(PinId value) {
            pinId = value;
            return this;
        }

        public Builder catalogGeneration(Long value) {
            catalogGeneration = value;
            return this;
        }

        public Builder catalogGeneration(long value) {
            return catalogGeneration(Long.valueOf(value));
        }

        public Builder provenance(DiagnosticProvenance value) {
            provenance = value;
            return this;
        }

        public Builder unknown(Map<String, ?> value) {
            unknown = DiagnosticValidation.immutableMap(value, "unknown");
            return this;
        }

        public Builder catalog(DiagnosticCodeCatalog value) {
            catalog = value;
            return this;
        }

        public Builder context(DiagnosticContext value) {
            DiagnosticContext normalized = value == null ? DiagnosticContext.empty() : value;
            serverId = normalized.serverId();
            resource = normalized.resource();
            nodeId = normalized.nodeId();
            pinId = normalized.pinId();
            catalogGeneration = normalized.catalogGeneration();
            provenance = normalized.provenance();
            return this;
        }

        public Builder durable(boolean value) {
            durable = value;
            return this;
        }

        public Builder redaction(DiagnosticRedaction value) {
            redaction = value;
            return this;
        }

        public Builder metricPolicy(DiagnosticMetricPolicy value) {
            metricPolicy = value;
            return this;
        }

        public Diagnostic build() {
            DiagnosticCodeCatalog selectedCatalog = catalog == null ? DiagnosticCodeCatalog.defaultCatalog() : catalog;
            if (!selectedCatalog.authoritative()) {
                throw new IllegalArgumentException("Diagnostic construction requires the frozen generation-1 catalog");
            }
            DiagnosticCodeCatalog.Definition definition = selectedCatalog.require(DiagnosticValidation.code(code));
            ContractRef<? extends LocalId> selectedMessageKey = Objects.requireNonNull(messageKey, "messageKey");
            return new Diagnostic(
                code,
                definition.severity(),
                definition.phase(),
                definition.stage(),
                new ContractRef<>(selectedMessageKey.owner(), new CapabilityId(definition.messageKey()), selectedMessageKey.unknown()),
                definition.message(),
                arguments,
                evidence,
                definition.remediation(),
                correlationId,
                traceId,
                new DiagnosticContext(serverId, resource, nodeId, pinId, catalogGeneration, provenance),
                unknown,
                definition.durable(),
                redaction,
                metricPolicy
            );
        }
    }
}

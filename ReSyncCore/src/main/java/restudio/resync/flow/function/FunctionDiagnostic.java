package restudio.resync.flow.function;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.diagnostic.DiagnosticCodeCatalog;
import restudio.resync.contract.identity.IdentityCodec;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticContext;
import restudio.resync.flow.diagnostic.DiagnosticMetricPolicy;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticRedaction;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ServerResourceLocator;

public final class FunctionDiagnostic {
    private static final OwnerId OWNER = new OwnerId("restudio.resync");

    private final Diagnostic diagnostic;
    private final FunctionLocator function;
    private final FunctionRevision revision;
    private final FunctionParameterId parameterId;

    private FunctionDiagnostic(Diagnostic diagnostic, FunctionLocator function, FunctionRevision revision,
                               FunctionParameterId parameterId) {
        this.diagnostic = Objects.requireNonNull(diagnostic, "Function Diagnostic Shared Diagnostic Is Required");
        this.function = function;
        this.revision = revision;
        this.parameterId = parameterId;
    }

    public FunctionDiagnostic(String code, Severity severity, Phase phase, String stage, String message,
                              String remediation, FunctionLocator function, FunctionRevision revision,
                              FunctionParameterId parameterId, UUID correlationId, UUID traceId,
                              Map<String, Object> arguments, Map<String, Object> evidence, boolean durable) {
        this(shared(code, stage, message, remediation, function, revision, parameterId, correlationId, traceId,
            arguments, evidence), function, revision, parameterId);
    }

    public FunctionDiagnostic(String code, Severity severity, Phase phase, String stage, String message,
                              String remediation, FunctionLocator function, FunctionRevision revision,
                              FunctionParameterId parameterId) {
        this(code, severity, phase, stage, message, remediation, function, revision, parameterId,
            null, null, Map.of(), Map.of(), false);
    }

    public static FunctionDiagnostic error(String code, String stage, String message, FunctionParameterId parameterId) {
        return new FunctionDiagnostic(code, Severity.ERROR, Phase.SEMANTIC, stage, message,
            "Align the function invocation with its declared typed contract.", null, null, parameterId);
    }

    public static FunctionDiagnostic fromCanonical(Map<String, ?> value) {
        Objects.requireNonNull(value, "Function Diagnostic Canonical Value Is Required");
        Map<String, Object> canonical = canonicalMap(value, "Function Diagnostic");
        String code = text(canonical.get("code"), "code");
        DiagnosticCodeCatalog.Definition definition = DiagnosticCodeCatalog.defaultCatalog().require(code);
        FunctionLocator function = function(canonical);
        FunctionRevision revision = revision(canonical);
        FunctionParameterId parameterId = parameter(canonical);
        Map<String, Object> arguments = map(canonical.get("arguments"), "arguments");
        String message = canonical.get("message") instanceof String text ? text : definition.message();
        if (!message.equals(definition.message()) && !arguments.containsKey("detail")) {
            LinkedHashMap<String, Object> enriched = new LinkedHashMap<>(arguments);
            enriched.put("detail", message);
            arguments = enriched;
        }
        Map<String, Object> sharedValue = new LinkedHashMap<>(canonical);
        sharedValue.remove("function");
        sharedValue.remove("revision");
        sharedValue.remove("parameterId");
        sharedValue.put("severity", definition.severity().wireName());
        sharedValue.put("phase", definition.phase().wireName());
        sharedValue.put("stage", definition.stage());
        sharedValue.put("message", definition.message());
        sharedValue.put("remediation", definition.remediation());
        sharedValue.put("durable", definition.durable());
        sharedValue.put("arguments", arguments);
        sharedValue.putIfAbsent("evidence", Map.of());
        if (function != null) {
            sharedValue.putIfAbsent("serverId", function.serverId().canonicalText());
            sharedValue.putIfAbsent("resource", function.resource().canonicalValue());
        }
        sharedValue.putIfAbsent("messageKey", messageKey(definition, canonical));
        sharedValue.putIfAbsent("correlationId", correlation(code, function, revision, parameterId,
            arguments, map(sharedValue.get("evidence"), "evidence")).toString());
        sharedValue.putIfAbsent("redaction", DiagnosticRedaction.PUBLIC.wireName());
        sharedValue.putIfAbsent("metricPolicy", DiagnosticMetricPolicy.NONE.wireName());
        Diagnostic shared = Diagnostic.fromCanonical(sharedValue);
        return new FunctionDiagnostic(shared, function, revision, parameterId);
    }

    public Diagnostic diagnostic() {
        return diagnostic;
    }

    public Diagnostic diagnosticContract() {
        return diagnostic;
    }

    public Diagnostic toSharedDiagnostic() {
        return diagnostic;
    }

    public String code() {
        return diagnostic.code();
    }

    public Severity severity() {
        return Severity.from(diagnostic.severity());
    }

    public Phase phase() {
        return Phase.from(diagnostic.phase());
    }

    public String stage() {
        return diagnostic.stage();
    }

    public String message() {
        return diagnostic.message();
    }

    public String remediation() {
        return diagnostic.remediation();
    }

    public FunctionLocator function() {
        return function;
    }

    public FunctionRevision revision() {
        return revision;
    }

    public FunctionParameterId parameterId() {
        return parameterId;
    }

    public UUID correlationId() {
        return diagnostic.correlationId();
    }

    public UUID traceId() {
        return diagnostic.traceId();
    }

    public Map<String, Object> arguments() {
        return diagnostic.arguments();
    }

    public Map<String, Object> evidence() {
        return diagnostic.evidence();
    }

    public boolean durable() {
        return diagnostic.durable();
    }

    public DiagnosticCodeCatalog.Retryability retryability() {
        return diagnostic.retryability();
    }

    public Map<String, DiagnosticCodeCatalog.ArgumentType> argumentTypes() {
        return diagnostic.argumentTypes();
    }

    public Map<String, Object> canonicalValue() {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>(diagnostic.toMap());
        if (function != null) {
            value.put("function", function.canonicalValue());
        }
        if (revision != null) {
            value.put("revision", revision.value());
        }
        if (parameterId != null) {
            value.put("parameterId", parameterId.canonicalText());
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    public String canonicalJson() {
        return FunctionContractSupport.canonicalJson(canonicalValue());
    }

    public Map<String, Object> toMap() {
        return canonicalValue();
    }

    private static Diagnostic shared(String code, String stage, String message, String remediation,
                                    FunctionLocator function, FunctionRevision revision,
                                    FunctionParameterId parameterId, UUID correlationId, UUID traceId,
                                    Map<String, Object> arguments, Map<String, Object> evidence) {
        Map<String, Object> normalizedArguments = FunctionContractSupport.immutableMap(arguments, "Function Diagnostic Arguments");
        DiagnosticCodeCatalog.Definition definition = DiagnosticCodeCatalog.defaultCatalog().require(code);
        if (message != null && !message.equals(definition.message()) && !normalizedArguments.containsKey("detail")) {
            LinkedHashMap<String, Object> enriched = new LinkedHashMap<>(normalizedArguments);
            enriched.put("detail", message);
            normalizedArguments = enriched;
        }
        return buildShared(code, function, revision, parameterId, correlationId, traceId,
            normalizedArguments, FunctionContractSupport.immutableMap(evidence, "Function Diagnostic Evidence"), Map.of());
    }

    private static Diagnostic buildShared(String code, FunctionLocator function, FunctionRevision revision,
                                           FunctionParameterId parameterId, UUID correlationId, UUID traceId,
                                           Map<String, Object> arguments, Map<String, Object> evidence,
                                           Map<String, Object> unknown) {
        DiagnosticCodeCatalog catalog = DiagnosticCodeCatalog.defaultCatalog();
        DiagnosticCodeCatalog.Definition definition = catalog.require(code);
        UUID normalizedCorrelation = correlationId == null ? correlation(code, function, revision, parameterId, arguments, evidence) : correlationId;
        Diagnostic.Builder builder = Diagnostic.builder(code, definition.severity(), definition.phase(), definition.stage())
            .messageKey(new ContractRef<>(OWNER, new CapabilityId(definition.messageKey())))
            .message(definition.message())
            .arguments(arguments)
            .evidence(evidence)
            .remediation(definition.remediation())
            .correlationId(normalizedCorrelation)
            .traceId(traceId)
            .unknown(unknown)
            .durable(definition.durable())
            .redaction(DiagnosticRedaction.PUBLIC)
            .metricPolicy(DiagnosticMetricPolicy.NONE);
        if (function != null) {
            builder.context(new DiagnosticContext(function.serverId(), function.resource(), null, null, null, null));
        }
        return builder.build();
    }

    private static UUID correlation(String code, FunctionLocator function, FunctionRevision revision,
                                    FunctionParameterId parameterId, Map<String, Object> arguments,
                                    Map<String, Object> evidence) {
        return UUID.nameUUIDFromBytes(FunctionContractSupport.canonicalJson(Map.of(
            "code", code,
            "function", function == null ? "" : function.canonicalText(),
            "revision", revision == null ? "" : revision.value(),
            "parameterId", parameterId == null ? "" : parameterId.canonicalText(),
            "arguments", arguments,
            "evidence", evidence)).getBytes(StandardCharsets.UTF_8));
    }

    private static FunctionLocator function(Map<String, ?> value) {
        Object raw = value.get("function");
        if (raw instanceof String text) {
            return FunctionLocator.parseCanonicalText(text);
        }
        if (raw instanceof Map<?, ?> map) {
            return FunctionLocator.of(IdentityCodec.decodeLocator(JsonValue.fromJava(stringMap(map, "function"))));
        }
        Object resource = value.get("resource");
        if (resource instanceof Map<?, ?> map) {
            return FunctionLocator.of(IdentityCodec.decodeLocator(JsonValue.fromJava(stringMap(map, "resource"))));
        }
        return null;
    }

    private static FunctionRevision revision(Map<String, ?> value) {
        Object raw = value.get("revision");
        if (raw instanceof Number number) {
            return new FunctionRevision(new BigDecimal(number.toString()).longValueExact());
        }
        if (raw instanceof String text) {
            return FunctionRevision.parseCanonicalText(text);
        }
        return null;
    }

    private static FunctionParameterId parameter(Map<String, ?> value) {
        Object raw = value.get("parameterId");
        return raw instanceof String text ? FunctionParameterId.parseCanonicalText(text) : null;
    }

    private static Map<String, Object> messageKey(DiagnosticCodeCatalog.Definition definition, Map<String, Object> value) {
        Object raw = value.get("messageKey");
        if (!(raw instanceof Map<?, ?> source)) {
            return new ContractRef<>(OWNER, new CapabilityId(definition.messageKey())).canonicalValue();
        }
        Map<String, Object> reference = stringMap(source, "messageKey");
        reference.put("localId", definition.messageKey());
        reference.putIfAbsent("ownerId", OWNER.canonicalText());
        return reference;
    }

    private static Map<String, Object> canonicalMap(Map<String, ?> value, String field) {
        Object normalized = JsonValue.fromJava(value).toJava();
        return stringMap((Map<?, ?>) normalized, field);
    }

    private static Map<String, Object> stringMap(Map<?, ?> value, String field) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException(field + " keys must be text");
            }
            result.put(text, item);
        });
        return result;
    }

    private static String text(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(field + " must be nonblank text");
        }
        return text;
    }

    private static Map<String, Object> map(Object value, String field) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> source)) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException(field + " keys must be text");
            }
            result.put(text, item);
        });
        return result;
    }

    public enum Severity {
        INFO("info"),
        WARNING("warning"),
        ERROR("error");

        private final String wireName;

        Severity(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        private static Severity from(DiagnosticSeverity value) {
            return valueOf(value.name());
        }
    }

    public enum Phase {
        SYNTACTIC("syntactic"),
        SEMANTIC("semantic"),
        CAPABILITY("capability"),
        ENVIRONMENT("environment");

        private final String wireName;

        Phase(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        private static Phase from(DiagnosticPhase value) {
            return valueOf(value.name());
        }
    }
}

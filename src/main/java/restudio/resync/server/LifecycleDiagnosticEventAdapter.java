package restudio.resync.server;

import restudio.resync.contract.identity.Revision;
import restudio.resync.diagnostics.DiagnosticEvent;
import restudio.resync.diagnostics.DiagnosticIdentity;
import restudio.resync.diagnostics.DiagnosticSink;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.LocalId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceKey;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.identity.TraceId;

import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

final class LifecycleDiagnosticEventAdapter {
    private static final OwnerId RESYNC_OWNER = new OwnerId("restudio.resync");

    private LifecycleDiagnosticEventAdapter() {
    }

    static DiagnosticEvent event(String stage, long elapsedMillis, String defaultServerId,
                                 Map<String, ?> values, boolean terminal) {
        Map<String, Object> rawValues = values == null ? Map.of() : new LinkedHashMap<>(values);
        DiagnosticIdentity identity = identity(defaultServerId, rawValues);
        Map<String, Object> fields = LifecycleDiagnosticPolicy.sanitizeValues(values);
        DiagnosticSink.Priority priority = LifecycleDiagnosticPolicy.priority(stage, fields, terminal);
        if (elapsedMillis >= 250L && !priority.atLeast(DiagnosticSink.Priority.IMPORTANT)) {
            priority = DiagnosticSink.Priority.IMPORTANT;
        }
        return DiagnosticEvent.of(stage, priority, identity, fields, Math.max(0L, elapsedMillis));
    }

    private static DiagnosticIdentity identity(String defaultServerId, Map<String, ?> values) {
        ServerId serverId = serverId(values.get("serverId"));
        if (serverId == null) {
            serverId = serverId(defaultServerId);
        }
        ServerResourceLocator resource = resource(values.get("typedKey"), serverId);
        if (resource == null) {
            resource = resource(serverId, values.get("resourceType"), values.get("resourceId"));
        }
        if (serverId == null && resource != null) {
            serverId = resource.serverId();
        }
        return new DiagnosticIdentity(
            serverId,
            resource,
            operation(values.get("operation")),
            uuid(values.get("requestId")),
            correlation(values.get("correlationId")),
            trace(values.get("traceId")),
            uuid(values.get("mutationId")),
            nonNegativeLong(values.get("generation")),
            nonNegativeLong(values.get("authorityEpoch")),
            revision(values.get("revision"))
        );
    }

    private static ServerId serverId(Object value) {
        if (value instanceof ServerId id) {
            return id;
        }
        if (value instanceof UUID uuid) {
            return safe(() -> new ServerId(uuid));
        }
        if (value instanceof String text && !text.isBlank()) {
            return safe(() -> ServerId.parseCanonicalText(text));
        }
        return null;
    }

    private static ServerResourceLocator resource(Object value, ServerId defaultServerId) {
        if (value instanceof ServerResourceLocator locator) {
            return locator;
        }
        if (value instanceof ResourceKey key && defaultServerId != null) {
            return safe(() -> new ServerResourceLocator(defaultServerId, key));
        }
        if (value instanceof String text && !text.isBlank()) {
            ServerResourceLocator locator = safe(() -> ServerResourceLocator.parseCanonicalText(text));
            if (locator != null) {
                return locator;
            }
            if (defaultServerId != null) {
                ResourceKey key = safe(() -> ResourceKey.parseCanonicalText(text));
                if (key != null) {
                    return safe(() -> new ServerResourceLocator(defaultServerId, key));
                }
                int separator = text.indexOf(':');
                if (separator > 0 && separator == text.lastIndexOf(':') && separator < text.length() - 1) {
                    String type = text.substring(0, separator);
                    String id = text.substring(separator + 1);
                    return safe(() -> new ServerResourceLocator(defaultServerId,
                        new ContractRef<>(RESYNC_OWNER, new ResourceTypeId(type)), id));
                }
            }
        }
        return null;
    }

    private static ServerResourceLocator resource(ServerId serverId, Object typeValue, Object idValue) {
        if (serverId == null || typeValue == null || idValue == null) {
            return null;
        }
        String typeText = text(typeValue);
        String idText = text(idValue);
        if (typeText.isBlank() || idText.isBlank()) {
            return null;
        }
        return safe(() -> {
            ContractRef<ResourceTypeId> type;
            if (typeValue instanceof ContractRef<?> reference && reference.id() instanceof ResourceTypeId resourceTypeId) {
                type = new ContractRef<>(reference.owner(), resourceTypeId, reference.unknown());
            } else if (typeText.indexOf('/') > 0) {
                type = ContractRef.parseCanonicalText(typeText, ResourceTypeId::new);
            } else {
                type = new ContractRef<>(RESYNC_OWNER, new ResourceTypeId(typeText));
            }
            return new ServerResourceLocator(serverId, type, idText);
        });
    }

    private static ContractRef<OperationId> operation(Object value) {
        if (value instanceof ContractRef<?> reference && reference.id() instanceof OperationId operationId) {
            return new ContractRef<>(reference.owner(), operationId, reference.unknown());
        }
        String text = text(value);
        if (text.isBlank()) {
            return null;
        }
        return safe(() -> {
            if (text.indexOf('/') > 0) {
                return ContractRef.parseCanonicalText(text, OperationId::new);
            }
            return new ContractRef<>(RESYNC_OWNER, new OperationId(text.toLowerCase(Locale.ROOT)));
        });
    }

    private static CorrelationId correlation(Object value) {
        if (value instanceof CorrelationId id) {
            return id;
        }
        UUID uuid = uuid(value);
        return uuid == null ? null : safe(() -> CorrelationId.of(uuid));
    }

    private static TraceId trace(Object value) {
        if (value instanceof TraceId id) {
            return id;
        }
        UUID uuid = uuid(value);
        return uuid == null ? null : safe(() -> TraceId.of(uuid));
    }

    private static UUID uuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof CorrelationId id) {
            return id.value();
        }
        if (value instanceof TraceId id) {
            return id.value();
        }
        if (value instanceof String text && !text.isBlank()) {
            return safe(() -> UUID.fromString(text));
        }
        return null;
    }

    private static Revision revision(Object value) {
        if (value instanceof Revision revision) {
            return revision;
        }
        Long number = nonNegativeLong(value);
        return number == null ? null : safe(() -> Revision.of(number));
    }

    private static Long nonNegativeLong(Object value) {
        if (value instanceof Revision revision) {
            return revision.value();
        }
        if (value instanceof Number number) {
            long valueAsLong = number.longValue();
            return number.doubleValue() == valueAsLong && valueAsLong >= 0L ? valueAsLong : null;
        }
        if (value instanceof String text && !text.isBlank()) {
            return safe(() -> {
                long parsed = Long.parseLong(text);
                return parsed < 0L ? null : parsed;
            });
        }
        return null;
    }

    private static String text(Object value) {
        if (value instanceof LocalId id) {
            return id.canonicalText();
        }
        if (value instanceof ResourceKey key) {
            return key.canonicalText();
        }
        if (value == null) {
            return "";
        }
        return value.toString().strip();
    }

    private static <T> T safe(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}

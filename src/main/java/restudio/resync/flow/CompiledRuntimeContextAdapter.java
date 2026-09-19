package restudio.resync.flow;

import org.bukkit.entity.Player;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.Event;
import org.bukkit.event.Cancellable;
import restudio.flow.data.FlowJobReference;
import restudio.flow.data.FlowOperationResult;
import restudio.flow.data.FlowResourceReference;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.runtime.CompiledRuntimeContext;
import restudio.resync.flow.runtime.RuntimePrincipal;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class CompiledRuntimeContextAdapter {
    private static final int MAX_TRANSPORT_DEPTH = 32;
    private static final int MAX_TRANSPORT_VALUES = 8_192;
    private static final String TRANSPORT_CYCLE = "RUNTIME_CONTEXT_VALUE_CYCLE";
    private static final String TRANSPORT_DEPTH = "RUNTIME_CONTEXT_VALUE_DEPTH_EXCEEDED";
    private static final String TRANSPORT_BUDGET = "RUNTIME_CONTEXT_VALUE_BUDGET_EXCEEDED";
    private static final TypeExpr ANY = TypeExpr.named(TypeReference.of("builtin", "any"));
    private static final TypeExpr STRING = TypeExpr.named(TypeReference.of("builtin", "string"));
    private static final TypeExpr BOOLEAN = TypeExpr.named(TypeReference.of("builtin", "boolean"));
    private static final TypeExpr INTEGER = TypeExpr.named(TypeReference.of("builtin", "integer"));
    private static final TypeExpr NUMBER = TypeExpr.named(TypeReference.of("builtin", "number"));
    private static final TypeExpr UUID_TYPE = TypeExpr.named(TypeReference.of("builtin", "uuid"));
    private static final TypeExpr JOB_REFERENCE = TypeExpr.named(TypeReference.of("builtin", "job_reference"));
    private static final TypeExpr RESULT = TypeExpr.named(TypeReference.of("builtin", "result"));

    private CompiledRuntimeContextAdapter() {
    }

    public static Result adapt(Player player, Event event, Map<String, Object> eventVariables) {
        return adapt(null, player, event, eventVariables);
    }

    public static Result adapt(ServerId serverId, Player player, Event event, Map<String, Object> eventVariables) {
        return adapt(serverId, null, player, event, eventVariables);
    }

    public static Result adapt(ServerId serverId, RuntimePrincipal principal, Player player, Event event,
                               Map<String, Object> eventVariables) {
        try {
            Objects.requireNonNull(eventVariables, "Event Variables Are Required");
            CompiledRuntimeContext.PlayerIdentity playerIdentity = player == null ? null : new CompiledRuntimeContext.PlayerIdentity(
                Objects.requireNonNull(player.getUniqueId(), "Player UUID Is Required"), player.getName());
            TransportTraversal transport = new TransportTraversal(serverId);
            transport.requireContainerSize(eventVariables.size());
            Map<String, TypedValue> variables = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : eventVariables.entrySet()) {
                String key = canonicalKey(entry.getKey(), "Event Variable Key");
                Object value = entry.getValue();
                if ("event.player".equals(key)) {
                    if (value == null) {
                        continue;
                    }
                    if (!(value instanceof Player eventPlayer) || playerIdentity == null
                        || !playerIdentity.uniqueId().equals(eventPlayer.getUniqueId())) {
                        throw new IllegalArgumentException("event.player must match the canonical player identity");
                    }
                }
                variables.put(key, encode(value, serverId, transport));
            }
            if (player != null && !variables.containsKey("event.player")) {
                variables.put("event.player", encode(player, serverId, transport));
            }
            Map<String, TypedValue> eventAttributes = new LinkedHashMap<>(variables);
            if (event != null) {
                eventAttributes.put("cancelled", TypedValue.value(BOOLEAN, event instanceof Cancellable cancellable && cancellable.isCancelled()));
            }
            CompiledRuntimeContext.EventIdentity eventIdentity = event == null ? null
                : new CompiledRuntimeContext.EventIdentity(event.getClass().getName(), eventAttributes);
            CompiledRuntimeContext context = new CompiledRuntimeContext(playerIdentity, eventIdentity, variables, principal);
            if (!context.supportedByCompiledCore()) {
                return Result.rejected(context.unsupportedReason());
            }
            return Result.accepted(context);
        } catch (RuntimeException failure) {
            return Result.rejected(failure.getMessage() == null ? failure.getClass().getName() : failure.getMessage());
        }
    }

    private static TypedValue encode(Object value, ServerId serverId, TransportTraversal transport) {
        transport.claimValue();
        if (value == null) {
            return TypedValue.nullValue(ANY);
        }
        if (value instanceof TypedValue typedValue) {
            if (serverId == null && TypedResourceReferenceBoundary.containsResource(typedValue.type())) {
                throw new IllegalArgumentException("RESOURCE_REFERENCE_SERVER_REQUIRED");
            }
            if (serverId != null) {
                typedValue = TypedResourceReferenceBoundary.requireValue(typedValue.type(), typedValue, serverId);
            }
            CompiledRuntimeValueCodec.decode(serverId, typedValue);
            CompiledRuntimeContext context = new CompiledRuntimeContext(null, null, Map.of("value", typedValue));
            if (!context.supportedByCompiledCore()) {
                throw new IllegalArgumentException("Typed runtime context value is unsupported");
            }
            return typedValue;
        }
        TypeExpr runtimeType = CompiledRuntimeValueCodec.runtimeType(value);
        if (runtimeType != null) {
            return CompiledRuntimeValueCodec.encode(serverId, runtimeType, value);
        }
        if (value instanceof String string) {
            return TypedValue.value(STRING, string);
        }
        if (value instanceof Boolean bool) {
            return TypedValue.value(BOOLEAN, bool);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
            || value instanceof BigInteger) {
            return TypedValue.value(INTEGER, value);
        }
        if (value instanceof Float floatValue) {
            return number(BigDecimal.valueOf(floatValue.doubleValue()), Float.isFinite(floatValue));
        }
        if (value instanceof Double doubleValue) {
            return number(BigDecimal.valueOf(doubleValue), Double.isFinite(doubleValue));
        }
        if (value instanceof BigDecimal decimal) {
            return TypedValue.value(NUMBER, decimal);
        }
        if (value instanceof UUID uuid) {
            return TypedValue.value(UUID_TYPE, uuid);
        }
        if (value instanceof Character character) {
            return TypedValue.value(STRING, character.toString());
        }
        if (value instanceof Enum<?> enumValue) {
            return TypedValue.value(STRING, enumValue.name());
        }
        if (value instanceof ServerResourceLocator locator) {
            requireServer(locator, serverId);
            return TypedValue.value(ANY, locator);
        }
        if (value instanceof FlowResourceReference legacy) {
            return TypedValue.value(ANY, TypedResourceReferenceBoundary.requireLocator(legacy, requireServerId(serverId)));
        }
        if (value instanceof FlowJobReference<?> job) {
            return TypedValue.value(JOB_REFERENCE, transport.convertClaimed(job, 0));
        }
        if (value instanceof FlowOperationResult<?> result) {
            return TypedValue.value(RESULT, transport.convertClaimed(result, 0));
        }
        return TypedValue.value(ANY, transport.convertClaimed(value, 0));
    }

    private static TypedValue number(BigDecimal value, boolean finite) {
        if (!finite) {
            throw new IllegalArgumentException("Non-finite runtime number is unsupported");
        }
        return TypedValue.value(NUMBER, value);
    }

    private static final class TransportTraversal {
        private final ServerId serverId;
        private final IdentityHashMap<Object, Boolean> active = new IdentityHashMap<>();
        private int values;

        private TransportTraversal(ServerId serverId) {
            this.serverId = serverId;
        }

        private void claimValue() {
            if (values >= MAX_TRANSPORT_VALUES) {
                throw new IllegalArgumentException(TRANSPORT_BUDGET);
            }
            values++;
        }

        private void requireContainerSize(int size) {
            if (size < 0 || size > MAX_TRANSPORT_VALUES - values) {
                throw new IllegalArgumentException(TRANSPORT_BUDGET);
            }
        }

        private Object convert(Object value, int depth) {
            claimValue();
            return convertClaimed(value, depth);
        }

        private Object convertClaimed(Object value, int depth) {
            if (depth > MAX_TRANSPORT_DEPTH) {
                throw new IllegalArgumentException(TRANSPORT_DEPTH);
            }
            if (value == null || value instanceof String || value instanceof Boolean || value instanceof UUID
                || value instanceof BigInteger || value instanceof BigDecimal
                || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                return value;
            }
            if (value instanceof ServerResourceLocator locator) {
                requireServer(locator, serverId);
                return locator;
            }
            if (value instanceof FlowResourceReference legacy) {
                return TypedResourceReferenceBoundary.requireLocator(legacy, requireServerId(serverId));
            }
            if (value instanceof Float floatValue) {
                return finiteNumber(floatValue.doubleValue(), Float.isFinite(floatValue));
            }
            if (value instanceof Double doubleValue) {
                return finiteNumber(doubleValue, Double.isFinite(doubleValue));
            }
            if (value instanceof Character character) {
                return character.toString();
            }
            if (value instanceof Enum<?> enumValue) {
                return enumValue.name();
            }
            if (value instanceof TypedValue typedValue) {
                throw new IllegalArgumentException("Runtime Value Requires An Explicit Host Type");
            }
            TypeExpr runtimeType = CompiledRuntimeValueCodec.runtimeType(value);
            if (runtimeType != null) {
                throw new IllegalArgumentException("Runtime Value Requires An Explicit Host Type");
            }
            if (value instanceof Chunk chunk) {
                return expand(value, chunkValue(chunk), depth);
            }
            if (value instanceof FlowJobReference<?> job) {
                return expand(value, jobValue(job), depth);
            }
            if (value instanceof FlowOperationResult<?> result) {
                return expand(value, resultValue(result), depth);
            }
            if (value instanceof java.time.Instant instant) {
                return instant.toString();
            }
            if (value instanceof Optional<?> optional) {
                enter(value);
                try {
                    return optional.isPresent()
                        ? Map.of("present", true, "value", convert(optional.get(), depth + 1))
                        : Map.of("present", false);
                } finally {
                    exit(value);
                }
            }
            if (value instanceof Map<?, ?> map) {
                enter(value);
                try {
                    requireContainerSize(map.size());
                    List<String> keys = new ArrayList<>(map.size());
                    for (Object key : map.keySet()) {
                        if (keys.size() >= MAX_TRANSPORT_VALUES - values) {
                            throw new IllegalArgumentException(TRANSPORT_BUDGET);
                        }
                        if (!(key instanceof String stringKey)) {
                            throw new IllegalArgumentException("Runtime map keys must be strings");
                        }
                        keys.add(canonicalKey(stringKey, "Runtime map key"));
                    }
                    keys.sort(Comparator.naturalOrder());
                    Map<String, Object> result = new LinkedHashMap<>();
                    for (String key : keys) {
                        result.put(key, convert(map.get(key), depth + 1));
                    }
                    return Collections.unmodifiableMap(result);
                } finally {
                    exit(value);
                }
            }
            if (value instanceof List<?> list) {
                enter(value);
                try {
                    requireContainerSize(list.size());
                    List<Object> result = new ArrayList<>(list.size());
                    for (Object entry : list) {
                        result.add(convert(entry, depth + 1));
                    }
                    return Collections.unmodifiableList(result);
                } finally {
                    exit(value);
                }
            }
            if (value.getClass().isArray()) {
                enter(value);
                try {
                    int length = Array.getLength(value);
                    requireContainerSize(length);
                    List<Object> result = new ArrayList<>(length);
                    for (int index = 0; index < length; index++) {
                        result.add(convert(Array.get(value, index), depth + 1));
                    }
                    return Collections.unmodifiableList(result);
                } finally {
                    exit(value);
                }
            }
            throw new IllegalArgumentException("Unsupported runtime context value: " + value.getClass().getName());
        }

        private Object expand(Object source, Object value, int depth) {
            enter(source);
            try {
                return convert(value, depth + 1);
            } finally {
                exit(source);
            }
        }

        private void enter(Object value) {
            if (active.put(value, Boolean.TRUE) != null) {
                throw new IllegalArgumentException(TRANSPORT_CYCLE);
            }
        }

        private void exit(Object value) {
            active.remove(value);
        }
    }

    private static ServerId requireServerId(ServerId serverId) {
        if (serverId == null) {
            throw new IllegalArgumentException("RESOURCE_REFERENCE_SERVER_REQUIRED");
        }
        return serverId;
    }

    private static void requireServer(ServerResourceLocator locator, ServerId serverId) {
        if (!requireServerId(serverId).equals(locator.serverId())) {
            throw new IllegalArgumentException("RESOURCE_REFERENCE_SERVER_MISMATCH");
        }
    }

    private static Map<String, Object> chunkValue(Chunk chunk) {
        Objects.requireNonNull(chunk, "Runtime Chunk Is Required");
        World world = Objects.requireNonNull(chunk.getWorld(), "Runtime Chunk World Is Required");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("world", world.getName());
        result.put("x", chunk.getX());
        result.put("z", chunk.getZ());
        return result;
    }

    private static Map<String, Object> jobValue(FlowJobReference<?> job) {
        Objects.requireNonNull(job, "Runtime Job Reference Is Required");
        FlowJobReference.Snapshot<?> snapshot = job.snapshot();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", snapshot.id());
        result.put("kind", snapshot.kind());
        result.put("owner", snapshot.owner());
        result.put("createdAt", snapshot.createdAt().toString());
        result.put("state", snapshot.state().name());
        result.put("progress", snapshot.progress());
        result.put("metadata", snapshot.metadata());
        result.put("cancellationRequested", snapshot.cancellationRequested());
        if (snapshot.outcome() != null) {
            result.put("outcome", snapshot.outcome());
        }
        return result;
    }

    private static Map<String, Object> resultValue(FlowOperationResult<?> result) {
        Objects.requireNonNull(result, "Runtime Operation Result Is Required");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("success", result.success());
        value.put("value", result.value());
        value.put("errorCode", result.errorCode());
        value.put("message", result.message());
        value.put("details", result.details());
        return value;
    }

    private static BigDecimal finiteNumber(double value, boolean finite) {
        if (!finite) {
            throw new IllegalArgumentException("Non-finite runtime number is unsupported");
        }
        return BigDecimal.valueOf(value);
    }

    private static String canonicalKey(String value, String label) {
        Objects.requireNonNull(value, label + " Is Required");
        if (value.isBlank() || !value.equals(value.strip()) || value.indexOf('\u0000') >= 0
            || !Normalizer.isNormalized(value, Normalizer.Form.NFC)) {
            throw new IllegalArgumentException(label + " Must Be Canonical Text");
        }
        return value;
    }

    public record Result(CompiledRuntimeContext context, String failure) {
        public Result {
            failure = failure == null ? "" : failure.strip();
            if (context != null && !failure.isEmpty()) {
                throw new IllegalArgumentException("Accepted Runtime Context Cannot Have A Failure");
            }
            if (context == null && failure.isEmpty()) {
                throw new IllegalArgumentException("Rejected Runtime Context Requires A Failure");
            }
        }

        public static Result accepted(CompiledRuntimeContext context) {
            return new Result(Objects.requireNonNull(context, "Runtime Context Is Required"), "");
        }

        public static Result rejected(String failure) {
            return new Result(null, failure);
        }

        public boolean accepted() {
            return context != null;
        }
    }
}

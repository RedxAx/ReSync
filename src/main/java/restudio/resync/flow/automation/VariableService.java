package restudio.resync.flow.automation;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowResourceReference;
import restudio.resync.ReSync;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowValueCodecRegistry;
import restudio.resync.flow.PersistentVariableStore;
import restudio.resync.flow.automation.event.VariableChangedEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleBinaryOperator;

public final class VariableService {
    private static final String FLOW_PREFIX = "__automation.variable.";
    private static final Object[] LOCKS = locks();
    private final AutomationDefinitionRegistry definitions;
    private final FlowValueCodecRegistry codecs;
    private final PersistentVariableStore persistent;
    private final Plugin plugin;
    private final Map<AutomationInstanceKey, Object> values = new ConcurrentHashMap<>();

    public VariableService(AutomationDefinitionRegistry definitions, FlowValueCodecRegistry codecs) {
        this(definitions, codecs, PersistentVariableStore.getInstance(), ReSync.getInstance());
    }

    public VariableService(AutomationDefinitionRegistry definitions, FlowValueCodecRegistry codecs, Plugin plugin) {
        this(definitions, codecs, PersistentVariableStore.getInstance(), plugin);
    }

    VariableService(AutomationDefinitionRegistry definitions, FlowValueCodecRegistry codecs, PersistentVariableStore persistent, Plugin plugin) {
        this.definitions = definitions;
        this.codecs = codecs;
        this.persistent = persistent;
        this.plugin = plugin;
    }

    public VariableDefinition definition(String id) {
        return definitions.variable(id);
    }

    public Object get(FlowContext context, VariableDefinition definition, Object ownerValue) {
        AutomationOwner owner = AutomationOwner.resolve(definition.scope(), context, ownerValue);
        AutomationInstanceKey key = key(definition, owner);
        Object value;
        if (definition.scope() == AutomationScope.FLOW) {
            String localKey = FLOW_PREFIX + definition.id();
            value = context.getLocalVariables().containsKey(localKey) ? context.getLocalVariables().get(localKey)
                : normalize(context, definition, definition.defaultValue());
        } else if (definition.persistent() && persistent.contains(key.storageKey("variable"))) {
            value = codecs.decode(definition.valueType(), persistent.get(key.storageKey("variable")));
            if (value == null) {
                throw new IllegalStateException("Persistent Variable value could not be restored: " + definition.name());
            }
            values.put(key, value);
        } else {
            value = values.containsKey(key) ? values.get(key) : normalize(context, definition, definition.defaultValue());
        }
        return value;
    }

    public boolean exists(FlowContext context, VariableDefinition definition, Object ownerValue) {
        AutomationOwner owner = AutomationOwner.resolve(definition.scope(), context, ownerValue);
        AutomationInstanceKey key = key(definition, owner);
        if (definition.scope() == AutomationScope.FLOW) {
            return context.getLocalVariables().containsKey(FLOW_PREFIX + definition.id());
        }
        return values.containsKey(key) || definition.persistent() && persistent.contains(key.storageKey("variable"));
    }

    public Object set(FlowContext context, VariableDefinition definition, Object ownerValue, Object nextValue) {
        AutomationOwner owner = AutomationOwner.resolve(definition.scope(), context, ownerValue);
        Object normalized = normalize(context, definition, nextValue);
        synchronized (lock(definition, owner)) {
            Object previous = get(context, definition, owner.value());
            write(context, definition, owner, normalized);
            publish(definition, owner, previous, normalized);
            return normalized;
        }
    }

    public Object delete(FlowContext context, VariableDefinition definition, Object ownerValue) {
        AutomationOwner owner = AutomationOwner.resolve(definition.scope(), context, ownerValue);
        synchronized (lock(definition, owner)) {
            boolean existed = exists(context, definition, owner.value());
            Object previous = get(context, definition, owner.value());
            AutomationInstanceKey key = key(definition, owner);
            if (definition.scope() == AutomationScope.FLOW) {
                context.getLocalVariables().remove(FLOW_PREFIX + definition.id());
            } else {
                values.remove(key);
                if (definition.persistent()) {
                    persistent.remove(key.storageKey("variable"));
                }
            }
            if (existed) {
                publish(definition, owner, previous, null);
            }
            return previous;
        }
    }

    public Object updateNumber(FlowContext context, VariableDefinition definition, Object ownerValue, double amount, DoubleBinaryOperator operation) {
        if (!FlowDataType.NUMBER.isAssignableFrom(FlowDataType.fromString(definition.valueType().getTypeId()))) {
            throw new IllegalArgumentException("Variable is not numeric: " + definition.name());
        }
        AutomationOwner owner = AutomationOwner.resolve(definition.scope(), context, ownerValue);
        synchronized (lock(definition, owner)) {
            Object current = get(context, definition, owner.value());
            double previous = current instanceof Number number ? number.doubleValue() : 0D;
            double next = operation.applyAsDouble(previous, amount);
            Object normalized = normalize(context, definition, next);
            write(context, definition, owner, normalized);
            publish(definition, owner, current, normalized);
            return normalized;
        }
    }

    public List<FlowResourceReference> list(FlowContext context, AutomationScope scope, Object ownerValue) {
        AutomationOwner owner = AutomationOwner.resolve(scope, context, ownerValue);
        List<FlowResourceReference> references = new ArrayList<>();
        for (VariableDefinition definition : definitions.variables()) {
            if (definition.scope() == scope && exists(context, definition, owner.value())) {
                references.add(definitions.reference(definition));
            }
        }
        return List.copyOf(references);
    }

    public FlowResourceReference reference(VariableDefinition definition) {
        return definitions.reference(definition);
    }

    private Object normalize(FlowContext context, VariableDefinition definition, Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number && !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException("Variable number must be finite");
        }
        FlowDataType type = FlowDataType.fromString(definition.valueType().getTypeId());
        if (!type.isResolved() || type == FlowDataType.ANY || type.getJavaType().isInstance(value)) {
            return value;
        }
        Object adapted = context.getTypeAdapter().adapt(value, type.getJavaType());
        if (adapted == null || !type.getJavaType().isInstance(adapted)) {
            throw new IllegalArgumentException("Variable value must be " + type.getId());
        }
        return adapted;
    }

    private void write(FlowContext context, VariableDefinition definition, AutomationOwner owner, Object value) {
        AutomationInstanceKey key = key(definition, owner);
        if (definition.scope() == AutomationScope.FLOW) {
            String localKey = FLOW_PREFIX + definition.id();
            if (value == null) {
                context.getLocalVariables().remove(localKey);
            } else {
                context.getLocalVariables().put(localKey, value);
            }
            return;
        }
        if (value == null) {
            values.remove(key);
        } else {
            values.put(key, value);
        }
        if (definition.persistent()) {
            if (!codecs.hasCodec(definition.valueType())) {
                throw new IllegalArgumentException("Persistent Variable type is unsupported: " + definition.valueType());
            }
            persistent.set(key.storageKey("variable"), codecs.encode(definition.valueType(), value));
        }
    }

    private void publish(VariableDefinition definition, AutomationOwner owner, Object previous, Object next) {
        if (Objects.equals(previous, next) || Bukkit.getServer() == null || plugin == null) {
            return;
        }
        VariableChangedEvent event = new VariableChangedEvent(definitions.reference(definition), owner.value(), previous, next);
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getPluginManager().callEvent(event);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(event));
        }
    }

    private AutomationInstanceKey key(VariableDefinition definition, AutomationOwner owner) {
        return new AutomationInstanceKey(definition.id(), definition.scope(), owner.id());
    }

    private Object lock(VariableDefinition definition, AutomationOwner owner) {
        int hash = Objects.hash(definition.id(), definition.scope(), owner.id());
        return LOCKS[Math.floorMod(hash, LOCKS.length)];
    }

    private static Object[] locks() {
        Object[] locks = new Object[64];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new Object();
        }
        return locks;
    }
}

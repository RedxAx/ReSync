package restudio.resync.modules.flow;

import restudio.flow.data.FlowOperationResult;
import restudio.flow.data.FlowResourceReference;
import restudio.resync.Log;
import restudio.resync.flow.sync.FlowResourceMetadata;
import restudio.resync.resources.ReSyncManagedResource;
import restudio.resync.resources.ReSyncResourceCatalog;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class FlowResourceRegistry {
    private static final int MAX_AUDIT_RECORDS = 2048;
    private static final List<String> STANDARD_OPERATIONS = List.of("discover", "query", "get", "create", "validate", "save", "update", "duplicate", "delete", "reload", "apply");
    private final Map<String, ResourceRegistration> registrations = new ConcurrentHashMap<>();
    private final Deque<FlowResourceAuditRecord> auditRecords = new ArrayDeque<>();
    private Consumer<String> changeListener = ignored -> {
    };
    private FlowResourceMutationListener mutationListener = FlowResourceMutationListener.NONE;
    private FlowResourceAuthorizationPolicy authorizationPolicy = (context, operation, resourceType, resourceId) ->
        "system".equals(context.source()) || "flow".equals(context.source());

    @FunctionalInterface
    public interface FlowResourceAuthorizationPolicy {
        boolean authorize(FlowResourceMutationContext context, String operation, String resourceType, String resourceId);
    }

    public void setChangeListener(Consumer<String> changeListener) {
        this.changeListener = changeListener != null ? changeListener : ignored -> {
        };
    }

    public void setMutationListener(FlowResourceMutationListener mutationListener) {
        this.mutationListener = mutationListener != null ? mutationListener : FlowResourceMutationListener.NONE;
    }

    public void setAuthorizationPolicy(FlowResourceAuthorizationPolicy authorizationPolicy) {
        this.authorizationPolicy = authorizationPolicy != null ? authorizationPolicy : this.authorizationPolicy;
    }

    public synchronized List<FlowResourceAuditRecord> auditSnapshot() {
        return List.copyOf(auditRecords);
    }

    public void register(FlowResourceAdapter<?> adapter) {
        register("builtin", adapter);
    }

    public void register(String owner, FlowResourceAdapter<?> adapter) {
        if (adapter == null || adapter.descriptor() == null || adapter.descriptor().typeId() == null || adapter.descriptor().typeId().isBlank()) {
            throw new IllegalArgumentException("Resource adapter and type ID are required");
        }
        if (adapter.descriptor().displayName() == null || adapter.descriptor().displayName().isBlank()) {
            throw new IllegalArgumentException("Resource display name is required: " + adapter.descriptor().typeId());
        }
        if (adapter.supportedOperations() == null || adapter.identityRules() == null || adapter.identityRules().isBlank()
            || adapter.lifecycle() == null || adapter.lifecycle().isBlank() || adapter.catalogSource() == null || adapter.catalogSource().isBlank()
            || adapter.authoritativeService() == null || adapter.authoritativeService().isBlank()) {
            throw new IllegalArgumentException("Resource lifecycle contract is incomplete: " + adapter.descriptor().typeId());
        }
        String normalizedOwner = owner != null && !owner.isBlank() ? owner : "builtin";
        ResourceRegistration registration = new ResourceRegistration(normalizedOwner, adapter);
        ResourceRegistration previous = registrations.putIfAbsent(normalize(adapter.descriptor().typeId()), registration);
        if (previous != null && previous.adapter() != adapter) {
            throw new IllegalStateException("Resource adapter already registered: " + adapter.descriptor().typeId());
        }
    }

    public void unregister(String typeId) {
        if (typeId != null) {
            registrations.remove(normalize(typeId));
        }
    }

    public void unregister(String owner, String typeId) {
        if (owner != null && typeId != null) {
            registrations.computeIfPresent(normalize(typeId), (ignored, registration) -> owner.equals(registration.owner()) ? null : registration);
        }
    }

    public FlowResourceAdapter<?> get(String typeId) {
        ResourceRegistration registration = typeId != null ? registrations.get(normalize(typeId)) : null;
        return registration != null ? registration.adapter() : null;
    }

    public Collection<FlowResourceAdapter<?>> adapters() {
        return registrations.values().stream()
            .<FlowResourceAdapter<?>>map(ResourceRegistration::adapter)
            .toList();
    }

    public FlowResourceReference reference(String typeId, String id, boolean available) {
        ResourceRegistration registration = registration(typeId);
        return registration != null ? reference(registration, id != null ? id : "", available)
            : new FlowResourceReference(typeId != null ? typeId : "", id != null ? id : "", "unresolved", false, Map.of());
    }

    public FlowOperationResult<List<FlowResourceReference>> discover(String typeId, String query) {
        return list(typeId, query, "discover");
    }

    public FlowOperationResult<List<FlowResourceReference>> query(String typeId, String query) {
        return list(typeId, query, "query");
    }

    private FlowOperationResult<List<FlowResourceReference>> list(String typeId, String query, String operation) {
        ResourceRegistration registration = typeId != null ? registrations.get(normalize(typeId)) : null;
        FlowResourceAdapter<?> adapter = registration != null ? registration.adapter() : null;
        if (adapter == null) {
            return unavailable(typeId, operation);
        }
        if (!adapter.supportedOperations().contains(operation)) {
            return unsupported(typeId, operation);
        }
        String normalizedQuery = query != null ? query.strip() : "";
        try {
            List<FlowResourceReference> references = adapter.listIds().stream()
                .filter(id -> normalizedQuery.isEmpty() || id.toLowerCase(Locale.ROOT).contains(normalizedQuery.toLowerCase(Locale.ROOT)))
                .map(id -> reference(registration, id, true))
                .toList();
            return FlowOperationResult.success(references);
        } catch (RuntimeException exception) {
            return FlowOperationResult.failure("RESOURCE_DISCOVERY_FAILED", failureMessage(exception), Map.of("resourceType", typeId));
        }
    }

    public FlowOperationResult<Object> get(String typeId, String id) {
        ResourceRegistration registration = typeId != null ? registrations.get(normalize(typeId)) : null;
        FlowResourceAdapter<?> adapter = registration != null ? registration.adapter() : null;
        if (adapter == null) {
            return unavailable(typeId, "get");
        }
        if (!adapter.supportedOperations().contains("get")) {
            return unsupported(typeId, "get");
        }
        if (id == null || id.isBlank()) {
            return FlowOperationResult.failure("RESOURCE_ID_REQUIRED", "Resource ID is required", Map.of("resourceType", typeId));
        }
        try {
            Object value = adapter.get(id);
            return value != null ? FlowOperationResult.success(value)
                : FlowOperationResult.failure("RESOURCE_NOT_FOUND", "Resource not found: " + id, Map.of("resourceType", typeId, "resourceId", id));
        } catch (RuntimeException exception) {
            return FlowOperationResult.failure("RESOURCE_READ_FAILED", failureMessage(exception), Map.of("resourceType", typeId, "resourceId", id));
        }
    }

    public FlowOperationResult<FlowResourceReference> save(String typeId, Object value) {
        return save(typeId, value, FlowResourceMutationContext.system());
    }

    public FlowOperationResult<FlowResourceReference> save(String typeId, Object value, FlowResourceMutationContext context) {
        return authorizedMutation(typeId, resourceId(typeId, value), "save", context, () -> persist(typeId, value, "save", ExistencePolicy.ANY));
    }

    public FlowOperationResult<FlowResourceReference> create(String typeId, Object value) {
        return create(typeId, value, FlowResourceMutationContext.system());
    }

    public FlowOperationResult<FlowResourceReference> create(String typeId, Object value, FlowResourceMutationContext context) {
        return authorizedMutation(typeId, resourceId(typeId, value), "create", context, () -> persist(typeId, value, "create", ExistencePolicy.MISSING));
    }

    public FlowOperationResult<FlowResourceReference> update(String typeId, Object value) {
        return update(typeId, value, FlowResourceMutationContext.system());
    }

    public FlowOperationResult<FlowResourceReference> update(String typeId, Object value, FlowResourceMutationContext context) {
        return authorizedMutation(typeId, resourceId(typeId, value), "update", context, () -> persist(typeId, value, "update", ExistencePolicy.EXISTING));
    }

    private FlowOperationResult<FlowResourceReference> persist(String typeId, Object value, String operation, ExistencePolicy existencePolicy) {
        ResourceRegistration registration = typeId != null ? registrations.get(normalize(typeId)) : null;
        FlowResourceAdapter<?> rawAdapter = registration != null ? registration.adapter() : null;
        if (rawAdapter == null) {
            return unavailable(typeId, operation);
        }
        if (!rawAdapter.supportedOperations().contains(operation)) {
            return unsupported(typeId, operation);
        }
        if (value == null) {
            return FlowOperationResult.failure("RESOURCE_VALUE_REQUIRED", "Resource value is required", Map.of("resourceType", typeId));
        }
        FlowResourceAdapter<Object> adapter = adapter(rawAdapter);
        try {
            String id = adapter.id(value);
            if (id == null || id.isBlank()) {
                return FlowOperationResult.failure("RESOURCE_ID_REQUIRED", "Resource ID is required", Map.of("resourceType", typeId));
            }
            boolean existed = adapter.get(id) != null;
            if (existencePolicy == ExistencePolicy.MISSING && existed) {
                return FlowOperationResult.failure("RESOURCE_ALREADY_EXISTS", "Resource already exists: " + id,
                    Map.of("resourceType", typeId, "resourceId", id, "operation", operation));
            }
            if (existencePolicy == ExistencePolicy.EXISTING && !existed) {
                return FlowOperationResult.failure("RESOURCE_NOT_FOUND", "Resource not found: " + id,
                    Map.of("resourceType", typeId, "resourceId", id, "operation", operation));
            }
            adapter.validate(value);
            adapter.save(value);
            RefreshOutcome refresh = refreshAfterSave(adapter, value);
            notifySaved(adapter, value);
            return new FlowOperationResult<>(true, reference(registration, id, true), "", "",
                mutationDetails(typeId, id, !existed, existed, false, adapter, refresh));
        } catch (RuntimeException exception) {
            return FlowOperationResult.failure("RESOURCE_" + operation.toUpperCase(Locale.ROOT) + "_FAILED", failureMessage(exception),
                Map.of("resourceType", typeId, "operation", operation));
        }
    }

    public FlowOperationResult<FlowResourceReference> duplicate(String typeId, String sourceId, String targetId) {
        return duplicate(typeId, sourceId, targetId, FlowResourceMutationContext.system());
    }

    public FlowOperationResult<FlowResourceReference> duplicate(String typeId, String sourceId, String targetId, FlowResourceMutationContext context) {
        return authorizedMutation(typeId, targetId, "duplicate", context, () -> duplicateAuthorized(typeId, sourceId, targetId));
    }

    private FlowOperationResult<FlowResourceReference> duplicateAuthorized(String typeId, String sourceId, String targetId) {
        ResourceRegistration registration = registration(typeId);
        FlowResourceAdapter<?> rawAdapter = registration != null ? registration.adapter() : null;
        if (rawAdapter == null) {
            return unavailable(typeId, "duplicate");
        }
        if (!rawAdapter.supportedOperations().contains("duplicate")) {
            return unsupported(typeId, "duplicate");
        }
        if (sourceId == null || sourceId.isBlank() || targetId == null || targetId.isBlank()) {
            return FlowOperationResult.failure("RESOURCE_ID_REQUIRED", "Source and target resource IDs are required",
                Map.of("resourceType", typeId, "sourceId", text(sourceId), "targetId", text(targetId)));
        }
        FlowResourceAdapter<Object> adapter = adapter(rawAdapter);
        try {
            Object source = adapter.get(sourceId);
            if (source == null) {
                return FlowOperationResult.failure("RESOURCE_NOT_FOUND", "Resource not found: " + sourceId,
                    Map.of("resourceType", typeId, "resourceId", sourceId));
            }
            if (adapter.get(targetId) != null) {
                return FlowOperationResult.failure("RESOURCE_ALREADY_EXISTS", "Resource already exists: " + targetId,
                    Map.of("resourceType", typeId, "resourceId", targetId));
            }
            Object copy = adapter.duplicate(source, targetId);
            if (copy == null || !targetId.equals(adapter.id(copy))) {
                return FlowOperationResult.failure("RESOURCE_DUPLICATE_INVALID", "Duplicated resource did not preserve the requested target ID",
                    Map.of("resourceType", typeId, "sourceId", sourceId, "targetId", targetId));
            }
            adapter.validate(copy);
            adapter.save(copy);
            RefreshOutcome refresh = refreshAfterSave(adapter, copy);
            notifySaved(adapter, copy);
            return new FlowOperationResult<>(true, reference(registration, targetId, true), "", "",
                mutationDetails(typeId, targetId, true, false, false, adapter, refresh));
        } catch (RuntimeException exception) {
            return FlowOperationResult.failure("RESOURCE_DUPLICATE_FAILED", failureMessage(exception),
                Map.of("resourceType", typeId, "sourceId", sourceId, "targetId", targetId));
        }
    }

    public FlowOperationResult<Object> reload(String typeId, String id) {
        return reload(typeId, id, FlowResourceMutationContext.system());
    }

    public FlowOperationResult<Object> reload(String typeId, String id, FlowResourceMutationContext context) {
        return authorizedMutation(typeId, id, "reload", context, () -> reloadAuthorized(typeId, id));
    }

    private FlowOperationResult<Object> reloadAuthorized(String typeId, String id) {
        ResourceRegistration registration = registration(typeId);
        FlowResourceAdapter<?> rawAdapter = registration != null ? registration.adapter() : null;
        if (rawAdapter == null) {
            return unavailable(typeId, "reload");
        }
        if (!rawAdapter.supportedOperations().contains("reload")) {
            return unsupported(typeId, "reload");
        }
        if (id == null || id.isBlank()) {
            return FlowOperationResult.failure("RESOURCE_ID_REQUIRED", "Resource ID is required", Map.of("resourceType", typeId));
        }
        try {
            Object value = adapter(rawAdapter).reload(id);
            if (value == null) {
                return FlowOperationResult.failure("RESOURCE_NOT_FOUND", "Resource not found: " + id, Map.of("resourceType", typeId, "resourceId", id));
            }
            String refreshError = "";
            try {
                changeListener.accept(rawAdapter.catalogSource());
            } catch (RuntimeException exception) {
                refreshError = failureMessage(exception);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("resourceType", typeId);
            details.put("resourceId", id);
            details.put("reloaded", true);
            details.put("refreshSucceeded", refreshError.isBlank());
            if (!refreshError.isBlank()) {
                details.put("refreshError", refreshError);
            }
            return new FlowOperationResult<>(true, value, "", "", Map.copyOf(details));
        } catch (RuntimeException exception) {
            return FlowOperationResult.failure("RESOURCE_RELOAD_FAILED", failureMessage(exception), Map.of("resourceType", typeId, "resourceId", id));
        }
    }

    public FlowOperationResult<Object> apply(String typeId, String id, Object context) {
        return apply(typeId, id, context, FlowResourceMutationContext.system());
    }

    public FlowOperationResult<Object> apply(String typeId, String id, Object runtimeContext, FlowResourceMutationContext mutationContext) {
        return authorizedMutation(typeId, id, "apply", mutationContext, () -> applyAuthorized(typeId, id, runtimeContext));
    }

    public FlowOperationResult<Object> applyValue(String typeId, Object value, Object runtimeContext, FlowResourceMutationContext mutationContext) {
        ResourceRegistration registration = registration(typeId);
        FlowResourceAdapter<?> rawAdapter = registration != null ? registration.adapter() : null;
        if (rawAdapter == null) {
            return unavailable(typeId, "apply");
        }
        if (!rawAdapter.supportedOperations().contains("apply")) {
            return unsupported(typeId, "apply");
        }
        if (value == null) {
            return FlowOperationResult.failure("RESOURCE_VALUE_REQUIRED", "Resource value is required", Map.of("resourceType", typeId));
        }
        FlowResourceAdapter<Object> adapter = adapter(rawAdapter);
        String id = adapter.id(value);
        if (id == null || id.isBlank()) {
            return FlowOperationResult.failure("RESOURCE_ID_REQUIRED", "Resource value has no ID", Map.of("resourceType", typeId));
        }
        return authorizedMutation(typeId, id, "apply", mutationContext, () -> {
            try {
                return FlowOperationResult.success(adapter.apply(value, runtimeContext));
            } catch (RuntimeException exception) {
                return FlowOperationResult.failure("RESOURCE_APPLY_FAILED", failureMessage(exception),
                    Map.of("resourceType", typeId, "resourceId", id));
            }
        });
    }

    private FlowOperationResult<Object> applyAuthorized(String typeId, String id, Object context) {
        ResourceRegistration registration = registration(typeId);
        FlowResourceAdapter<?> rawAdapter = registration != null ? registration.adapter() : null;
        if (rawAdapter == null) {
            return unavailable(typeId, "apply");
        }
        if (!rawAdapter.supportedOperations().contains("apply")) {
            return unsupported(typeId, "apply");
        }
        if (id == null || id.isBlank()) {
            return FlowOperationResult.failure("RESOURCE_ID_REQUIRED", "Resource ID is required", Map.of("resourceType", typeId));
        }
        FlowResourceAdapter<Object> adapter = adapter(rawAdapter);
        try {
            Object value = adapter.get(id);
            if (value == null) {
                return FlowOperationResult.failure("RESOURCE_NOT_FOUND", "Resource not found: " + id, Map.of("resourceType", typeId, "resourceId", id));
            }
            return FlowOperationResult.success(adapter.apply(value, context));
        } catch (RuntimeException exception) {
            return FlowOperationResult.failure("RESOURCE_APPLY_FAILED", failureMessage(exception), Map.of("resourceType", typeId, "resourceId", id));
        }
    }

    public FlowOperationResult<FlowResourceReference> validate(String typeId, Object value) {
        ResourceRegistration registration = typeId != null ? registrations.get(normalize(typeId)) : null;
        FlowResourceAdapter<?> rawAdapter = registration != null ? registration.adapter() : null;
        if (rawAdapter == null) {
            return unavailable(typeId, "validate");
        }
        if (!rawAdapter.supportedOperations().contains("validate")) {
            return unsupported(typeId, "validate");
        }
        if (value == null) {
            return FlowOperationResult.failure("RESOURCE_VALUE_REQUIRED", "Resource value is required", Map.of("resourceType", typeId));
        }
        FlowResourceAdapter<Object> adapter = adapter(rawAdapter);
        try {
            adapter.validate(value);
            String id = adapter.id(value);
            if (id == null || id.isBlank()) {
                return FlowOperationResult.failure("RESOURCE_ID_REQUIRED", "Resource ID is required", Map.of("resourceType", typeId));
            }
            return FlowOperationResult.success(reference(registration, id, true));
        } catch (RuntimeException exception) {
            return FlowOperationResult.failure("RESOURCE_VALIDATION_FAILED", failureMessage(exception), Map.of("resourceType", typeId));
        }
    }

    public FlowOperationResult<FlowResourceReference> delete(String typeId, String id) {
        return delete(typeId, id, FlowResourceMutationContext.system());
    }

    public FlowOperationResult<FlowResourceReference> delete(String typeId, String id, FlowResourceMutationContext context) {
        return authorizedMutation(typeId, id, "delete", context, () -> deleteAuthorized(typeId, id));
    }

    public FlowOperationResult<FlowResourceReference> previewDelete(String typeId, String id, FlowResourceMutationContext context) {
        return authorizedMutation(typeId, id, "delete_preview", context, () -> previewDeleteAuthorized(typeId, id));
    }

    private FlowOperationResult<FlowResourceReference> previewDeleteAuthorized(String typeId, String id) {
        ResourceRegistration registration = typeId != null ? registrations.get(normalize(typeId)) : null;
        FlowResourceAdapter<?> adapter = registration != null ? registration.adapter() : null;
        if (adapter == null) {
            return unavailable(typeId, "delete");
        }
        if (!adapter.supportedOperations().contains("delete")) {
            return unsupported(typeId, "delete");
        }
        if (id == null || id.isBlank()) {
            return FlowOperationResult.failure("RESOURCE_ID_REQUIRED", "Resource ID is required", Map.of("resourceType", typeId));
        }
        try {
            if (adapter.get(id) == null) {
                return FlowOperationResult.failure("RESOURCE_NOT_FOUND", "Resource not found: " + id, Map.of("resourceType", typeId, "resourceId", id));
            }
            return new FlowOperationResult<>(true, reference(registration, id, true), "", "",
                Map.of("resourceType", typeId, "resourceId", id, "preview", true, "wouldDelete", true, "changed", false));
        } catch (RuntimeException exception) {
            return FlowOperationResult.failure("RESOURCE_DELETE_PREVIEW_FAILED", failureMessage(exception), Map.of("resourceType", typeId, "resourceId", id));
        }
    }

    private FlowOperationResult<FlowResourceReference> deleteAuthorized(String typeId, String id) {
        ResourceRegistration registration = typeId != null ? registrations.get(normalize(typeId)) : null;
        FlowResourceAdapter<?> adapter = registration != null ? registration.adapter() : null;
        if (adapter == null) {
            return unavailable(typeId, "delete");
        }
        if (!adapter.supportedOperations().contains("delete")) {
            return unsupported(typeId, "delete");
        }
        if (id == null || id.isBlank()) {
            return FlowOperationResult.failure("RESOURCE_ID_REQUIRED", "Resource ID is required", Map.of("resourceType", typeId));
        }
        try {
            if (adapter.get(id) == null) {
                return FlowOperationResult.failure("RESOURCE_NOT_FOUND", "Resource not found: " + id, Map.of("resourceType", typeId, "resourceId", id));
            }
            adapter.delete(id);
            RefreshOutcome refresh = refreshAfterDelete(adapter, id);
            notifyDeleted(typeId, id);
            return new FlowOperationResult<>(true, reference(registration, id, false), "", "",
                mutationDetails(typeId, id, false, false, true, adapter, refresh));
        } catch (RuntimeException exception) {
            return FlowOperationResult.failure("RESOURCE_DELETE_FAILED", failureMessage(exception), Map.of("resourceType", typeId, "resourceId", id));
        }
    }

    public List<FlowResourceMetadata> metadata() {
        List<FlowResourceMetadata> result = new ArrayList<>();
        for (ReSyncManagedResource resource : ReSyncResourceCatalog.all()) {
            result.add(metadata(resource, registrations.get(normalize(resource.typeId()))));
        }
        for (ResourceRegistration registration : registrations.values()) {
            if (ReSyncResourceCatalog.byType(registration.adapter().descriptor().typeId()) == null) {
                result.add(metadata(registration.adapter().descriptor(), registration));
            }
        }
        result.sort(Comparator.comparing(FlowResourceMetadata::getTypeId, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    private String resourceId(String typeId, Object value) {
        ResourceRegistration registration = registration(typeId);
        if (registration == null || value == null) {
            return "";
        }
        try {
            return text(adapter(registration.adapter()).id(value));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Resource adapter could not resolve an ID for " + typeId, exception);
        }
    }

    private <T> FlowOperationResult<T> authorizedMutation(String typeId, String resourceId, String operation, FlowResourceMutationContext context,
                                                           Supplier<FlowOperationResult<T>> action) {
        FlowResourceMutationContext resolvedContext = context != null ? context : FlowResourceMutationContext.system();
        FlowOperationResult<T> result;
        if (!authorizationPolicy.authorize(resolvedContext, operation, text(typeId), text(resourceId))) {
            result = FlowOperationResult.failure("RESOURCE_AUTHORIZATION_DENIED", "Resource operation is not authorized",
                Map.of("operation", operation, "resourceType", text(typeId), "resourceId", text(resourceId), "source", resolvedContext.source()));
        } else {
            result = action.get();
        }
        appendAudit(new FlowResourceAuditRecord(System.currentTimeMillis(), operation, typeId, resourceId, resolvedContext.source(),
            resolvedContext.flowId(), resolvedContext.nodeId(), resolvedContext.actor(), result.success(), result.errorCode(), result.details()));
        return result;
    }

    public FlowResourceMetadata metadata(String typeId) {
        if (typeId == null || typeId.isBlank()) {
            return null;
        }
        ResourceRegistration registration = registrations.get(normalize(typeId));
        ReSyncManagedResource resource = ReSyncResourceCatalog.byType(typeId);
        if (resource == null && registration != null) {
            resource = registration.adapter().descriptor();
        }
        return resource != null ? metadata(resource, registration) : null;
    }

    private synchronized void appendAudit(FlowResourceAuditRecord record) {
        while (auditRecords.size() >= MAX_AUDIT_RECORDS) {
            auditRecords.removeFirst();
        }
        auditRecords.addLast(record);
    }

    private FlowResourceMetadata metadata(ReSyncManagedResource resource, ResourceRegistration registration) {
        FlowResourceAdapter<?> adapter = registration != null ? registration.adapter() : null;
        FlowResourceMetadata metadata = new FlowResourceMetadata();
        metadata.setTypeId(resource.typeId());
        metadata.setDisplayName(resource.displayName());
        metadata.setReferenceType("resource_reference<" + resource.typeId() + ">");
        metadata.setDefaultFolder(resource.defaultFolder());
        metadata.setOwner(registration != null ? registration.owner() : "builtin");
        metadata.setAuthorizationPolicy("trusted_server_flow");
        metadata.setAudited(true);
        metadata.setDurable(adapter != null ? adapter.durable() : resource.jsonStorageSupported());
        metadata.setAvailable(resource.enabled() && adapter != null);
        if (adapter == null) {
            metadata.setIdentityRules("undeclared");
            metadata.setLifecycle("unavailable");
            metadata.setOperations(List.of());
            metadata.setOperationAvailability(unavailableOperationAvailability("No authoritative lifecycle adapter is registered"));
            metadata.setUnavailableReason(resource.enabled() ? "No authoritative lifecycle adapter is registered" : "Resource domain is disabled");
            return metadata;
        }
        metadata.setIdentityRules(adapter.identityRules());
        metadata.setLifecycle(adapter.lifecycle());
        metadata.setCatalogSource(adapter.catalogSource());
        metadata.setOperations(adapter.supportedOperations().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList());
        metadata.setOperationAvailability(operationAvailability(adapter));
        metadata.setAuthoritativeService(adapter.authoritativeService());
        metadata.setChangeEvents(adapter.changeEvents());
        metadata.setActiveRefresh(adapter.activeRefresh());
        return metadata;
    }

    private Map<String, String> operationAvailability(FlowResourceAdapter<?> adapter) {
        Set<String> supported = adapter.supportedOperations();
        Map<String, String> availability = new LinkedHashMap<>();
        for (String operation : STANDARD_OPERATIONS) {
            availability.put(operation, supported.contains(operation) ? "available" : adapter.unsupportedOperationReason(operation));
        }
        return Map.copyOf(availability);
    }

    private Map<String, String> unavailableOperationAvailability(String reason) {
        Map<String, String> availability = new LinkedHashMap<>();
        for (String operation : STANDARD_OPERATIONS) {
            availability.put(operation, reason);
        }
        return Map.copyOf(availability);
    }

    private FlowResourceReference reference(ResourceRegistration registration, String id, boolean available) {
        FlowResourceAdapter<?> adapter = registration.adapter();
        return new FlowResourceReference(adapter.descriptor().typeId(), id, registration.owner(), available,
            Map.of("catalogSource", adapter.catalogSource(), "authoritativeService", adapter.authoritativeService()));
    }

    private <T> FlowOperationResult<T> unavailable(String typeId, String operation) {
        return FlowOperationResult.failure("RESOURCE_AUTHORITY_UNAVAILABLE", "Resource authority is unavailable: " + typeId,
            Map.of("resourceType", typeId != null ? typeId : "", "operation", operation));
    }

    private <T> FlowOperationResult<T> unsupported(String typeId, String operation) {
        ResourceRegistration registration = typeId != null ? registrations.get(normalize(typeId)) : null;
        String reason = registration != null ? registration.adapter().unsupportedOperationReason(operation)
            : "No authoritative lifecycle adapter is registered";
        return FlowOperationResult.failure("RESOURCE_OPERATION_UNSUPPORTED", reason,
            Map.of("resourceType", typeId != null ? typeId : "", "operation", operation != null ? operation : "", "reason", reason));
    }

    private String failureMessage(RuntimeException exception) {
        return exception.getMessage() != null && !exception.getMessage().isBlank() ? exception.getMessage() : exception.getClass().getSimpleName();
    }

    private RefreshOutcome refreshAfterSave(FlowResourceAdapter<Object> adapter, Object value) {
        String error = "";
        try {
            adapter.afterSave(value);
        } catch (RuntimeException exception) {
            error = failureMessage(exception);
        }
        try {
            changeListener.accept(adapter.catalogSource());
        } catch (RuntimeException exception) {
            error = appendError(error, failureMessage(exception));
        }
        return new RefreshOutcome(error.isBlank(), error);
    }

    private RefreshOutcome refreshAfterDelete(FlowResourceAdapter<?> adapter, String id) {
        String error = "";
        try {
            adapter.afterDelete(id);
        } catch (RuntimeException exception) {
            error = failureMessage(exception);
        }
        try {
            changeListener.accept(adapter.catalogSource());
        } catch (RuntimeException exception) {
            error = appendError(error, failureMessage(exception));
        }
        return new RefreshOutcome(error.isBlank(), error);
    }

    private String appendError(String current, String next) {
        return current == null || current.isBlank() ? next : current + "; " + next;
    }

    private Map<String, Object> mutationDetails(String typeId, String id, boolean created, boolean updated, boolean deleted,
                                                FlowResourceAdapter<?> adapter, RefreshOutcome refresh) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("resourceType", typeId);
        details.put("resourceId", id);
        details.put("created", created);
        details.put("updated", updated);
        details.put("deleted", deleted);
        details.put("changed", created || updated || deleted);
        details.put("activeRefresh", adapter.activeRefresh());
        details.put("refreshSucceeded", refresh.succeeded());
        if (!refresh.error().isBlank()) {
            details.put("refreshError", refresh.error());
        }
        return Map.copyOf(details);
    }

    private String normalize(String typeId) {
        return typeId.toLowerCase(Locale.ROOT);
    }

    private ResourceRegistration registration(String typeId) {
        return typeId != null ? registrations.get(normalize(typeId)) : null;
    }

    public <T> void notifySaved(FlowResourceAdapter<T> adapter, T value) {
        if (adapter == null || value == null) {
            return;
        }
        try {
            mutationListener.saved(adapter.descriptor().typeId(), adapter.id(value), adapter.serialize(value));
        } catch (RuntimeException exception) {
            Log.warn("Publish ReSync resource change failed: " + failureMessage(exception));
        }
    }

    public void notifyDeleted(String typeId, String resourceId) {
        try {
            mutationListener.deleted(typeId, resourceId);
        } catch (RuntimeException exception) {
            Log.warn("Publish ReSync resource deletion failed: " + failureMessage(exception));
        }
    }

    private String text(String value) {
        return value != null ? value : "";
    }

    @SuppressWarnings("unchecked")
    private FlowResourceAdapter<Object> adapter(FlowResourceAdapter<?> adapter) {
        return (FlowResourceAdapter<Object>) adapter;
    }

    private record ResourceRegistration(String owner, FlowResourceAdapter<?> adapter) {
    }

    private record RefreshOutcome(boolean succeeded, String error) {
    }

    private enum ExistencePolicy {
        ANY,
        MISSING,
        EXISTING
    }
}

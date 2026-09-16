package restudio.resync.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import restudio.flow.data.FlowOperationResult;
import restudio.flow.data.FlowResourceReference;
import restudio.resync.api.OptionCatalogCapture;
import restudio.resync.api.OptionCatalogItem;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.inspector.OptionQuerySchemaV1;
import restudio.resync.flow.protocol.OptionItem;
import restudio.resync.flow.protocol.OptionPage;
import restudio.resync.flow.protocol.OptionQuery;
import restudio.resync.flow.protocol.ProtocolRejectionCode;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;
import restudio.resync.flow.sync.FlowResourceMetadata;
import restudio.resync.modules.flow.FlowResourceAdapter;
import restudio.resync.modules.flow.FlowResourceRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class FlowResourceOptionQueryAdapter {
    private static final String HASH_DOMAIN = "option.page";
    private static final String INVALIDATION_PREFIX = "resource-options:";
    private static final Comparator<OptionItem> OPTION_ORDER = Comparator
        .comparing(item -> item.value().locator().canonicalText());
    private final FlowResourceRegistry registry;
    private final ServerId serverId;

    public FlowResourceOptionQueryAdapter(FlowResourceRegistry registry, ServerId serverId) {
        this.registry = Objects.requireNonNull(registry, "Resource registry is required");
        this.serverId = Objects.requireNonNull(serverId, "Server ID is required");
    }

    public ContractRef<ResourceTypeId> requireResourceType(OptionQueryAuthority.Source source) {
        Objects.requireNonNull(source, "Option query source is required");
        if (!(source.descriptor().optionType() instanceof TypeExpr.ResourceType resourceType)) {
            throw rejected(ProtocolRejectionCode.RESOURCE_TYPE_UNAVAILABLE,
                "Option query does not produce typed resource locators");
        }
        TypeReference reference = resourceType.resourceType();
        ContractRef<ResourceTypeId> type = ContractRef.of(OwnerId.of(reference.ownerId()), ResourceTypeId.of(reference.localId()));
        requireAvailableType(type);
        return type;
    }

    public OptionPage query(OptionQuery request, OptionQueryAuthority.Source source) {
        Objects.requireNonNull(request, "Option query is required");
        Objects.requireNonNull(source, "Option query source is required");
        if (!source.sourceRef().equals(request.sourceRef()) || !source.query().equals(request.query())) {
            throw rejected(ProtocolRejectionCode.RESOURCE_TYPE_UNAVAILABLE,
                "Option query capability does not match its authoritative source");
        }
        if (!serverId.equals(request.serverId())) {
            throw rejected(ProtocolRejectionCode.AUTHORIZATION_DENIED,
                "Option query is owned by another server");
        }
        ContractRef<ResourceTypeId> type = requireResourceType(source);
        OptionQuery normalized = normalizeContext(request, source);
        List<OptionItem> options = materialize(type, (TypeExpr.ResourceType) source.descriptor().optionType());
        String search = normalized.search() == null ? "" : normalized.search().strip().toLowerCase(Locale.ROOT);
        if (!search.isEmpty()) {
            options = options.stream().filter(item -> searchable(item).contains(search)).toList();
        }
        options = options.stream().sorted(OPTION_ORDER).toList();
        String contentHash = contentHash(normalized, source, type, search, options);
        long revision = revision(source.revision(), contentHash);
        String invalidationKey = INVALIDATION_PREFIX + contentHash;
        int start = cursorStart(normalized, contentHash, revision, invalidationKey, options.size());
        int pageLimit = Math.min(normalized.limit(), source.descriptor().pageLimit());
        int end = (int) Math.min(options.size(), (long) start + pageLimit);
        boolean complete = end >= options.size();
        String nextCursor = complete ? null : contentHash + ":" + end;
        return new OptionPage(normalized.sourceRef(), normalized.query(), revision, invalidationKey,
            List.copyOf(options.subList(start, end)), nextCursor, complete, source.diagnostics());
    }

    public OptionPage query(OptionQuery request, OptionQueryAuthority.Source source, OptionCatalogCapture capture) {
        Objects.requireNonNull(request, "Option query is required");
        Objects.requireNonNull(source, "Option query source is required");
        Objects.requireNonNull(capture, "Option catalog capture is required");
        if (!source.sourceRef().equals(request.sourceRef()) || !source.query().equals(request.query())) {
            throw rejected(ProtocolRejectionCode.RESOURCE_TYPE_UNAVAILABLE,
                "Option query source or capability does not match its authoritative source");
        }
        if (!serverId.equals(request.serverId())) {
            throw rejected(ProtocolRejectionCode.AUTHORIZATION_DENIED, "Option query is owned by another server");
        }
        TypeExpr optionType = source.descriptor().optionType();
        if (optionType instanceof TypeExpr.ResourceType) {
            requireProviderResourceType(source);
        }
        List<OptionItem> options = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        for (OptionCatalogItem item : capture.items()) {
            OptionItem option = option(optionType, item);
            if (!identities.add(option.value().canonicalJson())) {
                throw rejected(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
                    "Option provider returned a duplicate canonical typed identity");
            }
            options.add(option);
        }
        String search = request.search() == null ? "" : request.search().strip().toLowerCase(Locale.ROOT);
        if (!search.isEmpty()) {
            options = options.stream().filter(item -> searchableOption(item).contains(search)).toList();
        }
        options = options.stream().sorted(Comparator.comparing(item -> item.value().canonicalJson())).toList();
        String contentHash = providerContentHash(request, source, capture, search, options);
        long revision = revision(source.providerSourceEpoch(), contentHash);
        String invalidationKey = "provider-options:" + contentHash;
        int start = cursorStart(request, contentHash, revision, invalidationKey, options.size());
        int pageLimit = Math.min(request.limit(), source.descriptor().pageLimit());
        int end = (int) Math.min(options.size(), (long) start + pageLimit);
        boolean complete = end >= options.size();
        String nextCursor = complete ? null : contentHash + ":" + end;
        return new OptionPage(request.sourceRef(), request.query(), revision, invalidationKey,
            List.copyOf(options.subList(start, end)), nextCursor, complete, source.diagnostics());
    }

    private OptionItem option(TypeExpr type, OptionCatalogItem item) {
        if (item == null || item.value() == null || item.value().isBlank()) {
            throw rejected(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
                "Option provider returned a blank identity");
        }
        TypedValue typed = CoreOptionCatalogSupport.value(type, serverId, item);
        boolean available = !(item.metadata().get("available") instanceof Boolean flag) || flag;
        String description = item.description() == null || item.description().isBlank() ? item.label() + " option." : item.description();
        String reason = available ? null : safe(String.valueOf(item.metadata().getOrDefault("reason", "Option is unavailable")),
            "Option is unavailable");
        return new OptionItem(typed, bounded(item.label(), 256), bounded(description, 512), available, reason);
    }

    private String providerContentHash(OptionQuery query, OptionQueryAuthority.Source source, OptionCatalogCapture capture,
                                       String search, List<OptionItem> items) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("captureRevision", capture.revision());
        value.put("context", canonicalValues(query.context()));
        value.put("dependencies", canonicalValues(query.dependencies()));
        value.put("items", items.stream().map(this::canonicalItem).toList());
        value.put("providerSourceEpoch", source.providerSourceEpoch());
        value.put("providerSourceId", source.providerSourceId());
        value.put("query", query.query().canonicalValue());
        value.put("resource", query.resource() == null ? null : query.resource().canonicalValue());
        value.put("search", search);
        value.put("serverId", serverId.canonicalText());
        value.put("sourceRef", query.sourceRef().canonicalValue());
        return CanonicalJson.sha256(HASH_DOMAIN, value);
    }

    private List<OptionItem> materialize(ContractRef<ResourceTypeId> type, TypeExpr.ResourceType optionType) {
        String typeId = type.id().value();
        FlowOperationResult<List<FlowResourceReference>> discovered = registry.query(typeId, null);
        if (!discovered.success()) {
            throw rejected(ProtocolRejectionCode.fromResourceError(discovered.errorCode()), safe(discovered.message(),
                "Resource option query failed"));
        }
        FlowResourceAdapter<?> adapter = registry.get(typeId);
        if (adapter == null) {
            throw rejected(ProtocolRejectionCode.RESOURCE_TYPE_UNAVAILABLE, "Resource option type is unavailable");
        }
        List<OptionItem> options = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        for (FlowResourceReference reference : discovered.value()) {
            validateReference(type, reference);
            if (!identities.add(reference.id())) {
                throw rejected(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
                    "Resource option authority returned a duplicate typed identity");
            }
            FlowOperationResult<Object> loaded = registry.get(typeId, reference.id());
            if (!loaded.success()) {
                throw rejected(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
                    safe(loaded.message(), "Resource option could not be loaded"));
            }
            options.add(option(type, optionType, reference, adapter, loaded.value()));
        }
        return options;
    }

    private OptionItem option(ContractRef<ResourceTypeId> type, TypeExpr.ResourceType optionType,
                              FlowResourceReference reference,
                              FlowResourceAdapter<?> adapter, Object value) {
        JsonObject payload = payload(adapter, value);
        String label = firstText(payload, "displayName", "name", "title", "label", "id");
        if (label == null) {
            label = reference.id();
        }
        label = bounded(label, 256);
        String description = firstText(payload, "description", "summary");
        if (description == null) {
            description = adapter.descriptor().displayName() + " resource " + label + ".";
        }
        description = bounded(description, 512);
        boolean available = reference.available() && booleanValue(payload, "available", true)
            && booleanValue(payload, "enabled", true);
        String reason = null;
        if (!available) {
            reason = firstText(payload, "unavailableReason", "disabledReason", "reason");
            if (reason == null) {
                reason = adapter.descriptor().displayName() + " resource is unavailable";
            }
            reason = bounded(reason, 512);
        }
        ServerResourceLocator locator = new ServerResourceLocator(serverId, type, reference.id());
        return new OptionItem(TypedValue.locator(optionType, locator), label, description, available, reason);
    }

    @SuppressWarnings("unchecked")
    private JsonObject payload(FlowResourceAdapter<?> adapter, Object value) {
        String serialized = ((FlowResourceAdapter<Object>) adapter).serialize(value);
        JsonElement parsed = JsonParser.parseString(serialized);
        return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
    }

    private void requireAvailableType(ContractRef<ResourceTypeId> type) {
        requireAvailableType(type, null);
    }

    private ContractRef<ResourceTypeId> requireProviderResourceType(OptionQueryAuthority.Source source) {
        TypeExpr.ResourceType optionType = (TypeExpr.ResourceType) source.descriptor().optionType();
        TypeReference reference = optionType.resourceType();
        ContractRef<ResourceTypeId> type = ContractRef.of(OwnerId.of(reference.ownerId()), ResourceTypeId.of(reference.localId()));
        requireAvailableType(type, source.providerSourceId());
        return type;
    }

    private void requireAvailableType(ContractRef<ResourceTypeId> type, String providerSourceId) {
        FlowResourceMetadata metadata = registry.metadata(type.id().value());
        FlowResourceAdapter<?> adapter = registry.get(type.id().value());
        if (metadata == null || !metadata.isAvailable() || adapter == null || !adapter.descriptor().enabled()
            || !type.id().value().equals(adapter.descriptor().typeId()) || !ownerMatches(type.owner(), metadata.getOwner())
            || !adapter.supportedOperations().contains("query") || !adapter.supportedOperations().contains("get")
            || providerSourceId != null && !providerSourceId.equals(adapter.catalogSource())) {
            throw rejected(ProtocolRejectionCode.RESOURCE_TYPE_UNAVAILABLE,
                "Resource option type is unavailable");
        }
    }

    private void validateReference(ContractRef<ResourceTypeId> type, FlowResourceReference reference) {
        if (reference == null || reference.id().isBlank() || !type.id().value().equals(reference.kind())
            || !ownerMatches(type.owner(), reference.owner())) {
            throw rejected(ProtocolRejectionCode.RESOURCE_READ_UNAVAILABLE,
                "Resource option authority returned an invalid typed identity");
        }
    }

    private OptionQuery normalizeContext(OptionQuery request, OptionQueryAuthority.Source source) {
        ServerResourceLocator contextual = request.resource();
        if (contextual != null && !serverId.equals(contextual.serverId())) {
            throw rejected(ProtocolRejectionCode.AUTHORIZATION_DENIED,
                "Option query context is owned by another server");
        }
        validateTypedValues(request.context().values());
        validateTypedValues(request.dependencies().values());
        OptionQuerySchemaV1.Normalized normalized;
        try {
            normalized = source.descriptor().querySchema().normalize(serverId, contextual, request.context(), request.dependencies());
        } catch (RuntimeException exception) {
            throw rejected(ProtocolRejectionCode.INVALID_PAYLOAD,
                "Option query context does not match its published schema", exception);
        }
        return new OptionQuery(request.sourceRef(), request.query(), request.serverId(), normalized.resource(),
            normalized.context(), normalized.dependencies(), request.cursor(), request.limit(), request.search(), request.revision(),
            request.invalidationKey());
    }

    private void validateTypedValues(Collection<TypedValue> values) {
        for (TypedValue value : values) {
            if (value.locator() != null && !serverId.equals(value.locator().serverId())) {
                throw rejected(ProtocolRejectionCode.AUTHORIZATION_DENIED,
                    "Option query typed context is owned by another server");
            }
        }
    }

    private boolean ownerMatches(OwnerId owner, String registeredOwner) {
        return owner.canonicalText().equals(registeredOwner)
            || "builtin".equals(registeredOwner) && OptionQueryAuthority.PROTOCOL_OWNER.equals(owner);
    }

    private int cursorStart(OptionQuery query, String contentHash, long revision, String invalidationKey, int size) {
        String cursor = query.cursor();
        if (cursor == null) {
            return 0;
        }
        if (query.revision() != revision || !query.invalidationKey().equals(invalidationKey)) {
            throw rejected(ProtocolRejectionCode.INVALID_CURSOR,
                "Option cursor revision or invalidation key is stale");
        }
        int separator = cursor.lastIndexOf(':');
        if (separator != contentHash.length() || !cursor.startsWith(contentHash)) {
            throw rejected(ProtocolRejectionCode.INVALID_CURSOR, "Option cursor is invalid");
        }
        int start;
        try {
            start = Integer.parseInt(cursor.substring(separator + 1));
        } catch (NumberFormatException exception) {
            throw rejected(ProtocolRejectionCode.INVALID_CURSOR, "Option cursor is invalid", exception);
        }
        if (start < 0 || start > size) {
            throw rejected(ProtocolRejectionCode.INVALID_CURSOR,
                "Option cursor is outside the result set");
        }
        return start;
    }

    private String contentHash(OptionQuery query, OptionQueryAuthority.Source source, ContractRef<ResourceTypeId> type,
                               String search, List<OptionItem> items) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("context", canonicalValues(query.context()));
        value.put("dependencies", canonicalValues(query.dependencies()));
        value.put("diagnostics", source.diagnostics().stream().map(diagnostic -> diagnostic.toMap()).toList());
        value.put("items", items.stream().map(this::canonicalItem).toList());
        value.put("query", query.query().canonicalValue());
        value.put("resource", query.resource() == null ? null : query.resource().canonicalValue());
        value.put("search", search);
        value.put("serverId", serverId.canonicalText());
        value.put("sourceDescriptorInvalidationKey", source.descriptor().invalidationKey());
        value.put("sourceInvalidationKey", source.invalidationKey());
        value.put("sourceRevision", source.revision());
        value.put("type", type.canonicalValue());
        return CanonicalJson.sha256(HASH_DOMAIN, value);
    }

    private Map<String, Object> canonicalValues(Map<String, TypedValue> values) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        values.forEach((key, value) -> canonical.put(key, value.canonicalValue()));
        return canonical;
    }

    private Map<String, Object> canonicalItem(OptionItem item) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("available", item.available());
        value.put("description", item.description());
        value.put("label", item.label());
        value.put("reason", item.reason());
        value.put("value", item.value().canonicalValue());
        return value;
    }

    private long revision(long sourceRevision, String contentHash) {
        long contentRevision = Long.parseLong(contentHash.substring(0, 15), 16);
        long revision = contentRevision ^ sourceRevision;
        return revision == 0L ? 1L : revision;
    }

    private String searchable(OptionItem item) {
        return (item.value().locator().id() + "\n" + item.label() + "\n" + item.description()).toLowerCase(Locale.ROOT);
    }

    private String searchableOption(OptionItem item) {
        String identity = item.value().locator() != null ? item.value().locator().id() : String.valueOf(item.value().value());
        return (identity + "\n" + item.label() + "\n" + item.description()).toLowerCase(Locale.ROOT);
    }

    private String firstText(JsonObject payload, String... fields) {
        for (String field : fields) {
            JsonElement value = payload.get(field);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                String text = value.getAsString().strip();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    private boolean booleanValue(JsonObject payload, String field, boolean fallback) {
        JsonElement value = payload.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
            ? value.getAsBoolean() : fallback;
    }

    private String bounded(String value, int limit) {
        String normalized = value.strip();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit).stripTrailing();
    }

    private String safe(String message, String fallback) {
        return message == null || message.isBlank() ? fallback : bounded(message, 512);
    }

    private OptionQueryAuthority.Rejected rejected(ProtocolRejectionCode code, String message) {
        return new OptionQueryAuthority.Rejected(code, message);
    }

    private OptionQueryAuthority.Rejected rejected(ProtocolRejectionCode code, String message, Throwable cause) {
        return new OptionQueryAuthority.Rejected(code, message, cause);
    }
}

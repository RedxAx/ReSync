package restudio.resync.runtime.data;

import org.bukkit.inventory.ItemStack;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.RuntimeDataAdapter;
import restudio.resync.api.RuntimeDataCapability;
import restudio.resync.api.RuntimeDataQuery;
import restudio.resync.api.RuntimeDataRecord;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.customcontent.CustomContentStorage;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class CustomContentItemDataAdapter implements RuntimeDataAdapter<ItemStack> {
    public static final String ID = "resync:custom_items";
    private final CustomContentStorage storage;
    private final CustomContentService service;
    private final RuntimeDataCategoryCatalog<CustomContentStorage.CategoryProjection> categoryCatalog;

    public CustomContentItemDataAdapter(CustomContentStorage storage, CustomContentService service) {
        this.storage = storage;
        this.service = service;
        this.categoryCatalog = new RuntimeDataCategoryCatalog<>(this) {
            @Override
            public CaptureAffinity captureAffinity() {
                return CaptureAffinity.IO;
            }

            @Override
            protected Snapshot<CustomContentStorage.CategoryProjection> captureSnapshot() {
                CustomContentStorage.CategoryProjection projection = storage.readRuntimeDataCategoryProjection();
                return snapshot(projection.revision(), records(projection.definitions()), projection);
            }

            @Override
            protected boolean current(CustomContentStorage.CategoryProjection projection) {
                return storage.isRuntimeDataCategoryProjectionCurrent(projection);
            }
        };
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String domain() {
        return "item";
    }

    @Override
    public FlowTypeRef valueType() {
        return FlowTypeRef.simple("itemstack");
    }

    @Override
    public Class<ItemStack> valueClass() {
        return ItemStack.class;
    }

    @Override
    public Set<RuntimeDataCapability> capabilities() {
        return Set.of(RuntimeDataCapability.ENUMERATE, RuntimeDataCapability.RESOLVE, RuntimeDataCapability.DESCRIBE);
    }

    @Override
    public String revision() {
        List<String> ids = storage.listIds();
        return ID + ":" + ids.size() + ":" + ids.hashCode();
    }

    @Override
    public OptionCatalogProvider categoryCatalog() {
        return categoryCatalog;
    }

    @Override
    public List<RuntimeDataRecord> records(RuntimeDataQuery query) {
        return records(storage.getAll());
    }

    @Override
    public ItemStack resolve(RuntimeDataRecord record, int amount) {
        return record != null ? service.createItem(record.id(), Math.max(1, amount)) : null;
    }

    @Override
    public RuntimeDataRecord describe(ItemStack value) {
        String id = service.identifyItem(value);
        CustomContentDefinition definition = id != null ? storage.get(id) : null;
        return definition != null ? record(definition) : null;
    }

    private RuntimeDataRecord record(CustomContentDefinition definition) {
        String type = normalized(definition.getType(), "item");
        String provider = normalized(definition.getProvider(), "vanilla");
        Set<String> categories = new LinkedHashSet<>(Set.of("custom", "resync", type, provider));
        Set<String> tags = new LinkedHashSet<>();
        if (definition.getTags() != null) {
            definition.getTags().stream().map(value -> normalized(value, "")).filter(value -> !value.isBlank()).forEach(value -> {
                categories.add(value);
                tags.add(value);
            });
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("contentType", type);
        attributes.put("provider", provider);
        put(attributes, "material", definition.getMaterial());
        put(attributes, "flowId", definition.getFlowId());
        put(attributes, "externalId", definition.getExternalId());
        put(attributes, "customModelData", definition.getCustomModelData());
        return new RuntimeDataRecord(domain(), id(), definition.getId(), definition.getDisplayName(), "ReSync " + RuntimeDataLabels.label(type),
            categories, tags, attributes);
    }

    private static void put(Map<String, Object> attributes, String key, Object value) {
        if (value != null) {
            attributes.put(key, value);
        }
    }

    private List<RuntimeDataRecord> records(List<CustomContentDefinition> definitions) {
        return definitions.stream().filter(this::isItemContent).map(this::record).toList();
    }

    private boolean isItemContent(CustomContentDefinition definition) {
        return definition != null && definition.getId() != null && Set.of("item", "block", "armor", "projectile").contains(normalized(definition.getType(), "item"));
    }

    private static String normalized(String value, String fallback) {
        return value != null && !value.isBlank() ? value.trim().toLowerCase(Locale.ROOT).replace(' ', '_') : fallback;
    }
}

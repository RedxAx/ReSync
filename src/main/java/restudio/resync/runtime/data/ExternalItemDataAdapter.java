package restudio.resync.runtime.data;

import org.bukkit.inventory.ItemStack;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.api.OptionCatalogItem;
import restudio.resync.api.RuntimeDataAdapter;
import restudio.resync.api.RuntimeDataQuery;
import restudio.resync.api.RuntimeDataRecord;
import restudio.resync.customcontent.CustomContentService;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ExternalItemDataAdapter implements RuntimeDataAdapter<ItemStack> {
    public static final String ID = "resync:provider_items";
    private final CustomContentService service;

    public ExternalItemDataAdapter(CustomContentService service) {
        this.service = service;
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
    public List<RuntimeDataRecord> records(RuntimeDataQuery query) {
        return service.recipeItemCatalog().stream().filter(item -> item.value().startsWith("provider:"))
            .map(this::record).toList();
    }

    @Override
    public ItemStack resolve(RuntimeDataRecord record, int amount) {
        return record != null ? service.createReferencedItem(record.id(), Math.max(1, amount)) : null;
    }

    private RuntimeDataRecord record(OptionCatalogItem item) {
        String[] segments = item.value().split(":", 3);
        String provider = segments.length > 1 ? segments[1].toLowerCase(Locale.ROOT) : "provider";
        Set<String> categories = new LinkedHashSet<>(Set.of("custom", "external", provider));
        if (!item.group().isBlank()) {
            categories.add(item.group().toLowerCase(Locale.ROOT).replace(' ', '_'));
        }
        Map<String, Object> attributes = new LinkedHashMap<>(item.metadata());
        attributes.put("provider", provider);
        return new RuntimeDataRecord(domain(), id(), item.value(), item.label(), item.description(), categories, Set.of(provider), attributes);
    }
}

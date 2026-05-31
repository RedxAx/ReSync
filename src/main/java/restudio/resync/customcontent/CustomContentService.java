package restudio.resync.customcontent;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import restudio.flow.data.CustomAbilityBinding;
import restudio.flow.data.CustomContentGraphAdapter;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.CustomTriggerRule;
import restudio.flow.data.FlowGraph;
import restudio.resync.api.OptionCatalogItem;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FlowStorage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class CustomContentService {
    private final CustomContentStorage contentStorage;
    private final FlowStorage flowStorage;
    private final FlowExecutor executor;
    private final Map<String, CustomContentProvider> providers = new ConcurrentHashMap<>();
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<String, Integer> tickActivations = new ConcurrentHashMap<>();
    private final Map<String, CompiledContentDefinition> compiledDefinitions = new ConcurrentHashMap<>();
    private final VanillaContentProvider vanillaProvider;
    private long currentTick;

    public CustomContentService(CustomContentStorage contentStorage, FlowStorage flowStorage, FlowExecutor executor) {
        this.contentStorage = contentStorage;
        this.flowStorage = flowStorage;
        this.executor = executor;
        this.vanillaProvider = new VanillaContentProvider();
        registerProvider(vanillaProvider);
        if (Bukkit.getPluginManager().getPlugin("Nexo") != null) {
            registerProvider(new NexoContentProvider(contentStorage, vanillaProvider));
        }
    }

    public void registerProvider(CustomContentProvider provider) {
        if (provider != null && provider.getId() != null) {
            providers.put(provider.getId().toLowerCase(Locale.ROOT), provider);
        }
    }

    public boolean hasProvider(String providerId) {
        return providerId != null && providers.containsKey(providerId.toLowerCase(Locale.ROOT));
    }

    public void unregisterProvider(String providerId) {
        if (providerId != null) {
            providers.remove(providerId.toLowerCase(Locale.ROOT));
        }
    }

    public List<String> getAvailableProviderIds() {
        return providers.values().stream()
            .filter(CustomContentProvider::isAvailable)
            .map(CustomContentProvider::getId)
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    public boolean isProviderAvailable(String providerId) {
        if (providerId == null) {
            return false;
        }
        CustomContentProvider provider = providers.get(providerId.toLowerCase(Locale.ROOT));
        return provider != null && provider.isAvailable();
    }

    public List<String> getProviderOptionIds(String providerId, String catalog) {
        if (providerId == null || catalog == null) {
            return List.of();
        }
        CustomContentProvider provider = providers.get(providerId.toLowerCase(Locale.ROOT));
        if (!(provider instanceof NexoContentProvider nexoProvider) || !nexoProvider.isAvailable()) {
            return List.of();
        }
        return switch (catalog.toLowerCase(Locale.ROOT)) {
            case "item", "items" -> nexoProvider.itemIds();
            case "block", "blocks" -> nexoProvider.blockContentIds();
            case "furniture" -> nexoProvider.furnitureIds();
            case "armor" -> nexoProvider.armorIds();
            default -> List.of();
        };
    }

    public List<OptionCatalogItem> recipeItemCatalog() {
        ensureNexoProvider();
        List<OptionCatalogItem> items = new ArrayList<>();
        Set<String> values = new LinkedHashSet<>();
        for (CustomContentDefinition definition : contentStorage.getAll()) {
            if (definition == null || definition.getId() == null || definition.getId().isBlank()) {
                continue;
            }
            String contentType = definition.getType() != null ? definition.getType().toLowerCase(Locale.ROOT) : "";
            if (!Set.of("item", "armor", "block").contains(contentType)) {
                continue;
            }
            String value = "content:" + definition.getId();
            if (!values.add(value)) {
                continue;
            }
            String label = definition.getDisplayName() != null && !definition.getDisplayName().isBlank()
                ? definition.getDisplayName()
                : definition.getId();
            items.add(new OptionCatalogItem(value, label, contentType, "", "ReSync", Map.of()));
        }
        CustomContentProvider nexo = providers.get("nexo");
        if (nexo instanceof NexoContentProvider nexoProvider && nexoProvider.isAvailable()) {
            Set<String> providerIds = new LinkedHashSet<>();
            providerIds.addAll(nexoProvider.itemIds());
            providerIds.addAll(nexoProvider.armorIds());
            providerIds.addAll(nexoProvider.blockIds());
            for (String externalId : providerIds) {
                if (externalId == null || externalId.isBlank()) {
                    continue;
                }
                String value = "provider:nexo:" + externalId;
                if (!values.add(value)) {
                    continue;
                }
                items.add(new OptionCatalogItem(value, externalId, "", "", "Providers", Map.of()));
            }
        }
        for (Material material : Material.values()) {
            if (!material.isItem() || material.isAir()) {
                continue;
            }
            String value = material.name().toLowerCase(Locale.ROOT);
            if (values.add(value)) {
                items.add(new OptionCatalogItem(value, formatMaterialLabel(value), "", "", "Vanilla", Map.of()));
            }
        }
        items.sort(Comparator.comparingInt((OptionCatalogItem item) -> recipeItemGroupRank(item.group()))
            .thenComparing(OptionCatalogItem::label, String.CASE_INSENSITIVE_ORDER));
        return items;
    }

    private void ensureNexoProvider() {
        if (providers.containsKey("nexo")) {
            return;
        }
        if (Bukkit.getPluginManager().getPlugin("Nexo") != null) {
            registerProvider(new NexoContentProvider(contentStorage, vanillaProvider));
        }
    }

    private static int recipeItemGroupRank(String group) {
        if (group == null) {
            return 2;
        }
        return switch (group.toLowerCase(Locale.ROOT)) {
            case "resync" -> 0;
            case "providers", "nexo" -> 1;
            default -> 2;
        };
    }

    private static String formatMaterialLabel(String material) {
        String cleaned = material.replace('_', ' ').toLowerCase(Locale.ROOT);
        StringBuilder builder = new StringBuilder();
        for (String part : cleaned.split("\\s+")) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.isEmpty() ? material : builder.toString();
    }

    public CustomContentProvider providerFor(CustomContentDefinition definition) {
        String providerId = definition != null && definition.getProvider() != null ? definition.getProvider() : "vanilla";
        CustomContentProvider provider = providers.get(providerId.toLowerCase(Locale.ROOT));
        if (provider == null || !provider.isAvailable()) {
            return vanillaProvider;
        }
        return provider;
    }

    public ItemStack createItem(String contentId, int amount) {
        CustomContentDefinition definition = contentStorage.get(contentId);
        if (definition == null) {
            return null;
        }
        return providerFor(definition).createItem(definition, amount);
    }

    public String identifyItem(ItemStack item) {
        for (CustomContentProvider provider : providers.values()) {
            if (provider.isAvailable()) {
                String id = provider.identifyItem(item);
                if (id != null && contentStorage.get(id) != null) {
                    return id;
                }
            }
        }
        return null;
    }

    public String identifyBlock(Location location) {
        for (CustomContentProvider provider : providers.values()) {
            if (provider.isAvailable()) {
                String id = provider.identifyBlock(location);
                if (id != null && contentStorage.get(id) != null) {
                    return id;
                }
            }
        }
        return null;
    }

    public void markPlacedBlock(Location location, CustomContentDefinition definition) {
        providerFor(definition).markPlacedBlock(location, definition);
    }

    public void clearPlacedBlock(Location location) {
        for (CustomContentProvider provider : providers.values()) {
            if (provider.isAvailable()) {
                provider.clearPlacedBlock(location);
            }
        }
    }

    public void tick() {
        currentTick++;
        tickActivations.clear();
    }

    public void dispatch(String contentId, String trigger, Player player, Event event, Map<String, Object> eventVars) {
        CustomContentDefinition definition = contentStorage.get(contentId);
        if (definition == null || trigger == null) {
            return;
        }
        definition = compiledDefinition(contentId, definition);
        List<CustomAbilityBinding> bindings = new ArrayList<>(definition.getAbilities());
        bindings.sort(Comparator.comparingInt((CustomAbilityBinding binding) -> binding.getRule() != null ? binding.getRule().getPriority() : 0).reversed());
        for (CustomAbilityBinding binding : bindings) {
            if (binding == null || !binding.isEnabled() || binding.getTrigger() == null || !binding.getTrigger().equalsIgnoreCase(trigger)) {
                continue;
            }
            if (!passes(definition, binding, player, event, eventVars)) {
                continue;
            }
            FlowGraph graph = graphFor(definition, binding.getFlowId());
            if (graph == null) {
                continue;
            }
            Map<String, Object> vars = new HashMap<>();
            if (eventVars != null) {
                vars.putAll(eventVars);
            }
            vars.put("event.content_id", definition.getId());
            vars.put("event.content_type", definition.getType());
            vars.put("event.trigger", trigger);
            vars.put("event.player", player);
            vars.put("event.cancelled", event instanceof Cancellable cancellable && cancellable.isCancelled());
            if (binding.getRule() != null && (binding.getRule().isCancelEvent() || binding.getRule().isConsumeEvent()) && event instanceof Cancellable cancellable) {
                cancellable.setCancelled(true);
                vars.put("event.cancelled", true);
            }
            executor.execute(graph, findStartNode(graph), player, event, vars);
        }
    }

    private CustomContentDefinition compiledDefinition(String contentId, CustomContentDefinition storedDefinition) {
        String flowId = storedDefinition.getFlowId();
        FlowGraph sourceGraph = storedDefinition.getGraph();
        if (sourceGraph == null && (flowId == null || flowId.isBlank())) {
            compiledDefinitions.remove(contentId);
            return storedDefinition;
        }
        if (sourceGraph == null) {
            sourceGraph = flowStorage.getGraph(flowId);
        }
        if (sourceGraph == null) {
            compiledDefinitions.remove(contentId);
            return storedDefinition;
        }
        int graphIdentity = System.identityHashCode(sourceGraph);
        int graphVersion = sourceGraph.getVersion();
        CompiledContentDefinition cached = compiledDefinitions.get(contentId);
        if (cached != null && cached.graphIdentity == graphIdentity && cached.graphVersion == graphVersion) {
            return cached.definition;
        }
        CustomContentDefinition graphDefinition = CustomContentGraphAdapter.toDefinition(sourceGraph);
        if (graphDefinition == null || !contentId.equals(graphDefinition.getId())) {
            compiledDefinitions.remove(contentId);
            return storedDefinition;
        }
        compiledDefinitions.put(contentId, new CompiledContentDefinition(graphDefinition, graphIdentity, graphVersion));
        return graphDefinition;
    }

    private FlowGraph graphFor(CustomContentDefinition definition, String flowId) {
        if (definition != null && definition.getGraph() != null) {
            FlowGraph graph = definition.getGraph();
            if (flowId == null || flowId.isBlank() || flowId.equals(graph.getId())) {
                return graph;
            }
        }
        return flowStorage.getGraph(flowId);
    }

    private boolean passes(CustomContentDefinition definition, CustomAbilityBinding binding, Player player, Event event, Map<String, Object> vars) {
        CustomTriggerRule rule = binding.getRule();
        if (rule == null) {
            return true;
        }
        if (!rule.isEnabled()) {
            return false;
        }
        if (player != null) {
            if (rule.getPermission() != null && !rule.getPermission().isBlank() && !player.hasPermission(rule.getPermission())) {
                return false;
            }
            if (rule.isRequireSneaking() && !player.isSneaking()) {
                return false;
            }
            if (rule.isRequireOnGround() && !player.isOnGround()) {
                return false;
            }
            String world = player.getWorld().getName();
            if (rule.getAllowedWorlds() != null && !rule.getAllowedWorlds().isEmpty() && !rule.getAllowedWorlds().contains(world)) {
                return false;
            }
            if (rule.getDeniedWorlds() != null && rule.getDeniedWorlds().contains(world)) {
                return false;
            }
        }
        String handFilter = rule.getHandFilter() != null ? rule.getHandFilter().toLowerCase(Locale.ROOT) : "any";
        if (!"any".equals(handFilter) && vars != null) {
            String hand = String.valueOf(vars.getOrDefault("event.hand", "any")).toLowerCase(Locale.ROOT);
            if ("main hand".equals(handFilter)) {
                handFilter = "hand";
            } else if ("offhand".equals(handFilter)) {
                handFilter = "off_hand";
            }
            if (!handFilter.equals(hand)) {
                return false;
            }
        }
        String targetFilter = rule.getTargetFilter() != null ? rule.getTargetFilter().toLowerCase(Locale.ROOT) : "any";
        if (!"any".equals(targetFilter) && vars != null) {
            Object target = vars.get("event.target");
            if ("player".equals(targetFilter) && !(target instanceof Player)) {
                return false;
            }
            if ("living entity".equals(targetFilter) && !(target instanceof org.bukkit.entity.LivingEntity)) {
                return false;
            }
            if ("hostile".equals(targetFilter) && !(target instanceof org.bukkit.entity.Monster)) {
                return false;
            }
            if ("passive".equals(targetFilter) && !(target instanceof org.bukkit.entity.Animals)) {
                return false;
            }
        }
        if (rule.getChancePercent() < 100.0 && Math.random() * 100.0 > Math.max(0.0, rule.getChancePercent())) {
            return false;
        }
        if (rule.getMaxActivationsPerTick() > 0) {
            String tickKey = binding.getId() + ":" + currentTick;
            int count = tickActivations.getOrDefault(tickKey, 0);
            if (count >= rule.getMaxActivationsPerTick()) {
                return false;
            }
            tickActivations.put(tickKey, count + 1);
        }
        if (rule.getCooldownTicks() > 0) {
            String key = cooldownKey(definition, binding, player, vars);
            long readyTick = cooldowns.getOrDefault(key, 0L);
            if (readyTick > currentTick) {
                return false;
            }
            cooldowns.put(key, currentTick + rule.getCooldownTicks());
        }
        return true;
    }

    private String cooldownKey(CustomContentDefinition definition, CustomAbilityBinding binding, Player player, Map<String, Object> vars) {
        String scope = binding.getRule().getCooldownScope() != null ? binding.getRule().getCooldownScope().toLowerCase(Locale.ROOT) : "player";
        String base = binding.getId() != null ? binding.getId() : definition.getId() + ":" + binding.getTrigger();
        return switch (scope) {
            case "global" -> base + ":global";
            case "content", "definition" -> base + ':' + definition.getId();
            case "item", "instance", "item instance" -> base + ':' + String.valueOf(vars != null ? vars.getOrDefault("event.instance_id", "") : "");
            default -> base + ':' + (player != null ? player.getUniqueId() : "server");
        };
    }

    private String findStartNode(FlowGraph graph) {
        if (graph == null || graph.getNodes() == null || graph.getNodes().isEmpty()) {
            return null;
        }
        for (Map.Entry<String, restudio.flow.data.FlowNode> entry : graph.getNodes().entrySet()) {
            String type = entry.getValue() != null ? entry.getValue().getType() : null;
            if (CustomContentGraphAdapter.typeFromNode(type) != null) {
                return entry.getKey();
            }
            if (type != null && (type.startsWith("event.custom_content") || type.startsWith("event:custom_content"))) {
                return entry.getKey();
            }
        }
        return graph.getNodes().keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).findFirst().orElse(null);
    }

    public VanillaContentProvider getVanillaProvider() {
        return vanillaProvider;
    }

    private record CompiledContentDefinition(CustomContentDefinition definition, int graphIdentity, int graphVersion) {
    }
}

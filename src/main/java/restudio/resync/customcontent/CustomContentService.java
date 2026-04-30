package restudio.resync.customcontent;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import restudio.flow.data.CustomAbilityBinding;
import restudio.flow.data.CustomContentGraphAdapter;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.CustomTriggerRule;
import restudio.flow.data.FlowGraph;
import restudio.resync.flow.FlowExecutor;
import restudio.resync.flow.FlowStorage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CustomContentService {
    private final CustomContentStorage contentStorage;
    private final FlowStorage flowStorage;
    private final FlowExecutor executor;
    private final Map<String, CustomContentProvider> providers = new ConcurrentHashMap<>();
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<String, Integer> tickActivations = new ConcurrentHashMap<>();
    private final VanillaContentProvider vanillaProvider;
    private long currentTick;

    public CustomContentService(CustomContentStorage contentStorage, FlowStorage flowStorage, FlowExecutor executor) {
        this.contentStorage = contentStorage;
        this.flowStorage = flowStorage;
        this.executor = executor;
        this.vanillaProvider = new VanillaContentProvider();
        registerProvider(vanillaProvider);
        registerProvider(new ExternalContentProvider("oraxen", "Oraxen", vanillaProvider));
        registerProvider(new ExternalContentProvider("itemsadder", "ItemsAdder", vanillaProvider));
        registerProvider(new ExternalContentProvider("nexo", "Nexo", vanillaProvider));
    }

    public void registerProvider(CustomContentProvider provider) {
        if (provider != null && provider.getId() != null) {
            providers.put(provider.getId().toLowerCase(Locale.ROOT), provider);
        }
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
        if (definition.getFlowId() != null && !definition.getFlowId().isBlank()) {
            FlowGraph sourceGraph = flowStorage.getGraph(definition.getFlowId());
            CustomContentDefinition graphDefinition = CustomContentGraphAdapter.toDefinition(sourceGraph);
            if (graphDefinition != null && contentId.equals(graphDefinition.getId())) {
                definition = graphDefinition;
                contentStorage.save(definition);
            }
        }
        List<CustomAbilityBinding> bindings = new ArrayList<>(definition.getAbilities());
        bindings.sort(Comparator.comparingInt((CustomAbilityBinding binding) -> binding.getRule() != null ? binding.getRule().getPriority() : 0).reversed());
        for (CustomAbilityBinding binding : bindings) {
            if (binding == null || !binding.isEnabled() || binding.getTrigger() == null || !binding.getTrigger().equalsIgnoreCase(trigger)) {
                continue;
            }
            if (!passes(definition, binding, player, event, eventVars)) {
                continue;
            }
            FlowGraph graph = flowStorage.getGraph(binding.getFlowId());
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
}

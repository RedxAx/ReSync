package restudio.resync.flow;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.GuiDefinition;
import restudio.flow.data.GuiElement;
import restudio.flow.data.Visual;
import restudio.resync.modules.FlowModule;
import restudio.resync.server.ReSyncServer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GuiManager implements Listener {
    private final ReSyncServer server;
    private final FlowStorage storage;
    private final FlowExecutor executor;
    private final FlowModule flowModule;
    private final Map<UUID, GuiDefinition> openGuis = new ConcurrentHashMap<>();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public GuiManager(ReSyncServer server, FlowStorage storage, FlowExecutor executor, FlowModule flowModule) {
        this.server = server;
        this.storage = storage;
        this.executor = executor;
        this.flowModule = flowModule;
    }

    public void openGui(Player player, String guiId) {
        GuiDefinition def = storage.getGui(guiId);
        if (def != null) {
            openGui(player, def);
        }
    }

    public void openGui(Player player, GuiDefinition def) {
        RemotelyHolder holder = new RemotelyHolder(def, player);
        Inventory inv = Bukkit.createInventory(holder, def.getRows() * 9, miniMessage.deserialize(def.getTitle()));

        for (GuiElement el : def.getElements()) {
            ItemStack item = createItemStack(el.getVisual());
            if (item != null) {
                for (int slot : el.getSlots()) {
                    if (slot >= 0 && slot < inv.getSize()) {
                        inv.setItem(slot, item);
                    }
                }
            }
        }

        player.openInventory(inv);
        openGuis.put(player.getUniqueId(), def);

        String flowId = findFlowIdForGui(def);
        if (flowId != null) {
            var session = server.getSessionManager().getSessionByPlayer(player.getUniqueId());
            if (session != null) {
                flowModule.sendGuiState(session, true, flowId);
            }
        }
    }

    private ItemStack createItemStack(Visual visual) {
        if (visual == null) return null;

        Material material = Material.STONE;
        if (visual.getMaterial() != null) {
            try {
                material = Material.valueOf(visual.getMaterial().toUpperCase());
            } catch (IllegalArgumentException ignored) {}
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            if (visual.getName() != null) {
                meta.displayName(miniMessage.deserialize(visual.getName()));
            }

            if (visual.getLore() != null && !visual.getLore().isEmpty()) {
                List<Component> loreLines = visual.getLore().stream()
                    .map(miniMessage::deserialize)
                    .toList();
                meta.lore(loreLines);
            }

            if (visual.getModelData() != null) {
                meta.setCustomModelData(visual.getModelData());
            }

            item.setItemMeta(meta);
        }

        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        if (!(event.getInventory().getHolder() instanceof RemotelyHolder)) return;

        RemotelyHolder holder = (RemotelyHolder) event.getInventory().getHolder();
        GuiDefinition def = holder.getGuiDefinition();

        event.setCancelled(true);
        int slot = event.getSlot();

        for (GuiElement el : def.getElements()) {
            if (el.getSlots().contains(slot)) {
                String flowId = el.getFlowId();
                if (flowId != null) {
                    FlowGraph graph = storage.getGraph(flowId);
                    if (graph != null) {
                        executor.execute(graph, findStartNode(graph), player, event);
                    } else {
                        player.sendMessage("Flow not found: " + flowId);
                    }
                }
                break;
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        openGuis.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        openGuis.remove(event.getPlayer().getUniqueId());
        server.getSessionManager().unlinkPlayer(event.getPlayer().getUniqueId());
    }

    private String findStartNode(FlowGraph graph) {
        for (var entry : graph.getNodes().entrySet()) {
            if ("event:click".equals(entry.getValue().getType()) || "start".equals(entry.getValue().getType())) {
                return entry.getKey();
            }
        }
        return graph.getNodes().keySet().stream().findFirst().orElse(null);
    }

    private String findFlowIdForGui(GuiDefinition def) {
        for (GuiElement el : def.getElements()) {
            if (el.getFlowId() != null) {
                return el.getFlowId();
            }
        }
        return null;
    }

    public void linkPlayerToSession(Player player) {
        if (server.getSessionManager().getSessionCount() > 0) {
            var firstSession = server.getSessionManager().getSessions().iterator().next();
            server.getSessionManager().linkPlayerToSession(player.getUniqueId(), firstSession);
        }
    }
}

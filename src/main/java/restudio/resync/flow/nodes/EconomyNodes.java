package restudio.resync.flow.nodes;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowType;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.registry.DefineNode;
import restudio.resync.flow.registry.FlowPin;
import restudio.resync.flow.registry.NodeDefinition;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class EconomyNodes {
    
    private static Economy economy;
    private static final Map<String, BiConsumer<FlowContext, FlowNode>> LEGACY_EXECUTORS = new ConcurrentHashMap<>();
    private static volatile boolean initialized;
    
    static Economy getEconomy() {
        if (economy == null) {
            RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
            economy = registration != null ? registration.getProvider() : null;
        }
        return economy;
    }
    
    private static void registerLegacyNodes(FlowRegistry registry) {
        registry.register("eco_get_balance", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String nodeId = findNodeId(ctx, node);
            
            if (player == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "Player is null");
                ctx.setNodeOutput(nodeId, "balance", 0.0);
                return;
            }
            
            Economy eco = getEconomy();
            if (eco == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "Economy not available");
                ctx.setNodeOutput(nodeId, "balance", 0.0);
                return;
            }
            
            double balance = eco.getBalance(player);
            ctx.setNodeOutput(nodeId, "success", true);
            ctx.setNodeOutput(nodeId, "balance", balance);
        });
        
        registry.register("eco_set_balance", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            Double balance = ctx.getInputValue(node, "balance", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            
            if (player == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "Player is null");
                return;
            }
            
            Economy eco = getEconomy();
            if (eco == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "Economy not available");
                return;
            }
            
            eco.withdrawPlayer(player, eco.getBalance(player));
            eco.depositPlayer(player, balance);
            ctx.setNodeOutput(nodeId, "success", true);
        });
        
        registry.register("eco_add_balance", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            Double amount = ctx.getInputValue(node, "amount", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            
            if (player == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "Player is null");
                ctx.setNodeOutput(nodeId, "new_balance", 0.0);
                return;
            }
            
            Economy eco = getEconomy();
            if (eco == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "Economy not available");
                ctx.setNodeOutput(nodeId, "new_balance", 0.0);
                return;
            }
            
            eco.depositPlayer(player, amount);
            double newBalance = eco.getBalance(player);
            ctx.setNodeOutput(nodeId, "success", true);
            ctx.setNodeOutput(nodeId, "new_balance", newBalance);
        });
        
        registry.register("eco_remove_balance", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            Double amount = ctx.getInputValue(node, "amount", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            
            if (player == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "Player is null");
                ctx.setNodeOutput(nodeId, "new_balance", 0.0);
                return;
            }
            
            Economy eco = getEconomy();
            if (eco == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "Economy not available");
                ctx.setNodeOutput(nodeId, "new_balance", 0.0);
                return;
            }
            
            eco.withdrawPlayer(player, amount);
            double newBalance = eco.getBalance(player);
            ctx.setNodeOutput(nodeId, "success", true);
            ctx.setNodeOutput(nodeId, "new_balance", newBalance);
        });
        
        registry.register("eco_transfer", (ctx, node) -> {
            Player fromPlayer = ctx.getInputValue(node, "from_player", Player.class, null);
            Player toPlayer = ctx.getInputValue(node, "to_player", Player.class, null);
            Double amount = ctx.getInputValue(node, "amount", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            
            if (fromPlayer == null || toPlayer == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "Player is null");
                return;
            }
            
            Economy eco = getEconomy();
            if (eco == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "Economy not available");
                return;
            }
            
            EconomyResponse response = eco.withdrawPlayer(fromPlayer, amount);
            if (response.transactionSuccess()) {
                eco.depositPlayer(toPlayer, amount);
                ctx.setNodeOutput(nodeId, "success", true);
            } else {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", response.errorMessage);
            }
        });
        
        registry.register("eco_has_balance", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            Double amount = ctx.getInputValue(node, "amount", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            
            if (player == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "Player is null");
                ctx.setNodeOutput(nodeId, "has", false);
                return;
            }
            
            Economy eco = getEconomy();
            if (eco == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "Economy not available");
                ctx.setNodeOutput(nodeId, "has", false);
                return;
            }
            
            boolean has = eco.has(player, amount);
            ctx.setNodeOutput(nodeId, "success", true);
            ctx.setNodeOutput(nodeId, "has", has);
        });
        
        registry.register("eco_format", (ctx, node) -> {
            Double amount = ctx.getInputValue(node, "amount", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            
            Economy eco = getEconomy();
            if (eco == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "Economy not available");
                ctx.setNodeOutput(nodeId, "formatted", String.valueOf(amount));
                return;
            }
            
            String formatted = eco.format(amount);
            ctx.setNodeOutput(nodeId, "success", true);
            ctx.setNodeOutput(nodeId, "formatted", formatted);
        });
        
        registry.register("eco_get_currency", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            
            Economy eco = getEconomy();
            if (eco == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "Economy not available");
                ctx.setNodeOutput(nodeId, "singular", "");
                ctx.setNodeOutput(nodeId, "plural", "");
                return;
            }
            
            String singular = eco.currencyNameSingular();
            String plural = eco.currencyNamePlural();
            ctx.setNodeOutput(nodeId, "success", true);
            ctx.setNodeOutput(nodeId, "singular", singular);
            ctx.setNodeOutput(nodeId, "plural", plural);
        });
        
        registry.register("eco_deposit", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            Double amount = ctx.getInputValue(node, "amount", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            
            if (player == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "Player is null");
                return;
            }
            
            Economy eco = getEconomy();
            if (eco == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "Economy not available");
                return;
            }
            
            EconomyResponse response = eco.depositPlayer(player, amount);
            ctx.setNodeOutput(nodeId, "success", response.transactionSuccess());
            ctx.setNodeOutput(nodeId, "error", response.transactionSuccess() ? "" : response.errorMessage);
        });
        
        registry.register("eco_withdraw", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            Double amount = ctx.getInputValue(node, "amount", Double.class, 0.0);
            String nodeId = findNodeId(ctx, node);
            
            if (player == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "Player is null");
                return;
            }
            
            Economy eco = getEconomy();
            if (eco == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "Economy not available");
                return;
            }
            
            EconomyResponse response = eco.withdrawPlayer(player, amount);
            ctx.setNodeOutput(nodeId, "success", response.transactionSuccess());
            ctx.setNodeOutput(nodeId, "error", response.transactionSuccess() ? "" : response.errorMessage);
        });
        
        registry.register("eco_has_bank", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            
            Economy eco = getEconomy();
            boolean hasBank = eco != null && eco.hasBankSupport();
            ctx.setNodeOutput(nodeId, "success", true);
            ctx.setNodeOutput(nodeId, "has_bank", hasBank);
        });
        
        registry.register("eco_create_bank", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String nodeId = findNodeId(ctx, node);
            
            if (player == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "Player is null");
                return;
            }
            
            Economy eco = getEconomy();
            if (eco == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "Economy not available");
                return;
            }
            
            if (!eco.hasBankSupport()) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "Bank support not available");
                return;
            }
            
            String bankName = player.getName();
            EconomyResponse response = eco.createBank(bankName, player);
            ctx.setNodeOutput(nodeId, "success", response.transactionSuccess());
            ctx.setNodeOutput(nodeId, "error", response.transactionSuccess() ? "" : response.errorMessage);
        });
    }

    public void registerNodes(FlowRegistry registry) {
        registerLegacyNodes(registry);
    }

    private static void ensureLegacyInitialized() {
        if (initialized) {
            return;
        }
        synchronized (EconomyNodes.class) {
            if (initialized) {
                return;
            }
            FlowRegistry tempRegistry = new FlowRegistry();
            registerLegacyNodes(tempRegistry);
            for (String type : tempRegistry.getRegisteredTypes()) {
                LEGACY_EXECUTORS.put(type, tempRegistry.getExecutor(type));
            }
            initialized = true;
        }
    }

    private static void executeLegacy(String id, FlowContext ctx, FlowNode node) {
        ensureLegacyInitialized();
        BiConsumer<FlowContext, FlowNode> executor = LEGACY_EXECUTORS.get(id);
        if (executor != null) {
            executor.accept(ctx, node);
        }
    }

    @DefineNode(id = "eco_get_balance", displayName = "Get Balance", category = NodeDefinition.NodeCategory.ECONOMY,
        inputs = {
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
            @FlowPin(name = "player", dataType = FlowType.PLAYER)
        },
        outputs = {
            @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
            @FlowPin(name = "error", dataType = FlowType.STRING),
            @FlowPin(name = "balance", dataType = FlowType.NUMBER),
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)
        })
    public void ecoGetBalance(FlowContext ctx, FlowNode node) { executeLegacy("eco_get_balance", ctx, node); }

    @DefineNode(id = "eco_set_balance", displayName = "Set Balance", category = NodeDefinition.NodeCategory.ECONOMY,
        inputs = {
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
            @FlowPin(name = "player", dataType = FlowType.PLAYER),
            @FlowPin(name = "balance", dataType = FlowType.NUMBER)
        },
        outputs = {
            @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
            @FlowPin(name = "error", dataType = FlowType.STRING),
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)
        })
    public void ecoSetBalance(FlowContext ctx, FlowNode node) { executeLegacy("eco_set_balance", ctx, node); }

    @DefineNode(id = "eco_add_balance", displayName = "Add Balance", category = NodeDefinition.NodeCategory.ECONOMY,
        inputs = {
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
            @FlowPin(name = "player", dataType = FlowType.PLAYER),
            @FlowPin(name = "amount", dataType = FlowType.NUMBER)
        },
        outputs = {
            @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
            @FlowPin(name = "error", dataType = FlowType.STRING),
            @FlowPin(name = "new_balance", dataType = FlowType.NUMBER),
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)
        })
    public void ecoAddBalance(FlowContext ctx, FlowNode node) { executeLegacy("eco_add_balance", ctx, node); }

    @DefineNode(id = "eco_remove_balance", displayName = "Remove Balance", category = NodeDefinition.NodeCategory.ECONOMY,
        inputs = {
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
            @FlowPin(name = "player", dataType = FlowType.PLAYER),
            @FlowPin(name = "amount", dataType = FlowType.NUMBER)
        },
        outputs = {
            @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
            @FlowPin(name = "error", dataType = FlowType.STRING),
            @FlowPin(name = "new_balance", dataType = FlowType.NUMBER),
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)
        })
    public void ecoRemoveBalance(FlowContext ctx, FlowNode node) { executeLegacy("eco_remove_balance", ctx, node); }

    @DefineNode(id = "eco_transfer", displayName = "Transfer Balance", category = NodeDefinition.NodeCategory.ECONOMY,
        inputs = {
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
            @FlowPin(name = "from_player", dataType = FlowType.PLAYER),
            @FlowPin(name = "to_player", dataType = FlowType.PLAYER),
            @FlowPin(name = "amount", dataType = FlowType.NUMBER)
        },
        outputs = {
            @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
            @FlowPin(name = "error", dataType = FlowType.STRING),
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)
        })
    public void ecoTransfer(FlowContext ctx, FlowNode node) { executeLegacy("eco_transfer", ctx, node); }

    @DefineNode(id = "eco_has_balance", displayName = "Has Balance", category = NodeDefinition.NodeCategory.ECONOMY,
        inputs = {
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
            @FlowPin(name = "player", dataType = FlowType.PLAYER),
            @FlowPin(name = "amount", dataType = FlowType.NUMBER)
        },
        outputs = {
            @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
            @FlowPin(name = "error", dataType = FlowType.STRING),
            @FlowPin(name = "has", dataType = FlowType.BOOLEAN),
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)
        })
    public void ecoHasBalance(FlowContext ctx, FlowNode node) { executeLegacy("eco_has_balance", ctx, node); }

    @DefineNode(id = "eco_format", displayName = "Format Currency", category = NodeDefinition.NodeCategory.ECONOMY,
        inputs = {
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
            @FlowPin(name = "amount", dataType = FlowType.NUMBER)
        },
        outputs = {
            @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
            @FlowPin(name = "error", dataType = FlowType.STRING),
            @FlowPin(name = "formatted", dataType = FlowType.STRING),
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)
        })
    public void ecoFormat(FlowContext ctx, FlowNode node) { executeLegacy("eco_format", ctx, node); }

    @DefineNode(id = "eco_get_currency", displayName = "Get Currency Name", category = NodeDefinition.NodeCategory.ECONOMY,
        inputs = {
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)
        },
        outputs = {
            @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
            @FlowPin(name = "error", dataType = FlowType.STRING),
            @FlowPin(name = "singular", dataType = FlowType.STRING),
            @FlowPin(name = "plural", dataType = FlowType.STRING),
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)
        })
    public void ecoGetCurrency(FlowContext ctx, FlowNode node) { executeLegacy("eco_get_currency", ctx, node); }

    @DefineNode(id = "eco_deposit", displayName = "Deposit", category = NodeDefinition.NodeCategory.ECONOMY,
        inputs = {
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
            @FlowPin(name = "player", dataType = FlowType.PLAYER),
            @FlowPin(name = "amount", dataType = FlowType.NUMBER)
        },
        outputs = {
            @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
            @FlowPin(name = "error", dataType = FlowType.STRING),
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)
        })
    public void ecoDeposit(FlowContext ctx, FlowNode node) { executeLegacy("eco_deposit", ctx, node); }

    @DefineNode(id = "eco_withdraw", displayName = "Withdraw", category = NodeDefinition.NodeCategory.ECONOMY,
        inputs = {
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
            @FlowPin(name = "player", dataType = FlowType.PLAYER),
            @FlowPin(name = "amount", dataType = FlowType.NUMBER)
        },
        outputs = {
            @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
            @FlowPin(name = "error", dataType = FlowType.STRING),
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)
        })
    public void ecoWithdraw(FlowContext ctx, FlowNode node) { executeLegacy("eco_withdraw", ctx, node); }

    @DefineNode(id = "eco_has_bank", displayName = "Has Bank Support", category = NodeDefinition.NodeCategory.ECONOMY,
        inputs = {
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)
        },
        outputs = {
            @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
            @FlowPin(name = "has_bank", dataType = FlowType.BOOLEAN),
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)
        })
    public void ecoHasBank(FlowContext ctx, FlowNode node) { executeLegacy("eco_has_bank", ctx, node); }

    @DefineNode(id = "eco_create_bank", displayName = "Create Bank Account", category = NodeDefinition.NodeCategory.ECONOMY,
        inputs = {
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION),
            @FlowPin(name = "player", dataType = FlowType.PLAYER)
        },
        outputs = {
            @FlowPin(name = "success", dataType = FlowType.BOOLEAN),
            @FlowPin(name = "error", dataType = FlowType.STRING),
            @FlowPin(name = "flow", type = NodeDefinition.PinType.FLOW, dataType = FlowType.EXECUTION)
        })
    public void ecoCreateBank(FlowContext ctx, FlowNode node) { executeLegacy("eco_create_bank", ctx, node); }
    
    private static String findNodeId(FlowContext ctx, FlowNode node) {
        for (var entry : ctx.getRuntime().getGraph().getNodes().entrySet()) {
            if (entry.getValue() == node) {
                return entry.getKey();
            }
        }
        return null;
    }
}

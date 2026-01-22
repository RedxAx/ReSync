package restudio.resync.flow.nodes;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

public class EconomyNodes implements NodeCategory {
    
    private static Economy economy;
    
    static Economy getEconomy() {
        if (economy == null) {
            RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
            economy = registration != null ? registration.getProvider() : null;
        }
        return economy;
    }
    
    @Override
    public void registerNodes(FlowRegistry registry) {
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
    
    private static String findNodeId(FlowContext ctx, FlowNode node) {
        for (var entry : ctx.getRuntime().getGraph().getNodes().entrySet()) {
            if (entry.getValue() == node) {
                return entry.getKey();
            }
        }
        return null;
    }
}
package restudio.resync.flow.handler.generic;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class EconomyHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public EconomyHandler() {
        operations.put("eco_get_balance", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Vault not available");
                ctx.setOutput(node, "balance", 0.0);
                return;
            }
            Economy eco = getEconomy();
            if (eco == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Economy not available");
                ctx.setOutput(node, "balance", 0.0);
                return;
            }
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Player is null");
                ctx.setOutput(node, "balance", 0.0);
                return;
            }
            double balance = eco.getBalance(player);
            ctx.setOutput(node, "success", true);
            ctx.setOutput(node, "balance", balance);
        });

        operations.put("eco_set_balance", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Vault not available");
                return;
            }
            Economy eco = getEconomy();
            if (eco == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Economy not available");
                return;
            }
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            Double balance = ctx.getInputValue(node, "balance", Double.class, 0.0);
            if (player == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Player is null");
                return;
            }
            eco.withdrawPlayer(player, eco.getBalance(player));
            eco.depositPlayer(player, balance);
            ctx.setOutput(node, "success", true);
        });

        operations.put("eco_add_balance", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Vault not available");
                ctx.setOutput(node, "new_balance", 0.0);
                return;
            }
            Economy eco = getEconomy();
            if (eco == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Economy not available");
                ctx.setOutput(node, "new_balance", 0.0);
                return;
            }
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            Double amount = ctx.getInputValue(node, "amount", Double.class, 0.0);
            if (player == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Player is null");
                ctx.setOutput(node, "new_balance", 0.0);
                return;
            }
            eco.depositPlayer(player, amount);
            double newBalance = eco.getBalance(player);
            ctx.setOutput(node, "success", true);
            ctx.setOutput(node, "new_balance", newBalance);
        });

        operations.put("eco_subtract_balance", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Vault not available");
                ctx.setOutput(node, "new_balance", 0.0);
                return;
            }
            Economy eco = getEconomy();
            if (eco == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Economy not available");
                ctx.setOutput(node, "new_balance", 0.0);
                return;
            }
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            Double amount = ctx.getInputValue(node, "amount", Double.class, 0.0);
            if (player == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Player is null");
                ctx.setOutput(node, "new_balance", 0.0);
                return;
            }
            eco.withdrawPlayer(player, amount);
            double newBalance = eco.getBalance(player);
            ctx.setOutput(node, "success", true);
            ctx.setOutput(node, "new_balance", newBalance);
        });

        operations.put("eco_has_enough", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Vault not available");
                ctx.setOutput(node, "has", false);
                return;
            }
            Economy eco = getEconomy();
            if (eco == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Economy not available");
                ctx.setOutput(node, "has", false);
                return;
            }
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            Double amount = ctx.getInputValue(node, "amount", Double.class, 0.0);
            if (player == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Player is null");
                ctx.setOutput(node, "has", false);
                return;
            }
            boolean has = eco.has(player, amount);
            ctx.setOutput(node, "success", true);
            ctx.setOutput(node, "has", has);
        });

        operations.put("eco_transfer", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Vault not available");
                return;
            }
            Economy eco = getEconomy();
            if (eco == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Economy not available");
                return;
            }
            Player fromPlayer = ctx.getInputValue(node, "from_player", Player.class, null);
            Player toPlayer = ctx.getInputValue(node, "to_player", Player.class, null);
            Double amount = ctx.getInputValue(node, "amount", Double.class, 0.0);
            if (fromPlayer == null || toPlayer == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Player is null");
                return;
            }
            EconomyResponse response = eco.withdrawPlayer(fromPlayer, amount);
            if (response.transactionSuccess()) {
                eco.depositPlayer(toPlayer, amount);
                ctx.setOutput(node, "success", true);
            } else {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", response.errorMessage);
            }
        });

        operations.put("eco_get_top", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Vault not available");
                ctx.setOutput(node, "top", new ArrayList<>());
                return;
            }
            Economy eco = getEconomy();
            if (eco == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Economy not available");
                ctx.setOutput(node, "top", new ArrayList<>());
                return;
            }
            Integer limit = ctx.getInputValue(node, "limit", Integer.class, 10);
            List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
            online.sort(Comparator.comparingDouble((Player p) -> eco.getBalance(p)).reversed());
            List<String> top = new ArrayList<>();
            for (int i = 0; i < Math.min(limit, online.size()); i++) {
                Player p = online.get(i);
                top.add(p.getName() + ": " + eco.format(eco.getBalance(p)));
            }
            ctx.setOutput(node, "success", true);
            ctx.setOutput(node, "top", top);
        });

        operations.put("deposit", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Vault not available");
                ctx.setOutput(node, "new_balance", 0.0);
                return;
            }
            Economy eco = getEconomy();
            if (eco == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Economy not available");
                ctx.setOutput(node, "new_balance", 0.0);
                return;
            }
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            Double amount = ctx.getInputValue(node, "amount", Double.class, 0.0);
            if (player == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Player is null");
                ctx.setOutput(node, "new_balance", 0.0);
                return;
            }
            eco.depositPlayer(player, amount);
            double newBalance = eco.getBalance(player);
            ctx.setOutput(node, "success", true);
            ctx.setOutput(node, "new_balance", newBalance);
        });

        operations.put("withdraw", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Vault not available");
                ctx.setOutput(node, "new_balance", 0.0);
                return;
            }
            Economy eco = getEconomy();
            if (eco == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Economy not available");
                ctx.setOutput(node, "new_balance", 0.0);
                return;
            }
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            Double amount = ctx.getInputValue(node, "amount", Double.class, 0.0);
            if (player == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Player is null");
                ctx.setOutput(node, "new_balance", 0.0);
                return;
            }
            eco.withdrawPlayer(player, amount);
            double newBalance = eco.getBalance(player);
            ctx.setOutput(node, "success", true);
            ctx.setOutput(node, "new_balance", newBalance);
        });

        operations.put("format", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
                ctx.setOutput(node, "formatted", "$" + ctx.getInputValue(node, "amount", Double.class, 0.0));
                return;
            }
            Economy eco = getEconomy();
            if (eco == null) {
                ctx.setOutput(node, "formatted", "$" + ctx.getInputValue(node, "amount", Double.class, 0.0));
                return;
            }
            Double amount = ctx.getInputValue(node, "amount", Double.class, 0.0);
            ctx.setOutput(node, "formatted", eco.format(amount));
        });

        operations.put("eco_get_currency", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Vault not available");
                ctx.setOutput(node, "singular", "dollar");
                ctx.setOutput(node, "plural", "dollars");
                ctx.setOutput(node, "currency", "$");
                return;
            }
            Economy eco = getEconomy();
            if (eco == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Economy not available");
                ctx.setOutput(node, "singular", "dollar");
                ctx.setOutput(node, "plural", "dollars");
                ctx.setOutput(node, "currency", "$");
                return;
            }
            ctx.setOutput(node, "success", true);
            ctx.setOutput(node, "singular", eco.currencyNameSingular());
            ctx.setOutput(node, "plural", eco.currencyNamePlural());
            ctx.setOutput(node, "currency", eco.currencyNamePlural());
        });

        operations.put("eco_has_bank", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "has_bank", false);
                return;
            }
            Economy eco = getEconomy();
            if (eco == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "has_bank", false);
                return;
            }
            ctx.setOutput(node, "success", true);
            ctx.setOutput(node, "has_bank", eco.hasBankSupport());
            ctx.setOutput(node, "has", eco.hasBankSupport());
        });

        operations.put("eco_create_bank", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Vault not available");
                return;
            }
            Economy eco = getEconomy();
            if (eco == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Economy not available");
                return;
            }
            if (!eco.hasBankSupport()) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Bank support unavailable");
                return;
            }
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Player is null");
                return;
            }
            String bankName = ctx.getInputValue(node, "bank_name", String.class, "");
            if (bankName.isEmpty()) {
                bankName = player.getName();
            }
            EconomyResponse response = eco.createBank(bankName, player);
            ctx.setOutput(node, "success", response.transactionSuccess());
            if (!response.transactionSuccess()) {
                ctx.setOutput(node, "error", response.errorMessage);
            }
        });
    }

    private Economy getEconomy() {
        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        return registration != null ? registration.getProvider() : null;
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("EconomyHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op != null) {
            op.accept(ctx, node);
        }
        ctx.triggerOutput("flow");
    }
}

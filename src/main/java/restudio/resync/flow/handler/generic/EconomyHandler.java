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
            Economy economy = requireEconomy();
            Player player = requirePlayer(ctx, node, "player");
            succeed(ctx, node);
            ctx.setOutput(node, "balance", economy.getBalance(player));
        });

        operations.put("eco_set_balance", (ctx, node) -> {
            Economy economy = requireEconomy();
            Player player = requirePlayer(ctx, node, "player");
            double requestedBalance = requireAmount(ctx.getInputValue(node, "balance", Double.class, 0.0), "Balance");
            double currentBalance = economy.getBalance(player);
            EconomyResponse response = requestedBalance >= currentBalance
                ? economy.depositPlayer(player, requestedBalance - currentBalance)
                : economy.withdrawPlayer(player, currentBalance - requestedBalance);
            publishTransaction(ctx, node, economy, player, response);
        });

        operations.put("eco_add_balance", (ctx, node) -> deposit(ctx, node));
        operations.put("deposit", (ctx, node) -> deposit(ctx, node));
        operations.put("eco_subtract_balance", (ctx, node) -> withdraw(ctx, node));
        operations.put("withdraw", (ctx, node) -> withdraw(ctx, node));

        operations.put("eco_has_enough", (ctx, node) -> {
            Economy economy = requireEconomy();
            Player player = requirePlayer(ctx, node, "player");
            double amount = requireAmount(ctx.getInputValue(node, "amount", Double.class, 0.0), "Amount");
            succeed(ctx, node);
            ctx.setOutput(node, "has", economy.has(player, amount));
        });

        operations.put("eco_transfer", (ctx, node) -> {
            Economy economy = requireEconomy();
            Player fromPlayer = requirePlayer(ctx, node, "from_player");
            Player toPlayer = requirePlayer(ctx, node, "to_player");
            if (fromPlayer.getUniqueId().equals(toPlayer.getUniqueId())) throw new IllegalArgumentException("Transfer players must be different");
            double amount = requireAmount(ctx.getInputValue(node, "amount", Double.class, 0.0), "Transfer amount");
            EconomyResponse withdrawal = economy.withdrawPlayer(fromPlayer, amount);
            if (!withdrawal.transactionSuccess()) {
                fail(ctx, node, transactionError(withdrawal, "Transfer withdrawal failed"));
                return;
            }
            EconomyResponse deposit = economy.depositPlayer(toPlayer, amount);
            if (deposit.transactionSuccess()) {
                succeed(ctx, node);
                ctx.setOutput(node, "from_balance", economy.getBalance(fromPlayer));
                ctx.setOutput(node, "to_balance", economy.getBalance(toPlayer));
                return;
            }
            EconomyResponse rollback = economy.depositPlayer(fromPlayer, amount);
            if (!rollback.transactionSuccess()) {
                throw new IllegalStateException("Economy transfer failed and rollback failed: " + transactionError(deposit, "deposit failed") + "; " + transactionError(rollback, "rollback failed"));
            }
            fail(ctx, node, transactionError(deposit, "Transfer deposit failed"));
        });

        operations.put("eco_get_top", (ctx, node) -> {
            Economy economy = requireEconomy();
            int limit = ctx.getInputValue(node, "limit", Integer.class, 10);
            if (limit < 1 || limit > 100) throw new IllegalArgumentException("Economy leaderboard limit must be between 1 and 100");
            List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
            online.sort(Comparator.comparingDouble((Player player) -> economy.getBalance(player)).reversed());
            List<String> top = new ArrayList<>();
            for (int index = 0; index < Math.min(limit, online.size()); index++) {
                Player player = online.get(index);
                top.add(player.getName() + ": " + economy.format(economy.getBalance(player)));
            }
            succeed(ctx, node);
            ctx.setOutput(node, "top", top);
        });

        operations.put("format", (ctx, node) -> {
            Economy economy = requireEconomy();
            double amount = requireFinite(ctx.getInputValue(node, "amount", Double.class, 0.0), "Amount");
            ctx.setOutput(node, "formatted", economy.format(amount));
        });

        operations.put("eco_get_currency", (ctx, node) -> {
            Economy economy = requireEconomy();
            succeed(ctx, node);
            ctx.setOutput(node, "singular", economy.currencyNameSingular());
            ctx.setOutput(node, "plural", economy.currencyNamePlural());
            ctx.setOutput(node, "currency", economy.currencyNamePlural());
        });

        operations.put("eco_has_bank", (ctx, node) -> {
            Economy economy = requireEconomy();
            boolean supported = economy.hasBankSupport();
            succeed(ctx, node);
            ctx.setOutput(node, "has_bank", supported);
            ctx.setOutput(node, "has", supported);
        });

        operations.put("eco_create_bank", (ctx, node) -> {
            Economy economy = requireEconomy();
            if (!economy.hasBankSupport()) throw new IllegalStateException("The active economy provider does not support banks");
            Player player = requirePlayer(ctx, node, "player");
            String bankName = ctx.getInputValue(node, "bank_name", String.class, player.getName());
            if (bankName == null || bankName.isBlank()) throw new IllegalArgumentException("Bank name is required");
            EconomyResponse response = economy.createBank(bankName, player);
            if (response.transactionSuccess()) succeed(ctx, node);
            else fail(ctx, node, transactionError(response, "Bank creation failed"));
        });
    }

    private static void deposit(FlowContext context, FlowNode node) {
        Economy economy = requireEconomy();
        Player player = requirePlayer(context, node, "player");
        double amount = requireAmount(context.getInputValue(node, "amount", Double.class, 0.0), "Deposit amount");
        publishTransaction(context, node, economy, player, economy.depositPlayer(player, amount));
    }

    private static void withdraw(FlowContext context, FlowNode node) {
        Economy economy = requireEconomy();
        Player player = requirePlayer(context, node, "player");
        double amount = requireAmount(context.getInputValue(node, "amount", Double.class, 0.0), "Withdrawal amount");
        publishTransaction(context, node, economy, player, economy.withdrawPlayer(player, amount));
    }

    private static void publishTransaction(FlowContext context, FlowNode node, Economy economy, Player player, EconomyResponse response) {
        if (response == null) throw new IllegalStateException("Economy provider returned no transaction response");
        double balance = economy.getBalance(player);
        context.setOutput(node, "new_balance", balance);
        context.setOutput(node, "balance", balance);
        if (response.transactionSuccess()) succeed(context, node);
        else fail(context, node, transactionError(response, "Economy transaction failed"));
    }

    private static Economy requireEconomy() {
        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (registration == null || registration.getProvider() == null) throw new IllegalStateException("No Vault economy provider is available");
        return registration.getProvider();
    }

    private static Player requirePlayer(FlowContext context, FlowNode node, String inputName) {
        Player player = context.getInputValue(node, inputName, Player.class, null);
        if (player == null) throw new IllegalArgumentException("Player input is required: " + inputName);
        return player;
    }

    private static double requireAmount(double value, String field) {
        double finite = requireFinite(value, field);
        if (finite < 0) throw new IllegalArgumentException(field + " cannot be negative");
        return finite;
    }

    private static double requireFinite(double value, String field) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(field + " must be finite");
        return value;
    }

    private static String transactionError(EconomyResponse response, String fallback) {
        if (response == null) return fallback;
        return response.errorMessage != null && !response.errorMessage.isBlank() ? response.errorMessage : fallback;
    }

    private static void succeed(FlowContext context, FlowNode node) {
        context.setOutput(node, "success", true);
        context.setOutput(node, "error", "");
    }

    private static void fail(FlowContext context, FlowNode node, String error) {
        context.setOutput(node, "success", false);
        context.setOutput(node, "error", error);
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("EconomyHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op == null) throw new IllegalArgumentException("Unknown economy operation: " + operation);
        op.accept(ctx, node);
        ctx.triggerOutput("flow");
    }
}

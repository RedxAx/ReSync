package restudio.resync.flow.handler.generic;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.World;
import org.bukkit.entity.Player;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class RandomHandler implements NodeHandler {
    private static final Random RANDOM = new Random();
    private final ConcurrentHashMap<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public RandomHandler() {
        operations.put("random_int", (ctx, node) -> {
            Integer min = ctx.getInputValue(node, "min", Integer.class, 0);
            Integer max = ctx.getInputValue(node, "max", Integer.class, 10);
            int lower = Math.min(min, max);
            int upper = Math.max(min, max);
            int value = lower + RANDOM.nextInt(upper - lower + 1);
            ctx.setOutput(node, "number", value);
        });

        operations.put("random_double", (ctx, node) -> {
            Double min = ctx.getInputValue(node, "min", Double.class, 0.0);
            Double max = ctx.getInputValue(node, "max", Double.class, 1.0);
            double value = min + RANDOM.nextDouble() * (max - min);
            ctx.setOutput(node, "number", value);
        });

        operations.put("random_boolean", (ctx, node) -> {
            Double chance = ctx.getInputValue(node, "chance_0_to_100", Double.class, 50.0);
            boolean success = RANDOM.nextDouble() * 100 < chance;
            ctx.setOutput(node, "success", success);
        });

        operations.put("random_choice", (ctx, node) -> {
            List<?> list = ctx.getInputValue(node, "list", List.class, null);
            Object value = null;
            if (list != null && !list.isEmpty()) {
                value = list.get(RANDOM.nextInt(list.size()));
            }
            ctx.setOutput(node, "element", value);
        });

        operations.put("random_shuffle", (ctx, node) -> {
            List<?> list = ctx.getInputValue(node, "list", List.class, null);
            List<Object> shuffled = new ArrayList<>();
            if (list != null) {
                shuffled.addAll(list);
                Collections.shuffle(shuffled);
            }
            ctx.setOutput(node, "shuffled_list", shuffled);
        });

        operations.put("random_gaussian", (ctx, node) -> {
            Double mean = ctx.getInputValue(node, "mean", Double.class, 0.0);
            Double stddev = ctx.getInputValue(node, "stddev", Double.class, 1.0);
            double value = mean + stddev * RANDOM.nextGaussian();
            ctx.setOutput(node, "number", value);
        });

        operations.put("random_uuid", (ctx, node) -> {
            ctx.setOutput(node, "uuid", UUID.randomUUID().toString());
        });

        operations.put("random_hex", (ctx, node) -> {
            Integer length = ctx.getInputValue(node, "length", Integer.class, 8);
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < length; i++) {
                hex.append(Integer.toHexString(RANDOM.nextInt(16)));
            }
            ctx.setOutput(node, "hex", hex.toString());
        });

        operations.put("random_number", (ctx, node) -> {
            Integer min = ctx.getInputValue(node, "min", Integer.class, 0);
            Integer max = ctx.getInputValue(node, "max", Integer.class, 100);
            int result = min + (int) (Math.random() * (max - min + 1));
            ctx.setOutput(node, "result", result);
        });

        operations.put("random_item", (ctx, node) -> {
            Object itemsObj = ctx.getInputValue(node, "items", Object.class, null);
            if (itemsObj instanceof List<?> list && !list.isEmpty()) {
                int index = (int) (Math.random() * list.size());
                ctx.setOutput(node, "result", list.get(index));
            }
        });

        operations.put("random_player", (ctx, node) -> {
            World world = ctx.getInputValue(node, "world", World.class, null);
            List<Player> players;
            if (world != null) {
                players = new ArrayList<>(world.getPlayers());
            } else {
                players = new ArrayList<>(Bukkit.getOnlinePlayers());
            }
            if (!players.isEmpty()) {
                int index = (int) (Math.random() * players.size());
                ctx.setOutput(node, "player", players.get(index));
            }
        });

        operations.put("random_color", (ctx, node) -> {
            ctx.setOutput(node, "color", Color.fromRGB(RANDOM.nextInt(256), RANDOM.nextInt(256), RANDOM.nextInt(256)));
        });

    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("RandomHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op == null) {
            throw new IllegalArgumentException("Unknown random operation: " + operation);
        }
        op.accept(ctx, node);
        ctx.triggerOutput("flow");
    }
}

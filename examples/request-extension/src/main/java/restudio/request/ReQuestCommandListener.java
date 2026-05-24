package restudio.request;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

public class ReQuestCommandListener implements Listener {
    private final ReQuestService service;

    public ReQuestCommandListener(ReQuestService service) {
        this.service = service;
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage();
        if (!message.equalsIgnoreCase("/request") && !message.toLowerCase(Locale.ROOT).startsWith("/request ")) {
            return;
        }
        event.setCancelled(true);
        execute(event.getPlayer(), message.substring(1).split("\\s+"));
    }

    @EventHandler
    public void onServerCommand(ServerCommandEvent event) {
        String command = event.getCommand();
        if (!command.equalsIgnoreCase("request") && !command.toLowerCase(Locale.ROOT).startsWith("request ")) {
            return;
        }
        event.setCancelled(true);
        execute(event.getSender(), command.split("\\s+"));
    }

    private void execute(CommandSender sender, String[] input) {
        String[] args = Arrays.stream(input)
            .filter(value -> value != null && !value.isBlank())
            .toArray(String[]::new);
        if (args.length < 2) {
            usage(sender);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "save" -> {
                service.save();
                sender.sendMessage("ReQuest Saved");
            }
            case "reload" -> {
                service.reload();
                sender.sendMessage("ReQuest Reloaded");
            }
            case "seed" -> {
                service.seedDefaults();
                sender.sendMessage("ReQuest Seeded");
            }
            case "clear" -> clear(sender, args);
            default -> usage(sender);
        }
    }

    private void clear(CommandSender sender, String[] args) {
        if (args.length < 3) {
            usage(sender);
            return;
        }
        Player player = Bukkit.getPlayerExact(args[2]);
        if (player != null) {
            service.clearPlayer(player);
            sender.sendMessage("ReQuest Player Cleared");
            return;
        }
        try {
            service.clearPlayer(UUID.fromString(args[2]));
            sender.sendMessage("ReQuest Player Cleared");
        } catch (IllegalArgumentException exception) {
            sender.sendMessage("ReQuest Player Missing");
        }
    }

    private void usage(CommandSender sender) {
        sender.sendMessage("ReQuest Save|Reload|Seed|Clear <Player>");
    }
}

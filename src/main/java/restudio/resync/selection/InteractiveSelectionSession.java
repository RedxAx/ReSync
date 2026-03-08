package restudio.resync.selection;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.UUID;

public interface InteractiveSelectionSession {
    UUID getPlayerId();

    void start(InteractiveSelectionManager manager, Player player);

    void handleBlockSelect(InteractiveSelectionManager manager, Player player, Block block);

    void tick(InteractiveSelectionManager manager, Player player, long now);

    void stop(InteractiveSelectionManager manager, Player player, String reason, boolean completed);
}

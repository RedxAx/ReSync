package restudio.resync.advancement;

import com.google.gson.JsonObject;
import org.bukkit.entity.Player;

import java.util.Map;

public interface AdvancementRuntimeBridge {
    boolean supported();

    String unsupportedReason();

    void replace(Map<String, JsonObject> trees);

    void sync(Player player);
}

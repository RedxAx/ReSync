package restudio.resync.customcontent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.Test;
import restudio.flow.data.CustomContentGraphAdapter;

import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CustomContentListenerContractTest {
    @Test
    void executableTriggersRunBeforeMonitorPriority() throws Exception {
        List<Method> methods = List.of(
            CustomContentListener.class.getMethod("onInteract", PlayerInteractEvent.class),
            CustomContentListener.class.getMethod("onDamage", EntityDamageByEntityEvent.class),
            CustomContentListener.class.getMethod("onBlockPlace", BlockPlaceEvent.class),
            CustomContentListener.class.getMethod("onBlockBreak", BlockBreakEvent.class),
            CustomContentListener.class.getMethod("onMove", PlayerMoveEvent.class),
            CustomContentListener.class.getMethod("onRedstone", BlockRedstoneEvent.class),
            CustomContentListener.class.getMethod("onConsume", PlayerItemConsumeEvent.class),
            CustomContentListener.class.getMethod("onDrop", PlayerDropItemEvent.class),
            CustomContentListener.class.getMethod("onPickup", EntityPickupItemEvent.class),
            CustomContentListener.class.getMethod("onShootBow", EntityShootBowEvent.class),
            CustomContentListener.class.getMethod("onProjectileLaunch", ProjectileLaunchEvent.class),
            CustomContentListener.class.getMethod("onProjectileHit", ProjectileHitEvent.class)
        );

        for (Method method : methods) {
            assertEquals(EventPriority.HIGHEST, method.getAnnotation(EventHandler.class).priority(), method.getName());
        }
    }

    @Test
    void triggerSchemaGraphAdapterAndListenerStayInExactParity() throws Exception {
        Set<String> adapterTriggers = new HashSet<>();
        for (String type : List.of("item", "block", "armor", "projectile")) {
            CustomContentGraphAdapter.triggersForType(type).forEach(trigger -> adapterTriggers.add(trigger.trigger()));
        }

        JsonArray definitions;
        try (InputStreamReader reader = new InputStreamReader(Objects.requireNonNull(
            getClass().getResourceAsStream("/nodes/migrated/ability_effects.json")), StandardCharsets.UTF_8)) {
            definitions = JsonParser.parseReader(reader).getAsJsonArray();
        }
        JsonObject triggerNode = definitions.asList().stream()
            .map(element -> element.getAsJsonObject())
            .filter(node -> "ability.trigger_content_ability".equals(node.get("id").getAsString()))
            .findFirst()
            .orElseThrow();
        JsonObject triggerPin = triggerNode.getAsJsonArray("inputs").asList().stream()
            .map(element -> element.getAsJsonObject())
            .filter(pin -> "trigger".equals(pin.get("name").getAsString()))
            .findFirst()
            .orElseThrow();
        Set<String> schemaTriggers = new HashSet<>();
        triggerPin.getAsJsonArray("options").forEach(option -> schemaTriggers.add(option.getAsString()));

        String listenerSource = Files.readString(Path.of("src/main/java/restudio/resync/customcontent/CustomContentListener.java"));
        Matcher matcher = Pattern.compile("\\\"((?:item|block|armor|projectile)\\.[a-z_]+)\\\"").matcher(listenerSource);
        Set<String> listenerTriggers = new HashSet<>();
        while (matcher.find()) {
            listenerTriggers.add(matcher.group(1));
        }

        assertEquals(adapterTriggers, schemaTriggers);
        assertEquals(adapterTriggers, listenerTriggers);
    }
}

package restudio.resync.modules;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ProtocolLibMessageRewriteBridge implements AutoCloseable {
    private final MessageRewriteModule module;
    private final PacketAdapter packetAdapter;
    private final GsonComponentSerializer gsonComponent = GsonComponentSerializer.gson();
    private final PlainTextComponentSerializer plainText = PlainTextComponentSerializer.plainText();

    public ProtocolLibMessageRewriteBridge(MessageRewriteModule module, ModuleContext context) {
        this.module = module;
        packetAdapter = new PacketAdapter(context.getPlugin(), ListenerPriority.NORMAL, packetTypes()) {
            @Override
            public void onPacketSending(PacketEvent event) {
                rewritePacket(event);
            }
        };
        ProtocolLibrary.getProtocolManager().addPacketListener(packetAdapter);
    }

    private PacketType[] packetTypes() {
        List<PacketType> types = new ArrayList<>(List.of(
            PacketType.Play.Server.CHAT,
            PacketType.Play.Server.DISGUISED_CHAT,
            PacketType.Play.Server.SET_TITLE_TEXT,
            PacketType.Play.Server.SET_SUBTITLE_TEXT,
            PacketType.Play.Server.SET_ACTION_BAR_TEXT,
            PacketType.Play.Server.BOSS,
            PacketType.Play.Server.OPEN_WINDOW
        ));
        addPacketType(types, "SYSTEM_CHAT");
        addPacketType(types, "PLAYER_CHAT");
        return types.toArray(PacketType[]::new);
    }

    private void addPacketType(List<PacketType> types, String name) {
        try {
            Object value = PacketType.Play.Server.class.getField(name).get(null);
            if (value instanceof PacketType type) {
                types.add(type);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void close() {
        ProtocolLibrary.getProtocolManager().removePacketListener(packetAdapter);
    }

    private void rewritePacket(PacketEvent event) {
        PacketContainer packet = event.getPacket();
        StructureModifier<WrappedChatComponent> components = packet.getChatComponents();
        for (int index = 0; index < components.size(); index++) {
            WrappedChatComponent component = components.read(index);
            if (component == null) {
                continue;
            }
            try {
                String originalJson = component.getJson();
                Component original = gsonComponent.deserialize(originalJson);
                Player target = event.getPlayer();
                Component replacement = module.rewritePacketComponent(packetSource(event), target, original, originalJson);
                if (replacement != null) {
                    components.write(index, WrappedChatComponent.fromJson(gsonComponent.serialize(replacement)));
                }
            } catch (Exception ignored) {
            }
        }
        StructureModifier<String> strings = packet.getStrings();
        for (int index = 0; index < strings.size(); index++) {
            String original = strings.read(index);
            if (original == null || original.isBlank()) {
                continue;
            }
            Component replacement = module.rewritePacketComponent(packetSource(event), event.getPlayer(), Component.text(original), original);
            if (replacement != null) {
                strings.write(index, plainText.serialize(replacement));
            }
        }
    }

    private String packetSource(PacketEvent event) {
        String name = event.getPacketType().name().toLowerCase(Locale.ROOT);
        if (name.contains("title")) {
            return "title";
        }
        if (name.contains("action_bar")) {
            return "actionbar";
        }
        if (name.contains("boss")) {
            return "bossbar";
        }
        if (name.contains("open_window")) {
            return "openScreen";
        }
        return "packetText";
    }
}

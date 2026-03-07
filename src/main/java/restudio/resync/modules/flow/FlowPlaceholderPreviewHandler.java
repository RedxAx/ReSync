package restudio.resync.modules.flow;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import restudio.resync.core.Session;
import restudio.resync.flow.util.ReSyncPlaceholderUtil;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class FlowPlaceholderPreviewHandler {
    private final FlowPacketSender sender;

    public FlowPlaceholderPreviewHandler(FlowPacketSender sender) {
        this.sender = sender;
    }

    public void handle(Session session, ByteBuffer buffer) {
        if (buffer.remaining() < 9) {
            return;
        }
        int requestId = buffer.getInt();
        boolean usePapi = buffer.get() == 1;
        int textLength = buffer.getInt();
        if (textLength < 0 || textLength > FlowPacketSender.MAX_STRING_LENGTH || textLength > buffer.remaining()) {
            return;
        }
        byte[] textBytes = new byte[textLength];
        buffer.get(textBytes);
        String text = new String(textBytes, StandardCharsets.UTF_8);
        Player player = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        sender.sendPlaceholderPreview(session, requestId, ReSyncPlaceholderUtil.apply(player, text, usePapi));
    }
}

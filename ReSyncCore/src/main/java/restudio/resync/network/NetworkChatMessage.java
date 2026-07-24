package restudio.resync.network;

import java.util.UUID;

public record NetworkChatMessage(UUID playerId, String playerName, String displayName, String channelId, String message, long sentAt) {
    public NetworkChatMessage {
        if (playerId == null) {
            throw new IllegalArgumentException("Network Chat Player ID Is Required");
        }
        playerName = NetworkValues.required(playerName, "Network Chat Player Name");
        displayName = NetworkValues.required(displayName, "Network Chat Display Name");
        channelId = NetworkValues.required(channelId, "Network Chat Channel");
        message = NetworkValues.required(message, "Network Chat Message");
        if (sentAt < 0) {
            throw new IllegalArgumentException("Network Chat Time Is Invalid");
        }
    }
}

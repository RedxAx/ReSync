package restudio.resync.player;

import java.util.ArrayList;
import java.util.List;

public class PlayerTrackingUpdate {
    private String type;
    private String reason;
    private String playerId;
    private PlayerDossier dossier;
    private List<PlayerDossier> dossiers = new ArrayList<>();

    public static PlayerTrackingUpdate snapshot(List<PlayerDossier> dossiers) {
        PlayerTrackingUpdate update = new PlayerTrackingUpdate();
        update.type = "snapshot";
        update.dossiers = dossiers == null ? new ArrayList<>() : dossiers;
        return update;
    }

    public static PlayerTrackingUpdate delta(String reason, PlayerDossier dossier) {
        PlayerTrackingUpdate update = new PlayerTrackingUpdate();
        update.type = "delta";
        update.reason = reason;
        update.dossier = dossier;
        update.playerId = dossier != null ? dossier.getPlayerId() : null;
        return update;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getPlayerId() {
        return playerId;
    }

    public void setPlayerId(String playerId) {
        this.playerId = playerId;
    }

    public PlayerDossier getDossier() {
        return dossier;
    }

    public void setDossier(PlayerDossier dossier) {
        this.dossier = dossier;
    }

    public List<PlayerDossier> getDossiers() {
        return dossiers;
    }

    public void setDossiers(List<PlayerDossier> dossiers) {
        this.dossiers = dossiers == null ? new ArrayList<>() : dossiers;
    }
}

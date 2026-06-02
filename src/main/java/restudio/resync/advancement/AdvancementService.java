package restudio.resync.advancement;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class AdvancementService {
    public boolean grant(Player player, String treeId, String nodeId, String criterion) {
        AdvancementProgress progress = progress(player, treeId, nodeId);
        return progress != null && progress.awardCriteria(criterion(criterion));
    }

    public boolean revoke(Player player, String treeId, String nodeId, String criterion) {
        AdvancementProgress progress = progress(player, treeId, nodeId);
        return progress != null && progress.revokeCriteria(criterion(criterion));
    }

    public boolean has(Player player, String treeId, String nodeId, String criterion) {
        AdvancementProgress progress = progress(player, treeId, nodeId);
        return progress != null && progress.getAwardedCriteria().contains(criterion(criterion));
    }

    public boolean complete(Player player, String treeId, String nodeId) {
        AdvancementProgress progress = progress(player, treeId, nodeId);
        return progress != null && progress.isDone();
    }

    public Map<UUID, Map<NamespacedKey, Set<String>>> snapshot(Collection<? extends Player> players) {
        Map<UUID, Map<NamespacedKey, Set<String>>> snapshots = new LinkedHashMap<>();
        for (Player player : players) {
            Map<NamespacedKey, Set<String>> progress = new LinkedHashMap<>();
            Bukkit.advancementIterator().forEachRemaining(advancement -> {
                if ("resync".equals(advancement.getKey().getNamespace())) {
                    progress.put(advancement.getKey(), new LinkedHashSet<>(player.getAdvancementProgress(advancement).getAwardedCriteria()));
                }
            });
            snapshots.put(player.getUniqueId(), progress);
        }
        return snapshots;
    }

    public void restore(Collection<? extends Player> players, Map<UUID, Map<NamespacedKey, Set<String>>> snapshots) {
        for (Player player : players) {
            Map<NamespacedKey, Set<String>> snapshot = snapshots.get(player.getUniqueId());
            if (snapshot == null) {
                continue;
            }
            for (Map.Entry<NamespacedKey, Set<String>> entry : snapshot.entrySet()) {
                Advancement advancement = Bukkit.getAdvancement(entry.getKey());
                if (advancement == null) {
                    continue;
                }
                AdvancementProgress progress = player.getAdvancementProgress(advancement);
                for (String criterion : progress.getAwardedCriteria()) {
                    if (!entry.getValue().contains(criterion)) {
                        progress.revokeCriteria(criterion);
                    }
                }
                for (String criterion : entry.getValue()) {
                    if (!progress.getAwardedCriteria().contains(criterion)) {
                        progress.awardCriteria(criterion);
                    }
                }
            }
        }
    }

    private AdvancementProgress progress(Player player, String treeId, String nodeId) {
        if (player == null || blank(treeId) || blank(nodeId)) {
            return null;
        }
        Advancement advancement = Bukkit.getAdvancement(new NamespacedKey("resync", treeId + "/" + nodeId));
        return advancement != null ? player.getAdvancementProgress(advancement) : null;
    }

    private String criterion(String criterion) {
        return blank(criterion) ? "impossible" : criterion;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

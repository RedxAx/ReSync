package restudio.resync.world;

import java.util.ArrayList;
import java.util.List;

public class WorldSnapshot {
    private List<WorldDashboardEntry> dashboard = new ArrayList<>();
    private List<WorldRegistryEntry> worlds = new ArrayList<>();
    private List<WorldPortal> portals = new ArrayList<>();
    private List<WorldGameRuleDescriptor> gameRuleDescriptors = new ArrayList<>();
    private List<String> generatorHints = new ArrayList<>();
    private long generatedAt;

    public WorldSnapshot copy() {
        WorldSnapshot copy = new WorldSnapshot();
        copy.dashboard = new ArrayList<>();
        for (WorldDashboardEntry entry : dashboard) {
            copy.dashboard.add(entry == null ? null : entry.copy());
        }
        copy.worlds = new ArrayList<>();
        for (WorldRegistryEntry world : worlds) {
            copy.worlds.add(world == null ? null : world.copy());
        }
        copy.portals = new ArrayList<>();
        for (WorldPortal portal : portals) {
            copy.portals.add(portal == null ? null : portal.copy());
        }
        copy.gameRuleDescriptors = new ArrayList<>();
        for (WorldGameRuleDescriptor descriptor : gameRuleDescriptors) {
            copy.gameRuleDescriptors.add(descriptor == null ? null : descriptor.copy());
        }
        copy.generatorHints = new ArrayList<>(generatorHints);
        copy.generatedAt = generatedAt;
        return copy;
    }

    public List<WorldDashboardEntry> getDashboard() {
        return dashboard;
    }

    public void setDashboard(List<WorldDashboardEntry> dashboard) {
        this.dashboard = dashboard == null ? new ArrayList<>() : dashboard;
    }

    public List<WorldRegistryEntry> getWorlds() {
        return worlds;
    }

    public void setWorlds(List<WorldRegistryEntry> worlds) {
        this.worlds = worlds == null ? new ArrayList<>() : worlds;
    }

    public List<WorldPortal> getPortals() {
        return portals;
    }

    public void setPortals(List<WorldPortal> portals) {
        this.portals = portals == null ? new ArrayList<>() : portals;
    }

    public List<WorldGameRuleDescriptor> getGameRuleDescriptors() {
        return gameRuleDescriptors;
    }

    public void setGameRuleDescriptors(List<WorldGameRuleDescriptor> gameRuleDescriptors) {
        this.gameRuleDescriptors = gameRuleDescriptors == null ? new ArrayList<>() : gameRuleDescriptors;
    }

    public List<String> getGeneratorHints() {
        return generatorHints;
    }

    public void setGeneratorHints(List<String> generatorHints) {
        this.generatorHints = generatorHints == null ? new ArrayList<>() : generatorHints;
    }

    public long getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(long generatedAt) {
        this.generatedAt = generatedAt;
    }
}

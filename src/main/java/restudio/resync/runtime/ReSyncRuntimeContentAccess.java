package restudio.resync.runtime;

public final class ReSyncRuntimeContentAccess {
    private static volatile Services services = new Services(null, null, null);

    private ReSyncRuntimeContentAccess() {
    }

    public static void configure(LootTableService lootService, TradeProfileService tradeService, NpcService npcRuntimeService) {
        services = new Services(lootService, tradeService, npcRuntimeService);
    }

    public static void clear() {
        services = new Services(null, null, null);
    }

    public static LootTableService lootTables() {
        return services.lootTables();
    }

    public static TradeProfileService tradeProfiles() {
        return services.tradeProfiles();
    }

    public static NpcService npcs() {
        return services.npcs();
    }

    private record Services(LootTableService lootTables, TradeProfileService tradeProfiles, NpcService npcs) {
    }
}

package restudio.resync.runtime;

public final class ReSyncRuntimeContentAccess {
    private static LootTableService lootTableService;
    private static TradeProfileService tradeProfileService;
    private static NpcService npcService;

    private ReSyncRuntimeContentAccess() {
    }

    public static void configure(LootTableService lootService, TradeProfileService tradeService, NpcService npcRuntimeService) {
        lootTableService = lootService;
        tradeProfileService = tradeService;
        npcService = npcRuntimeService;
    }

    public static void clear() {
        lootTableService = null;
        tradeProfileService = null;
        npcService = null;
    }

    public static LootTableService lootTables() {
        return lootTableService;
    }

    public static TradeProfileService tradeProfiles() {
        return tradeProfileService;
    }

    public static NpcService npcs() {
        return npcService;
    }
}

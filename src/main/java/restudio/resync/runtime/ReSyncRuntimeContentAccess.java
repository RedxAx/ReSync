package restudio.resync.runtime;

public final class ReSyncRuntimeContentAccess {
    private static LootTableService lootTableService;
    private static VillageProfileService villageProfileService;
    private static NpcService npcService;

    private ReSyncRuntimeContentAccess() {
    }

    public static void configure(LootTableService lootService, VillageProfileService villageService, NpcService npcRuntimeService) {
        lootTableService = lootService;
        villageProfileService = villageService;
        npcService = npcRuntimeService;
    }

    public static void clear() {
        lootTableService = null;
        villageProfileService = null;
        npcService = null;
    }

    public static LootTableService lootTables() {
        return lootTableService;
    }

    public static VillageProfileService villageProfiles() {
        return villageProfileService;
    }

    public static NpcService npcs() {
        return npcService;
    }
}

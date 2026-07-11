package restudio.resync.player;

import restudio.resync.core.Session;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PlayerControlAuthorizer {
    private static final Map<String, String> CAPABILITY_BY_ACTION = capabilities();
    private static final List<String> OPERATIONS = CAPABILITY_BY_ACTION.values().stream().distinct().toList();

    public boolean allows(Session session, String action) {
        return session != null && session.getIdentity() != null && CAPABILITY_BY_ACTION.containsKey(action);
    }

    public List<String> operations(Session session) {
        return session != null && session.getIdentity() != null ? OPERATIONS : List.of();
    }

    private static Map<String, String> capabilities() {
        LinkedHashMap<String, String> capabilities = new LinkedHashMap<>();
        capabilities.put("playerDataSnapshot", "playerData");
        capabilities.put("inventorySnapshot", "playerData");
        capabilities.put("enderSnapshot", "playerData");
        capabilities.put("inventoryEdit", "onlineInventoryEdit");
        capabilities.put("inventoryEditBatch", "onlineInventoryEdit");
        capabilities.put("gameRulesList", "gameRules");
        capabilities.put("gameRuleSet", "gameRules");
        capabilities.put("liveSettingsList", "liveSettings");
        capabilities.put("liveSettingSet", "liveSettings");
        return Collections.unmodifiableMap(capabilities);
    }
}

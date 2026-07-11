package restudio.resync.player;

import org.junit.jupiter.api.Test;
import restudio.resync.core.Session;
import restudio.resync.security.ClientIdentity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerControlAuthorizerTest {
    @Test
    void rejectsUnauthenticatedSession() {
        PlayerControlAuthorizer authorizer = new PlayerControlAuthorizer();

        assertFalse(authorizer.allows(new Session("session", "client", null), "inventoryEditBatch"));
        assertEquals(List.of(), authorizer.operations(new Session("session", "client", null)));
    }

    @Test
    void allowsEverySupportedActionForAuthenticatedSessions() {
        PlayerControlAuthorizer authorizer = new PlayerControlAuthorizer();
        Session session = new Session("session", "client", null, new ClientIdentity("client", "2.1.0"));

        for (String action : List.of("playerDataSnapshot", "inventorySnapshot", "enderSnapshot", "inventoryEdit", "inventoryEditBatch", "gameRulesList", "gameRuleSet", "liveSettingsList", "liveSettingSet")) {
            assertTrue(authorizer.allows(session, action), action);
        }
    }

    @Test
    void deniesUnknownActionsByDefault() {
        PlayerControlAuthorizer authorizer = new PlayerControlAuthorizer();
        Session session = new Session("session", "client", null, new ClientIdentity("client", "2.1.0"));

        assertFalse(authorizer.allows(session, null));
        assertFalse(authorizer.allows(session, ""));
        assertFalse(authorizer.allows(session, "inventoryDelete"));
    }

    @Test
    void advertisesCapabilityGroupsBackedBySupportedActions() {
        PlayerControlAuthorizer authorizer = new PlayerControlAuthorizer();
        Session session = new Session("session", "client", null, new ClientIdentity("client", "2.1.0"));

        assertEquals(List.of("playerData", "onlineInventoryEdit", "gameRules", "liveSettings"), authorizer.operations(session));
    }
}

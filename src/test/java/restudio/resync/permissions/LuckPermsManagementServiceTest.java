package restudio.resync.permissions;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LuckPermsManagementServiceTest {
    @Test
    void mergesStoredAndLoadedUsersInStableOrder() {
        UUID first = UUID.fromString("09587536-eb25-40f9-9aae-0dc7f3b49b22");
        UUID second = UUID.fromString("b04ff102-b0f5-4eea-8ca5-786e491ee023");

        List<UUID> users = LuckPermsManagementService.mergeUserIds(List.of(second), List.of(first, second));

        assertEquals(List.of(first, second), users);
    }
}

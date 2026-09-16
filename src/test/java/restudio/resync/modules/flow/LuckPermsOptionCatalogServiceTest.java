package restudio.resync.modules.flow;

import org.bukkit.Bukkit;
import org.bukkit.permissions.Permission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.resync.api.OptionCatalogCapture;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.OptionCatalogQuery;
import restudio.resync.api.OptionCatalogRegistry;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuckPermsOptionCatalogServiceTest {
    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void providersRequireServerMainCaptureAndReturnOneCapturedPermissionView() {
        Bukkit.getPluginManager().addPermission(new Permission("resync.test", "ReSync Test Permission"));
        OptionCatalogRegistry registry = new OptionCatalogRegistry();
        new LuckPermsOptionCatalogService().registerProviders(registry);

        OptionCatalogProvider groups = registry.provider(LuckPermsOptionCatalogService.GROUP_SOURCE);
        OptionCatalogProvider tracks = registry.provider(LuckPermsOptionCatalogService.TRACK_SOURCE);
        OptionCatalogProvider permissions = registry.provider(LuckPermsOptionCatalogService.PERMISSION_SOURCE);
        OptionCatalogCapture capture = permissions.capture(new OptionCatalogQuery(permissions.sourceId(), Map.of()));

        assertEquals(OptionCatalogProvider.CaptureAffinity.SERVER_MAIN, groups.captureAffinity());
        assertEquals(OptionCatalogProvider.CaptureAffinity.SERVER_MAIN, tracks.captureAffinity());
        assertEquals(OptionCatalogProvider.CaptureAffinity.SERVER_MAIN, permissions.captureAffinity());
        assertTrue(capture.items().stream().anyMatch(item -> item.value().equals("resync.test")
            && item.description().equals("ReSync Test Permission")));
        assertEquals("unavailable", capture.status());
        assertEquals("LuckPerms Service Is Unavailable", capture.diagnostic());
    }
}

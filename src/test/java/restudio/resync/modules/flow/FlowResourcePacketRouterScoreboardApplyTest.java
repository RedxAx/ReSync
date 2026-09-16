package restudio.resync.modules.flow;

import com.google.gson.Gson;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.flow.data.ScoreboardDefinition;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.ScoreboardRuntimeCapability;
import restudio.resync.flow.ScoreboardTemplateManager;
import restudio.resync.resources.ReSyncManagedResource;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.storage.AssetTransactionCoordinator;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlowResourcePacketRouterScoreboardApplyTest {
    private static final String ID = "canonical";
    private static final String HASH = "b".repeat(64);
    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void clearRuntimeCapability() {
        ScoreboardTemplateManager.clearRuntimeCapability();
    }

    @Test
    void scoreboardAdapterApplyResolvesTheRuntimeCapability() throws Exception {
        RuntimeScoreboardAdapter runtimeAdapter = new RuntimeScoreboardAdapter();
        runtimeAdapter.definition = new ScoreboardDefinition(ID, "Canonical");
        FlowResourceRegistry runtimeRegistry = new FlowResourceRegistry();
        runtimeRegistry.register(runtimeAdapter);
        ScoreboardTemplateManager.configureRuntimeCapability(ScoreboardRuntimeCapability.of(runtimeRegistry));

        FlowResourceRegistry routerRegistry = new FlowResourceRegistry();
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(
            temporaryDirectory.resolve("assets"), new Gson())) {
            FlowStorage storage = new FlowStorage(temporaryDirectory.toFile(), coordinator);
            new FlowResourcePacketRouter(storage, null, null, null, null, null, null, routerRegistry, ignored -> {
            });
        }
        @SuppressWarnings("unchecked")
        FlowResourceAdapter<ScoreboardDefinition> scoreboardAdapter =
            (FlowResourceAdapter<ScoreboardDefinition>) routerRegistry.get(ReSyncResourceCatalog.SCOREBOARD);

        ScoreboardDefinition staleValue = new ScoreboardDefinition(ID, "Legacy fallback");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> scoreboardAdapter.apply(staleValue, new FlowContext(null, player(), null)));

        assertEquals("Scoreboard could not be applied", failure.getMessage());
        assertEquals(1, runtimeAdapter.getCalls);
    }

    private Player player() {
        UUID playerId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, arguments) -> {
                if ("getUniqueId".equals(method.getName())) {
                    return playerId;
                }
                if (method.getReturnType() == boolean.class) {
                    return false;
                }
                if (method.getReturnType() == byte.class) {
                    return (byte) 0;
                }
                if (method.getReturnType() == short.class) {
                    return (short) 0;
                }
                if (method.getReturnType() == int.class) {
                    return 0;
                }
                if (method.getReturnType() == long.class) {
                    return 0L;
                }
                if (method.getReturnType() == float.class) {
                    return 0F;
                }
                if (method.getReturnType() == double.class) {
                    return 0D;
                }
                if (method.getReturnType() == char.class) {
                    return (char) 0;
                }
                return null;
            });
    }

    private static final class RuntimeScoreboardAdapter implements FlowResourceAdapter<ScoreboardDefinition> {
        private ScoreboardDefinition definition;
        private int getCalls;

        @Override
        public ReSyncManagedResource descriptor() {
            return ReSyncResourceCatalog.byType(ReSyncResourceCatalog.SCOREBOARD);
        }

        @Override
        public ScoreboardDefinition get(String id) {
            getCalls++;
            return definition != null && id.equals(definition.getId()) ? definition : null;
        }

        @Override
        public List<String> listIds() {
            return definition != null ? List.of(definition.getId()) : List.of();
        }

        @Override
        public ScoreboardDefinition deserialize(String json) {
            return null;
        }

        @Override
        public String id(ScoreboardDefinition value) {
            return value != null ? value.getId() : null;
        }

        @Override
        public void save(ScoreboardDefinition value) {
        }

        @Override
        public void delete(String id) {
        }

        @Override
        public boolean supportsAuthoritativeMutationIdentity() {
            return true;
        }

        @Override
        public FlowResourceMutationStamp readMutationStamp(String id) {
            return new FlowResourceMutationStamp(ReSyncResourceCatalog.SCOREBOARD, id, 1L,
                UUID.fromString("22222222-2222-4222-8222-222222222222"), HASH, false);
        }
    }
}

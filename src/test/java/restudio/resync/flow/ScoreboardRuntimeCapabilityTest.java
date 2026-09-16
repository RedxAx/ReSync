package restudio.resync.flow;

import org.junit.jupiter.api.Test;
import restudio.flow.data.ScoreboardDefinition;
import restudio.resync.modules.flow.FlowResourceAdapter;
import restudio.resync.modules.flow.FlowResourceMutationStamp;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.resources.ReSyncManagedResource;
import restudio.resync.resources.ReSyncResourceCatalog;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoreboardRuntimeCapabilityTest {
    private static final String HASH = "a".repeat(64);

    @Test
    void resolvesAndListsLiveStampedTypedScoreboards() {
        TestScoreboardAdapter adapter = new TestScoreboardAdapter();
        ScoreboardDefinition live = new ScoreboardDefinition("live", "Live");
        ScoreboardDefinition second = new ScoreboardDefinition("second", "Second");
        adapter.definitions.put(live.getId(), live);
        adapter.definitions.put(second.getId(), second);
        adapter.ids = List.of("second", "live", "live");
        adapter.stamps.put("live", stamp("scoreboard", "live", false));
        adapter.stamps.put("second", stamp("scoreboard", "second", false));

        ScoreboardRuntimeCapability capability = capability(adapter);

        assertTrue(capability.available());
        assertSame(live, capability.get("live"));
        assertEquals(List.of("live", "second"), capability.listIds());
    }

    @Test
    void rejectsTombstonedAndMissingStampedScoreboards() {
        TestScoreboardAdapter adapter = new TestScoreboardAdapter();
        adapter.definitions.put("deleted", new ScoreboardDefinition("deleted", "Deleted"));
        adapter.definitions.put("unstamped", new ScoreboardDefinition("unstamped", "Unstamped"));
        adapter.ids = List.of("deleted", "unstamped");
        adapter.stamps.put("deleted", stamp("scoreboard", "deleted", true));

        ScoreboardRuntimeCapability capability = capability(adapter);

        assertNull(capability.get("deleted"));
        assertNull(capability.get("unstamped"));
        assertEquals(List.of(), capability.listIds());
    }

    @Test
    void reportsAuthoritativeReadAndListFailures() {
        TestScoreboardAdapter adapter = new TestScoreboardAdapter();
        adapter.ids = List.of("broken");
        adapter.stampFailure = new IllegalStateException("persistence gate closed");
        ScoreboardRuntimeCapability capability = capability(adapter);

        IllegalStateException readFailure = assertThrows(IllegalStateException.class, () -> capability.get("broken"));
        IllegalStateException listFailure = assertThrows(IllegalStateException.class, capability::listIds);

        assertTrue(readFailure.getMessage().contains("could not read broken"));
        assertTrue(listFailure.getMessage().contains("could not list resources"));
        assertSame(adapter.stampFailure, readFailure.getCause());
        assertSame(adapter.stampFailure, listFailure.getCause());
    }

    @Test
    void rejectsMissingAndMismatchedPayloadsBehindLiveStamps() {
        TestScoreboardAdapter adapter = new TestScoreboardAdapter();
        adapter.stamps.put("missing", stamp("scoreboard", "missing", false));
        adapter.stamps.put("mismatched", stamp("scoreboard", "mismatched", false));
        adapter.definitions.put("mismatched", new ScoreboardDefinition("other", "Other"));
        ScoreboardRuntimeCapability capability = capability(adapter);

        IllegalStateException missing = assertThrows(IllegalStateException.class, () -> capability.get("missing"));
        IllegalStateException mismatched = assertThrows(IllegalStateException.class, () -> capability.get("mismatched"));

        assertTrue(missing.getCause().getMessage().contains("returned no payload"));
        assertTrue(mismatched.getCause().getMessage().contains("returned the wrong identity"));
    }

    private ScoreboardRuntimeCapability capability(TestScoreboardAdapter adapter) {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        registry.register(adapter);
        return ScoreboardRuntimeCapability.of(registry);
    }

    private static FlowResourceMutationStamp stamp(String type, String id, boolean deleted) {
        UUID mutationId = UUID.nameUUIDFromBytes((type + ':' + id).getBytes(StandardCharsets.UTF_8));
        return new FlowResourceMutationStamp(type, id, 1L, mutationId, HASH, deleted);
    }

    private static final class TestScoreboardAdapter implements FlowResourceAdapter<ScoreboardDefinition> {
        private final Map<String, ScoreboardDefinition> definitions = new LinkedHashMap<>();
        private final Map<String, FlowResourceMutationStamp> stamps = new LinkedHashMap<>();
        private List<String> ids = List.of();
        private RuntimeException stampFailure;

        @Override
        public ReSyncManagedResource descriptor() {
            return ReSyncResourceCatalog.byType(ReSyncResourceCatalog.SCOREBOARD);
        }

        @Override
        public ScoreboardDefinition get(String id) {
            return definitions.get(id);
        }

        @Override
        public List<String> listIds() {
            return ids;
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
            if (stampFailure != null) {
                throw stampFailure;
            }
            return stamps.get(id);
        }
    }
}

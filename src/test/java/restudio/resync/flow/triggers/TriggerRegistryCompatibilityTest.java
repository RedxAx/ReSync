package restudio.resync.flow.triggers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TriggerRegistryCompatibilityTest {
    @TempDir
    Path tempDir;

    @Test
    void triggerUpdatePreservesServerEventBindingsAndAcceptsCommandBindings() {
        TriggerRegistry registry = new TriggerRegistry(tempDir.resolve("triggers.json").toFile());

        TriggerBinding eventBinding = new TriggerBinding("flow:event", "flow", TriggerType.EVENT, "player_join");
        TriggerBinding commandBinding = new TriggerBinding("flow:command", "flow", TriggerType.COMMAND, "{\"command\":\"hello\",\"structured\":true}");
        registry.setBindings(List.of(eventBinding));

        registry.setBindingsPreservingType(List.of(commandBinding), TriggerType.EVENT);

        assertEquals(1, registry.getBindings(TriggerType.EVENT).size());
        assertEquals(1, registry.getBindings(TriggerType.COMMAND).size());
        assertTrue(registry.getBindings().stream().anyMatch(binding -> "flow:event".equals(binding.getId())));
        assertTrue(registry.getBindings().stream().anyMatch(binding -> "flow:command".equals(binding.getId())));
    }

    @Test
    void atomicSaveCanBeReloaded() {
        Path file = tempDir.resolve("triggers.json");
        TriggerRegistry registry = new TriggerRegistry(file.toFile());
        TriggerBinding binding = new TriggerBinding("flow:command", "flow", TriggerType.COMMAND, "hello");

        registry.setBindings(List.of(binding));
        TriggerRegistry reloaded = new TriggerRegistry(file.toFile());

        assertEquals(1, reloaded.getBindings().size());
        assertEquals("flow:command", reloaded.getBindings().getFirst().getId());
    }

    @Test
    void removeFlowBindingsRemovesEveryBindingTypeForFlow() {
        TriggerRegistry registry = new TriggerRegistry(tempDir.resolve("triggers.json").toFile());
        TriggerBinding eventBinding = new TriggerBinding("flow:event", "flow", TriggerType.EVENT, "player_join");
        TriggerBinding commandBinding = new TriggerBinding("flow:command", "flow", TriggerType.COMMAND, "hello");
        TriggerBinding otherBinding = new TriggerBinding("other:command", "other", TriggerType.COMMAND, "other");

        registry.setBindings(List.of(eventBinding, commandBinding, otherBinding));
        registry.removeFlowBindings("flow");

        assertEquals(1, registry.getBindings().size());
        assertEquals("other:command", registry.getBindings().getFirst().getId());
    }
}

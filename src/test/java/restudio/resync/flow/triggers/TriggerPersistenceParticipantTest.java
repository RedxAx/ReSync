package restudio.resync.flow.triggers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.PersistenceOwnershipContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TriggerPersistenceParticipantTest {
    @TempDir
    Path temporary;

    @Test
    void quiesceClosesEveryTriggerBindingWriterAndResumeReopensIt() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        TriggerRegistry registry = registry(source);
        TriggerPersistenceParticipant participant = new TriggerPersistenceParticipant(source, registry);
        TriggerBinding initial = binding("event-primary", TriggerType.EVENT, "player_join");

        registry.setBindings(List.of(initial));
        participant.quiesce();

        assertThrows(IllegalStateException.class, () -> registry.addBinding(binding("system-primary", TriggerType.SYSTEM, "server_start")));
        assertEquals(List.of("event-primary"), registry.getBindings().stream().map(TriggerBinding::getId).toList());

        participant.resume();
        registry.addBinding(binding("system-primary", TriggerType.SYSTEM, "server_start"));

        assertEquals(List.of("event-primary", "system-primary"), registry.getBindings().stream().map(TriggerBinding::getId).toList());
        assertTrue(Files.exists(source.resolve("triggers.json")));
    }

    @Test
    void rebindRequiresQuiescenceAndPreservesThePreviousRootWhenCandidateIsInvalid() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        TriggerRegistry registry = registry(source);
        TriggerPersistenceParticipant participant = new TriggerPersistenceParticipant(source, registry);
        registry.setBindings(List.of(binding("event-primary", TriggerType.EVENT, "player_join"),
            binding("system-primary", TriggerType.SYSTEM, "server_start")));
        Path previousFile = participant.root();

        Path replacement = Files.createDirectory(temporary.resolve("replacement"));
        assertThrows(IOException.class, () -> participant.rebind(replacement));

        Files.writeString(replacement.resolve("triggers.json"), "{invalid");
        participant.quiesce();
        assertThrows(IOException.class, () -> participant.rebind(replacement));
        assertEquals(previousFile, participant.root());
        assertEquals(List.of("event-primary", "system-primary"), registry.getBindings().stream().map(TriggerBinding::getId).toList());

        Path valid = Files.createDirectory(temporary.resolve("valid"));
        TriggerRegistry candidate = registry(valid);
        candidate.setBindings(List.of(binding("event-rebound", TriggerType.EVENT, "player_quit"),
            binding("system-rebound", TriggerType.SYSTEM, "server_stop")));

        participant.rebind(valid);
        participant.resume();
        participant.healthCheck();

        assertEquals(valid.resolve("triggers.json").toAbsolutePath().normalize(), participant.root());
        assertEquals(List.of("event-rebound", "system-rebound"), registry.getBindings().stream().map(TriggerBinding::getId).toList());
    }

    @Test
    void healthCheckRejectsExternalBindingDrift() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        TriggerRegistry registry = registry(source);
        TriggerPersistenceParticipant participant = new TriggerPersistenceParticipant(source, registry);
        registry.setBindings(List.of(binding("event-primary", TriggerType.EVENT, "player_join")));

        Files.writeString(source.resolve("triggers.json"), "[]");

        assertThrows(IOException.class, participant::healthCheck);
    }

    @Test
    void failedResumeKeepsTriggerWritesQuiesced() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        TriggerRegistry registry = registry(source);
        TriggerPersistenceParticipant participant = new TriggerPersistenceParticipant(source, registry);
        registry.setBindings(List.of(binding("event-primary", TriggerType.EVENT, "player_join")));
        participant.quiesce();

        Files.writeString(source.resolve("triggers.json"), "[]");

        assertThrows(IOException.class, participant::resume);
        assertThrows(IllegalStateException.class, () -> registry.removeBinding("event-primary"));
    }

    @Test
    void ownershipIndexClaimsOnlyTheTriggerFile() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("ownership"));
        TriggerRegistry registry = registry(source);
        TriggerPersistenceParticipant participant = new TriggerPersistenceParticipant(source, registry);
        var index = participant.ownershipIndex(new PersistenceOwnershipContext(source, participant.root()));

        assertTrue(index.owns("triggers.json"));
        assertFalse(index.owns("triggers.json.tmp"));
        assertFalse(index.owns("triggers.json/nested"));
    }

    private TriggerRegistry registry(Path root) {
        return new TriggerRegistry(root.resolve("triggers.json").toFile());
    }

    private TriggerBinding binding(String id, TriggerType type, String context) {
        return new TriggerBinding(id, "shared", type, context);
    }
}

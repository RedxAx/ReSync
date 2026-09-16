package restudio.resync.flow.command;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandGraphMetadataTest {
    @Test
    void readsDefaultsAndNormalizesExplicitMetadata() {
        assertEquals(new CommandGraphMetadata("example", false, List.of()), CommandGraphMetadata.from(graph(Map.of())));

        CommandGraphMetadata metadata = CommandGraphMetadata.from(graph(Map.of(
            "commandLabel", " /ReStudio:Reload ",
            "structured", true,
            "commandPaths", List.of(" /Reload now ", "", "/Reload now", "reload later"))));

        assertEquals("reload", metadata.commandLabel());
        assertEquals(true, metadata.structured());
        assertEquals(List.of("/Reload now", "reload later"), metadata.commandPaths());
    }

    @Test
    void rejectsEveryMalformedExplicitField() {
        assertThrows(IllegalArgumentException.class, () -> CommandGraphMetadata.from(graph(Map.of("commandLabel", 5))));
        assertThrows(IllegalArgumentException.class, () -> CommandGraphMetadata.from(graph(Map.of("structured", "true"))));
        assertThrows(IllegalArgumentException.class, () -> CommandGraphMetadata.from(graph(Map.of("commandPaths", "reload"))));
        assertThrows(IllegalArgumentException.class, () -> CommandGraphMetadata.from(graph(Map.of("commandPaths", List.of(5)))));
    }

    @Test
    void applyReplacesOnlyOwnedUnknownFields() {
        GraphDocument original = graph(Map.of(
            "commandLabel", "old",
            "structured", false,
            "commandPaths", List.of("old path"),
            "future", Map.of("nested", List.of(1, 2, 3))));

        GraphDocument applied = new CommandGraphMetadata("/Example", true, List.of("example run", "example run"))
            .apply(original);

        assertEquals("example", applied.unknown().get("commandLabel"));
        assertEquals(true, applied.unknown().get("structured"));
        assertEquals(List.of("example run"), applied.unknown().get("commandPaths"));
        assertEquals(original.unknown().get("future"), applied.unknown().get("future"));
        assertEquals(original.resource(), applied.resource());
        assertEquals(original.revision(), applied.revision());
        assertEquals(original.catalogBinding(), applied.catalogBinding());
    }

    private GraphDocument graph(Map<String, Object> unknown) {
        ContractRef<ResourceTypeId> type = ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("command"));
        ServerResourceLocator resource = new ServerResourceLocator(
            UUID.fromString("11111111-1111-4111-8111-111111111111"), type, "example");
        CatalogBinding binding = new CatalogBinding(1, new ContentHash("a".repeat(64)), new ContentHash("b".repeat(64)));
        return new GraphDocument(new CatalogVersion(1, 0), resource, 7, binding, Set.of(), List.of(), List.of(),
            List.of(), List.of(), OpaqueData.of(unknown));
    }
}

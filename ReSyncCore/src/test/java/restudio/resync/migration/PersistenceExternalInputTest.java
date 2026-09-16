package restudio.resync.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceExternalInputTest {
    @TempDir
    Path temporary;

    @Test
    void classifiesOperatorInputsAsReadOnlyAndExcluded() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("resync"));

        List<PersistenceExternalInput.Input> inputs = PersistenceExternalInput.forDataRoot(root);

        assertEquals(List.of("dataRoot/nodes", "resync.properties"), inputs.stream().map(PersistenceExternalInput.Input::id).toList());
        assertTrue(inputs.stream().allMatch(PersistenceExternalInput.Input::readOnly));
        assertTrue(inputs.stream().allMatch(PersistenceExternalInput.Input::excludedFromPersistence));
        assertEquals(root.resolve("nodes"), inputs.stream().filter(input -> input.id().equals("dataRoot/nodes")).findFirst().orElseThrow().path());
        assertEquals(root.resolve("resync.properties"), inputs.stream().filter(input -> input.id().equals("resync.properties")).findFirst().orElseThrow().path());
    }

    @Test
    void rejectsOutsideAndOverlappingInputClaims() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("resync-validation"));
        PersistenceExternalInput.Input outside = new PersistenceExternalInput.Input(
            "outside", temporary.resolve("outside"), PersistenceExternalInput.Kind.OPERATOR_CATALOG, "external");
        assertThrows(IllegalArgumentException.class, () -> PersistenceExternalInput.validate(root, List.of(outside)));

        PersistenceExternalInput.Input first = new PersistenceExternalInput.Input(
            "first", root.resolve("inputs"), PersistenceExternalInput.Kind.OPERATOR_CATALOG, "external");
        PersistenceExternalInput.Input second = new PersistenceExternalInput.Input(
            "second", root.resolve("inputs/file"), PersistenceExternalInput.Kind.OPERATOR_CONFIGURATION, "external");
        assertThrows(IllegalArgumentException.class, () -> PersistenceExternalInput.validate(root, List.of(first, second)));
    }

    @Test
    void rootValidationSkipsExternalTreesWithoutAssigningAnInternalOwner() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("resync-root"));
        Path nodes = Files.createDirectories(root.resolve("nodes").resolve("extension"));
        Path nodeFile = Files.writeString(nodes.resolve("catalog.json"), "{}");
        Path properties = Files.writeString(root.resolve("resync.properties"), "network.enabled=false\n");
        Path internal = Files.writeString(root.resolve("owned.json"), "{}");
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(root);
        registry.register(new PersistenceParticipant() {
            @Override
            public String owner() {
                return "owned";
            }

            @Override
            public Path root() {
                return internal;
            }
        });
        registry.registerExternalInputs(PersistenceExternalInput.forDataRoot(root));

        registry.validateForRoot(root);

        assertEquals("owned", registry.ownerFor(root, internal));
        assertThrows(MigrationException.class, () -> registry.ownerFor(root, nodeFile));
        assertThrows(MigrationException.class, () -> registry.ownerFor(root, properties));
        assertTrue(registry.isExternalPath(root, nodes));
        assertTrue(registry.isExternalPath(root, properties));
        assertFalse(registry.isExternalPath(root, internal));
    }

    @Test
    void rejectsParticipantsThatClaimOperatorInputs() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("participant-overlap"));
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(root);
        registry.registerExternalInputs(PersistenceExternalInput.forDataRoot(root));

        PersistenceParticipant participant = new PersistenceParticipant() {
            @Override
            public String owner() {
                return "nodes";
            }

            @Override
            public Path root() {
                return root.resolve("nodes");
            }
        };

        assertThrows(IllegalArgumentException.class, () -> registry.register(participant));

        Path reverseRoot = Files.createDirectory(temporary.resolve("participant-overlap-reverse"));
        PersistenceParticipant reverseParticipant = new PersistenceParticipant() {
            @Override
            public String owner() {
                return "nodes";
            }

            @Override
            public Path root() {
                return reverseRoot.resolve("nodes");
            }
        };
        PersistenceParticipantRegistry reverseRegistry = new PersistenceParticipantRegistry(reverseRoot);
        reverseRegistry.register(reverseParticipant);

        assertThrows(IllegalArgumentException.class,
            () -> reverseRegistry.registerExternalInputs(PersistenceExternalInput.forDataRoot(reverseRoot)));
    }

    @Test
    void rejectsScopedParticipantsThatOwnExternalInputsFromAnotherRootRegardlessOfOrder() throws Exception {
        Path beforeRoot = Files.createDirectory(temporary.resolve("scoped-before"));
        PersistenceParticipant beforeParticipant = scopedParticipant(beforeRoot, beforeRoot.resolve("owned"));
        PersistenceParticipantRegistry beforeRegistry = new PersistenceParticipantRegistry(beforeRoot);
        beforeRegistry.register(beforeParticipant);

        assertThrows(IllegalArgumentException.class,
            () -> beforeRegistry.registerExternalInputs(PersistenceExternalInput.forDataRoot(beforeRoot)));
        assertTrue(beforeRegistry.externalInputs().isEmpty());

        Path afterRoot = Files.createDirectory(temporary.resolve("scoped-after"));
        PersistenceParticipant afterParticipant = scopedParticipant(afterRoot, afterRoot.resolve("owned"));
        PersistenceParticipantRegistry afterRegistry = new PersistenceParticipantRegistry(afterRoot);
        afterRegistry.registerExternalInputs(PersistenceExternalInput.forDataRoot(afterRoot));

        assertThrows(IllegalArgumentException.class, () -> afterRegistry.register(afterParticipant));

        Path propertiesRoot = Files.createDirectory(temporary.resolve("scoped-properties"));
        PersistenceParticipant propertiesParticipant = scopedParticipant(propertiesRoot, propertiesRoot.resolve("owned"),
            propertiesRoot.resolve("resync.properties"));
        PersistenceParticipantRegistry propertiesRegistry = new PersistenceParticipantRegistry(propertiesRoot);
        propertiesRegistry.register(propertiesParticipant);

        assertThrows(IllegalArgumentException.class,
            () -> propertiesRegistry.registerExternalInputs(PersistenceExternalInput.forDataRoot(propertiesRoot)));
    }

    @Test
    void snapshotsExcludeExternalInputsAndDoNotRewriteThem() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("snapshot-source"));
        Path nodes = Files.createDirectories(root.resolve("nodes").resolve("extension"));
        Files.writeString(nodes.resolve("catalog.json"), "{}");
        Files.writeString(root.resolve("resync.properties"), "network.enabled=false\n");
        Path internal = Files.writeString(root.resolve("owned.json"), "owned");
        Path staging = temporary.resolve("snapshot-staging");
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(root);
        registry.register(new PersistenceParticipant() {
            @Override
            public String owner() {
                return "owned";
            }

            @Override
            public Path root() {
                return internal;
            }
        });
        registry.registerExternalInputs(PersistenceExternalInput.forDataRoot(root));

        Snapshot snapshot = new SnapshotService(new MigrationFence()).createFenced(
            root, staging, SnapshotMetadata.preflight(), registry);

        assertEquals(List.of("owned.json"), snapshot.manifest().entries().stream().map(SnapshotManifest.Entry::relativePath).toList());
        assertFalse(Files.exists(staging.resolve("nodes")));
        assertFalse(Files.exists(staging.resolve("resync.properties")));
        assertTrue(Files.exists(staging.resolve("owned.json")));
    }

    private static PersistenceParticipant scopedParticipant(Path root, Path participantRoot) {
        return scopedParticipant(root, participantRoot, root.resolve("nodes"));
    }

    private static PersistenceParticipant scopedParticipant(Path root, Path participantRoot, Path externalInputPath) {
        Path externalInput = externalInputPath.toAbsolutePath().normalize();
        return new PersistenceParticipant() {
            @Override
            public String owner() {
                return "scoped";
            }

            @Override
            public Path root() {
                return participantRoot;
            }

            @Override
            public boolean owns(Path file) {
                return MigrationPaths.requirePath(file, "file").equals(externalInput);
            }
        };
    }
}

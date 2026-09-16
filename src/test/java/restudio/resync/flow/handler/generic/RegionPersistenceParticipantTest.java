package restudio.resync.flow.handler.generic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.migration.PersistenceOwnershipContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionPersistenceParticipantTest {
    private static final String CLIPBOARD = "{\"blockTypes\":[[[\"STONE\"]]],\"blockDataStrings\":[[[\"minecraft:stone\"]]],\"minX\":0,\"minY\":0,\"minZ\":0,\"sizeX\":1,\"sizeY\":1,\"sizeZ\":1}";

    @Test
    void ownsOnlyFlowRegionsAndUsesAtomicPersistence(@TempDir Path temporary) throws Exception {
        RegionHandler handler = new RegionHandler();
        RegionPersistenceParticipant participant = new RegionPersistenceParticipant(temporary, handler);
        Path file = participant.resolve("nested/stone.json");

        participant.save(file, CLIPBOARD).join();
        participant.flush();
        participant.healthCheck();

        assertEquals("resync.flow-regions", participant.owner());
        assertEquals(temporary.resolve("flow-regions").toAbsolutePath().normalize(), participant.root());
        assertEquals(temporary.toAbsolutePath().normalize(), participant.rebindScope());
        assertTrue(participant.owns(file));
        assertTrue(participant.owns(participant.root()));
        PersistenceOwnershipContext context = new PersistenceOwnershipContext(temporary, participant.root());
        var ownership = participant.ownershipIndex(context);
        assertTrue(ownership.owns(context.participantRootRelative()));
        assertTrue(ownership.owns(context.relativeToSource(file)));
        assertFalse(ownership.owns(RegionPersistenceParticipant.DIRECTORY + "-other/stone.json"));
        assertEquals(CLIPBOARD, participant.load(file).join());

        participant.close();
    }

    @Test
    void quiesceClosesMutationAdmissionAndResumeReopensIt(@TempDir Path temporary) throws Exception {
        RegionPersistenceParticipant participant = new RegionPersistenceParticipant(temporary, new RegionHandler());
        Path file = participant.resolve("clipboard.json");
        participant.save(file, CLIPBOARD).join();

        participant.quiesce();

        assertThrows(IllegalStateException.class, () -> participant.save(file, CLIPBOARD));

        participant.resume();
        participant.save(file, CLIPBOARD).join();
        participant.close();
    }

    @Test
    void serializesWritesPerFile(@TempDir Path temporary) throws Exception {
        RegionPersistenceParticipant participant = new RegionPersistenceParticipant(temporary, new RegionHandler());
        Path file = participant.resolve("clipboard.json");

        var first = participant.save(file, CLIPBOARD);
        var second = participant.save(file, CLIPBOARD.replace("STONE", "DIRT").replace("minecraft:stone", "minecraft:dirt"));
        first.join();
        second.join();

        assertTrue(Files.readString(file).contains("DIRT"));
        participant.close();
    }

    @Test
    void quiesceDrainsQueuedTasksAndCloseCancelsQueuedTasks(@TempDir Path temporary) throws Exception {
        List<Runnable> queued = new CopyOnWriteArrayList<>();
        RegionPersistenceParticipant participant = new RegionPersistenceParticipant(temporary, new RegionHandler(), queued::add);
        Path file = participant.resolve("clipboard.json");
        var pending = participant.save(file, CLIPBOARD);

        CompletableFuture<Void> quiesced = CompletableFuture.runAsync(() -> {
            try {
                participant.quiesce();
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        });
        queued.getFirst().run();
        quiesced.join();
        assertTrue(pending.isDone());
        assertTrue(Files.exists(file));

        participant.resume();
        var cancelled = participant.save(file, CLIPBOARD);
        participant.close();
        assertTrue(cancelled.isCancelled());
        if (queued.size() > 1) {
            queued.get(1).run();
        }
    }

    @Test
    void failedCandidateRebindRetainsThePreviousRoot(@TempDir Path temporary) throws Exception {
        RegionHandler handler = new RegionHandler();
        RegionPersistenceParticipant participant = new RegionPersistenceParticipant(temporary, handler);
        Path originalRoot = participant.root();
        Path candidateScope = Files.createDirectory(temporary.resolve("candidate"));
        Path candidateRoot = Files.createDirectory(candidateScope.resolve("flow-regions"));
        Files.writeString(candidateRoot.resolve("broken.json"), "{not-json");

        participant.quiesce();

        assertThrows(IOException.class, () -> participant.rebind(candidateScope));
        assertEquals(originalRoot, participant.root());

        participant.resume();
        participant.healthCheck();
        participant.close();
    }

    @Test
    void validCandidateRebindUpdatesTheBoundRoot(@TempDir Path temporary) throws Exception {
        RegionPersistenceParticipant participant = new RegionPersistenceParticipant(temporary, new RegionHandler());
        Path candidateScope = Files.createDirectory(temporary.resolve("candidate"));
        Path candidateRoot = Files.createDirectory(candidateScope.resolve("flow-regions"));
        Path candidateFile = candidateRoot.resolve("candidate.json");
        Files.writeString(candidateFile, CLIPBOARD);

        participant.quiesce();
        participant.rebind(candidateScope);

        assertEquals(candidateRoot.toAbsolutePath().normalize(), participant.root());
        participant.resume();
        assertEquals(CLIPBOARD, participant.load(candidateFile).join());
        participant.close();
    }

    @Test
    void rejectsSymlinkTraversal(@TempDir Path temporary) throws Exception {
        RegionPersistenceParticipant participant = new RegionPersistenceParticipant(temporary, new RegionHandler());
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Path link = participant.root().resolve("link");
        AtomicBoolean symlinkCreated = new AtomicBoolean();
        try {
            Files.createSymbolicLink(link, outside);
            symlinkCreated.set(true);
        } catch (UnsupportedOperationException | IOException | SecurityException ignored) {
        }
        if (symlinkCreated.get()) {
            assertThrows(RuntimeException.class, () -> participant.resolve("link/clipboard.json"));
        }
        participant.close();
    }

    @Test
    void handlerQueuesImmediateLoadBehindSaveCreation(@TempDir Path temporary) throws Exception {
        List<Runnable> queued = new CopyOnWriteArrayList<>();
        RegionHandler handler = new RegionHandler();
        RegionPersistenceParticipant participant = new RegionPersistenceParticipant(temporary, handler, queued::add);
        Path file = participant.resolve("clipboard.json");

        handler.saveClipboard(file, CLIPBOARD);
        CompletableFuture<String> loaded = handler.loadClipboard(file);

        queued.get(0).run();
        queued.get(1).run();

        assertEquals(CLIPBOARD, loaded.join());
        participant.close();
    }

    @Test
    void rejectsNonCanonicalClipboardJson() {
        assertThrows(IllegalArgumentException.class,
            () -> RegionHandler.validateClipboardJson(CLIPBOARD.replace("\"sizeZ\":1}", "\"sizeZ\":1,\"unknown\":1}")));
        assertThrows(IllegalArgumentException.class,
            () -> RegionHandler.validateClipboardJson(CLIPBOARD.replace("\"minX\":0,", "\"minX\":0,\"minX\":0,")));
        assertThrows(IllegalArgumentException.class,
            () -> RegionHandler.validateClipboardJson(CLIPBOARD.replace("\"minX\":0,\"minY\":0", "\"minY\":0,\"minX\":0")));
        assertThrows(IllegalArgumentException.class,
            () -> RegionHandler.validateClipboardJson(" " + CLIPBOARD));
        assertThrows(IllegalArgumentException.class,
            () -> RegionHandler.validateClipboardJson(CLIPBOARD.replace("STONE", "NOT_A_MATERIAL")));
        assertThrows(IllegalArgumentException.class,
            () -> RegionHandler.validateClipboardJson(CLIPBOARD.replace("STONE", "DIRT")));
        assertThrows(IllegalArgumentException.class,
            () -> RegionHandler.validateClipboardJson(CLIPBOARD.replace("minecraft:stone", "minecraft:stone[invalid=true]")));
        assertThrows(IllegalArgumentException.class,
            () -> RegionHandler.validateClipboardJson(CLIPBOARD.replace("\"sizeX\":1", "\"sizeX\":0")));
        assertThrows(IllegalArgumentException.class,
            () -> RegionHandler.validateClipboardJson(CLIPBOARD.replace("\"minX\":0", "\"minX\":2147483647").replace("\"sizeX\":1", "\"sizeX\":2")));
    }

    @Test
    void retainsAsyncFailuresUntilFlushAcknowledgesThem(@TempDir Path temporary) throws Exception {
        RegionPersistenceParticipant participant = new RegionPersistenceParticipant(temporary, new RegionHandler(), command -> {
            throw new RejectedExecutionException("test rejection");
        });
        Path file = participant.resolve("clipboard.json");

        assertThrows(CompletionException.class, () -> participant.save(file, CLIPBOARD).join());
        assertThrows(IOException.class, participant::flush);
        participant.flush();
        participant.close();
    }

    @Test
    void rejectsAncestorAndPortableCaseCollisions(@TempDir Path temporary) throws Exception {
        RegionPersistenceParticipant participant = new RegionPersistenceParticipant(temporary, new RegionHandler());
        Path file = participant.resolve("Folder.json");
        participant.save(file, CLIPBOARD).join();

        CompletableFuture<Void> descendant = participant.save(participant.resolve("Folder.json/child.json"), CLIPBOARD);
        assertThrows(CompletionException.class, descendant::join);
        CompletableFuture<Void> caseCollision = participant.save(participant.resolve("folder.json"), CLIPBOARD);
        assertThrows(CompletionException.class, caseCollision::join);
        assertThrows(IOException.class, participant::flush);
        participant.flush();
        participant.close();
    }

    @Test
    void bindIsIdempotentAndDetachedHandlerCannotRebind(@TempDir Path temporary) throws Exception {
        RegionHandler handler = new RegionHandler();
        RegionPersistenceParticipant first = handler.bindPersistence(temporary);
        assertEquals(first, handler.bindPersistence(temporary));
        assertThrows(IllegalStateException.class, () -> handler.bindPersistence(temporary.resolve("other")));

        handler.shutdown();
        assertFalse(first.isClosed());
        assertThrows(IllegalStateException.class, () -> handler.bindPersistence(temporary));
        first.close();
    }

    @Test
    void rejectsCaseCollisionsInCandidates(@TempDir Path temporary) throws Exception {
        RegionPersistenceParticipant participant = new RegionPersistenceParticipant(temporary, new RegionHandler());
        Path candidateScope = Files.createDirectory(temporary.resolve("candidate-case"));
        Path candidateRoot = Files.createDirectory(candidateScope.resolve("flow-regions"));
        Files.writeString(candidateRoot.resolve("A.json"), CLIPBOARD);
        Files.writeString(candidateRoot.resolve("a.json"), CLIPBOARD);

        if (candidateRoot.resolve("A.json").toRealPath().equals(candidateRoot.resolve("a.json").toRealPath())) {
            participant.close();
            return;
        }

        assertThrows(IOException.class, () -> participant.validateCandidate(candidateScope));
        participant.close();
    }
}

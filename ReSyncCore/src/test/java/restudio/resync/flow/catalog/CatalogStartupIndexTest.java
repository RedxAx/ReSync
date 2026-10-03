package restudio.resync.flow.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.identity.ContentHash;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogStartupIndexTest {
    @TempDir
    Path directory;

    @Test
    void storeRestoresDerivedSnapshotAndRebasesGeneration() {
        ContentHash binding = ContentHash.of("b".repeat(64));
        String fingerprint = CatalogStartupIndex.fingerprint(List.of("source-a"), binding.canonicalText(), "1.0", 1);
        CatalogCanonicalizer.DerivedSnapshot stored = snapshot(4, binding);

        CatalogStartupIndex.store(directory, fingerprint, 4, stored);

        CatalogCanonicalizer.DerivedSnapshot restored = CatalogStartupIndex.find(directory, fingerprint, 4).orElseThrow();
        assertEquals(stored.contentChecksum(), restored.contentChecksum());
        assertEquals(binding, restored.bindingManifestHash());
        assertEquals(stored.canonicalContent(), restored.canonicalContent());

        CatalogCanonicalizer.DerivedSnapshot rebased = CatalogStartupIndex.find(directory, fingerprint, 10).orElseThrow();
        assertEquals(CatalogCanonicalizer.rebaseSnapshotGeneration(stored.canonicalContent(), 10), rebased.canonicalContent());
        assertTrue(CatalogStartupIndex.find(directory, fingerprint + "other", 4).isEmpty());
    }

    @Test
    void coldRestartVerifiesAndRestoresTheAtomicRecord() {
        ContentHash binding = ContentHash.of("b".repeat(64));
        CatalogCanonicalizer.DerivedSnapshot stored = snapshot(4, binding);
        String fingerprint = fingerprint(binding);
        CatalogStartupIndex.store(directory, fingerprint, 4, stored);
        CatalogStartupIndex.invalidate(directory);

        CatalogCanonicalizer.DerivedSnapshot restored = CatalogStartupIndex.find(directory, fingerprint, 9).orElseThrow();

        assertEquals(stored.contentChecksum(), restored.contentChecksum());
        assertEquals(CatalogCanonicalizer.rebaseSnapshotGeneration(stored.canonicalContent(), 9), restored.canonicalContent());
    }

    @Test
    void identicalFingerprintDoesNotCrossDirectoryAuthority() {
        ContentHash binding = ContentHash.of("b".repeat(64));
        String fingerprint = fingerprint(binding);
        CatalogStartupIndex.store(directory, fingerprint, 4, snapshot(4, binding));

        assertTrue(CatalogStartupIndex.find(directory.resolve("other"), fingerprint, 4).isEmpty());
    }

    @Test
    void corruptedColdRecordIsNotAdmitted() throws Exception {
        ContentHash binding = ContentHash.of("b".repeat(64));
        String fingerprint = fingerprint(binding);
        CatalogStartupIndex.store(directory, fingerprint, 4, snapshot(4, binding));
        Path file = directory.resolve(CatalogStartupIndex.FILE_NAME);
        Files.writeString(file, Files.readString(file).replace("\"kind\":\"snapshot\"", "\"kind\":\"tampered\""));
        CatalogStartupIndex.invalidate(directory);

        assertTrue(CatalogStartupIndex.find(directory, fingerprint, 4).isEmpty());
    }

    @Test
    void incompleteRecordIsNotAdmittedAndStagedWorkDoesNotReplaceLiveSnapshot() throws Exception {
        ContentHash binding = ContentHash.of("b".repeat(64));
        String fingerprint = fingerprint(binding);
        CatalogCanonicalizer.DerivedSnapshot stored = snapshot(4, binding);
        CatalogStartupIndex.store(directory, fingerprint, 4, stored);
        Path file = directory.resolve(CatalogStartupIndex.FILE_NAME);
        String record = Files.readString(file);
        Files.writeString(directory.resolve(".resync-interrupted.tmp"), record.substring(0, record.length() / 2));
        CatalogStartupIndex.invalidate(directory);

        assertEquals(stored.canonicalContent(), CatalogStartupIndex.find(directory, fingerprint, 4).orElseThrow().canonicalContent());
        Files.writeString(file, record.substring(0, record.length() / 2));
        CatalogStartupIndex.invalidate(directory);
        assertTrue(CatalogStartupIndex.find(directory, fingerprint, 4).isEmpty());
    }

    @Test
    void publicationIsRejectedAfterDirectoryRebindWhileColdVerificationRuns() throws Exception {
        ContentHash binding = ContentHash.of("b".repeat(64));
        String fingerprint = fingerprint(binding);
        CatalogCanonicalizer.DerivedSnapshot original = snapshot(4, binding);
        CatalogCanonicalizer.DerivedSnapshot replacement = snapshot(9, binding);
        CatalogStartupIndex.store(directory, fingerprint, 4, original);
        CatalogStartupIndex.invalidate(directory);
        CountDownLatch verifying = new CountDownLatch(1);
        CountDownLatch rebound = new CountDownLatch(1);
        CompletableFuture<Optional<CatalogCanonicalizer.DerivedSnapshot>> pending = CompletableFuture.supplyAsync(() ->
            CatalogStartupIndex.find(directory, fingerprint, 4, () -> {
                verifying.countDown();
                try {
                    if (!rebound.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Catalog Authority Was Not Rebound");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
                return original;
            }));

        try {
            assertTrue(verifying.await(5, TimeUnit.SECONDS));
            CatalogStartupIndex.invalidate(directory);
            CatalogStartupIndex.store(directory, fingerprint, 9, replacement);
        } finally {
            rebound.countDown();
        }

        assertTrue(pending.get(5, TimeUnit.SECONDS).isEmpty());
        assertEquals(replacement.canonicalContent(), CatalogStartupIndex.find(directory, fingerprint, 9,
            () -> replacement).orElseThrow().canonicalContent());
    }

    private static String fingerprint(ContentHash binding) {
        return CatalogStartupIndex.fingerprint(List.of("source-a"), binding.canonicalText(), "1.0", 0);
    }

    private static CatalogCanonicalizer.DerivedSnapshot snapshot(long generation, ContentHash binding) {
        return CatalogCanonicalizer.deriveSnapshot(generation, new CatalogVersion(1, 0), List.of(), Set.of(), List.of(), binding);
    }
}

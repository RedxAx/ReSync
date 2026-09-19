package restudio.resync.flow.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.identity.ContentHash;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogStartupIndexTest {
    @TempDir
    Path directory;

    @Test
    void storeRestoresDerivedSnapshotAndRebasesGeneration() {
        ContentHash checksum = ContentHash.of("a".repeat(64));
        ContentHash binding = ContentHash.of("b".repeat(64));
        String canonical = "{\"generation\":4,\"kind\":\"snapshot\"}";
        String fingerprint = CatalogStartupIndex.fingerprint(List.of("source-a"), binding.canonicalText(), "1.0", 1);
        CatalogCanonicalizer.DerivedSnapshot stored = CatalogCanonicalizer.derivedSnapshot(checksum, binding, canonical);

        CatalogStartupIndex.store(directory, fingerprint, 4, stored);

        CatalogCanonicalizer.DerivedSnapshot restored = CatalogStartupIndex.find(directory, fingerprint, 4).orElseThrow();
        assertEquals(checksum, restored.contentChecksum());
        assertEquals(binding, restored.bindingManifestHash());
        assertEquals(canonical, restored.canonicalContent());

        CatalogCanonicalizer.DerivedSnapshot rebased = CatalogStartupIndex.find(directory, fingerprint, 10).orElseThrow();
        assertEquals(CatalogCanonicalizer.rebaseSnapshotGeneration(canonical, 10), rebased.canonicalContent());
        assertTrue(CatalogStartupIndex.find(directory, fingerprint + "other", 4).isEmpty());
    }
}

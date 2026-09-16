package restudio.resync.flow.catalog;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.identity.ContentHash;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CatalogCompilerCanonicalizationTest {
    private static final CatalogVersion CONTRACT = new CatalogVersion(1, 0);

    @Test
    void compiledSnapshotMatchesThePublicCanonicalContract() {
        CatalogSnapshot snapshot = new CatalogCompiler(CONTRACT).compile(List.of(), 7).snapshot().orElseThrow();
        ContentHash bindingManifestHash = CatalogCanonicalizer.bindingManifestHash(List.of());

        assertEquals(CatalogCanonicalizer.contentChecksum(7, CONTRACT, List.of(), Set.of(), List.of()), snapshot.contentChecksum());
        assertEquals(CatalogCanonicalizer.canonicalSnapshotContent(7, CONTRACT, List.of(), Set.of(), List.of(), bindingManifestHash), snapshot.canonicalContent());
        assertEquals(bindingManifestHash, snapshot.bindingManifestHash());
        assertArrayEquals(snapshot.canonicalContent().getBytes(StandardCharsets.UTF_8), snapshot.canonicalBytes());
        assertEquals(snapshot.contentChecksum(), CatalogCanonicalizer.checksumForCanonicalContent(snapshot.canonicalContent()));
    }

    @Test
    void publicSnapshotConstructionStillRejectsTamperedCanonicalContent() {
        CatalogSnapshot snapshot = new CatalogCompiler(CONTRACT).compile(List.of(), 1).snapshot().orElseThrow();
        String tampered = snapshot.canonicalContent().replace("\"kind\":\"snapshot\"", "\"kind\":\"tampered\"");

        assertThrows(IllegalArgumentException.class, () -> new CatalogSnapshot(
            1,
            CONTRACT,
            snapshot.contentChecksum(),
            snapshot.bindingManifestHash(),
            snapshot.minimumClientCapabilities(),
            List.of(),
            snapshot.definitions(),
            snapshot.types(),
            snapshot.conversions(),
            snapshot.categories(),
            snapshot.inspectors(),
            snapshot.capabilities(),
            snapshot.runtimeRequirements(),
            snapshot.migrations(),
            snapshot.provenance(),
            snapshot.diagnostics(),
            tampered));
    }

    @Test
    void generationRebasePreservesValidatedContent() {
        CatalogSnapshot snapshot = new CatalogCompiler(CONTRACT).compile(List.of(), 7).snapshot().orElseThrow();

        CatalogSnapshot rebased = snapshot.withGeneration(8);

        assertEquals(8, rebased.generation());
        assertEquals(snapshot.contentChecksum(), rebased.contentChecksum());
        assertEquals(snapshot.bindingManifestHash(), rebased.bindingManifestHash());
        assertEquals(CatalogCanonicalizer.canonicalSnapshotContent(8, CONTRACT, List.of(), Set.of(), List.of(),
            snapshot.bindingManifestHash()), rebased.canonicalContent());
        assertEquals(snapshot, snapshot.withGeneration(7));
        assertThrows(IllegalArgumentException.class, () -> snapshot.withGeneration(0));
    }
}

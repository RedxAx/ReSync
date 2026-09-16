package restudio.resync.upgrade.lifecycle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.upgrade.TypedLifecycleMigrationAdapter.Adaptation;
import restudio.resync.upgrade.TypedLifecycleMigrationAdapter.Change;
import restudio.resync.upgrade.TypedLifecycleMigrationAdapter.Input;
import restudio.resync.upgrade.TypedLifecycleMigrationAdapter.SourceFile;

class CustomContentMigrationAdapterTest {
    private static final String ITEMS = "assets/Content/Items/item.json";
    private static final String ARMOR = "assets/Content/Armor/armor.json";
    private static final String BLOCKS = "assets/Content/Blocks/block.json";
    private static final String PROJECTILES = "assets/Content/Projectiles/projectile.json";

    @TempDir
    Path root;

    @Test
    void claimsEveryTypedSubtypeAndPreservesOpaqueGraphPayload() throws Exception {
        Map<String, byte[]> payloads = Map.of(
            ITEMS, content("item", "item", "opaque-item"),
            ARMOR, content("armor", "armor", "opaque-armor"),
            BLOCKS, content("block", "block", "opaque-block"),
            PROJECTILES, content("projectile", "projectile", "opaque-projectile"));
        List<SourceFile> sources = write(payloads);

        Adaptation result = new CustomContentMigrationAdapter().adapt(input(sources));

        assertEquals(CustomContentMigrationAdapter.ID, new CustomContentMigrationAdapter().adapterId());
        assertEquals(List.of(ITEMS, ARMOR, BLOCKS, PROJECTILES).stream().sorted().toList(),
            result.claims().stream().map(claim -> claim.relativePath()).toList());
        assertTrue(result.claims().stream().allMatch(claim -> claim.owner().equals(ProductionPersistenceOwners.FLOW_ASSETS)));
        assertTrue(result.changes().isEmpty());
        assertTrue(result.quarantineRecords().isEmpty());
        assertArrayEquals(payloads.get(PROJECTILES), Files.readAllBytes(root.resolve(PROJECTILES)));
    }

    @Test
    void relocatesOneLegacyAliasAndReplansCanonicalOutputWithoutChanges() throws Exception {
        String alias = "assets/Content/Blocks/custom_content__sofa.json";
        byte[] bytes = content("sofa", "block", "opaque-sofa");
        List<SourceFile> firstSources = write(Map.of(alias, bytes));

        Adaptation first = new CustomContentMigrationAdapter().adapt(input(firstSources));

        assertEquals(1, first.changes().size());
        Change relocation = first.changes().getFirst();
        assertEquals("move", relocation.kind());
        assertEquals(alias, relocation.sourcePath());
        assertEquals("assets/Content/Blocks/sofa.json", relocation.targetPath());
        assertArrayEquals(bytes, relocation.targetBytes());
        assertTrue(first.quarantineRecords().isEmpty());

        Path target = root.resolve(relocation.targetPath());
        Files.createDirectories(target.getParent());
        Files.write(target, relocation.targetBytes());
        Files.delete(root.resolve(relocation.sourcePath()));
        Adaptation second = new CustomContentMigrationAdapter().adapt(input(writeExisting(target, relocation.targetPath())));

        assertTrue(second.changes().isEmpty());
        assertTrue(second.quarantineRecords().isEmpty());
        assertArrayEquals(bytes, Files.readAllBytes(target));
    }

    @Test
    void preservesAValidDoubleUnderscoreContentKey() throws Exception {
        String path = "assets/Content/Items/custom_content__literal.json";
        Adaptation result = new CustomContentMigrationAdapter().adapt(input(write(Map.of(
            path, content("custom_content__literal", "item", "opaque-literal")))));

        assertTrue(result.changes().isEmpty());
        assertTrue(result.quarantineRecords().isEmpty());
    }

    @Test
    void rejectsSubtypePathAndLineageViolationsWithoutPartialChanges() throws Exception {
        String path = "assets/Content/Items/bad.json";
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("resourceType", "custom_content");
        value.put("id", "bad");
        value.put("type", "block");
        value.put("assetRevision", 2);
        value.put("resourceRevision", 3);
        value.put("assetMutationId", "opaque");
        value.put("graph", Map.of("id", "flow.bad"));
        Adaptation result = new CustomContentMigrationAdapter().adapt(input(write(Map.of(path, CanonicalJson.canonicalBytes(value)))));

        assertTrue(result.changes().isEmpty());
        assertEquals(1, result.quarantineRecords().size());
        assertEquals(CustomContentMigrationAdapter.CODE_PAYLOAD_INVALID, result.quarantineRecords().getFirst().code());

        value.put("type", "item");
        Adaptation lineage = new CustomContentMigrationAdapter().adapt(input(write(Map.of(path, CanonicalJson.canonicalBytes(value)))));
        assertTrue(lineage.changes().isEmpty());
        assertEquals(CustomContentMigrationAdapter.CODE_LINEAGE_INVALID, lineage.quarantineRecords().getFirst().code());
    }

    @Test
    void rejectsCaseAliasesAndCanonicalAliasConflicts() throws Exception {
        String upper = "assets/Content/Items/Foo.json";
        String lower = "assets/Content/Items/foo.json";
        String alias = "assets/Content/Items/custom_content__same.json";
        String canonical = "assets/Content/Items/same.json";
        Map<String, byte[]> payloads = new LinkedHashMap<>();
        payloads.put(upper, content("Foo", "item", "opaque-upper"));
        payloads.put(lower, content("foo", "item", "opaque-lower"));
        payloads.put(alias, content("same", "item", "opaque-alias"));
        payloads.put(canonical, content("same", "item", "opaque-canonical"));

        Adaptation result = new CustomContentMigrationAdapter().adapt(input(write(payloads)));

        assertTrue(result.changes().isEmpty());
        assertEquals(4, result.quarantineRecords().size());
        assertEquals(2, result.quarantineRecords().stream()
            .filter(record -> record.code().equals(CustomContentMigrationAdapter.CODE_CASE_ALIAS)).count());
        assertEquals(2, result.quarantineRecords().stream()
            .filter(record -> record.code().equals(CustomContentMigrationAdapter.CODE_ALIAS_CONFLICT)).count());
    }

    @Test
    void rejectsUnsafePathAndLivePayloadTombstoneMarkers() throws Exception {
        String unsafePath = "assets/content/Items/unsafe.json";
        String deletedPath = "assets/Content/Items/deleted.json";
        Map<String, Object> deleted = new LinkedHashMap<>();
        deleted.put("resourceType", "custom_content");
        deleted.put("id", "deleted");
        deleted.put("type", "item");
        deleted.put("assetRevision", 4);
        deleted.put("assetMutationId", "opaque-delete");
        deleted.put("deleted", true);
        Adaptation result = new CustomContentMigrationAdapter().adapt(input(write(Map.of(
            unsafePath, content("unsafe", "item", "opaque-unsafe"),
            deletedPath, CanonicalJson.canonicalBytes(deleted)))));

        assertTrue(result.changes().isEmpty());
        assertEquals(2, result.quarantineRecords().size());
        assertTrue(result.quarantineRecords().stream().anyMatch(record -> record.code().equals(CustomContentMigrationAdapter.CODE_PATH_INVALID)));
        assertTrue(result.quarantineRecords().stream().anyMatch(record -> record.code().equals(CustomContentMigrationAdapter.CODE_TOMBSTONE_INVALID)));
    }

    private Input input(List<SourceFile> sources) {
        return new Input(root, SnapshotMetadata.preflight(), "0".repeat(64), sources);
    }

    private List<SourceFile> write(Map<String, byte[]> payloads) throws Exception {
        List<SourceFile> sources = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : payloads.entrySet()) {
            Path target = root.resolve(entry.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, entry.getValue());
            sources.add(source(entry.getKey(), entry.getValue()));
        }
        return sources;
    }

    private List<SourceFile> writeExisting(Path target, String relativePath) throws Exception {
        return List.of(source(relativePath, Files.readAllBytes(target)));
    }

    private SourceFile source(String relativePath, byte[] bytes) throws Exception {
        return new SourceFile(relativePath, bytes.length, HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(bytes)), ProductionPersistenceOwners.FLOW_ASSETS);
    }

    private static byte[] content(String id, String subtype, String mutation) {
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("id", "flow." + id);
        graph.put("futureGraph", Map.of("unknown", List.of(true, 7, "keep")));
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("resourceType", "custom_content");
        value.put("id", id);
        value.put("key", id);
        value.put("type", subtype);
        value.put("flowId", "flow." + id);
        value.put("assetRevision", 1);
        value.put("assetMutationId", mutation);
        value.put("graph", graph);
        value.put("futureDefinition", Map.of("preserve", true));
        return CanonicalJson.canonicalBytes(value);
    }
}

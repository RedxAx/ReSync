package restudio.resync.storage;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetTransactionManagerExactMutationTest {
    private static final Gson GSON = new Gson();

    @TempDir
    Path tempDir;

    @Test
    void mutationReplayRequiresTheSameNormalizedOperationsAndPayloads() throws Exception {
        Path assets = tempDir.resolve("assets");
        Path target = assets.resolve("Blueprints").resolve("Flows").resolve("main.json");
        Path second = assets.resolve("Blueprints").resolve("Functions").resolve("main.json");
        Files.createDirectories(target.getParent());
        Files.createDirectories(second.getParent());
        AssetTransactionManager manager = new AssetTransactionManager(assets, GSON);
        UUID mutation = UUID.fromString("11111111-1111-4111-8111-111111111111");
        String content = "{\"value\":1}";

        String first = manager.commit(Map.of(target, content), mutation.toString());
        Path normalizedAlias = target.getParent().resolve("nested").resolve("..").resolve(target.getFileName());
        String replay = manager.commit(Map.of(normalizedAlias, content), mutation.toString());

        assertEquals(first, replay);
        assertThrows(IOException.class,
            () -> manager.commit(Map.of(second, content), mutation.toString()));
        assertThrows(IOException.class,
            () -> manager.commit(Map.of(target, "{\"value\":2}"), mutation.toString()));
        assertThrows(IOException.class,
            () -> manager.commit(Map.of(), Map.of(target, content.getBytes(StandardCharsets.UTF_8)), Set.of(),
                mutation.toString()));
        assertEquals(content, Files.readString(target));
    }

    @Test
    void deleteReplayIsBoundToItsTarget() throws Exception {
        Path assets = tempDir.resolve("assets");
        Path target = assets.resolve("Blueprints").resolve("Flows").resolve("delete.json");
        Path other = assets.resolve("Blueprints").resolve("Flows").resolve("other.json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "old");
        Files.writeString(other, "other");
        AssetTransactionManager manager = new AssetTransactionManager(assets, GSON);
        String mutationId = "44444444-4444-4444-8444-444444444444";

        String first = manager.commit(Map.of(), Set.of(target), mutationId);
        String replay = manager.commit(Map.of(), Set.of(target.normalize()), mutationId);

        assertEquals(first, replay);
        assertFalse(Files.exists(target));
        assertThrows(IOException.class, () -> manager.commit(Map.of(), Set.of(other), mutationId));
    }

    @Test
    void descriptorOrderingAndVisibilityAreCanonical() throws Exception {
        Path assets = tempDir.resolve("assets");
        Path firstTarget = assets.resolve("a.json");
        Path secondTarget = assets.resolve("b.bin");
        AssetTransactionManager manager = new AssetTransactionManager(assets, GSON);
        Map<Path, String> firstWrites = new LinkedHashMap<>();
        firstWrites.put(secondTarget, "ignored");
        firstWrites.put(firstTarget, "one");
        Map<Path, String> secondWrites = new LinkedHashMap<>();
        secondWrites.put(firstTarget, "one");
        secondWrites.put(secondTarget, "ignored");
        AssetTransactionManager.TransactionDescriptor firstDescriptor = manager.descriptor(firstWrites, Set.of());
        AssetTransactionManager.TransactionDescriptor secondDescriptor = manager.descriptor(secondWrites, Set.of());

        assertEquals(firstDescriptor.fingerprint(), secondDescriptor.fingerprint());
        assertEquals("a.json", firstDescriptor.operations().getFirst().resource());
        assertNotEquals(firstDescriptor.fingerprint(), manager.descriptor(Map.of(firstTarget, "two"), Set.of()).fingerprint());

        String mutationId = "22222222-2222-4222-8222-222222222222";
        AssetTransactionManager.PhaseTiming before = manager.phaseTiming();
        String transactionId = manager.commit(firstWrites, mutationId);
        AssetTransactionManager.PhaseTiming after = manager.phaseTiming();
        assertEquals(before.commitAttemptCount() + 1L, after.commitAttemptCount());
        assertEquals(before.prepareAttemptCount() + 1L, after.prepareAttemptCount());
        assertEquals(before.applyAttemptCount() + 1L, after.applyAttemptCount());
        assertEquals(before.journalDurabilityAttemptCount() + 2L, after.journalDurabilityAttemptCount());
        assertTrue(after.commitNanos() > before.commitNanos());
        assertTrue(after.prepareNanos() > before.prepareNanos());
        assertTrue(after.applyNanos() > before.applyNanos());
        assertTrue(after.journalDurabilityNanos() > before.journalDurabilityNanos());
        assertTrue(after.residualNanos() >= before.residualNanos());
        JsonObject journal = JsonParser.parseString(Files.readString(
            assets.resolve(".transactions").resolve(transactionId).resolve("journal.json"))).getAsJsonObject();
        assertEquals(3, journal.get("version").getAsInt());
        assertEquals(firstDescriptor.fingerprint(), journal.get("fingerprint").getAsString());

        AssetTransactionManager.TransactionVisibility visibility = manager.readTransaction(transactionId);
        assertEquals(transactionId, visibility.transactionId());
        assertEquals(mutationId, visibility.mutationId());
        assertEquals(firstDescriptor.fingerprint(), visibility.descriptor().fingerprint());
        Optional<AssetTransactionManager.TransactionVisibility> found = manager.findCommittedTransaction(mutationId);
        assertTrue(found.isPresent());
        assertEquals(transactionId, found.get().transactionId());
    }

    @Test
    void prepareFailuresRemainAttributedToThePrepareAttempt() throws Exception {
        Path assets = tempDir.resolve("prepare-failure-assets");
        AssetTransactionManager manager = new AssetTransactionManager(assets, GSON);
        AssetTransactionManager.PhaseTiming before = manager.phaseTiming();

        assertThrows(IOException.class, () -> manager.commit(
            Map.of(assets.resolve(".transactions").resolve("forbidden.json"), "forbidden"),
            "55555555-5555-4555-8555-555555555555"));

        AssetTransactionManager.PhaseTiming after = manager.phaseTiming();
        assertEquals(before.commitAttemptCount() + 1L, after.commitAttemptCount());
        assertEquals(before.prepareAttemptCount() + 1L, after.prepareAttemptCount());
        assertEquals(before.applyAttemptCount(), after.applyAttemptCount());
        assertEquals(before.journalDurabilityAttemptCount(), after.journalDurabilityAttemptCount());
        assertTrue(after.commitNanos() > before.commitNanos());
        assertTrue(after.prepareNanos() > before.prepareNanos());
        assertTrue(after.residualNanos() >= before.residualNanos());
    }

    @Test
    void fingerprintDamageIsRejectedAndLegacyPreparedJournalIsExplicitlyUpgraded() throws Exception {
        Path assets = tempDir.resolve("assets");
        AssetTransactionManager manager = new AssetTransactionManager(assets, GSON);
        Path target = assets.resolve("legacy.json");
        String mutationId = "33333333-3333-4333-8333-333333333333";
        String transactionId = manager.commit(Map.of(target, "stable"), mutationId);
        Path journalFile = assets.resolve(".transactions").resolve(transactionId).resolve("journal.json");
        JsonObject damaged = JsonParser.parseString(Files.readString(journalFile)).getAsJsonObject();
        damaged.addProperty("fingerprint", "damaged");
        Files.writeString(journalFile, damaged.toString());
        assertThrows(IOException.class, () -> manager.findCommittedTransaction(mutationId));

        Path legacyAssets = tempDir.resolve("legacy-assets");
        Path legacyTransaction = legacyAssets.resolve(".transactions").resolve("legacy");
        Path legacyTarget = legacyAssets.resolve("legacy.json");
        Files.createDirectories(legacyTransaction);
        byte[] legacyContent = "legacy".getBytes(StandardCharsets.UTF_8);
        StorageSafety.writeBytesAtomic(legacyTransaction.resolve("content-0.json"), legacyContent);
        String legacyJournal = "{\"version\":2,\"id\":\"legacy\",\"mutationId\":\"legacy-mutation\","
            + "\"state\":\"PREPARED\",\"entries\":[{\"target\":\"legacy.json\","
            + "\"staged\":\"content-0.json\",\"hash\":\"" + StorageSafety.sha256(legacyContent)
            + "\",\"delete\":false,\"existed\":false}]}";
        Files.writeString(legacyTransaction.resolve("journal.json"), legacyJournal);

        AssetTransactionManager recovered = new AssetTransactionManager(legacyAssets, GSON);
        JsonObject upgraded = JsonParser.parseString(Files.readString(
            legacyTransaction.resolve("journal.json"))).getAsJsonObject();
        assertEquals(3, upgraded.get("version").getAsInt());
        assertFalse(upgraded.get("fingerprint").getAsString().isBlank());
        assertEquals("legacy", recovered.readTransaction("legacy").transactionId());
        assertEquals("legacy", Files.readString(legacyTarget));
    }

    @Test
    void trustedMutationIndexAvoidsHistoricalJournalReadsOnCommit() throws Exception {
        Path root = tempDir.resolve("indexed-commits");
        AssetTransactionManager manager = new AssetTransactionManager(root, GSON,
            AssetTransactionManager.RecoveryMode.DEFERRED);
        manager.inspectTransactions();
        AssetTransactionManager.IoMetrics before = manager.ioMetrics();
        byte[] inspectedContent = "inspected".getBytes(StandardCharsets.UTF_8);
        manager.descriptorFromInspection(List.of(new AssetTransactionManager.TransactionOperation("inspected.json",
            "WRITE_BINARY", StorageSafety.sha256(inspectedContent), inspectedContent.length)));
        assertEquals(before.assetPathCanonicalizations(), manager.ioMetrics().assetPathCanonicalizations());

        Path first = root.resolve("first.json");
        manager.commit(Map.of(first, "first"), "55555555-5555-4555-8555-555555555555");
        AssetTransactionManager.IoMetrics committed = manager.ioMetrics();
        Path second = root.resolve("second.json");
        manager.commit(Map.of(second, "second"), "66666666-6666-4666-8666-666666666666");
        AssetTransactionManager.IoMetrics appended = manager.ioMetrics();

        assertEquals(before.fullJournalPasses(), appended.fullJournalPasses());
        assertEquals(before.journalReads(), appended.journalReads());
        assertEquals(before.stagedPayloadReads(), appended.stagedPayloadReads());
        assertEquals(committed.indexedMutationLookups() + 1L, appended.indexedMutationLookups());
        assertEquals(2L, committed.assetPathCanonicalizations() - before.assetPathCanonicalizations());
        assertEquals(2L, appended.assetPathCanonicalizations() - committed.assetPathCanonicalizations());
        assertEquals("first", Files.readString(first));
        assertEquals("second", Files.readString(second));
    }
}

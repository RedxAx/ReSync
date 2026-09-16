package restudio.resync.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoverableJsonStoreDurabilityTest {
    private static final Gson GSON = new Gson();
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    @TempDir
    Path temporaryDirectory;

    @Test
    void malformedRevisionFailsClosedAndPreservesTheOriginalDocument() throws Exception {
        Path file = temporaryDirectory.resolve("journal.json");
        JsonObject payload = new JsonObject();
        payload.addProperty("state", "before");
        JsonObject document = new JsonObject();
        document.addProperty("schemaVersion", 1);
        document.addProperty("revision", "not-a-revision");
        document.addProperty("hash", StorageSafety.sha256(GSON.toJson(payload)));
        document.add("payload", payload);
        Files.writeString(file, GSON.toJson(document));
        String original = Files.readString(file);

        RecoverableJsonStore store = new RecoverableJsonStore(file, GSON);

        assertThrows(IOException.class, () -> store.save(new JsonObject()));
        assertEquals(original, Files.readString(file));
        assertTrue(Files.exists(temporaryDirectory.resolve(".quarantine").resolve("journals").resolve("journal.json.corrupt")));
    }

    @Test
    void missingRevisionFailsClosedAndPreservesTheOriginalDocument() throws Exception {
        Path file = temporaryDirectory.resolve("journal.json");
        JsonObject payload = new JsonObject();
        payload.addProperty("state", "before");
        JsonObject document = new JsonObject();
        document.addProperty("schemaVersion", 1);
        document.addProperty("hash", StorageSafety.sha256(GSON.toJson(payload)));
        document.add("payload", payload);
        Files.writeString(file, GSON.toJson(document));
        String original = Files.readString(file);

        RecoverableJsonStore store = new RecoverableJsonStore(file, GSON);

        assertThrows(IOException.class, () -> store.save(new JsonObject()));
        assertEquals(original, Files.readString(file));
        assertTrue(Files.exists(temporaryDirectory.resolve(".quarantine").resolve("journals").resolve("journal.json.corrupt")));
    }

    @Test
    void numericStringRevisionIsRejected() throws Exception {
        Path file = temporaryDirectory.resolve("journal.json");
        JsonObject payload = new JsonObject();
        payload.addProperty("state", "before");
        JsonObject document = new JsonObject();
        document.addProperty("schemaVersion", 1);
        document.addProperty("revision", "1");
        document.addProperty("hash", StorageSafety.sha256(GSON.toJson(payload)));
        document.add("payload", payload);
        Files.writeString(file, GSON.toJson(document));

        assertThrows(IOException.class, () -> new RecoverableJsonStore(file, GSON).save(new JsonObject()));
    }

    @Test
    void unknownEnvelopeMetadataSurvivesRewrite() throws Exception {
        Path file = temporaryDirectory.resolve("journal.json");
        RecoverableJsonStore store = new RecoverableJsonStore(file, GSON);
        JsonObject first = new JsonObject();
        first.addProperty("state", "first");
        store.save(first);

        JsonObject document = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        document.addProperty("futureEnvelopeField", "preserve-me");
        Files.writeString(file, GSON.toJson(document));

        JsonObject second = new JsonObject();
        second.addProperty("state", "second");
        store.save(second);

        JsonObject rewritten = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals("preserve-me", rewritten.get("futureEnvelopeField").getAsString());
        assertEquals("second", rewritten.getAsJsonObject("payload").get("state").getAsString());
    }

    @Test
    void canonicalAtomicTemporaryFilesAreInventoriedAndPreserved() throws Exception {
        Path file = temporaryDirectory.resolve("journal.json");
        String temporaryName = ".resync-00000000-0000-0000-0000-000000000000.tmp";
        Path temporary = temporaryDirectory.resolve(temporaryName);
        Files.writeString(temporary, "partial");

        RecoverableJsonStore store = new RecoverableJsonStore(file, GSON);

        assertNull(store.load());
        assertFalse(Files.exists(temporary));
        assertEquals("partial", Files.readString(temporaryDirectory.resolve(".quarantine").resolve("journals").resolve(temporaryName)));
    }

    @Test
    void migrationFenceEpochsAreMonotonic() throws Exception {
        Path assets = temporaryDirectory.resolve("assets");

        try (MigrationLedger.Fence first = MigrationLedger.acquireFence(assets)) {
            assertEquals(1L, first.epoch());
        }
        try (MigrationLedger.Fence second = MigrationLedger.acquireFence(assets)) {
            assertEquals(2L, second.epoch());
        }
        assertEquals("2", Files.readString(assets.resolve(".durability").resolve("migration.epoch")));
    }

    @Test
    void malformedMigrationEpochCannotBeReused() throws Exception {
        Path assets = temporaryDirectory.resolve("assets");
        try (MigrationLedger.Fence ignored = MigrationLedger.acquireFence(assets)) {
        }
        Path epoch = assets.resolve(".durability").resolve("migration.epoch");
        Files.writeString(epoch, "not-an-epoch");

        assertThrows(IOException.class, () -> MigrationLedger.acquireFence(assets));
        assertEquals("not-an-epoch", Files.readString(epoch));
    }

    @Test
    void committedLedgerEntriesAreTerminalAndPrepareIsIdempotent() throws Exception {
        Path assets = temporaryDirectory.resolve("assets");
        MigrationLedger ledger = new MigrationLedger(assets);
        ledger.prepare("fixture", "graph", "flow", 1L, "source-a", 2, 1L);
        ledger.commit("fixture", "graph", "source-a");

        ledger.prepare("fixture", "graph", "flow", 1L, "source-a", 2, 2L);

        assertTrue(ledger.isCommitted("fixture", "graph", "source-a"));
        assertThrows(IOException.class, () -> ledger.fail("fixture", "graph", "source-a", "late failure"));
    }

    @Test
    void ledgerMutationsReloadUnderDurableLockAndPreserveUnknownFields() throws Exception {
        Path assets = temporaryDirectory.resolve("assets");
        MigrationLedger first = new MigrationLedger(assets);
        MigrationLedger second = new MigrationLedger(assets);
        first.prepare("fixture", "first", "flow", 1L, "source-a", 2, 1L);
        second.prepare("fixture", "second", "flow", 1L, "source-b", 2, 1L);

        Path file = assets.resolve(".durability").resolve("migrations.json");
        JsonObject document = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        JsonObject payload = document.getAsJsonObject("payload");
        payload.addProperty("futureTopLevel", "preserve-top-level");
        String entryKey = "fixture\u0000second\u0000source-b";
        payload.getAsJsonObject(entryKey).addProperty("futureEntry", "preserve-entry");
        document.addProperty("hash", StorageSafety.sha256(PRETTY_GSON.toJson(payload)));
        Files.writeString(file, GSON.toJson(document));

        MigrationLedger reloaded = new MigrationLedger(assets);
        reloaded.commit("fixture", "second", "source-b");

        JsonObject rewritten = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        JsonObject rewrittenPayload = rewritten.getAsJsonObject("payload");
        assertEquals("preserve-top-level", rewrittenPayload.get("futureTopLevel").getAsString());
        assertEquals("preserve-entry", rewrittenPayload.getAsJsonObject(entryKey).get("futureEntry").getAsString());
        MigrationLedger finalLedger = new MigrationLedger(assets);
        finalLedger.commit("fixture", "first", "source-a");
        assertTrue(finalLedger.isCommitted("fixture", "first", "source-a"));
    }

    @Test
    void migrationKeyRejectsControlDelimiters() throws Exception {
        MigrationLedger ledger = new MigrationLedger(temporaryDirectory.resolve("assets"));

        assertThrows(IllegalArgumentException.class,
            () -> ledger.prepare("fixture\u0000broken", "graph", "flow", 1L, "source-a", 2, 1L));
    }
}

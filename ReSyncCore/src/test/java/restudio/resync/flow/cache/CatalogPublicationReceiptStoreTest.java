package restudio.resync.flow.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.contract.canonical.CanonicalHash;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerId;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogPublicationReceiptStoreTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final CatalogCacheKey KEY = new CatalogCacheKey(SERVER, 7, ContentHash.of("a".repeat(64)));
    private static final CatalogCachePublication PUBLICATION = CatalogCachePublication.full(CatalogCacheSnapshot.empty(KEY));
    private static final CatalogCacheKey NEXT_KEY = new CatalogCacheKey(SERVER, 8, ContentHash.of("b".repeat(64)));
    private static final CatalogCachePublication NEXT_PUBLICATION = CatalogCachePublication.full(CatalogCacheSnapshot.empty(NEXT_KEY));
    private static final String OWNER = "owner-1";

    @Test
    void restartRetainsIndependentReceiptStagesAndIdentity() throws Exception {
        Path path = temporary.resolve("receipts.json");
        CatalogPublicationReceiptStore first = new CatalogPublicationReceiptStore(path);

        first.recordDispatch("applied", OWNER, PUBLICATION);
        first.acknowledgeClientReceipt("applied", OWNER, KEY, PUBLICATION.revision());
        first.acknowledgeCacheApplication("applied", OWNER, KEY, PUBLICATION.revision());
        first.recordDispatchFailure("failed", OWNER, PUBLICATION, "CATALOG_PUBLICATION.SEND_FAILED");

        CatalogPublicationReceiptStore restarted = new CatalogPublicationReceiptStore(path);

        CatalogPublicationReceipt applied = restarted.baseline("applied").orElseThrow();
        assertEquals(CatalogPublicationReceipt.DispatchState.DISPATCHED, applied.dispatch());
        assertEquals(CatalogPublicationReceipt.ClientReceiptState.RECEIVED, applied.clientReceipt());
        assertEquals(CatalogPublicationReceipt.CacheApplicationState.APPLIED, applied.cacheApplication());
        assertTrue(applied.converged());
        CatalogPublicationReceipt failed = restarted.baseline("failed").orElseThrow();
        assertEquals(CatalogPublicationReceipt.DispatchState.FAILED, failed.dispatch());
        assertEquals(CatalogPublicationReceipt.ClientReceiptState.REJECTED, failed.clientReceipt());
        assertEquals(CatalogPublicationReceipt.CacheApplicationState.REJECTED, failed.cacheApplication());
        assertEquals("CATALOG_PUBLICATION.SEND_FAILED", failed.diagnosticCode());
        assertEquals(KEY, failed.publicationKey());
    }

    @Test
    void retriesAreIdempotentAndDoNotRewriteTheCanonicalDocument() throws Exception {
        Path path = temporary.resolve("receipts.json");
        CatalogPublicationReceiptStore store = new CatalogPublicationReceiptStore(path);
        store.recordDispatch("session", OWNER, PUBLICATION);
        byte[] dispatched = Files.readAllBytes(path);

        assertTrue(store.recordDispatch("session", OWNER, PUBLICATION).serverDispatched());
        assertArrayEquals(dispatched, Files.readAllBytes(path));

        store.acknowledgeClientReceipt("session", OWNER, KEY, PUBLICATION.revision());
        byte[] received = Files.readAllBytes(path);
        assertTrue(store.acknowledgeClientReceipt("session", OWNER, KEY, PUBLICATION.revision()).accepted());
        assertArrayEquals(received, Files.readAllBytes(path));
    }

    @Test
    void broadcastDispatchPersistsAllReceiptRowsAsOneAtomicTransition() throws Exception {
        Path path = temporary.resolve("receipts.json");
        CatalogPublicationReceiptStore store = new CatalogPublicationReceiptStore(path);

        store.recordDispatchBatch(Map.of("second", "owner-second", "first", "owner-first"), PUBLICATION);

        CatalogPublicationReceiptStore restarted = new CatalogPublicationReceiptStore(path);
        assertTrue(restarted.baseline("first").orElseThrow().serverDispatched());
        assertTrue(restarted.baseline("second").orElseThrow().serverDispatched());
    }

    @Test
    void failedBroadcastDispatchPersistenceRestoresEveryPriorReceiptRow() throws Exception {
        Path path = temporary.resolve("receipts.json");
        CatalogPublicationReceiptStore store = new CatalogPublicationReceiptStore(path);
        store.recordDispatch("existing", OWNER, PUBLICATION);
        CatalogPublicationReceipt before = store.baseline("existing").orElseThrow();

        Files.delete(path);
        Files.createDirectory(path);

        assertThrows(IllegalStateException.class,
            () -> store.recordDispatchBatch(Map.of("existing", OWNER, "new", "owner-new"), NEXT_PUBLICATION));

        assertEquals(before, store.baseline("existing").orElseThrow());
        assertTrue(store.baseline("new").isEmpty());
    }

    @Test
    void restartProvidesOnlyPendingTypedReplayRows() throws Exception {
        Path path = temporary.resolve("receipts.json");
        CatalogPublicationReceiptStore store = new CatalogPublicationReceiptStore(path);
        store.recordDispatch("application-pending", OWNER, PUBLICATION);
        store.acknowledgeClientReceipt("application-pending", OWNER, KEY, PUBLICATION.revision());
        store.recordDispatch("complete", OWNER, PUBLICATION);
        store.acknowledgeClientReceipt("complete", OWNER, KEY, PUBLICATION.revision());
        store.acknowledgeCacheApplication("complete", OWNER, KEY, PUBLICATION.revision());

        CatalogPublicationReceiptStore restarted = new CatalogPublicationReceiptStore(path);
        assertEquals(1, restarted.pendingReplays().size());
        CatalogPublicationReceiptReplay replay = restarted.replay("application-pending").orElseThrow();
        assertEquals(CatalogPublicationReceiptReplay.Stage.CLIENT_APPLICATION_REQUIRED, replay.stage());
        assertTrue(replay.clientApplicationRequired());
        assertTrue(restarted.replay("complete").orElseThrow().converged());
    }

    @Test
    void mismatchedGenerationChecksumAndRevisionRemainFailClosedAfterRestart() throws Exception {
        Path path = temporary.resolve("receipts.json");
        CatalogPublicationReceiptStore store = new CatalogPublicationReceiptStore(path);
        store.recordDispatch("session", OWNER, PUBLICATION);
        CatalogPublicationReceipt before = store.baseline("session").orElseThrow();
        CatalogCacheKey mismatched = new CatalogCacheKey(SERVER, KEY.catalogGeneration() + 1, ContentHash.of("b".repeat(64)));

        CatalogPublicationReceipt.Transition transition = store.acknowledgeClientReceipt("session", OWNER, mismatched,
            PUBLICATION.revision() + 1);

        assertFalse(transition.accepted());
        assertEquals("CATALOG_PUBLICATION.RECEIPT_KEY_MISMATCH", transition.code());
        assertEquals(before, store.baseline("session").orElseThrow());
        CatalogPublicationReceiptStore restarted = new CatalogPublicationReceiptStore(path);
        assertEquals(before, restarted.baseline("session").orElseThrow());
    }

    @Test
    void staleOwnerMutationsReturnTypedRejectionsAndLeaveThePersistedReceiptUntouched() throws Exception {
        Path path = temporary.resolve("receipts.json");
        CatalogPublicationReceiptStore store = new CatalogPublicationReceiptStore(path);
        store.recordDispatch("session", OWNER, PUBLICATION);
        CatalogPublicationReceipt before = store.baseline("session").orElseThrow();

        CatalogPublicationReceipt.Transition dispatch = store.tryRecordDispatch("session", "stale", NEXT_PUBLICATION);
        CatalogPublicationReceipt.Transition failure = store.tryRecordDispatchFailure("session", "stale",
            NEXT_PUBLICATION, "CATALOG_PUBLICATION.SEND_FAILED");
        CatalogPublicationReceipt.Transition receipt = store.acknowledgeClientReceipt("session", "stale", KEY,
            PUBLICATION.revision());

        assertFalse(dispatch.accepted());
        assertEquals(CatalogPublicationReceiptTracker.OWNER_STALE, dispatch.code());
        assertFalse(failure.accepted());
        assertEquals(CatalogPublicationReceiptTracker.OWNER_STALE, failure.code());
        assertFalse(receipt.accepted());
        assertEquals(CatalogPublicationReceiptTracker.OWNER_STALE, receipt.code());
        assertFalse(store.remove("session", "stale"));
        assertEquals(before, store.baseline("session").orElseThrow());
        assertEquals(before, new CatalogPublicationReceiptStore(path).baseline("session").orElseThrow());
    }

    @Test
    void rejectedReceiptResetPersistsOnlyForTheAdoptedOwner() throws Exception {
        Path path = temporary.resolve("receipts.json");
        CatalogPublicationReceiptStore store = new CatalogPublicationReceiptStore(path);
        store.recordDispatch("session", OWNER, PUBLICATION);
        store.acknowledgeClientReceipt("session", OWNER, KEY, PUBLICATION.revision());
        store.rejectCacheApplication("session", OWNER, KEY, PUBLICATION.revision(),
            "CATALOG_PUBLICATION.RECEIPT_APPLICATION_REJECTED");
        assertTrue(store.adoptForRedispatch("session", "owner-2", PUBLICATION).accepted());
        CatalogPublicationReceipt restarted = new CatalogPublicationReceiptStore(path)
            .baseline("session").orElseThrow();

        assertEquals("owner-2", restarted.ownerToken());
        assertTrue(restarted.serverDispatched());
        assertFalse(restarted.clientReceived());
        assertFalse(restarted.cacheApplied());
        assertEquals("", restarted.diagnosticCode());
        assertEquals("owner-2", store.adoptForRedispatch("session", "owner-2", PUBLICATION)
            .receipt().orElseThrow().ownerToken());
    }

    @Test
    void legacyV1OwnerlessRowsMigrateToV2AndRequireClaimBeforeMutation() throws Exception {
        Path path = temporary.resolve("receipts.json");
        Files.createDirectories(path.getParent());
        Files.write(path, legacyDocument("session"));

        CatalogPublicationReceiptStore store = new CatalogPublicationReceiptStore(path);

        assertNull(store.baseline("session").orElseThrow().ownerToken());
        store.flush();
        assertTrue(Files.readString(path, StandardCharsets.UTF_8).contains("\"version\":2"));
        CatalogPublicationReceipt.Transition dispatch = store.tryRecordDispatch("session", OWNER, PUBLICATION);
        assertFalse(dispatch.accepted());
        assertEquals(CatalogPublicationReceiptTracker.OWNER_TOKEN_REQUIRED, dispatch.code());
        CatalogPublicationReceipt.Claim claim = store.claim("session", OWNER);

        assertTrue(claim.accepted());
        assertEquals(OWNER, store.baseline("session").orElseThrow().ownerToken());
        String encoded = Files.readString(path, StandardCharsets.UTF_8);
        assertTrue(encoded.contains("\"version\":2"));
        assertTrue(encoded.contains("\"ownerToken\":\"owner-1\""));
        assertEquals(store.baseline("session").orElseThrow(),
            new CatalogPublicationReceiptStore(path).baseline("session").orElseThrow());
    }

    @Test
    void claimingOneLegacyRowPreservesOtherRowsAsExplicitlyUnclaimed() throws Exception {
        Path path = temporary.resolve("receipts.json");
        Files.createDirectories(path.getParent());
        Files.write(path, legacyDocument(List.of("first", "second")));

        CatalogPublicationReceiptStore store = new CatalogPublicationReceiptStore(path);
        assertTrue(store.claim("first", OWNER).accepted());

        CatalogPublicationReceiptStore restarted = new CatalogPublicationReceiptStore(path);
        CatalogPublicationReceipt first = restarted.baseline("first").orElseThrow();
        CatalogPublicationReceipt second = restarted.baseline("second").orElseThrow();
        assertEquals(CatalogPublicationReceipt.OwnerState.CLAIMED, first.ownerState());
        assertEquals(OWNER, first.ownerToken());
        assertEquals(CatalogPublicationReceipt.OwnerState.LEGACY_UNCLAIMED, second.ownerState());
        assertNull(second.ownerToken());
        assertTrue(restarted.claim("second", "owner-2").accepted());
    }

    @Test
    void v2OwnerStateAndTokenPairingsAreStrictlyValidated() throws Exception {
        Path claimedPath = temporary.resolve("claimed.json");
        Map<String, Object> missingToken = legacyReceipt("claimed");
        missingToken.put("ownerState", "claimed");
        Files.write(claimedPath, receiptDocument(2, List.of(missingToken)));
        assertThrows(IllegalArgumentException.class, () -> new CatalogPublicationReceiptStore(claimedPath));

        Path legacyPath = temporary.resolve("legacy.json");
        Map<String, Object> unexpectedToken = legacyReceipt("legacy");
        unexpectedToken.put("ownerState", "legacy-unclaimed");
        unexpectedToken.put("ownerToken", OWNER);
        Files.write(legacyPath, receiptDocument(2, List.of(unexpectedToken)));
        assertThrows(IllegalArgumentException.class, () -> new CatalogPublicationReceiptStore(legacyPath));
    }

    @Test
    void contentHashCorruptionIsRejectedBeforeAnyBaselineIsLoaded() throws Exception {
        Path path = temporary.resolve("receipts.json");
        CatalogPublicationReceiptStore store = new CatalogPublicationReceiptStore(path);
        store.recordDispatch("session", OWNER, PUBLICATION);
        String original = Files.readString(path, StandardCharsets.UTF_8);
        String corrupted = original.replaceFirst("[0-9a-f]{64}(?=})$", "0".repeat(64));
        if (corrupted.equals(original)) {
            corrupted = original.replace("\"contentHash\":\"", "\"contentHash\":\"0");
        }
        Files.writeString(path, corrupted, StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> new CatalogPublicationReceiptStore(path));
    }

    @Test
    void malformedCanonicalDocumentIsRejected() throws Exception {
        Path path = temporary.resolve("receipts.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{\"kind\":\"catalog-publication-receipts\",\"version\":1}", StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> new CatalogPublicationReceiptStore(path));
    }

    private static byte[] legacyDocument(String sessionKey) {
        return legacyDocument(List.of(sessionKey));
    }

    private static byte[] legacyDocument(List<String> sessionKeys) {
        List<Map<String, Object>> rows = sessionKeys.stream()
            .sorted()
            .map(CatalogPublicationReceiptStoreTest::legacyReceipt)
            .toList();
        return receiptDocument(1, rows);
    }

    private static byte[] receiptDocument(int version, List<Map<String, Object>> rows) {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("kind", "catalog-publication-receipts");
        base.put("version", version);
        base.put("receipts", rows);
        base.put("contentHash", CanonicalHash.sha256(JsonValue.fromJava(base)));
        return JsonValue.fromJava(base).canonicalBytes();
    }

    private static Map<String, Object> legacyReceipt(String sessionKey) {
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("sessionKey", sessionKey);
        receipt.put("publicationKey", KEY.canonicalText());
        receipt.put("revision", PUBLICATION.revision());
        receipt.put("dispatch", "dispatched");
        receipt.put("clientReceipt", "not-received");
        receipt.put("cacheApplication", "not-applied");
        receipt.put("diagnosticCode", "");
        return receipt;
    }

    @TempDir
    Path temporary;
}

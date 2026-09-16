package restudio.resync.flow.cache;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerId;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogPublicationReceiptTrackerTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final CatalogCacheKey KEY = new CatalogCacheKey(SERVER, 1, ContentHash.of("a".repeat(64)));
    private static final CatalogCachePublication PUBLICATION = CatalogCachePublication.full(CatalogCacheSnapshot.empty(KEY));
    private static final String OWNER = "owner-1";
    private static final String NEXT_OWNER = "owner-2";

    @Test
    void dispatchDoesNotImplyClientReceiptOrCacheApplication() {
        CatalogPublicationReceiptTracker tracker = new CatalogPublicationReceiptTracker();

        CatalogPublicationReceipt receipt = tracker.recordDispatch("session", OWNER, PUBLICATION);

        assertTrue(receipt.serverDispatched());
        assertFalse(receipt.clientReceived());
        assertFalse(receipt.cacheApplied());
        assertFalse(receipt.converged());
        assertEquals(List.of("session"), tracker.pendingSessionKeys());
    }

    @Test
    void receiptAndApplicationAdvanceOnlyTheirOwnTypedStages() {
        CatalogPublicationReceiptTracker tracker = new CatalogPublicationReceiptTracker();
        tracker.recordDispatch("session", OWNER, PUBLICATION);

        CatalogPublicationReceipt.Transition received = tracker.acknowledgeClientReceipt("session", OWNER, KEY,
            PUBLICATION.revision());
        assertTrue(received.accepted());
        assertTrue(received.receipt().orElseThrow().clientReceived());
        assertFalse(received.receipt().orElseThrow().cacheApplied());

        CatalogPublicationReceipt.Transition applied = tracker.acknowledgeCacheApplication("session", OWNER, KEY,
            PUBLICATION.revision());
        assertTrue(applied.accepted());
        assertTrue(applied.receipt().orElseThrow().converged());
        assertTrue(tracker.pendingSessionKeys().isEmpty());
    }

    @Test
    void mismatchedReceiptFailsClosedWithoutChangingTheBaseline() {
        CatalogPublicationReceiptTracker tracker = new CatalogPublicationReceiptTracker();
        tracker.recordDispatch("session", OWNER, PUBLICATION);
        CatalogCacheKey otherKey = new CatalogCacheKey(SERVER, 2, ContentHash.of("b".repeat(64)));

        CatalogPublicationReceipt.Transition transition = tracker.acknowledgeClientReceipt("session", OWNER, otherKey, 2);

        assertFalse(transition.accepted());
        assertEquals("CATALOG_PUBLICATION.RECEIPT_KEY_MISMATCH", transition.code());
        assertEquals(CatalogPublicationReceipt.ClientReceiptState.NOT_RECEIVED,
            tracker.baseline("session").orElseThrow().clientReceipt());
    }

    @Test
    void cacheApplicationBeforeReceiptFailsClosed() {
        CatalogPublicationReceiptTracker tracker = new CatalogPublicationReceiptTracker();
        tracker.recordDispatch("session", OWNER, PUBLICATION);

        CatalogPublicationReceipt.Transition transition = tracker.acknowledgeCacheApplication("session", OWNER, KEY,
            PUBLICATION.revision());

        assertFalse(transition.accepted());
        assertEquals("CATALOG_PUBLICATION.APPLICATION_BEFORE_RECEIPT", transition.code());
        assertEquals(CatalogPublicationReceipt.CacheApplicationState.NOT_APPLIED,
            tracker.baseline("session").orElseThrow().cacheApplication());
    }

    @Test
    void cacheApplicationRejectionPersistsCanonicalDiagnosticAndIsIdempotent() {
        CatalogPublicationReceiptTracker tracker = new CatalogPublicationReceiptTracker();
        tracker.recordDispatch("session", OWNER, PUBLICATION);
        tracker.acknowledgeClientReceipt("session", OWNER, KEY, PUBLICATION.revision());

        CatalogPublicationReceipt.Transition rejected = tracker.rejectCacheApplication("session", OWNER, KEY,
            PUBLICATION.revision(), "CATALOG_PUBLICATION.CACHE_PROJECTION_FAILED");

        assertTrue(rejected.accepted());
        assertEquals(CatalogPublicationReceipt.CacheApplicationState.REJECTED,
            rejected.receipt().orElseThrow().cacheApplication());
        assertEquals("CATALOG_PUBLICATION.CACHE_PROJECTION_FAILED",
            rejected.receipt().orElseThrow().diagnosticCode());
        assertTrue(tracker.rejectCacheApplication("session", OWNER, KEY, PUBLICATION.revision(),
            "CATALOG_PUBLICATION.CACHE_PROJECTION_FAILED").accepted());
        assertFalse(tracker.rejectCacheApplication("session", OWNER, KEY, PUBLICATION.revision(),
            "CATALOG_PUBLICATION.OTHER_FAILURE").accepted());
    }

    @Test
    void dispatchFailureDoesNotRegressAReceiptThatArrivedDuringTransmission() {
        CatalogPublicationReceiptTracker tracker = new CatalogPublicationReceiptTracker();
        tracker.recordDispatch("session", OWNER, PUBLICATION);
        tracker.acknowledgeClientReceipt("session", OWNER, KEY, PUBLICATION.revision());

        CatalogPublicationReceipt result = tracker.recordDispatchFailure("session", OWNER, PUBLICATION,
            "CATALOG_PUBLICATION.SEND_FAILED");

        assertEquals(CatalogPublicationReceipt.DispatchState.DISPATCHED, result.dispatch());
        assertEquals(CatalogPublicationReceipt.ClientReceiptState.RECEIVED, result.clientReceipt());
        assertEquals(CatalogPublicationReceipt.CacheApplicationState.NOT_APPLIED, result.cacheApplication());
    }

    @Test
    void retryingTheSamePublicationIsIdempotentAndAChangedPublicationResetsTheBaseline() {
        CatalogPublicationReceiptTracker tracker = new CatalogPublicationReceiptTracker();
        tracker.recordDispatch("session", OWNER, PUBLICATION);
        tracker.acknowledgeClientReceipt("session", OWNER, KEY, PUBLICATION.revision());
        tracker.acknowledgeCacheApplication("session", OWNER, KEY, PUBLICATION.revision());

        assertTrue(tracker.recordDispatch("session", OWNER, PUBLICATION).converged());

        CatalogCacheKey nextKey = new CatalogCacheKey(SERVER, 2, ContentHash.of("b".repeat(64)));
        CatalogCachePublication next = CatalogCachePublication.full(CatalogCacheSnapshot.empty(nextKey));
        CatalogPublicationReceipt nextReceipt = tracker.recordDispatch("session", OWNER, next);

        assertTrue(nextReceipt.serverDispatched());
        assertFalse(nextReceipt.clientReceived());
        assertFalse(nextReceipt.cacheApplied());
    }

    @Test
    void legacyOwnerlessReceiptCanBeClaimedAndAdoptedWithoutLosingPendingState() {
        CatalogPublicationReceiptTracker tracker = new CatalogPublicationReceiptTracker();
        CatalogPublicationReceipt legacy = new CatalogPublicationReceipt("legacy", null, KEY,
            PUBLICATION.revision(), CatalogPublicationReceipt.DispatchState.DISPATCHED,
            CatalogPublicationReceipt.ClientReceiptState.NOT_RECEIVED,
            CatalogPublicationReceipt.CacheApplicationState.NOT_APPLIED, "");
        tracker.restore(List.of(legacy));

        CatalogPublicationReceipt.Claim claimed = tracker.claim("legacy", OWNER);

        assertTrue(claimed.accepted());
        assertNull(claimed.previousOwnerToken());
        assertEquals(OWNER, claimed.receipt().orElseThrow().ownerToken());
        assertEquals(legacy.dispatch(), claimed.receipt().orElseThrow().dispatch());
        assertEquals(legacy.clientReceipt(), claimed.receipt().orElseThrow().clientReceipt());
        assertEquals(legacy.cacheApplication(), claimed.receipt().orElseThrow().cacheApplication());

        CatalogPublicationReceipt.Claim adopted = tracker.adopt("legacy", NEXT_OWNER);
        assertTrue(adopted.accepted());
        assertEquals(OWNER, adopted.previousOwnerToken());
        assertEquals(NEXT_OWNER, adopted.receipt().orElseThrow().ownerToken());
        assertEquals(legacy.dispatch(), adopted.receipt().orElseThrow().dispatch());
    }

    @Test
    void replacementOwnerCanResetAnExactRejectedPublicationForRedispatch() {
        CatalogPublicationReceiptTracker tracker = new CatalogPublicationReceiptTracker();
        tracker.recordDispatch("session", OWNER, PUBLICATION);
        tracker.acknowledgeClientReceipt("session", OWNER, KEY, PUBLICATION.revision());
        tracker.rejectCacheApplication("session", OWNER, KEY, PUBLICATION.revision(),
            "CATALOG_PUBLICATION.RECEIPT_APPLICATION_REJECTED");

        CatalogPublicationReceipt.Claim adopted = tracker.adoptForRedispatch("session", NEXT_OWNER, PUBLICATION);

        assertTrue(adopted.accepted());
        assertEquals(OWNER, adopted.previousOwnerToken());
        CatalogPublicationReceipt receipt = adopted.receipt().orElseThrow();
        assertTrue(receipt.serverDispatched());
        assertFalse(receipt.clientReceived());
        assertFalse(receipt.cacheApplied());
        assertEquals("", receipt.diagnosticCode());
        assertEquals(NEXT_OWNER, tracker.adoptForRedispatch("session", NEXT_OWNER, PUBLICATION)
            .receipt().orElseThrow().ownerToken());
    }

    @Test
    void staleOwnerCannotMutateOrAcknowledgeAReceipt() {
        CatalogPublicationReceiptTracker tracker = new CatalogPublicationReceiptTracker();
        tracker.recordDispatch("session", OWNER, PUBLICATION);
        CatalogPublicationReceipt before = tracker.baseline("session").orElseThrow();

        CatalogPublicationReceipt.Transition dispatch = tracker.tryRecordDispatch("session", NEXT_OWNER, PUBLICATION);
        CatalogPublicationReceipt.Transition receipt = tracker.acknowledgeClientReceipt("session", NEXT_OWNER, KEY,
            PUBLICATION.revision());

        assertFalse(dispatch.accepted());
        assertEquals(CatalogPublicationReceiptTracker.OWNER_STALE, dispatch.code());
        assertFalse(receipt.accepted());
        assertEquals(CatalogPublicationReceiptTracker.OWNER_STALE, receipt.code());
        assertEquals(before, tracker.baseline("session").orElseThrow());
        assertFalse(tracker.remove("session", NEXT_OWNER));
        assertTrue(tracker.baseline("session").isPresent());
    }

    @Test
    void batchDispatchUsesEachSessionOwnerAndRejectsStaleRowsAtomically() {
        CatalogPublicationReceiptTracker tracker = new CatalogPublicationReceiptTracker();

        CatalogPublicationReceipt.BatchTransition created = tracker.tryRecordDispatchBatch(
            Map.of("first", "owner-first", "second", "owner-second"), PUBLICATION);

        assertTrue(created.accepted());
        assertEquals("owner-first", tracker.baseline("first").orElseThrow().ownerToken());
        assertEquals("owner-second", tracker.baseline("second").orElseThrow().ownerToken());
        CatalogPublicationReceipt before = tracker.baseline("first").orElseThrow();

        CatalogPublicationReceipt.BatchTransition stale = tracker.tryRecordDispatchBatch(
            Map.of("first", NEXT_OWNER, "third", "owner-third"), PUBLICATION);

        assertFalse(stale.accepted());
        assertEquals(CatalogPublicationReceiptTracker.OWNER_STALE, stale.code());
        assertEquals(CatalogPublicationReceiptTracker.OWNER_STALE, stale.outcomes().get("first").code());
        assertEquals(before, tracker.baseline("first").orElseThrow());
        assertTrue(tracker.baseline("third").isEmpty());
    }

    @Test
    void invalidSessionAndDiagnosticTextAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> CatalogPublicationReceipt.pending(" session", OWNER, PUBLICATION));
        CatalogPublicationReceipt receipt = CatalogPublicationReceipt.pending("session", OWNER, PUBLICATION);
        assertThrows(IllegalArgumentException.class, () -> receipt.dispatchFailed(" failure "));
    }

    @Test
    void impossibleReceiptStateTuplesAreRejectedByTheCoreContract() {
        assertInvalid(CatalogPublicationReceipt.DispatchState.FAILED,
            CatalogPublicationReceipt.ClientReceiptState.NOT_RECEIVED,
            CatalogPublicationReceipt.CacheApplicationState.NOT_APPLIED, "");
        assertInvalid(CatalogPublicationReceipt.DispatchState.NOT_ATTEMPTED,
            CatalogPublicationReceipt.ClientReceiptState.REJECTED,
            CatalogPublicationReceipt.CacheApplicationState.NOT_APPLIED, "");
        assertInvalid(CatalogPublicationReceipt.DispatchState.DISPATCHED,
            CatalogPublicationReceipt.ClientReceiptState.NOT_RECEIVED,
            CatalogPublicationReceipt.CacheApplicationState.REJECTED, "");
        assertInvalid(CatalogPublicationReceipt.DispatchState.DISPATCHED,
            CatalogPublicationReceipt.ClientReceiptState.NOT_RECEIVED,
            CatalogPublicationReceipt.CacheApplicationState.APPLIED, "");
        assertInvalid(CatalogPublicationReceipt.DispatchState.DISPATCHED,
            CatalogPublicationReceipt.ClientReceiptState.REJECTED,
            CatalogPublicationReceipt.CacheApplicationState.REJECTED, "");
        assertInvalid(CatalogPublicationReceipt.DispatchState.FAILED,
            CatalogPublicationReceipt.ClientReceiptState.REJECTED,
            CatalogPublicationReceipt.CacheApplicationState.REJECTED, "");
        assertInvalid(CatalogPublicationReceipt.DispatchState.NOT_ATTEMPTED,
            CatalogPublicationReceipt.ClientReceiptState.NOT_RECEIVED,
            CatalogPublicationReceipt.CacheApplicationState.NOT_APPLIED, "unexpected");
    }

    @Test
    void orderedClientAndApplicationRejectionsRemainRepresentable() {
        CatalogPublicationReceipt clientRejected = new CatalogPublicationReceipt("client-rejected", OWNER, KEY,
            PUBLICATION.revision(), CatalogPublicationReceipt.DispatchState.DISPATCHED,
            CatalogPublicationReceipt.ClientReceiptState.REJECTED,
            CatalogPublicationReceipt.CacheApplicationState.NOT_APPLIED, "");
        CatalogPublicationReceipt applicationRejected = new CatalogPublicationReceipt("application-rejected", OWNER, KEY,
            PUBLICATION.revision(), CatalogPublicationReceipt.DispatchState.DISPATCHED,
            CatalogPublicationReceipt.ClientReceiptState.RECEIVED,
            CatalogPublicationReceipt.CacheApplicationState.REJECTED, "");

        assertEquals(CatalogPublicationReceipt.ClientReceiptState.REJECTED, clientRejected.clientReceipt());
        assertEquals(CatalogPublicationReceipt.CacheApplicationState.NOT_APPLIED, clientRejected.cacheApplication());
        assertEquals(CatalogPublicationReceipt.ClientReceiptState.RECEIVED, applicationRejected.clientReceipt());
        assertEquals(CatalogPublicationReceipt.CacheApplicationState.REJECTED, applicationRejected.cacheApplication());
    }

    private static void assertInvalid(CatalogPublicationReceipt.DispatchState dispatch,
                                      CatalogPublicationReceipt.ClientReceiptState clientReceipt,
                                      CatalogPublicationReceipt.CacheApplicationState cacheApplication,
                                      String diagnosticCode) {
        assertThrows(IllegalArgumentException.class,
            () -> new CatalogPublicationReceipt("session", OWNER, KEY, PUBLICATION.revision(), dispatch,
                clientReceipt, cacheApplication, diagnosticCode));
    }
}

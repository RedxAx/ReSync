package restudio.resync.flow.cache;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerId;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogPublicationReceiptReplayTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final CatalogCacheKey KEY = new CatalogCacheKey(SERVER, 7, ContentHash.of("a".repeat(64)));
    private static final CatalogCachePublication PUBLICATION = CatalogCachePublication.full(CatalogCacheSnapshot.empty(KEY));

    @Test
    void replayExposesEachDurableStageWithoutClaimingApplication() {
        CatalogPublicationReceipt pending = CatalogPublicationReceipt.pending("session", "owner", PUBLICATION);
        CatalogPublicationReceiptReplay dispatchRequired = CatalogPublicationReceiptReplay.from(pending);
        assertEquals(CatalogPublicationReceiptReplay.Stage.DISPATCH_REQUIRED, dispatchRequired.stage());
        assertFalse(dispatchRequired.clientApplicationRequired());
        assertFalse(dispatchRequired.converged());

        CatalogPublicationReceiptReplay receiptRequired = CatalogPublicationReceiptReplay.from(pending.dispatched());
        assertEquals(CatalogPublicationReceiptReplay.Stage.CLIENT_RECEIPT_REQUIRED, receiptRequired.stage());
        assertTrue(receiptRequired.serverDispatchConfirmed());
        assertFalse(receiptRequired.clientReceiptConfirmed());
        assertFalse(receiptRequired.clientApplicationConfirmed());

        CatalogPublicationReceipt received = pending.dispatched();
        received = received.clientReceived(KEY, PUBLICATION.revision()).receipt().orElseThrow();
        CatalogPublicationReceiptReplay applicationRequired = CatalogPublicationReceiptReplay.from(received);
        assertEquals(CatalogPublicationReceiptReplay.Stage.CLIENT_APPLICATION_REQUIRED, applicationRequired.stage());
        assertTrue(applicationRequired.clientApplicationRequired());
        assertFalse(applicationRequired.converged());

        CatalogPublicationReceipt applied = received.cacheApplied(KEY, PUBLICATION.revision()).receipt().orElseThrow();
        CatalogPublicationReceiptReplay complete = CatalogPublicationReceiptReplay.from(applied);
        assertEquals(CatalogPublicationReceiptReplay.Stage.COMPLETE, complete.stage());
        assertTrue(complete.clientApplicationConfirmed());
        assertTrue(complete.converged());
        assertFalse(complete.replayRequired());
    }

    @Test
    void failedDispatchRemainsAnExplicitNonApplicationOutcome() {
        CatalogPublicationReceipt failed = CatalogPublicationReceipt.pending("session", "owner", PUBLICATION)
            .dispatchFailed("CATALOG_PUBLICATION.SEND_FAILED");
        CatalogPublicationReceiptReplay replay = CatalogPublicationReceiptReplay.from(failed);
        assertEquals(CatalogPublicationReceiptReplay.Stage.DISPATCH_FAILED, replay.stage());
        assertEquals(CatalogPublicationReceipt.CacheApplicationState.REJECTED, replay.cacheApplication());
        assertFalse(replay.clientApplicationRequired());
        assertFalse(replay.clientApplicationConfirmed());
        assertTrue(replay.replayRequired());
    }
}

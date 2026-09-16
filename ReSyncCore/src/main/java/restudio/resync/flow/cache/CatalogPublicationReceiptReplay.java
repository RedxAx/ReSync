package restudio.resync.flow.cache;

import java.util.Objects;

public record CatalogPublicationReceiptReplay(CatalogPublicationReceipt receipt, Stage stage) {
    public CatalogPublicationReceiptReplay {
        receipt = Objects.requireNonNull(receipt, "Catalog publication receipt is required");
        stage = Objects.requireNonNull(stage, "Catalog publication replay stage is required");
        if (stage != stageOf(receipt)) {
            throw new IllegalArgumentException("Catalog publication replay stage does not match receipt state");
        }
    }

    public static CatalogPublicationReceiptReplay from(CatalogPublicationReceipt receipt) {
        return new CatalogPublicationReceiptReplay(receipt, stageOf(receipt));
    }

    public String sessionKey() {
        return receipt.sessionKey();
    }

    public CatalogCacheKey publicationKey() {
        return receipt.publicationKey();
    }

    public long revision() {
        return receipt.revision();
    }

    public CatalogPublicationReceipt.DispatchState dispatch() {
        return receipt.dispatch();
    }

    public CatalogPublicationReceipt.ClientReceiptState clientReceipt() {
        return receipt.clientReceipt();
    }

    public CatalogPublicationReceipt.CacheApplicationState cacheApplication() {
        return receipt.cacheApplication();
    }

    public String diagnosticCode() {
        return receipt.diagnosticCode();
    }

    public boolean serverDispatchConfirmed() {
        return receipt.serverDispatched();
    }

    public boolean clientReceiptConfirmed() {
        return receipt.clientReceived();
    }

    public boolean clientApplicationConfirmed() {
        return receipt.cacheApplied();
    }

    public boolean clientApplicationRequired() {
        return stage == Stage.CLIENT_APPLICATION_REQUIRED;
    }

    public boolean replayRequired() {
        return stage != Stage.COMPLETE;
    }

    public boolean converged() {
        return receipt.converged();
    }

    private static Stage stageOf(CatalogPublicationReceipt receipt) {
        if (receipt.dispatch() == CatalogPublicationReceipt.DispatchState.FAILED) {
            return Stage.DISPATCH_FAILED;
        }
        if (receipt.dispatch() != CatalogPublicationReceipt.DispatchState.DISPATCHED) {
            return Stage.DISPATCH_REQUIRED;
        }
        if (receipt.clientReceipt() == CatalogPublicationReceipt.ClientReceiptState.REJECTED) {
            return Stage.CLIENT_RECEIPT_REJECTED;
        }
        if (receipt.clientReceipt() != CatalogPublicationReceipt.ClientReceiptState.RECEIVED) {
            return Stage.CLIENT_RECEIPT_REQUIRED;
        }
        if (receipt.cacheApplication() == CatalogPublicationReceipt.CacheApplicationState.REJECTED) {
            return Stage.CLIENT_APPLICATION_REJECTED;
        }
        if (receipt.cacheApplication() != CatalogPublicationReceipt.CacheApplicationState.APPLIED) {
            return Stage.CLIENT_APPLICATION_REQUIRED;
        }
        return Stage.COMPLETE;
    }

    public enum Stage {
        DISPATCH_REQUIRED,
        DISPATCH_FAILED,
        CLIENT_RECEIPT_REQUIRED,
        CLIENT_RECEIPT_REJECTED,
        CLIENT_APPLICATION_REQUIRED,
        CLIENT_APPLICATION_REJECTED,
        COMPLETE
    }
}

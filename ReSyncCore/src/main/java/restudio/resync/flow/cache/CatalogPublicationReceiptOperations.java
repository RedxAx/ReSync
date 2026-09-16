package restudio.resync.flow.cache;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface CatalogPublicationReceiptOperations {
    CatalogPublicationReceipt.Claim claim(String sessionKey, String ownerToken);

    CatalogPublicationReceipt.Claim adopt(String sessionKey, String ownerToken);

    CatalogPublicationReceipt.Claim adoptForRedispatch(String sessionKey, String ownerToken,
                                                        CatalogCachePublication publication);

    CatalogPublicationReceipt recordDispatch(String sessionKey, String ownerToken, CatalogCachePublication publication);

    CatalogPublicationReceipt.Transition tryRecordDispatch(String sessionKey, String ownerToken,
                                                            CatalogCachePublication publication);

    void recordDispatchBatch(Map<String, String> sessionOwners, CatalogCachePublication publication);

    CatalogPublicationReceipt.BatchTransition tryRecordDispatchBatch(Map<String, String> sessionOwners,
                                                                       CatalogCachePublication publication);

    CatalogPublicationReceipt recordDispatchFailure(String sessionKey, String ownerToken,
                                                     CatalogCachePublication publication,
                                                     String diagnosticCode);

    CatalogPublicationReceipt.Transition tryRecordDispatchFailure(String sessionKey, String ownerToken,
                                                                    CatalogCachePublication publication,
                                                                    String diagnosticCode);

    CatalogPublicationReceipt.Transition acknowledgeClientReceipt(String sessionKey, String ownerToken,
                                                                    CatalogCacheKey publicationKey,
                                                                    long revision);

    CatalogPublicationReceipt.Transition acknowledgeCacheApplication(String sessionKey, String ownerToken,
                                                                       CatalogCacheKey publicationKey,
                                                                       long revision);

    CatalogPublicationReceipt.Transition rejectCacheApplication(String sessionKey, String ownerToken,
                                                                  CatalogCacheKey publicationKey,
                                                                  long revision, String diagnosticCode);

    Optional<CatalogPublicationReceipt> baseline(String sessionKey);

    List<CatalogPublicationReceipt> baselines();

    List<String> pendingSessionKeys();

    boolean remove(String sessionKey, String ownerToken);
}

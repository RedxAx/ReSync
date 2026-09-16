package restudio.resync.migration;

public interface PersistenceOwnershipProvider {
    PersistenceOwnershipIndex ownershipIndex(PersistenceOwnershipContext context);
}

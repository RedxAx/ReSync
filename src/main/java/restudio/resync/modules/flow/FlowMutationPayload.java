package restudio.resync.modules.flow;

public record FlowMutationPayload(String requestId, String payload, long authorityEpoch,
                                  boolean authorityEpochPresent, boolean authorityEpochValid,
                                  long expectedBindingEpoch, boolean expectedBindingEpochPresent,
                                  String expectedBindingHash) {
    public FlowMutationPayload(String requestId, String payload) {
        this(requestId, payload, 0L, false, true, 0L, false, null);
    }

    public boolean hasAuthorityEpoch() {
        return authorityEpochPresent;
    }

    public boolean hasExpectedBindingEpoch() {
        return expectedBindingEpochPresent;
    }
}

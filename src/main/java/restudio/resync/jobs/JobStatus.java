package restudio.resync.jobs;

import restudio.resync.contracts.ReSyncProtocolContract;

public enum JobStatus {
    PENDING(ReSyncProtocolContract.JOB_PENDING),
    RUNNING(ReSyncProtocolContract.JOB_RUNNING),
    SUCCEEDED(ReSyncProtocolContract.JOB_SUCCEEDED),
    FAILED(ReSyncProtocolContract.JOB_FAILED),
    CANCELLED(ReSyncProtocolContract.JOB_CANCELLED);

    private final String wireName;

    JobStatus(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}

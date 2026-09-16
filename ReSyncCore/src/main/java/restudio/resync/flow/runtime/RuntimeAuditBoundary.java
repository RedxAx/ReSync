package restudio.resync.flow.runtime;

public interface RuntimeAuditBoundary {
    boolean available(RuntimeSemantics.Audit audit);

    void record(RuntimeLeaseInput.AuditEvent event);

    static RuntimeAuditBoundary unavailable() {
        return new RuntimeAuditBoundary() {
            @Override
            public boolean available(RuntimeSemantics.Audit audit) {
                return audit == RuntimeSemantics.Audit.NONE;
            }

            @Override
            public void record(RuntimeLeaseInput.AuditEvent event) {
                throw new IllegalStateException("Runtime Audit Boundary Is Unavailable");
            }
        };
    }
}

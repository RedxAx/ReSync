package restudio.resync.flow.diagnostics;

import restudio.resync.migration.RebindablePersistenceParticipant;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;
import restudio.resync.migration.PersistenceOwnershipProvider;
import restudio.resync.migration.ScopedPersistenceParticipant;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

public final class DiagnosticReportPersistenceParticipant
    implements RebindablePersistenceParticipant, PersistenceOwnershipProvider {
    public static final String OWNER = "resync.diagnostics";
    private final ScopedPersistenceParticipant delegate;

    public DiagnosticReportPersistenceParticipant(Path dataRoot, Path root, StructuredFlowDiagnosticReporter reporter) {
        Objects.requireNonNull(reporter, "Diagnostic reporter is required");
        this.delegate = new ScopedPersistenceParticipant(OWNER, dataRoot, root, new ScopedPersistenceParticipant.Lifecycle() {
            @Override
            public void flush(Path activeRoot) {
                reporter.flush();
            }

            @Override
            public void quiesce(Path activeRoot) {
                reporter.quiesce();
            }

            @Override
            public void resume(Path activeRoot) {
                reporter.resume();
            }

            @Override
            public void rebind(Path previousRoot, Path nextRoot) {
                reporter.rebind(nextRoot);
            }

            @Override
            public void healthCheck(Path activeRoot) {
                reporter.healthCheck();
            }
        });
    }

    @Override
    public String owner() {
        return delegate.owner();
    }

    @Override
    public Path root() {
        return delegate.root();
    }

    @Override
    public Path rebindScope() {
        return delegate.rebindScope();
    }

    @Override
    public PersistenceOwnershipIndex ownershipIndex(PersistenceOwnershipContext context) {
        return PersistenceOwnershipIndex.builder(context).subtreeRoot().build();
    }

    @Override
    public void flush() throws IOException {
        delegate.flush();
    }

    @Override
    public void quiesce() throws IOException {
        delegate.quiesce();
    }

    @Override
    public void resume() throws IOException {
        delegate.resume();
    }

    @Override
    public void rebind(Path activeRoot) throws IOException {
        delegate.rebind(activeRoot);
    }

    @Override
    public void healthCheck() throws IOException {
        delegate.healthCheck();
    }
}

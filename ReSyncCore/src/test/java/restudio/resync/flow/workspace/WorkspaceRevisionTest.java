package restudio.resync.flow.workspace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkspaceRevisionTest {
    @Test
    void fencesStaleOperationsAndReturnsAcceptedDuplicates() {
        WorkspaceRevision<String> revision = new WorkspaceRevision<>(4L, 8);

        assertEquals(WorkspaceRevision.Status.ACCEPT, revision.assess(4L, "first").status());
        assertEquals("event-5", revision.advance("first", sequence -> "event-" + sequence));
        assertEquals(5L, revision.sequence());

        WorkspaceRevision.Assessment<String> duplicate = revision.assess(4L, "first");
        assertEquals(WorkspaceRevision.Status.DUPLICATE, duplicate.status());
        assertEquals("event-5", duplicate.existing());
        assertEquals(WorkspaceRevision.Status.CONFLICT, revision.assess(4L, "second").status());
        assertEquals(WorkspaceRevision.Status.ACCEPT, revision.assess(5L, "second").status());
    }

    @Test
    void boundsRememberedOperationResults() {
        WorkspaceRevision<String> revision = new WorkspaceRevision<>(0L, 2);
        revision.advance("first", sequence -> "first");
        revision.advance("second", sequence -> "second");
        revision.advance("third", sequence -> "third");

        assertEquals(2, revision.operations().size());
        assertEquals(WorkspaceRevision.Status.CONFLICT, revision.assess(0L, "first").status());
        assertEquals(WorkspaceRevision.Status.DUPLICATE, revision.assess(1L, "second").status());
    }
}

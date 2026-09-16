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
    void retainsAcceptedOperationsAndRejectsIdReuseWithAnotherPayload() {
        WorkspaceRevision<String> revision = new WorkspaceRevision<>(0L, 2);
        revision.advance("first", "hash-one", sequence -> "first");
        revision.advance("second", "hash-two", sequence -> "second");
        revision.advance("third", "hash-three", sequence -> "third");

        assertEquals(3, revision.operations().size());
        assertEquals(WorkspaceRevision.Status.DUPLICATE, revision.assess(0L, "first", "hash-one").status());
        assertEquals(WorkspaceRevision.Status.MISMATCH, revision.assess(0L, "first", "different").status());
    }
}

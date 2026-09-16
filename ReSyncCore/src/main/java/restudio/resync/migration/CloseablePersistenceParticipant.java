package restudio.resync.migration;

import java.io.IOException;

public interface CloseablePersistenceParticipant extends RebindablePersistenceParticipant {
    void close() throws IOException;
}

package restudio.resync.migration;

import java.io.IOException;
import java.nio.file.Path;

public interface RebindablePersistenceParticipant extends PersistenceParticipant {
    default Path rebindScope() {
        return root();
    }

    @Override
    void flush() throws IOException;

    @Override
    void quiesce() throws IOException;

    @Override
    void resume() throws IOException;

    @Override
    void rebind(Path activeRoot) throws IOException;

    @Override
    void healthCheck() throws IOException;
}

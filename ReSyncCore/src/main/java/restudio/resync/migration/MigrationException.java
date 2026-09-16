package restudio.resync.migration;

import java.io.IOException;

public final class MigrationException extends IOException {
    private static final long serialVersionUID = 1L;

    public MigrationException(String message) {
        super(message);
    }

    public MigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}

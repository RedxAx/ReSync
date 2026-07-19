package restudio.resync.runtime;

import restudio.resync.Log;
import restudio.resync.core.Session;
import restudio.resync.modules.ModuleContext;
import restudio.resync.protocol.messages.ErrorMessage;

public class RuntimeNotificationService {
    private final ModuleContext context;

    public RuntimeNotificationService(ModuleContext context) {
        this.context = context;
    }

    public void broadcastError(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        ErrorMessage error = new ErrorMessage();
        error.setErrorCode(501);
        error.setErrorText(message);
        try {
            for (Session session : context.getSessionManager().getSessions()) {
                if (session.getConnection() != null && session.getConnection().getFrameSender() != null) {
                    context.getCodec().sendMessage(session.getConnection().getFrameSender(), error, 0, false);
                }
            }
        } catch (RuntimeException exception) {
            Log.warn("Failed to broadcast a ReSync runtime error: " + exception.getMessage(), exception);
        }
        Log.warn(message);
    }
}

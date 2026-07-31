package restudio.resync.modules.flow;

import restudio.resync.core.Session;

public interface FlowResourceCommitListener {
    FlowResourceCommitListener NONE = new FlowResourceCommitListener() {
        @Override
        public void saved(String type, String resourceId, String payload) {
        }

        @Override
        public void deleted(String type, String resourceId) {
        }
    };

    void saved(String type, String resourceId, String payload);

    default void saved(Session session, String type, String resourceId, String payload) {
        saved(type, resourceId, payload);
    }

    void deleted(String type, String resourceId);

    default void deleted(Session session, String type, String resourceId) {
        deleted(type, resourceId);
    }
}

package restudio.resync.modules.flow;

public interface FlowResourceMutationListener {
    FlowResourceMutationListener NONE = new FlowResourceMutationListener() {
        @Override
        public void saved(String type, String resourceId, String payload) {
        }

        @Override
        public void deleted(String type, String resourceId) {
        }
    };

    void saved(String type, String resourceId, String payload);

    void deleted(String type, String resourceId);
}

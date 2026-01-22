package restudio.resync.flow;

public interface NodeCategory {
    
    void registerNodes(FlowRegistry registry);
    
    default String getCategoryName() {
        return this.getClass().getSimpleName();
    }
}

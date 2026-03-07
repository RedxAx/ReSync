package restudio.resync.flow;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import restudio.flow.data.FlowGraph;

public interface FlowExecutionListener {
    void onFlowExecution(FlowGraph graph, String startNodeId, Player player, Event event);
}

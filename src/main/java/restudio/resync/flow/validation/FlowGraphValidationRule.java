package restudio.resync.flow.validation;

import restudio.flow.data.FlowGraph;

import java.util.List;

@FunctionalInterface
public interface FlowGraphValidationRule {
    List<FlowGraphDiagnostic> validate(FlowGraph graph);
}

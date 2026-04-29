package restudio.resync.worldgen.pipeline;

import java.util.Map;

@FunctionalInterface
public interface PipelineNode {
    Object evaluate(EvalContext context, Map<String, PipelineNode> upstreams);
}

package restudio.resync.flow.function;

import restudio.resync.flow.graph.GraphNode;

@FunctionalInterface
public interface TypedFunctionNodeCompiler {
    CompiledFunctionBody.Step compile(GraphNode node);
}

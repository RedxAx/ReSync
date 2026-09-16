package restudio.resync.flow.function;

import java.util.Optional;

@FunctionalInterface
public interface CompiledFunctionResolver {
    Optional<CompiledFunction> resolve(FunctionLocator function, FunctionRevision revision);
}

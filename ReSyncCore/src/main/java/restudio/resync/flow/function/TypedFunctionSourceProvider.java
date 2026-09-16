package restudio.resync.flow.function;

import java.util.Optional;

@FunctionalInterface
public interface TypedFunctionSourceProvider {
    Optional<FunctionSourceDocument> resolve(FunctionLocator function, FunctionRevision revision);
}

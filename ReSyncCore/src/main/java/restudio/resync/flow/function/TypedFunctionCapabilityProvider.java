package restudio.resync.flow.function;

import java.util.Optional;

@FunctionalInterface
public interface TypedFunctionCapabilityProvider {
    Optional<TypedFunctionCapabilitySet> resolve(FunctionSourceDocument source);
}

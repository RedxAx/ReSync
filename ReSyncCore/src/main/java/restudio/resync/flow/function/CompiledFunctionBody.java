package restudio.resync.flow.function;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record CompiledFunctionBody(List<Step> steps, int maximumSteps, Map<String, Object> metadata) {
    public static final int MAXIMUM_STEPS = 256;

    public CompiledFunctionBody {
        Objects.requireNonNull(steps, "Compiled Function Body Steps Are Required");
        if (steps.size() > MAXIMUM_STEPS) {
            throw new IllegalArgumentException("Compiled Function Body Exceeds The Maximum Step Count");
        }
        if (maximumSteps < 0 || maximumSteps > MAXIMUM_STEPS) {
            throw new IllegalArgumentException("Compiled Function Body Maximum Step Count Is Invalid");
        }
        if (steps.size() > maximumSteps) {
            throw new IllegalArgumentException("Compiled Function Body Contains More Steps Than Its Execution Budget");
        }
        steps = steps.stream()
            .map(step -> Objects.requireNonNull(step, "Compiled Function Body Cannot Contain Null Steps"))
            .toList();
        metadata = FunctionContractSupport.immutableMap(metadata, "Compiled Function Body Metadata");
    }

    public CompiledFunctionBody(List<Step> steps) {
        this(steps, MAXIMUM_STEPS, Map.of());
    }

    public CompiledFunctionBody(List<Step> steps, int maximumSteps) {
        this(steps, maximumSteps, Map.of());
    }

    public static CompiledFunctionBody empty() {
        return new CompiledFunctionBody(List.of());
    }

    public static CompiledFunctionBody of(Step... steps) {
        Objects.requireNonNull(steps, "Compiled Function Body Steps Are Required");
        return new CompiledFunctionBody(List.of(steps));
    }

    public int size() {
        return steps.size();
    }

    public Map<String, Object> canonicalValue() {
        return FunctionContractSupport.immutableMap(Map.of(
            "maximumSteps", maximumSteps,
            "metadata", metadata,
            "stepCount", steps.size()), "Compiled Function Body Canonical Data");
    }

    public String canonicalJson() {
        return FunctionContractSupport.canonicalJson(canonicalValue());
    }

    @FunctionalInterface
    public interface Step {
        FunctionFrame apply(FunctionFrame frame) throws Exception;
    }
}

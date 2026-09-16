package restudio.resync.flow.runtime;

import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypedValue;

import java.util.Objects;

public record RuntimeFailure(Diagnostic diagnostic, boolean retryable, TypedValue payload) {
    public RuntimeFailure {
        diagnostic = Objects.requireNonNull(diagnostic, "Failure Diagnostic Is Required");
    }

    public RuntimeFailure(Diagnostic diagnostic, boolean retryable) {
        this(diagnostic, retryable, null);
    }

    public boolean hasPayload(TypeExpr expectedType) {
        return payload != null
            && payload.state() != TypedValue.State.ABSENT
            && Objects.requireNonNull(expectedType, "Expected Failure Payload Type Is Required").equals(payload.type());
    }
}

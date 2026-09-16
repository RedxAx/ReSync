package restudio.resync.flow;

import restudio.resync.flow.function.FunctionExecutionRequest;
import restudio.resync.flow.function.FunctionLocator;
import restudio.resync.flow.function.FunctionRevision;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.runtime.CompiledRuntimeContext;
import restudio.resync.flow.runtime.RuntimeAuthority;
import restudio.resync.flow.runtime.RuntimeExecutionContext;
import restudio.resync.flow.runtime.RuntimePrincipal;

import java.util.Objects;

public record CompiledFunctionExecutionRequest(
    FunctionExecutionRequest execution,
    CatalogBinding catalogBinding,
    ContentHash capabilityFingerprint,
    RuntimeAuthority authority,
    RuntimePrincipal principal,
    CompiledRuntimeContext runtimeContext,
    String sessionReference,
    long requestedDeadlineMillis,
    String creatorPrincipal,
    String creatorSessionReference
) {
    public CompiledFunctionExecutionRequest(FunctionExecutionRequest execution,
                                            CatalogBinding catalogBinding,
                                            ContentHash capabilityFingerprint) {
        this(execution, catalogBinding, capabilityFingerprint, null, null, null, null,
            RuntimeExecutionContext.NO_DEADLINE, null, null);
    }

    public CompiledFunctionExecutionRequest(FunctionExecutionRequest execution,
                                            CatalogBinding catalogBinding,
                                            ContentHash capabilityFingerprint,
                                            RuntimeAuthority authority,
                                            RuntimePrincipal principal,
                                            CompiledRuntimeContext runtimeContext,
                                            long requestedDeadlineMillis) {
        this(execution, catalogBinding, capabilityFingerprint, authority, principal, runtimeContext, null,
            requestedDeadlineMillis, null, null);
    }

    public CompiledFunctionExecutionRequest(FunctionExecutionRequest execution,
                                            CatalogBinding catalogBinding,
                                            ContentHash capabilityFingerprint,
                                            RuntimeAuthority authority,
                                            RuntimePrincipal principal,
                                            CompiledRuntimeContext runtimeContext,
                                            String sessionReference,
                                            long requestedDeadlineMillis) {
        this(execution, catalogBinding, capabilityFingerprint, authority, principal, runtimeContext, sessionReference,
            requestedDeadlineMillis, null, null);
    }

    public CompiledFunctionExecutionRequest {
        execution = Objects.requireNonNull(execution, "Compiled Function Execution Request Is Required");
        if (authority == null && principal != null) {
            throw new IllegalArgumentException("A Function Principal Requires A Runtime Authority");
        }
        if (principal != null && runtimeContext != null && runtimeContext.principal() != null
            && !principal.canonical().equals(runtimeContext.principal().canonical())) {
            throw new IllegalArgumentException("Function Runtime Context Principal Does Not Match The Request Principal");
        }
        if (sessionReference != null && sessionReference.isBlank()) {
            throw new IllegalArgumentException("Function Session Reference Cannot Be Blank");
        }
        if (requestedDeadlineMillis < 0) {
            throw new IllegalArgumentException("Requested Deadline Cannot Be Negative");
        }
        if (creatorPrincipal != null && creatorPrincipal.isBlank()) {
            throw new IllegalArgumentException("Function Creator Principal Cannot Be Blank");
        }
        if (creatorSessionReference != null && creatorSessionReference.isBlank()) {
            throw new IllegalArgumentException("Function Creator Session Reference Cannot Be Blank");
        }
        if (creatorPrincipal == null && creatorSessionReference != null) {
            throw new IllegalArgumentException("Function Creator Session Requires A Creator Principal");
        }
    }

    public FunctionLocator function() {
        return execution.function();
    }

    public FunctionRevision revision() {
        return execution.revision();
    }
}

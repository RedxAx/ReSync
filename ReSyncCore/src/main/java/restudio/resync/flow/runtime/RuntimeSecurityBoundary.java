package restudio.resync.flow.runtime;

import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;

public interface RuntimeSecurityBoundary {
    boolean authorize(RuntimeAuthority authority, ContractRef<CapabilityId> capability);

    default boolean authorize(RuntimeExecutionContext context, ContractRef<CapabilityId> capability) {
        return context != null && authorize(context.authority(), capability);
    }

    boolean confirm(RuntimeAuthority authority, RuntimeBindingKey binding, RuntimeSemantics.Confirmation confirmation);

    default boolean confirm(RuntimeExecutionContext context, RuntimeBindingKey binding,
                            RuntimeSemantics.Confirmation confirmation) {
        return context != null && confirm(context.authority(), binding, confirmation);
    }

    default boolean principalAllowed(RuntimeExecutionContext context) {
        return context != null && context.principal() != null;
    }

    default boolean requiresTrustedPrincipal() {
        return false;
    }

    default boolean approvePolicyRetry(
        RuntimeAuthority authority,
        RuntimeBindingKey binding,
        RuntimeFailure failure,
        int attempt
    ) {
        return false;
    }

    static RuntimeSecurityBoundary denyAll() {
        return new RuntimeSecurityBoundary() {
            @Override
            public boolean authorize(RuntimeAuthority authority, ContractRef<CapabilityId> capability) {
                return false;
            }

            @Override
            public boolean confirm(RuntimeAuthority authority, RuntimeBindingKey binding, RuntimeSemantics.Confirmation confirmation) {
                return confirmation == RuntimeSemantics.Confirmation.NONE;
            }
        };
    }
}

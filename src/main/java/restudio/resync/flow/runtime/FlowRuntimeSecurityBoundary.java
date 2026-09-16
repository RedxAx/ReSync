package restudio.resync.flow.runtime;

import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;

import java.util.Objects;
import java.util.function.Supplier;

public final class FlowRuntimeSecurityBoundary implements RuntimeSecurityBoundary {
    private final RuntimeAuthority trustedAuthority;
    private final Supplier<RuntimeRegistrySnapshot> activeRuntime;
    private final RuntimePrincipalAuthority principalAuthority;

    public FlowRuntimeSecurityBoundary(RuntimeAuthority trustedAuthority, Supplier<RuntimeRegistrySnapshot> activeRuntime) {
        this(trustedAuthority, activeRuntime, null);
    }

    public FlowRuntimeSecurityBoundary(RuntimeAuthority trustedAuthority, Supplier<RuntimeRegistrySnapshot> activeRuntime,
                                       RuntimePrincipalAuthority principalAuthority) {
        this.trustedAuthority = Objects.requireNonNull(trustedAuthority, "Trusted Runtime Authority Is Required");
        this.activeRuntime = Objects.requireNonNull(activeRuntime, "Active Runtime Snapshot Supplier Is Required");
        this.principalAuthority = principalAuthority;
    }

    public RuntimeAuthority trustedAuthority() {
        return trustedAuthority;
    }

    public boolean available() {
        return activeSnapshot() != null;
    }

    @Override
    public boolean principalAllowed(RuntimeExecutionContext context) {
        if (context == null || context.principal() == null) {
            return false;
        }
        return principalAuthority == null
            ? context.authority() == trustedAuthority
            : principalAuthority.trusts(context.principal(), context.authority());
    }

    @Override
    public boolean requiresTrustedPrincipal() {
        return principalAuthority != null;
    }

    @Override
    public boolean authorize(RuntimeAuthority authority, ContractRef<CapabilityId> capability) {
        if (!trusted(authority) || capability == null) {
            return false;
        }
        RuntimeRegistrySnapshot snapshot = activeSnapshot();
        return snapshot != null && snapshot.hasAuthorization(capability);
    }

    @Override
    public boolean confirm(RuntimeAuthority authority, RuntimeBindingKey binding, RuntimeSemantics.Confirmation confirmation) {
        if (!trusted(authority) || binding == null || confirmation == null) {
            return false;
        }
        RuntimeRegistrySnapshot snapshot = activeSnapshot();
        if (snapshot == null || snapshot.binding(binding).isEmpty()) {
            return false;
        }
        return switch (confirmation) {
            case NONE, SERVER -> true;
            case CLIENT -> false;
        };
    }

    @Override
    public boolean approvePolicyRetry(RuntimeAuthority authority, RuntimeBindingKey binding, RuntimeFailure failure, int attempt) {
        return false;
    }

    private boolean trusted(RuntimeAuthority authority) {
        return trustedAuthority == authority;
    }

    private RuntimeRegistrySnapshot activeSnapshot() {
        try {
            return activeRuntime.get();
        } catch (RuntimeException failure) {
            return null;
        }
    }
}

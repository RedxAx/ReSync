package restudio.resync.flow.runtime;

import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ProviderId;

public final class RuntimeBindingCollisionException extends IllegalArgumentException {
    public RuntimeBindingCollisionException(RuntimeBindingKey key) {
        super("Runtime Binding Collision: " + key.canonical());
    }

    public RuntimeBindingCollisionException(ContractRef<ProviderId> provider) {
        super("Runtime Provider Collision: " + provider.canonicalText());
    }
}

package restudio.resync.flow.runtime;

import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ProviderId;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class RuntimeBinding {
    private final RuntimeBindingDescriptor descriptor;
    private final RuntimeOperationHandler handler;
    private final ContentHash executionFingerprint;

    public RuntimeBinding(RuntimeBindingDescriptor descriptor, RuntimeOperationHandler handler) {
        this.descriptor = Objects.requireNonNull(descriptor, "Binding Descriptor Is Required");
        this.handler = handler;
        if (descriptor.available() && handler == null) {
            throw new IllegalArgumentException("An Available Binding Requires An Operation Handler");
        }
        this.executionFingerprint = descriptor.executionFingerprint();
    }

    public RuntimeBinding(RuntimeBindingDescriptor descriptor) {
        this(descriptor, null);
    }

    public static RuntimeBinding available(RuntimeOperationDescriptor operation, ContractRef<ProviderId> provider, String providerVersion, RuntimeOperationHandler handler) {
        return new RuntimeBinding(new RuntimeBindingDescriptor(operation, provider, providerVersion, true), handler);
    }

    public static RuntimeBinding unavailable(RuntimeOperationDescriptor operation, ContractRef<ProviderId> provider, String providerVersion) {
        return new RuntimeBinding(new RuntimeBindingDescriptor(operation, provider, providerVersion, false));
    }

    public static RuntimeBinding available(
        RuntimeOperationDescriptor operation,
        ContractRef<ProviderId> provider,
        String providerVersion,
        RuntimeOperationHandler handler,
        Map<String, ?> unknown
    ) {
        return new RuntimeBinding(new RuntimeBindingDescriptor(operation, provider, providerVersion, true, unknown), handler);
    }

    public static RuntimeBinding unavailable(
        RuntimeOperationDescriptor operation,
        ContractRef<ProviderId> provider,
        String providerVersion,
        Map<String, ?> unknown
    ) {
        return new RuntimeBinding(new RuntimeBindingDescriptor(operation, provider, providerVersion, false, unknown));
    }

    public RuntimeBindingDescriptor descriptor() {
        return descriptor;
    }

    public RuntimeBindingKey key() {
        return descriptor.key();
    }

    public ContractRef<ProviderId> provider() {
        return descriptor.provider();
    }

    public boolean available() {
        return descriptor.available() && handler != null;
    }

    Optional<RuntimeOperationHandler> handler() {
        return Optional.ofNullable(handler);
    }

    public ContentHash executionFingerprint() {
        return executionFingerprint;
    }
}

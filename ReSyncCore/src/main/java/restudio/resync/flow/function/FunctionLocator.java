package restudio.resync.flow.function;

import java.util.Map;
import java.util.Objects;

import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;

public final class FunctionLocator implements Comparable<FunctionLocator> {
    private final ServerResourceLocator resource;

    public FunctionLocator(ServerResourceLocator resource) {
        this.resource = Objects.requireNonNull(resource, "Function Resource Locator Is Required");
    }

    public FunctionLocator(ServerId serverId, ContractRef<ResourceTypeId> type, String id) {
        this(new ServerResourceLocator(serverId, type, id));
    }

    public static FunctionLocator of(ServerResourceLocator resource) {
        return new FunctionLocator(resource);
    }

    public static FunctionLocator parseCanonicalText(String value) {
        return new FunctionLocator(ServerResourceLocator.parseCanonicalText(value));
    }

    public ServerResourceLocator resource() {
        return resource;
    }

    public ServerId serverId() {
        return resource.serverId();
    }

    public ContractRef<ResourceTypeId> type() {
        return resource.type();
    }

    public String id() {
        return resource.id();
    }

    public String canonicalText() {
        return resource.canonicalText();
    }

    public Map<String, Object> canonicalValue() {
        return resource.canonicalValue();
    }

    @Override
    public int compareTo(FunctionLocator other) {
        return resource.compareTo(Objects.requireNonNull(other, "Function Locator Is Required").resource);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof FunctionLocator locator && resource.equals(locator.resource);
    }

    @Override
    public int hashCode() {
        return resource.hashCode();
    }

    @Override
    public String toString() {
        return canonicalText();
    }
}

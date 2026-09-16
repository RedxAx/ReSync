package restudio.resync.flow.identity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class ServerResourceLocator implements Comparable<ServerResourceLocator> {
    private final ServerId serverId;
    private final ResourceKey key;
    private final Map<String, Object> unknown;
    private final boolean keyExplicit;

    public ServerResourceLocator(ServerId serverId, ContractRef<ResourceTypeId> type, String id) {
        this(serverId, new ResourceKey(type, id), false, Map.of());
    }

    public ServerResourceLocator(ServerId serverId, ContractRef<ResourceTypeId> type, String id, Map<String, ?> unknown) {
        this(serverId, new ResourceKey(type, id), false, unknown);
    }

    public ServerResourceLocator(ServerId serverId, ResourceKey key) {
        this(serverId, key, true, Map.of());
    }

    public ServerResourceLocator(ServerId serverId, ResourceKey key, Map<String, ?> unknown) {
        this(serverId, key, true, unknown);
    }

    private ServerResourceLocator(ServerId serverId, ResourceKey key, boolean keyExplicit, Map<String, ?> unknown) {
        this.serverId = Objects.requireNonNull(serverId, "Server ID is required");
        this.key = Objects.requireNonNull(key, "Resource key is required");
        this.keyExplicit = keyExplicit;
        this.unknown = IdentitySupport.unknown(unknown, "resource locator unknown data");
    }

    public ServerResourceLocator(UUID serverId, ContractRef<ResourceTypeId> type, String id) {
        this(new ServerId(serverId), type, id);
    }

    public ServerResourceLocator(UUID serverId, ContractRef<ResourceTypeId> type, String id, Map<String, ?> unknown) {
        this(new ServerId(serverId), type, id, unknown);
    }

    public ServerResourceLocator(UUID serverId, ResourceKey key) {
        this(new ServerId(serverId), key);
    }

    public ServerResourceLocator(UUID serverId, ResourceKey key, Map<String, ?> unknown) {
        this(new ServerId(serverId), key, unknown);
    }

    public static ServerResourceLocator of(ServerId serverId, ContractRef<ResourceTypeId> type, String id) {
        return new ServerResourceLocator(serverId, type, id);
    }

    public ServerId serverId() {
        return serverId;
    }

    public ServerId server() {
        return serverId;
    }

    public ResourceKey key() {
        return key;
    }

    public ContractRef<ResourceTypeId> type() {
        return key.type();
    }

    public OwnerId owner() {
        return key.owner();
    }

    public ResourceTypeId resourceType() {
        return key.resourceType();
    }

    public String id() {
        return key.id();
    }

    public Map<String, Object> unknown() {
        return unknown;
    }

    public String canonicalText() {
        return serverId.canonicalText() + "/" + key.canonicalText();
    }

    public Map<String, Object> canonicalValue() {
        var known = new LinkedHashMap<String, Object>();
        known.put("id", id());
        known.put("serverId", serverId.canonicalText());
        known.put("type", type().canonicalValue());
        if (keyExplicit || !key.unknown().isEmpty()) {
            known.put("key", key.canonicalValue());
        }
        return IdentitySupport.merge(unknown, known);
    }

    public static ServerResourceLocator parseCanonicalText(String value) {
        Objects.requireNonNull(value, "Resource locator text is required");
        String[] parts = value.split("/", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("Resource locator text must contain server, owner, type, and ID");
        }
        ServerId serverId = new ServerId(IdentityValidation.uuid(parts[0], "Server ID"));
        ContractRef<ResourceTypeId> type = new ContractRef<>(new OwnerId(parts[1]), new ResourceTypeId(parts[2]));
        ServerResourceLocator locator = new ServerResourceLocator(serverId, type, parts[3]);
        if (!locator.canonicalText().equals(value)) {
            throw new IllegalArgumentException("Resource locator text is not canonical");
        }
        return locator;
    }

    @Override
    public int compareTo(ServerResourceLocator other) {
        int serverOrder = serverId.compareTo(other.serverId);
        return serverOrder != 0 ? serverOrder : key.compareTo(other.key);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof ServerResourceLocator other)) {
            return false;
        }
        return serverId.equals(other.serverId) && key.equals(other.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(serverId, key);
    }

    @Override
    public String toString() {
        return canonicalText();
    }
}

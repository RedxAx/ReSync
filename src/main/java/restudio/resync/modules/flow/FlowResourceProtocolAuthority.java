package restudio.resync.modules.flow;

import restudio.resync.protocol.ReSyncProtocolContract;
import restudio.resync.resources.ReSyncManagedResource;

import java.util.Objects;

public final class FlowResourceProtocolAuthority {
    private static final FlowResourceProtocolAuthority SHARED = new FlowResourceProtocolAuthority();

    private FlowResourceProtocolAuthority() {
    }

    public static FlowResourceProtocolAuthority shared() {
        return SHARED;
    }

    public ReSyncProtocolContract.ResourceContract requireResource(String typeId) {
        if (typeId == null || typeId.isBlank()) {
            throw new IllegalArgumentException("Resource type ID is required");
        }
        ReSyncProtocolContract.ResourceContract resource = ReSyncProtocolContract.resource(typeId);
        if (resource == null) {
            throw new IllegalArgumentException("Unknown shared resource contract: " + typeId);
        }
        return resource;
    }

    public ReSyncProtocolContract.ResourceFlowPackets requireFlowPackets(String typeId) {
        ReSyncProtocolContract.ResourceFlowPackets packets = requireResource(typeId).flowPackets();
        if (packets == null) {
            throw new IllegalStateException("Resource has no Flow packet contract: " + typeId);
        }
        return packets;
    }

    public void validateDescriptor(ReSyncManagedResource descriptor) {
        Objects.requireNonNull(descriptor, "Resource descriptor is required");
        ReSyncProtocolContract.ResourceContract resource = requireResource(descriptor.typeId());
        if (!Objects.equals(resource.displayName(), descriptor.displayName())
            || !Objects.equals(resource.defaultFolder(), descriptor.defaultFolder())
            || resource.jsonStorageSupported() != descriptor.jsonStorageSupported()) {
            throw new IllegalStateException("Resource descriptor differs from the shared contract: " + descriptor.typeId());
        }
        ReSyncProtocolContract.ResourceFlowPackets expected = resource.flowPackets();
        ReSyncManagedResource.FlowPackets actual = descriptor.flowPackets();
        if (expected == null || actual == null) {
            if ((expected == null) != (actual == null)) {
                throw new IllegalStateException("Resource packet contract differs from the shared contract: " + descriptor.typeId());
            }
            return;
        }
        if (expected.request() != actual.request()
            || expected.listRequest() != actual.listRequest()
            || expected.data() != actual.data()
            || expected.list() != actual.list()
            || expected.save() != actual.save()
            || expected.delete() != actual.delete()
            || expected.saveAck() != actual.saveAck()) {
            throw new IllegalStateException("Resource packet contract differs from the shared contract: " + descriptor.typeId());
        }
    }

    public boolean matches(ReSyncManagedResource descriptor, byte packetId) {
        validateDescriptor(descriptor);
        ReSyncManagedResource.FlowPackets packets = descriptor.flowPackets();
        return packets != null && packets.matches(packetId);
    }
}

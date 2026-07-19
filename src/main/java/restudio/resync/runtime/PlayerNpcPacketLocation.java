package restudio.resync.runtime;

import com.github.retrooper.packetevents.protocol.world.Location;

final class PlayerNpcPacketLocation {
    private PlayerNpcPacketLocation() {
    }

    static Location create(double x, double y, double z, float yaw, float pitch) {
        return new Location(x, y, z, yaw, pitch);
    }
}

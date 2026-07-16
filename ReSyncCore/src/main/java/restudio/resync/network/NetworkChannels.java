package restudio.resync.network;

import java.util.Set;

public final class NetworkChannels {
    public static final String CONTROL = "network.control";
    public static final String PRESENCE = "network.presence";
    public static final String ROUTING = "network.routing";
    public static final String TRANSFER = "network.transfer";
    public static final String STATE = "network.state";
    public static final String VARIABLES = "network.variables";
    public static final String EVENTS = "network.events";
    public static final String RESOURCES = "network.resources";
    public static final Set<String> ALL = Set.of(CONTROL, PRESENCE, ROUTING, TRANSFER, STATE, VARIABLES, EVENTS, RESOURCES);

    private NetworkChannels() {
    }
}

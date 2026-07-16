package restudio.resync.network.paper.state;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

public record NetworkPlayerStateConfig(NetworkPlayerStateProfile profile, String realm, String nodeId, boolean inventory, boolean enderChest, boolean vitals, boolean experience, boolean movement, boolean effects, boolean attributes, boolean advancements, boolean recipes, boolean statistics, boolean persistentData, NetworkPlayerLocationPolicy locationPolicy, Set<String> persistentDataNamespaces) {
    public NetworkPlayerStateConfig {
        profile = profile == null ? NetworkPlayerStateProfile.PRESENCE_ONLY : profile;
        realm = realm == null ? "" : realm.trim();
        nodeId = nodeId == null ? "" : nodeId.trim();
        locationPolicy = locationPolicy == null ? NetworkPlayerLocationPolicy.NEVER : locationPolicy;
        persistentDataNamespaces = persistentDataNamespaces == null ? Set.of() : persistentDataNamespaces.stream().map(value -> value == null ? "" : value.trim().toLowerCase(Locale.ROOT)).filter(value -> !value.isBlank()).collect(Collectors.toUnmodifiableSet());
        if ((inventory || enderChest || vitals || experience || movement || effects || attributes || advancements || recipes || statistics || persistentData || locationPolicy != NetworkPlayerLocationPolicy.NEVER) && (realm.isBlank() || nodeId.isBlank())) {
            throw new IllegalArgumentException("Network Transfer Realm Is Required");
        }
        if (persistentData && persistentDataNamespaces.isEmpty()) {
            throw new IllegalArgumentException("Network Persistent Data Requires An Allowlist");
        }
    }

    public static NetworkPlayerStateConfig load(Path dataDirectory) throws IOException {
        Properties properties = new Properties();
        Path file = dataDirectory.toAbsolutePath().normalize().resolve("resync.properties");
        if (Files.exists(file)) {
            try (InputStream input = Files.newInputStream(file)) {
                properties.load(input);
            }
        }
        NetworkPlayerStateProfile profile;
        try {
            profile = NetworkPlayerStateProfile.valueOf(properties.getProperty("network.transfer.profile", "PRESENCE_ONLY").trim().replace(' ', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Network Transfer Profile Is Invalid");
        }
        boolean survival = profile == NetworkPlayerStateProfile.SURVIVAL_SHARED;
        boolean custom = profile == NetworkPlayerStateProfile.CUSTOM;
        NetworkPlayerLocationPolicy locationPolicy;
        try {
            locationPolicy = NetworkPlayerLocationPolicy.valueOf(properties.getProperty("network.transfer.location-policy", "NEVER").trim().replace(' ', '_').replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Network Transfer Location Policy Is Invalid");
        }
        Set<String> namespaces = Arrays.stream(properties.getProperty("network.transfer.persistent-data-namespaces", "").split(",")).map(String::trim).filter(value -> !value.isBlank()).map(value -> value.toLowerCase(Locale.ROOT)).collect(Collectors.toCollection(LinkedHashSet::new));
        return new NetworkPlayerStateConfig(profile, properties.getProperty("network.transfer.realm", ""), properties.getProperty("network.node-id", ""), survival || custom && enabled(properties, "inventory"), survival || custom && enabled(properties, "ender-chest"), survival || custom && enabled(properties, "vitals"), survival || custom && enabled(properties, "experience"), survival || custom && enabled(properties, "movement"), survival || custom && enabled(properties, "effects"), survival || custom && enabled(properties, "attributes"), custom && enabled(properties, "advancements"), custom && enabled(properties, "recipes"), custom && enabled(properties, "statistics"), custom && enabled(properties, "persistent-data"), custom ? locationPolicy : NetworkPlayerLocationPolicy.NEVER, namespaces);
    }

    public boolean enabled() {
        return inventory || enderChest || vitals || experience || movement || effects || attributes || advancements || recipes || statistics || persistentData || locationPolicy != NetworkPlayerLocationPolicy.NEVER;
    }

    public String family() {
        return realm + "/" + profile.name().toLowerCase(Locale.ROOT);
    }

    private static boolean enabled(Properties properties, String family) {
        return Boolean.parseBoolean(properties.getProperty("network.transfer.family." + family, "false"));
    }
}

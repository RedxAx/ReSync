package restudio.resync.modules.flow;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.track.Track;
import net.luckperms.api.node.types.PermissionNode;
import org.bukkit.Bukkit;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.RegisteredServiceProvider;
import restudio.resync.api.OptionCatalogItem;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.OptionCatalogQuery;
import restudio.resync.api.OptionCatalogRegistry;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;

public final class LuckPermsOptionCatalogService {
    public static final String GROUP_SOURCE = "server:luckperms:group";
    public static final String TRACK_SOURCE = "server:luckperms:track";
    public static final String PERMISSION_SOURCE = "server:luckperms:permission";

    public void registerProviders(OptionCatalogRegistry registry) {
        registry.register(provider(GROUP_SOURCE, this::groups, this::groupItem));
        registry.register(provider(TRACK_SOURCE, this::tracks, this::trackItem));
        registry.register(provider(PERMISSION_SOURCE, this::permissions, this::permissionItem));
    }

    private OptionCatalogProvider provider(String sourceId, Supplier<List<String>> values, Function<String, OptionCatalogItem> itemFactory) {
        return new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return sourceId;
            }

            @Override
            public String revision() {
                List<String> current = values.get();
                return sourceId + ":" + current.size() + ":" + String.join(",", current);
            }

            @Override
            public List<String> values() {
                return values.get();
            }

            @Override
            public List<OptionCatalogItem> items() {
                return values().stream().map(itemFactory).toList();
            }

            @Override
            public String status(OptionCatalogQuery query) {
                return luckPerms() != null ? "available" : "unavailable";
            }

            @Override
            public String diagnostic(OptionCatalogQuery query) {
                return luckPerms() != null ? "" : "LuckPerms Service Is Unavailable";
            }
        };
    }

    private List<String> groups() {
        LuckPerms luckPerms = luckPerms();
        if (luckPerms == null) {
            return List.of();
        }
        return luckPerms.getGroupManager().getLoadedGroups().stream().map(Group::getName)
            .sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder())).toList();
    }

    private List<String> tracks() {
        LuckPerms luckPerms = luckPerms();
        if (luckPerms == null) {
            return List.of();
        }
        return luckPerms.getTrackManager().getLoadedTracks().stream().map(Track::getName)
            .sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder())).toList();
    }

    private List<String> permissions() {
        TreeSet<String> permissions = new TreeSet<>(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));
        Bukkit.getPluginManager().getPermissions().stream().map(Permission::getName).forEach(permissions::add);
        LuckPerms luckPerms = luckPerms();
        if (luckPerms != null) {
            luckPerms.getGroupManager().getLoadedGroups().forEach(group -> group.getNodes().stream().filter(PermissionNode.class::isInstance).map(PermissionNode.class::cast)
                .map(PermissionNode::getPermission).forEach(permissions::add));
            luckPerms.getUserManager().getLoadedUsers().forEach(user -> user.getNodes().stream().filter(PermissionNode.class::isInstance).map(PermissionNode.class::cast)
                .map(PermissionNode::getPermission).forEach(permissions::add));
        }
        return List.copyOf(permissions);
    }

    private OptionCatalogItem groupItem(String name) {
        LuckPerms luckPerms = luckPerms();
        Group group = luckPerms != null ? luckPerms.getGroupManager().getGroup(name) : null;
        int directPermissions = group != null ? group.getNodes().size() : 0;
        String description = directPermissions + (directPermissions == 1 ? " Direct Permission" : " Direct Permissions");
        return new OptionCatalogItem(name, name, description, "group", "LuckPerms Group", Map.of(
            "provider", "LuckPerms",
            "directPermissions", directPermissions,
            "available", group != null
        ));
    }

    private OptionCatalogItem trackItem(String name) {
        LuckPerms luckPerms = luckPerms();
        Track track = luckPerms != null ? luckPerms.getTrackManager().getTrack(name) : null;
        int groups = track != null ? track.getGroups().size() : 0;
        String description = groups + (groups == 1 ? " Group" : " Groups");
        return new OptionCatalogItem(name, name, description, "track", "LuckPerms Track", Map.of(
            "provider", "LuckPerms",
            "groups", groups,
            "available", track != null
        ));
    }

    private OptionCatalogItem permissionItem(String name) {
        Permission permission = Bukkit.getPluginManager().getPermission(name);
        String description = permission == null || permission.getDescription().isBlank() ? "Permission Used By Installed Plugins" : permission.getDescription();
        return new OptionCatalogItem(name, name, description, "", permissionCategory(name), Map.of(
            "provider", permission == null ? "LuckPerms" : "Bukkit",
            "available", true
        ));
    }

    private String permissionCategory(String permission) {
        int separator = permission.indexOf('.');
        String namespace = (separator > 0 ? permission.substring(0, separator) : "Other").replace('_', ' ');
        return switch (namespace.toLowerCase(Locale.ROOT)) {
            case "bukkit" -> "Bukkit";
            case "minecraft" -> "Minecraft";
            case "resync" -> "ReSync";
            case "luckperms", "lp" -> "LuckPerms";
            case "other" -> "Other";
            default -> Character.toUpperCase(namespace.charAt(0)) + namespace.substring(1);
        };
    }

    private LuckPerms luckPerms() {
        RegisteredServiceProvider<LuckPerms> registration = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        return registration != null ? registration.getProvider() : null;
    }
}

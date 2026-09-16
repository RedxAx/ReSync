package restudio.resync.modules.flow;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.track.Track;
import net.luckperms.api.node.types.PermissionNode;
import org.bukkit.Bukkit;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.RegisteredServiceProvider;
import restudio.flow.data.FlowPermission;
import restudio.flow.data.FlowResourceReference;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.api.OptionCatalogItem;
import restudio.resync.api.OptionCatalogCapture;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.OptionCatalogQuery;
import restudio.resync.api.OptionCatalogRegistry;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

public final class LuckPermsOptionCatalogService {
    public static final String GROUP_SOURCE = "server:luckperms:group";
    public static final String TRACK_SOURCE = "server:luckperms:track";
    public static final String PERMISSION_SOURCE = "server:luckperms:permission";
    private static final Comparator<String> NAME_ORDER = String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder());

    public void registerProviders(OptionCatalogRegistry registry) {
        registry.register(provider(GROUP_SOURCE));
        registry.register(provider(TRACK_SOURCE));
        registry.register(provider(PERMISSION_SOURCE));
    }

    private OptionCatalogProvider provider(String sourceId) {
        return new OptionCatalogProvider() {
            @Override
            public String sourceId() {
                return sourceId;
            }

            @Override
            public CaptureAffinity captureAffinity() {
                return CaptureAffinity.SERVER_MAIN;
            }

            @Override
            public OptionCatalogCapture capture(OptionCatalogQuery query) {
                LuckPerms current = luckPerms();
                List<OptionCatalogItem> capturedItems = switch (sourceId) {
                    case GROUP_SOURCE -> groupItems(current);
                    case TRACK_SOURCE -> trackItems(current);
                    case PERMISSION_SOURCE -> permissionItems(current);
                    default -> List.of();
                };
                String status = current != null ? "available" : "unavailable";
                String diagnostic = current != null ? "" : "LuckPerms Service Is Unavailable";
                return new OptionCatalogCapture(sourceId + ":" + capturedItems.size() + ":" + capturedItems.hashCode(),
                    capturedItems, status, diagnostic);
            }

            @Override
            public String revision() {
                return capture(new OptionCatalogQuery(sourceId, Map.of())).revision();
            }

            @Override
            public List<String> values() {
                return items().stream().map(OptionCatalogItem::value).toList();
            }

            @Override
            public List<OptionCatalogItem> items() {
                return capture(new OptionCatalogQuery(sourceId, Map.of())).items();
            }

            @Override
            public FlowTypeRef runtimeDataType() {
                return LuckPermsOptionCatalogService.this.runtimeDataType(sourceId);
            }

            @Override
            public Class<?> runtimeDataClass() {
                return LuckPermsOptionCatalogService.this.runtimeDataClass(sourceId);
            }

            @Override
            public Object resolveRuntimeData(String value) {
                return LuckPermsOptionCatalogService.this.resolveRuntimeData(sourceId, value);
            }

            @Override
            public String status(OptionCatalogQuery query) {
                return capture(query).status();
            }

            @Override
            public String diagnostic(OptionCatalogQuery query) {
                return capture(query).diagnostic();
            }
        };
    }

    private FlowTypeRef runtimeDataType(String sourceId) {
        return FlowTypeRef.simple(switch (sourceId) {
            case GROUP_SOURCE -> "permission_group";
            case PERMISSION_SOURCE -> "permission";
            case TRACK_SOURCE -> "permission_track";
            default -> "string";
        });
    }

    private Class<?> runtimeDataClass(String sourceId) {
        return switch (sourceId) {
            case PERMISSION_SOURCE -> FlowPermission.class;
            case TRACK_SOURCE -> FlowResourceReference.class;
            default -> String.class;
        };
    }

    private Object resolveRuntimeData(String sourceId, String value) {
        return switch (sourceId) {
            case PERMISSION_SOURCE -> new FlowPermission(value);
            case TRACK_SOURCE -> trackReference(value);
            default -> value;
        };
    }

    private FlowResourceReference trackReference(String name) {
        LuckPerms luckPerms = luckPerms();
        Track track = luckPerms != null ? luckPerms.getTrackManager().getTrack(name) : null;
        int groups = track != null ? track.getGroups().size() : 0;
        return new FlowResourceReference("permission_track", name, "luckperms", track != null, Map.of(
            "provider", "LuckPerms",
            "groups", groups
        ));
    }

    private List<OptionCatalogItem> groupItems(LuckPerms luckPerms) {
        if (luckPerms == null) {
            return List.of();
        }
        return luckPerms.getGroupManager().getLoadedGroups().stream()
            .sorted(Comparator.comparing(Group::getName, NAME_ORDER))
            .map(this::groupItem)
            .toList();
    }

    private List<OptionCatalogItem> trackItems(LuckPerms luckPerms) {
        if (luckPerms == null) {
            return List.of();
        }
        return luckPerms.getTrackManager().getLoadedTracks().stream()
            .sorted(Comparator.comparing(Track::getName, NAME_ORDER))
            .map(this::trackItem)
            .toList();
    }

    private List<OptionCatalogItem> permissionItems(LuckPerms luckPerms) {
        TreeSet<String> permissions = new TreeSet<>(NAME_ORDER);
        Map<String, Permission> definitions = new TreeMap<>(NAME_ORDER);
        Bukkit.getPluginManager().getPermissions().forEach(permission -> {
            permissions.add(permission.getName());
            definitions.put(permission.getName(), permission);
        });
        if (luckPerms != null) {
            luckPerms.getGroupManager().getLoadedGroups().forEach(group -> group.getNodes().stream().filter(PermissionNode.class::isInstance).map(PermissionNode.class::cast)
                .map(PermissionNode::getPermission).forEach(permissions::add));
            luckPerms.getUserManager().getLoadedUsers().forEach(user -> user.getNodes().stream().filter(PermissionNode.class::isInstance).map(PermissionNode.class::cast)
                .map(PermissionNode::getPermission).forEach(permissions::add));
        }
        return permissions.stream().map(name -> permissionItem(name, definitions.get(name))).toList();
    }

    private OptionCatalogItem groupItem(Group group) {
        String name = group.getName();
        int directPermissions = group.getNodes().size();
        String description = directPermissions + (directPermissions == 1 ? " Direct Permission" : " Direct Permissions");
        return new OptionCatalogItem(name, name, description, "group", "LuckPerms Group", Map.of(
            "provider", "LuckPerms",
            "directPermissions", directPermissions,
            "available", true
        ));
    }

    private OptionCatalogItem trackItem(Track track) {
        String name = track.getName();
        int groups = track.getGroups().size();
        String description = groups + (groups == 1 ? " Group" : " Groups");
        return new OptionCatalogItem(name, name, description, "track", "LuckPerms Track", Map.of(
            "provider", "LuckPerms",
            "groups", groups,
            "available", true
        ));
    }

    private OptionCatalogItem permissionItem(String name, Permission permission) {
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

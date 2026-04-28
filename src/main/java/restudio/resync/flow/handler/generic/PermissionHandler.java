package restudio.resync.flow.handler.generic;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.MetaNode;
import net.luckperms.api.node.types.PermissionNode;
import net.luckperms.api.node.types.PrefixNode;
import net.luckperms.api.node.types.SuffixNode;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

public class PermissionHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public PermissionHandler() {
        operations.put("perm_has", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "LuckPerms not available");
                ctx.setOutput(node, "has", false);
                return;
            }
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String permission = ctx.getInputValue(node, "permission", String.class, "");
            if (player == null || permission.isEmpty()) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", player == null ? "Player is null" : "Permission is empty");
                ctx.setOutput(node, "has", false);
                return;
            }
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "LuckPerms service not available");
                ctx.setOutput(node, "has", false);
                return;
            }
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "User not found");
                ctx.setOutput(node, "has", false);
                return;
            }
            QueryOptions queryOptions = getQueryOptions(lp, user);
            boolean has = user.getCachedData().getPermissionData(queryOptions)
                    .checkPermission(permission).asBoolean();
            ctx.setOutput(node, "success", true);
            ctx.setOutput(node, "has", has);
        });

        operations.put("perm_add", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "LuckPerms not available");
                return;
            }
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String permission = ctx.getInputValue(node, "permission", String.class, "");
            if (player == null || permission.isEmpty()) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", player == null ? "Player is null" : "Permission is empty");
                return;
            }
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "LuckPerms service not available");
                return;
            }
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "User not found");
                return;
            }
            Node permNode = PermissionNode.builder(permission).build();
            user.data().add(permNode);
            lp.getUserManager().saveUser(user);
            ctx.setOutput(node, "success", true);
        });

        operations.put("perm_remove", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "LuckPerms not available");
                return;
            }
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String permission = ctx.getInputValue(node, "permission", String.class, "");
            if (player == null || permission.isEmpty()) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", player == null ? "Player is null" : "Permission is empty");
                return;
            }
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "LuckPerms service not available");
                return;
            }
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "User not found");
                return;
            }
            Node permNode = PermissionNode.builder(permission).build();
            user.data().remove(permNode);
            lp.getUserManager().saveUser(user);
            ctx.setOutput(node, "success", true);
        });

        operations.put("perm_get_groups", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "LuckPerms not available");
                ctx.setOutput(node, "groups", new ArrayList<>());
                return;
            }
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Player is null");
                ctx.setOutput(node, "groups", new ArrayList<>());
                return;
            }
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "LuckPerms service not available");
                ctx.setOutput(node, "groups", new ArrayList<>());
                return;
            }
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "User not found");
                ctx.setOutput(node, "groups", new ArrayList<>());
                return;
            }
            QueryOptions queryOptions = getQueryOptions(lp, user);
            List<String> groups = new ArrayList<>();
            user.getInheritedGroups(queryOptions).forEach(g -> groups.add(g.getName()));
            ctx.setOutput(node, "success", true);
            ctx.setOutput(node, "groups", groups);
        });

        operations.put("perm_in_group", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "LuckPerms not available");
                ctx.setOutput(node, "has", false);
                return;
            }
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String group = ctx.getInputValue(node, "group", String.class, "");
            if (player == null || group.isEmpty()) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", player == null ? "Player is null" : "Group is empty");
                ctx.setOutput(node, "has", false);
                return;
            }
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "LuckPerms service not available");
                ctx.setOutput(node, "has", false);
                return;
            }
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "User not found");
                ctx.setOutput(node, "has", false);
                return;
            }
            QueryOptions queryOptions = getQueryOptions(lp, user);
            boolean hasGroup = user.getInheritedGroups(queryOptions).stream()
                    .anyMatch(g -> g.getName().equalsIgnoreCase(group));
            ctx.setOutput(node, "success", true);
            ctx.setOutput(node, "has", hasGroup);
        });

        operations.put("perm_set_group", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "LuckPerms not available");
                return;
            }
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String group = ctx.getInputValue(node, "group", String.class, "");
            if (player == null || group.isEmpty()) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", player == null ? "Player is null" : "Group is empty");
                return;
            }
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "LuckPerms service not available");
                return;
            }
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "User not found");
                return;
            }
            Group groupObj = lp.getGroupManager().getGroup(group);
            if (groupObj == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Group not found");
                return;
            }
            user.setPrimaryGroup(group);
            lp.getUserManager().saveUser(user);
            ctx.setOutput(node, "success", true);
        });

        operations.put("perm_add_temp", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "LuckPerms not available");
                return;
            }
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String permission = ctx.getInputValue(node, "permission", String.class, "");
            Integer duration = ctx.getInputValue(node, "duration", Integer.class, 0);
            if (player == null || permission.isEmpty() || duration <= 0) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", player == null ? "Player is null" : permission.isEmpty() ? "Permission is empty" : "Duration must be positive");
                return;
            }
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "LuckPerms service not available");
                return;
            }
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "User not found");
                return;
            }
            Node permNode = PermissionNode.builder(permission).expiry(duration, TimeUnit.SECONDS).build();
            user.data().add(permNode);
            lp.getUserManager().saveUser(user);
            ctx.setOutput(node, "success", true);
        });

        operations.put("perm_get_prefix", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "LuckPerms not available");
                ctx.setOutput(node, "prefix", "");
                return;
            }
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Player is null");
                ctx.setOutput(node, "prefix", "");
                return;
            }
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "LuckPerms service not available");
                ctx.setOutput(node, "prefix", "");
                return;
            }
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "User not found");
                ctx.setOutput(node, "prefix", "");
                return;
            }
            String prefix = user.getCachedData().getMetaData().getPrefix();
            ctx.setOutput(node, "success", true);
            ctx.setOutput(node, "prefix", prefix != null ? prefix : "");
        });

        operations.put("perm_get_suffix", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "LuckPerms not available");
                ctx.setOutput(node, "suffix", "");
                return;
            }
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Player is null");
                ctx.setOutput(node, "suffix", "");
                return;
            }
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "LuckPerms service not available");
                ctx.setOutput(node, "suffix", "");
                return;
            }
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "User not found");
                ctx.setOutput(node, "suffix", "");
                return;
            }
            String suffix = user.getCachedData().getMetaData().getSuffix();
            ctx.setOutput(node, "success", true);
            ctx.setOutput(node, "suffix", suffix != null ? suffix : "");
        });

        operations.put("perm_get_meta", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "LuckPerms not available");
                ctx.setOutput(node, "value", "");
                return;
            }
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String key = ctx.getInputValue(node, "key", String.class, "");
            if (player == null || key.isEmpty()) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", player == null ? "Player is null" : "Key is empty");
                ctx.setOutput(node, "value", "");
                return;
            }
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "LuckPerms service not available");
                ctx.setOutput(node, "value", "");
                return;
            }
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "User not found");
                ctx.setOutput(node, "value", "");
                return;
            }
            String value = user.getCachedData().getMetaData().getMetaValue(key);
            ctx.setOutput(node, "success", true);
            ctx.setOutput(node, "value", value != null ? value : "");
        });

        operations.put("perm_set_meta", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "LuckPerms not available");
                return;
            }
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String key = ctx.getInputValue(node, "key", String.class, "");
            String value = ctx.getInputValue(node, "value", String.class, "");
            if (player == null || key.isEmpty()) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", player == null ? "Player is null" : "Key is empty");
                return;
            }
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "LuckPerms service not available");
                return;
            }
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "User not found");
                return;
            }
            Node metaNode = MetaNode.builder(key, value).build();
            user.data().add(metaNode);
            lp.getUserManager().saveUser(user);
            ctx.setOutput(node, "success", true);
        });

        operations.put("perm_remove_meta", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "LuckPerms not available");
                return;
            }
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String key = ctx.getInputValue(node, "key", String.class, "");
            if (player == null || key.isEmpty()) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", player == null ? "Player is null" : "Key is empty");
                return;
            }
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "LuckPerms service not available");
                return;
            }
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "User not found");
                return;
            }
            List<Node> toRemove = new ArrayList<>();
            for (Node n : user.getNodes()) {
                if (n instanceof MetaNode && ((MetaNode) n).getMetaKey().equals(key)) {
                    toRemove.add(n);
                }
            }
            for (Node n : toRemove) {
                user.data().remove(n);
            }
            lp.getUserManager().saveUser(user);
            ctx.setOutput(node, "success", true);
        });

        operations.put("perm_get_all_perms", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "LuckPerms not available");
                ctx.setOutput(node, "permissions", new ArrayList<>());
                return;
            }
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Player is null");
                ctx.setOutput(node, "permissions", new ArrayList<>());
                return;
            }
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "LuckPerms service not available");
                ctx.setOutput(node, "permissions", new ArrayList<>());
                return;
            }
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "User not found");
                ctx.setOutput(node, "permissions", new ArrayList<>());
                return;
            }
            QueryOptions queryOptions = getQueryOptions(lp, user);
            List<String> permissions = new ArrayList<>();
            user.getCachedData().getPermissionData(queryOptions)
                    .getPermissionMap()
                    .forEach((key, value) -> {
                        if (Boolean.TRUE.equals(value)) {
                            permissions.add(key);
                        }
                    });
            ctx.setOutput(node, "success", true);
            ctx.setOutput(node, "permissions", permissions);
        });

        operations.put("perm_check_inheritance", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "LuckPerms not available");
                ctx.setOutput(node, "inherits", false);
                return;
            }
            String group = ctx.getInputValue(node, "group", String.class, "");
            String parent = ctx.getInputValue(node, "parent_group", String.class, "");
            if (group.isEmpty() || parent.isEmpty()) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", group.isEmpty() ? "Group is empty" : "Parent group is empty");
                ctx.setOutput(node, "inherits", false);
                return;
            }
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "LuckPerms service not available");
                ctx.setOutput(node, "inherits", false);
                return;
            }
            Group groupObj = lp.getGroupManager().getGroup(group);
            if (groupObj == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Group not found");
                ctx.setOutput(node, "inherits", false);
                return;
            }
            QueryOptions queryOptions = getStaticQueryOptions(lp);
            boolean inherits = groupObj.getCachedData().getPermissionData(queryOptions)
                    .checkPermission("group." + parent).asBoolean();
            ctx.setOutput(node, "success", true);
            ctx.setOutput(node, "inherits", inherits);
        });

        operations.put("perm_add_group", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
                ctx.setOutput(node, "success", false);
                return;
            }
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String group = ctx.getInputValue(node, "group", String.class, "");
            if (player == null || group.isEmpty()) {
                ctx.setOutput(node, "success", false);
                return;
            }
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setOutput(node, "success", false);
                return;
            }
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setOutput(node, "success", false);
                return;
            }
            Group groupObj = lp.getGroupManager().getGroup(group);
            if (groupObj == null) {
                ctx.setOutput(node, "success", false);
                return;
            }
            user.data().add(PermissionNode.builder("group." + group).build());
            lp.getUserManager().saveUser(user);
            ctx.setOutput(node, "success", true);
        });

        operations.put("perm_remove_group", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
                ctx.setOutput(node, "success", false);
                return;
            }
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String group = ctx.getInputValue(node, "group", String.class, "");
            if (player == null || group.isEmpty()) {
                ctx.setOutput(node, "success", false);
                return;
            }
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setOutput(node, "success", false);
                return;
            }
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setOutput(node, "success", false);
                return;
            }
            user.data().remove(PermissionNode.builder("group." + group).build());
            lp.getUserManager().saveUser(user);
            ctx.setOutput(node, "success", true);
        });

        operations.put("perm_has_group", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
                ctx.setOutput(node, "has", false);
                return;
            }
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String group = ctx.getInputValue(node, "group", String.class, "");
            if (player == null || group.isEmpty()) {
                ctx.setOutput(node, "has", false);
                return;
            }
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setOutput(node, "has", false);
                return;
            }
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setOutput(node, "has", false);
                return;
            }
            QueryOptions queryOptions = getQueryOptions(lp, user);
            boolean has = user.getInheritedGroups(queryOptions).stream()
                    .anyMatch(g -> g.getName().equalsIgnoreCase(group));
            ctx.setOutput(node, "has", has);
        });

        operations.put("perm_get_permissions", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
                ctx.setOutput(node, "permissions", new ArrayList<>());
                return;
            }
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                ctx.setOutput(node, "permissions", new ArrayList<>());
                return;
            }
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setOutput(node, "permissions", new ArrayList<>());
                return;
            }
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setOutput(node, "permissions", new ArrayList<>());
                return;
            }
            List<String> perms = new ArrayList<>();
            user.getNodes().forEach(n -> {
                if (n instanceof PermissionNode) {
                    perms.add(((PermissionNode) n).getPermission());
                }
            });
            ctx.setOutput(node, "permissions", perms);
        });

        operations.put("perm_get_primary_group", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
                ctx.setOutput(node, "group", "");
                return;
            }
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                ctx.setOutput(node, "group", "");
                return;
            }
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setOutput(node, "group", "");
                return;
            }
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setOutput(node, "group", "");
                return;
            }
            ctx.setOutput(node, "group", user.getPrimaryGroup());
        });

        operations.put("perm_get_all_groups", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
                ctx.setOutput(node, "groups", new ArrayList<>());
                return;
            }
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setOutput(node, "groups", new ArrayList<>());
                return;
            }
            List<String> groups = new ArrayList<>();
            lp.getGroupManager().getLoadedGroups().forEach(g -> groups.add(g.getName()));
            ctx.setOutput(node, "groups", groups);
        });

        operations.put("perm_group_add_permission", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
                ctx.setOutput(node, "success", false);
                return;
            }
            String group = ctx.getInputValue(node, "group", String.class, "");
            String permission = ctx.getInputValue(node, "permission", String.class, "");
            if (group.isEmpty() || permission.isEmpty()) {
                ctx.setOutput(node, "success", false);
                return;
            }
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setOutput(node, "success", false);
                return;
            }
            Group groupObj = lp.getGroupManager().getGroup(group);
            if (groupObj == null) {
                ctx.setOutput(node, "success", false);
                return;
            }
            groupObj.data().add(PermissionNode.builder(permission).build());
            lp.getGroupManager().saveGroup(groupObj);
            ctx.setOutput(node, "success", true);
        });

        operations.put("perm_group_has_permission", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
                ctx.setOutput(node, "has", false);
                return;
            }
            String group = ctx.getInputValue(node, "group", String.class, "");
            String permission = ctx.getInputValue(node, "permission", String.class, "");
            if (group.isEmpty() || permission.isEmpty()) {
                ctx.setOutput(node, "has", false);
                return;
            }
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setOutput(node, "has", false);
                return;
            }
            Group groupObj = lp.getGroupManager().getGroup(group);
            if (groupObj == null) {
                ctx.setOutput(node, "has", false);
                return;
            }
            QueryOptions queryOptions = getStaticQueryOptions(lp);
            boolean has = groupObj.getCachedData().getPermissionData(queryOptions)
                    .checkPermission(permission).asBoolean();
            ctx.setOutput(node, "has", has);
        });

        operations.put("perm_group_remove_permission", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
                ctx.setOutput(node, "success", false);
                return;
            }
            String group = ctx.getInputValue(node, "group", String.class, "");
            String permission = ctx.getInputValue(node, "permission", String.class, "");
            if (group.isEmpty() || permission.isEmpty()) {
                ctx.setOutput(node, "success", false);
                return;
            }
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setOutput(node, "success", false);
                return;
            }
            Group groupObj = lp.getGroupManager().getGroup(group);
            if (groupObj == null) {
                ctx.setOutput(node, "success", false);
                return;
            }
            groupObj.data().remove(PermissionNode.builder(permission).build());
            lp.getGroupManager().saveGroup(groupObj);
            ctx.setOutput(node, "success", true);
        });

        operations.put("perm_get_group_permissions", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
                ctx.setOutput(node, "permissions", new ArrayList<>());
                return;
            }
            String group = ctx.getInputValue(node, "group", String.class, "");
            if (group.isEmpty()) {
                ctx.setOutput(node, "permissions", new ArrayList<>());
                return;
            }
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setOutput(node, "permissions", new ArrayList<>());
                return;
            }
            Group groupObj = lp.getGroupManager().getGroup(group);
            if (groupObj == null) {
                ctx.setOutput(node, "permissions", new ArrayList<>());
                return;
            }
            List<String> perms = new ArrayList<>();
            groupObj.getNodes().forEach(n -> {
                if (n instanceof PermissionNode) {
                    perms.add(((PermissionNode) n).getPermission());
                }
            });
            ctx.setOutput(node, "permissions", perms);
        });

        operations.put("perm_set_prefix", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
                ctx.setOutput(node, "success", false);
                return;
            }
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String prefix = ctx.getInputValue(node, "prefix", String.class, "");
            if (player == null) {
                ctx.setOutput(node, "success", false);
                return;
            }
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setOutput(node, "success", false);
                return;
            }
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setOutput(node, "success", false);
                return;
            }
            user.data().add(PrefixNode.builder(prefix, 100).build());
            lp.getUserManager().saveUser(user);
            ctx.setOutput(node, "success", true);
        });

        operations.put("perm_set_suffix", (ctx, node) -> {
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
                ctx.setOutput(node, "success", false);
                return;
            }
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String suffix = ctx.getInputValue(node, "suffix", String.class, "");
            if (player == null) {
                ctx.setOutput(node, "success", false);
                return;
            }
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setOutput(node, "success", false);
                return;
            }
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setOutput(node, "success", false);
                return;
            }
            user.data().add(SuffixNode.builder(suffix, 100).build());
            lp.getUserManager().saveUser(user);
            ctx.setOutput(node, "success", true);
        });
    }

    private LuckPerms getLuckPerms() {
        RegisteredServiceProvider<LuckPerms> registration = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        return registration != null ? registration.getProvider() : null;
    }

    private QueryOptions getQueryOptions(LuckPerms lp, User user) {
        return lp.getContextManager().getQueryOptions(user)
                .orElse(lp.getContextManager().getStaticQueryOptions());
    }

    private QueryOptions getStaticQueryOptions(LuckPerms lp) {
        return lp.getContextManager().getStaticQueryOptions();
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("PermissionHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op != null) {
            op.accept(ctx, node);
        }
        ctx.triggerOutput("flow");
    }
}

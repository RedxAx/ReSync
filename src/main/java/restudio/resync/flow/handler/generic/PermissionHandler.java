package restudio.resync.flow.handler.generic;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.MetaNode;
import net.luckperms.api.node.types.PermissionNode;
import net.luckperms.api.node.types.PrefixNode;
import net.luckperms.api.node.types.SuffixNode;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.query.QueryMode;
import net.luckperms.api.track.Track;
import net.luckperms.api.util.Result;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

public class PermissionHandler implements NodeHandler {
    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();

    public PermissionHandler() {
        operations.put("perm_has", (ctx, node) -> {
            ctx.setOutput(node, "resolved_context", Map.of());
            if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "LuckPerms not available");
                ctx.setOutput(node, "has", false);
                ctx.setOutput(node, "count", 0);
                return;
            }
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            List<String> permissions = resolvePermissions(ctx, node);
            if (player == null || permissions.isEmpty()) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", player == null ? "Player is null" : "Permission is empty");
                ctx.setOutput(node, "has", false);
                ctx.setOutput(node, "count", 0);
                return;
            }
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "LuckPerms service not available");
                ctx.setOutput(node, "has", false);
                ctx.setOutput(node, "count", 0);
                return;
            }
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "User not found");
                ctx.setOutput(node, "has", false);
                ctx.setOutput(node, "count", 0);
                return;
            }
            QueryOptions queryOptions = getQueryOptions(lp, user, ctx.getInputValue(node, "context"));
            var permissionData = user.getCachedData().getPermissionData(queryOptions);
            long count = permissions.stream().filter(permission -> permissionData.checkPermission(permission).asBoolean()).count();
            String mode = ctx.getInputValue(node, "mode", String.class, "Find Any");
            boolean has = matchesMode(count, permissions.size(), mode);
            ctx.setOutput(node, "success", true);
            ctx.setOutput(node, "error", "");
            ctx.setOutput(node, "has", has);
            ctx.setOutput(node, "count", Math.toIntExact(count));
            ctx.setOutput(node, "resolved_context", queryOptions.context().toMap());
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
            persistUser(ctx, lp, user);
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
            persistUser(ctx, lp, user);
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
            Result result = user.setPrimaryGroup(group);
            if (!result.wasSuccessful()) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Primary group change was rejected");
                return;
            }
            persistUser(ctx, lp, user);
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
            persistUser(ctx, lp, user);
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
            user.getNodes().stream().filter(MetaNode.class::isInstance).map(MetaNode.class::cast)
                    .filter(existing -> existing.getMetaKey().equalsIgnoreCase(key)).toList().forEach(existing -> user.data().remove(existing));
            Node metaNode = MetaNode.builder(key, value).build();
            user.data().add(metaNode);
            persistUser(ctx, lp, user);
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
            persistUser(ctx, lp, user);
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
            if (lp.getGroupManager().getGroup(parent) == null) {
                ctx.setOutput(node, "success", false);
                ctx.setOutput(node, "error", "Parent group not found");
                ctx.setOutput(node, "inherits", false);
                return;
            }
            QueryOptions queryOptions = getStaticQueryOptions(lp);
            boolean inherits = groupObj.getInheritedGroups(queryOptions).stream().anyMatch(candidate -> candidate.getName().equalsIgnoreCase(parent));
            ctx.setOutput(node, "success", true);
            ctx.setOutput(node, "inherits", inherits);
        });

        operations.put("perm_add_group", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String group = ctx.getInputValue(node, "group", String.class, "").trim();
            if (player == null || group.isBlank()) {
                fail(ctx, node, player == null ? "Player is null" : "Group is empty");
                return;
            }
            LuckPerms lp = requireLuckPerms(ctx, node);
            if (lp == null) {
                return;
            }
            User user = requireUser(ctx, node, lp, player);
            if (user == null) {
                return;
            }
            Group groupObj = lp.getGroupManager().getGroup(group);
            if (groupObj == null) {
                fail(ctx, node, "Group not found");
                return;
            }
            user.data().add(InheritanceNode.builder(groupObj).build());
            persistUser(ctx, lp, user);
            succeed(ctx, node);
        });

        operations.put("perm_remove_group", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String group = ctx.getInputValue(node, "group", String.class, "").trim();
            if (player == null || group.isBlank()) {
                fail(ctx, node, player == null ? "Player is null" : "Group is empty");
                return;
            }
            LuckPerms lp = requireLuckPerms(ctx, node);
            if (lp == null) {
                return;
            }
            User user = requireUser(ctx, node, lp, player);
            if (user == null) {
                return;
            }
            user.data().remove(InheritanceNode.builder(group).build());
            persistUser(ctx, lp, user);
            succeed(ctx, node);
        });

        operations.put("perm_has_group", (ctx, node) -> {
            ctx.setOutput(node, "has", false);
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String group = ctx.getInputValue(node, "group", String.class, "").trim();
            if (player == null || group.isBlank()) {
                fail(ctx, node, player == null ? "Player is null" : "Group is empty");
                return;
            }
            LuckPerms lp = requireLuckPerms(ctx, node);
            if (lp == null) {
                return;
            }
            User user = requireUser(ctx, node, lp, player);
            if (user == null) {
                return;
            }
            QueryOptions queryOptions = getQueryOptions(lp, user);
            boolean has = user.getInheritedGroups(queryOptions).stream()
                    .anyMatch(g -> g.getName().equalsIgnoreCase(group));
            ctx.setOutput(node, "has", has);
            succeed(ctx, node);
        });

        operations.put("perm_get_permissions", (ctx, node) -> {
            ctx.setOutput(node, "permissions", List.of());
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                fail(ctx, node, "Player is null");
                return;
            }
            LuckPerms lp = requireLuckPerms(ctx, node);
            if (lp == null) {
                return;
            }
            User user = requireUser(ctx, node, lp, player);
            if (user == null) {
                return;
            }
            List<String> perms = user.getNodes().stream().filter(PermissionNode.class::isInstance).map(PermissionNode.class::cast)
                    .map(PermissionNode::getPermission).distinct().sorted().toList();
            ctx.setOutput(node, "permissions", perms);
            succeed(ctx, node);
        });

        operations.put("perm_get_primary_group", (ctx, node) -> {
            ctx.setOutput(node, "group", "");
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            if (player == null) {
                fail(ctx, node, "Player is null");
                return;
            }
            LuckPerms lp = requireLuckPerms(ctx, node);
            if (lp == null) {
                return;
            }
            User user = requireUser(ctx, node, lp, player);
            if (user == null) {
                return;
            }
            ctx.setOutput(node, "group", user.getPrimaryGroup());
            succeed(ctx, node);
        });

        operations.put("perm_get_all_groups", (ctx, node) -> {
            ctx.setOutput(node, "groups", List.of());
            LuckPerms lp = requireLuckPerms(ctx, node);
            if (lp == null) {
                return;
            }
            List<String> groups = lp.getGroupManager().getLoadedGroups().stream().map(Group::getName).sorted().toList();
            ctx.setOutput(node, "groups", groups);
            succeed(ctx, node);
        });

        operations.put("perm_group_add_permission", (ctx, node) -> {
            String group = ctx.getInputValue(node, "group", String.class, "").trim();
            String permission = ctx.getInputValue(node, "permission", String.class, "").trim();
            if (group.isBlank() || permission.isBlank()) {
                fail(ctx, node, group.isBlank() ? "Group is empty" : "Permission is empty");
                return;
            }
            LuckPerms lp = requireLuckPerms(ctx, node);
            if (lp == null) {
                return;
            }
            Group groupObj = lp.getGroupManager().getGroup(group);
            if (groupObj == null) {
                fail(ctx, node, "Group not found");
                return;
            }
            groupObj.data().add(PermissionNode.builder(permission).build());
            persistGroup(ctx, lp, groupObj);
            succeed(ctx, node);
        });

        operations.put("perm_group_has_permission", (ctx, node) -> {
            ctx.setOutput(node, "has", false);
            String group = ctx.getInputValue(node, "group", String.class, "").trim();
            String permission = ctx.getInputValue(node, "permission", String.class, "").trim();
            if (group.isBlank() || permission.isBlank()) {
                fail(ctx, node, group.isBlank() ? "Group is empty" : "Permission is empty");
                return;
            }
            LuckPerms lp = requireLuckPerms(ctx, node);
            if (lp == null) {
                return;
            }
            Group groupObj = lp.getGroupManager().getGroup(group);
            if (groupObj == null) {
                fail(ctx, node, "Group not found");
                return;
            }
            QueryOptions queryOptions = getStaticQueryOptions(lp);
            boolean has = groupObj.getCachedData().getPermissionData(queryOptions)
                    .checkPermission(permission).asBoolean();
            ctx.setOutput(node, "has", has);
            succeed(ctx, node);
        });

        operations.put("perm_group_remove_permission", (ctx, node) -> {
            String group = ctx.getInputValue(node, "group", String.class, "").trim();
            String permission = ctx.getInputValue(node, "permission", String.class, "").trim();
            if (group.isBlank() || permission.isBlank()) {
                fail(ctx, node, group.isBlank() ? "Group is empty" : "Permission is empty");
                return;
            }
            LuckPerms lp = requireLuckPerms(ctx, node);
            if (lp == null) {
                return;
            }
            Group groupObj = lp.getGroupManager().getGroup(group);
            if (groupObj == null) {
                fail(ctx, node, "Group not found");
                return;
            }
            groupObj.data().remove(PermissionNode.builder(permission).build());
            persistGroup(ctx, lp, groupObj);
            succeed(ctx, node);
        });

        operations.put("perm_get_group_permissions", (ctx, node) -> {
            ctx.setOutput(node, "permissions", List.of());
            String group = ctx.getInputValue(node, "group", String.class, "").trim();
            if (group.isBlank()) {
                fail(ctx, node, "Group is empty");
                return;
            }
            LuckPerms lp = requireLuckPerms(ctx, node);
            if (lp == null) {
                return;
            }
            Group groupObj = lp.getGroupManager().getGroup(group);
            if (groupObj == null) {
                fail(ctx, node, "Group not found");
                return;
            }
            List<String> perms = groupObj.getNodes().stream().filter(PermissionNode.class::isInstance).map(PermissionNode.class::cast)
                    .map(PermissionNode::getPermission).distinct().sorted().toList();
            ctx.setOutput(node, "permissions", perms);
            succeed(ctx, node);
        });

        operations.put("perm_set_prefix", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String prefix = ctx.getInputValue(node, "prefix", String.class, "");
            if (player == null) {
                fail(ctx, node, "Player is null");
                return;
            }
            LuckPerms lp = requireLuckPerms(ctx, node);
            if (lp == null) {
                return;
            }
            User user = requireUser(ctx, node, lp, player);
            if (user == null) {
                return;
            }
            user.getNodes().stream().filter(PrefixNode.class::isInstance).toList().forEach(existing -> user.data().remove(existing));
            if (!prefix.isEmpty()) {
                user.data().add(PrefixNode.builder(prefix, 100).build());
            }
            persistUser(ctx, lp, user);
            succeed(ctx, node);
        });

        operations.put("perm_set_suffix", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String suffix = ctx.getInputValue(node, "suffix", String.class, "");
            if (player == null) {
                fail(ctx, node, "Player is null");
                return;
            }
            LuckPerms lp = requireLuckPerms(ctx, node);
            if (lp == null) {
                return;
            }
            User user = requireUser(ctx, node, lp, player);
            if (user == null) {
                return;
            }
            user.getNodes().stream().filter(SuffixNode.class::isInstance).toList().forEach(existing -> user.data().remove(existing));
            if (!suffix.isEmpty()) {
                user.data().add(SuffixNode.builder(suffix, 100).build());
            }
            persistUser(ctx, lp, user);
            succeed(ctx, node);
        });

        operations.put("perm_list_tracks", (ctx, node) -> {
            ctx.setOutput(node, "tracks", List.of());
            LuckPerms lp = requireLuckPerms(ctx, node);
            if (lp == null) {
                return;
            }
            List<String> tracks = lp.getTrackManager().getLoadedTracks().stream().map(Track::getName).sorted().toList();
            ctx.setOutput(node, "tracks", tracks);
            succeed(ctx, node);
        });

        operations.put("perm_promote", (ctx, node) -> {
            mutateTrackPosition(ctx, node, true);
        });

        operations.put("perm_demote", (ctx, node) -> {
            mutateTrackPosition(ctx, node, false);
        });

    }

    private List<String> resolvePermissions(FlowContext ctx, FlowNode node) {
        return ctx.getRepeatableInputValues(node, "permission", String.class).stream().map(String::trim).filter(value -> !value.isBlank()).toList();
    }

    static boolean matchesMode(long matching, int total, String mode) {
        return switch (mode != null ? mode.toLowerCase(Locale.ROOT) : "find any") {
            case "find all", "all" -> matching == total;
            case "find none", "none" -> matching == 0;
            case "find any", "any", "count" -> matching > 0;
            default -> throw new IllegalArgumentException("Unknown permission match mode: " + mode);
        };
    }

    static Map<String, List<String>> normalizePermissionContext(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> input)) {
            throw new IllegalArgumentException("Permission context must be a map");
        }
        Map<String, List<String>> output = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            String key = entry.getKey() != null ? String.valueOf(entry.getKey()).trim().toLowerCase(Locale.ROOT) : "";
            if (key.isBlank()) {
                throw new IllegalArgumentException("Permission context keys cannot be blank");
            }
            Object rawValue = entry.getValue();
            Collection<?> values = rawValue instanceof Collection<?> collection ? collection : rawValue != null ? List.of(rawValue) : List.of();
            LinkedHashSet<String> normalizedValues = new LinkedHashSet<>();
            for (Object raw : values) {
                String normalized = raw != null ? String.valueOf(raw).trim().toLowerCase(Locale.ROOT) : "";
                if (normalized.isBlank()) {
                    throw new IllegalArgumentException("Permission context values cannot be blank");
                }
                normalizedValues.add(normalized);
            }
            if (normalizedValues.isEmpty()) {
                throw new IllegalArgumentException("Permission context values cannot be empty");
            }
            output.put(key, List.copyOf(normalizedValues));
        }
        return Map.copyOf(output);
    }

    private void mutateTrackPosition(FlowContext ctx, FlowNode node, boolean promote) {
        LuckPerms lp = getLuckPerms();
        Player player = ctx.getInputValue(node, "player", Player.class, null);
        String trackName = ctx.getInputValue(node, "track", String.class, "");
        if (lp == null || player == null || trackName.isBlank()) {
            ctx.setOutput(node, "success", false);
            ctx.setOutput(node, "result", lp == null ? "LuckPerms Missing" : player == null ? "Player Missing" : "Track Missing");
            ctx.triggerOutput("failure_flow");
            return;
        }
        User user = lp.getUserManager().getUser(player.getUniqueId());
        Track track = lp.getTrackManager().getTrack(trackName);
        if (user == null || track == null) {
            ctx.setOutput(node, "success", false);
            ctx.setOutput(node, "result", user == null ? "User Missing" : "Track Missing");
            ctx.triggerOutput("failure_flow");
            return;
        }
        Result result = promote ? track.promote(user, getQueryOptions(lp, user).context()) : track.demote(user, getQueryOptions(lp, user).context());
        ctx.setOutput(node, "result", result.toString());
        if (!result.wasSuccessful()) {
            ctx.setOutput(node, "success", false);
            ctx.triggerOutput("failure_flow");
            return;
        }
        persistUser(ctx, lp, user);
        ctx.setOutput(node, "success", true);
        ctx.triggerOutput("success_flow");
    }

    private LuckPerms getLuckPerms() {
        RegisteredServiceProvider<LuckPerms> registration = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        return registration != null ? registration.getProvider() : null;
    }

    private LuckPerms requireLuckPerms(FlowContext ctx, FlowNode node) {
        LuckPerms luckPerms = getLuckPerms();
        if (luckPerms == null) {
            fail(ctx, node, "LuckPerms service not available");
        }
        return luckPerms;
    }

    private User requireUser(FlowContext ctx, FlowNode node, LuckPerms luckPerms, Player player) {
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            fail(ctx, node, "User not found");
        }
        return user;
    }

    private void succeed(FlowContext ctx, FlowNode node) {
        ctx.setOutput(node, "success", true);
        ctx.setOutput(node, "error", "");
    }

    private void fail(FlowContext ctx, FlowNode node, String error) {
        ctx.setOutput(node, "success", false);
        ctx.setOutput(node, "error", error);
    }

    private void persistUser(FlowContext ctx, LuckPerms luckPerms, User user) {
        ctx.runAsyncBeforeContinuation(() -> luckPerms.getUserManager().saveUser(user).join());
    }

    private void persistGroup(FlowContext ctx, LuckPerms luckPerms, Group group) {
        ctx.runAsyncBeforeContinuation(() -> luckPerms.getGroupManager().saveGroup(group).join());
    }

    private QueryOptions getQueryOptions(LuckPerms lp, User user) {
        return lp.getContextManager().getQueryOptions(user)
                .orElse(lp.getContextManager().getStaticQueryOptions());
    }

    private QueryOptions getQueryOptions(LuckPerms lp, User user, Object explicitContext) {
        QueryOptions base = getQueryOptions(lp, user);
        Map<String, List<String>> values = normalizePermissionContext(explicitContext);
        if (values.isEmpty()) {
            return base;
        }
        ImmutableContextSet.Builder builder = ImmutableContextSet.builder();
        if (base.mode() == QueryMode.CONTEXTUAL) {
            builder.addAll(base.context());
        }
        values.forEach((key, entries) -> entries.forEach(value -> builder.add(key, value)));
        ImmutableContextSet context = builder.build();
        return base.mode() == QueryMode.CONTEXTUAL ? base.toBuilder().context(context).build() : QueryOptions.contextual(context, base.flags());
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
            ctx.setOutput(node, "success", false);
            ctx.setOutput(node, "error", "");
            op.accept(ctx, node);
        } else {
            throw new IllegalArgumentException("Unknown permission operation: " + operation);
        }
        ctx.triggerOutput("flow");
    }
}

package restudio.resync.flow.nodes;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.PermissionNode;
import net.luckperms.api.node.types.PrefixNode;
import net.luckperms.api.node.types.SuffixNode;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

import java.util.ArrayList;
import java.util.List;

public class PermissionNodes implements NodeCategory {
    
    private static LuckPerms luckPerms;
    
    static LuckPerms getLuckPerms() {
        if (luckPerms == null) {
            RegisteredServiceProvider<LuckPerms> registration = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
            luckPerms = registration != null ? registration.getProvider() : null;
        }
        return luckPerms;
    }

    private static QueryOptions getQueryOptions(LuckPerms lp, User user) {
        return lp.getContextManager().getQueryOptions(user)
                .orElse(lp.getContextManager().getStaticQueryOptions());
    }

    private static QueryOptions getStaticQueryOptions(LuckPerms lp) {
        return lp.getContextManager().getStaticQueryOptions();
    }
    
    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("perm_check", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String permission = ctx.getInputValue(node, "permission", String.class, "");
            String nodeId = findNodeId(ctx, node);
            
            if (player == null || permission.isEmpty()) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", player == null ? "Player is null" : "Permission is empty");
                ctx.setNodeOutput(nodeId, "has", false);
                return;
            }
            
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "LuckPerms not available");
                ctx.setNodeOutput(nodeId, "has", false);
                return;
            }
            
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "User not found");
                ctx.setNodeOutput(nodeId, "has", false);
                return;
            }
            
            QueryOptions queryOptions = getQueryOptions(lp, user);
            boolean has = user.getCachedData().getPermissionData(queryOptions)
                    .checkPermission(permission).asBoolean();
            ctx.setNodeOutput(nodeId, "success", true);
            ctx.setNodeOutput(nodeId, "has", has);
        });
        
        registry.register("perm_grant", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String permission = ctx.getInputValue(node, "permission", String.class, "");
            String nodeId = findNodeId(ctx, node);
            
            if (player == null || permission.isEmpty()) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", player == null ? "Player is null" : "Permission is empty");
                return;
            }
            
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "LuckPerms not available");
                return;
            }
            
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "User not found");
                return;
            }
            
            Node permNode = PermissionNode.builder(permission).build();
            user.data().add(permNode);
            lp.getUserManager().saveUser(user);
            
            ctx.setNodeOutput(nodeId, "success", true);
        });
        
        registry.register("perm_revoke", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String permission = ctx.getInputValue(node, "permission", String.class, "");
            String nodeId = findNodeId(ctx, node);
            
            if (player == null || permission.isEmpty()) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", player == null ? "Player is null" : "Permission is empty");
                return;
            }
            
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "LuckPerms not available");
                return;
            }
            
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "User not found");
                return;
            }
            
            Node permNode = PermissionNode.builder(permission).build();
            user.data().remove(permNode);
            lp.getUserManager().saveUser(user);
            
            ctx.setNodeOutput(nodeId, "success", true);
        });
        
        registry.register("perm_add_group", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String group = ctx.getInputValue(node, "group", String.class, "");
            String nodeId = findNodeId(ctx, node);
            
            if (player == null || group.isEmpty()) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", player == null ? "Player is null" : "Group is empty");
                return;
            }
            
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "LuckPerms not available");
                return;
            }
            
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "User not found");
                return;
            }
            
            Node groupNode = Node.builder("group." + group).build();
            user.data().add(groupNode);
            lp.getUserManager().saveUser(user);
            
            ctx.setNodeOutput(nodeId, "success", true);
        });
        
        registry.register("perm_remove_group", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String group = ctx.getInputValue(node, "group", String.class, "");
            String nodeId = findNodeId(ctx, node);
            
            if (player == null || group.isEmpty()) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", player == null ? "Player is null" : "Group is empty");
                return;
            }
            
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "LuckPerms not available");
                return;
            }
            
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "User not found");
                return;
            }
            
            Node groupNode = Node.builder("group." + group).build();
            user.data().remove(groupNode);
            lp.getUserManager().saveUser(user);
            
            ctx.setNodeOutput(nodeId, "success", true);
        });
        
        registry.register("perm_set_group", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String group = ctx.getInputValue(node, "group", String.class, "");
            String nodeId = findNodeId(ctx, node);
            
            if (player == null || group.isEmpty()) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", player == null ? "Player is null" : "Group is empty");
                return;
            }
            
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "LuckPerms not available");
                return;
            }
            
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "User not found");
                return;
            }
            
            Group groupObj = lp.getGroupManager().getGroup(group);
            if (groupObj == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "Group not found");
                return;
            }
            
            user.setPrimaryGroup(group);
            lp.getUserManager().saveUser(user);
            
            ctx.setNodeOutput(nodeId, "success", true);
        });
        
        registry.register("perm_has_group", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String group = ctx.getInputValue(node, "group", String.class, "");
            String nodeId = findNodeId(ctx, node);
            
            if (player == null || group.isEmpty()) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", player == null ? "Player is null" : "Group is empty");
                ctx.setNodeOutput(nodeId, "has", false);
                return;
            }
            
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "LuckPerms not available");
                ctx.setNodeOutput(nodeId, "has", false);
                return;
            }
            
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "User not found");
                ctx.setNodeOutput(nodeId, "has", false);
                return;
            }
            
            QueryOptions queryOptions = getQueryOptions(lp, user);
            boolean hasGroup = user.getInheritedGroups(queryOptions).stream()
                    .anyMatch(g -> g.getName().equalsIgnoreCase(group));
            ctx.setNodeOutput(nodeId, "success", true);
            ctx.setNodeOutput(nodeId, "has", hasGroup);
        });
        
        registry.register("perm_get_groups", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String nodeId = findNodeId(ctx, node);
            
            if (player == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "Player is null");
                ctx.setNodeOutput(nodeId, "groups", new ArrayList<>());
                return;
            }
            
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "LuckPerms not available");
                ctx.setNodeOutput(nodeId, "groups", new ArrayList<>());
                return;
            }
            
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "User not found");
                ctx.setNodeOutput(nodeId, "groups", new ArrayList<>());
                return;
            }
            
            QueryOptions queryOptions = getQueryOptions(lp, user);
            List<String> groups = new ArrayList<>();
            user.getInheritedGroups(queryOptions).forEach(g -> groups.add(g.getName()));
            
            ctx.setNodeOutput(nodeId, "success", true);
            ctx.setNodeOutput(nodeId, "groups", groups);
        });
        
        registry.register("perm_get_permissions", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String nodeId = findNodeId(ctx, node);
            
            if (player == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "Player is null");
                ctx.setNodeOutput(nodeId, "permissions", new ArrayList<>());
                return;
            }
            
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "LuckPerms not available");
                ctx.setNodeOutput(nodeId, "permissions", new ArrayList<>());
                return;
            }
            
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "User not found");
                ctx.setNodeOutput(nodeId, "permissions", new ArrayList<>());
                return;
            }
            
            List<String> permissions = new ArrayList<>();
            user.getNodes().forEach(n -> permissions.add(n.getKey()));
            
            ctx.setNodeOutput(nodeId, "success", true);
            ctx.setNodeOutput(nodeId, "permissions", permissions);
        });
        
        registry.register("perm_group_has_permission", (ctx, node) -> {
            String group = ctx.getInputValue(node, "group", String.class, "");
            String permission = ctx.getInputValue(node, "permission", String.class, "");
            String nodeId = findNodeId(ctx, node);
            
            if (group.isEmpty() || permission.isEmpty()) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", group.isEmpty() ? "Group is empty" : "Permission is empty");
                ctx.setNodeOutput(nodeId, "has", false);
                return;
            }
            
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "LuckPerms not available");
                ctx.setNodeOutput(nodeId, "has", false);
                return;
            }
            
            Group groupObj = lp.getGroupManager().getGroup(group);
            if (groupObj == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "Group not found");
                ctx.setNodeOutput(nodeId, "has", false);
                return;
            }
            
            QueryOptions queryOptions = getStaticQueryOptions(lp);
            boolean has = groupObj.getCachedData().getPermissionData(queryOptions)
                    .checkPermission(permission).asBoolean();
            ctx.setNodeOutput(nodeId, "success", true);
            ctx.setNodeOutput(nodeId, "has", has);
        });
        
        registry.register("perm_group_add_permission", (ctx, node) -> {
            String group = ctx.getInputValue(node, "group", String.class, "");
            String permission = ctx.getInputValue(node, "permission", String.class, "");
            String nodeId = findNodeId(ctx, node);
            
            if (group.isEmpty() || permission.isEmpty()) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", group.isEmpty() ? "Group is empty" : "Permission is empty");
                return;
            }
            
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "LuckPerms not available");
                return;
            }
            
            Group groupObj = lp.getGroupManager().getGroup(group);
            if (groupObj == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "Group not found");
                return;
            }
            
            Node permNode = PermissionNode.builder(permission).build();
            groupObj.data().add(permNode);
            lp.getGroupManager().saveGroup(groupObj);
            
            ctx.setNodeOutput(nodeId, "success", true);
        });
        
        registry.register("perm_group_remove_permission", (ctx, node) -> {
            String group = ctx.getInputValue(node, "group", String.class, "");
            String permission = ctx.getInputValue(node, "permission", String.class, "");
            String nodeId = findNodeId(ctx, node);
            
            if (group.isEmpty() || permission.isEmpty()) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", group.isEmpty() ? "Group is empty" : "Permission is empty");
                return;
            }
            
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "LuckPerms not available");
                return;
            }
            
            Group groupObj = lp.getGroupManager().getGroup(group);
            if (groupObj == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "Group not found");
                return;
            }
            
            Node permNode = PermissionNode.builder(permission).build();
            groupObj.data().remove(permNode);
            lp.getGroupManager().saveGroup(groupObj);
            
            ctx.setNodeOutput(nodeId, "success", true);
        });
        
        registry.register("perm_get_group_permissions", (ctx, node) -> {
            String group = ctx.getInputValue(node, "group", String.class, "");
            String nodeId = findNodeId(ctx, node);
            
            if (group.isEmpty()) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "Group is empty");
                ctx.setNodeOutput(nodeId, "permissions", new ArrayList<>());
                return;
            }
            
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "LuckPerms not available");
                ctx.setNodeOutput(nodeId, "permissions", new ArrayList<>());
                return;
            }
            
            Group groupObj = lp.getGroupManager().getGroup(group);
            if (groupObj == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "Group not found");
                ctx.setNodeOutput(nodeId, "permissions", new ArrayList<>());
                return;
            }
            
            List<String> permissions = new ArrayList<>();
            groupObj.getNodes().forEach(n -> permissions.add(n.getKey()));
            
            ctx.setNodeOutput(nodeId, "success", true);
            ctx.setNodeOutput(nodeId, "permissions", permissions);
        });
        
        registry.register("perm_get_all_groups", (ctx, node) -> {
            String nodeId = findNodeId(ctx, node);
            
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "LuckPerms not available");
                ctx.setNodeOutput(nodeId, "groups", new ArrayList<>());
                return;
            }
            
            List<String> groups = new ArrayList<>();
            lp.getGroupManager().loadAllGroups().join();
            lp.getGroupManager().getLoadedGroups().forEach(g -> groups.add(g.getName()));
            
            ctx.setNodeOutput(nodeId, "success", true);
            ctx.setNodeOutput(nodeId, "groups", groups);
        });
        
        registry.register("perm_get_primary_group", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String nodeId = findNodeId(ctx, node);
            
            if (player == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "Player is null");
                ctx.setNodeOutput(nodeId, "group", "");
                return;
            }
            
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "LuckPerms not available");
                ctx.setNodeOutput(nodeId, "group", "");
                return;
            }
            
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "User not found");
                ctx.setNodeOutput(nodeId, "group", "");
                return;
            }
            
            String primaryGroup = user.getPrimaryGroup();
            ctx.setNodeOutput(nodeId, "success", true);
            ctx.setNodeOutput(nodeId, "group", primaryGroup);
        });
        
        registry.register("perm_set_prefix", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String prefix = ctx.getInputValue(node, "prefix", String.class, "");
            String nodeId = findNodeId(ctx, node);
            
            if (player == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "Player is null");
                return;
            }
            
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "LuckPerms not available");
                return;
            }
            
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "User not found");
                return;
            }
            
            Node prefixNode = PrefixNode.builder(prefix, 0).build();
            user.data().add(prefixNode);
            lp.getUserManager().saveUser(user);
            
            ctx.setNodeOutput(nodeId, "success", true);
        });
        
        registry.register("perm_set_suffix", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String suffix = ctx.getInputValue(node, "suffix", String.class, "");
            String nodeId = findNodeId(ctx, node);
            
            if (player == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "Player is null");
                return;
            }
            
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "LuckPerms not available");
                return;
            }
            
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "User not found");
                return;
            }
            
            Node suffixNode = SuffixNode.builder(suffix, 0).build();
            user.data().add(suffixNode);
            lp.getUserManager().saveUser(user);
            
            ctx.setNodeOutput(nodeId, "success", true);
        });
        
        registry.register("perm_get_prefix", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String nodeId = findNodeId(ctx, node);
            
            if (player == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "Player is null");
                ctx.setNodeOutput(nodeId, "prefix", "");
                return;
            }
            
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "LuckPerms not available");
                ctx.setNodeOutput(nodeId, "prefix", "");
                return;
            }
            
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "User not found");
                ctx.setNodeOutput(nodeId, "prefix", "");
                return;
            }
            
            String prefix = user.getCachedData().getMetaData().getPrefix();
            ctx.setNodeOutput(nodeId, "success", true);
            ctx.setNodeOutput(nodeId, "prefix", prefix != null ? prefix : "");
        });
        
        registry.register("perm_get_suffix", (ctx, node) -> {
            Player player = ctx.getInputValue(node, "player", Player.class, null);
            String nodeId = findNodeId(ctx, node);
            
            if (player == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "Player is null");
                ctx.setNodeOutput(nodeId, "suffix", "");
                return;
            }
            
            LuckPerms lp = getLuckPerms();
            if (lp == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "LuckPerms not available");
                ctx.setNodeOutput(nodeId, "suffix", "");
                return;
            }
            
            User user = lp.getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                ctx.setNodeOutput(nodeId, "success", false);
                ctx.setNodeOutput(nodeId, "error", "User not found");
                ctx.setNodeOutput(nodeId, "suffix", "");
                return;
            }
            
            String suffix = user.getCachedData().getMetaData().getSuffix();
            ctx.setNodeOutput(nodeId, "success", true);
            ctx.setNodeOutput(nodeId, "suffix", suffix != null ? suffix : "");
        });
    }
    
    private static String findNodeId(FlowContext ctx, FlowNode node) {
        for (var entry : ctx.getRuntime().getGraph().getNodes().entrySet()) {
            if (entry.getValue() == node) {
                return entry.getKey();
            }
        }
        return null;
    }
}

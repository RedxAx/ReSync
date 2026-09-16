package restudio.resync.flow.handler.generic;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.PermissionHolder;
import net.luckperms.api.model.data.DataMutateResult;
import net.luckperms.api.model.data.NodeMap;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.group.GroupManager;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.Node;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRuntime;
import restudio.resync.permissions.LuckPermsBackendPersistenceCapability;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PermissionHandlerCompensationTest {
    @Test
    void partialUserAndGroupSavesPersistRestoredNodesExactlyOnce() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        LuckPermsBackendPersistenceCapability capability = new LuckPermsBackendPersistenceCapability(new Adapter());
        Bukkit.getServicesManager().register(LuckPermsBackendPersistenceCapability.class, capability, plugin, ServicePriority.Normal);
        try {
            List<Node> userNodes = new ArrayList<>();
            List<Node> groupNodes = new ArrayList<>();
            Node userBefore = node("user-before");
            Node groupBefore = node("group-before");
            Node userAfter = node("user-after");
            Node groupAfter = node("group-after");
            userNodes.add(userBefore);
            groupNodes.add(groupBefore);
            User user = subject(User.class, userNodes);
            Group group = subject(Group.class, groupNodes);
            List<List<Node>> userSaves = new ArrayList<>();
            List<List<Node>> groupSaves = new ArrayList<>();
            UserManager userManager = sequencedManager(UserManager.class, "saveUser", userSaves,
                null,
                CompletableFuture.failedFuture(new IllegalStateException("partial user save")),
                CompletableFuture.completedFuture(null));
            GroupManager groupManager = sequencedManager(GroupManager.class, "saveGroup", groupSaves,
                null,
                CompletableFuture.failedFuture(new IllegalStateException("partial group save")),
                CompletableFuture.completedFuture(null));
            LuckPerms luckPerms = luckPerms(userManager, groupManager);
            PermissionHandler handler = new PermissionHandler();
            Method mutateUser = method("mutateUser", User.class);
            Method mutateGroup = method("mutateGroup", Group.class);
            TestFlowContext userContext = new TestFlowContext();
            TestFlowContext groupContext = new TestFlowContext();

            mutateUser.invoke(handler, userContext, null, luckPerms, user, (Runnable) () -> userNodes.add(userAfter));
            mutateGroup.invoke(handler, groupContext, null, luckPerms, group, (Runnable) () -> groupNodes.add(groupAfter));

            assertThrows(RuntimeException.class, userContext::runCaptured);
            assertThrows(RuntimeException.class, groupContext::runCaptured);

            assertEquals(List.of(userBefore), userNodes);
            assertEquals(List.of(groupBefore), groupNodes);
            assertEquals(List.of(List.of(userBefore, userAfter), List.of(userBefore)), userSaves);
            assertEquals(List.of(List.of(groupBefore, groupAfter), List.of(groupBefore)), groupSaves);
            assertFalse(userContext.success);
            assertFalse(groupContext.success);
        } finally {
            Bukkit.getServicesManager().unregister(LuckPermsBackendPersistenceCapability.class, capability);
            capability.close();
            MockBukkit.unmock();
        }
    }

    @Test
    void compensationFailureFailsClosedWithoutRecursiveSave() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        LuckPermsBackendPersistenceCapability capability = new LuckPermsBackendPersistenceCapability(new Adapter());
        Bukkit.getServicesManager().register(LuckPermsBackendPersistenceCapability.class, capability, plugin, ServicePriority.Normal);
        try {
            List<Node> nodes = new ArrayList<>();
            Node before = node("before");
            Node after = node("after");
            nodes.add(before);
            User user = subject(User.class, nodes);
            List<List<Node>> saves = new ArrayList<>();
            AtomicInteger calls = new AtomicInteger();
            UserManager userManager = sequencedManager(UserManager.class, "saveUser", saves,
                calls,
                CompletableFuture.failedFuture(new IllegalStateException("partial save")),
                CompletableFuture.failedFuture(new IllegalStateException("compensation save failed")));
            LuckPerms luckPerms = luckPerms(userManager, null);
            PermissionHandler handler = new PermissionHandler();
            TestFlowContext context = new TestFlowContext();

            method("mutateUser", User.class).invoke(handler, context, null, luckPerms, user,
                (Runnable) () -> nodes.add(after));

            RuntimeException failure = assertThrows(RuntimeException.class, context::runCaptured);

            assertEquals(2, calls.get());
            assertEquals(List.of(before), nodes);
            assertEquals(List.of(List.of(before, after), List.of(before)), saves);
            assertFalse(context.success);
            assertEquals("User permission mutation compensation could not be proven", failure.getMessage());
            assertEquals("compensation save failed", failure.getCause().getSuppressed()[0].getCause().getMessage());
            assertEquals("User permission mutation compensation could not be proven", context.error);
        } finally {
            Bukkit.getServicesManager().unregister(LuckPermsBackendPersistenceCapability.class, capability);
            capability.close();
            MockBukkit.unmock();
        }
    }

    private static Method method(String name, Class<?> holderType) throws NoSuchMethodException {
        Method method = PermissionHandler.class.getDeclaredMethod(name, FlowContext.class, FlowNode.class,
            LuckPerms.class, holderType, Runnable.class);
        method.setAccessible(true);
        return method;
    }

    private static LuckPerms luckPerms(UserManager userManager, GroupManager groupManager) {
        return (LuckPerms) Proxy.newProxyInstance(LuckPerms.class.getClassLoader(), new Class[]{LuckPerms.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUserManager" -> userManager;
                case "getGroupManager" -> groupManager;
                default -> defaultValue(method.getReturnType());
            });
    }

    @SafeVarargs
    private static <T> T sequencedManager(Class<T> type, String methodName, List<List<Node>> saves,
                                          AtomicInteger calls, CompletableFuture<Void>... results) {
        AtomicInteger index = calls == null ? new AtomicInteger() : calls;
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, (proxy, method, args) -> {
            if (method.getName().equals(methodName)) {
                PermissionHolder holder = (PermissionHolder) args[0];
                saves.add(List.copyOf(holder.getNodes()));
                int position = index.getAndIncrement();
                return results[Math.min(position, results.length - 1)];
            }
            return defaultValue(method.getReturnType());
        }));
    }

    private static <T extends PermissionHolder> T subject(Class<T> type, List<Node> nodes) {
        NodeMap map = (NodeMap) Proxy.newProxyInstance(NodeMap.class.getClassLoader(), new Class[]{NodeMap.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "toCollection", "toMap" -> method.getName().equals("toCollection") ? List.copyOf(nodes) : Map.of();
                case "add" -> {
                    nodes.add((Node) args[0]);
                    yield DataMutateResult.SUCCESS;
                }
                case "remove" -> {
                    nodes.remove(args[0]);
                    yield DataMutateResult.SUCCESS;
                }
                case "clear" -> {
                    nodes.clear();
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            });
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, (proxy, method, args) -> switch (method.getName()) {
            case "data", "transientData" -> map;
            case "getNodes" -> List.copyOf(nodes);
            case "getPrimaryGroup" -> "default";
            case "setPrimaryGroup" -> DataMutateResult.SUCCESS;
            case "getName" -> "group";
            default -> defaultValue(method.getReturnType());
        }));
    }

    private static Node node(String name) {
        return (Node) Proxy.newProxyInstance(Node.class.getClassLoader(), new Class[]{Node.class}, (proxy, method, args) -> {
            if (method.getName().equals("toString")) {
                return name;
            }
            if (method.getName().equals("equals")) {
                return proxy == args[0];
            }
            if (method.getName().equals("hashCode")) {
                return System.identityHashCode(proxy);
            }
            return defaultValue(method.getReturnType());
        });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return type == CompletableFuture.class ? CompletableFuture.completedFuture(null) : null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class || type == short.class || type == int.class || type == long.class
            || type == float.class || type == double.class) {
            return 0;
        }
        return null;
    }

    private static final class TestFlowContext extends FlowContext {
        private Runnable captured;
        private boolean success;
        private String error = "";

        private TestFlowContext() {
            super((FlowRuntime) null, null, null);
        }

        @Override
        public CompletableFuture<Void> runAsyncBeforeContinuation(Runnable runnable) {
            captured = runnable;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void setOutput(FlowNode node, String pinName, Object value) {
            if ("success".equals(pinName)) {
                success = Boolean.TRUE.equals(value);
            } else if ("error".equals(pinName)) {
                error = String.valueOf(value);
            }
        }

        private void runCaptured() {
            if (captured == null) {
                throw new AssertionError("No persistence operation was captured");
            }
            captured.run();
        }
    }

    private static final class Adapter implements LuckPermsBackendPersistenceCapability.Adapter {
        @Override
        public String id() {
            return "permission-compensation-test";
        }

        @Override
        public Path activeRoot() {
            return Path.of("permission-compensation-backend");
        }

        @Override
        public LuckPermsBackendPersistenceCapability.Support support() {
            return LuckPermsBackendPersistenceCapability.Support.capable();
        }

        @Override
        public void flush() {
        }

        @Override
        public void healthCheck() {
        }
    }
}

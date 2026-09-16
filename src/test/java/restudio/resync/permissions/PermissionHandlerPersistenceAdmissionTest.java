package restudio.resync.permissions;

import net.luckperms.api.LuckPerms;
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
import restudio.resync.flow.handler.generic.PermissionHandler;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PermissionHandlerPersistenceAdmissionTest {
    @Test
    void mutationIsAdmittedBeforeLiveCacheChangesAndHeldThroughSave() throws Exception {
        MockBukkit.mock();
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        LuckPermsBackendPersistenceCapability capability = new LuckPermsBackendPersistenceCapability(new Adapter());
        Bukkit.getServicesManager().register(LuckPermsBackendPersistenceCapability.class, capability, plugin, ServicePriority.Normal);
        AtomicInteger userSaves = new AtomicInteger();
        AtomicInteger groupSaves = new AtomicInteger();
        UserManager userManager = proxy(UserManager.class, "saveUser", userSaves);
        GroupManager groupManager = proxy(GroupManager.class, "saveGroup", groupSaves);
        LuckPerms luckPerms = (LuckPerms) Proxy.newProxyInstance(LuckPerms.class.getClassLoader(), new Class[]{LuckPerms.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUserManager" -> userManager;
                case "getGroupManager" -> groupManager;
                default -> defaultValue(method.getReturnType());
            });
        List<Node> userNodes = new ArrayList<>();
        List<Node> groupNodes = new ArrayList<>();
        User user = subject(User.class, userNodes);
        Group group = subject(Group.class, groupNodes);
        PermissionHandler handler = new PermissionHandler();
        Method mutateUser = PermissionHandler.class.getDeclaredMethod("mutateUser", FlowContext.class, FlowNode.class,
            LuckPerms.class, User.class, Runnable.class);
        Method mutateGroup = PermissionHandler.class.getDeclaredMethod("mutateGroup", FlowContext.class, FlowNode.class,
            LuckPerms.class, Group.class, Runnable.class);
        mutateUser.setAccessible(true);
        mutateGroup.setAccessible(true);
        TestFlowContext userContext = new TestFlowContext();
        TestFlowContext groupContext = new TestFlowContext();
        try {
            Node userNode = node("user");
            Node groupNode = node("group");
            mutateUser.invoke(handler, userContext, null, luckPerms, user, (Runnable) () -> userNodes.add(userNode));
            mutateGroup.invoke(handler, groupContext, null, luckPerms, group, (Runnable) () -> groupNodes.add(groupNode));
            userContext.runCaptured();
            groupContext.runCaptured();
            assertEquals(List.of(userNode), userNodes);
            assertEquals(List.of(groupNode), groupNodes);
            assertEquals(1, userSaves.get());
            assertEquals(1, groupSaves.get());

            UserManager failingUserManager = proxy(UserManager.class, "saveUser", new AtomicInteger(), true);
            LuckPerms failingLuckPerms = (LuckPerms) Proxy.newProxyInstance(LuckPerms.class.getClassLoader(), new Class[]{LuckPerms.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUserManager" -> failingUserManager;
                    case "getGroupManager" -> groupManager;
                    default -> defaultValue(method.getReturnType());
                });
            List<Node> beforeSaveFailure = List.copyOf(userNodes);
            TestFlowContext failedSave = new TestFlowContext();
            mutateUser.invoke(handler, failedSave, null, failingLuckPerms, user, (Runnable) () -> userNodes.add(node("failed-save")));
            assertThrows(RuntimeException.class, failedSave::runCaptured);
            assertEquals(beforeSaveFailure, userNodes);

            capability.quiesce();
            List<Node> beforeUserRejection = List.copyOf(userNodes);
            List<Node> beforeGroupRejection = List.copyOf(groupNodes);
            TestFlowContext rejectedUser = new TestFlowContext();
            TestFlowContext rejectedGroup = new TestFlowContext();
            mutateUser.invoke(handler, rejectedUser, null, luckPerms, user, (Runnable) () -> userNodes.add(node("rejected-user")));
            mutateGroup.invoke(handler, rejectedGroup, null, luckPerms, group, (Runnable) () -> groupNodes.add(node("rejected-group")));
            assertThrows(IllegalStateException.class, rejectedUser::runCaptured);
            assertThrows(IllegalStateException.class, rejectedGroup::runCaptured);
            assertEquals(beforeUserRejection, userNodes);
            assertEquals(beforeGroupRejection, groupNodes);
            assertEquals(1, userSaves.get());
            assertEquals(1, groupSaves.get());
        } finally {
            if (capability.state() == LuckPermsBackendPersistenceCapability.State.QUIESCED) {
                capability.resume();
            }
            Bukkit.getServicesManager().unregister(LuckPermsBackendPersistenceCapability.class, capability);
            capability.close();
            MockBukkit.unmock();
        }
    }

    private static Node node(String permission) {
        return (Node) Proxy.newProxyInstance(Node.class.getClassLoader(), new Class[]{Node.class}, (proxy, method, args) -> {
            if (method.getName().equals("toString")) {
                return permission;
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

    private static <T> T subject(Class<T> type, List<Node> nodes) {
        NodeMap map = (NodeMap) Proxy.newProxyInstance(NodeMap.class.getClassLoader(), new Class[]{NodeMap.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "toCollection" -> List.copyOf(nodes);
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

    private static <T> T proxy(Class<T> type, String methodName, AtomicInteger calls) {
        return proxy(type, methodName, calls, false);
    }

    private static <T> T proxy(Class<T> type, String methodName, AtomicInteger calls, boolean fail) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, (proxy, method, args) -> {
            if (method.getName().equals(methodName)) {
                calls.incrementAndGet();
                return fail ? CompletableFuture.failedFuture(new IllegalStateException("save failed"))
                    : CompletableFuture.completedFuture(null);
            }
            return defaultValue(method.getReturnType());
        }));
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
            return "permission-test";
        }

        @Override
        public Path activeRoot() {
            return Path.of("permission-backend");
        }

        @Override
        public LuckPermsBackendPersistenceCapability.Support support() {
            return LuckPermsBackendPersistenceCapability.Support.capable();
        }

        @Override
        public void flush() {
        }

        @Override
        public void backup(Path snapshotRoot) {
        }

        @Override
        public void restore(Path snapshotRoot) {
        }

        @Override
        public void rebind(Path activeRoot) {
        }

        @Override
        public void quiesce() {
        }

        @Override
        public void resume() {
        }

        @Override
        public void healthCheck() {
        }
    }
}

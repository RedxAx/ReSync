package restudio.resync.modules.flow;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypedResourceRoutingContractTest {
    private static final Path ROUTER = Path.of("src", "main", "java", "restudio", "resync", "modules", "flow", "FlowResourcePacketRouter.java");
    private static final Path WORKSPACES = Path.of("src", "main", "java", "restudio", "resync", "modules", "flow", "FlowWorkspaceService.java");
    private static final Path FLOW_MODULE = Path.of("src", "main", "java", "restudio", "resync", "modules", "FlowModule.java");
    private static final Path COMMAND = Path.of("src", "main", "java", "restudio", "resync", "commands", "ReSyncCommand.java");

    @Test
    void graphConflictChecksRemainTyped() throws Exception {
        String source = Files.readString(ROUTER);

        assertTrue(source.contains("return storage.readGraphIdentity(resourceType, id) != null;"));
        assertFalse(source.contains("return !storage.getGraphResourceType(id).isBlank();"));
    }

    @Test
    void committedGenericDeletionOnlyRetiresItsWorkspaceProjection() throws Exception {
        String source = Files.readString(WORKSPACES);
        int start = source.indexOf("private void resourceDeletedLocked");
        int end = source.indexOf("void delete(String type", start);
        String method = source.substring(start, end);

        assertTrue(method.contains("delete(type, resourceId, () -> {"));
        assertFalse(method.contains("storage.getGraph("));
        assertFalse(method.contains("storage.deleteGraph("));
    }

    @Test
    void legacyAdministrativeMutationsCannotBypassDurableAuthority() throws Exception {
        String module = Files.readString(FLOW_MODULE);
        String command = Files.readString(COMMAND);

        assertTrue(method(module, "public void createResource", "public void updateResource").contains("rejectLegacyResourceMutation();"));
        assertTrue(method(module, "public void updateResource", "public void deleteResource").contains("rejectLegacyResourceMutation();"));
        assertTrue(method(module, "public void deleteResource", "private void rejectLegacyResourceMutation").contains("rejectLegacyResourceMutation();"));
        assertTrue(method(module, "public boolean supportsResourceActivation", "public List<String> resourceIds").contains("return false;"));
        assertTrue(method(module, "public void setResourceEnabled", "public void registerWorkspaceDocumentProvider").contains("rejectLegacyResourceMutation();"));
        String activation = method(module, "private void handleResourceActivation", "private void sendResourceActivationResult");
        assertTrue(activation.contains("LEGACY_RESOURCE_MUTATION_UNAVAILABLE"));
        assertFalse(activation.contains("resources.setEnabledAuthoritative"));
        assertFalse(module.contains("resources.createJson(type, gson.toJson(resource))"));
        assertFalse(module.contains("resources.updateJson(type, gson.toJson(resource))"));
        assertFalse(module.contains("resources.deleteAuthoritative(type, resourceId)"));
        assertFalse(command.contains("storage.save(type, resource)"));
        assertFalse(command.contains("storage.delete(type, id)"));
        assertFalse(command.contains("flowModule.setResourceEnabled(type, id, enabled)"));
        assertTrue(method(command, "private void createResource", "private void updateResource").contains("FlowModule.LEGACY_RESOURCE_MUTATION_UNAVAILABLE"));
        assertTrue(method(command, "private void updateResource", "private void deleteResource").contains("FlowModule.LEGACY_RESOURCE_MUTATION_UNAVAILABLE"));
        assertTrue(method(command, "private void deleteResource", "private List<String> resourceFieldOptions").contains("FlowModule.LEGACY_RESOURCE_MUTATION_UNAVAILABLE"));
    }

    private String method(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex);
        assertTrue(startIndex >= 0);
        assertTrue(endIndex > startIndex);
        return source.substring(startIndex, endIndex);
    }
}

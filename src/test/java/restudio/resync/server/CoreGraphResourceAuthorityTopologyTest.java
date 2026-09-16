package restudio.resync.server;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreGraphResourceAuthorityTopologyTest {
    @Test
    void bindsRegisteredCoreGraphStorageExactlyOnceBeforeGenericProtocolAuthorityCreation() throws IOException {
        String runtimeSource = Files.readString(Path.of(
            "src/main/java/restudio/resync/modules/FlowRuntimeModule.java"));
        String serverSource = Files.readString(Path.of(
            "src/main/java/restudio/resync/server/ReSyncServer.java"));
        String registrySource = Files.readString(Path.of(
            "src/main/java/restudio/resync/modules/flow/FlowResourceRegistry.java"));
        String handlerSource = Files.readString(Path.of(
            "src/main/java/restudio/resync/server/FlowResourceProtocolEnvelopeHandler.java"));
        String protocolAuthoritySource = Files.readString(Path.of(
            "src/main/java/restudio/resync/server/ProtocolResourceMutationAuthority.java"));

        int binding = serverSource.indexOf("flowRuntimeModule.bindCoreGraphResourceAuthority()");
        int registryBinding = serverSource.indexOf("resourceRegistry.bindCoreGraphResourceAuthority(coreGraphResourceAuthority)");
        int mutationAuthorityRequirement = serverSource.indexOf("resourceRegistry.requireGenericMutationAuthority(");
        int activation = serverSource.indexOf("flowRuntimeModule.registerExtensionRegistryActivation()");
        int protocolAuthority = serverSource.indexOf("new SqliteProtocolResourceMutationAuthority");
        int handler = serverSource.indexOf("new FlowResourceProtocolEnvelopeHandler");

        assertTrue(binding >= 0);
        assertEquals(1, occurrences(serverSource, "resourceRegistry.bindCoreGraphResourceAuthority(coreGraphResourceAuthority)"));
        assertTrue(registryBinding > binding);
        assertTrue(mutationAuthorityRequirement > registryBinding);
        assertTrue(activation > mutationAuthorityRequirement);
        assertTrue(protocolAuthority > binding);
        assertTrue(protocolAuthority > registryBinding);
        assertTrue(protocolAuthority > activation);
        assertTrue(handler > registryBinding);
        assertTrue(serverSource.contains("coreGraphResourceAuthority);"));
        assertTrue(registrySource.contains("protocolLoad(ServerResourceLocator resource)"));
        assertTrue(registrySource.contains("protocolList(ServerId serverId,"));
        assertTrue(protocolAuthoritySource.contains("authoritativeCoreReads()"));
        assertTrue(handlerSource.contains("mutationAuthority.load(resource)"));
        assertEquals(4, occurrences(handlerSource, "mutationAuthority.list(serverId, request.type(), request.search())"));
        assertFalse(handlerSource.contains("registry.protocolLoad(resource)"));
        assertFalse(handlerSource.contains("registry.protocolList(serverId, request.type(), request.search())"));
        assertEquals(1, occurrences(runtimeSource, "new FlowStorageCoreGraphResourceAuthority("));
        assertTrue(runtimeSource.contains("moduleContext.getService(FlowStorage.class)"));
        assertTrue(runtimeSource.contains("moduleContext.getService(ServerIdentityStore.class)"));
        assertTrue(runtimeSource.contains("registeredStorage != storage"));
        assertTrue(runtimeSource.contains("moduleContext.registerService(CoreGraphResourceAuthority.class, authority)"));
        assertTrue(runtimeSource.contains("if (existing != null)"));
        assertTrue(runtimeSource.contains("if (!authority.available())"));
        assertTrue(runtimeSource.contains("stopped || stopPending"));
        assertTrue(serverSource.contains("coreGraphResourceAuthority == null || !coreGraphResourceAuthority.available()"));
        assertFalse(serverSource.contains("CoreGraphResourceAuthority.unavailable()"));
        assertFalse(serverSource.contains("new FlowStorage("));
    }

    private int occurrences(String source, String value) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(value, offset)) >= 0) {
            count++;
            offset += value.length();
        }
        return count;
    }
}

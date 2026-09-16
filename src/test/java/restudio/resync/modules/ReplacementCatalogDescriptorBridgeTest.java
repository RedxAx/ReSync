package restudio.resync.modules;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.catalog.CatalogCapabilityDescriptor;
import restudio.resync.flow.catalog.CatalogCategoryDescriptor;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.catalog.CatalogNodeDescriptor.Lifecycle;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.inspector.InspectorOptionSource;
import restudio.resync.flow.registry.AuthoredNodeMetadata;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionLoader;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ReplacementCatalogDescriptorBridgeTest {
    @Test
    void authoredAutomationFieldsReachCoreDescriptorsWithoutLegacyInference() {
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        String source = "[{\"id\":\"automation.bridge\",\"displayName\":\"Automation Bridge\",\"description\":\"Carries authored automation metadata into the Core catalog descriptor.\",\"domain\":\"automation\",\"family\":\"bridge\",\"lifecycle\":\"active\",\"handlerCapability\":\"automation.bridge\",\"selectorIntent\":\"typed-reference\",\"inspectorIntent\":\"generic\",\"category\":\"UTILITY\",\"handler\":\"ReplacementHandler\",\"handlerConfig\":{\"operation\":\"bridge\"},\"inputs\":[{\"id\":\"source\",\"displayName\":\"Source\",\"name\":\"source\",\"dataType\":\"string\",\"description\":\"Provides the authored source value for this bridge operation.\"}],\"outputs\":[{\"id\":\"result\",\"displayName\":\"Result\",\"name\":\"result\",\"dataType\":\"string\",\"description\":\"Returns the authored result produced by this bridge operation.\"}]}]";
        List<NodeDefinition> definitions = loader.parseReplacement(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), "automation-bridge.json");
        HandlerRegistry handlers = handlers();
        List<RuntimeOperationDescriptor> runtimeRequirements = new ArrayList<>();

        assertEquals(1, definitions.size());
        NodeDefinition definition = definitions.getFirst();
        AuthoredNodeMetadata authored = definition.getAuthoredMetadata();
        assertNotNull(authored, definition.getId());
        Map<String, CatalogCategoryDescriptor> categories = new LinkedHashMap<>();
        Map<String, CatalogCapabilityDescriptor> capabilities = new LinkedHashMap<>();
        Map<String, InspectorOptionSource> optionSources = new LinkedHashMap<>();
        CatalogNodeDescriptor descriptor = FlowModule.catalogNode(
            OwnerId.of(definition.getOwner()), definition, categories, capabilities, optionSources, runtimeRequirements, handlers);

        Map<?, ?> sourceMetadata = assertInstanceOf(Map.class, descriptor.metadata().get("authoredSource"));
        assertEquals(authored.toMetadata(), sourceMetadata);
        assertEquals(authored.id(), descriptor.id().value());
        assertEquals(authored.domain(), descriptor.domain());
        assertEquals(authored.family(), descriptor.family());
        assertEquals(lifecycle(authored.lifecycle()), descriptor.lifecycle());
        assertEquals(authored.description(), sourceMetadata.get("description"));
        assertEquals(authored.handlerCapability(), sourceMetadata.get("handlerCapability"));
        assertEquals(authored.selectorIntent(), sourceMetadata.get("selectorIntent"));
        assertEquals(authored.inspectorIntent(), sourceMetadata.get("inspectorIntent"));
        assertEquals(authored.handlerCapability(), descriptor.handler().capability().id().value());
        assertEquals(authored.handlerCapability(), runtimeRequirements.getFirst().capability().id().value());
    }

    @Test
    void retiringAuthoredReplacementIdentityReachesCoreDescriptorWithoutActivation() {
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        String source = "[{\"id\":\"automation.retiring\",\"displayName\":\"Retiring Automation\",\"description\":\"Retires an authored automation definition after its replacement is available.\",\"domain\":\"automation\",\"family\":\"bridge\",\"lifecycle\":\"retiring\",\"replacementFor\":\"  automation.current  \",\"handlerCapability\":\"automation.retiring\",\"selectorIntent\":\"typed-reference\",\"inspectorIntent\":\"generic\",\"category\":\"UTILITY\",\"handler\":\"ReplacementHandler\",\"handlerConfig\":{\"operation\":\"bridge\"},\"inputs\":[],\"outputs\":[]}]";
        List<NodeDefinition> definitions = loader.parseReplacement(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), "automation-retiring.json");
        HandlerRegistry handlers = handlers();
        List<RuntimeOperationDescriptor> runtimeRequirements = new ArrayList<>();

        assertEquals(1, definitions.size());
        NodeDefinition definition = definitions.getFirst();
        OwnerId owner = OwnerId.of(definition.getOwner());
        CatalogNodeDescriptor descriptor = FlowModule.catalogNode(
            owner, definition, new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), runtimeRequirements, handlers);

        assertEquals(Lifecycle.RETIRING, descriptor.lifecycle());
        assertEquals(ContractRef.of(owner, NodeId.of("automation.current")), descriptor.replacementIdentity());
        assertEquals("automation.retiring", descriptor.id().value());
        assertNotNull(descriptor.metadata().get("authoredSource"));
        assertFalse(descriptor.metadata().containsKey("owner"));
    }

    private HandlerRegistry handlers() {
        HandlerRegistry handlers = new HandlerRegistry();
        handlers.register("ReplacementHandler", handler(Set.of("bridge")));
        return handlers;
    }

    private NodeHandler handler(Set<String> operations) {
        return new NodeHandler() {
            @Override
            public void execute(FlowContext context, FlowNode node) {
            }

            @Override
            public Set<String> getSupportedOperations() {
                return operations;
            }
        };
    }

    private Lifecycle lifecycle(String value) {
        return switch (value.strip().toLowerCase()) {
            case "active" -> Lifecycle.ACTIVE;
            case "deprecated" -> Lifecycle.DEPRECATED;
            case "retiring" -> Lifecycle.RETIRING;
            case "migration-only" -> Lifecycle.MIGRATION_ONLY;
            default -> throw new IllegalArgumentException(value);
        };
    }
}

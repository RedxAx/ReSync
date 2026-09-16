package restudio.resync.flow.type;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeFoundationTest {
    @Test
    void recursiveGenericTypesAreImmutableAndCanonical() {
        var string = TypeExpr.named(TypeReference.of("restudio.resync", "string"));
        var resource = TypeExpr.resource(TypeReference.of("example.reference", "task"));
        var union = TypeExpr.union(List.of(
                new TypeExpr.UnionVariant("resource", resource),
                new TypeExpr.UnionVariant("text", string)
        ));
        var expression = TypeExpr.named(TypeReference.of("example.reference", "container"), List.of(
                TypeExpr.map(string, TypeExpr.list(TypeExpr.optional(union))),
                TypeExpr.tuple(List.of(string, resource))
        ));
        var reversedUnion = TypeExpr.union(List.of(
                new TypeExpr.UnionVariant("text", string),
                new TypeExpr.UnionVariant("resource", resource)
        ));

        assertEquals("resource", union.variants().getFirst().variantId());
        assertEquals(union, reversedUnion);
        assertTrue(expression.canonicalJson().contains("\"kind\":\"map\""));
        assertThrows(UnsupportedOperationException.class, () -> union.variants().add(new TypeExpr.UnionVariant("other", string)));

        var mutableArguments = new ArrayList<TypeExpr>();
        mutableArguments.add(string);
        var named = TypeExpr.named(TypeReference.of("example.reference", "immutable"), mutableArguments);
        mutableArguments.clear();
        assertEquals(1, named.arguments().size());
    }

    @Test
    void opaqueUnknownValuesRemainOpaqueAndLossless() {
        var unknown = TypeExpr.opaque(TypeReference.of("missing.extension", "future-value"));
        var raw = new LinkedHashMap<String, Object>();
        raw.put("b", List.of(2, 1));
        raw.put("a", "preserved");
        var value = TypedValue.opaque(unknown, raw);
        raw.put("a", "changed");

        assertEquals(TypedValue.State.OPAQUE, value.state());
        assertEquals("preserved", ((Map<?, ?>) value.value()).get("a"));
        assertThrows(UnsupportedOperationException.class, () -> ((Map<?, ?>) value.value()).clear());
        assertTrue(value.canonicalJson().contains("\"state\":\"opaque\""));
        assertTrue(unknown.canonicalJson().contains("\"raw\":true"));
        assertThrows(IllegalArgumentException.class, () -> TypedValue.value(unknown, "known"));
        var locator = new ServerResourceLocator(UUID.randomUUID(), new ContractRef<>(new OwnerId("missing.extension"), new ResourceTypeId("future-value")), "value");
        assertThrows(IllegalArgumentException.class, () -> TypedValue.locator(unknown, locator));
    }

    @Test
    void typedValueStatesUseCanonicalJsonWithoutChangingTheirShape() {
        var type = TypeExpr.named(TypeReference.of("example.reference", "text"));
        var resourceType = TypeExpr.resource(TypeReference.of("example.reference", "task"));
        var locator = new ServerResourceLocator(UUID.fromString("00000000-0000-4000-8000-000000000001"), new ContractRef<>(new OwnerId("example.reference"), new ResourceTypeId("task")), "home");
        var opaqueType = TypeExpr.opaque(TypeReference.of("missing.extension", "future-value"));

        var absent = TypedValue.absent(type);
        var nullValue = TypedValue.nullValue(type);
        var material = TypedValue.value(type, "text");
        var located = TypedValue.locator(resourceType, locator);
        var opaque = TypedValue.opaque(opaqueType, null);

        assertEquals("absent", ((Map<?, ?>) CanonicalJson.parse(absent.canonicalJson())).get("state"));
        assertEquals("null", ((Map<?, ?>) CanonicalJson.parse(nullValue.canonicalJson())).get("state"));
        assertEquals("value", ((Map<?, ?>) CanonicalJson.parse(material.canonicalJson())).get("state"));
        assertEquals("locator", ((Map<?, ?>) CanonicalJson.parse(located.canonicalJson())).get("state"));
        assertEquals("opaque", ((Map<?, ?>) CanonicalJson.parse(opaque.canonicalJson())).get("state"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) CanonicalJson.parse(opaque.type().canonicalJson())).get("raw"));
        assertTrue(opaque.canonicalJson().contains("\"value\":null"));
    }

    @Test
    void genericResourceValuesAndNestedUnknownFieldsRemainCanonical() {
        var locator = new ServerResourceLocator(UUID.fromString("00000000-0000-4000-8000-000000000001"), new ContractRef<>(new OwnerId("example.reference"), new ResourceTypeId("task")), "home");
        var resource = new TypeExpr.ResourceType(TypeReference.of("example.reference", "task"), Map.of("future", Map.of("locator", locator)));
        var generic = TypeExpr.named(TypeReference.of("example.reference", "box"), List.of(resource));
        TypedValue value = TypedValue.value(generic, locator, Map.of("futureValue", Map.of("type", resource)));

        assertTrue(value.canonicalJson().contains("\"future\""));
        assertTrue(value.canonicalJson().contains("\"futureValue\""));
        assertEquals("value", ((Map<?, ?>) CanonicalJson.parse(value.canonicalJson())).get("state"));
    }

    @Test
    void typeDescriptorAndCodecMetadataRejectUnsafePersistence() {
        var type = TypeExpr.named(TypeReference.of("example.reference", "task"));
        var storage = new CodecDescriptor(TypeReference.of("restudio.resync", "task-json"), 1, true, true);
        var network = new CodecDescriptor(TypeReference.of("restudio.resync", "task-wire"), 2, true, true);
        var descriptor = new TypeDescriptor(TypeReference.of("example.reference", "task"), "Task", type, storage, network, List.of(TypeReference.of("example.reference", "task-valid")), true, true);

        assertTrue(descriptor.canonicalJson().contains("\"displayName\":\"Task\""));
        assertThrows(IllegalArgumentException.class, () -> new CodecDescriptor(TypeReference.of("example.reference", "unsafe"), 0, false, true));
        var nondeterministic = new CodecDescriptor(TypeReference.of("example.reference", "random"), 1, false, false);
        assertThrows(IllegalArgumentException.class, () -> new TypeDescriptor(TypeReference.of("example.reference", "unsafe"), "Unsafe", type, nondeterministic, network, List.of(), true, true));
    }

    @Test
    void conversionGraphChoosesTheUniqueLowestRankPath() {
        var source = TypeExpr.named(TypeReference.of("example.reference", "source"));
        var middle = TypeExpr.named(TypeReference.of("example.reference", "middle"));
        var target = TypeExpr.named(TypeReference.of("example.reference", "target"));
        var directId = TypeReference.of("example.reference", "direct");
        var firstId = TypeReference.of("example.reference", "source-middle");
        var secondId = TypeReference.of("example.reference", "middle-target");
        var graph = ConversionGraph.builder()
                .add(directId, source, target, 8, true, 0)
                .add(secondId, middle, target, 1, true, 0)
                .add(firstId, source, middle, 1, true, 0)
                .build();

        var path = graph.resolve(source, target);
        var reversedGraph = ConversionGraph.builder()
                .add(firstId, source, middle, 1, true, 0)
                .add(directId, source, target, 8, true, 0)
                .add(secondId, middle, target, 1, true, 0)
                .build();

        assertEquals(List.of(firstId, secondId), path.edgeIds());
        assertEquals(graph.edges(), reversedGraph.edges());
        assertEquals(path.edgeIds(), reversedGraph.resolve(source, target).edgeIds());
        assertEquals(new ConversionGraph.ConversionRank(2, 0, 0, 2), path.rank());
        assertNotEquals(directId, path.edgeIds().getFirst());
    }

    @Test
    void conversionGraphRejectsEqualRankedAlternatives() {
        var source = TypeExpr.named(TypeReference.of("example.reference", "source"));
        var target = TypeExpr.named(TypeReference.of("example.reference", "target"));
        var exception = assertThrows(ConversionGraph.ResolutionException.class, () -> ConversionGraph.builder()
                .add(TypeReference.of("example.reference", "first"), source, target, 1, true, 0)
                .add(TypeReference.of("example.reference", "second"), source, target, 1, true, 0)
                .build()
                .resolve(source, target));

        assertEquals(ConversionGraph.Reason.AMBIGUOUS, exception.reason());
    }
}

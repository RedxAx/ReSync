package restudio.resync.flow.function;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.OpaqueData;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FunctionSourceDocumentCodecTest {
    private static final ServerResourceLocator RESOURCE = new ServerResourceLocator(
        new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111")),
        ContractRef.of(new OwnerId("resync"), new ResourceTypeId("function")), "identity");
    private static final TypeExpr INTEGER = TypeExpr.named(new TypeReference("builtin", "integer"));
    private static final TypeExpr TEXT = TypeExpr.named(new TypeReference("builtin", "string"));
    private static final FunctionParameterId FIRST = FunctionParameterId.of(UUID.fromString("22222222-2222-4222-8222-222222222222"));
    private static final FunctionParameterId SECOND = FunctionParameterId.of(UUID.fromString("33333333-3333-4333-8333-333333333333"));
    private static final FunctionParameterId OUTPUT = FunctionParameterId.of(UUID.fromString("44444444-4444-4444-8444-444444444444"));
    private static final FunctionSourceDocumentCodec CODEC = FunctionSourceDocumentCodec.INSTANCE;

    @Test
    void roundTripPreservesOrderDefaultsUnknownDataAndStableBytes() {
        FunctionParameterContract first = new FunctionParameterContract(FIRST, INTEGER, true,
            TypedValue.value(INTEGER, BigInteger.TEN), Map.of("parameterFuture", Map.of("enabled", true)));
        FunctionParameterContract second = new FunctionParameterContract(SECOND, TEXT, false, null, Map.of("parameterNote", "kept"));
        FunctionParameterContract output = new FunctionParameterContract(OUTPUT, TEXT, true);
        FunctionSignature signature = new FunctionSignature(new FunctionLocator(RESOURCE), new FunctionRevision(9),
            List.of(first, second), List.of(output), Map.of("signatureFuture", List.of("a", "b")));
        FunctionSourceDocument source = new FunctionSourceDocument(signature, graph(9),
            OpaqueData.of(Map.of("documentFuture", Map.of("enabled", true))));

        byte[] firstBytes = CODEC.encodeBytes(source);
        byte[] secondBytes = CODEC.encodeBytes(source);
        FunctionSourceDocument decoded = CODEC.decodeBytes(firstBytes);

        assertArrayEquals(firstBytes, secondBytes);
        assertEquals(new String(firstBytes), CODEC.encodeText(decoded));
        assertEquals(source.checksum(), CODEC.checksum(source));
        assertEquals(List.of(FIRST, SECOND), decoded.signature().inputs().stream().map(FunctionParameterContract::id).toList());
        assertEquals(BigInteger.TEN, decoded.signature().inputs().getFirst().defaultValue().value());
        assertEquals(true, ((Map<?, ?>) decoded.unknown().get("documentFuture")).get("enabled"));
        assertEquals(List.of("a", "b"), decoded.signature().unknown().get("signatureFuture"));
    }

    @Test
    void knownFieldsAreRequiredAndDuplicateParameterIdsAreRejected() {
        JsonValue.JsonObject signature = CODEC.encodeSignature(signature(9));
        Map<String, Object> missingRequired = mutableObject(signature.toJava());
        Map<String, Object> first = (Map<String, Object>) ((List<?>) missingRequired.get("inputs")).getFirst();
        first.remove("required");
        assertThrows(IllegalArgumentException.class, () -> CODEC.decodeSignature(JsonValue.fromJava(missingRequired)));

        FunctionParameterContract duplicate = new FunctionParameterContract(FIRST, TEXT, true);
        FunctionSignature duplicateSignature = new FunctionSignature(new FunctionLocator(RESOURCE), new FunctionRevision(9),
            List.of(duplicate), List.of());
        Map<String, Object> duplicateRoot = mutableObject(CODEC.encodeSignature(duplicateSignature).toJava());
        duplicateRoot.put("outputs", duplicateRoot.get("inputs"));
        assertThrows(IllegalArgumentException.class, () -> CODEC.decodeSignature(JsonValue.fromJava(duplicateRoot)));
    }

    @Test
    void opaqueTypeMarkerAndTypedMaterialAreValidatedWithoutDefaults() {
        Map<String, Object> raw = mutableObject(CODEC.encodeSignature(signature(9)).toJava());
        Map<String, Object> parameter = (Map<String, Object>) ((List<?>) raw.get("inputs")).getFirst();
        Map<String, Object> type = (Map<String, Object>) parameter.get("type");
        type.put("kind", "opaque");
        type.put("raw", false);
        type.remove("arguments");
        type.put("type", Map.of("ownerId", "builtin", "localId", "raw"));
        assertThrows(IllegalArgumentException.class, () -> CODEC.decodeSignature(JsonValue.fromJava(raw)));

        Map<String, Object> noDefault = mutableObject(CODEC.encodeSignature(signature(9)).toJava());
        Map<String, Object> noDefaultParameter = (Map<String, Object>) ((List<?>) noDefault.get("inputs")).getFirst();
        noDefaultParameter.remove("defaultValue");
        FunctionSignature decoded = CODEC.decodeSignature(JsonValue.fromJava(noDefault));
        assertEquals(null, decoded.inputs().getFirst().defaultValue());
    }

    private static FunctionSignature signature(long revision) {
        return new FunctionSignature(new FunctionLocator(RESOURCE), new FunctionRevision(revision),
            List.of(new FunctionParameterContract(FIRST, INTEGER, true, TypedValue.value(INTEGER, BigInteger.TEN))),
            List.of(new FunctionParameterContract(OUTPUT, TEXT, true)));
    }

    private static GraphDocument graph(long revision) {
        return new GraphDocument(new CatalogVersion(1, 0), RESOURCE, revision, binding(), Set.of(), List.of(), List.of(),
            List.of(), List.of(), OpaqueData.of(Map.of("graphFuture", Map.of("keep", true))));
    }

    private static CatalogBinding binding() {
        return new CatalogBinding(1, new ContentHash("0".repeat(64)), new ContentHash("1".repeat(64)));
    }

    private static Map<String, Object> mutableObject(Object value) {
        Map<String, Object> source = (Map<String, Object>) value;
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, item) -> copy.put(key, mutableValue(item)));
        return copy;
    }

    private static Object mutableValue(Object value) {
        if (value instanceof Map<?, ?>) {
            return mutableObject(value);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(FunctionSourceDocumentCodecTest::mutableValue).toList().stream()
                .collect(Collectors.toCollection(ArrayList::new));
        }
        return value;
    }

}

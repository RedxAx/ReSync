package restudio.resync.flow.type;

import org.junit.jupiter.api.Test;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TypeDescriptorCodecTest {
    @Test
    void losslessCodecPreservesAllMetadataAndUnknownBoundaries() {
        TypeReference id = new TypeReference("restudio", "text", Map.of("idFuture", true));
        TypeReference codecId = new TypeReference("restudio", "json", Map.of("codecIdFuture", true));
        CodecDescriptor storage = new CodecDescriptor(codecId, 3, true, true, Map.of("storageFuture", List.of(1, 2)));
        CodecDescriptor network = new CodecDescriptor(codecId, 4, true, false, Map.of("networkFuture", true));
        TypeExpr expression = new TypeExpr.Named(new TypeReference("builtin", "string", Map.of("typeFuture", true)),
            List.of(), Map.of("expressionFuture", true));
        ContractRef<CapabilityId> editor = new ContractRef<>(new OwnerId("restudio"), CapabilityId.of("text-editor"),
            Map.of("editorFuture", true));
        TypeReference validator = new TypeReference("restudio", "not-blank", Map.of("validatorFuture", true));
        TypeDescriptor descriptor = new TypeDescriptor(id, "Text", expression, Map.of("kind", "text", "future", true),
            storage, network, editor, List.of(validator), true, true, Map.of("descriptorFuture", true));

        byte[] encoded = TypeDescriptorCodec.INSTANCE.encodeBytes(descriptor);
        TypeDescriptor decoded = TypeDescriptorCodec.INSTANCE.decodeBytes(encoded);

        assertArrayEquals(encoded, TypeDescriptorCodec.INSTANCE.encodeBytes(decoded));
        assertEquals(true, decoded.unknown().get("descriptorFuture"));
        assertEquals(List.of(BigDecimal.ONE, new BigDecimal("2")),
            decoded.storageCodec().unknown().get("storageFuture"));
        assertEquals(true, decoded.networkCodec().unknown().get("networkFuture"));
        assertEquals(true, decoded.id().unknown().get("idFuture"));
        assertEquals(true, decoded.storageCodec().id().unknown().get("codecIdFuture"));
        assertEquals(true, ((TypeExpr.Named) decoded.expression()).reference().unknown().get("typeFuture"));
        assertEquals(true, decoded.editor().unknown().get("editorFuture"));
        assertEquals(true, decoded.validators().getFirst().unknown().get("validatorFuture"));
    }

    @Test
    void legacyDescriptorCanonicalBytesRemainFrozenAndLossyOnlyAtThatBoundary() {
        CodecDescriptor codec = new CodecDescriptor(TypeReference.of("restudio", "json"), 7, true, true,
            Map.of("codecFuture", true));
        TypeDescriptor descriptor = new TypeDescriptor(TypeReference.of("restudio", "text"), "Text",
            TypeExpr.named(TypeReference.of("builtin", "string")), Map.of("kind", "text"), codec, codec,
            ContractRef.of(new OwnerId("restudio"), CapabilityId.of("text-editor")),
            List.of(TypeReference.of("restudio", "not-blank")), true, true);
        String frozen = "{\"displayName\":\"Text\",\"editor\":{\"localId\":\"text-editor\",\"ownerId\":\"restudio\"},"
            + "\"expression\":{\"arguments\":[],\"kind\":\"named\",\"type\":{\"localId\":\"string\",\"ownerId\":\"builtin\"}},"
            + "\"id\":\"text\",\"literalSchema\":{\"kind\":\"text\"},\"networkCodec\":{\"localId\":\"json\",\"ownerId\":\"restudio\"},"
            + "\"persistable\":true,\"storageCodec\":{\"localId\":\"json\",\"ownerId\":\"restudio\"},\"transportable\":true,"
            + "\"validators\":[{\"localId\":\"not-blank\",\"ownerId\":\"restudio\"}]}";

        assertArrayEquals(frozen.getBytes(StandardCharsets.UTF_8), descriptor.canonicalJson().getBytes(StandardCharsets.UTF_8));
        assertEquals(false, descriptor.canonicalJson().contains("codecFuture"));
        assertEquals(false, descriptor.canonicalJson().contains("deterministic"));
    }

    @Test
    void missingCodecMetadataAndUnsupportedEnvelopeFailClosed() {
        TypeDescriptor descriptor = descriptor();
        JsonValue.JsonObject encoded = TypeDescriptorCodec.INSTANCE.encode(descriptor);

        Map<String, JsonValue> missingMetadata = new LinkedHashMap<>(((JsonValue.JsonObject) encoded.value("storageCodec")).fields());
        missingMetadata.remove("version");
        Map<String, JsonValue> missingRoot = new LinkedHashMap<>(encoded.fields());
        missingRoot.put("storageCodec", JsonValue.object(missingMetadata));
        assertThrows(IllegalArgumentException.class, () -> TypeDescriptorCodec.INSTANCE.decode(JsonValue.object(missingRoot)));

        Map<String, JsonValue> futureVersion = new LinkedHashMap<>(encoded.fields());
        futureVersion.put("version", JsonValue.fromJava(2));
        assertThrows(IllegalArgumentException.class, () -> TypeDescriptorCodec.INSTANCE.decode(JsonValue.object(futureVersion)));
    }

    @Test
    void presentNullLiteralSchemaRoundTripsWhileMissingFieldFailsClosed() {
        CodecDescriptor codec = new CodecDescriptor(TypeReference.of("restudio", "json"), 1, true, true);
        TypeDescriptor descriptor = new TypeDescriptor(TypeReference.of("restudio", "text"), "Text",
            TypeExpr.named(TypeReference.of("builtin", "string")), null, codec, codec,
            ContractRef.of(new OwnerId("restudio"), CapabilityId.of("text-editor")), List.of(), true, true);

        byte[] encoded = TypeDescriptorCodec.INSTANCE.encodeBytes(descriptor);
        TypeDescriptor decoded = TypeDescriptorCodec.INSTANCE.decodeBytes(encoded);

        assertNull(decoded.literalSchema());
        assertArrayEquals(encoded, TypeDescriptorCodec.INSTANCE.encodeBytes(decoded));

        Map<String, JsonValue> missing = new LinkedHashMap<>(TypeDescriptorCodec.INSTANCE.encode(descriptor).fields());
        missing.remove("literalSchema");
        assertThrows(IllegalArgumentException.class,
            () -> TypeDescriptorCodec.INSTANCE.decode(JsonValue.object(missing)));
    }

    private static TypeDescriptor descriptor() {
        CodecDescriptor codec = new CodecDescriptor(TypeReference.of("restudio", "json"), 1, true, true);
        return new TypeDescriptor(TypeReference.of("restudio", "text"), "Text",
            TypeExpr.named(TypeReference.of("builtin", "string")), Map.of(), codec, codec,
            ContractRef.of(new OwnerId("restudio"), CapabilityId.of("text-editor")), List.of(), true, true);
    }
}

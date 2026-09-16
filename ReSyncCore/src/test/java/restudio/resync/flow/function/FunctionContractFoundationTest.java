package restudio.resync.flow.function;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunctionContractFoundationTest {
    private static final UUID SERVER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final ContractRef<ResourceTypeId> FUNCTION_TYPE = new ContractRef<>(new OwnerId("restudio.resync"), new ResourceTypeId("function"));
    private static final TypeExpr TEXT = TypeExpr.named(TypeReference.of("restudio.resync", "string"));
    private static final FunctionParameterId INPUT_ID = FunctionParameterId.of(UUID.fromString("22222222-2222-4222-8222-222222222222"));
    private static final FunctionParameterId OUTPUT_ID = FunctionParameterId.of(UUID.fromString("33333333-3333-4333-8333-333333333333"));

    @Test
    void locatorAndRevisionUseCanonicalIdentity() {
        FunctionLocator locator = new FunctionLocator(new ServerResourceLocator(SERVER, FUNCTION_TYPE, "welcome"));

        assertEquals("11111111-1111-4111-8111-111111111111/restudio.resync/function/welcome", locator.canonicalText());
        assertEquals(locator, FunctionLocator.parseCanonicalText(locator.canonicalText()));
        assertEquals(new FunctionRevision(8), FunctionRevision.parseCanonicalText("8"));
        assertEquals(new FunctionRevision(9), new FunctionRevision(8).next());
        assertThrows(IllegalArgumentException.class, () -> FunctionRevision.parseCanonicalText("08"));
        assertThrows(IllegalArgumentException.class, () -> new FunctionRevision(-1));
    }

    @Test
    void signatureAndTypedMapsAreImmutableAndTypeChecked() {
        FunctionLocator locator = new FunctionLocator(new ServerResourceLocator(SERVER, FUNCTION_TYPE, "welcome"));
        FunctionParameterContract input = new FunctionParameterContract(INPUT_ID, TEXT, true);
        FunctionParameterContract output = new FunctionParameterContract(OUTPUT_ID, TEXT, true);
        FunctionSignature signature = new FunctionSignature(locator, new FunctionRevision(4), List.of(input), List.of(output));
        Map<FunctionParameterId, TypedValue> mutable = new LinkedHashMap<>();
        mutable.put(INPUT_ID, TypedValue.value(TEXT, "hello"));
        FunctionInputMap values = new FunctionInputMap(mutable);
        mutable.clear();

        assertEquals(1, values.values().size());
        assertThrows(UnsupportedOperationException.class, () -> values.values().clear());
        assertTrue(values.validate(signature).isEmpty());
        assertTrue(new FunctionInputMap(Map.of(INPUT_ID, TypedValue.value(TypeExpr.named(TypeReference.of("restudio.resync", "integer")), 2)))
            .validate(signature).stream().anyMatch(diagnostic -> diagnostic.code().equals("FUNCTION.PARAMETER_TYPE_MISMATCH")));
        assertThrows(IllegalArgumentException.class, () -> new FunctionSignature(locator, new FunctionRevision(4), List.of(input), List.of(input)));
    }

    @Test
    void contextCancellationAndDiagnosticsRemainImmutable() {
        FunctionLocator locator = new FunctionLocator(new ServerResourceLocator(SERVER, FUNCTION_TYPE, "welcome"));
        FunctionInputMap inputs = new FunctionInputMap(Map.of(INPUT_ID, TypedValue.value(TEXT, "hello")));
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("nested", new ArrayList<>(List.of("one")));
        FunctionRuntimeContext context = new FunctionRuntimeContext(locator, new FunctionRevision(1), UUID.fromString("44444444-4444-4444-8444-444444444444"), inputs,
            FunctionCancellation.requested("caller"), attributes);
        attributes.put("changed", true);
        FunctionDiagnostic diagnostic = FunctionDiagnostic.error("FUNCTION.PARAMETER_REQUIRED", "parameter-validation", "A required parameter is missing.", INPUT_ID);

        assertFalse(context.cancellation().terminal());
        assertFalse(context.canonicalJson().contains("changed"));
        assertNotSame(attributes, context.attributes());
        assertThrows(UnsupportedOperationException.class, () -> context.attributes().clear());
        assertEquals("FUNCTION.PARAMETER_REQUIRED", diagnostic.code());
        assertTrue(diagnostic.canonicalJson().contains("parameterId"));
        assertThrows(IllegalArgumentException.class, () -> new FunctionDiagnostic("bad", FunctionDiagnostic.Severity.ERROR,
            FunctionDiagnostic.Phase.SEMANTIC, "stage", "message", "remediation", null, null, null));
    }
}

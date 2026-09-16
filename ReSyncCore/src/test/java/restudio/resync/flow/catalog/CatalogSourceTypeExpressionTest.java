package restudio.resync.flow.catalog;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogSourceTypeExpressionTest {
    private static final OwnerId OWNER = OwnerId.of("source.catalog");
    private static final ContractRef<ResourceTypeId> REQUESTED = ContractRef.of(
        OwnerId.of("source.resources"), ResourceTypeId.of("flow"));
    private static final ContractRef<ResourceTypeId> RESOLVED = ContractRef.of(
        OwnerId.of("canonical.resources"), ResourceTypeId.of("flow"));

    @Test
    void productionAndCoreResourceSyntaxNormalizeToTheAuthoritativeResolvedType() {
        List<ContractRef<ResourceTypeId>> requests = new ArrayList<>();
        CatalogSourceIngestor.ResourceTypeResolver resolver = request -> {
            requests.add(request);
            return Optional.of(RESOLVED);
        };

        TypeExpr production = parse("resource_reference<source.resources:flow>", resolver);
        TypeExpr core = parse("resource<source.resources:flow>", resolver);
        TypeExpr nested = parse("list<resource_reference<source.resources:flow>>", resolver);
        TypeExpr expected = TypeExpr.resource(TypeReference.of("canonical.resources", "flow"));

        assertEquals(expected, production);
        assertEquals(expected, core);
        assertEquals(TypeExpr.list(expected), nested);
        assertEquals(production.canonicalJson(), core.canonicalJson());
        assertEquals(List.of(REQUESTED, REQUESTED, REQUESTED), requests);
    }

    @Test
    void rejectsUnparameterizedAndOverparameterizedResourceReferences() {
        CatalogSourceIngestor.ResourceTypeResolver resolver = request -> Optional.of(request);

        for (String expression : List.of(
            "resource_reference",
            "resource",
            "resource_reference<source.resources:flow,builtin:string>",
            "resource<source.resources:flow,builtin:string>",
            "resource_reference<list<source.resources:flow>>",
            "resource_reference<source.resources:>")) {
            assertTypeError(expression, resolver);
        }
    }

    @Test
    void rejectsUnavailableResourceTypesWithoutAnyOrOpaqueFallback() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> parse("resource_reference<source.resources:missing>", request -> Optional.empty()));

        assertTrue(error.getMessage().startsWith("TYPE.UNKNOWN:"));
        assertTrue(error.getMessage().contains("source.resources/missing"));
    }

    private static TypeExpr parse(String expression, CatalogSourceIngestor.ResourceTypeResolver resolver) {
        return CatalogSourceIngestor.parseTypeExpression(expression, OWNER, resolver);
    }

    private static void assertTypeError(String expression, CatalogSourceIngestor.ResourceTypeResolver resolver) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> parse(expression, resolver), expression);
        assertTrue(error.getMessage().startsWith("TYPE.UNKNOWN:"), expression);
    }
}

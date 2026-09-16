package restudio.resync.server;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.inspector.InspectorOptionSource;
import restudio.resync.flow.inspector.OptionQuerySchemaV1;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MappedOptionQueryAuthorityTest {
    private static final ContractRef<CapabilityId> QUERY = ContractRef.of(OwnerId.of("query.owner"),
        CapabilityId.of("options"));
    private static final ContractRef<InspectorFieldId> FIRST = ContractRef.of(OwnerId.of("source.one"),
        InspectorFieldId.of("first"));
    private static final ContractRef<InspectorFieldId> SECOND = ContractRef.of(OwnerId.of("source.two"),
        InspectorFieldId.of("second"));

    @Test
    void keysAuthorityByTheFullSourceReferenceAndCapability() {
        OptionQueryAuthority.Source first = source(FIRST, "one");
        OptionQueryAuthority.Source second = source(SECOND, "two");
        MappedOptionQueryAuthority authority = new MappedOptionQueryAuthority(List.of(first, second));

        assertEquals(first, authority.require(FIRST, QUERY));
        assertEquals(second, authority.require(SECOND, QUERY));
        assertThrows(OptionQueryAuthority.Rejected.class, () -> authority.require(
            ContractRef.of(OwnerId.of("source.three"), FIRST.id()), QUERY));
    }

    @Test
    void rejectsOnlyDuplicateSourceAndCapabilityPairs() {
        OptionQueryAuthority.Source first = source(FIRST, "one");

        assertThrows(IllegalArgumentException.class, () -> new MappedOptionQueryAuthority(List.of(first, first)));
    }

    private static OptionQueryAuthority.Source source(ContractRef<InspectorFieldId> reference, String provider) {
        InspectorOptionSource descriptor = new InspectorOptionSource(reference.id(), "Options",
            "Provides authoritative values for the selected inspector field.",
            TypeExpr.named(TypeReference.of("builtin", "string")), OptionQuerySchemaV1.empty(), QUERY, 100,
            reference.id().value());
        return new OptionQueryAuthority.Source(reference, descriptor, provider, 1L, List.of());
    }
}

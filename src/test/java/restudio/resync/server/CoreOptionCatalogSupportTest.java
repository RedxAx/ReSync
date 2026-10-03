package restudio.resync.server;

import org.junit.jupiter.api.Test;
import restudio.resync.api.OptionCatalogItem;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoreOptionCatalogSupportTest {
    private static final ServerId SERVER = new ServerId(UUID.randomUUID());

    @Test
    void registeredEnumOptionsPreserveTheirTypedCatalogIdentity() {
        for (String typeId : List.of("material", "difficulty", "gamemode", "entity_type", "display_slot", "text_decoration")) {
            TypeExpr type = TypeExpr.named(TypeReference.of("builtin", typeId));
            for (String identity : List.of("stone", "any")) {
                assertEquals(TypedValue.value(type, identity),
                    CoreOptionCatalogSupport.value(type, SERVER, new OptionCatalogItem(identity)));
            }
        }
    }

    @Test
    void unsupportedAndNonScalarTypesRemainRejected() {
        for (TypeExpr type : List.of(
            TypeExpr.named(TypeReference.of("builtin", "unknown")),
            TypeExpr.named(TypeReference.of("builtin", "location")),
            TypeExpr.named(TypeReference.of("other", "material")),
            TypeExpr.list(TypeExpr.named(TypeReference.of("builtin", "material"))))) {
            assertThrows(OptionQueryAuthority.Rejected.class,
                () -> CoreOptionCatalogSupport.value(type, SERVER, new OptionCatalogItem("stone")));
        }
    }

    @Test
    void primitiveOptionsStillRequireCanonicalValues() {
        for (String[] invalid : List.of(new String[]{"boolean", "yes"}, new String[]{"integer", "01"},
            new String[]{"number", "1.0"}, new String[]{"uuid", "invalid"})) {
            TypeExpr type = TypeExpr.named(TypeReference.of("builtin", invalid[0]));
            assertThrows(OptionQueryAuthority.Rejected.class,
                () -> CoreOptionCatalogSupport.value(type, SERVER, new OptionCatalogItem(invalid[1])));
        }
    }
}

package restudio.resync.flow.graph;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphValidatorTypeCompatibilityTest {
    @Test
    void acceptsGenericSpecializationInheritanceAndImplicitStringCoercion() {
        TypeExpr player = builtin("player");
        TypeExpr entity = builtin("entity");
        TypeExpr number = builtin("number");
        TypeExpr itemstack = builtin("itemstack");
        TypeExpr string = builtin("string");
        TypeExpr variable = TypeExpr.named(TypeReference.of("type", "t"));

        assertTrue(GraphValidator.directlyAssignable(TypeExpr.list(player), TypeExpr.list(variable)));
        assertTrue(GraphValidator.directlyAssignable(variable, player));
        assertTrue(GraphValidator.directlyAssignable(player, entity));
        assertTrue(GraphValidator.directlyAssignable(number, string));
        assertTrue(GraphValidator.directlyAssignable(itemstack, string));
        assertFalse(GraphValidator.directlyAssignable(entity, player));
    }

    private static TypeExpr builtin(String id) {
        return TypeExpr.named(TypeReference.of("builtin", id));
    }
}

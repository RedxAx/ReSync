package restudio.resync.flow;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowTypeRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowTypeRefTest {
    @Test
    void genericTypesPreserveArgumentsAndCompatibility() {
        FlowTypeRef itemList = FlowTypeRef.parse("list<item>");
        FlowTypeRef materialList = FlowTypeRef.parse("list<material>");
        FlowTypeRef stringMap = FlowTypeRef.parse("map<string,list<item>>");

        assertEquals("map<string,list<item>>", stringMap.toString());
        assertTrue(materialList.isAssignableFrom(itemList));
        assertFalse(itemList.isAssignableFrom(materialList));
        assertTrue(stringMap.isResolved());
    }

    @Test
    void unknownTypesRemainExplicit() {
        FlowDataType unresolved = FlowDataType.fromString("example:missing");

        assertEquals("example:missing", unresolved.getId());
        assertEquals("example", unresolved.getOwner());
        assertFalse(unresolved.isResolved());
    }

    @Test
    void resourceKindsAreNominalReferenceArguments() {
        FlowTypeRef anyResource = FlowTypeRef.parse("resource_reference");
        FlowTypeRef trade = FlowTypeRef.parse("resource_reference<trade_profile>");
        FlowTypeRef loot = FlowTypeRef.parse("resource_reference<loot_table>");

        assertTrue(trade.isResolved());
        assertTrue(anyResource.isAssignableFrom(trade));
        assertTrue(trade.isAssignableFrom(trade));
        assertFalse(trade.isAssignableFrom(loot));
    }

    @Test
    void bareGenericTypesReceiveExplicitAnyArguments() {
        assertEquals("list<any>", FlowTypeRef.simple("list").normalizedGenerics().toString());
        assertEquals("map<any,any>", FlowTypeRef.simple("map").normalizedGenerics().toString());
        assertEquals("job_reference<any>", FlowTypeRef.simple("job_reference").normalizedGenerics().toString());
        assertEquals("list<map<any,any>>", FlowTypeRef.parse("list<map>").normalizedGenerics().toString());
    }

    @Test
    void typeVariablesRemainResolvedGenericPlaceholders() {
        FlowTypeRef variable = FlowTypeRef.parse("type:t");
        FlowTypeRef typedList = FlowTypeRef.parse("list<type:t>");

        assertTrue(variable.isResolved());
        assertTrue(variable.isTypeVariable());
        assertEquals("t", variable.getTypeVariableName());
        assertTrue(typedList.isResolved());
        assertTrue(typedList.isAssignableFrom(FlowTypeRef.parse("list<string>")));
    }
}

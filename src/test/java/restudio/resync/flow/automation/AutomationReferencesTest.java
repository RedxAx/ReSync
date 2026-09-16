package restudio.resync.flow.automation;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowResourceReference;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.flow.CompiledRuntimeValueCodec;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;
import restudio.resync.resources.ReSyncResourceCatalog;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AutomationReferencesTest {
    private static final ServerId SERVER = ServerId.deterministic("automation-reference-test");

    @Test
    void typedAutomationLocatorsPreserveExactDefinitionIdsAcrossAllFamilies() {
        for (String kind : List.of(ReSyncResourceCatalog.VARIABLE_DEFINITION, ReSyncResourceCatalog.TIMER_DEFINITION,
            ReSyncResourceCatalog.SCHEDULE_DEFINITION)) {
            ServerResourceLocator locator = new ServerResourceLocator(SERVER,
                ContractRef.of(OwnerId.of("builtin"), ResourceTypeId.of(kind)), "exact_" + kind);

            assertEquals("exact_" + kind, AutomationReferences.id(locator));
        }
    }

    @Test
    void registryReferencesEncodeAgainstBuiltinAutomationResourceTypes() {
        AutomationDefinitionRegistry registry = new AutomationDefinitionRegistry(null);
        List<AutomationDefinition> definitions = List.of(
            new VariableDefinition("typed_variable", "Typed Variable", "", FlowTypeRef.simple("string"),
                AutomationScope.SERVER, false, ""),
            new TimerDefinition("typed_timer", "Typed Timer", "", AutomationScope.SERVER, false,
                1D, TimerDefinition.TimeUnit.SECONDS, 0D),
            new ScheduleDefinition("typed_schedule", "Typed Schedule", "", ScheduleDefinition.TargetType.FLOW,
                "target", ScheduleDefinition.TimingMode.AFTER_DELAY, 1D, TimerDefinition.TimeUnit.SECONDS, 0D,
                "", "UTC", "", AutomationScope.SERVER, false, ScheduleDefinition.OverlapPolicy.SKIP,
                ScheduleDefinition.ExistingTaskPolicy.REPLACE, ScheduleDefinition.FailurePolicy.CONTINUE,
                ScheduleDefinition.OfflinePolicy.WAIT, ScheduleDefinition.MissedRunPolicy.RUN_ONCE)
        );

        for (AutomationDefinition definition : definitions) {
            String kind = kind(definition);
            TypeExpr type = TypeExpr.resource(TypeReference.of("builtin", kind));
            TypedValue encoded = CompiledRuntimeValueCodec.encode(SERVER, type, registry.reference(definition));

            assertEquals(TypedValue.State.LOCATOR, encoded.state());
            assertEquals(OwnerId.of("builtin"), encoded.locator().owner());
            assertEquals(ResourceTypeId.of(kind), encoded.locator().resourceType());
            assertEquals(definition.id(), encoded.locator().id());
        }
    }

    @Test
    void typedBoundaryStillRejectsWrongLegacyReferenceOwnerAcrossAllFamilies() {
        for (String kind : List.of(ReSyncResourceCatalog.VARIABLE_DEFINITION, ReSyncResourceCatalog.TIMER_DEFINITION,
            ReSyncResourceCatalog.SCHEDULE_DEFINITION)) {
            TypeExpr type = TypeExpr.resource(TypeReference.of("builtin", kind));

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CompiledRuntimeValueCodec.encode(SERVER, type, new FlowResourceReference(kind, "exact", "server")));

            assertEquals("RESOURCE_REFERENCE_TYPE_MISMATCH", failure.getMessage());
        }
    }

    private String kind(AutomationDefinition definition) {
        return switch (definition) {
            case VariableDefinition ignored -> ReSyncResourceCatalog.VARIABLE_DEFINITION;
            case TimerDefinition ignored -> ReSyncResourceCatalog.TIMER_DEFINITION;
            case ScheduleDefinition ignored -> ReSyncResourceCatalog.SCHEDULE_DEFINITION;
            default -> throw new IllegalArgumentException("Unsupported automation definition");
        };
    }
}

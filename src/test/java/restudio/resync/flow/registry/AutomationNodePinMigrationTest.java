package restudio.resync.flow.registry;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomationNodePinMigrationTest {
    @Test
    void loadsCompleteAutomationOutputPinMigrationsAndAuthoredEventTargets() throws Exception {
        NodeDefinitionLoader loader = new NodeDefinitionLoader();
        List<NodeDefinition> definitions;
        try (InputStream input = Files.newInputStream(Path.of("src", "main", "resources", "nodes", "automation.json"))) {
            definitions = loader.parseReplacement(input, "nodes/automation.json");
        }

        assertEquals(8, definitions.size());
        assertTrue(loader.getDiagnostics().stream().noneMatch(value -> value.severity() == NodeDefinitionDiagnostic.Severity.ERROR));
        assertTrue(definitions.stream().allMatch(definition -> "restudio.resync".equals(definition.getOwner())));

        Map<String, NodeDefinition> byId = definitions.stream().collect(Collectors.toMap(NodeDefinition::getId, value -> value));
        Map<String, Integer> targetVersions = Map.of(
            "automation.variable", 2,
            "automation.timer", 3,
            "automation.schedule", 2,
            "automation.scheduled_task", 2,
            "event.variable.changed", 2,
            "event.timer", 2,
            "event.scheduled_task", 2,
            "event.schedule", 2
        );
        Map<String, Integer> sourceVersions = Map.of(
            "automation.variable", 1,
            "automation.timer", 2,
            "automation.schedule", 1,
            "automation.scheduled_task", 1,
            "event.variable.changed", 1,
            "event.timer", 1,
            "event.scheduled_task", 1,
            "event.schedule", 1
        );
        Map<String, List<String>> expectedMappings = Map.of(
            "automation.variable", List.of(
                "input:flow->flow", "input:variable->variable", "input:action->action", "input:owner->owner",
                "input:value->value", "input:amount->amount", "output:flow->output_flow", "output:variable->output_variable",
                "output:value->output_value", "output:exists->exists", "output:variables->variables"),
            "automation.timer", List.of(
                "input:flow->flow", "input:timer->timer", "input:action->action", "input:owner->owner",
                "input:duration->duration", "input:unit->unit", "output:active->active", "output:paused->paused",
                "output:inactive->inactive", "output:timer->output_timer", "output:state->state", "output:remaining->remaining",
                "output:elapsed->elapsed", "output:duration->output_duration", "output:progress->progress",
                "output:progress_percent->progress_percent"),
            "automation.schedule", List.of(
                "input:flow->flow", "input:schedule->schedule", "input:owner->owner", "input:arguments->arguments",
                "output:scheduled->scheduled", "output:failed->failed", "output:schedule->output_schedule", "output:task->task",
                "output:success->success", "output:error_code->error_code", "output:message->message"),
            "automation.scheduled_task", List.of(
                "input:flow->flow", "input:action->action", "input:task->task", "input:schedule->schedule", "input:owner->owner",
                "output:active->active", "output:paused->paused", "output:inactive->inactive", "output:task->output_task",
                "output:success->success", "output:state->state", "output:remaining->remaining", "output:next_run->next_run",
                "output:last_run->last_run", "output:run_count->run_count", "output:last_result->last_result",
                "output:last_error->last_error"),
            "event.variable.changed", List.of(
                "input:variable->variable", "output:flow->flow", "output:variable->output_variable", "output:owner->owner",
                "output:old_value->old_value", "output:new_value->new_value"),
            "event.timer", List.of(
                "input:timer->timer", "input:event->event", "output:flow->flow", "output:timer->output_timer", "output:owner->owner",
                "output:state->state", "output:remaining->remaining", "output:elapsed->elapsed", "output:duration->output_duration",
                "output:progress->progress"),
            "event.scheduled_task", List.of(
                "input:schedule->schedule", "input:event->event", "output:flow->flow", "output:schedule->output_schedule",
                "output:task->task", "output:owner->owner", "output:target_type->target_type", "output:target->target",
                "output:fired_at->fired_at", "output:run_count->run_count", "output:function_result->function_result",
                "output:error->error"),
            "event.schedule", List.of(
                "input:schedule->schedule", "output:flow->flow", "output:schedule->output_schedule", "output:task->task",
                "output:owner->owner", "output:arguments->arguments", "output:fired_at->fired_at", "output:run_count->run_count")
        );
        Map<String, Integer> identityCounts = Map.of(
            "automation.variable", 8,
            "automation.timer", 14,
            "automation.schedule", 10,
            "automation.scheduled_task", 16,
            "event.variable.changed", 5,
            "event.timer", 8,
            "event.scheduled_task", 11,
            "event.schedule", 7
        );

        for (Map.Entry<String, Integer> entry : targetVersions.entrySet()) {
            NodeDefinition definition = byId.get(entry.getKey());
            assertNotNull(definition, entry.getKey());
            NodeDefinition.MigrationMapping migration = definition.getAuthoredPinMigration();
            assertNotNull(migration, entry.getKey());
            assertTrue(migration.complete(), entry.getKey());
            assertEquals(sourceVersions.get(entry.getKey()), migration.sourceSchemaVersion(), entry.getKey());
            assertEquals(entry.getValue(), migration.targetSchemaVersion(), entry.getKey());
            assertEquals(entry.getValue(), definition.getSchemaVersion(), entry.getKey());
            List<String> actualMappings = migration.pins().stream()
                .map(value -> value.direction().name().toLowerCase() + ":" + value.sourcePinId().value() + "->" + value.targetPinId().value())
                .toList();
            assertEquals(expectedMappings.get(entry.getKey()), actualMappings, entry.getKey());
            assertEquals(identityCounts.get(entry.getKey()), (int) actualMappings.stream()
                .filter(value -> value.substring(value.indexOf(':') + 1).split("->", 2)[0]
                    .equals(value.substring(value.indexOf("->") + 2)))
                .count(), entry.getKey());
        }

        Map<String, List<String>> eventMappings = Map.of(
            "event.variable.changed", List.of("event.variable->output_variable", "event.owner->owner", "event.oldValue->old_value", "event.newValue->new_value"),
            "event.timer", List.of("event.timer->output_timer", "event.owner->owner", "event.state->state", "event.remaining->remaining", "event.elapsed->elapsed", "event.duration->output_duration", "event.progress->progress"),
            "event.scheduled_task", List.of("event.schedule->output_schedule", "event.task->task", "event.owner->owner", "event.targetType->target_type", "event.target->target", "event.firedAt->fired_at", "event.runCount->run_count", "event.functionResult->function_result", "event.error->error"),
            "event.schedule", List.of("event.schedule->output_schedule", "event.task->task", "event.owner->owner", "event.arguments->arguments", "event.firedAt->fired_at", "event.runCount->run_count")
        );
        for (Map.Entry<String, List<String>> entry : eventMappings.entrySet()) {
            assertEquals(entry.getValue(), byId.get(entry.getKey()).getOutputMappings().stream()
                .map(value -> value.source() + "->" + value.target())
                .toList(), entry.getKey());
        }
    }
}

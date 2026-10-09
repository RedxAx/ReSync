package restudio.resync.flow.automation;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScheduleDefinitionTest {
    @Test
    void topLevelTargetDoesNotConflictWithTheScheduleIdentity() {
        JsonObject value = schedule("asd");
        value.addProperty("targetType", "function");
        value.addProperty("targetId", "heartbeat");

        ScheduleDefinition definition = ScheduleDefinition.from(value, "asd");

        assertEquals("heartbeat", definition.targetId());
    }

    @Test
    void blankTargetIdsAreAbsentDuringNestedTargetResolution() {
        JsonObject value = schedule("asd");
        value.addProperty("targetId", "   ");
        JsonObject target = new JsonObject();
        target.addProperty("type", "function");
        target.addProperty("id", " heartbeat ");
        value.add("target", target);

        ScheduleDefinition definition = ScheduleDefinition.from(value, "asd");

        assertEquals("heartbeat", definition.targetId());
    }

    @Test
    void absentTargetDoesNotReuseTheScheduleIdentity() {
        JsonObject value = schedule("asd");
        value.addProperty("targetId", " ");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> ScheduleDefinition.from(value, "asd"));

        assertEquals("Schedule target is required", failure.getMessage());
    }

    @Test
    void conflictingNonBlankTargetIdsAreRejectedAfterNormalization() {
        JsonObject value = schedule("asd");
        value.addProperty("targetId", "first");
        JsonObject target = new JsonObject();
        target.addProperty("type", "flow");
        target.addProperty("id", "second");
        value.add("target", target);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> ScheduleDefinition.from(value, "asd"));

        assertEquals("Schedule target ID fields conflict", failure.getMessage());
    }

    @Test
    void equivalentNonBlankTargetIdsAreComparedAfterTrimming() {
        JsonObject value = schedule("asd");
        value.addProperty("targetId", " heartbeat ");
        JsonObject target = new JsonObject();
        target.addProperty("type", "function");
        target.addProperty("id", "heartbeat");
        value.add("target", target);

        ScheduleDefinition definition = ScheduleDefinition.from(value, "asd");

        assertEquals("heartbeat", definition.targetId());
    }

    @Test
    void conflictingTypedTargetsCannotSelectAnotherGraphWithTheSameId() {
        JsonObject value = schedule("schedule");
        value.addProperty("targetType", "function");
        value.addProperty("targetId", "shared");
        JsonObject target = new JsonObject();
        target.addProperty("type", "flow");
        target.addProperty("id", "shared");
        value.add("target", target);

        assertThrows(IllegalArgumentException.class, () -> ScheduleDefinition.from(value, "schedule"));
    }

    private JsonObject schedule(String id) {
        JsonObject value = new JsonObject();
        value.addProperty("id", id);
        value.addProperty("timingMode", "after_delay");
        return value;
    }
}

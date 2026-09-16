package restudio.resync.flow.inspector;

import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.type.TypedValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public sealed interface InspectorCondition permits InspectorCondition.Always, InspectorCondition.Present, InspectorCondition.Equals, InspectorCondition.NotEquals, InspectorCondition.All, InspectorCondition.Any, InspectorCondition.Not {
    Set<InspectorFieldId> references();

    static InspectorCondition always() {
        return new Always();
    }

    static InspectorCondition present(InspectorFieldId field) {
        return new Present(field);
    }

    static InspectorCondition equalsValue(InspectorFieldId field, TypedValue value) {
        return new Equals(field, value);
    }

    static InspectorCondition notEquals(InspectorFieldId field, TypedValue value) {
        return new NotEquals(field, value);
    }

    static InspectorCondition all(List<InspectorCondition> conditions) {
        return new All(conditions);
    }

    static InspectorCondition any(List<InspectorCondition> conditions) {
        return new Any(conditions);
    }

    static InspectorCondition not(InspectorCondition condition) {
        return new Not(condition);
    }

    record Always() implements InspectorCondition {
        @Override
        public Set<InspectorFieldId> references() {
            return Set.of();
        }
    }

    record Present(InspectorFieldId field) implements InspectorCondition {
        public Present {
            Objects.requireNonNull(field, "field");
        }

        @Override
        public Set<InspectorFieldId> references() {
            return Set.of(field);
        }
    }

    record Equals(InspectorFieldId field, TypedValue value) implements InspectorCondition {
        public Equals {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(value, "value");
        }

        @Override
        public Set<InspectorFieldId> references() {
            return Set.of(field);
        }
    }

    record NotEquals(InspectorFieldId field, TypedValue value) implements InspectorCondition {
        public NotEquals {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(value, "value");
        }

        @Override
        public Set<InspectorFieldId> references() {
            return Set.of(field);
        }
    }

    record All(List<InspectorCondition> conditions) implements InspectorCondition {
        public All {
            conditions = InspectorSupport.list(conditions, "conditions");
        }

        @Override
        public Set<InspectorFieldId> references() {
            return referencesOf(conditions);
        }
    }

    record Any(List<InspectorCondition> conditions) implements InspectorCondition {
        public Any {
            conditions = InspectorSupport.list(conditions, "conditions");
        }

        @Override
        public Set<InspectorFieldId> references() {
            return referencesOf(conditions);
        }
    }

    record Not(InspectorCondition condition) implements InspectorCondition {
        public Not {
            Objects.requireNonNull(condition, "condition");
        }

        @Override
        public Set<InspectorFieldId> references() {
            return condition.references();
        }
    }

    private static Set<InspectorFieldId> referencesOf(List<InspectorCondition> conditions) {
        var references = new LinkedHashSet<InspectorFieldId>();
        for (var condition : conditions) {
            references.addAll(condition.references());
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(references));
    }
}

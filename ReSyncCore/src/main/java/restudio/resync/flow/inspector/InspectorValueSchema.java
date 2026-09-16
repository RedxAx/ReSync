package restudio.resync.flow.inspector;

import restudio.resync.flow.type.TypeExpr;

import java.util.List;
import java.util.Objects;

public record InspectorValueSchema(TypeExpr type, List<InspectorConstraint> constraints) {
    public InspectorValueSchema {
        type = Objects.requireNonNull(type, "type");
        constraints = InspectorSupport.list(constraints == null ? List.of() : constraints, "constraints");
        InspectorSupport.unique(constraints, InspectorConstraint::id, "value schema constraint");
    }

    public InspectorValueSchema(TypeExpr type) {
        this(type, List.of());
    }
}

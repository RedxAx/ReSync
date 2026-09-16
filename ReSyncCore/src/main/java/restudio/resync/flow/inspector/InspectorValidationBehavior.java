package restudio.resync.flow.inspector;

import java.util.EnumSet;
import java.util.List;

public record InspectorValidationBehavior(List<InspectorValidationPhase> phases, boolean preserveDraftOnFailure, boolean rejectUnknownFields) {
    public InspectorValidationBehavior {
        phases = InspectorSupport.list(phases == null ? List.of() : phases, "validation phases");
        if (phases.isEmpty()) {
            throw new IllegalArgumentException("Validation behavior needs at least one phase");
        }
        if (EnumSet.copyOf(phases).size() != phases.size()) {
            throw new IllegalArgumentException("Validation phases must be unique");
        }
    }

    public static InspectorValidationBehavior standard() {
        return new InspectorValidationBehavior(List.of(InspectorValidationPhase.SYNTACTIC, InspectorValidationPhase.SEMANTIC, InspectorValidationPhase.CAPABILITY), true, true);
    }
}

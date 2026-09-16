package restudio.resync.flow.inspector;

import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.type.TypeExpr;

import java.util.List;
import java.util.Objects;

public abstract sealed class InspectorField permits InspectorScalarField, InspectorSelectorField, InspectorListField, InspectorMapField, InspectorObjectField, InspectorTaggedUnionField, InspectorRepeatableField, InspectorBranchField, InspectorFunctionField, InspectorSummaryField, InspectorPreviewField {
    private final InspectorFieldId id;
    private final String title;
    private final String description;
    private final TypeExpr valueType;
    private final InspectorCondition visibility;
    private final InspectorFallback fallback;
    private final InspectorCapability editor;

    protected InspectorField(InspectorFieldId id, String title, String description, TypeExpr valueType, InspectorCondition visibility, InspectorFallback fallback, InspectorCapability editor) {
        this.id = Objects.requireNonNull(id, "field id");
        this.title = InspectorDescription.title(title, "field");
        this.description = InspectorDescription.description(this.title, description, "field");
        this.valueType = Objects.requireNonNull(valueType, "field value type");
        this.visibility = visibility == null ? InspectorCondition.always() : visibility;
        this.fallback = Objects.requireNonNull(fallback, "field fallback");
        if (this.fallback == InspectorFallback.REJECT) {
            throw new IllegalArgumentException("Field fallback cannot reject a field descriptor");
        }
        this.editor = Objects.requireNonNull(editor, "field editor");
    }

    public abstract Kind kind();

    public InspectorFieldId id() {
        return id;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public TypeExpr valueType() {
        return valueType;
    }

    public InspectorCondition visibility() {
        return visibility;
    }

    public InspectorFallback fallback() {
        return fallback;
    }

    public InspectorCapability editor() {
        return editor;
    }

    public List<InspectorField> children() {
        return List.of();
    }

    public enum Kind {
        SCALAR("scalar"),
        SELECTOR("selector"),
        LIST("list"),
        MAP("map"),
        OBJECT("object"),
        UNION("union"),
        REPEATABLE("repeatable"),
        BRANCH("branch"),
        FUNCTION("function"),
        SUMMARY("summary"),
        PREVIEW("preview");

        private final String wireName;

        Kind(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }
}

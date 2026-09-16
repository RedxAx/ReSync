package restudio.resync.flow.graph;

import restudio.resync.flow.identity.RepeatableElementId;
import restudio.resync.flow.identity.RepeatableGroupId;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RepeatableBinding {
    private final RepeatableGroupId groupId;
    private final boolean ordered;
    private final List<RepeatableElement> elements;
    private final OpaqueData unknown;

    public RepeatableBinding(RepeatableGroupId groupId, boolean ordered, List<RepeatableElement> elements, OpaqueData unknown) {
        this.groupId = Objects.requireNonNull(groupId, "groupId");
        this.ordered = ordered;
        this.elements = List.copyOf(elements != null ? elements : List.of());
        Set<RepeatableElementId> ids = new HashSet<>();
        this.elements.forEach(element -> {
            RepeatableElement value = Objects.requireNonNull(element, "element");
            if (!ids.add(value.elementId())) {
                throw new IllegalArgumentException("Duplicate repeatable element ID: " + value.elementId());
            }
        });
        this.unknown = unknown != null ? unknown : OpaqueData.empty();
        this.unknown.rejectKnownFields("groupId", "ordered", "elements");
    }

    public RepeatableBinding(RepeatableGroupId groupId, List<RepeatableElement> elements) {
        this(groupId, true, elements, OpaqueData.empty());
    }

    public RepeatableBinding(RepeatableGroupId groupId, boolean ordered, List<RepeatableElement> elements) {
        this(groupId, ordered, elements, OpaqueData.empty());
    }

    public RepeatableGroupId groupId() {
        return groupId;
    }

    public boolean ordered() {
        return ordered;
    }

    public List<RepeatableElement> elements() {
        return elements;
    }

    public OpaqueData unknown() {
        return unknown;
    }

    Map<String, Object> canonicalValue() {
        List<RepeatableElement> canonicalElements = ordered ? elements : elements.stream().sorted(Comparator.comparing(RepeatableElement::elementId)).toList();
        return OpaqueData.mergeKnownFields(unknown, Map.of("groupId", groupId.canonicalText(), "ordered", ordered, "elements", canonicalElements.stream().map(RepeatableElement::canonicalValue).toList()));
    }
}

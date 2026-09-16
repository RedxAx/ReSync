package restudio.resync.flow.validation;

import java.util.Objects;

import restudio.resync.flow.diagnostic.DiagnosticContext;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.CanonicalText;
import restudio.resync.flow.identity.LocalId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ServerResourceLocator;

public final class ValidationSubject implements Comparable<ValidationSubject> {
    private final ValidationTarget target;
    private final ContractRef<? extends LocalId> reference;
    private final ServerResourceLocator resource;
    private final NodeId nodeId;
    private final PinId pinId;
    private final String canonicalText;

    private ValidationSubject(
        ValidationTarget target,
        ContractRef<? extends LocalId> reference,
        ServerResourceLocator resource,
        NodeId nodeId,
        PinId pinId
    ) {
        this.target = Objects.requireNonNull(target, "Validation target is required");
        this.reference = reference;
        this.resource = resource;
        this.nodeId = nodeId;
        this.pinId = pinId;
        if (reference == null && resource == null) {
            throw new IllegalArgumentException("A typed validation reference or server resource is required");
        }
        if (pinId != null && nodeId == null) {
            throw new IllegalArgumentException("A validation pin requires a validation node");
        }
        this.canonicalText = canonical(target, reference, resource, nodeId, pinId);
    }

    public static ValidationSubject of(ValidationTarget target, ContractRef<? extends LocalId> reference) {
        return new ValidationSubject(target, Objects.requireNonNull(reference, "Validation reference is required"), null, null, null);
    }

    public static ValidationSubject of(
        ValidationTarget target,
        ContractRef<? extends LocalId> reference,
        ServerResourceLocator resource
    ) {
        return new ValidationSubject(target, Objects.requireNonNull(reference, "Validation reference is required"), resource, null, null);
    }

    public static ValidationSubject resource(ValidationTarget target, ServerResourceLocator resource) {
        return new ValidationSubject(target, null, Objects.requireNonNull(resource, "Validation resource is required"), null, null);
    }

    public static ValidationSubject node(
        ValidationTarget target,
        ContractRef<? extends LocalId> reference,
        ServerResourceLocator resource,
        NodeId nodeId
    ) {
        return new ValidationSubject(target, reference, resource, Objects.requireNonNull(nodeId, "Validation node ID is required"), null);
    }

    public static ValidationSubject pin(
        ValidationTarget target,
        ContractRef<? extends LocalId> reference,
        ServerResourceLocator resource,
        NodeId nodeId,
        PinId pinId
    ) {
        return new ValidationSubject(
            target,
            reference,
            resource,
            Objects.requireNonNull(nodeId, "Validation node ID is required"),
            Objects.requireNonNull(pinId, "Validation pin ID is required")
        );
    }

    public ValidationTarget target() {
        return target;
    }

    public ContractRef<? extends LocalId> reference() {
        return reference;
    }

    public ServerResourceLocator resource() {
        return resource;
    }

    public NodeId nodeId() {
        return nodeId;
    }

    public PinId pinId() {
        return pinId;
    }

    public String typeName() {
        if (resource != null) {
            return resource.type().id().canonicalText();
        }
        return reference.id().getClass().getSimpleName();
    }

    public String typeKey() {
        if (resource != null) {
            return resource.type().canonicalText();
        }
        return reference.owner().canonicalText() + "/" + reference.id().getClass().getName() + "/" + reference.id().canonicalText();
    }

    public boolean exactIdentityMatches(
        ServerResourceLocator reportedResource,
        NodeId reportedNode,
        PinId reportedPin
    ) {
        return Objects.equals(resource, reportedResource)
            && Objects.equals(nodeId, reportedNode)
            && Objects.equals(pinId, reportedPin);
    }

    public String canonicalText() {
        return canonicalText;
    }

    DiagnosticContext diagnosticContext(DiagnosticContext base) {
        DiagnosticContext normalized = base == null ? DiagnosticContext.empty() : base;
        if (resource != null && normalized.resource() != null && !resource.equals(normalized.resource())) {
            throw new IllegalArgumentException("Validation subject resource does not match diagnostic context");
        }
        if (nodeId != null && normalized.nodeId() != null && !nodeId.equals(normalized.nodeId())) {
            throw new IllegalArgumentException("Validation subject node does not match diagnostic context");
        }
        if (pinId != null && normalized.pinId() != null && !pinId.equals(normalized.pinId())) {
            throw new IllegalArgumentException("Validation subject pin does not match diagnostic context");
        }
        ServerResourceLocator subjectResource = normalized.resource() == null ? resource : normalized.resource();
        NodeId subjectNode = normalized.nodeId() == null ? nodeId : normalized.nodeId();
        PinId subjectPin = normalized.pinId() == null ? pinId : normalized.pinId();
        return new DiagnosticContext(
            normalized.serverId(),
            subjectResource,
            subjectNode,
            subjectPin,
            normalized.catalogGeneration(),
            normalized.provenance()
        );
    }

    @Override
    public int compareTo(ValidationSubject other) {
        return CanonicalText.compare(canonicalText, other.canonicalText);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof ValidationSubject other)) {
            return false;
        }
        return target == other.target
            && Objects.equals(reference, other.reference)
            && Objects.equals(resource, other.resource)
            && Objects.equals(nodeId, other.nodeId)
            && Objects.equals(pinId, other.pinId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(target, reference, resource, nodeId, pinId);
    }

    @Override
    public String toString() {
        return canonicalText;
    }

    private static String canonical(
        ValidationTarget target,
        ContractRef<? extends LocalId> reference,
        ServerResourceLocator resource,
        NodeId nodeId,
        PinId pinId
    ) {
        StringBuilder value = new StringBuilder(target.wireName());
        if (reference != null) {
            value.append("|reference=")
                .append(reference.id().getClass().getName())
                .append(':')
                .append(reference.canonicalText());
        }
        if (resource != null) {
            value.append("|resource=").append(resource.canonicalText());
        }
        if (nodeId != null) {
            value.append("|node=").append(nodeId.canonicalText());
        }
        if (pinId != null) {
            value.append("|pin=").append(pinId.canonicalText());
        }
        return value.toString();
    }
}

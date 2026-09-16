package restudio.resync.flow.protocol;

import java.util.Objects;

public record ResourceCreateResult(ResourceDocument<?> resource, ResourceDocument<?> projectMetadata,
                                   ResourcePresentationIntent presentation) {
    public ResourceCreateResult {
        resource = requireLive(resource, "resource");
        projectMetadata = requireLive(projectMetadata, "projectMetadata");
        presentation = Objects.requireNonNull(presentation, "presentation");
        if (!resource.resource().serverId().equals(projectMetadata.resource().serverId())
            || !resource.mutationId().equals(projectMetadata.mutationId())) {
            throw new IllegalArgumentException("Aggregate create documents must share server and mutation identity");
        }
        if (!"project_metadata".equals(projectMetadata.resource().resourceType().value())
            || !"restudio.resync".equals(projectMetadata.resource().owner().canonicalText())
            || !projectMetadata.resource().serverId().canonicalText().equals(projectMetadata.resource().id())) {
            throw new IllegalArgumentException("Aggregate create result requires canonical project metadata");
        }
        if (resource.resource().equals(projectMetadata.resource())) {
            throw new IllegalArgumentException("Aggregate create result requires distinct resource documents");
        }
    }

    private static ResourceDocument<?> requireLive(ResourceDocument<?> document, String name) {
        document = Objects.requireNonNull(document, name);
        if (document.deleted()) {
            throw new IllegalArgumentException(name + " must be live");
        }
        return document;
    }
}

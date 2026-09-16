package restudio.resync.flow.protocol;

public sealed interface ResourceOperation permits ResourceListRequest, ResourceQueryRequest, ResourceLoadRequest, ResourceCreateRequest,
    ResourceSaveRequest, ResourceRenameRequest, ResourceMoveRequest, ResourceDuplicateRequest, ResourceActivateRequest,
    ResourceDeleteRequest, ResourceSubscribeRequest {
    ResourceOperationKind kind();
}

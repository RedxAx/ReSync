package restudio.resync.permissions;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class LuckPermsManagementContract {
    public static final int VERSION = 1;
    public static final String CHANNEL_ID = "luckperms_management";

    private LuckPermsManagementContract() {
    }

    public enum Action {
        OVERVIEW,
        USERS,
        GROUPS,
        TRACKS,
        SUBJECT,
        PREVIEW,
        SAVE
    }

    public enum SubjectType {
        USER,
        GROUP
    }

    public enum EntityType {
        USER,
        GROUP,
        TRACK
    }

    public enum NodeKind {
        PERMISSION,
        INHERITANCE,
        PREFIX,
        SUFFIX,
        META,
        DISPLAY_NAME,
        WEIGHT,
        UNKNOWN
    }

    public record PageRequest(String cursor, int size, String query) {
        public PageRequest {
            cursor = normalize(cursor);
            size = Math.clamp(size <= 0 ? 50 : size, 1, 200);
            query = normalize(query);
        }
    }

    public record SubjectRef(SubjectType type, String id) {
        public SubjectRef {
            type = type == null ? SubjectType.USER : type;
            id = normalize(id);
        }
    }

    public record Request(
        int version,
        String requestId,
        Action action,
        PageRequest page,
        SubjectRef subject,
        PreviewRequest preview,
        ChangeSet changes
    ) {
        public Request {
            version = VERSION;
            requestId = normalize(requestId);
            action = action == null ? Action.OVERVIEW : action;
        }
    }

    public record Response(
        int version,
        String requestId,
        Action action,
        boolean success,
        String message,
        long revision,
        Overview overview,
        UserPage users,
        GroupPage groups,
        List<TrackDetail> tracks,
        SubjectDetail subject,
        EffectivePreview preview,
        SaveResult save,
        Invalidation invalidation
    ) {
        public Response {
            version = VERSION;
            requestId = normalize(requestId);
            action = action == null ? Action.OVERVIEW : action;
            message = normalize(message);
            tracks = immutable(tracks);
        }
    }

    public record Overview(
        boolean available,
        String serverId,
        String serverName,
        String version,
        long loadedUsers,
        long knownUsers,
        long onlineUsers,
        long groups,
        long tracks,
        long revision,
        long lastChangedAt,
        List<AuditEntry> audit
    ) {
        public Overview {
            serverId = normalize(serverId);
            serverName = normalize(serverName);
            version = normalize(version);
            audit = immutable(audit);
        }
    }

    public record AuditEntry(long changedAt, String action, String target, String actor, boolean success, String detail) {
        public AuditEntry {
            action = normalize(action);
            target = normalize(target);
            actor = normalize(actor);
            detail = normalize(detail);
        }
    }

    public record UserSummary(
        String uniqueId,
        String username,
        String primaryGroup,
        boolean online,
        int directNodes,
        String prefix,
        String suffix,
        long lastSeen
    ) {
        public UserSummary {
            uniqueId = normalize(uniqueId);
            username = normalize(username);
            primaryGroup = normalize(primaryGroup);
            prefix = normalize(prefix);
            suffix = normalize(suffix);
        }
    }

    public record GroupSummary(String name, String displayName, Integer weight, int directNodes, int members) {
        public GroupSummary {
            name = normalize(name);
            displayName = normalize(displayName);
        }
    }

    public record UserPage(List<UserSummary> items, String nextCursor, boolean hasMore, long total, long revision) {
        public UserPage {
            items = immutable(items);
            nextCursor = normalize(nextCursor);
        }
    }

    public record GroupPage(List<GroupSummary> items, String nextCursor, boolean hasMore, long total, long revision) {
        public GroupPage {
            items = immutable(items);
            nextCursor = normalize(nextCursor);
        }
    }

    public record TrackDetail(String name, long revision, List<String> groups) {
        public TrackDetail {
            name = normalize(name);
            groups = immutable(groups);
        }
    }

    public record NodeData(
        String id,
        NodeKind kind,
        String key,
        boolean value,
        Integer priority,
        Map<String, List<String>> contexts,
        Long expiresAt
    ) {
        public NodeData {
            id = normalize(id);
            kind = kind == null ? NodeKind.UNKNOWN : kind;
            key = normalize(key);
            contexts = immutableMap(contexts);
        }
    }

    public record SubjectDetail(
        long revision,
        SubjectRef subject,
        String name,
        String primaryGroup,
        Integer weight,
        List<NodeData> directNodes
    ) {
        public SubjectDetail {
            name = normalize(name);
            primaryGroup = normalize(primaryGroup);
            directNodes = immutable(directNodes);
        }
    }

    public record PreviewRequest(
        String requestId,
        SubjectRef subject,
        String permission,
        Map<String, List<String>> contexts,
        ChangeSet staged
    ) {
        public PreviewRequest {
            requestId = normalize(requestId);
            permission = normalize(permission);
            contexts = immutableMap(contexts);
        }
    }

    public record EffectivePreview(
        String requestId,
        SubjectRef subject,
        String permission,
        Map<String, List<String>> contexts,
        boolean allowed,
        boolean resolved,
        List<PreviewMatch> matches
    ) {
        public EffectivePreview {
            requestId = normalize(requestId);
            permission = normalize(permission);
            contexts = immutableMap(contexts);
            matches = immutable(matches);
        }
    }

    public record PreviewMatch(NodeData node, SubjectRef source, List<String> inheritancePath, boolean effective, String explanation) {
        public PreviewMatch {
            inheritancePath = immutable(inheritancePath);
            explanation = normalize(explanation);
        }
    }

    public record ChangeSet(
        String operationId,
        List<SubjectChange> subjects,
        List<TrackChange> tracks,
        List<EntityCreate> creates,
        List<EntityDelete> deletes
    ) {
        public ChangeSet {
            operationId = normalize(operationId);
            subjects = immutable(subjects);
            tracks = immutable(tracks);
            creates = immutable(creates);
            deletes = immutable(deletes);
        }

        public static ChangeSet empty() {
            return new ChangeSet("", List.of(), List.of(), List.of(), List.of());
        }
    }

    public record SubjectChange(
        SubjectRef subject,
        long baseRevision,
        String name,
        String primaryGroup,
        Integer weight,
        List<NodeData> nodes
    ) {
        public SubjectChange {
            name = normalize(name);
            primaryGroup = normalize(primaryGroup);
            nodes = immutable(nodes);
        }
    }

    public record TrackChange(String name, long baseRevision, List<String> groups) {
        public TrackChange {
            name = normalize(name);
            groups = immutable(groups);
        }
    }

    public record EntityCreate(EntityType type, String id, String username) {
        public EntityCreate {
            type = type == null ? EntityType.GROUP : type;
            id = normalize(id);
            username = normalize(username);
        }
    }

    public record EntityDelete(EntityType type, String id) {
        public EntityDelete {
            type = type == null ? EntityType.GROUP : type;
            id = normalize(id);
        }
    }

    public record SaveResult(
        String operationId,
        boolean applied,
        long revision,
        List<Conflict> conflicts,
        List<AppliedEntity> entities
    ) {
        public SaveResult {
            operationId = normalize(operationId);
            conflicts = immutable(conflicts);
            entities = immutable(entities);
        }
    }

    public record Conflict(EntityType type, String id, long expectedRevision, long actualRevision, String message) {
        public Conflict {
            type = type == null ? EntityType.GROUP : type;
            id = normalize(id);
            message = normalize(message);
        }
    }

    public record AppliedEntity(EntityType type, String id, long revision, boolean success, String message) {
        public AppliedEntity {
            type = type == null ? EntityType.GROUP : type;
            id = normalize(id);
            message = normalize(message);
        }
    }

    public record Invalidation(long revision, Set<EntityType> scopes, List<String> ids, String source) {
        public Invalidation {
            scopes = scopes == null || scopes.isEmpty() ? Set.of() : Set.copyOf(scopes);
            ids = immutable(ids);
            source = normalize(source);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null || values.isEmpty() ? List.of() : List.copyOf(values);
    }

    private static Map<String, List<String>> immutableMap(Map<String, List<String>> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return values.entrySet().stream().collect(Collectors.toUnmodifiableMap(
            entry -> normalize(entry.getKey()),
            entry -> immutable(entry.getValue())
        ));
    }
}

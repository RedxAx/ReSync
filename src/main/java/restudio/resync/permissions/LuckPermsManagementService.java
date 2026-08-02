package restudio.resync.permissions;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.group.GroupCreateEvent;
import net.luckperms.api.event.group.GroupDeleteEvent;
import net.luckperms.api.event.node.NodeMutateEvent;
import net.luckperms.api.event.track.mutate.TrackMutateEvent;
import net.luckperms.api.model.PermissionHolder;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.ChatMetaNode;
import net.luckperms.api.node.types.DisplayNameNode;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.MetaNode;
import net.luckperms.api.node.types.PermissionNode;
import net.luckperms.api.node.types.PrefixNode;
import net.luckperms.api.node.types.SuffixNode;
import net.luckperms.api.node.types.WeightNode;
import net.luckperms.api.query.QueryMode;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.track.Track;
import net.luckperms.api.util.Tristate;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import restudio.resync.permissions.LuckPermsManagementContract.Action;
import restudio.resync.permissions.LuckPermsManagementContract.AppliedEntity;
import restudio.resync.permissions.LuckPermsManagementContract.AuditEntry;
import restudio.resync.permissions.LuckPermsManagementContract.ChangeSet;
import restudio.resync.permissions.LuckPermsManagementContract.Conflict;
import restudio.resync.permissions.LuckPermsManagementContract.EffectivePreview;
import restudio.resync.permissions.LuckPermsManagementContract.EntityCreate;
import restudio.resync.permissions.LuckPermsManagementContract.EntityDelete;
import restudio.resync.permissions.LuckPermsManagementContract.EntityType;
import restudio.resync.permissions.LuckPermsManagementContract.GroupPage;
import restudio.resync.permissions.LuckPermsManagementContract.GroupSummary;
import restudio.resync.permissions.LuckPermsManagementContract.Invalidation;
import restudio.resync.permissions.LuckPermsManagementContract.NodeData;
import restudio.resync.permissions.LuckPermsManagementContract.NodeKind;
import restudio.resync.permissions.LuckPermsManagementContract.Overview;
import restudio.resync.permissions.LuckPermsManagementContract.PageRequest;
import restudio.resync.permissions.LuckPermsManagementContract.PreviewMatch;
import restudio.resync.permissions.LuckPermsManagementContract.PreviewRequest;
import restudio.resync.permissions.LuckPermsManagementContract.Request;
import restudio.resync.permissions.LuckPermsManagementContract.Response;
import restudio.resync.permissions.LuckPermsManagementContract.SaveResult;
import restudio.resync.permissions.LuckPermsManagementContract.SubjectChange;
import restudio.resync.permissions.LuckPermsManagementContract.SubjectDetail;
import restudio.resync.permissions.LuckPermsManagementContract.SubjectRef;
import restudio.resync.permissions.LuckPermsManagementContract.SubjectType;
import restudio.resync.permissions.LuckPermsManagementContract.TrackChange;
import restudio.resync.permissions.LuckPermsManagementContract.TrackDetail;
import restudio.resync.permissions.LuckPermsManagementContract.UserPage;
import restudio.resync.permissions.LuckPermsManagementContract.UserSummary;
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class LuckPermsManagementService implements AutoCloseable {
    private static final int AUDIT_LIMIT = 50;
    private static final int COMPLETED_SAVE_LIMIT = 256;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final JavaPlugin plugin;
    private final Path operationJournal;
    private final AtomicLong revision = new AtomicLong(1);
    private final Map<String, Long> entityRevisions = new ConcurrentHashMap<>();
    private final Deque<AuditEntry> audit = new ArrayDeque<>();
    private final Deque<String> completedSaveOrder = new ArrayDeque<>();
    private final Map<String, SaveResult> completedSaves = new ConcurrentHashMap<>();
    private final List<Consumer<Invalidation>> listeners = new CopyOnWriteArrayList<>();
    private final List<EventSubscription<?>> subscriptions = new ArrayList<>();
    private final Object saveLock = new Object();
    private CompletableFuture<Void> saveTail = CompletableFuture.completedFuture(null);
    private volatile long lastChangedAt = System.currentTimeMillis();

    private record PermissionSource(SubjectRef source, List<String> path, String explanation) {
    }

    private record SubjectState(SubjectRef subject, UUID uniqueId, String username, String primaryGroup, List<Node> nodes, List<Node> transientNodes) {
    }

    private record TrackState(String name, boolean existed, List<String> groups) {
    }

    static record Compensated<T>(T result, Supplier<CompletableFuture<Void>> rollback) {
    }

    static final class CompensationFailure extends RuntimeException {
        private final boolean rollbackFailed;
        private final List<?> applied;

        private CompensationFailure(Throwable cause, boolean rollbackFailed, List<?> applied) {
            super(cause);
            this.rollbackFailed = rollbackFailed;
            this.applied = List.copyOf(applied);
        }

        boolean rollbackFailed() {
            return rollbackFailed;
        }

        @SuppressWarnings("unchecked")
        <T> List<T> applied() {
            return (List<T>) applied;
        }
    }

    public LuckPermsManagementService(JavaPlugin plugin) {
        this.plugin = plugin;
        operationJournal = plugin.getDataFolder().toPath().resolve("runtime").resolve("luckperms-operations.json");
        loadCompleted();
        subscribeEvents();
    }

    public void addListener(Consumer<Invalidation> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public CompletableFuture<Response> handle(Request request, String actor) {
        if (request == null || request.version() != LuckPermsManagementContract.VERSION) {
            return CompletableFuture.completedFuture(error(request, "Unsupported Permission Request"));
        }
        if (luckPerms() == null) {
            return CompletableFuture.completedFuture(error(request, "LuckPerms Is Not Available On This Server"));
        }
        return switch (request.action()) {
            case OVERVIEW -> overview(request);
            case USERS -> users(request);
            case GROUPS -> groups(request);
            case TRACKS -> tracks(request);
            case SUBJECT -> subject(request);
            case PREVIEW -> preview(request);
            case SAVE -> save(request, actor == null || actor.isBlank() ? "Remotely" : actor);
        };
    }

    private CompletableFuture<Response> overview(Request request) {
        LuckPerms luckPerms = requireLuckPerms();
        CompletableFuture<Void> groups = luckPerms.getGroupManager().loadAllGroups();
        CompletableFuture<Void> tracks = luckPerms.getTrackManager().loadAllTracks();
        CompletableFuture<List<UUID>> users = knownUserIds(luckPerms);
        return CompletableFuture.allOf(groups, tracks, users).thenApply(ignored -> {
            Overview overview = new Overview(true, "", Bukkit.getServer().getName(), pluginVersion(),
                luckPerms.getUserManager().getLoadedUsers().size(), users.join().size(), Bukkit.getOnlinePlayers().size(),
                luckPerms.getGroupManager().getLoadedGroups().size(), luckPerms.getTrackManager().getLoadedTracks().size(),
                revision.get(), lastChangedAt, auditSnapshot());
            return response(request, "Permissions Ready", overview, null, null, null, null, null, null);
        });
    }

    private CompletableFuture<Response> users(Request request) {
        LuckPerms luckPerms = requireLuckPerms();
        PageRequest page = request.page() == null ? new PageRequest("", 50, "") : request.page();
        return knownUserIds(luckPerms).thenCompose(ordered -> {
            if (page.query().isBlank()) {
                int offset = offset(page.cursor(), ordered.size());
                int end = Math.min(ordered.size(), offset + page.size());
                return loadUserSummaries(luckPerms, ordered.subList(offset, end)).thenApply(summaries -> {
                    UserPage result = new UserPage(summaries, end < ordered.size() ? Integer.toString(end) : "", end < ordered.size(),
                        ordered.size(), revision.get());
                    return response(request, "Users Loaded", null, result, null, null, null, null, null);
                });
            }
            List<CompletableFuture<UserSummary>> summaries = ordered.stream()
                .map(id -> loadUserSummary(luckPerms, id)).toList();
            return CompletableFuture.allOf(summaries.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
                String query = page.query().toLowerCase(Locale.ROOT);
                List<UserSummary> filtered = summaries.stream().map(CompletableFuture::join)
                    .filter(user -> query.isBlank() || user.uniqueId().toLowerCase(Locale.ROOT).contains(query)
                        || user.username().toLowerCase(Locale.ROOT).contains(query)
                        || user.primaryGroup().toLowerCase(Locale.ROOT).contains(query))
                    .toList();
                int offset = offset(page.cursor(), filtered.size());
                int end = Math.min(filtered.size(), offset + page.size());
                List<UserSummary> items = filtered.subList(offset, end);
                UserPage result = new UserPage(items, end < filtered.size() ? Integer.toString(end) : "", end < filtered.size(),
                    filtered.size(), revision.get());
                return response(request, "Users Loaded", null, result, null, null, null, null, null);
            });
        });
    }

    private CompletableFuture<List<UserSummary>> loadUserSummaries(LuckPerms luckPerms, List<UUID> ids) {
        List<CompletableFuture<UserSummary>> summaries = ids.stream()
            .map(id -> loadUserSummary(luckPerms, id)).toList();
        return CompletableFuture.allOf(summaries.toArray(CompletableFuture[]::new))
            .thenApply(ignored -> summaries.stream().map(CompletableFuture::join).toList());
    }

    private CompletableFuture<List<UUID>> knownUserIds(LuckPerms luckPerms) {
        return luckPerms.getUserManager().getUniqueUsers().thenApply(stored -> mergeUserIds(stored,
            Stream.concat(luckPerms.getUserManager().getLoadedUsers().stream().map(User::getUniqueId),
                Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId)).toList()));
    }

    static List<UUID> mergeUserIds(Collection<UUID> stored, Collection<UUID> loaded) {
        Set<UUID> users = new TreeSet<>(Comparator.comparing(UUID::toString));
        if (stored != null) {
            users.addAll(stored);
        }
        if (loaded != null) {
            users.addAll(loaded);
        }
        return List.copyOf(users);
    }

    private CompletableFuture<UserSummary> loadUserSummary(LuckPerms luckPerms, UUID uniqueId) {
        User loaded = luckPerms.getUserManager().getUser(uniqueId);
        return loaded != null ? CompletableFuture.completedFuture(userSummary(loaded))
            : luckPerms.getUserManager().loadUser(uniqueId).thenApply(this::userSummary);
    }

    private CompletableFuture<Response> groups(Request request) {
        LuckPerms luckPerms = requireLuckPerms();
        PageRequest page = request.page() == null ? new PageRequest("", 50, "") : request.page();
        return luckPerms.getGroupManager().loadAllGroups().thenApply(ignored -> {
            String query = page.query().toLowerCase(Locale.ROOT);
            List<GroupSummary> filtered = luckPerms.getGroupManager().getLoadedGroups().stream()
                .sorted(Comparator.comparing(Group::getName, String.CASE_INSENSITIVE_ORDER))
                .map(this::groupSummary)
                .filter(group -> query.isBlank() || group.name().toLowerCase(Locale.ROOT).contains(query)
                    || group.displayName().toLowerCase(Locale.ROOT).contains(query))
                .toList();
            int offset = offset(page.cursor(), filtered.size());
            int end = Math.min(filtered.size(), offset + page.size());
            GroupPage result = new GroupPage(filtered.subList(offset, end), end < filtered.size() ? Integer.toString(end) : "",
                end < filtered.size(), filtered.size(), revision.get());
            return response(request, "Groups Loaded", null, null, result, null, null, null, null);
        });
    }

    private CompletableFuture<Response> tracks(Request request) {
        LuckPerms luckPerms = requireLuckPerms();
        return luckPerms.getTrackManager().loadAllTracks().thenApply(ignored -> {
            List<TrackDetail> tracks = luckPerms.getTrackManager().getLoadedTracks().stream()
                .sorted(Comparator.comparing(Track::getName, String.CASE_INSENSITIVE_ORDER))
                .map(track -> new TrackDetail(track.getName(), entityRevision(EntityType.TRACK, track.getName()), track.getGroups()))
                .toList();
            return response(request, "Tracks Loaded", null, null, null, tracks, null, null, null);
        });
    }

    private CompletableFuture<Response> subject(Request request) {
        if (request.subject() == null || request.subject().id().isBlank()) {
            return CompletableFuture.completedFuture(error(request, "Choose A User Or Group"));
        }
        return loadSubject(request.subject()).thenApply(holder -> {
            if (holder == null) {
                return error(request, "Permission Holder Was Not Found");
            }
            SubjectDetail result = subjectDetail(request.subject(), holder);
            return response(request, "Permissions Loaded", null, null, null, null, result, null, null);
        });
    }

    private CompletableFuture<Response> preview(Request request) {
        PreviewRequest preview = request.preview();
        if (preview == null || preview.subject() == null || preview.permission().isBlank()) {
            return CompletableFuture.completedFuture(error(request, "Choose A Permission To Check"));
        }
        LuckPerms luckPerms = requireLuckPerms();
        return loadSubject(preview.subject()).thenApply(holder -> {
            if (holder == null) {
                return error(request, "Permission Holder Was Not Found");
            }
            ImmutableContextSet contexts = contexts(preview.contexts());
            QueryOptions queryOptions = luckPerms.getContextManager().queryOptionsBuilder(QueryMode.CONTEXTUAL).context(contexts).build();
            Tristate check = holder.getCachedData().getPermissionData(queryOptions).checkPermission(preview.permission());
            List<PreviewMatch> matches = previewMatches(luckPerms, holder, preview.subject(), preview.permission(), contexts, queryOptions, check);
            EffectivePreview result = new EffectivePreview(preview.requestId(), preview.subject(), preview.permission(), preview.contexts(),
                check.asBoolean(), check != Tristate.UNDEFINED, matches);
            return response(request, "Permission Checked", null, null, null, null, null, result, null);
        });
    }

    private List<PreviewMatch> previewMatches(LuckPerms luckPerms, PermissionHolder holder, SubjectRef subject, String permission,
                                              ImmutableContextSet contexts, QueryOptions queryOptions, Tristate result) {
        Set<PermissionNode> resolved = holder.resolveInheritedNodes(queryOptions).stream().filter(PermissionNode.class::isInstance)
            .map(PermissionNode.class::cast).filter(node -> permissionMatches(node.getKey(), permission))
            .collect(Collectors.toSet());
        Map<PermissionNode, PermissionSource> sources = new LinkedHashMap<>();
        permissionNodes(holder, permission).forEach(node -> sources.putIfAbsent(node,
            new PermissionSource(subject, List.of("Direct"), "Direct Permission")));

        Map<String, Group> inherited = holder.getInheritedGroups(queryOptions).stream()
            .collect(Collectors.toMap(Group::getName, group -> group, (left, right) -> left, LinkedHashMap::new));
        Deque<Map.Entry<Group, List<String>>> queue = new ArrayDeque<>();
        inheritanceNodes(holder, contexts).forEach(node -> {
            Group group = inherited.get(node.getGroupName());
            if (group != null) {
                queue.add(Map.entry(group, List.of(subject.id(), group.getName())));
            }
        });
        Set<String> visited = new HashSet<>();
        while (!queue.isEmpty() && visited.size() < 256) {
            Map.Entry<Group, List<String>> visit = queue.removeFirst();
            Group group = visit.getKey();
            if (!visited.add(group.getName())) {
                continue;
            }
            SubjectRef source = new SubjectRef(SubjectType.GROUP, group.getName());
            permissionNodes(group, permission).forEach(node -> sources.putIfAbsent(node,
                new PermissionSource(source, visit.getValue(), "Inherited From " + group.getName())));
            inheritanceNodes(group, contexts).forEach(node -> {
                Group parent = inherited.get(node.getGroupName());
                if (parent != null && !visited.contains(parent.getName())) {
                    List<String> path = new ArrayList<>(visit.getValue());
                    path.add(parent.getName());
                    queue.add(Map.entry(parent, List.copyOf(path)));
                }
            });
        }
        return sources.entrySet().stream().filter(entry -> resolved.contains(entry.getKey())).map(entry -> {
            PermissionNode node = entry.getKey();
            PermissionSource source = entry.getValue();
            boolean effective = result != Tristate.UNDEFINED && node.getValue() == result.asBoolean();
            return new PreviewMatch(nodeData(node), source.source(), source.path(), effective, source.explanation());
        }).sorted(Comparator.comparing((PreviewMatch match) -> match.inheritancePath().size())
            .thenComparing(match -> match.source().id(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(match -> match.node().key(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(match -> match.node().id())).toList();
    }

    private List<PermissionNode> permissionNodes(PermissionHolder holder, String permission) {
        return holder.data().toCollection().stream().filter(PermissionNode.class::isInstance).map(PermissionNode.class::cast)
            .filter(node -> !node.hasExpired() && permissionMatches(node.getKey(), permission))
            .sorted(Comparator.comparing(PermissionNode::getKey, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(PermissionNode::getValue).thenComparing(node -> nodeData(node).id())).toList();
    }

    private List<InheritanceNode> inheritanceNodes(PermissionHolder holder, ImmutableContextSet contexts) {
        return holder.data().toCollection().stream().filter(InheritanceNode.class::isInstance).map(InheritanceNode.class::cast)
            .filter(node -> !node.hasExpired() && node.getContexts().isSatisfiedBy(contexts))
            .sorted(Comparator.comparing(InheritanceNode::getGroupName, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    private CompletableFuture<Response> save(Request request, String actor) {
        synchronized (saveLock) {
            CompletableFuture<Response> result = saveTail.handle((ignored, failure) -> null)
                .thenCompose(ignored -> performSave(request, actor));
            saveTail = result.handle((ignored, failure) -> null);
            return result;
        }
    }

    private CompletableFuture<Response> performSave(Request request, String actor) {
        ChangeSet changes = request.changes() == null ? ChangeSet.empty() : request.changes();
        if (changes.operationId().isBlank()) {
            return CompletableFuture.completedFuture(error(request, "Permission Save Operation Is Missing"));
        }
        SaveResult completed = completedSaves.get(changes.operationId());
        if (completed != null) {
            String message = completed.applied() ? "Permissions Already Saved" : "Permission Save Was Already Processed";
            return CompletableFuture.completedFuture(response(request, message, null, null, null, null, null, null, completed));
        }
        List<Conflict> conflicts = conflicts(changes);
        if (!conflicts.isEmpty()) {
            SaveResult result = new SaveResult(changes.operationId(), false, revision.get(), conflicts, List.of());
            remember("Save", "Permissions", actor, false, "Review Changes Before Saving");
            return CompletableFuture.completedFuture(response(request, "Permissions Changed On The Server", null, null, null, null, null, null, result));
        }
        List<Supplier<CompletableFuture<Compensated<AppliedEntity>>>> mutations = new ArrayList<>();
        for (EntityCreate create : changes.creates()) {
            mutations.add(() -> createMutation(create));
        }
        for (SubjectChange change : changes.subjects()) {
            mutations.add(() -> subjectMutation(change));
        }
        for (TrackChange change : changes.tracks()) {
            mutations.add(() -> trackMutation(change));
        }
        for (EntityDelete delete : changes.deletes()) {
            mutations.add(() -> deleteMutation(delete));
        }
        return runCompensating(mutations, applied -> {
            Invalidation invalidation = recordChange(applied, actor);
            SaveResult result = new SaveResult(changes.operationId(), true, invalidation.revision(), List.of(), committedEntities(applied, invalidation.revision()));
            rememberCompleted(result);
            notifyInvalidation(invalidation);
            return CompletableFuture.completedFuture(result);
        }).thenApply(result -> {
            remember("Save", "Permissions", actor, true, result.entities().size() + " Changes Applied");
            return response(request, "Permissions Saved", null, null, null, null, null, null, result);
        }).exceptionally(exception -> {
            Throwable failure = unwrap(exception);
            boolean rollbackFailed = failure instanceof CompensationFailure compensation && compensation.rollbackFailed();
            List<AppliedEntity> applied = failure instanceof CompensationFailure compensation ? compensation.applied() : List.of();
            String detail = rollbackFailed ? message(failure) + ". Recovery Was Incomplete" : message(failure);
            SaveResult result = new SaveResult(changes.operationId(), false, revision.get(), List.of(), applied);
            if (rollbackFailed) {
                try {
                    rememberCompleted(result);
                } catch (RuntimeException journalFailure) {
                    detail += ". Recovery Journal Could Not Be Saved: " + message(journalFailure);
                }
            }
            remember("Save", "Permissions", actor, false, detail);
            return response(request, rollbackFailed ? "Permissions Need Recovery" : "Permissions Were Not Saved",
                null, null, null, null, null, null, result);
        });
    }

    static List<AppliedEntity> committedEntities(List<AppliedEntity> applied, long revision) {
        return applied.stream().map(entity -> new AppliedEntity(entity.type(), entity.id(), revision, entity.success(), entity.message())).toList();
    }

    static <T> CompletableFuture<List<T>> runCompensating(List<Supplier<CompletableFuture<Compensated<T>>>> mutations) {
        return runCompensating(mutations, CompletableFuture::completedFuture);
    }

    static <T, R> CompletableFuture<R> runCompensating(
        List<Supplier<CompletableFuture<Compensated<T>>>> mutations,
        Function<List<T>, CompletableFuture<R>> commit
    ) {
        List<Compensated<T>> completed = new ArrayList<>();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Supplier<CompletableFuture<Compensated<T>>> mutation : mutations) {
            chain = chain.thenCompose(ignored -> invoke(mutation).thenAccept(completed::add));
        }
        CompletableFuture<R> result = new CompletableFuture<>();
        chain.thenCompose(ignored -> invoke(() -> commit.apply(completed.stream().map(Compensated::result).toList()))).whenComplete((committed, failure) -> {
            if (failure == null) {
                result.complete(committed);
                return;
            }
            Throwable cause = unwrap(failure);
            CompensationFailure nested = cause instanceof CompensationFailure compensation ? compensation : null;
            List<T> applied = new ArrayList<>(completed.stream().map(Compensated::result).toList());
            if (nested != null) {
                applied.addAll(nested.applied());
            }
            rollback(completed).whenComplete((rolledBack, rollbackFailure) -> {
                boolean rollbackFailed = rollbackFailure != null || nested != null && nested.rollbackFailed();
                result.completeExceptionally(new CompensationFailure(nested != null ? nested.getCause() : cause, rollbackFailed, applied));
            });
        });
        return result;
    }

    private static <T> CompletableFuture<T> invoke(Supplier<CompletableFuture<T>> action) {
        try {
            return action.get();
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private static CompletableFuture<Void> rollback(List<? extends Compensated<?>> completed) {
        List<Throwable> failures = new ArrayList<>();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int index = completed.size() - 1; index >= 0; index--) {
            Compensated<?> mutation = completed.get(index);
            chain = chain.thenCompose(ignored -> invoke(mutation.rollback()).handle((rolledBack, failure) -> {
                if (failure != null) {
                    failures.add(unwrap(failure));
                }
                return null;
            }));
        }
        return chain.thenCompose(ignored -> failures.isEmpty()
            ? CompletableFuture.completedFuture(null)
            : CompletableFuture.failedFuture(failures.getFirst()));
    }

    private <T> CompletableFuture<Compensated<T>> compensateOnFailure(CompletableFuture<Void> mutation, Supplier<CompletableFuture<Void>> rollback, T result) {
        CompletableFuture<Compensated<T>> compensated = new CompletableFuture<>();
        mutation.whenComplete((ignored, failure) -> {
            if (failure == null) {
                compensated.complete(new Compensated<>(result, rollback));
                return;
            }
            Throwable cause = unwrap(failure);
            invoke(rollback).whenComplete((rolledBack, rollbackFailure) -> {
                compensated.completeExceptionally(new CompensationFailure(cause, rollbackFailure != null, List.of(result)));
            });
        });
        return compensated;
    }

    private CompletableFuture<Compensated<AppliedEntity>> createMutation(EntityCreate create) {
        AppliedEntity result = applied(create.type(), create.id(), true, "Created");
        return ensureCreateAvailable(create).thenCompose(ignored -> compensateOnFailure(create(create),
            () -> delete(new EntityDelete(create.type(), create.id())), result));
    }

    private CompletableFuture<Compensated<AppliedEntity>> subjectMutation(SubjectChange change) {
        AppliedEntity result = applied(entityType(change.subject()), change.subject().id(), true, "Saved");
        return captureSubject(change.subject()).thenCompose(state -> compensateOnFailure(apply(change), () -> restoreSubject(state), result));
    }

    private CompletableFuture<Compensated<AppliedEntity>> trackMutation(TrackChange change) {
        AppliedEntity result = applied(EntityType.TRACK, change.name(), true, "Saved");
        return captureTrack(change.name(), false).thenCompose(state -> compensateOnFailure(apply(change), () -> restoreTrack(state), result));
    }

    private CompletableFuture<Compensated<AppliedEntity>> deleteMutation(EntityDelete delete) {
        AppliedEntity result = applied(delete.type(), delete.id(), true, "Deleted");
        return switch (delete.type()) {
            case USER -> captureSubject(new SubjectRef(SubjectType.USER, delete.id()))
                .thenCompose(state -> compensateOnFailure(delete(delete), () -> restoreSubject(state), result));
            case GROUP -> captureSubject(new SubjectRef(SubjectType.GROUP, delete.id()))
                .thenCompose(state -> compensateOnFailure(delete(delete), () -> restoreSubject(state), result));
            case TRACK -> captureTrack(delete.id(), true)
                .thenCompose(state -> compensateOnFailure(delete(delete), () -> restoreTrack(state), result));
        };
    }

    private CompletableFuture<Void> ensureCreateAvailable(EntityCreate create) {
        LuckPerms luckPerms = requireLuckPerms();
        return switch (create.type()) {
            case USER -> resolveUserId(create.id()).thenCompose(uniqueId -> luckPerms.getUserManager().getUniqueUsers().thenCompose(users ->
                users.contains(uniqueId) ? CompletableFuture.failedFuture(new IllegalArgumentException("User Already Exists")) : CompletableFuture.completedFuture(null)));
            case GROUP -> luckPerms.getGroupManager().loadGroup(create.id()).thenCompose(group ->
                group.isPresent() ? CompletableFuture.failedFuture(new IllegalArgumentException("Group Already Exists")) : CompletableFuture.completedFuture(null));
            case TRACK -> luckPerms.getTrackManager().loadTrack(create.id()).thenCompose(track ->
                track.isPresent() ? CompletableFuture.failedFuture(new IllegalArgumentException("Track Already Exists")) : CompletableFuture.completedFuture(null));
        };
    }

    private CompletableFuture<UUID> resolveUserId(String id) {
        try {
            return CompletableFuture.completedFuture(UUID.fromString(id));
        } catch (IllegalArgumentException exception) {
            return requireLuckPerms().getUserManager().lookupUniqueId(id).thenCompose(uniqueId -> uniqueId != null
                ? CompletableFuture.completedFuture(uniqueId)
                : CompletableFuture.failedFuture(new IllegalArgumentException("Player Was Not Found")));
        }
    }

    private CompletableFuture<SubjectState> captureSubject(SubjectRef subject) {
        return loadSubject(subject).thenCompose(holder -> {
            if (holder == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Permission Holder Was Not Found"));
            }
            UUID uniqueId = holder instanceof User user ? user.getUniqueId() : null;
            String username = holder instanceof User user ? Optional.ofNullable(user.getUsername()).orElse(subject.id()) : "";
            String primaryGroup = holder instanceof User user ? user.getPrimaryGroup() : "";
            return CompletableFuture.completedFuture(new SubjectState(subject, uniqueId, username, primaryGroup,
                List.copyOf(holder.data().toCollection()), List.copyOf(holder.transientData().toCollection())));
        });
    }

    private CompletableFuture<Void> restoreSubject(SubjectState state) {
        LuckPerms luckPerms = requireLuckPerms();
        if (state.subject().type() == SubjectType.GROUP) {
            return luckPerms.getGroupManager().loadGroup(state.subject().id())
                .thenCompose(group -> group.map(CompletableFuture::completedFuture)
                    .orElseGet(() -> luckPerms.getGroupManager().createAndLoadGroup(state.subject().id())))
                .thenCompose(group -> {
                    group.data().clear();
                    group.transientData().clear();
                    state.nodes().forEach(group.data()::add);
                    state.transientNodes().forEach(group.transientData()::add);
                    return luckPerms.getGroupManager().saveGroup(group);
                });
        }
        UUID uniqueId = state.uniqueId();
        if (uniqueId == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("User identity is unavailable"));
        }
        return luckPerms.getUserManager().savePlayerData(uniqueId, state.username())
            .thenCompose(ignored -> luckPerms.getUserManager().loadUser(uniqueId))
            .thenCompose(user -> {
                user.data().clear();
                user.transientData().clear();
                state.nodes().forEach(user.data()::add);
                state.transientNodes().forEach(user.transientData()::add);
                if (!state.primaryGroup().isBlank()) {
                    user.setPrimaryGroup(state.primaryGroup());
                }
                return luckPerms.getUserManager().saveUser(user);
            });
    }

    private CompletableFuture<TrackState> captureTrack(String name, boolean required) {
        return requireLuckPerms().getTrackManager().loadTrack(name).thenCompose(track -> {
            if (track.isEmpty()) {
                return required
                    ? CompletableFuture.failedFuture(new IllegalArgumentException("Track Was Not Found"))
                    : CompletableFuture.completedFuture(new TrackState(name, false, List.of()));
            }
            return CompletableFuture.completedFuture(new TrackState(name, true, List.copyOf(track.get().getGroups())));
        });
    }

    private CompletableFuture<Void> restoreTrack(TrackState state) {
        LuckPerms luckPerms = requireLuckPerms();
        if (!state.existed()) {
            return luckPerms.getTrackManager().loadTrack(state.name()).thenCompose(track -> track
                .map(luckPerms.getTrackManager()::deleteTrack)
                .orElseGet(() -> CompletableFuture.completedFuture(null)));
        }
        return luckPerms.getTrackManager().createAndLoadTrack(state.name()).thenCompose(track -> {
            track.clearGroups();
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (String groupName : state.groups()) {
                chain = chain.thenCompose(ignored -> luckPerms.getGroupManager().loadGroup(groupName).thenAccept(group ->
                    track.appendGroup(group.orElseThrow(() -> new IllegalArgumentException("Group Was Not Found: " + groupName)))));
            }
            return chain.thenCompose(ignored -> luckPerms.getTrackManager().saveTrack(track));
        });
    }

    private CompletableFuture<Void> create(EntityCreate create) {
        LuckPerms luckPerms = requireLuckPerms();
        return switch (create.type()) {
            case USER -> {
                CompletableFuture<UUID> uniqueId;
                try {
                    uniqueId = CompletableFuture.completedFuture(UUID.fromString(create.id()));
                } catch (IllegalArgumentException exception) {
                    uniqueId = luckPerms.getUserManager().lookupUniqueId(create.id());
                }
                yield uniqueId.thenCompose(value -> {
                    if (value == null) {
                        return CompletableFuture.failedFuture(new IllegalArgumentException("Player Was Not Found"));
                    }
                    String username = create.username().isBlank() ? create.id() : create.username();
                    return luckPerms.getUserManager().savePlayerData(value, username).thenApply(ignored -> null);
                });
            }
            case GROUP -> luckPerms.getGroupManager().createAndLoadGroup(create.id()).thenApply(ignored -> null);
            case TRACK -> luckPerms.getTrackManager().createAndLoadTrack(create.id()).thenApply(ignored -> null);
        };
    }

    private CompletableFuture<Void> delete(EntityDelete delete) {
        LuckPerms luckPerms = requireLuckPerms();
        return switch (delete.type()) {
            case USER -> deleteUser(luckPerms, delete.id());
            case GROUP -> luckPerms.getGroupManager().loadGroup(delete.id()).thenCompose(group -> group
                .map(luckPerms.getGroupManager()::deleteGroup)
                .orElseGet(() -> CompletableFuture.failedFuture(new IllegalArgumentException("Group Was Not Found"))));
            case TRACK -> luckPerms.getTrackManager().loadTrack(delete.id()).thenCompose(track -> track
                .map(luckPerms.getTrackManager()::deleteTrack)
                .orElseGet(() -> CompletableFuture.failedFuture(new IllegalArgumentException("Track Was Not Found"))));
        };
    }

    private CompletableFuture<Void> deleteUser(LuckPerms luckPerms, String id) {
        CompletableFuture<UUID> uniqueId;
        try {
            uniqueId = CompletableFuture.completedFuture(UUID.fromString(id));
        } catch (IllegalArgumentException exception) {
            uniqueId = luckPerms.getUserManager().lookupUniqueId(id);
        }
        return uniqueId.thenCompose(value -> {
            if (value == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("User Was Not Found"));
            }
            return luckPerms.getUserManager().loadUser(value).thenCompose(user -> {
                user.data().clear();
                user.transientData().clear();
                return luckPerms.getUserManager().saveUser(user)
                    .thenCompose(ignored -> luckPerms.getUserManager().deletePlayerData(value));
            });
        });
    }

    private CompletableFuture<Void> apply(SubjectChange change) {
        LuckPerms luckPerms = requireLuckPerms();
        return loadSubject(change.subject()).thenCompose(holder -> {
            if (holder == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Permission Holder Was Not Found"));
            }
            holder.data().clear();
            for (NodeData node : change.nodes()) {
                holder.data().add(node(node));
            }
            if (holder instanceof Group group) {
                replaceGroupPresentation(group, change);
                return luckPerms.getGroupManager().saveGroup(group);
            }
            User user = (User) holder;
            if (!change.primaryGroup().isBlank() && !change.primaryGroup().equalsIgnoreCase(user.getPrimaryGroup())) {
                user.setPrimaryGroup(change.primaryGroup());
            }
            return luckPerms.getUserManager().saveUser(user);
        });
    }

    private CompletableFuture<Void> apply(TrackChange change) {
        LuckPerms luckPerms = requireLuckPerms();
        return luckPerms.getTrackManager().createAndLoadTrack(change.name()).thenCompose(track -> {
            track.clearGroups();
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (String groupName : change.groups()) {
                chain = chain.thenCompose(ignored -> luckPerms.getGroupManager().loadGroup(groupName).thenAccept(group -> {
                    Group value = group.orElseThrow(() -> new IllegalArgumentException("Group Was Not Found: " + groupName));
                    track.appendGroup(value);
                }));
            }
            return chain.thenCompose(ignored -> luckPerms.getTrackManager().saveTrack(track));
        });
    }

    private void replaceGroupPresentation(Group group, SubjectChange change) {
        group.data().clear(node -> node instanceof DisplayNameNode || node instanceof WeightNode);
        if (!change.name().isBlank() && !change.name().equalsIgnoreCase(group.getName())) {
            group.data().add(DisplayNameNode.builder(change.name()).build());
        }
        if (change.weight() != null) {
            group.data().add(WeightNode.builder(change.weight()).build());
        }
    }

    private List<Conflict> conflicts(ChangeSet changes) {
        List<Conflict> conflicts = new ArrayList<>();
        Set<String> created = changes.creates().stream().map(create -> create.type() + ":" + create.id().toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
        for (SubjectChange change : changes.subjects()) {
            EntityType type = entityType(change.subject());
            if (created.contains(type + ":" + change.subject().id().toLowerCase(Locale.ROOT))) {
                continue;
            }
            long actual = entityRevision(type, change.subject().id());
            if (change.baseRevision() != actual) {
                conflicts.add(new Conflict(type, change.subject().id(), change.baseRevision(), actual, "This Entry Changed Since It Was Opened"));
            }
        }
        for (TrackChange change : changes.tracks()) {
            if (created.contains(EntityType.TRACK + ":" + change.name().toLowerCase(Locale.ROOT))) {
                continue;
            }
            long actual = entityRevision(EntityType.TRACK, change.name());
            if (change.baseRevision() != actual) {
                conflicts.add(new Conflict(EntityType.TRACK, change.name(), change.baseRevision(), actual, "This Track Changed Since It Was Opened"));
            }
        }
        return conflicts;
    }

    private CompletableFuture<PermissionHolder> loadSubject(SubjectRef subject) {
        LuckPerms luckPerms = requireLuckPerms();
        if (subject.type() == SubjectType.GROUP) {
            return luckPerms.getGroupManager().loadGroup(subject.id()).thenApply(group -> group.<PermissionHolder>map(value -> value).orElse(null));
        }
        try {
            return luckPerms.getUserManager().loadUser(UUID.fromString(subject.id())).thenApply(user -> user);
        } catch (IllegalArgumentException exception) {
            return luckPerms.getUserManager().lookupUniqueId(subject.id()).thenCompose(uniqueId -> uniqueId == null
                ? CompletableFuture.completedFuture(null)
                : luckPerms.getUserManager().loadUser(uniqueId).thenApply(user -> user));
        }
    }

    private SubjectDetail subjectDetail(SubjectRef reference, PermissionHolder holder) {
        String name = holder instanceof User user ? playerName(user) : ((Group) holder).getDisplayName();
        if (name == null || name.isBlank()) {
            name = reference.id();
        }
        String primaryGroup = holder instanceof User user ? user.getPrimaryGroup() : "";
        Integer weight = holder instanceof Group group && group.getWeight().isPresent() ? group.getWeight().getAsInt() : null;
        List<NodeData> nodes = holder.data().toCollection().stream().sorted(Comparator.comparing(Node::getKey)).map(this::nodeData).toList();
        return new SubjectDetail(entityRevision(entityType(reference), reference.id()), reference, name, primaryGroup, weight, nodes);
    }

    private UserSummary userSummary(User user) {
        Player player = Bukkit.getPlayer(user.getUniqueId());
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(user.getUniqueId());
        String username = playerName(user);
        String prefix = Optional.ofNullable(user.getCachedData().getMetaData().getPrefix()).orElse("");
        String suffix = Optional.ofNullable(user.getCachedData().getMetaData().getSuffix()).orElse("");
        return new UserSummary(user.getUniqueId().toString(), username, user.getPrimaryGroup(), player != null && player.isOnline(),
            user.data().toCollection().size(), prefix, suffix, player != null ? System.currentTimeMillis() : offlinePlayer.getLastPlayed());
    }

    private String playerName(User user) {
        Player player = Bukkit.getPlayer(user.getUniqueId());
        if (player != null && !player.getName().isBlank()) {
            return player.getName();
        }
        String offlineName = Bukkit.getOfflinePlayer(user.getUniqueId()).getName();
        if (offlineName != null && !offlineName.isBlank()) {
            return offlineName;
        }
        return Optional.ofNullable(user.getUsername()).filter(value -> !value.isBlank()).orElse(user.getUniqueId().toString());
    }

    private GroupSummary groupSummary(Group group) {
        int members = (int) requireLuckPerms().getUserManager().getLoadedUsers().stream().filter(user -> group.getName().equalsIgnoreCase(user.getPrimaryGroup())).count();
        return new GroupSummary(group.getName(), Optional.ofNullable(group.getDisplayName()).orElse(group.getName()),
            group.getWeight().isPresent() ? group.getWeight().getAsInt() : null, group.data().toCollection().size(), members);
    }

    private NodeData nodeData(Node node) {
        Integer priority = node instanceof ChatMetaNode<?, ?> chatMeta ? chatMeta.getPriority() : null;
        Long expiresAt = node.getExpiry() == null ? null : node.getExpiry().getEpochSecond();
        Map<String, List<String>> contexts = new LinkedHashMap<>();
        node.getContexts().toMap().forEach((key, values) -> contexts.put(key, values.stream().sorted().toList()));
        String identity = node.getKey() + "|" + node.getValue() + "|" + contexts + "|" + expiresAt;
        return new NodeData(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString(), nodeKind(node), node.getKey(),
            node.getValue(), priority, contexts, expiresAt);
    }

    private Node node(NodeData node) {
        var builder = Node.builder(node.key()).value(node.value());
        if (node.expiresAt() != null && node.expiresAt() > Instant.now().getEpochSecond()) {
            builder.expiry(node.expiresAt());
        }
        node.contexts().forEach((key, values) -> values.forEach(value -> builder.withContext(key, value)));
        return builder.build();
    }

    private NodeKind nodeKind(Node node) {
        if (node instanceof PermissionNode) {
            return NodeKind.PERMISSION;
        }
        if (node instanceof InheritanceNode) {
            return NodeKind.INHERITANCE;
        }
        if (node instanceof PrefixNode) {
            return NodeKind.PREFIX;
        }
        if (node instanceof SuffixNode) {
            return NodeKind.SUFFIX;
        }
        if (node instanceof MetaNode) {
            return NodeKind.META;
        }
        if (node instanceof DisplayNameNode) {
            return NodeKind.DISPLAY_NAME;
        }
        if (node instanceof WeightNode) {
            return NodeKind.WEIGHT;
        }
        return NodeKind.UNKNOWN;
    }

    private ImmutableContextSet contexts(Map<String, List<String>> values) {
        ImmutableContextSet.Builder builder = ImmutableContextSet.builder();
        if (values != null) {
            values.forEach((key, entries) -> entries.forEach(value -> builder.add(key, value)));
        }
        return builder.build();
    }

    private boolean permissionMatches(String node, String requested) {
        if (node.equalsIgnoreCase(requested) || node.equals("*")) {
            return true;
        }
        return node.endsWith(".*") && requested.regionMatches(true, 0, node, 0, node.length() - 1);
    }

    private int offset(String cursor, int size) {
        try {
            return Math.clamp(Integer.parseInt(cursor), 0, size);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private long entityRevision(EntityType type, String id) {
        return entityRevisions.computeIfAbsent(type + ":" + id.toLowerCase(Locale.ROOT), ignored -> revision.get());
    }

    private AppliedEntity applied(EntityType type, String id, boolean success, String message) {
        return new AppliedEntity(type, id, entityRevision(type, id), success, message);
    }

    private EntityType entityType(SubjectRef subject) {
        return subject.type() == SubjectType.GROUP ? EntityType.GROUP : EntityType.USER;
    }

    private long changed(Set<EntityType> scopes, Collection<String> ids, String source) {
        long current = revision.incrementAndGet();
        lastChangedAt = System.currentTimeMillis();
        for (EntityType scope : scopes) {
            for (String id : ids) {
                entityRevisions.put(scope + ":" + id.toLowerCase(Locale.ROOT), current);
            }
        }
        Invalidation invalidation = new Invalidation(current, scopes, List.copyOf(ids), source);
        notifyInvalidation(invalidation);
        return current;
    }

    private Invalidation recordChange(Collection<AppliedEntity> applied, String source) {
        long current = revision.incrementAndGet();
        lastChangedAt = System.currentTimeMillis();
        Set<EntityType> scopes = applied.stream().map(AppliedEntity::type).collect(Collectors.toSet());
        List<String> ids = applied.stream().map(AppliedEntity::id).distinct().toList();
        applied.forEach(entity -> entityRevisions.put(entity.type() + ":" + entity.id().toLowerCase(Locale.ROOT), current));
        return new Invalidation(current, scopes, ids, source);
    }

    private void notifyInvalidation(Invalidation invalidation) {
        for (Consumer<Invalidation> listener : listeners) {
            try {
                listener.accept(invalidation);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("LuckPerms invalidation listener failed: " + message(exception));
            }
        }
    }

    private void subscribeEvents() {
        LuckPerms luckPerms = luckPerms();
        if (luckPerms == null) {
            return;
        }
        subscriptions.add(luckPerms.getEventBus().subscribe(plugin, NodeMutateEvent.class, event -> {
            if (event.getTarget() instanceof User user) {
                changed(Set.of(EntityType.USER), List.of(user.getUniqueId().toString()), "LuckPerms");
            } else if (event.getTarget() instanceof Group group) {
                changed(Set.of(EntityType.GROUP), List.of(group.getName()), "LuckPerms");
            }
        }));
        subscriptions.add(luckPerms.getEventBus().subscribe(plugin, TrackMutateEvent.class,
            event -> changed(Set.of(EntityType.TRACK), List.of(event.getTrack().getName()), "LuckPerms")));
        subscriptions.add(luckPerms.getEventBus().subscribe(plugin, GroupCreateEvent.class,
            event -> changed(Set.of(EntityType.GROUP), List.of(event.getGroup().getName()), "LuckPerms")));
        subscriptions.add(luckPerms.getEventBus().subscribe(plugin, GroupDeleteEvent.class,
            event -> changed(Set.of(EntityType.GROUP), List.of(event.getGroupName()), "LuckPerms")));
    }

    private Response response(Request request, String message, Overview overview, UserPage users, GroupPage groups, List<TrackDetail> tracks,
                              SubjectDetail subject, EffectivePreview preview, SaveResult save) {
        return new Response(LuckPermsManagementContract.VERSION, request.requestId(), request.action(), true, message, revision.get(),
            overview, users, groups, tracks, subject, preview, save, null);
    }

    private Response error(Request request, String message) {
        String requestId = request == null ? "" : request.requestId();
        Action action = request == null ? Action.OVERVIEW : request.action();
        return new Response(LuckPermsManagementContract.VERSION, requestId, action, false, message, revision.get(),
            null, null, null, List.of(), null, null, null, null);
    }

    private synchronized void remember(String action, String target, String actor, boolean success, String detail) {
        audit.addFirst(new AuditEntry(System.currentTimeMillis(), action, target, actor, success, detail));
        while (audit.size() > AUDIT_LIMIT) {
            audit.removeLast();
        }
    }

    private synchronized List<AuditEntry> auditSnapshot() {
        return List.copyOf(audit);
    }

    private synchronized void rememberCompleted(SaveResult result) {
        if (result.operationId().isBlank() || completedSaves.containsKey(result.operationId())) {
            return;
        }
        List<SaveResult> saved = Stream.concat(
                completedSaveOrder.stream().map(completedSaves::get).filter(Objects::nonNull),
                Stream.of(result)
            )
            .skip(Math.max(0, completedSaveOrder.size() + 1L - COMPLETED_SAVE_LIMIT))
            .toList();
        saveCompleted(saved);
        completedSaves.put(result.operationId(), result);
        completedSaveOrder.addLast(result.operationId());
        while (completedSaveOrder.size() > COMPLETED_SAVE_LIMIT) {
            completedSaves.remove(completedSaveOrder.removeFirst());
        }
    }

    private void loadCompleted() {
        if (Files.notExists(operationJournal)) {
            return;
        }
        try {
            List<SaveResult> saved = GSON.fromJson(StorageSafety.readUtf8(operationJournal), new TypeToken<List<SaveResult>>() {}.getType());
            if (saved == null) {
                return;
            }
            saved.stream().filter(Objects::nonNull).skip(Math.max(0, saved.size() - COMPLETED_SAVE_LIMIT)).forEach(result -> {
                if (!result.operationId().isBlank()) {
                    completedSaves.put(result.operationId(), result);
                    completedSaveOrder.addLast(result.operationId());
                }
            });
        } catch (IOException | RuntimeException exception) {
            plugin.getLogger().warning("LuckPerms operation journal could not be loaded: " + message(exception));
        }
    }

    private void saveCompleted(List<SaveResult> saved) {
        try {
            StorageSafety.writeUtf8Atomic(operationJournal, GSON.toJson(saved));
        } catch (IOException exception) {
            throw new IllegalStateException("LuckPerms operation journal could not be saved", exception);
        }
    }

    private String pluginVersion() {
        return Bukkit.getPluginManager().getPlugin("LuckPerms") == null ? "" : Bukkit.getPluginManager().getPlugin("LuckPerms").getPluginMeta().getVersion();
    }

    private String message(Throwable throwable) {
        Throwable cause = unwrap(throwable);
        if (cause instanceof CompensationFailure && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null || cause.getMessage().isBlank() ? "Permissions Could Not Be Saved" : cause.getMessage();
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable cause = throwable;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private LuckPerms requireLuckPerms() {
        LuckPerms luckPerms = luckPerms();
        if (luckPerms == null) {
            throw new IllegalStateException("LuckPerms Is Not Available On This Server");
        }
        return luckPerms;
    }

    private LuckPerms luckPerms() {
        RegisteredServiceProvider<LuckPerms> registration = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        return registration == null ? null : registration.getProvider();
    }

    @Override
    public void close() {
        for (EventSubscription<?> subscription : subscriptions) {
            subscription.close();
        }
        subscriptions.clear();
        listeners.clear();
    }
}

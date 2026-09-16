package restudio.resync.flow.cache;

import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public final class GraphResourceCache {
    private volatile State state = new State(Map.of(), Map.of());

    public record Snapshot(Map<ServerResourceLocator, GraphResourceState> authoritative,
                           Map<ServerResourceLocator, GraphDraft> drafts) {
        public Snapshot {
            authoritative = Objects.requireNonNull(authoritative, "authoritative");
            drafts = Objects.requireNonNull(drafts, "drafts");
        }
    }

    private record State(Map<ServerResourceLocator, GraphResourceState> authoritative,
                         Map<ServerResourceLocator, GraphDraft> drafts) {
        private State {
            authoritative = Objects.requireNonNull(authoritative, "authoritative");
            drafts = Objects.requireNonNull(drafts, "drafts");
        }
    }

    public GraphResourceCache() {
    }

    public GraphResourceCache(Collection<GraphResourceState> authoritative,
                              Collection<GraphDraft> drafts) {
        this.state = new State(immutableAuthoritative(authoritative), immutableDrafts(drafts));
    }

    public GraphResourceCache(Map<ServerResourceLocator, GraphResourceState> authoritative,
                              Map<ServerResourceLocator, GraphDraft> drafts) {
        this.state = new State(immutableAuthoritative(authoritative), immutableDrafts(drafts));
    }

    public Map<ServerResourceLocator, GraphResourceState> authoritative() {
        return state.authoritative();
    }

    public Map<ServerResourceLocator, GraphResourceState> authoritativeStates() {
        return authoritative();
    }

    public Map<ServerResourceLocator, GraphResourceState> authoritativeEntries() {
        return authoritative();
    }

    public Map<ServerResourceLocator, GraphDraft> drafts() {
        return state.drafts();
    }

    public Map<ServerResourceLocator, GraphDraft> draftStates() {
        return drafts();
    }

    public Map<ServerResourceLocator, GraphDraft> draftEntries() {
        return drafts();
    }

    public Optional<GraphResourceState> authoritative(ServerResourceLocator resource) {
        return Optional.ofNullable(state.authoritative().get(Objects.requireNonNull(resource, "resource")));
    }

    public Optional<GraphResourceState> state(ServerResourceLocator resource) {
        return authoritative(resource);
    }

    public Optional<GraphDraft> draft(ServerResourceLocator resource) {
        return Optional.ofNullable(state.drafts().get(Objects.requireNonNull(resource, "resource")));
    }

    public synchronized Snapshot snapshot() {
        State current = state;
        return new Snapshot(current.authoritative(), current.drafts());
    }

    public synchronized GraphResourceState putAuthoritative(GraphResourceState state) {
        Objects.requireNonNull(state, "state");
        State current = this.state;
        LinkedHashMap<ServerResourceLocator, GraphResourceState> next = new LinkedHashMap<>(current.authoritative());
        GraphResourceState previous = next.put(state.resource(), state);
        this.state = new State(immutableAuthoritative(next), current.drafts());
        return previous;
    }

    public synchronized void replace(Map<ServerResourceLocator, GraphResourceState> authoritative,
                                     Map<ServerResourceLocator, GraphDraft> drafts) {
        Objects.requireNonNull(authoritative, "authoritative");
        Objects.requireNonNull(drafts, "drafts");
        State current = this.state;
        Map<ServerResourceLocator, GraphResourceState> nextAuthoritative = authoritative == current.authoritative()
            ? current.authoritative() : immutableAuthoritative(authoritative);
        Map<ServerResourceLocator, GraphDraft> nextDrafts = drafts == current.drafts()
            ? current.drafts() : immutableDrafts(drafts);
        if (nextAuthoritative != current.authoritative() || nextDrafts != current.drafts()) {
            this.state = new State(nextAuthoritative, nextDrafts);
        }
    }

    public synchronized GraphResourceState putState(GraphResourceState state) {
        return putAuthoritative(state);
    }

    public synchronized GraphDraft putDraft(GraphDraft draft) {
        Objects.requireNonNull(draft, "draft");
        State current = state;
        LinkedHashMap<ServerResourceLocator, GraphDraft> next = new LinkedHashMap<>(current.drafts());
        GraphDraft previous = next.put(draft.resource(), draft);
        state = new State(current.authoritative(), immutableDrafts(next));
        return previous;
    }

    public synchronized GraphDraft setDraft(GraphDraft draft) {
        return putDraft(draft);
    }

    public synchronized GraphResourceState removeAuthoritative(ServerResourceLocator resource) {
        Objects.requireNonNull(resource, "resource");
        State current = state;
        if (!current.authoritative().containsKey(resource)) {
            return null;
        }
        LinkedHashMap<ServerResourceLocator, GraphResourceState> next = new LinkedHashMap<>(current.authoritative());
        GraphResourceState removed = next.remove(resource);
        state = new State(immutableAuthoritative(next), current.drafts());
        return removed;
    }

    public synchronized GraphDraft removeDraft(ServerResourceLocator resource) {
        Objects.requireNonNull(resource, "resource");
        State current = state;
        if (!current.drafts().containsKey(resource)) {
            return null;
        }
        LinkedHashMap<ServerResourceLocator, GraphDraft> next = new LinkedHashMap<>(current.drafts());
        GraphDraft removed = next.remove(resource);
        state = new State(current.authoritative(), immutableDrafts(next));
        return removed;
    }

    public synchronized GraphDraft clearDraft(ServerResourceLocator resource) {
        return removeDraft(resource);
    }

    public synchronized void clear() {
        state = new State(Map.of(), Map.of());
    }

    public synchronized Reconciliation reconcile(GraphResourceState incoming) {
        Objects.requireNonNull(incoming, "incoming");
        ServerResourceLocator resource = incoming.resource();
        State currentState = state;
        GraphResourceState current = currentState.authoritative().get(resource);
        GraphDraft currentDraft = currentState.drafts().get(resource);
        if (current == null) {
            boolean acknowledged = currentDraft != null && currentDraft.matchesMutation(incoming.mutationId());
            State next = publish(incoming, resource, acknowledged);
            return result(next, acknowledged ? Status.ACKNOWLEDGED : Status.NEW, current, incoming, resource);
        }
        int revisionOrder = Long.compare(incoming.revision(), current.revision());
        if (revisionOrder < 0) {
            return result(Status.STALE, current, incoming, resource);
        }
        if (revisionOrder > 0) {
            boolean acknowledged = currentDraft != null && currentDraft.matchesMutation(incoming.mutationId());
            State next = publish(incoming, resource, acknowledged);
            return result(next, acknowledged ? Status.ACKNOWLEDGED : Status.NEWER, current, incoming, resource);
        }
        if (current.equals(incoming)) {
            boolean acknowledged = currentDraft != null && currentDraft.matchesMutation(incoming.mutationId());
            if (acknowledged) {
                State next = publish(null, resource, true);
                return result(next, Status.ACKNOWLEDGED, current, incoming, resource);
            }
            return result(Status.DUPLICATE, current, incoming, resource);
        }
        return result(Status.CONFLICT, current, incoming, resource);
    }

    public synchronized List<Reconciliation> reconcile(Collection<GraphResourceState> incoming) {
        Objects.requireNonNull(incoming, "incoming");
        return withBatch(batch -> batch.reconcile(incoming));
    }

    public synchronized Reconciliation accept(GraphResourceState incoming) {
        return reconcile(incoming);
    }

    public synchronized List<Reconciliation> acceptAll(Collection<GraphResourceState> incoming) {
        return reconcile(incoming);
    }

    public synchronized <T> T withBatch(Function<Batch, T> operation) {
        Objects.requireNonNull(operation, "batch operation");
        State current = state;
        Batch batch = new Batch(current.authoritative(), current.drafts());
        T result = operation.apply(batch);
        Map<ServerResourceLocator, GraphResourceState> nextAuthoritative = current.authoritative();
        Map<ServerResourceLocator, GraphDraft> nextDrafts = current.drafts();
        if (batch.authoritativeChanged) {
            nextAuthoritative = immutableAuthoritative(batch.authoritative);
        }
        if (batch.draftsChanged) {
            nextDrafts = immutableDrafts(batch.drafts);
        }
        if (nextAuthoritative != current.authoritative() || nextDrafts != current.drafts()) {
            state = new State(nextAuthoritative, nextDrafts);
        }
        return result;
    }

    private State publish(GraphResourceState incoming, ServerResourceLocator resource, boolean removeDraft) {
        State current = state;
        Map<ServerResourceLocator, GraphResourceState> nextAuthoritative = current.authoritative();
        if (incoming != null) {
            LinkedHashMap<ServerResourceLocator, GraphResourceState> next = new LinkedHashMap<>(current.authoritative());
            next.put(incoming.resource(), incoming);
            nextAuthoritative = immutableAuthoritative(next);
        }
        Map<ServerResourceLocator, GraphDraft> nextDrafts = current.drafts();
        if (removeDraft) {
            LinkedHashMap<ServerResourceLocator, GraphDraft> next = new LinkedHashMap<>(current.drafts());
            next.remove(resource);
            nextDrafts = immutableDrafts(next);
        }
        State next = new State(nextAuthoritative, nextDrafts);
        state = next;
        return next;
    }

    private Reconciliation result(Status status, GraphResourceState previous, GraphResourceState incoming,
                                   ServerResourceLocator resource) {
        return result(state, status, previous, incoming, resource);
    }

    private Reconciliation result(State current, Status status, GraphResourceState previous,
                                  GraphResourceState incoming, ServerResourceLocator resource) {
        return new Reconciliation(status, resource, previous, incoming,
            current.authoritative().get(resource), current.drafts().get(resource));
    }

    private static Map<ServerResourceLocator, GraphResourceState> immutableAuthoritative(
        Collection<GraphResourceState> values) {
        Objects.requireNonNull(values, "authoritative");
        LinkedHashMap<ServerResourceLocator, GraphResourceState> entries = new LinkedHashMap<>();
        for (GraphResourceState state : values) {
            Objects.requireNonNull(state, "authoritative contains null");
            if (entries.putIfAbsent(state.resource(), state) != null) {
                throw new IllegalArgumentException("Duplicate authoritative graph resource: "
                    + state.resource().canonicalText());
            }
        }
        return immutableSorted(entries);
    }

    private static Map<ServerResourceLocator, GraphResourceState> immutableAuthoritative(
        Map<ServerResourceLocator, GraphResourceState> values) {
        Objects.requireNonNull(values, "authoritative");
        LinkedHashMap<ServerResourceLocator, GraphResourceState> entries = new LinkedHashMap<>();
        values.forEach((resource, state) -> {
            Objects.requireNonNull(resource, "authoritative key");
            Objects.requireNonNull(state, "authoritative value");
            if (!resource.equals(state.resource())) {
                throw new IllegalArgumentException("Authoritative key does not match graph resource state locator");
            }
            if (entries.putIfAbsent(resource, state) != null) {
                throw new IllegalArgumentException("Duplicate authoritative graph resource: "
                    + resource.canonicalText());
            }
        });
        return immutableSorted(entries);
    }

    private static Map<ServerResourceLocator, GraphDraft> immutableDrafts(Collection<GraphDraft> values) {
        Objects.requireNonNull(values, "drafts");
        LinkedHashMap<ServerResourceLocator, GraphDraft> entries = new LinkedHashMap<>();
        for (GraphDraft draft : values) {
            Objects.requireNonNull(draft, "drafts contains null");
            if (entries.putIfAbsent(draft.resource(), draft) != null) {
                throw new IllegalArgumentException("Duplicate graph draft resource: "
                    + draft.resource().canonicalText());
            }
        }
        return immutableSorted(entries);
    }

    private static Map<ServerResourceLocator, GraphDraft> immutableDrafts(Map<ServerResourceLocator, GraphDraft> values) {
        Objects.requireNonNull(values, "drafts");
        LinkedHashMap<ServerResourceLocator, GraphDraft> entries = new LinkedHashMap<>();
        values.forEach((resource, draft) -> {
            Objects.requireNonNull(resource, "draft key");
            Objects.requireNonNull(draft, "draft value");
            if (!resource.equals(draft.resource())) {
                throw new IllegalArgumentException("Draft key does not match graph draft locator");
            }
            if (entries.putIfAbsent(resource, draft) != null) {
                throw new IllegalArgumentException("Duplicate graph draft resource: "
                    + resource.canonicalText());
            }
        });
        return immutableSorted(entries);
    }

    private static <V> Map<ServerResourceLocator, V> immutableSorted(
        Map<ServerResourceLocator, V> values) {
        LinkedHashMap<ServerResourceLocator, V> sorted = new LinkedHashMap<>();
        values.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(sorted);
    }

    public static final class Batch {
        private final LinkedHashMap<ServerResourceLocator, GraphResourceState> authoritative;
        private final LinkedHashMap<ServerResourceLocator, GraphDraft> drafts;
        private boolean authoritativeChanged;
        private boolean draftsChanged;

        private Batch(Map<ServerResourceLocator, GraphResourceState> authoritative,
                      Map<ServerResourceLocator, GraphDraft> drafts) {
            this.authoritative = new LinkedHashMap<>(authoritative);
            this.drafts = new LinkedHashMap<>(drafts);
        }

        public Optional<GraphResourceState> state(ServerResourceLocator resource) {
            return Optional.ofNullable(authoritative.get(Objects.requireNonNull(resource, "resource")));
        }

        public Optional<GraphDraft> draft(ServerResourceLocator resource) {
            return Optional.ofNullable(drafts.get(Objects.requireNonNull(resource, "resource")));
        }

        public Reconciliation reconcile(GraphResourceState incoming) {
            Objects.requireNonNull(incoming, "incoming");
            ServerResourceLocator resource = incoming.resource();
            GraphResourceState current = authoritative.get(resource);
            GraphDraft currentDraft = drafts.get(resource);
            if (current == null) {
                authoritative.put(resource, incoming);
                authoritativeChanged = true;
                boolean acknowledged = currentDraft != null && currentDraft.matchesMutation(incoming.mutationId());
                if (acknowledged) {
                    drafts.remove(resource);
                    draftsChanged = true;
                }
                return result(acknowledged ? Status.ACKNOWLEDGED : Status.NEW, current, incoming, resource);
            }
            int revisionOrder = Long.compare(incoming.revision(), current.revision());
            if (revisionOrder < 0) {
                return result(Status.STALE, current, incoming, resource);
            }
            if (revisionOrder > 0) {
                authoritative.put(resource, incoming);
                authoritativeChanged = true;
                boolean acknowledged = currentDraft != null && currentDraft.matchesMutation(incoming.mutationId());
                if (acknowledged) {
                    drafts.remove(resource);
                    draftsChanged = true;
                }
                return result(acknowledged ? Status.ACKNOWLEDGED : Status.NEWER, current, incoming, resource);
            }
            if (current.equals(incoming)) {
                boolean acknowledged = currentDraft != null && currentDraft.matchesMutation(incoming.mutationId());
                if (acknowledged) {
                    drafts.remove(resource);
                    draftsChanged = true;
                    return result(Status.ACKNOWLEDGED, current, incoming, resource);
                }
                return result(Status.DUPLICATE, current, incoming, resource);
            }
            return result(Status.CONFLICT, current, incoming, resource);
        }

        public List<Reconciliation> reconcile(Collection<GraphResourceState> incoming) {
            Objects.requireNonNull(incoming, "incoming");
            List<Reconciliation> results = new ArrayList<>(incoming.size());
            for (GraphResourceState state : incoming) {
                results.add(reconcile(Objects.requireNonNull(state, "incoming contains null")));
            }
            return List.copyOf(results);
        }

        private Reconciliation result(Status status, GraphResourceState previous, GraphResourceState incoming,
                                      ServerResourceLocator resource) {
            return new Reconciliation(status, resource, previous, incoming, authoritative.get(resource),
                drafts.get(resource));
        }
    }

    public enum Status {
        NEW,
        STALE,
        DUPLICATE,
        CONFLICT,
        NEWER,
        ACKNOWLEDGED
    }

    public record Reconciliation(Status status, ServerResourceLocator resource,
                                 GraphResourceState previous, GraphResourceState incoming,
                                 GraphResourceState authoritative, GraphDraft draft) {
        public Reconciliation {
            status = Objects.requireNonNull(status, "status");
            resource = Objects.requireNonNull(resource, "resource");
            incoming = Objects.requireNonNull(incoming, "incoming");
            authoritative = Objects.requireNonNull(authoritative, "authoritative");
            if (!resource.equals(incoming.resource()) || !resource.equals(authoritative.resource())) {
                throw new IllegalArgumentException("Reconciliation resource does not match its states");
            }
        }

        public GraphResourceState state() {
            return authoritative;
        }

        public GraphResourceState current() {
            return authoritative;
        }

        public Status outcome() {
            return status;
        }

        public boolean applied() {
            return status == Status.NEW || status == Status.NEWER || status == Status.ACKNOWLEDGED;
        }

        public boolean accepted() {
            return applied();
        }

        public boolean stale() {
            return status == Status.STALE;
        }

        public boolean duplicate() {
            return status == Status.DUPLICATE;
        }

        public boolean conflict() {
            return status == Status.CONFLICT;
        }

        public boolean newer() {
            return status == Status.NEWER;
        }

        public boolean acknowledged() {
            return status == Status.ACKNOWLEDGED;
        }

        public boolean mutationAcknowledged() {
            return acknowledged();
        }
    }
}

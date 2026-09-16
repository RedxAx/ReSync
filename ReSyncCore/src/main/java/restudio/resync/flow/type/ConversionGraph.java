package restudio.resync.flow.type;

import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ConversionGraph {
    private static final int MAX_PATH_LENGTH = 16;
    private static final int MAX_SEARCH_STATES = 10000;
    private static final Comparator<ConversionEdge> EDGE_ORDER = Comparator.comparing(edge -> edge.id().canonicalKey());

    private final List<ConversionEdge> edges;
    private final Map<TypeExpr, List<ConversionEdge>> outgoing;

    private ConversionGraph(List<ConversionEdge> edges) {
        this.edges = List.copyOf(edges);
        var index = new HashMap<TypeExpr, List<ConversionEdge>>();
        for (var edge : edges) {
            index.computeIfAbsent(edge.source(), ignored -> new ArrayList<>()).add(edge);
        }
        index.values().forEach(values -> values.sort(EDGE_ORDER));
        var immutableIndex = new HashMap<TypeExpr, List<ConversionEdge>>();
        index.forEach((key, value) -> immutableIndex.put(key, List.copyOf(value)));
        this.outgoing = Map.copyOf(immutableIndex);
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<ConversionEdge> edges() {
        return edges;
    }

    public ConversionPath resolve(TypeExpr source, TypeExpr target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        if (source.equals(target)) {
            return new ConversionPath(List.of());
        }
        var candidates = new ArrayList<ConversionPath>();
        var path = new ArrayList<ConversionEdge>();
        var visited = new HashSet<TypeExpr>();
        visited.add(source);
        explore(source, target, path, visited, candidates, new SearchState());
        if (candidates.isEmpty()) {
            throw new ResolutionException(Reason.NO_PATH, source, target);
        }
        candidates.sort(Comparator.comparing(ConversionPath::rank));
        var best = candidates.getFirst();
        var tied = candidates.stream().filter(candidate -> candidate.rank().equals(best.rank())).count();
        if (tied > 1) {
            throw new ResolutionException(Reason.AMBIGUOUS, source, target);
        }
        return best;
    }

    private void explore(TypeExpr current, TypeExpr target, List<ConversionEdge> path, Set<TypeExpr> visited, List<ConversionPath> candidates, SearchState state) {
        if (++state.states > MAX_SEARCH_STATES) {
            throw new ResolutionException(Reason.SEARCH_LIMIT, current, target);
        }
        if (path.size() >= MAX_PATH_LENGTH) {
            return;
        }
        for (var edge : outgoing.getOrDefault(current, List.of())) {
            if (visited.contains(edge.target())) {
                continue;
            }
            path.add(edge);
            visited.add(edge.target());
            if (edge.target().equals(target)) {
                candidates.add(new ConversionPath(path));
            } else {
                explore(edge.target(), target, path, visited, candidates, state);
            }
            visited.remove(edge.target());
            path.removeLast();
        }
    }

    public static final class ConversionEdge {
        private final TypeReference id;
        private final TypeExpr source;
        private final TypeExpr target;
        private final int cost;
        private final Losslessness losslessness;
        private final FailureBehavior failure;
        private final ContractRef<CapabilityId> capability;
        private final ContractRef<OperationId> operation;
        private final int legacyFailureRank;

        public ConversionEdge(TypeReference id, TypeExpr source, TypeExpr target, int cost, boolean lossless, int failureRank) {
            this(id, source, target, cost, lossless ? Losslessness.LOSSLESS : Losslessness.LOSSY,
                FailureBehavior.fromRank(failureRank), ContractRef.of(idOwner(id), CapabilityId.of("conversion")),
                ContractRef.of(idOwner(id), OperationId.of("convert")), failureRank);
        }

        public ConversionEdge(TypeReference id, TypeExpr source, TypeExpr target, int cost, Losslessness losslessness,
                              FailureBehavior failure, ContractRef<CapabilityId> capability, ContractRef<OperationId> operation) {
            this(id, source, target, cost, losslessness, failure, capability, operation, failure == null ? -1 : failure.rank());
        }

        private ConversionEdge(TypeReference id, TypeExpr source, TypeExpr target, int cost, Losslessness losslessness,
                               FailureBehavior failure, ContractRef<CapabilityId> capability, ContractRef<OperationId> operation,
                               int legacyFailureRank) {
            this.id = Objects.requireNonNull(id, "id");
            this.source = Objects.requireNonNull(source, "source");
            this.target = Objects.requireNonNull(target, "target");
            if (source.equals(target)) {
                throw new IllegalArgumentException("A conversion edge must change its type");
            }
            if (cost < 0) {
                throw new IllegalArgumentException("Conversion cost cannot be negative");
            }
            if (legacyFailureRank < 0) {
                throw new IllegalArgumentException("Failure rank cannot be negative");
            }
            this.cost = cost;
            this.losslessness = Objects.requireNonNull(losslessness, "losslessness");
            this.failure = Objects.requireNonNull(failure, "failure");
            this.capability = Objects.requireNonNull(capability, "capability");
            this.operation = Objects.requireNonNull(operation, "operation");
            this.legacyFailureRank = legacyFailureRank;
        }

        private static OwnerId idOwner(TypeReference id) {
            return OwnerId.of(Objects.requireNonNull(id, "id").ownerId());
        }

        public TypeReference id() {
            return id;
        }

        public TypeExpr source() {
            return source;
        }

        public TypeExpr target() {
            return target;
        }

        public int cost() {
            return cost;
        }

        public boolean lossless() {
            return losslessness == Losslessness.LOSSLESS;
        }

        public Losslessness losslessness() {
            return losslessness;
        }

        public FailureBehavior failure() {
            return failure;
        }

        public int failureRank() {
            return legacyFailureRank;
        }

        public ContractRef<CapabilityId> capability() {
            return capability;
        }

        public ContractRef<OperationId> operation() {
            return operation;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof ConversionEdge other)) {
                return false;
            }
            return cost == other.cost && legacyFailureRank == other.legacyFailureRank && id.equals(other.id)
                && source.equals(other.source) && target.equals(other.target) && losslessness == other.losslessness
                && failure == other.failure && capability.equals(other.capability) && operation.equals(other.operation);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, source, target, cost, losslessness, failure, capability, operation, legacyFailureRank);
        }

        @Override
        public String toString() {
            return id.canonicalKey();
        }
    }

    public enum Losslessness {
        LOSSLESS("lossless"),
        LOSSY("lossy");

        private final String wireName;

        Losslessness(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    public enum FailureBehavior {
        INFALLIBLE("infallible", 0),
        RESULT("result", 1),
        DIAGNOSTIC("diagnostic", 2);

        private final String wireName;
        private final int rank;

        FailureBehavior(String wireName, int rank) {
            this.wireName = wireName;
            this.rank = rank;
        }

        public String wireName() {
            return wireName;
        }

        public int rank() {
            return rank;
        }

        private static FailureBehavior fromRank(int rank) {
            if (rank < 0) {
                throw new IllegalArgumentException("Failure rank cannot be negative");
            }
            return rank == 0 ? INFALLIBLE : rank == 1 ? RESULT : DIAGNOSTIC;
        }
    }

    public record ConversionRank(int totalCost, int lossyEdges, int failureRank, int edgeCount) implements Comparable<ConversionRank> {
        public ConversionRank {
            if (totalCost < 0 || lossyEdges < 0 || failureRank < 0 || edgeCount < 0) {
                throw new IllegalArgumentException("Conversion rank values cannot be negative");
            }
        }

        @Override
        public int compareTo(ConversionRank other) {
            int result = Integer.compare(totalCost, other.totalCost);
            if (result == 0) {
                result = Integer.compare(lossyEdges, other.lossyEdges);
            }
            if (result == 0) {
                result = Integer.compare(failureRank, other.failureRank);
            }
            if (result == 0) {
                result = Integer.compare(edgeCount, other.edgeCount);
            }
            return result;
        }
    }

    public record ConversionPath(List<ConversionEdge> edges) {
        public ConversionPath {
            edges = List.copyOf(edges);
        }

        public int totalCost() {
            return edges.stream().mapToInt(ConversionEdge::cost).sum();
        }

        public int lossyEdges() {
            return (int) edges.stream().filter(edge -> !edge.lossless()).count();
        }

        public int failureRank() {
            return edges.stream().mapToInt(ConversionEdge::failureRank).sum();
        }

        public ConversionRank rank() {
            return new ConversionRank(totalCost(), lossyEdges(), failureRank(), edges.size());
        }

        public List<TypeReference> edgeIds() {
            return edges.stream().map(ConversionEdge::id).toList();
        }
    }

    public static final class Builder {
        private final List<ConversionEdge> edges = new ArrayList<>();

        public Builder add(ConversionEdge edge) {
            edges.add(Objects.requireNonNull(edge, "edge"));
            return this;
        }

        public Builder add(TypeReference id, TypeExpr source, TypeExpr target, int cost, boolean lossless, int failureRank) {
            return add(new ConversionEdge(id, source, target, cost, lossless, failureRank));
        }

        public Builder add(TypeReference id, TypeExpr source, TypeExpr target, int cost, Losslessness losslessness,
                           FailureBehavior failure, ContractRef<CapabilityId> capability, ContractRef<OperationId> operation) {
            return add(new ConversionEdge(id, source, target, cost, losslessness, failure, capability, operation));
        }

        public ConversionGraph build() {
            var sorted = new ArrayList<>(edges);
            sorted.sort(EDGE_ORDER);
            for (int index = 1; index < sorted.size(); index++) {
                if (sorted.get(index - 1).id().equals(sorted.get(index).id())) {
                    throw new IllegalArgumentException("Duplicate conversion id: " + sorted.get(index).id().canonicalKey());
                }
            }
            return new ConversionGraph(sorted);
        }
    }

    public enum Reason {
        NO_PATH,
        AMBIGUOUS,
        SEARCH_LIMIT
    }

    public static final class ResolutionException extends IllegalStateException {
        private final Reason reason;
        private final TypeExpr source;
        private final TypeExpr target;

        private ResolutionException(Reason reason, TypeExpr source, TypeExpr target) {
            super("Conversion " + reason.name().toLowerCase() + " from " + source.canonicalJson() + " to " + target.canonicalJson());
            this.reason = reason;
            this.source = source;
            this.target = target;
        }

        public Reason reason() {
            return reason;
        }

        public TypeExpr source() {
            return source;
        }

        public TypeExpr target() {
            return target;
        }
    }

    private static final class SearchState {
        private int states;
    }
}

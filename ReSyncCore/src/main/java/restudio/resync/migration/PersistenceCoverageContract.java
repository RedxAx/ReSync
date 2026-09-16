package restudio.resync.migration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class PersistenceCoverageContract {
    public enum Proof {
        TRANSACTION_TARGET_POPULATION,
        PARTICIPANT_LIFECYCLE,
        SNAPSHOT_RESTORE
    }

    public enum State {
        PROVEN,
        MISSING
    }

    public record Requirement(String writerId, Proof proof) {
        public Requirement {
            writerId = requireId(writerId, "writerId");
            proof = Objects.requireNonNull(proof, "proof");
        }
    }

    public record Observation(
        String evidenceId,
        String writerId,
        Proof proof,
        long matchingRows,
        long population,
        String equivalenceId
    ) {
        public Observation(String evidenceId, String writerId, Proof proof, long matchingRows, long population) {
            this(evidenceId, writerId, proof, matchingRows, population, evidenceId);
        }

        public Observation {
            evidenceId = requireId(evidenceId, "evidenceId");
            writerId = requireId(writerId, "writerId");
            proof = Objects.requireNonNull(proof, "proof");
            equivalenceId = requireId(equivalenceId, "equivalenceId");
            if (population <= 0) {
                throw new IllegalArgumentException("population Must Be Positive");
            }
            if (matchingRows < 0 || matchingRows > population) {
                throw new IllegalArgumentException("matchingRows Must Be Between Zero And Population");
            }
        }

        public boolean complete() {
            return matchingRows == population;
        }

        public boolean equivalentClaim(Observation other) {
            return other != null
                && equivalenceId.equals(other.equivalenceId)
                && writerId.equals(other.writerId)
                && proof == other.proof
                && matchingRows == other.matchingRows
                && population == other.population;
        }
    }

    public record Row(Requirement requirement, State state, List<String> evidenceIds, String reason) {
        public Row {
            requirement = Objects.requireNonNull(requirement, "requirement");
            state = Objects.requireNonNull(state, "state");
            evidenceIds = List.copyOf(evidenceIds == null ? List.of() : evidenceIds);
            reason = reason == null ? "" : reason.trim();
            if (state == State.PROVEN && evidenceIds.isEmpty()) {
                throw new IllegalArgumentException("Proven Persistence Coverage Requires Evidence");
            }
            if (state == State.PROVEN && !reason.isBlank()) {
                throw new IllegalArgumentException("Proven Persistence Coverage Cannot Have A Missing Reason");
            }
            if (state == State.MISSING && reason.isBlank()) {
                throw new IllegalArgumentException("Missing Persistence Coverage Requires A Reason");
            }
        }
    }

    public record Report(List<Row> rows) {
        public Report {
            rows = List.copyOf(rows == null ? List.of() : rows);
        }

        public boolean complete() {
            return !rows.isEmpty() && rows.stream().allMatch(row -> row.state() == State.PROVEN);
        }

        public List<Row> missing() {
            return rows.stream().filter(row -> row.state() == State.MISSING).toList();
        }

        public void requireComplete() throws MigrationException {
            if (complete()) {
                return;
            }
            List<String> gaps = missing().stream()
                .map(row -> row.requirement().writerId() + ":" + row.requirement().proof())
                .toList();
            throw new MigrationException("Persistence Coverage Is Incomplete: " + gaps);
        }
    }

    private PersistenceCoverageContract() {
    }

    public static Report evaluate(Collection<Requirement> requirements, Collection<Observation> observations) {
        List<Requirement> required = requirements == null
            ? List.of()
            : requirements.stream().filter(Objects::nonNull)
                .sorted(Comparator.comparing(Requirement::writerId).thenComparing(Requirement::proof))
                .toList();
        List<Observation> observed = observations == null
            ? List.of()
            : observations.stream().filter(Objects::nonNull)
                .sorted(Comparator.comparing(Observation::evidenceId))
                .toList();
        rejectDuplicateRequirements(required);
        rejectDuplicateEvidence(observed);
        rejectConflictingEvidence(observed);
        List<Row> rows = new ArrayList<>();
        for (Requirement requirement : required) {
            List<Observation> matching = observed.stream()
                .filter(observation -> observation.writerId().equals(requirement.writerId())
                    && observation.proof() == requirement.proof())
                .toList();
            List<String> completeEvidence = matching.stream().filter(Observation::complete)
                .map(Observation::evidenceId).toList();
            if (!completeEvidence.isEmpty()) {
                rows.add(new Row(requirement, State.PROVEN, completeEvidence, ""));
                continue;
            }
            String reason = matching.isEmpty()
                ? "No Complete Exact Evidence Matches The Writer And Proof"
                : "Matching Evidence Does Not Cover Its Declared Population";
            rows.add(new Row(requirement, State.MISSING, matching.stream().map(Observation::evidenceId).toList(), reason));
        }
        return new Report(rows);
    }

    private static void rejectDuplicateRequirements(Collection<Requirement> requirements) {
        Set<String> keys = new HashSet<>();
        for (Requirement requirement : requirements) {
            String key = requirement.writerId() + '\u0000' + requirement.proof();
            if (!keys.add(key)) {
                throw new IllegalArgumentException("Persistence Coverage Requirement Is Ambiguous: " + requirement.writerId());
            }
        }
    }

    private static void rejectDuplicateEvidence(Collection<Observation> observations) {
        Set<String> ids = new HashSet<>();
        for (Observation observation : observations) {
            if (!ids.add(observation.evidenceId())) {
                throw new IllegalArgumentException("Persistence Coverage Evidence Is Ambiguous: " + observation.evidenceId());
            }
        }
    }

    private static void rejectConflictingEvidence(Collection<Observation> observations) {
        Map<Requirement, List<Observation>> claims = new LinkedHashMap<>();
        for (Observation observation : observations) {
            Requirement requirement = new Requirement(observation.writerId(), observation.proof());
            claims.computeIfAbsent(requirement, ignored -> new ArrayList<>()).add(observation);
        }
        for (Map.Entry<Requirement, List<Observation>> claim : claims.entrySet()) {
            List<Observation> evidence = claim.getValue();
            if (evidence.size() < 2) {
                continue;
            }
            Observation baseline = evidence.getFirst();
            if (evidence.stream().skip(1).allMatch(baseline::equivalentClaim)) {
                continue;
            }
            Requirement requirement = claim.getKey();
            throw new IllegalArgumentException(
                "Persistence Coverage Evidence Conflicts For Requirement: " + requirement.writerId() + ':' + requirement.proof());
        }
    }

    private static String requireId(String value, String name) {
        String id = MigrationCanonical.requireText(value, name).trim();
        if (!id.matches("[a-z0-9][a-z0-9._-]*")) {
            throw new IllegalArgumentException(name + " Must Be A Stable Lowercase Identifier");
        }
        return id;
    }
}



package restudio.resync.migration;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PersistenceRootReadiness {
    public static final int REPORT_VERSION = 3;

    public enum Scope {
        LOCAL,
        EXTERNAL_AFFECTED
    }

    public enum State {
        REGISTERED,
        UNAVAILABLE
    }

    public record Owner(String owner, Path root, boolean required, PersistenceParticipantClassification classification,
                        State state, String reason) {
        public Owner(String owner, Path root, boolean required, State state, String reason) {
            this(owner, root, required, PersistenceParticipantClassification.AUTHORITATIVE, state, reason);
        }

        public Owner {
            owner = requireText(owner, "owner");
            root = requirePath(root, "root");
            classification = Objects.requireNonNull(classification, "classification");
            state = Objects.requireNonNull(state, "state");
            reason = reason == null ? "" : reason.trim();
            if (state == State.UNAVAILABLE && reason.isBlank()) {
                throw new IllegalArgumentException("Unavailable Persistence Owner Must Have A Reason: " + owner);
            }
            if (state == State.REGISTERED && !reason.isBlank()) {
                throw new IllegalArgumentException("Registered Persistence Owner Cannot Have An Unavailable Reason: " + owner);
            }
        }

        public static Owner registered(String owner, Path root, boolean required) {
            return registered(owner, root, required, PersistenceParticipantClassification.AUTHORITATIVE);
        }

        public static Owner registered(String owner, Path root, boolean required,
                                       PersistenceParticipantClassification classification) {
            return new Owner(owner, root, required, classification, State.REGISTERED, "");
        }

        public static Owner unavailable(String owner, Path root, boolean required, String reason) {
            return unavailable(owner, root, required, PersistenceParticipantClassification.AUTHORITATIVE, reason);
        }

        public static Owner unavailable(String owner, Path root, boolean required,
                                        PersistenceParticipantClassification classification, String reason) {
            return new Owner(owner, root, required, classification, State.UNAVAILABLE, reason);
        }
    }

    public record UncoveredWriter(String owner, Path root, PersistenceParticipantClassification classification,
                                  Scope scope, String reason, String authority) {
        public UncoveredWriter(String owner, Path root, String reason) {
            this(owner, root, PersistenceParticipantClassification.AUTHORITATIVE, Scope.LOCAL, reason, "");
        }

        public UncoveredWriter(String owner, Path root, String reason,
                               PersistenceParticipantClassification classification) {
            this(owner, root, classification, Scope.LOCAL, reason, "");
        }

        public UncoveredWriter(String owner, Path root, String reason, Scope scope, String authority) {
            this(owner, root, PersistenceParticipantClassification.AUTHORITATIVE, scope, reason, authority);
        }

        public UncoveredWriter(String owner, Path root, String reason,
                               PersistenceParticipantClassification classification, Scope scope, String authority) {
            this(owner, root, classification, scope, reason, authority);
        }

        public UncoveredWriter {
            owner = requireText(owner, "writer owner");
            root = requirePath(root, "writer root");
            classification = Objects.requireNonNull(classification, "writer classification");
            scope = Objects.requireNonNull(scope, "writer scope");
            reason = requireText(reason, "writer reason");
            authority = authority == null ? "" : authority.trim();
            if (scope == Scope.EXTERNAL_AFFECTED && authority.isBlank()) {
                throw new IllegalArgumentException("External Affected Writer Must Declare Its Authority: " + owner);
            }
        }

        public static UncoveredWriter of(String owner, Path root, String reason) {
            return local(owner, root, PersistenceParticipantClassification.AUTHORITATIVE, reason);
        }

        public static UncoveredWriter of(String owner, Path root, PersistenceParticipantClassification classification,
                                         String reason) {
            return local(owner, root, classification, reason);
        }

        public static UncoveredWriter local(String owner, Path root, String reason) {
            return local(owner, root, PersistenceParticipantClassification.AUTHORITATIVE, reason);
        }

        public static UncoveredWriter local(String owner, Path root, PersistenceParticipantClassification classification,
                                            String reason) {
            return new UncoveredWriter(owner, root, classification, Scope.LOCAL, reason, "");
        }

        public static UncoveredWriter externalAffected(String owner, Path root, String reason, String authority) {
            return externalAffected(owner, root, PersistenceParticipantClassification.AUTHORITATIVE, reason, authority);
        }

        public static UncoveredWriter externalAffected(String owner, Path root,
                                                       PersistenceParticipantClassification classification,
                                                       String reason, String authority) {
            return new UncoveredWriter(owner, root, classification, Scope.EXTERNAL_AFFECTED, reason, authority);
        }

        public String id() {
            return owner;
        }

        public boolean local() {
            return scope == Scope.LOCAL;
        }

        public boolean externalAffected() {
            return scope == Scope.EXTERNAL_AFFECTED;
        }

        public boolean isLocal() {
            return local();
        }

        public boolean isExternalAffected() {
            return externalAffected();
        }
    }

    private final List<Owner> owners;
    private final List<UncoveredWriter> uncoveredWriters;
    private final List<PersistenceExternalInput.Input> externalInputs;

    public PersistenceRootReadiness(Collection<Owner> owners) {
        this(owners, List.of(), List.of());
    }

    public PersistenceRootReadiness(Collection<Owner> owners, Collection<UncoveredWriter> uncoveredWriters) {
        this(owners, uncoveredWriters, List.of());
    }

    public PersistenceRootReadiness(Collection<Owner> owners, Collection<UncoveredWriter> uncoveredWriters,
                                    Collection<PersistenceExternalInput.Input> externalInputs) {
        Map<String, Owner> unique = new LinkedHashMap<>();
        if (owners != null) {
            owners.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Owner::owner))
                .forEach(owner -> {
                    if (unique.putIfAbsent(owner.owner(), owner) != null) {
                        throw new IllegalArgumentException("Persistence Owner Is Ambiguous: " + owner.owner());
                    }
                });
        }
        this.owners = List.copyOf(unique.values());
        Map<String, UncoveredWriter> uniqueWriters = new LinkedHashMap<>();
        if (uncoveredWriters != null) {
            uncoveredWriters.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(UncoveredWriter::id))
                .forEach(writer -> {
                    if (uniqueWriters.putIfAbsent(writer.id(), writer) != null) {
                        throw new IllegalArgumentException("Uncovered Persistence Writer Is Ambiguous: " + writer.id());
                    }
                });
        }
        this.uncoveredWriters = List.copyOf(uniqueWriters.values());
        Map<String, PersistenceExternalInput.Input> uniqueInputs = new LinkedHashMap<>();
        if (externalInputs != null) {
            externalInputs.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(PersistenceExternalInput.Input::id))
                .forEach(input -> {
                    if (uniqueInputs.putIfAbsent(input.id(), input) != null) {
                        throw new IllegalArgumentException("External Persistence Input Is Ambiguous: " + input.id());
                    }
                });
        }
        this.externalInputs = List.copyOf(uniqueInputs.values());
    }

    public static PersistenceRootReadiness empty() {
        return new PersistenceRootReadiness(List.of());
    }

    public List<Owner> owners() {
        return owners;
    }

    public List<UncoveredWriter> uncoveredWriters() {
        return uncoveredWriters;
    }

    public List<UncoveredWriter> localWriters() {
        return uncoveredWriters.stream().filter(UncoveredWriter::local).toList();
    }

    public List<UncoveredWriter> localUncoveredWriters() {
        return localWriters();
    }

    public List<UncoveredWriter> externalAffectedWriters() {
        return uncoveredWriters.stream().filter(UncoveredWriter::externalAffected).toList();
    }

    public List<UncoveredWriter> externalWriters() {
        return externalAffectedWriters();
    }

    public List<String> uncoveredWriterIds() {
        return uncoveredWriters.stream().map(UncoveredWriter::id).toList();
    }

    public List<String> localUncoveredWriterIds() {
        return localWriters().stream().map(UncoveredWriter::id).toList();
    }

    public List<PersistenceExternalInput.Input> externalInputs() {
        return externalInputs;
    }

    public boolean writerInventoryComplete() {
        return localWriters().isEmpty();
    }

    public List<Owner> requiredGaps() {
        return owners.stream()
            .filter(owner -> owner.required() && owner.state() != State.REGISTERED)
            .toList();
    }

    public List<Owner> unavailableOwners() {
        return owners.stream().filter(owner -> owner.state() == State.UNAVAILABLE).toList();
    }

    public boolean complete() {
        return !owners.isEmpty() && requiredGaps().isEmpty() && writerInventoryComplete();
    }

    public int reportVersion() {
        return REPORT_VERSION;
    }

    public String canonicalReport() {
        StringBuilder report = new StringBuilder();
        report.append("version=").append(REPORT_VERSION).append('\n');
        report.append("complete=").append(complete()).append('\n');
        report.append("owners=").append(owners.size()).append('\n');
        for (Owner owner : owners) {
            report.append("owner=").append(MigrationCanonical.encode(owner.owner())).append('|')
                .append(MigrationCanonical.encode(owner.root().toString())).append('|')
                .append(owner.required()).append('|').append(owner.classification().name()).append('|')
                .append(owner.state().name()).append('|')
                .append(MigrationCanonical.encode(owner.reason())).append('\n');
        }
        report.append("uncovered-writers=").append(uncoveredWriters.size()).append('\n');
        for (UncoveredWriter writer : uncoveredWriters) {
            report.append("writer=").append(MigrationCanonical.encode(writer.id())).append('|')
                .append(MigrationCanonical.encode(writer.root().toString())).append('|')
                .append(writer.classification().name()).append('|')
                .append(writer.scope().name()).append('|')
                .append(MigrationCanonical.encode(writer.reason())).append('|')
                .append(MigrationCanonical.encode(writer.authority())).append('\n');
        }
        report.append("external-inputs=").append(externalInputs.size()).append('\n');
        for (PersistenceExternalInput.Input input : externalInputs) {
            report.append("external-input=").append(MigrationCanonical.encode(input.id())).append('|')
                .append(MigrationCanonical.encode(input.path().toString())).append('|')
                .append(input.kind().name()).append('|')
                .append(input.readOnly()).append('|')
                .append(input.excludedFromPersistence()).append('|')
                .append(MigrationCanonical.encode(input.reason())).append('\n');
        }
        return report.toString();
    }

    public String reportHash() {
        return MigrationCanonical.sha256(canonicalReport());
    }

    public Owner owner(String owner) {
        if (owner == null || owner.isBlank()) {
            return null;
        }
        return owners.stream().filter(candidate -> candidate.owner().equals(owner.trim())).findFirst().orElse(null);
    }

    public Map<String, String> unavailableReasons() {
        Map<String, String> reasons = new LinkedHashMap<>();
        for (Owner owner : unavailableOwners()) {
            reasons.put(owner.owner(), owner.reason());
        }
        return Collections.unmodifiableMap(reasons);
    }

    public Map<String, Object> payload() {
        List<Map<String, Object>> entries = owners.stream().map(owner -> Map.<String, Object>of(
            "owner", owner.owner(),
            "root", owner.root().toString(),
            "required", owner.required(),
            "classification", owner.classification().name(),
            "state", owner.state().name(),
            "reason", owner.reason())).toList();
        List<Map<String, Object>> writerEntries = uncoveredWriters.stream().map(writer -> Map.<String, Object>of(
            "id", writer.id(),
            "owner", writer.owner(),
            "root", writer.root().toString(),
            "classification", writer.classification().name(),
            "scope", writer.scope().name(),
            "reason", writer.reason(),
            "authority", writer.authority())).toList();
        List<Map<String, Object>> inputEntries = externalInputs.stream().map(input -> Map.<String, Object>of(
            "id", input.id(),
            "path", input.path().toString(),
            "kind", input.kind().name(),
            "readOnly", input.readOnly(),
            "excludedFromPersistence", input.excludedFromPersistence(),
            "reason", input.reason())).toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("complete", complete());
        payload.put("owners", entries);
        payload.put("registeredOwners", owners.stream().filter(owner -> owner.state() == State.REGISTERED).map(Owner::owner).toList());
        payload.put("unavailableOwners", unavailableOwners().stream().map(Owner::owner).toList());
        payload.put("unavailableReasons", unavailableReasons());
        payload.put("requiredGaps", requiredGaps().stream().map(Owner::owner).toList());
        payload.put("writerInventoryComplete", writerInventoryComplete());
        payload.put("uncoveredWriterIds", uncoveredWriterIds());
        payload.put("localUncoveredWriterIds", localUncoveredWriterIds());
        payload.put("uncoveredWriters", writerEntries);
        payload.put("externalAffectedWriters", externalAffectedWriters().stream().map(UncoveredWriter::id).toList());
        payload.put("externalInputs", inputEntries);
        return Collections.unmodifiableMap(payload);
    }

    public void requireComplete() throws MigrationException {
        if (complete()) {
            return;
        }
        String gaps = requiredGaps().stream().map(owner -> owner.owner() + ": " + owner.reason()).toList().toString();
        String writerGaps = localWriters().stream()
            .map(writer -> writer.id() + ": " + writer.reason())
            .toList().toString();
        throw new MigrationException("Persistence Root Readiness Is Incomplete: owners=" + gaps + ", uncoveredWriters=" + writerGaps);
    }

    private static Path requirePath(Path path, String name) {
        Objects.requireNonNull(path, name);
        return path.toAbsolutePath().normalize();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " Must Not Be Blank");
        }
        return value.trim();
    }
}

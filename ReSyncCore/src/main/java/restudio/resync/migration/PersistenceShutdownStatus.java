package restudio.resync.migration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record PersistenceShutdownStatus(State state, List<String> flushedOwners, List<String> quiescedOwners,
                                        Map<String, String> failures, String reason) {
    public PersistenceShutdownStatus {
        state = Objects.requireNonNull(state, "state");
        flushedOwners = sortedOwners(flushedOwners);
        quiescedOwners = sortedOwners(quiescedOwners);
        failures = sortedFailures(failures);
        reason = reason == null ? "" : reason.trim();
    }

    public static PersistenceShutdownStatus open() {
        return new PersistenceShutdownStatus(State.OPEN, List.of(), List.of(), Map.of(), "");
    }

    public static PersistenceShutdownStatus quiescing() {
        return new PersistenceShutdownStatus(State.QUIESCING, List.of(), List.of(), Map.of(), "");
    }

    public static PersistenceShutdownStatus quiesced(List<String> flushedOwners, List<String> quiescedOwners) {
        return new PersistenceShutdownStatus(State.QUIESCED, flushedOwners, quiescedOwners, Map.of(), "");
    }

    public static PersistenceShutdownStatus closed(List<String> flushedOwners, List<String> quiescedOwners) {
        return new PersistenceShutdownStatus(State.CLOSED, flushedOwners, quiescedOwners, Map.of(), "");
    }

    public static PersistenceShutdownStatus failed(List<String> flushedOwners, List<String> quiescedOwners,
                                                   Map<String, String> failures, String reason) {
        return new PersistenceShutdownStatus(State.FAILED, flushedOwners, quiescedOwners, failures, reason);
    }

    public boolean started() {
        return state != State.OPEN;
    }

    public boolean closed() {
        return state == State.CLOSED || state == State.FAILED;
    }

    public Map<String, Object> payload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("state", state.name());
        payload.put("started", started());
        payload.put("flushedOwners", flushedOwners);
        payload.put("quiescedOwners", quiescedOwners);
        payload.put("failures", failures);
        if (!reason.isBlank()) {
            payload.put("reason", reason);
        }
        return Collections.unmodifiableMap(payload);
    }

    private static List<String> sortedOwners(List<String> owners) {
        if (owners == null || owners.isEmpty()) {
            return List.of();
        }
        return owners.stream().filter(Objects::nonNull).distinct().sorted().toList();
    }

    private static Map<String, String> sortedFailures(Map<String, String> failures) {
        if (failures == null || failures.isEmpty()) {
            return Map.of();
        }
        Map<String, String> sorted = new LinkedHashMap<>();
        failures.entrySet().stream()
            .filter(entry -> entry.getKey() != null)
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> sorted.put(entry.getKey(), entry.getValue() == null ? "" : entry.getValue()));
        return Collections.unmodifiableMap(sorted);
    }

    public enum State {
        OPEN,
        QUIESCING,
        QUIESCED,
        CLOSED,
        FAILED
    }
}

package restudio.resync.flow.diagnostic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DiagnosticSet implements Iterable<Diagnostic> {
    private static final Comparator<Diagnostic> ORDER = Comparator
        .comparing(Diagnostic::code)
        .thenComparing(diagnostic -> diagnostic.phase().wireName())
        .thenComparing(Diagnostic::stage)
        .thenComparing(diagnostic -> diagnostic.severity().wireName())
        .thenComparing(diagnostic -> value(diagnostic.serverId() == null ? null : diagnostic.serverId().canonicalText()))
        .thenComparing(diagnostic -> diagnostic.resource() == null ? "" : diagnostic.resource().canonicalText())
        .thenComparing(diagnostic -> value(diagnostic.nodeId() == null ? null : diagnostic.nodeId().canonicalText()))
        .thenComparing(diagnostic -> value(diagnostic.pinId() == null ? null : diagnostic.pinId().canonicalText()))
        .thenComparing(diagnostic -> value(diagnostic.catalogGeneration()))
        .thenComparing(diagnostic -> value(diagnostic.ownerId() == null ? null : diagnostic.ownerId().canonicalText()))
        .thenComparing(diagnostic -> diagnostic.messageKey().owner().canonicalText())
        .thenComparing(diagnostic -> diagnostic.messageKey().id().canonicalText())
        .thenComparing(Diagnostic::message)
        .thenComparing(Diagnostic::remediation)
        .thenComparing(diagnostic -> diagnostic.correlationId().toString())
        .thenComparing(diagnostic -> DiagnosticJson.write(diagnostic.evidence()))
        .thenComparing(diagnostic -> DiagnosticJson.write(diagnostic.unknown()));

    private final List<Diagnostic> diagnostics;

    public DiagnosticSet(Collection<? extends Diagnostic> diagnostics) {
        List<Diagnostic> ordered = new ArrayList<>();
        if (diagnostics != null) {
            for (Diagnostic diagnostic : diagnostics) {
                ordered.add(Objects.requireNonNull(diagnostic, "diagnostic"));
            }
        }
        ordered.sort(ORDER);
        this.diagnostics = List.copyOf(ordered);
    }

    public static DiagnosticSet of(Diagnostic... diagnostics) {
        return new DiagnosticSet(diagnostics == null ? List.of() : List.of(diagnostics));
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    public List<Map<String, Object>> toMaps() {
        return diagnostics.stream().map(Diagnostic::toMap).toList();
    }

    public List<Map<String, Object>> toRedactedMaps() {
        return diagnostics.stream().map(Diagnostic::toRedactedMap).toList();
    }

    public List<Map<String, Object>> toRedactedMaps(DiagnosticRedaction exportRedaction) {
        return diagnostics.stream().map(diagnostic -> diagnostic.toRedactedMap(exportRedaction)).toList();
    }

    public String toJson() {
        return DiagnosticJson.write(toMaps());
    }

    public String toRedactedJson() {
        return DiagnosticJson.write(toRedactedMaps());
    }

    public String toRedactedJson(DiagnosticRedaction exportRedaction) {
        return DiagnosticJson.write(toRedactedMaps(exportRedaction));
    }

    public int size() {
        return diagnostics.size();
    }

    public boolean isEmpty() {
        return diagnostics.isEmpty();
    }

    @Override
    public Iterator<Diagnostic> iterator() {
        return diagnostics.iterator();
    }

    private static String value(Object value) {
        return value == null ? "" : value.toString();
    }
}

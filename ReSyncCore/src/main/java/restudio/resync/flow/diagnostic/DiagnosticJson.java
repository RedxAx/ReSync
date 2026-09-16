package restudio.resync.flow.diagnostic;

import restudio.resync.flow.canonical.CanonicalJson;

final class DiagnosticJson {
    private DiagnosticJson() {
    }

    static String write(Object value) {
        return CanonicalJson.canonicalize(value);
    }
}

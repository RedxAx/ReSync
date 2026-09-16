package restudio.resync.contract.diagnostic;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public enum RuntimePostCommitDiagnosticCode {
    CLEANUP_FAILED("RUNTIME.POST_COMMIT_CLEANUP_FAILED"),
    OBSERVER_FAILED("RUNTIME.POST_COMMIT_OBSERVER_FAILED");

    private static final Set<String> WIRE_NAMES = Collections.unmodifiableSet(
        new LinkedHashSet<>(Arrays.stream(values()).map(RuntimePostCommitDiagnosticCode::wireName).toList()));

    private final String wireName;

    RuntimePostCommitDiagnosticCode(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Set<String> wireNames() {
        return WIRE_NAMES;
    }

    public static RuntimePostCommitDiagnosticCode require(String wireName) {
        for (RuntimePostCommitDiagnosticCode code : values()) {
            if (code.wireName.equals(wireName)) {
                return code;
            }
        }
        throw new IllegalArgumentException("Unsupported runtime post-commit diagnostic code: " + wireName);
    }
}

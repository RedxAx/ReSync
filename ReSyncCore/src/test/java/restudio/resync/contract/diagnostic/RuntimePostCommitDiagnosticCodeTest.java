package restudio.resync.contract.diagnostic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimePostCommitDiagnosticCodeTest {
    @Test
    void sharedOwnerEnumeratesOnlyCataloguedPostCommitCodes() {
        DiagnosticCodeCatalog catalog = DiagnosticCodeCatalog.defaultCatalog();

        for (RuntimePostCommitDiagnosticCode code : RuntimePostCommitDiagnosticCode.values()) {
            assertTrue(catalog.contains(code.wireName()));
            assertEquals(code, RuntimePostCommitDiagnosticCode.require(code.wireName()));
        }

        assertEquals(2, RuntimePostCommitDiagnosticCode.wireNames().size());
        assertThrows(IllegalArgumentException.class,
            () -> RuntimePostCommitDiagnosticCode.require("RUNTIME.POST_COMMIT_UNKNOWN"));
    }
}

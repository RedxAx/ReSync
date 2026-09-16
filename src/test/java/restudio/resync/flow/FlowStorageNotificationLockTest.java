package restudio.resync.flow;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowStorageNotificationLockTest {
    private static final Path SOURCE = Path.of("src/main/java/restudio/resync/flow/FlowStorage.java");

    @Test
    void coreMutationCallbacksFollowTheStorageMonitorScope() throws Exception {
        String source = Files.readString(SOURCE).replaceAll("\\s+", "");
        String delete = method(source, "privateCoreGraphStorageBoundary.CoreGraphTombstonedeleteCoreGraphDocument(",
            "privateCoreGraphStorageBoundary.CoreGraphTombstoneverifyCoreGraphDeletion(");
        String save = method(source, "privateCoreSaveResultsaveCoreGraphDocument(",
            "privatebyte[]encodeCoreGraph(");

        assertFalse(delete.startsWith("privatesynchronized"));
        assertFalse(save.startsWith("privatesynchronized"));
        assertNotificationAfterMonitor(delete);
        assertNotificationAfterMonitor(save);
    }

    private static String method(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0 && end > start);
        return source.substring(start, end);
    }

    private static void assertNotificationAfterMonitor(String method) {
        int monitor = method.indexOf("synchronized(this)");
        int blockStart = method.indexOf('{', monitor);
        int blockEnd = matchingBrace(method, blockStart);
        int notification = method.indexOf("notifyGraphChanged(safeId");
        assertTrue(monitor >= 0 && blockStart > monitor && blockEnd > blockStart);
        assertTrue(notification > blockEnd);
    }

    private static int matchingBrace(String source, int open) {
        int depth = 0;
        for (int index = open; index >= 0 && index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }
}

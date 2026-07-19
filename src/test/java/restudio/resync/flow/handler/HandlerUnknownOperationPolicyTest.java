package restudio.resync.flow.handler;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HandlerUnknownOperationPolicyTest {
    private static final Pattern EXECUTE_METHOD = Pattern.compile(
        "public void execute\\(FlowContext (?:ctx|context), FlowNode node\\) \\{(?<body>[\\s\\S]*?)\\n    \\}"
    );

    @Test
    void mappedHandlersNeverSilentlyIgnoreUnknownOperations() throws Exception {
        Path root = Path.of("src", "main", "java", "restudio", "resync", "flow", "handler");
        List<Path> sources;
        try (Stream<Path> paths = Files.walk(root)) {
            sources = paths.filter(path -> path.toString().endsWith(".java")).toList();
        }

        int audited = 0;
        for (Path source : sources) {
            String text = Files.readString(source);
            Matcher matcher = EXECUTE_METHOD.matcher(text);
            if (!matcher.find() || !matcher.group("body").contains("operations.get(operation)")) {
                continue;
            }
            audited++;
            String body = matcher.group("body");
            boolean guarded = body.contains("op == null") || body.contains("handler == null") || body.contains("action == null")
                || body.contains("throw new IllegalArgumentException") || body.contains("throw new IllegalStateException");
            assertTrue(guarded, source.getFileName() + " can silently ignore an unknown operation");
        }
        assertTrue(audited > 0, "No mapped handler execute methods were audited");
    }
}

package restudio.resync.commands;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import restudio.resync.ReSync;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.qa.QaService;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QaCommandTest {
    private TestReSync plugin;
    private ConsoleCommandSenderMock console;
    private FakeService service;
    private QaCommand command;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.loadSimple(TestReSync.class);
        console = new CapturingConsole();
        console.addAttachment(plugin, "resync.qa", true);
        service = new FakeService();
        command = new QaCommand(() -> service);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void explicitConsoleDenialPreventsExecutionAndDiscovery() {
        console.addAttachment(plugin, "resync.qa", false);
        QaCommand denied = new QaCommand(() -> {
            throw new AssertionError("Unauthorized access to the QA service");
        });

        assertTrue(denied.execute(console, new String[]{"run", "node.run", "{}"}));
        assertEquals("QA_PERMISSION_DENIED", response().get("code").getAsString());
        assertEquals(List.of(), denied.complete(console, new String[]{"run", ""}));
    }

    @Test
    void operatorCannotBypassAnExplicitQaDenial() {
        PlayerMock player = MockBukkit.getMock().addPlayer();
        player.setOp(true);
        player.addAttachment(plugin, "resync.command", true);
        player.addAttachment(plugin, "resync.qa", false);

        command.execute(player, new String[]{"list"});

        assertEquals("QA_PERMISSION_DENIED", json(player.nextComponentMessage()).get("code").getAsString());
        assertNull(player.nextComponentMessage());
        assertEquals(List.of(), command.complete(player, new String[]{""}));
    }

    @Test
    void dedicatedQaPermissionDoesNotRequireTheGeneralCommandPermission() {
        console.addAttachment(plugin, "resync.command", false);

        command.execute(console, new String[]{"list"});

        assertEquals(JsonParser.parseString(CanonicalJson.canonicalize(service.describe())), response());
    }

    @Test
    void missingServiceReturnsAStructuredUnavailableResult() {
        QaCommand unavailable = new QaCommand(() -> null);

        unavailable.execute(console, new String[]{"list"});

        assertEquals("QA_UNAVAILABLE", response().get("code").getAsString());
        assertEquals(List.of(), unavailable.complete(console, new String[]{"describe", ""}));
    }

    @Test
    void helpExplainsDiscoverySubmissionAndPolling() {
        command.execute(console, new String[]{});

        JsonObject help = response();
        assertEquals(5, help.getAsJsonArray("commands").size());
        assertTrue(help.get("message").getAsString().contains("runId"));
        assertTrue(help.getAsJsonArray("commands").get(3).getAsJsonObject().get("usage").getAsString().contains("JSON object"));
    }

    @Test
    void describeReturnsTheSharedOperationDescriptor() {
        command.execute(console, new String[]{"DeScRiBe", "node.run"});

        assertEquals(JsonParser.parseString(CanonicalJson.canonicalize(service.describe("node.run"))), response());
    }

    @Test
    void runPreservesJsonTokenBoundariesAndReturnsItsReceipt() {
        String[] args = {"run", "node.run", "{\"text\":", "\"hello", "world\",", "\"nested\":", "{\"enabled\":true},", "\"list\":[1,null,\"x\"]}"};

        command.execute(console, args);

        assertEquals("{\"text\": \"hello world\", \"nested\": {\"enabled\":true}, \"list\":[1,null,\"x\"]}", service.parsedJson);
        assertEquals("hello world", service.submittedInput.get("text"));
        assertEquals(Map.of("enabled", true), service.submittedInput.get("nested"));
        assertSame(console, service.submittedActor);
        assertEquals("node.run", service.submittedOperation);
        JsonObject result = response();
        assertEquals("accepted", result.get("status").getAsString());
        assertEquals(service.runId.toString(), result.get("runId").getAsString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "true", "\"text\"", "{\"x\":}", "{} {}", "{x:1}"})
    void malformedOrNonObjectJsonDoesNotSubmitAnOperation(String input) {
        command.execute(console, new String[]{"run", "node.run", input});

        assertEquals("QA_INVALID_ARGUMENT", response().get("code").getAsString());
        assertEquals(input, service.parsedJson);
        assertNull(service.submittedInput);
    }

    @ParameterizedTest
    @ValueSource(strings = {"help extra", "list extra", "describe", "describe node.run extra", "run node.run", "poll", "poll a b", "unknown"})
    void invalidCommandShapesReturnStructuredUsage(String input) {
        command.execute(console, input.split(" "));

        JsonObject error = response();
        assertEquals("QA_INVALID_ARGUMENT", error.get("code").getAsString());
        assertTrue(error.get("usage").getAsString().startsWith("/resync qa "));
        assertNull(service.submittedInput);
        assertNull(service.polledId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "1-1-1-1-1"})
    void malformedRunIdsNeverReachPolling(String id) {
        command.execute(console, new String[]{"poll", id});

        assertEquals("QA_INVALID_ARGUMENT", response().get("code").getAsString());
        assertNull(service.polledId);
    }

    @Test
    void pendingRunReturnsImmediatelyAndCompletionRequiresPolling() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> command.execute(console, new String[]{"run", "node.run", "{}"}));
        assertEquals("accepted", response().get("status").getAsString());
        assertFalse(service.completion.isDone());

        command.execute(console, new String[]{"poll", service.runId.toString()});
        assertEquals("running", response().get("status").getAsString());

        service.completion.complete(Map.of("status", "completed", "runId", service.runId.toString(), "outputs", Map.of("value", "§a<red>value")));
        assertNull(console.nextComponentMessage());
        command.execute(console, new String[]{"poll", service.runId.toString()});

        JsonObject completed = response();
        assertEquals("completed", completed.get("status").getAsString());
        assertEquals("§a<red>value", completed.getAsJsonObject("outputs").get("value").getAsString());
        assertSame(console, service.polledActor);
        assertEquals(service.runId, service.polledId);
    }

    @Test
    void completionUsesTheSharedOperationCatalogAndCaseIndependentPrefixes() {
        assertEquals(List.of("help", "list", "describe", "run", "poll"), command.complete(console, new String[]{""}));
        assertEquals(List.of("run"), command.complete(console, new String[]{"R"}));
        assertEquals(List.of("node.run", "node.describe"), command.complete(console, new String[]{"run", "NODE."}));
        assertEquals(List.of("function.run"), command.complete(console, new String[]{"describe", "function."}));
        assertEquals(List.of(), command.complete(console, new String[]{"run", "node.run", ""}));
    }

    @Test
    void sharedServiceFailureStillReturnsExactlyOneJsonResult() {
        QaCommand failed = new QaCommand(() -> {
            throw new IllegalStateException("Unavailable during shutdown");
        });

        failed.execute(console, new String[]{"list"});

        assertEquals("QA_FAILED", response().get("code").getAsString());
        assertEquals(List.of(), failed.complete(console, new String[]{"run", ""}));
    }

    private JsonObject response() {
        Component message = console.nextComponentMessage();
        assertNotNull(message);
        assertNull(console.nextComponentMessage());
        return json(message);
    }

    private JsonObject json(Component message) {
        TextComponent text = assertInstanceOf(TextComponent.class, message);
        assertTrue(text.children().isEmpty());
        assertNull(text.color());
        return JsonParser.parseString(text.content()).getAsJsonObject();
    }

    private static final class CapturingConsole extends ConsoleCommandSenderMock {
        private final Queue<Component> messages = new ArrayDeque<>();

        @Override
        public void sendMessage(Component message) {
            messages.add(message);
        }

        @Override
        public Component nextComponentMessage() {
            return messages.poll();
        }
    }

    private static final class FakeService implements QaService {
        private final UUID runId = UUID.fromString("cad118c2-3bc0-45b8-a062-35d893a29cfe");
        private final CompletableFuture<Map<String, Object>> completion = new CompletableFuture<>();
        private String parsedJson;
        private String submittedOperation;
        private Map<String, Object> submittedInput;
        private CommandSender submittedActor;
        private CommandSender polledActor;
        private UUID polledId;

        @Override
        public Map<String, Object> describe() {
            return Map.of("version", 1, "operations", List.of(describe("node.run"), describe("node.describe"), describe("function.run")), "limits", Map.of("active", 16));
        }

        @Override
        public Map<String, Object> describe(String operation) {
            return Map.of("id", operation, "description", "Describe or run the selected operation", "input", Map.of("text", "string"));
        }

        @Override
        public Map<String, Object> submit(CommandSender actor, String operation, Map<String, Object> input) {
            submittedActor = actor;
            submittedOperation = operation;
            submittedInput = input;
            return Map.of("status", "accepted", "runId", runId.toString());
        }

        @Override
        public Map<String, Object> poll(CommandSender actor, UUID id) {
            polledActor = actor;
            polledId = id;
            return completion.getNow(Map.of("status", "running", "runId", id.toString()));
        }

        @Override
        public Map<String, Object> parse(String json) {
            parsedJson = json;
            return QaService.super.parse(json);
        }

        @Override
        public void close() {
        }
    }

    public static class TestReSync extends ReSync {
        @Override
        public void onEnable() {
        }

        @Override
        public void onDisable() {
        }
    }
}

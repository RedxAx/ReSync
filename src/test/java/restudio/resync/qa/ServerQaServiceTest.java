package restudio.resync.qa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import restudio.resync.ReSync;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerQaServiceTest {
    private TestReSync plugin;
    private ConsoleCommandSenderMock console;
    private ServerQaService service;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.loadSimple(TestReSync.class);
        console = MockBukkit.getMock().getConsoleSender();
        console.addAttachment(plugin, "resync.qa", true);
        service = new ServerQaService(() -> 23L);
    }

    @AfterEach
    void tearDown() {
        service.close();
        MockBukkit.unmock();
    }

    @Test
    void requestReplayCreditsTheBalanceOnceWhilePendingAndAfterCompletion() {
        AtomicInteger balance = new AtomicInteger(100);
        CompletableFuture<Map<String, Object>> completion = new CompletableFuture<>();
        register((actor, operation, input) -> {
            balance.addAndGet(((Number) input.get("amount")).intValue());
            return completion;
        });
        UUID id = UUID.randomUUID();
        Map<String, Object> request = request(id, 25);

        Map<String, Object> accepted = service.submit(console, "ledger.credit", request);
        Map<String, Object> pendingReplay = service.submit(console, "ledger.credit", service.parse(service.stringify(request)));

        assertEquals("accepted", accepted.get("status"));
        assertEquals(accepted, pendingReplay);
        assertEquals(id.toString(), accepted.get("runId"));
        assertEquals(23L, ((Number) accepted.get("authorityEpoch")).longValue());
        assertEquals(125, balance.get());

        completion.complete(Map.of("balance", balance.get()));
        Map<String, Object> completed = service.poll(console, id);
        Map<String, Object> completedReplay = service.submit(console, "ledger.credit", request);

        assertEquals("completed", completed.get("status"));
        assertEquals(completed, completedReplay);
        assertEquals(125, ((Number) map(completed.get("result")).get("balance")).intValue());
        assertEquals(125, balance.get());
    }

    @Test
    void changedPayloadOrOperationCannotReuseAnAdmittedRequestId() {
        AtomicInteger balance = new AtomicInteger(100);
        CompletableFuture<Map<String, Object>> completion = new CompletableFuture<>();
        register((actor, operation, input) -> {
            balance.addAndGet(((Number) input.get("amount")).intValue());
            return completion;
        });
        UUID id = UUID.randomUUID();
        service.submit(console, "ledger.credit", request(id, 25));

        assertCode("QA_REQUEST_CONFLICT", service.submit(console, "ledger.credit", request(id, 26)));
        assertCode("QA_REQUEST_CONFLICT", service.submit(console, "ledger.debit", request(id, 25)));
        assertEquals(125, balance.get());

        completion.complete(Map.of("balance", balance.get()));

        assertCode("QA_REQUEST_CONFLICT", service.submit(console, "ledger.credit", request(id, 26)));
        assertCode("QA_REQUEST_CONFLICT", service.submit(console, "ledger.debit", request(id, 25)));
        assertEquals(125, balance.get());
        assertEquals("completed", service.poll(console, id).get("status"));
    }

    @Test
    void anotherAuthorizedActorCannotReadOrReplayAnOwnersReceipt() {
        PlayerMock owner = operator();
        PlayerMock outsider = operator();
        AtomicInteger balance = new AtomicInteger(100);
        register((actor, operation, input) -> CompletableFuture.completedFuture(Map.of("balance", balance.addAndGet(25))));
        UUID id = UUID.randomUUID();
        Map<String, Object> request = request(id, 25);
        Map<String, Object> completed = service.submit(owner, "ledger.credit", request);

        assertCode("QA_RECEIPT_NOT_FOUND", service.poll(outsider, id));
        assertCode("QA_RECEIPT_NOT_FOUND", service.submit(outsider, "ledger.credit", request));
        assertCode("QA_RECEIPT_NOT_FOUND", service.poll(console, id));
        assertEquals(completed, service.poll(owner, id));
        assertEquals(125, balance.get());

        owner.addAttachment(plugin, "resync.qa", false);

        assertCode("QA_PERMISSION_DENIED", service.poll(owner, id));
        assertCode("QA_PERMISSION_DENIED", service.submit(owner, "ledger.credit", request));
        assertEquals(125, balance.get());
    }

    @Test
    void explicitConsoleDenialCannotInvokeEffectsOrReadReceipts() {
        AtomicInteger balance = new AtomicInteger(100);
        register((actor, operation, input) -> CompletableFuture.completedFuture(Map.of("balance", balance.addAndGet(25))));
        console.addAttachment(plugin, "resync.qa", false);
        UUID id = UUID.randomUUID();

        assertCode("QA_PERMISSION_DENIED", service.submit(console, "ledger.credit", request(id, 25)));
        assertCode("QA_PERMISSION_DENIED", service.poll(console, id));
        assertEquals(100, balance.get());
    }

    @Test
    void callerMutationCannotChangeInputsUsedByDeferredWork() {
        CompletableFuture<Void> proceed = new CompletableFuture<>();
        register((actor, operation, input) -> proceed.thenApply(ignored -> Map.of("echo", input.get("payload"))));
        UUID id = UUID.randomUUID();
        List<Object> labels = new ArrayList<>(Arrays.asList("initial", null));
        Map<String, Object> selection = new LinkedHashMap<>(Map.of("id", "original"));
        Map<String, Object> payload = new LinkedHashMap<>(Map.of("labels", labels, "selection", selection));
        Map<String, Object> input = new LinkedHashMap<>(Map.of("requestId", id.toString(), "payload", payload));

        assertEquals("accepted", service.submit(console, "ledger.credit", input).get("status"));
        labels.set(0, "changed");
        labels.add("added");
        selection.put("id", "changed");
        payload.put("extra", true);
        input.put("payload", Map.of("replacement", true));
        proceed.complete(null);

        Map<String, Object> echoed = map(map(service.poll(console, id).get("result")).get("echo"));
        assertEquals(Arrays.asList("initial", null), echoed.get("labels"));
        assertEquals(Map.of("id", "original"), echoed.get("selection"));
        assertFalse(echoed.containsKey("extra"));
    }

    @Test
    void callerMutationCannotRewritePublishedReceiptsOrNestedResults() {
        CompletableFuture<Map<String, Object>> completion = new CompletableFuture<>();
        register((actor, operation, input) -> completion);
        UUID id = UUID.randomUUID();
        Map<String, Object> request = request(id, 25);
        Map<String, Object> accepted = service.submit(console, "ledger.credit", request);

        attemptMutation(() -> accepted.put("status", "forged"));
        attemptMutation(() -> accepted.put("requestId", UUID.randomUUID().toString()));
        assertEquals("accepted", service.poll(console, id).get("status"));
        assertEquals(id.toString(), service.submit(console, "ledger.credit", request).get("requestId"));

        List<Object> values = new ArrayList<>(Arrays.asList("original", null));
        Map<String, Object> nested = new LinkedHashMap<>(Map.of("values", values));
        Map<String, Object> result = new LinkedHashMap<>(Map.of("nested", nested));
        completion.complete(result);
        values.set(0, "changed by handler caller");
        nested.put("extra", true);
        result.put("extra", true);
        Map<String, Object> published = service.poll(console, id);
        Map<String, Object> publishedResult = map(published.get("result"));
        Map<String, Object> publishedNested = map(publishedResult.get("nested"));

        attemptMutation(() -> published.put("status", "forged"));
        attemptMutation(() -> publishedResult.put("extra", "forged"));
        attemptMutation(() -> publishedNested.put("extra", "forged"));
        attemptMutation(() -> list(publishedNested.get("values")).set(0, "forged"));

        Map<String, Object> retained = service.poll(console, id);
        Map<String, Object> retainedResult = map(retained.get("result"));
        Map<String, Object> retainedNested = map(retainedResult.get("nested"));
        assertEquals("completed", retained.get("status"));
        assertEquals(Arrays.asList("original", null), retainedNested.get("values"));
        assertFalse(retainedResult.containsKey("extra"));
        assertFalse(retainedNested.containsKey("extra"));
        assertEquals(retained, service.submit(console, "ledger.credit", request));
    }

    @Test
    void closingAdmissionLetsPendingWorkFinishAndRetainsItsReceipt() throws Exception {
        AtomicInteger balance = new AtomicInteger(100);
        CompletableFuture<Map<String, Object>> completion = new CompletableFuture<>();
        register((actor, operation, input) -> {
            balance.addAndGet(((Number) input.get("amount")).intValue());
            return completion;
        });
        UUID id = UUID.randomUUID();
        Map<String, Object> request = request(id, 25);
        Map<String, Object> accepted = service.submit(console, "ledger.credit", request);
        CompletableFuture<Void> idle = service.whenIdle().toCompletableFuture();

        service.close();

        assertCode("QA_UNAVAILABLE", service.submit(console, "ledger.credit", request(UUID.randomUUID(), 50)));
        assertEquals(accepted, service.poll(console, id));
        assertEquals(accepted, service.submit(console, "ledger.credit", request));
        assertFalse(idle.isDone());

        CompletableFuture.runAsync(() -> completion.complete(Map.of("balance", balance.get()))).get(2, TimeUnit.SECONDS);
        idle.get(2, TimeUnit.SECONDS);

        Map<String, Object> completed = service.poll(console, id);
        assertEquals("completed", completed.get("status"));
        assertEquals(125, ((Number) map(completed.get("result")).get("balance")).intValue());
        assertEquals(completed, service.submit(console, "ledger.credit", request));
        assertEquals(125, balance.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"object", "surrogate", "error_surrogate"})
    void invalidResultsSettleAndReleaseAdmission(String invalid) {
        List<CompletableFuture<Map<String, Object>>> completions = new ArrayList<>();
        register((actor, operation, input) -> {
            CompletableFuture<Map<String, Object>> completion = new CompletableFuture<>();
            completions.add(completion);
            return completion;
        });
        int capacity = ((Number) map(service.describe().get("limits")).get("activeRequests")).intValue();
        List<UUID> ids = new ArrayList<>();
        for (int index = 0; index < capacity; index++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            assertEquals("accepted", service.submit(console, "ledger.credit", request(id, 25)).get("status"));
        }
        assertCode("QA_BUSY", service.submit(console, "ledger.credit", request(UUID.randomUUID(), 25)));
        switch (invalid) {
            case "object" -> completions.getFirst().complete(Map.of("value", new Object()));
            case "surrogate" -> completions.getFirst().complete(Map.of("value", "\uD800"));
            case "error_surrogate" -> completions.getFirst().completeExceptionally(new IllegalStateException("Invalid \uD800 message"));
            default -> throw new AssertionError("Unknown invalid result");
        }

        Map<String, Object> failed = service.poll(console, ids.getFirst());
        assertEquals("failed", failed.get("status"));
        assertEquals("error_surrogate".equals(invalid) ? "QA_FAILED" : "QA_RESULT_INVALID", failed.get("code"));
        assertNotNull(service.parse(service.stringify(failed)));
        assertEquals("accepted", service.submit(console, "ledger.credit", request(UUID.randomUUID(), 25)).get("status"));

        completions.forEach(completion -> completion.complete(Map.of("value", true)));
        assertTrue(service.whenIdle().toCompletableFuture().isDone());
    }

    @Test
    void oversizedResultRetainsAFailedReceiptAndDoesNotRepeatItsEffect() {
        AtomicInteger balance = new AtomicInteger(100);
        CompletableFuture<Map<String, Object>> completion = new CompletableFuture<>();
        register((actor, operation, input) -> {
            balance.addAndGet(((Number) input.get("amount")).intValue());
            return completion;
        });
        UUID id = UUID.randomUUID();
        Map<String, Object> request = request(id, 25);
        assertEquals("accepted", service.submit(console, "ledger.credit", request).get("status"));

        String piece = "x".repeat(1_048_576);
        completion.complete(Map.of("chunks", Collections.nCopies(17, piece)));

        Map<String, Object> failed = service.poll(console, id);
        assertEquals("failed", failed.get("status"));
        assertCode("QA_RESULT_TOO_LARGE", failed);
        assertFalse(failed.containsKey("result"));
        assertNotNull(service.parse(service.stringify(failed)));
        assertEquals(failed, service.submit(console, "ledger.credit", request));
        assertEquals(125, balance.get());
    }

    private void register(QaService.Handler handler) {
        service.register(Map.of("operations", List.of(descriptor("ledger.credit"), descriptor("ledger.debit"))), handler);
    }

    private Map<String, Object> descriptor(String id) {
        return Map.of("id", id, "description", "Apply the requested amount to the test balance", "input", Map.of("amount", "integer"));
    }

    private Map<String, Object> request(UUID id, int amount) {
        return Map.of("requestId", id.toString(), "amount", amount);
    }

    private PlayerMock operator() {
        PlayerMock player = MockBukkit.getMock().addPlayer();
        player.addAttachment(plugin, "resync.qa", true);
        return player;
    }

    private void assertCode(String code, Map<String, Object> result) {
        assertEquals(code, result.get("code"));
    }

    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) assertInstanceOf(Map.class, value);
    }

    private List<Object> list(Object value) {
        return (List<Object>) assertInstanceOf(List.class, value);
    }

    private void attemptMutation(Runnable mutation) {
        try {
            mutation.run();
        } catch (UnsupportedOperationException ignored) {
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

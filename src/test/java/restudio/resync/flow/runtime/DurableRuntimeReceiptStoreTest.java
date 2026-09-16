package restudio.resync.flow.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.migration.PersistenceOwnershipContext;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurableRuntimeReceiptStoreTest {
    private static final RuntimeAuthority AUTHORITY = new RuntimeAuthority("resync:test");
    private static final RuntimePrincipalAuthority PRINCIPALS = new RuntimePrincipalAuthority(AUTHORITY);
    private static final RuntimePrincipal PRINCIPAL = PRINCIPALS.issueSystem("test");
    private static final ContractRef<ProviderId> PROVIDER = ContractRef.of(OwnerId.of("resync"), ProviderId.of("flow"));
    private static final RuntimeBindingKey BINDING = new RuntimeBindingKey(
        ContractRef.of(OwnerId.of("resync"), CapabilityId.of("runtime")),
        ContractRef.of(OwnerId.of("resync"), OperationId.of("invoke")));
    private static final ContentHash PLAN = hash("plan");
    private static final ContentHash EXECUTION = hash("execution");
    private static final ContentHash INPUT = hash("input");

    @Test
    void completedOutcomeAndPendingAuditSurviveRestart(@TempDir Path temporary) throws Exception {
        DurableRuntimeReceiptStore first = new DurableRuntimeReceiptStore(temporary);
        RuntimeReceiptStore.Key key = key("operation-1");
        RuntimeExecutionProvenance provenance = provenance("operation-1");
        RuntimeLeaseInput.AuditEvent event = event("operation-1");

        first.reserve(key, INPUT, provenance, event);
        String reservedDocument = Files.readString(first.root(), StandardCharsets.UTF_8);
        assertTrue(reservedDocument.contains("\"state\":\"reserved\""));
        assertFalse(reservedDocument.contains("\"outcome\":"));
        assertTrue(reservedDocument.contains("\"auditState\":\"pending\""));
        first.complete(key, RuntimeResult.success(), provenance, event);

        DurableRuntimeReceiptStore restarted = new DurableRuntimeReceiptStore(temporary);
        RuntimeReceiptStore.Claim replay = restarted.claim(key, INPUT);

        assertFalse(replay.owner());
        assertTrue(replay.inputMatches());
        assertTrue(replay.outcome().join().successful());
        assertEquals(1, restarted.pendingAuditCount());

        AtomicInteger records = new AtomicInteger();
        int recorded = restarted.retryPendingAudits(recordingBoundary(records));

        assertEquals(1, recorded);
        assertEquals(1, records.get());
        assertEquals(0, restarted.pendingAuditCount());
        assertEquals(0, new DurableRuntimeReceiptStore(temporary).pendingAuditCount());
    }

    @Test
    void reservedInvocationRecoversAsAStoredFailure(@TempDir Path temporary) throws Exception {
        DurableRuntimeReceiptStore first = new DurableRuntimeReceiptStore(temporary);
        RuntimeReceiptStore.Key key = key("operation-2");
        first.reserve(key, INPUT, provenance("operation-2"), event("operation-2"));

        DurableRuntimeReceiptStore restarted = new DurableRuntimeReceiptStore(temporary);
        RuntimeReceiptStore.Claim replay = restarted.claim(key, INPUT);

        assertFalse(replay.owner());
        assertEquals(RuntimeResult.Status.FAILURE, replay.outcome().join().status());
        assertEquals("RUNTIME.PROVIDER_DRAINING", replay.outcome().join().failure().diagnostic().code());
        assertEquals(1, restarted.pendingAuditCount());
        String recovered = replay.outcome().join().canonicalJson();
        assertEquals(recovered, new DurableRuntimeReceiptStore(temporary).claim(key, INPUT).outcome().join().canonicalJson());
    }

    @Test
    void quiescencePreventsNewReservations(@TempDir Path temporary) throws Exception {
        DurableRuntimeReceiptStore store = new DurableRuntimeReceiptStore(temporary);
        store.quiesce();

        assertTrue(store.quiesced());
        assertFalse(store.available());
        assertThrows(IllegalStateException.class, () -> store.claim(key("operation-3"), INPUT));

        store.resume();

        assertTrue(store.available());
    }

    @Test
    void ownershipIndexClaimsTheReceiptAndItsQuarantineEvidence(@TempDir Path temporary) {
        DurableRuntimeReceiptStore store = new DurableRuntimeReceiptStore(temporary);
        var index = store.ownershipIndex(new PersistenceOwnershipContext(temporary, store.root()));

        assertTrue(index.owns("runtime/runtime-receipts.json"));
        assertTrue(index.owns("runtime/runtime-receipts.json.lock"));
        assertTrue(index.owns("runtime/runtime-receipts.json-00000000-0000-0000-0000-000000000000.tmp"));
        assertTrue(index.owns("runtime/.quarantine"));
        assertTrue(index.owns("runtime/.quarantine/runtime-receipt-temps"));
        assertTrue(index.owns("runtime/.quarantine/runtime-receipt-temps/runtime-receipts.json-00000000-0000-0000-0000-000000000001.tmp"));
        assertFalse(index.owns("runtime/.quarantine/other"));
        assertFalse(index.owns("runtime/.quarantine/runtime-receipt-temps/nested"));
        assertTrue(store.owns(store.root().getParent().resolve(DurableRuntimeReceiptStore.QUARANTINE_DIRECTORY)
            .resolve("runtime-receipts.json-00000000-0000-0000-0000-000000000001.tmp")));
        assertTrue(store.owns(store.root().getParent().resolve(".quarantine")));
        assertTrue(store.owns(store.root().getParent().resolve(DurableRuntimeReceiptStore.FILE_NAME + ".lock")));
        assertFalse(index.owns("runtime/runtime-receipts.json.tmp"));
        assertFalse(index.owns("runtime/runtime-receipts.json/nested"));
    }

    @Test
    void recoversAtomicTempsOnStartupHealthAndRebind(@TempDir Path temporary) throws Exception {
        Path seed = temporary.resolve("seed");
        DurableRuntimeReceiptStore seedStore = new DurableRuntimeReceiptStore(seed);
        byte[] validDocument = Files.readAllBytes(seedStore.root());
        Path sourceRuntime = Files.createDirectories(temporary.resolve(DurableRuntimeReceiptStore.DIRECTORY));
        String startupName = DurableRuntimeReceiptStore.FILE_NAME + "-00000000-0000-0000-0000-000000000000.tmp";
        Files.write(sourceRuntime.resolve(startupName), validDocument);

        DurableRuntimeReceiptStore store = new DurableRuntimeReceiptStore(temporary);
        assertFalse(Files.exists(sourceRuntime.resolve(startupName)));
        assertEquals(new String(validDocument, StandardCharsets.UTF_8),
            Files.readString(store.root(), StandardCharsets.UTF_8));

        String healthName = DurableRuntimeReceiptStore.FILE_NAME + "-00000000-0000-0000-0000-000000000001.tmp";
        Files.write(store.root().getParent().resolve(healthName), validDocument);
        store.healthCheck();
        assertEquals(new String(validDocument, StandardCharsets.UTF_8), Files.readString(store.root().getParent()
            .resolve(DurableRuntimeReceiptStore.QUARANTINE_DIRECTORY).resolve(healthName), StandardCharsets.UTF_8));

        Path target = Files.createDirectories(temporary.resolve("rebind-target"));
        Path targetRuntime = Files.createDirectories(target.resolve(DurableRuntimeReceiptStore.DIRECTORY));
        Files.writeString(targetRuntime.resolve(DurableRuntimeReceiptStore.FILE_NAME),
            Files.readString(store.root(), StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        String rebindName = DurableRuntimeReceiptStore.FILE_NAME + "-00000000-0000-0000-0000-000000000002.tmp";
        Files.write(targetRuntime.resolve(rebindName), validDocument);

        store.quiesce();
        store.rebind(target);
        assertEquals(new String(validDocument, StandardCharsets.UTF_8), Files.readString(targetRuntime
            .resolve(DurableRuntimeReceiptStore.QUARANTINE_DIRECTORY).resolve(rebindName), StandardCharsets.UTF_8));
        store.resume();
    }

    @Test
    void rejectsConflictingValidCrashCandidatesWithoutBootstrapping(@TempDir Path temporary) throws Exception {
        DurableRuntimeReceiptStore first = new DurableRuntimeReceiptStore(temporary.resolve("first"));
        first.reserve(key("candidate-one"), INPUT, provenance("candidate-one"), null);
        DurableRuntimeReceiptStore second = new DurableRuntimeReceiptStore(temporary.resolve("second"));
        second.reserve(key("candidate-two"), INPUT, provenance("candidate-two"), null);

        Path runtime = Files.createDirectories(temporary.resolve(DurableRuntimeReceiptStore.DIRECTORY));
        String firstName = DurableRuntimeReceiptStore.FILE_NAME
            + "-00000000-0000-0000-0000-000000000010.tmp";
        String secondName = DurableRuntimeReceiptStore.FILE_NAME
            + "-00000000-0000-0000-0000-000000000011.tmp";
        Files.copy(first.root(), runtime.resolve(firstName));
        Files.copy(second.root(), runtime.resolve(secondName));

        assertThrows(IllegalStateException.class, () -> new DurableRuntimeReceiptStore(temporary));
        assertFalse(Files.exists(runtime.resolve(DurableRuntimeReceiptStore.FILE_NAME)));
        assertTrue(Files.exists(runtime.resolve(firstName)));
        assertTrue(Files.exists(runtime.resolve(secondName)));
    }

    @Test
    void promotesNewerCandidateAndQuarantinesStaleEvidence(@TempDir Path temporary) throws Exception {
        Path targetRoot = temporary.resolve("target");
        DurableRuntimeReceiptStore target = new DurableRuntimeReceiptStore(targetRoot);
        RuntimeReceiptStore.Key key = key("newer-candidate");
        RuntimeExecutionProvenance provenance = provenance("newer-candidate");
        RuntimeLeaseInput.AuditEvent event = event("newer-candidate");
        target.reserve(key, INPUT, provenance, event);
        byte[] staleDocument = Files.readAllBytes(target.root());

        DurableRuntimeReceiptStore newer = new DurableRuntimeReceiptStore(temporary.resolve("newer"));
        newer.reserve(key, INPUT, provenance, event);
        newer.complete(key, RuntimeResult.success(), provenance, event);
        byte[] newerDocument = Files.readAllBytes(newer.root());

        Path runtime = target.root().getParent();
        String newerName = DurableRuntimeReceiptStore.FILE_NAME
            + "-00000000-0000-0000-0000-000000000012.tmp";
        String staleName = DurableRuntimeReceiptStore.FILE_NAME
            + "-00000000-0000-0000-0000-000000000013.tmp";
        Files.write(runtime.resolve(newerName), newerDocument);
        Files.write(runtime.resolve(staleName), staleDocument);

        DurableRuntimeReceiptStore recovered = new DurableRuntimeReceiptStore(targetRoot);
        assertTrue(recovered.claim(key, INPUT).outcome().join().successful());
        assertFalse(Files.exists(runtime.resolve(newerName)));
        assertEquals(new String(staleDocument, StandardCharsets.UTF_8), Files.readString(runtime
            .resolve(DurableRuntimeReceiptStore.QUARANTINE_DIRECTORY).resolve(staleName), StandardCharsets.UTF_8));
    }

    @Test
    void healthCheckReloadsAValidExternallyNewerDocument(@TempDir Path temporary) throws Exception {
        DurableRuntimeReceiptStore first = new DurableRuntimeReceiptStore(temporary);
        DurableRuntimeReceiptStore second = new DurableRuntimeReceiptStore(temporary);
        RuntimeReceiptStore.Key key = key("external-health");
        second.reserve(key, INPUT, provenance("external-health"), null);

        first.healthCheck();

        RuntimeReceiptStore.Claim replay = first.claim(key, INPUT);
        assertFalse(replay.owner());
        assertEquals(RuntimeResult.Status.FAILURE, replay.outcome().join().status());
    }

    @Test
    void staleInstanceMergesItsMutationWithAnExternallyNewerDocument(@TempDir Path temporary) throws Exception {
        DurableRuntimeReceiptStore first = new DurableRuntimeReceiptStore(temporary);
        DurableRuntimeReceiptStore second = new DurableRuntimeReceiptStore(temporary);
        second.reserve(key("external-first"), INPUT, provenance("external-first"), null);
        first.reserve(key("local-second"), INPUT, provenance("local-second"), null);

        DurableRuntimeReceiptStore restarted = new DurableRuntimeReceiptStore(temporary);
        assertFalse(restarted.claim(key("external-first"), INPUT).owner());
        assertFalse(restarted.claim(key("local-second"), INPUT).owner());
    }

    @Test
    void rejectsConflictingSameKeyExternalMutation(@TempDir Path temporary) throws Exception {
        DurableRuntimeReceiptStore first = new DurableRuntimeReceiptStore(temporary);
        DurableRuntimeReceiptStore second = new DurableRuntimeReceiptStore(temporary);
        RuntimeReceiptStore.Key key = key("same-key-conflict");
        RuntimeExecutionProvenance provenance = provenance("same-key-conflict");

        second.claim(key, INPUT);
        first.reserve(key, INPUT, provenance, null);
        first.complete(key, RuntimeResult.success(), provenance, null);

        assertThrows(IllegalStateException.class, () -> second.reserve(key, INPUT, provenance, null));
        assertTrue(new DurableRuntimeReceiptStore(temporary).claim(key, INPUT).outcome().join().successful());
    }

    @Test
    void rejectsMalformedAtomicTempsNonRegularEntriesAndQuarantineCollisions(@TempDir Path temporary) throws Exception {
        Path malformed = Files.createDirectories(temporary.resolve("malformed"));
        Path malformedRuntime = Files.createDirectories(malformed.resolve(DurableRuntimeReceiptStore.DIRECTORY));
        Files.writeString(malformedRuntime.resolve(DurableRuntimeReceiptStore.FILE_NAME + ".tmp"), "bad",
            StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> new DurableRuntimeReceiptStore(malformed));

        Path directory = Files.createDirectories(temporary.resolve("directory"));
        Path directoryRuntime = Files.createDirectories(directory.resolve(DurableRuntimeReceiptStore.DIRECTORY));
        Files.createDirectory(directoryRuntime.resolve(DurableRuntimeReceiptStore.FILE_NAME
            + "-00000000-0000-0000-0000-000000000003.tmp"));
        assertThrows(IllegalStateException.class, () -> new DurableRuntimeReceiptStore(directory));

        Path collision = Files.createDirectories(temporary.resolve("collision"));
        Path collisionRuntime = Files.createDirectories(collision.resolve(DurableRuntimeReceiptStore.DIRECTORY));
        Path collisionQuarantine = Files.createDirectories(collisionRuntime.resolve(
            DurableRuntimeReceiptStore.QUARANTINE_DIRECTORY));
        String collisionName = DurableRuntimeReceiptStore.FILE_NAME
            + "-00000000-0000-0000-0000-000000000004.tmp";
        Files.writeString(collisionRuntime.resolve(collisionName), "new-evidence", StandardCharsets.UTF_8);
        Files.writeString(collisionQuarantine.resolve(collisionName), "old-evidence", StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> new DurableRuntimeReceiptStore(collision));
        assertEquals("new-evidence", Files.readString(collisionRuntime.resolve(collisionName), StandardCharsets.UTF_8));
        assertEquals("old-evidence", Files.readString(collisionQuarantine.resolve(collisionName), StandardCharsets.UTF_8));

        Path unknown = Files.createDirectories(temporary.resolve("unknown-quarantine"));
        Path unknownContainer = Files.createDirectories(unknown.resolve(DurableRuntimeReceiptStore.DIRECTORY)
            .resolve(".quarantine"));
        Files.createDirectories(unknownContainer.resolve(DurableRuntimeReceiptStore.QUARANTINE_DIRECTORY
            .substring(".quarantine/".length())));
        Files.createDirectory(unknownContainer.resolve(DurableRuntimeReceiptStore.QUARANTINE_DIRECTORY
            .substring(".quarantine/".length())).resolve("unexpected"));
        assertThrows(IllegalStateException.class, () -> new DurableRuntimeReceiptStore(unknown));
    }

    @Test
    void rejectsOversizedReceiptBeforeParsing(@TempDir Path temporary) throws Exception {
        Path runtime = Files.createDirectories(temporary.resolve(DurableRuntimeReceiptStore.DIRECTORY));
        Path receipt = runtime.resolve(DurableRuntimeReceiptStore.FILE_NAME);
        try (FileChannel channel = FileChannel.open(receipt, StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE)) {
            channel.position(33_554_432L);
            channel.write(ByteBuffer.wrap(new byte[] { '{' }));
        }

        assertThrows(IllegalStateException.class, () -> new DurableRuntimeReceiptStore(temporary));
    }

    @Test
    void quiescenceWaitsForActiveInvocationLeases(@TempDir Path temporary) throws Exception {
        DurableRuntimeReceiptStore store = new DurableRuntimeReceiptStore(temporary);
        RuntimeReceiptStore.InvocationLease invocation = store.acquireInvocationLease();
        CompletableFuture<Void> quiesce = CompletableFuture.runAsync(() -> {
            try {
                store.quiesce();
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });

        Thread.sleep(50);
        assertFalse(quiesce.isDone());

        invocation.close();
        quiesce.join();
        assertTrue(store.quiesced());
    }

    @Test
    void missingReceiptFileRejectsRebindAndRetainsPreviousAuthority(@TempDir Path temporary) throws Exception {
        DurableRuntimeReceiptStore store = new DurableRuntimeReceiptStore(temporary);
        Path original = store.root();
        Path candidate = temporary.resolve("candidate");
        Files.createDirectories(candidate.resolve(DurableRuntimeReceiptStore.DIRECTORY));
        store.quiesce();

        assertThrows(IOException.class, () -> store.rebind(candidate));
        assertEquals(original, store.root());
        assertEquals(temporary.resolve(DurableRuntimeReceiptStore.DIRECTORY).resolve(DurableRuntimeReceiptStore.FILE_NAME)
            .toAbsolutePath().normalize(), store.root());
    }

    @Test
    void successfulRebindKeepsTheReceiptLeafWritable(@TempDir Path temporary) throws Exception {
        DurableRuntimeReceiptStore store = new DurableRuntimeReceiptStore(temporary);
        Path candidate = temporary.resolve("candidate");
        Files.createDirectories(candidate.resolve(DurableRuntimeReceiptStore.DIRECTORY));
        Files.writeString(candidate.resolve(DurableRuntimeReceiptStore.DIRECTORY).resolve(DurableRuntimeReceiptStore.FILE_NAME),
            Files.readString(store.root(), StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        store.quiesce();
        store.rebind(candidate);
        store.resume();
        RuntimeReceiptStore.Key key = key("operation-rebound");
        RuntimeExecutionProvenance provenance = provenance("operation-rebound");
        store.reserve(key, INPUT, provenance, event("operation-rebound"));
        store.complete(key, RuntimeResult.success(), provenance, event("operation-rebound"));

        DurableRuntimeReceiptStore restarted = new DurableRuntimeReceiptStore(candidate);
        assertTrue(restarted.claim(key, INPUT).outcome().join().successful());
        assertEquals(candidate.resolve(DurableRuntimeReceiptStore.DIRECTORY)
            .resolve(DurableRuntimeReceiptStore.FILE_NAME).toAbsolutePath().normalize(), restarted.root());
    }

    @Test
    void healthFailureFencesFurtherReceiptAdmission(@TempDir Path temporary) throws Exception {
        DurableRuntimeReceiptStore store = new DurableRuntimeReceiptStore(temporary);
        Files.delete(store.root());

        assertThrows(IOException.class, store::healthCheck);
        assertFalse(store.available());
        assertThrows(IllegalStateException.class, () -> store.claim(key("faulted"), INPUT));
    }

    @Test
    void pendingAuditRestoresItsProvenance(@TempDir Path temporary) throws Exception {
        DurableRuntimeReceiptStore first = new DurableRuntimeReceiptStore(temporary);
        RuntimeReceiptStore.Key key = key("operation-provenance");
        RuntimeExecutionProvenance provenance = provenance("operation-provenance");
        RuntimeLeaseInput.AuditEvent event = event("operation-provenance");
        first.reserve(key, INPUT, provenance, event);
        first.complete(key, RuntimeResult.success(), provenance, event);

        DurableRuntimeReceiptStore restarted = new DurableRuntimeReceiptStore(temporary);
        AtomicReference<RuntimeLeaseInput.AuditEvent> restored = new AtomicReference<>();
        restarted.retryPendingAudits(new RuntimeAuditBoundary() {
            @Override
            public boolean available(RuntimeSemantics.Audit audit) {
                return true;
            }

            @Override
            public void record(RuntimeLeaseInput.AuditEvent value) {
                restored.set(value);
            }
        });

        assertEquals(provenance.canonicalValue(), restored.get().provenance().canonicalValue());
    }

    @Test
    void replayWithAnotherPrincipalIsDenied(@TempDir Path temporary) throws Exception {
        DurableRuntimeReceiptStore store = new DurableRuntimeReceiptStore(temporary);
        RuntimeReceiptStore.Key issued = key("operation-actor", PRINCIPAL);
        store.reserve(issued, INPUT, provenance("operation-actor"), event("operation-actor"));
        store.complete(issued, RuntimeResult.success(), provenance("operation-actor"), event("operation-actor"));

        RuntimePrincipal otherPrincipal = PRINCIPALS.issuePlayer("player");
        RuntimeReceiptStore.Claim other = store.claim(key("operation-actor", otherPrincipal), INPUT);

        assertFalse(other.owner());
        assertTrue(other.inputMatches());
        assertFalse(other.principalMatches());
    }

    @Test
    void scheduledFunctionReplayUsesStableFireStateAcrossRestart(@TempDir Path temporary) throws Exception {
        RuntimeAuthority authority = new RuntimeAuthority("resync:schedule-replay");
        RuntimePrincipalAuthority principals = new RuntimePrincipalAuthority(authority);
        RuntimePrincipal principal = principals.issueAuthenticatedClient("schedule-client");
        ContractRef<ProviderId> provider = ContractRef.of(OwnerId.of("resync"), ProviderId.of("compiled-function"));
        RuntimeBindingKey binding = new RuntimeBindingKey(
            ContractRef.of(OwnerId.of("resync"), CapabilityId.of("function-execution")),
            ContractRef.of(OwnerId.of("resync"), OperationId.of("test-run")));
        ContentHash plan = hash("schedule-plan");
        ContentHash execution = hash("schedule-execution");
        String idempotencyKey = "schedule-function|schedule-test|server|server|4";
        RuntimeReceiptStore.Key key = new RuntimeReceiptStore.Key(provider, plan, binding, execution,
            authority.identity(), idempotencyKey, principal.canonical(), RuntimeReceiptStore.IdempotencyKind.MUTATION_ID);
        Map<String, Object> persistedFire = Map.of(
            "definitionId", "schedule-test",
            "scope", "server",
            "ownerId", "server",
            "runCount", 4L,
            "scheduledAt", 2_000L);
        Map<String, Object> retryFire = Map.of(
            "definitionId", "schedule-test",
            "scope", "server",
            "ownerId", "server",
            "runCount", 4L,
            "scheduledAt", 2_000L);
        Map<String, Object> persistedContext = Map.of(
            "schedule.task", persistedFire,
            "schedule.fired_at", 2_000L,
            "schedule.arguments", Map.of("value", "hello"));
        Map<String, Object> retryContext = Map.of(
            "schedule.task", retryFire,
            "schedule.fired_at", 2_000L,
            "schedule.arguments", Map.of("value", "hello"));
        ContentHash inputHash = ContentHash.of(CanonicalJson.sha256("schedule-function-input", Map.of(
            "context", persistedContext,
            "deadlineMillis", RuntimeExecutionContext.NO_DEADLINE,
            "principal", principal.canonical())));
        ContentHash retryHash = ContentHash.of(CanonicalJson.sha256("schedule-function-input", Map.of(
            "context", retryContext,
            "deadlineMillis", RuntimeExecutionContext.NO_DEADLINE,
            "principal", principal.canonical())));
        assertEquals(inputHash, retryHash);

        CorrelationId invocationId = CorrelationId.deterministic(idempotencyKey);
        RuntimeExecutionProvenance provenance = new RuntimeExecutionProvenance(
            authority.identity(), principal, binding, provider, "1.0.0", 1, hash("schedule-manifest"),
            1, hash("schedule-catalog"), plan, execution, idempotencyKey, invocationId, idempotencyKey,
            inputHash, hash("schedule-context"), RuntimeExecutionContext.NO_DEADLINE,
            UUID.nameUUIDFromBytes(("schedule-lease|" + idempotencyKey).getBytes(StandardCharsets.UTF_8)),
            "schedule-session");
        RuntimeLeaseInput.AuditEvent audit = RuntimeAuditEvent.create(
            provenance.leaseId(), authority, binding, idempotencyKey, RuntimeResult.Status.SUCCESS, true,
            "outcome", provenance).leaseEvent();
        DurableRuntimeReceiptStore first = new DurableRuntimeReceiptStore(temporary);
        AtomicInteger sideEffects = new AtomicInteger();
        first.reserve(key, inputHash, provenance, audit);
        sideEffects.incrementAndGet();
        first.complete(key, RuntimeResult.success(), provenance, audit);
        first.quiesce();

        DurableRuntimeReceiptStore restarted = new DurableRuntimeReceiptStore(temporary);
        RuntimeReceiptStore.Claim replay = restarted.claim(key, retryHash);

        assertFalse(replay.owner());
        assertTrue(replay.inputMatches());
        assertTrue(replay.outcome().join().successful());
        assertEquals(1, sideEffects.get());
    }

    private static RuntimeReceiptStore.Key key(String idempotencyKey) {
        return new RuntimeReceiptStore.Key(PROVIDER, PLAN, BINDING, EXECUTION, AUTHORITY.identity(), idempotencyKey);
    }

    private static RuntimeReceiptStore.Key key(String idempotencyKey, RuntimePrincipal principal) {
        return new RuntimeReceiptStore.Key(PROVIDER, PLAN, BINDING, EXECUTION, AUTHORITY.identity(), idempotencyKey,
            principal.canonical());
    }

    private static RuntimeExecutionProvenance provenance(String idempotencyKey) {
        return new RuntimeExecutionProvenance(
            AUTHORITY.identity(), PRINCIPAL, BINDING, PROVIDER, "1.0.0", 3, hash("manifest"),
            7, hash("catalog"), PLAN, EXECUTION, idempotencyKey,
            CorrelationId.deterministic("test-runtime-invocation:" + idempotencyKey), null, INPUT,
            hash("runtime-context"), RuntimeExecutionContext.NO_DEADLINE,
            UUID.nameUUIDFromBytes(("lease:" + idempotencyKey).getBytes(StandardCharsets.UTF_8)));
    }

    private static RuntimeLeaseInput.AuditEvent event(String idempotencyKey) {
        return new RuntimeLeaseInput.AuditEvent(
            UUID.randomUUID(), AUTHORITY.identity(), BINDING.canonical(), idempotencyKey,
            RuntimeResult.Status.SUCCESS, false, "outcome", provenance(idempotencyKey));
    }

    private static RuntimeAuditBoundary recordingBoundary(AtomicInteger records) {
        return new RuntimeAuditBoundary() {
            @Override
            public boolean available(RuntimeSemantics.Audit audit) {
                return true;
            }

            @Override
            public void record(RuntimeLeaseInput.AuditEvent event) {
                records.incrementAndGet();
            }
        };
    }

    private static ContentHash hash(String value) {
        return ContentHash.of(CanonicalJson.sha256("durable-runtime-test", Map.of("value", value)));
    }
}

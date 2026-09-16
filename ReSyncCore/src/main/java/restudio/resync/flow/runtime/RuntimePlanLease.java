package restudio.resync.flow.runtime;

import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticContext;
import restudio.resync.flow.diagnostic.DiagnosticRedaction;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.CorrelationId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ProviderId;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypedValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class RuntimePlanLease implements AutoCloseable {
    private static final ScheduledExecutorService TIMER = Executors.newScheduledThreadPool(1, daemonThreadFactory("resync-runtime-timer"));
    private static final Set<String> RUNTIME_CODES = Set.of(
        "RUNTIME.AUTHORIZATION_DENIED",
        "RUNTIME.AUDIT_FAILURE",
        "RUNTIME.CANCELLED",
        "RUNTIME.EXECUTION_CANCELLED",
        "RUNTIME.EXECUTION_TIMEOUT",
        "RUNTIME.FAILURE_PAYLOAD_TYPE",
        "RUNTIME.HANDLER_FAILURE",
        "RUNTIME.INVALID_INVOCATION",
        "RUNTIME.NULL_RESULT",
        "RUNTIME.PROVIDER_DRAINING",
        "RUNTIME.PROVIDER_REVOKED",
        "RUNTIME.RESOURCE_ACCESS_UNDECLARED",
        "RUNTIME.RESULT_BRANCH_INVALID",
        "RUNTIME.RESULT_BRANCH_MISMATCH",
        "RUNTIME.RESULT_NON_FINITE",
        "RUNTIME.RESULT_OUTPUT_COUNT",
        "RUNTIME.RESULT_TYPE_MISMATCH",
        "RUNTIME.UNLOAD_BLOCKED");

    private final UUID leaseId = UUID.randomUUID();
    private final Map<RuntimeBindingKey, RuntimeBinding> bindings;
    private final Map<RuntimeBindingKey, ContentHash> fingerprints;
    private final Map<RuntimeBindingKey, RuntimeLeaseInput.BindingRequirement> requirements;
    private final Map<ContractRef<ProviderId>, RuntimeCancellationToken> providerTokens;
    private final Map<RuntimeBindingKey, RuntimeCancellationToken> bindingTokens;
    private final RuntimeLeaseInput input;
    private final RuntimeSecurityBoundary securityBoundary;
    private final RuntimeAuditBoundary auditBoundary;
    private final RuntimeExecutionBoundary executionBoundary;
    private final RuntimeReceiptStore receiptStore;
    private final RuntimeRegistrySnapshot runtimeSnapshot;
    private final Runnable release;
    private final RuntimeExecutionBarrier executionBarrier = new RuntimeExecutionBarrier();
    private final Object monitor = new Object();
    private final Set<ContractRef<ProviderId>> fencedProviders = ConcurrentHashMap.newKeySet();
    private final Set<RuntimeBindingKey> fencedBindings = ConcurrentHashMap.newKeySet();
    private final List<RuntimeLeaseInput.AuditEvent> auditEvents = new ArrayList<>();
    private final List<RuntimeExecutionBarrier.Admission> executionAdmissions = new ArrayList<>();
    private final List<RuntimeReceiptStore.InvocationLease> receiptAdmissions = new ArrayList<>();
    private final Map<RuntimeBindingKey, Long> stableSemanticDeadlines = new ConcurrentHashMap<>();
    private volatile boolean closing;
    private volatile boolean released;
    private int inFlight;

    RuntimePlanLease(
        Map<RuntimeBindingKey, RuntimeBinding> bindings,
        Map<RuntimeBindingKey, ContentHash> fingerprints,
        Map<RuntimeBindingKey, RuntimeLeaseInput.BindingRequirement> requirements,
        Map<ContractRef<ProviderId>, RuntimeCancellationToken> providerTokens,
        Map<RuntimeBindingKey, RuntimeCancellationToken> bindingTokens,
        RuntimeLeaseInput input,
        RuntimeSecurityBoundary securityBoundary,
        RuntimeAuditBoundary auditBoundary,
        RuntimeExecutionBoundary executionBoundary,
        RuntimeReceiptStore receiptStore,
        RuntimeRegistrySnapshot runtimeSnapshot,
        Runnable release
    ) {
        this.bindings = immutableBindings(bindings);
        this.fingerprints = immutableFingerprints(fingerprints, this.bindings);
        this.requirements = immutableRequirements(requirements, this.bindings, this.fingerprints);
        this.providerTokens = Map.copyOf(Objects.requireNonNull(providerTokens, "Provider Tokens Are Required"));
        this.bindingTokens = Map.copyOf(Objects.requireNonNull(bindingTokens, "Binding Tokens Are Required"));
        this.input = Objects.requireNonNull(input, "Lease Input Is Required");
        this.securityBoundary = Objects.requireNonNull(securityBoundary, "Runtime Security Boundary Is Required");
        this.auditBoundary = Objects.requireNonNull(auditBoundary, "Runtime Audit Boundary Is Required");
        this.executionBoundary = Objects.requireNonNull(executionBoundary, "Runtime Execution Boundary Is Required");
        this.receiptStore = Objects.requireNonNull(receiptStore, "Runtime Receipt Store Is Required");
        this.runtimeSnapshot = Objects.requireNonNull(runtimeSnapshot, "Runtime Registry Snapshot Is Required");
        this.release = Objects.requireNonNull(release, "Lease Release Is Required");
    }

    public UUID leaseId() {
        return leaseId;
    }

    public CorrelationId invocationId() {
        return input.invocationId();
    }

    public Set<RuntimeBindingKey> bindings() {
        return bindings.keySet();
    }

    public Map<RuntimeBindingKey, ContentHash> bindingFingerprints() {
        return fingerprints;
    }

    public ContentHash bindingFingerprint(RuntimeBindingKey key) {
        ContentHash fingerprint = fingerprints.get(Objects.requireNonNull(key, "Binding Key Is Required"));
        if (fingerprint == null) {
            throw new IllegalArgumentException("Binding Is Not In Runtime Lease: " + key.canonical());
        }
        return fingerprint;
    }

    public ContentHash planFingerprint() {
        return input.planFingerprint();
    }

    public List<RuntimeLeaseInput.AuditEvent> auditEvents() {
        synchronized (monitor) {
            return List.copyOf(auditEvents);
        }
    }

    RuntimeCancellationToken cancellationToken(ContractRef<ProviderId> provider) {
        RuntimeCancellationToken token = providerTokens.get(Objects.requireNonNull(provider, "Provider Is Required"));
        if (token == null) {
            throw new IllegalArgumentException("Provider Is Not Leased By This Lease: " + provider.canonicalText());
        }
        return token;
    }

    RuntimeCancellationToken cancellationToken(RuntimeBindingKey key) {
        RuntimeCancellationToken token = bindingTokens.get(Objects.requireNonNull(key, "Binding Key Is Required"));
        if (token == null) {
            throw new IllegalArgumentException("Binding Is Not In Runtime Lease: " + key.canonical());
        }
        return token;
    }

    public boolean isFenced(RuntimeBindingKey key) {
        Objects.requireNonNull(key, "Binding Key Is Required");
        RuntimeBinding binding = bindings.get(key);
        return binding != null && (fencedBindings.contains(key) || fencedProviders.contains(binding.provider()));
    }

    public CompletionStage<RuntimeResult> execute(RuntimeBindingKey key, Map<PinId, TypedValue> inputs, String idempotencyKey) {
        return executeInternal(key, inputs, idempotencyKey, null, null, null);
    }

    private CompletionStage<RuntimeResult> executeInternal(
        RuntimeBindingKey key,
        Map<PinId, TypedValue> inputs,
        String idempotencyKey,
        RuntimeCancellationToken suppliedToken,
        CompiledRuntimeContext runtimeContext,
        CorrelationId invocationId
    ) {
        Objects.requireNonNull(key, "Binding Key Is Required");
        Map<PinId, TypedValue> invocationInputs = immutableInputs(inputs);
        String normalizedKey = Objects.requireNonNull(idempotencyKey, "Idempotency Key Is Required").trim();
        if (normalizedKey.isEmpty()) {
            throw new IllegalArgumentException("Idempotency Key Is Required");
        }
        CorrelationId rootInvocationId = invocationId != null ? invocationId : input.invocationId();

        RuntimeBinding binding;
        RuntimeCancellationToken providerToken;
        RuntimeCancellationToken bindingToken;
        synchronized (monitor) {
            if (closing || released) {
                throw new IllegalStateException("Runtime Lease Is Closed");
            }
            binding = bindings.get(key);
            if (binding == null) {
                throw new IllegalArgumentException("Binding Is Not In Runtime Lease: " + key.canonical());
            }
            if (fencedBindings.contains(key) || fencedProviders.contains(binding.provider())) {
                throw new RuntimeCapabilityUnavailableException(key, "provider is unloading");
            }
            bindingToken = bindingTokens.get(key);
            if (bindingToken == null) {
                throw new IllegalStateException("Binding Lease Is Missing: " + key.canonical());
            }
            bindingToken.throwIfCancelled();
            providerToken = providerTokens.get(binding.provider());
            admitInvocationLocked();
        }

        long deadlineMillis = effectiveDeadline(binding, suppliedToken, providerToken, bindingToken);
        ContentHash invocationInputHash = inputHash(invocationInputs, runtimeContext, deadlineMillis);
        RuntimeResult policyFailure;
        try {
            policyFailure = preflight(binding, invocationInputs);
        } catch (RuntimeException | Error failure) {
            invocationFinished();
            throw failure;
        }
        if (policyFailure != null) {
            RuntimeResult audited = audited(binding, normalizedKey, policyFailure, invocationInputHash, runtimeContext,
                deadlineMillis, rootInvocationId);
            invocationFinished();
            return CompletableFuture.completedFuture(audited);
        }
        RuntimeResult preExecutionFailure = null;
        if (deadlineExpired(deadlineMillis)) {
            preExecutionFailure = timeoutResult(binding);
        } else if (binding.descriptor().semantics().cancellable() && suppliedToken != null && suppliedToken.isCancelled()) {
            preExecutionFailure = cancellationResult(binding);
        }

        CompletableFuture<RuntimeResult> outcome = null;
        RuntimeResult receiptFailure = null;
        CompletionStage<RuntimeResult> existingOutcome = null;
        RuntimeReceiptStore.Key receiptKey = receiptKey(binding, normalizedKey);
        RuntimeReceiptStore.Claim claim;
        try {
            claim = receiptKey == null
                ? null
                : receiptStore.claim(receiptKey, invocationInputHash);
        } catch (RuntimeException | Error failure) {
            invocationFinished();
            throw failure;
        }
        if (claim == null) {
            outcome = new CompletableFuture<>();
        } else {
            outcome = claim.outcome();
        }
        RuntimeExecutionProvenance invocationProvenance = provenance(binding, normalizedKey, invocationInputHash,
            runtimeContext, deadlineMillis, rootInvocationId);
        RuntimeLeaseInput.AuditEvent auditAttempt = auditEvent(
            binding, normalizedKey, RuntimeResult.Status.FAILURE, "attempt", invocationInputHash,
            runtimeContext, deadlineMillis, rootInvocationId);
        RuntimeResult reservationFailure = null;
        if (claim == null || claim.owner()) {
            try {
                if (claim != null || auditAttempt != null) {
                    receiptStore.reserve(
                        claim == null ? auditKey(binding, normalizedKey) : receiptKey,
                        invocationInputHash, invocationProvenance, auditAttempt);
                }
            } catch (RuntimeException | Error failure) {
                reservationFailure = runtimeFailureResult(binding, "RUNTIME.INVALID_INVOCATION",
                    "Runtime Durability Lease Is Unavailable");
            }
        }
        RuntimeException leaseFailure = null;
        synchronized (monitor) {
            if (closing || released) {
                if (claim == null) {
                    leaseFailure = new IllegalStateException("Runtime Lease Is Closed");
                } else if (claim.owner()) {
                    receiptFailure = runtimeFailureResult(binding, "RUNTIME.PROVIDER_DRAINING", "Runtime Lease Is Closed");
                } else {
                    existingOutcome = claim.outcome();
                }
            } else if (fencedBindings.contains(key) || fencedProviders.contains(binding.provider())) {
                if (claim == null) {
                    leaseFailure = new RuntimeCapabilityUnavailableException(key, "provider is unloading");
                } else if (claim.owner()) {
                    receiptFailure = runtimeFailureResult(binding, "RUNTIME.PROVIDER_DRAINING", "provider is unloading");
                } else {
                    existingOutcome = claim.outcome();
                }
            } else if (reservationFailure != null) {
                receiptFailure = reservationFailure;
            } else if (claim == null) {
                if (preExecutionFailure != null) {
                    receiptFailure = preExecutionFailure;
                }
            } else if (!claim.principalMatches()) {
                receiptFailure = RuntimeResult.failure(runtimeFailure(binding,
                    "RUNTIME.AUTHORIZATION_DENIED", "Idempotency Key Belongs To Another Principal"));
            } else if (!claim.inputMatches()) {
                receiptFailure = RuntimeResult.failure(runtimeFailure(binding,
                    "RUNTIME.INVALID_INVOCATION", "Idempotency Key Was Reused With Different Inputs"));
            } else if (!claim.owner()) {
                existingOutcome = claim.outcome();
            } else if (preExecutionFailure != null) {
                receiptFailure = preExecutionFailure;
            }
        }
        if (leaseFailure != null) {
            invocationFinished();
            throw leaseFailure;
        }
        if (receiptFailure != null) {
            if ((claim != null && claim.owner()) || (claim == null && auditAttempt != null)) {
                completeOutcome(binding, normalizedKey, outcome, receiptFailure, invocationInputHash,
                    runtimeContext, deadlineMillis, rootInvocationId);
                invocationFinished();
                return outcome;
            }
            invocationFinished();
            return CompletableFuture.completedFuture(receiptFailure);
        }
        if (existingOutcome != null) {
            invocationFinished();
            return existingOutcome;
        }

        runAttempts(binding, invocationInputs, normalizedKey, invocationInputHash, outcome, 0, suppliedToken, deadlineMillis,
            runtimeContext, rootInvocationId);
        return outcome;
    }

    public CompletionStage<RuntimeResult> execute(RuntimeInvocation invocation) {
        Objects.requireNonNull(invocation, "Invocation Is Required");
        return executeInternal(
            invocation.binding(),
            invocation.inputs(),
            invocation.idempotencyKey(),
            invocation.cancellationToken(),
            invocation.runtimeContext(),
            invocation.invocationId());
    }

    private RuntimeResult preflight(RuntimeBinding binding, Map<PinId, TypedValue> inputs) {
        RuntimeSemantics semantics = binding.descriptor().semantics();
        RuntimeLeaseInput.BindingRequirement requirement = requirements.get(binding.key());
        List<RuntimeOperationDescriptor.Pin> expectedPins = inputPins(binding.descriptor());
        List<PinId> expectedIds = pinIds(expectedPins);
        if (!requirement.inputPins().equals(expectedIds) || !inputs.keySet().equals(Set.copyOf(expectedIds))) {
            return RuntimeResult.failure(runtimeFailure(binding,
                "RUNTIME.INVALID_INVOCATION", "Runtime Invocation Pin Bindings Do Not Match The Operation"));
        }
        for (RuntimeOperationDescriptor.Pin pin : expectedPins) {
            TypedValue value = inputs.get(pin.id());
            if (!pin.type().equals(value.type())) {
                return RuntimeResult.failure(runtimeFailure(binding, "RUNTIME.INVALID_INVOCATION", "Runtime Invocation Input Type Does Not Match The Operation"));
            }
            if (value.locator() != null
                && !semantics.resourceReads().contains(value.locator().type())
                && !semantics.resourceWrites().contains(value.locator().type())) {
                return RuntimeResult.failure(runtimeFailure(binding,
                    "RUNTIME.RESOURCE_ACCESS_UNDECLARED", "Runtime Invocation Uses An Undeclared Resource"));
            }
        }
        return null;
    }

    private RuntimeReceiptStore.Key receiptKey(RuntimeBinding binding, String idempotencyKey) {
        return switch (binding.descriptor().semantics().idempotency()) {
            case MUTATION_ID -> new RuntimeReceiptStore.Key(
                binding.provider(),
                input.planFingerprint(),
                binding.key(),
                fingerprints.get(binding.key()),
                input.authority().identity(),
                idempotencyKey,
                principalReference(), RuntimeReceiptStore.IdempotencyKind.MUTATION_ID);
            case OPERATION_KEY -> new RuntimeReceiptStore.Key(
                binding.provider(),
                input.planFingerprint(),
                binding.key(),
                fingerprints.get(binding.key()),
                input.authority().identity(),
                idempotencyKey,
                principalReference(), RuntimeReceiptStore.IdempotencyKind.OPERATION_KEY);
            case NONE, INTRINSIC -> null;
        };
    }

    private RuntimeReceiptStore.Key auditKey(RuntimeBinding binding, String idempotencyKey) {
        return new RuntimeReceiptStore.Key(
            binding.provider(),
            input.planFingerprint(),
            binding.key(),
            fingerprints.get(binding.key()),
            input.authority().identity(),
            idempotencyKey,
            principalReference(), RuntimeReceiptStore.IdempotencyKind.OPERATION_KEY);
    }

    private String principalReference() {
        return input.principal() == null ? "" : input.principal().canonical();
    }

    private RuntimeExecutionProvenance provenance(RuntimeBinding binding, String idempotencyKey,
                                                  ContentHash invocationInputHash,
                                                  CompiledRuntimeContext runtimeContext,
                                                  long deadlineMillis,
                                                  CorrelationId invocationId) {
        RuntimePrincipal principal = input.principal();
        if (principal == null) {
            if (securityBoundary.requiresTrustedPrincipal()) {
                throw new IllegalStateException("Runtime Invocation Requires A Trusted Principal");
            }
            principal = new RuntimePrincipal(
                RuntimePrincipal.Kind.SYSTEM,
                input.authority().identity(),
                UUID.nameUUIDFromBytes((leaseId + ":" + input.authority().identity()).getBytes(StandardCharsets.UTF_8)),
                input.authority().identity());
        }
        RuntimeProviderDescriptor provider = runtimeSnapshot.provider(binding.provider()).orElseThrow(
            () -> new IllegalStateException("Runtime Provider Is Missing From Lease Snapshot"));
        return new RuntimeExecutionProvenance(
            input.authority().identity(),
            principal,
            binding.key(),
            binding.provider(),
            provider.version(),
            runtimeSnapshot.generation(),
            runtimeSnapshot.bindingManifestHash(),
            input.catalogGeneration(),
            input.catalogHash(),
            input.planFingerprint(),
            fingerprints.get(binding.key()),
            idempotencyKey,
            Objects.requireNonNull(invocationId, "Invocation ID Is Required"),
            input.mutationId() == null
                && binding.descriptor().semantics().idempotency() == RuntimeSemantics.Idempotency.MUTATION_ID
                ? idempotencyKey : input.mutationId(),
            invocationInputHash,
            contextHash(runtimeContext),
            deadlineMillis,
            leaseId);
    }

    private RuntimeLeaseInput.AuditEvent auditEvent(RuntimeBinding binding, String idempotencyKey,
                                                     RuntimeResult.Status status, String phase,
                                                     ContentHash invocationInputHash,
                                                     CompiledRuntimeContext runtimeContext,
                                                     long deadlineMillis,
                                                     CorrelationId invocationId) {
        RuntimeSemantics semantics = binding.descriptor().semantics();
        if (semantics.audit() == RuntimeSemantics.Audit.NONE) {
            return null;
        }
        RuntimeExecutionProvenance provenance = provenance(binding, idempotencyKey, invocationInputHash,
            runtimeContext, deadlineMillis, invocationId);
        return RuntimeAuditEvent.create(
            leaseId,
            input.authority(),
            binding.key(),
            idempotencyKey,
            status,
            semantics.audit() == RuntimeSemantics.Audit.FULL_REDACTED
                || semantics.sensitiveData() != RuntimeSemantics.SensitiveData.NONE,
            phase,
            provenance).leaseEvent();
    }

    private static ContentHash inputHash(Map<PinId, TypedValue> inputs,
                                         CompiledRuntimeContext runtimeContext,
                                         long deadlineMillis) {
        Map<String, Object> values = new TreeMap<>();
        inputs.forEach((pin, value) -> values.put(pin.canonicalText(), value.canonicalValue()));
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("inputs", values);
        canonical.put("runtimeContext", runtimeContext == null ? Map.of() : runtimeContext.canonicalValue());
        canonical.put("deadlineMillis", deadlineMillis);
        return ContentHash.of(CanonicalJson.sha256("runtime-invocation", canonical));
    }

    private static Map<PinId, TypedValue> immutableInputs(Map<PinId, TypedValue> inputs) {
        Objects.requireNonNull(inputs, "Inputs Are Required");
        Map<PinId, TypedValue> copy = new TreeMap<>();
        inputs.forEach((pin, value) -> copy.put(
            Objects.requireNonNull(pin, "Input Pin Cannot Be Null"),
            Objects.requireNonNull(value, "Input Value Cannot Be Null")));
        return Collections.unmodifiableMap(copy);
    }

    private static ContentHash contextHash(CompiledRuntimeContext runtimeContext) {
        return ContentHash.of(CanonicalJson.sha256("runtime-context",
            runtimeContext == null ? Map.of() : runtimeContext.canonicalValue()));
    }

    private void runAttempts(
        RuntimeBinding binding,
        Map<PinId, TypedValue> inputs,
        String idempotencyKey,
        ContentHash invocationInputHash,
        CompletableFuture<RuntimeResult> outcome,
        int attempt,
        RuntimeCancellationToken suppliedToken,
        long deadlineMillis,
        CompiledRuntimeContext runtimeContext,
        CorrelationId invocationId
    ) {
        RuntimeBindingKey key = binding.key();
        RuntimeCancellationToken providerToken = providerTokens.get(binding.provider());
        RuntimeCancellationToken bindingToken = bindingTokens.get(key);
        boolean cancellable = binding.descriptor().semantics().cancellable();
        if (deadlineExpired(deadlineMillis)) {
            completeOutcome(binding, idempotencyKey, outcome, timeoutResult(binding), invocationInputHash,
                runtimeContext, deadlineMillis, invocationId);
            invocationFinished();
            return;
        }
        if (cancellable && cancellationRequested(suppliedToken, providerToken, bindingToken)) {
            completeOutcome(binding, idempotencyKey, outcome, cancellationResult(binding), invocationInputHash,
                runtimeContext, deadlineMillis, invocationId);
            invocationFinished();
            return;
        }
        RuntimeCancellationToken invocationToken = bindingToken.child(deadlineMillis);
        AtomicBoolean attemptFinished = new AtomicBoolean();
        AtomicBoolean stageAttached = new AtomicBoolean();
        AtomicBoolean attemptReleased = new AtomicBoolean();
        long timeoutMillis = deadlineMillis == RuntimeExecutionContext.NO_DEADLINE
            ? 0
            : Math.max(1, deadlineMillis - System.currentTimeMillis());
        AtomicReference<ScheduledFuture<?>> timeoutReference = new AtomicReference<>();
        AtomicReference<RuntimeExecutionContext> executionContext = new AtomicReference<>();
        AtomicBoolean runtimeTerminal = new AtomicBoolean();
        Runnable releaseAttempt = () -> {
            if (attemptReleased.compareAndSet(false, true)) {
                invocationFinished();
            }
        };

        Runnable cancellationAction = () -> {
            invocationToken.cancel();
            if (attemptFinished.compareAndSet(false, true)) {
                cancel(timeoutReference.get());
                RuntimeResult cancelled = deadlineTriggered(deadlineMillis, suppliedToken, providerToken, bindingToken)
                    ? timeoutResult(binding)
                    : cancellationResult(binding);
                    completeOutcome(binding, idempotencyKey, outcome,
                    validateAttemptResult(binding, cancelled, executionContext.get(), true), invocationInputHash,
                    runtimeContext, deadlineMillis, invocationId);
                if (!stageAttached.get()) {
                    releaseAttempt.run();
                }
            }
        };
        if (cancellable && suppliedToken != null) {
            suppliedToken.cancelled().thenRun(cancellationAction);
        }
        if (cancellable) {
            bindingToken.cancelled().thenRun(cancellationAction);
        }
        if (cancellable && providerToken != null) {
            providerToken.cancelled().thenRun(cancellationAction);
        }
        if (!deadlineExpired(deadlineMillis) && cancellable
            && cancellationRequested(suppliedToken, providerToken, bindingToken)) {
            cancellationAction.run();
        }
        if (timeoutMillis > 0) {
            ScheduledFuture<?> scheduled = TIMER.schedule(() -> {
                if (attemptFinished.compareAndSet(false, true)) {
                    invocationToken.cancel();
                    finishAttempt(binding, inputs, idempotencyKey, invocationInputHash, outcome, attempt, suppliedToken, deadlineMillis,
                        runtimeContext,
                        invocationId, validateAttemptResult(binding, timeoutResult(binding), executionContext.get(), true));
                    if (!stageAttached.get()) {
                        releaseAttempt.run();
                    }
                }
            }, timeoutMillis, TimeUnit.MILLISECONDS);
            timeoutReference.set(scheduled);
            if (attemptFinished.get()) {
                scheduled.cancel(false);
            }
        }
        if (deadlineExpired(deadlineMillis) && attemptFinished.compareAndSet(false, true)) {
            cancel(timeoutReference.get());
            completeOutcome(binding, idempotencyKey, outcome, timeoutResult(binding), invocationInputHash,
                runtimeContext, deadlineMillis, invocationId);
            releaseAttempt.run();
            return;
        }
        if (attemptFinished.get()) {
            releaseAttempt.run();
            return;
        }

        stageAttached.set(true);
        RuntimeInvocation invocation = new RuntimeInvocation(key, inputs, idempotencyKey, invocationToken)
            .withRuntimeContext(runtimeContext)
            .withInvocationId(invocationId);
        CompletionStage<RuntimeResult> stage;
        if (attemptFinished.get()) {
            stage = CompletableFuture.completedFuture(cancellationResult(binding));
        } else {
            try {
                stage = invoke(binding, invocation, executionContext, deadlineMillis);
            } catch (Throwable throwable) {
                runtimeTerminal.set(isCancellation(throwable));
                stage = CompletableFuture.completedFuture(normalizeFailure(binding, throwable, deadlineMillis));
            }
        }
        if (stage == null) {
            stage = CompletableFuture.completedFuture(RuntimeResult.failure(runtimeFailure(binding,
                "RUNTIME.NULL_RESULT", "Runtime Handler Returned No Result")));
        }
        stage.whenComplete((result, failure) -> {
            cancel(timeoutReference.get());
            if (!attemptFinished.compareAndSet(false, true)) {
                releaseAttempt.run();
                return;
            }
            RuntimeResult normalized = failure == null
                ? result == null
                    ? RuntimeResult.failure(runtimeFailure(binding, "RUNTIME.NULL_RESULT", "Runtime Handler Returned No Result"))
                    : result
                : normalizeFailure(binding, failure, deadlineMillis);
            normalized = validateAttemptResult(binding, normalized, executionContext.get(),
                runtimeTerminal.get() || (failure != null && isCancellation(failure)));
            finishAttempt(binding, inputs, idempotencyKey, invocationInputHash, outcome, attempt, suppliedToken,
                deadlineMillis, runtimeContext, invocationId, normalized);
            releaseAttempt.run();
        });
    }

    private void finishAttempt(
        RuntimeBinding binding,
        Map<PinId, TypedValue> inputs,
        String idempotencyKey,
        ContentHash invocationInputHash,
        CompletableFuture<RuntimeResult> outcome,
        int attempt,
        RuntimeCancellationToken suppliedToken,
        long deadlineMillis,
        CompiledRuntimeContext runtimeContext,
        CorrelationId invocationId,
        RuntimeResult result
    ) {
        if (!outcome.isDone() && shouldRetry(binding, result, attempt, suppliedToken, deadlineMillis)) {
            if (invocationStarted()) {
                runAttempts(binding, inputs, idempotencyKey, invocationInputHash, outcome, attempt + 1, suppliedToken,
                    deadlineMillis, runtimeContext, invocationId);
                return;
            }
            result = runtimeFailureResult(binding, "RUNTIME.PROVIDER_DRAINING", "Runtime Lease Is Closing");
        }
        completeOutcome(binding, idempotencyKey, outcome, result, invocationInputHash, runtimeContext, deadlineMillis,
            invocationId);
    }

    private void completeOutcome(
        RuntimeBinding binding,
        String idempotencyKey,
        CompletableFuture<RuntimeResult> outcome,
        RuntimeResult result,
        ContentHash invocationInputHash,
        CompiledRuntimeContext runtimeContext,
        long deadlineMillis,
        CorrelationId invocationId
    ) {
        RuntimeReceiptStore.Key receiptKey = receiptKey(binding, idempotencyKey);
        RuntimeReceiptStore.Key auditKey = receiptKey == null ? auditKey(binding, idempotencyKey) : receiptKey;
        RuntimeExecutionProvenance provenance = provenance(binding, idempotencyKey, invocationInputHash,
            runtimeContext, deadlineMillis, invocationId);
        RuntimeLeaseInput.AuditEvent event = auditEvent(binding, idempotencyKey, result.status(), "outcome",
            invocationInputHash, runtimeContext, deadlineMillis, invocationId);
        RuntimeReceiptStore.Key outcomeKey = receiptKey == null && event != null
            ? auditKey : receiptKey;
        try {
            if (outcomeKey != null) {
                receiptStore.complete(outcomeKey, result, provenance, event);
            }
        } catch (RuntimeException | Error failure) {
            if (event != null) {
                try {
                    receiptStore.recordPendingAudit(auditKey, provenance, event, failure);
                } catch (RuntimeException | Error ignored) {
                }
            }
            outcome.complete(runtimeFailureResult(binding, "RUNTIME.INVALID_INVOCATION",
                "Runtime Outcome Could Not Be Durably Recorded"));
            return;
        }
        if (event != null) {
            try {
                auditBoundary.record(event);
                receiptStore.markAuditRecorded(auditKey, event);
                synchronized (monitor) {
                    auditEvents.add(event);
                }
            } catch (RuntimeException | Error failure) {
                try {
                    receiptStore.recordPendingAudit(auditKey, provenance, event, failure);
                } catch (RuntimeException | Error ignored) {
                }
            }
        }
        outcome.complete(result);
    }

    private static void cancel(ScheduledFuture<?> timeout) {
        if (timeout != null) {
            timeout.cancel(false);
        }
    }

    private CompletionStage<RuntimeResult> invoke(
        RuntimeBinding binding,
        RuntimeInvocation invocation,
        AtomicReference<RuntimeExecutionContext> executionContext,
        long deadlineMillis
    ) {
        RuntimeOperationHandler handler = binding.handler().orElseThrow(() ->
            new RuntimeCapabilityUnavailableException(binding.key(), "binding has no handler"));
        RuntimeExecutionContext context = new RuntimeExecutionContext(
            binding.descriptor(), input.authority(), input.principal(), deadlineMillis);
        executionContext.set(context);
        invocation.throwIfCancelled();
        RuntimeSemantics semantics = binding.descriptor().semantics();
        boolean principalRequired = semantics.effect() == RuntimeSemantics.Effect.STATE_MUTATING
            || semantics.effect() == RuntimeSemantics.Effect.DESTRUCTIVE
            || semantics.sensitiveData() != RuntimeSemantics.SensitiveData.NONE;
        if (principalRequired && securityBoundary.requiresTrustedPrincipal()
            && !securityBoundary.principalAllowed(context)) {
            throw new RuntimeCapabilityUnavailableException(binding.key(), "RUNTIME.AUTHORIZATION_DENIED",
                "trusted runtime principal is required");
        }
        if (!executionBoundary.supports(context)) {
            throw new RuntimeCapabilityUnavailableException(binding.key(), "RUNTIME.INVALID_INVOCATION", "runtime execution policy is unavailable");
        }
        context.establishPolicyEvidence();
        RuntimeInvocation checkedInvocation = invocation.withExecutionContext(context);
        return executionBoundary.execute(context, checkedInvocation, context.handlerView(handler));
    }

    private RuntimeResult validateAttemptResult(
        RuntimeBinding binding,
        RuntimeResult result,
        RuntimeExecutionContext executionContext,
        boolean runtimeTerminal
    ) {
        if (runtimeTerminal) {
            return validateResult(binding, result);
        }
        if (executionContext == null) {
            return RuntimeResult.failure(runtimeFailure(binding,
                "RUNTIME.INVALID_INVOCATION", "Runtime Execution Evidence Is Missing"));
        }
        if (executionContext.deadlineExceeded()) {
            return timeoutResult(binding);
        }
        try {
            executionContext.verifyTerminal(result.status());
            return validateResult(binding, result);
        } catch (RuntimeException failure) {
            return RuntimeResult.failure(runtimeFailure(binding,
                "RUNTIME.INVALID_INVOCATION", "Runtime Execution Evidence Is Invalid"));
        }
    }

    private boolean shouldRetry(
        RuntimeBinding binding,
        RuntimeResult result,
        int attempt,
        RuntimeCancellationToken suppliedToken,
        long deadlineMillis
    ) {
        RuntimeSemantics semantics = binding.descriptor().semantics();
        if (result.status() != RuntimeResult.Status.FAILURE || result.failure() == null) {
            return false;
        }
        if (!result.failure().retryable()
            || semantics.retry() == RuntimeSemantics.Retry.NEVER
            || attempt >= 1
            || "RUNTIME.EXECUTION_TIMEOUT".equals(result.failure().diagnostic().code())
            || deadlineExpired(deadlineMillis)
            || cancellationRequested(suppliedToken, providerTokens.get(binding.provider()), bindingTokens.get(binding.key()))) {
            return false;
        }
        RuntimeExecutionContext context = new RuntimeExecutionContext(
            binding.descriptor(),
            input.authority(),
            input.principal(),
            deadlineMillis);
        try {
            if (!securityBoundary.authorize(input.authority(), semantics.authorization())
                || !securityBoundary.confirm(input.authority(), binding.key(), semantics.confirmation())) {
                return false;
            }
            return switch (semantics.retry()) {
                case SAFE -> executionBoundary.approveSafeRetry(context, result.failure(), attempt);
                case POLICY -> securityBoundary.approvePolicyRetry(input.authority(), binding.key(), result.failure(), attempt);
                case NEVER -> false;
            };
        } catch (RuntimeException | Error failure) {
            return false;
        }
    }

    private RuntimeResult normalizeFailure(RuntimeBinding binding, Throwable failure, long deadlineMillis) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause instanceof CompletionException) {
            cause = cause.getCause();
        }
        if (deadlineExpired(deadlineMillis)) {
            return timeoutResult(binding);
        }
        if (cause instanceof RuntimeOperationCancelledException) {
            return cancellationResult(binding);
        }
        String failureMessage = binding.descriptor().semantics().sensitiveData() == RuntimeSemantics.SensitiveData.NONE
            ? message(cause)
            : "Runtime Handler Failure";
        return RuntimeResult.failure(runtimeFailure(binding, "RUNTIME.HANDLER_FAILURE", failureMessage));
    }

    private static boolean isCancellation(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause instanceof CompletionException) {
            cause = cause.getCause();
        }
        return cause instanceof RuntimeOperationCancelledException;
    }

    private RuntimeResult validateResult(RuntimeBinding binding, RuntimeResult result) {
        if (result.branch() != null) {
            Set<String> allowed = switch (result.status()) {
                case SUCCESS -> binding.descriptor().semantics().successBranches();
                case FAILURE -> binding.descriptor().semantics().failureBranches();
                case CANCELLED -> binding.descriptor().semantics().cancellationBranches();
            };
            if (!allowed.contains(result.branch())) {
                return RuntimeResult.failure(runtimeFailure(binding,
                    "RUNTIME.RESULT_BRANCH_INVALID", "Runtime Result Branch Is Not Declared By The Operation"));
            }
        }
        if (result.status() == RuntimeResult.Status.FAILURE) {
            RuntimeFailure failure = result.failure();
            RuntimeFailureContract contract = binding.descriptor().semantics().failureContract();
            if (!failure.hasPayload(contract.payloadType())) {
                return RuntimeResult.failure(runtimeFailure(binding,
                    "RUNTIME.FAILURE_PAYLOAD_TYPE", "Runtime Failure Payload Does Not Match The Operation Contract"));
            }
            String code = failure.diagnostic().code();
            if (!RUNTIME_CODES.contains(code) && !contract.acceptsDiagnosticCode(code)) {
                return RuntimeResult.failure(runtimeFailure(binding,
                    "RUNTIME.INVALID_INVOCATION", "Runtime Failure Code Is Not Declared By The Operation"));
            }
            return sanitizeFailure(binding, result);
        }
        if (result.status() == RuntimeResult.Status.CANCELLED) {
            RuntimeFailureContract contract = binding.descriptor().semantics().failureContract();
            RuntimeFailure failure = result.failure();
            if (!failure.hasPayload(contract.payloadType())) {
                return RuntimeResult.failure(runtimeFailure(binding,
                    "RUNTIME.FAILURE_PAYLOAD_TYPE", "Runtime Cancellation Payload Does Not Match The Operation Contract"));
            }
            String code = failure.diagnostic().code();
            if (!RUNTIME_CODES.contains(code) && !contract.acceptsDiagnosticCode(code)) {
                return RuntimeResult.failure(runtimeFailure(binding,
                    "RUNTIME.INVALID_INVOCATION", "Runtime Cancellation Diagnostic Is Not Declared By The Operation"));
            }
            if (result.branch() == null
                || !binding.descriptor().semantics().cancellationBranches().contains(result.branch())) {
                return RuntimeResult.failure(runtimeFailure(binding,
                    "RUNTIME.RESULT_BRANCH_MISMATCH", "Runtime Cancellation Branch Is Not Declared By The Operation"));
            }
            return sanitizeFailure(binding, result);
        }

        RuntimeLeaseInput.BindingRequirement requirement = requirements.get(binding.key());
        List<RuntimeOperationDescriptor.Pin> expectedPins = outputPins(binding.descriptor());
        List<PinId> expectedIds = pinIds(expectedPins);
        if (!requirement.outputPins().equals(expectedIds)) {
            return RuntimeResult.failure(runtimeFailure(binding,
                "RUNTIME.RESULT_OUTPUT_COUNT", "Runtime Result Does Not Provide The Declared Operation Outputs"));
        }
        if (result.value() != null) {
            if (expectedPins.size() != 1) {
                return RuntimeResult.failure(runtimeFailure(binding,
                    "RUNTIME.RESULT_OUTPUT_COUNT", "Runtime Result Does Not Provide The Declared Operation Outputs"));
            }
            RuntimeResult checked = validateOutputValue(binding, expectedPins.getFirst().type(), result.value());
            return checked.successful() ? result : checked;
        }
        if (!result.outputs().keySet().equals(Set.copyOf(expectedIds))) {
            return RuntimeResult.failure(runtimeFailure(binding,
                "RUNTIME.RESULT_OUTPUT_COUNT", "Runtime Result Does Not Provide The Declared Operation Outputs"));
        }
        for (RuntimeOperationDescriptor.Pin pin : expectedPins) {
            RuntimeResult checked = validateOutputValue(binding, pin.type(), result.outputs().get(pin.id()));
            if (!checked.successful()) {
                return checked;
            }
        }
        return result;
    }

    private RuntimeResult validateOutputValue(RuntimeBinding binding, TypeExpr expected, TypedValue value) {
        if (!expected.equals(value.type())) {
            return RuntimeResult.failure(runtimeFailure(binding,
                "RUNTIME.RESULT_TYPE_MISMATCH", "Runtime Result Type Does Not Match The Operation Output"));
        }
        RuntimeSemantics semantics = binding.descriptor().semantics();
        if (value.locator() != null
            && !semantics.resourceReads().contains(value.locator().type())
            && !semantics.resourceWrites().contains(value.locator().type())) {
            return RuntimeResult.failure(runtimeFailure(binding,
                "RUNTIME.RESOURCE_ACCESS_UNDECLARED", "Runtime Result Uses An Undeclared Resource"));
        }
        return RuntimeResult.success(value);
    }

    private RuntimeResult audited(RuntimeBinding binding, String idempotencyKey, RuntimeResult result,
                                  ContentHash invocationInputHash, CompiledRuntimeContext runtimeContext,
                                  long deadlineMillis, CorrelationId invocationId) {
        RuntimeSemantics semantics = binding.descriptor().semantics();
        if (semantics.audit() == RuntimeSemantics.Audit.NONE) {
            return result;
        }
        try {
            RuntimeLeaseInput.AuditEvent leaseEvent = auditEvent(
                binding, idempotencyKey, result.status(), "preflight", invocationInputHash,
                runtimeContext, deadlineMillis, invocationId);
            auditBoundary.record(leaseEvent);
            synchronized (monitor) {
                auditEvents.add(leaseEvent);
            }
            return result;
        } catch (RuntimeException | Error failure) {
            return RuntimeResult.failure(runtimeFailure(binding, "RUNTIME.AUDIT_FAILURE", "Runtime Audit Could Not Be Recorded"));
        }
    }

    private static Map<RuntimeBindingKey, RuntimeBinding> immutableBindings(Map<RuntimeBindingKey, RuntimeBinding> values) {
        Objects.requireNonNull(values, "Lease Bindings Are Required");
        Map<RuntimeBindingKey, RuntimeBinding> copy = new HashMap<>();
        values.forEach((key, value) -> {
            RuntimeBindingKey checkedKey = Objects.requireNonNull(key, "Binding Key Cannot Be Null");
            RuntimeBinding checkedValue = Objects.requireNonNull(value, "Binding Cannot Be Null");
            if (!checkedKey.equals(checkedValue.key())) {
                throw new IllegalArgumentException("Lease Binding Key Does Not Match Binding");
            }
            copy.put(checkedKey, checkedValue);
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Map<RuntimeBindingKey, ContentHash> immutableFingerprints(
        Map<RuntimeBindingKey, ContentHash> values,
        Map<RuntimeBindingKey, RuntimeBinding> bindings
    ) {
        Objects.requireNonNull(values, "Binding Fingerprints Are Required");
        Map<RuntimeBindingKey, ContentHash> copy = new HashMap<>();
        bindings.keySet().forEach(key -> {
            ContentHash fingerprint = Objects.requireNonNull(values.get(key), "Binding Fingerprint Is Required");
            if (!fingerprint.equals(bindings.get(key).executionFingerprint())) {
                throw new IllegalArgumentException("Lease Fingerprint Does Not Match Binding: " + key.canonical());
            }
            copy.put(key, fingerprint);
        });
        if (copy.size() != values.size()) {
            throw new IllegalArgumentException("Lease Fingerprints Must Match Lease Bindings");
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<RuntimeBindingKey, RuntimeLeaseInput.BindingRequirement> immutableRequirements(
        Map<RuntimeBindingKey, RuntimeLeaseInput.BindingRequirement> values,
        Map<RuntimeBindingKey, RuntimeBinding> bindings,
        Map<RuntimeBindingKey, ContentHash> fingerprints
    ) {
        Objects.requireNonNull(values, "Binding Requirements Are Required");
        Map<RuntimeBindingKey, RuntimeLeaseInput.BindingRequirement> copy = new HashMap<>();
        bindings.forEach((key, binding) -> {
            RuntimeLeaseInput.BindingRequirement requirement = Objects.requireNonNull(values.get(key), "Binding Requirement Is Required");
            if (!key.equals(requirement.binding()) || !fingerprints.get(key).equals(requirement.fingerprint())) {
                throw new IllegalArgumentException("Lease Requirement Does Not Match Binding: " + key.canonical());
            }
            if (!requirement.inputPins().equals(pinIds(inputPins(binding.descriptor())))) {
                throw new IllegalArgumentException("Lease Input Pins Do Not Match Binding Descriptor: " + key.canonical());
            }
            if (!requirement.outputPins().equals(pinIds(outputPins(binding.descriptor())))) {
                throw new IllegalArgumentException("Lease Output Pins Do Not Match Binding Descriptor: " + key.canonical());
            }
            copy.put(key, requirement);
        });
        if (copy.size() != values.size()) {
            throw new IllegalArgumentException("Lease Requirements Must Match Lease Bindings");
        }
        return Collections.unmodifiableMap(copy);
    }

    private static List<RuntimeOperationDescriptor.Pin> inputPins(RuntimeBindingDescriptor descriptor) {
        return descriptor.pins().stream()
            .filter(pin -> pin.direction() == RuntimeOperationDescriptor.Direction.INPUT)
            .toList();
    }

    private static List<RuntimeOperationDescriptor.Pin> outputPins(RuntimeBindingDescriptor descriptor) {
        return descriptor.pins().stream()
            .filter(pin -> pin.direction() == RuntimeOperationDescriptor.Direction.OUTPUT)
            .toList();
    }

    private static List<PinId> pinIds(List<RuntimeOperationDescriptor.Pin> pins) {
        return pins.stream().map(RuntimeOperationDescriptor.Pin::id).toList();
    }

    private static Map<RuntimeBindingKey, ContentHash> bindingFingerprints(Map<RuntimeBindingKey, RuntimeBinding> bindings) {
        Map<RuntimeBindingKey, ContentHash> fingerprints = new HashMap<>();
        bindings.forEach((key, binding) -> fingerprints.put(key, binding.executionFingerprint()));
        return fingerprints;
    }

    private static Map<RuntimeBindingKey, RuntimeCancellationToken> bindingTokens(
        Map<RuntimeBindingKey, RuntimeBinding> bindings,
        Map<ContractRef<ProviderId>, RuntimeCancellationToken> providerTokens
    ) {
        Map<RuntimeBindingKey, RuntimeCancellationToken> tokens = new HashMap<>();
        bindings.forEach((key, binding) -> {
            RuntimeCancellationToken providerToken = providerTokens.get(binding.provider());
            if (providerToken == null) {
                throw new IllegalArgumentException("Provider Token Is Missing: " + binding.provider().canonicalText());
            }
            tokens.put(key, providerToken.child());
        });
        return tokens;
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + UUID.randomUUID());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static String message(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getMessage() == null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private RuntimeFailure runtimeFailure(RuntimeBinding binding, String code, String message) {
        TypeExpr payloadType = binding.descriptor().semantics().failureContract().payloadType();
        TypedValue payload = TypedValue.nullValue(payloadType);
        return new RuntimeFailure(diagnostic(code, message), code.equals("RUNTIME.EXECUTION_TIMEOUT"), payload);
    }

    private RuntimeResult runtimeFailureResult(RuntimeBinding binding, String code, String message) {
        return RuntimeResult.failure(runtimeFailure(binding, code, message));
    }

    private RuntimeResult timeoutResult(RuntimeBinding binding) {
        return runtimeFailureResult(binding, "RUNTIME.EXECUTION_TIMEOUT", "Runtime Operation Timed Out");
    }

    private RuntimeResult cancellationResult(RuntimeBinding binding) {
        return RuntimeResult.cancelled(
            runtimeFailure(binding, "RUNTIME.CANCELLED", "Runtime Operation Cancelled"),
            cancellationBranch(binding));
    }

    private RuntimeResult sanitizeFailure(RuntimeBinding binding, RuntimeResult result) {
        RuntimeSemantics.SensitiveData sensitiveData = binding.descriptor().semantics().sensitiveData();
        if (sensitiveData == RuntimeSemantics.SensitiveData.NONE) {
            return result;
        }
        RuntimeFailure failure = result.failure();
        Diagnostic source = failure.diagnostic();
        String localId = source.code().toLowerCase(Locale.ROOT).replace('.', '-');
        Diagnostic sanitizedDiagnostic = Diagnostic.builder(
                source.code(), source.severity(), source.phase(), source.stage())
            .messageKey(new ContractRef<>(new OwnerId("restudio.resync"), new NodeId(localId)))
            .arguments(Map.of())
            .evidence(Map.of())
            .remediation(source.remediation())
            .correlationId(UUID.randomUUID())
            .context(DiagnosticContext.empty())
            .durable(source.durable())
            .redaction(DiagnosticRedaction.PUBLIC)
            .metricPolicy(source.metricPolicy())
            .build();
        TypedValue payload = failure.payload() == null
            ? null
            : TypedValue.nullValue(failure.payload().type());
        RuntimeFailure sanitizedFailure = new RuntimeFailure(sanitizedDiagnostic, failure.retryable(), payload);
        return result.status() == RuntimeResult.Status.CANCELLED
            ? new RuntimeResult(RuntimeResult.Status.CANCELLED, null, Map.of(), result.branch(), sanitizedFailure)
            : RuntimeResult.failure(sanitizedFailure, result.branch());
    }

    private static Diagnostic diagnostic(String code, String message) {
        String localId = code.toLowerCase(Locale.ROOT).replace('.', '-');
        String normalizedMessage = message == null || message.isBlank() ? "Runtime execution failed" : message;
        if (normalizedMessage.length() > 512) {
            normalizedMessage = normalizedMessage.substring(0, 512);
        }
        return Diagnostic.builder(code, DiagnosticSeverity.ERROR, DiagnosticPhase.ENVIRONMENT, "execution")
            .messageKey(new ContractRef<>(new OwnerId("restudio.resync"), new NodeId(localId)))
            .message(normalizedMessage)
            .remediation("Inspect the runtime execution diagnostic.")
            .correlationId(UUID.randomUUID())
            .build();
    }

    private static String cancellationBranch(RuntimeBinding binding) {
        return binding.descriptor().semantics().cancellationBranches().stream().sorted().findFirst().orElse(null);
    }

    private static boolean deadlineExpired(long deadlineMillis) {
        return deadlineMillis != RuntimeExecutionContext.NO_DEADLINE
            && System.currentTimeMillis() >= deadlineMillis;
    }

    private static boolean deadlineTriggered(
        long deadlineMillis,
        RuntimeCancellationToken suppliedToken,
        RuntimeCancellationToken providerToken,
        RuntimeCancellationToken bindingToken
    ) {
        return deadlineExpired(deadlineMillis)
            || (suppliedToken != null && suppliedToken.deadlineExceeded())
            || (providerToken != null && providerToken.deadlineExceeded())
            || (bindingToken != null && bindingToken.deadlineExceeded());
    }

    private static boolean cancellationRequested(
        RuntimeCancellationToken suppliedToken,
        RuntimeCancellationToken providerToken,
        RuntimeCancellationToken bindingToken
    ) {
        return (suppliedToken != null && suppliedToken.isCancelled())
            || (providerToken != null && providerToken.isCancelled())
            || (bindingToken != null && bindingToken.isCancelled());
    }

    private long effectiveDeadline(
        RuntimeBinding binding,
        RuntimeCancellationToken suppliedToken,
        RuntimeCancellationToken providerToken,
        RuntimeCancellationToken bindingToken
    ) {
        long requested = input.requestedDeadlineMillis();
        long deadline = requested == RuntimeExecutionContext.NO_DEADLINE
            ? stableSemanticDeadlines.computeIfAbsent(binding.key(), ignored ->
                binding.descriptor().semantics().executionDeadlineMillis(System.currentTimeMillis()))
            : requested;
        if (suppliedToken != null) {
            deadline = Math.min(deadline, suppliedToken.deadlineMillis());
        }
        if (providerToken != null) {
            deadline = Math.min(deadline, providerToken.deadlineMillis());
        }
        return Math.min(deadline, bindingToken.deadlineMillis());
    }

    private void invocationFinished() {
        RuntimeExecutionBarrier.Admission admission;
        RuntimeReceiptStore.InvocationLease receiptAdmission;
        Runnable releaseNow = null;
        synchronized (monitor) {
            if (inFlight <= 0) {
                return;
            }
            inFlight--;
            admission = executionAdmissions.removeLast();
            receiptAdmission = receiptAdmissions.removeLast();
            if (closing && inFlight == 0 && !released) {
                released = true;
                releaseNow = release;
            }
        }
        admission.close();
        receiptAdmission.close();
        if (releaseNow != null) {
            releaseNow.run();
        }
    }

    private boolean invocationStarted() {
        synchronized (monitor) {
            if (closing || released) {
                return false;
            }
            admitInvocationLocked();
            return true;
        }
    }

    private void admitInvocationLocked() {
        RuntimeReceiptStore.InvocationLease receiptAdmission = receiptStore.acquireInvocationLease();
        RuntimeExecutionBarrier.Admission admission;
        try {
            admission = executionBarrier.tryAcquire().orElseThrow(
                () -> new IllegalStateException("Runtime Lease Execution Admissions Are Fenced"));
        } catch (RuntimeException | Error failure) {
            receiptAdmission.close();
            throw failure;
        }
        inFlight++;
        executionAdmissions.add(admission);
        receiptAdmissions.add(receiptAdmission);
    }

    private void fenceExecutionAdmissions() {
        executionBarrier.fence();
    }

    CompletionStage<Void> executionDrainSignal() {
        synchronized (monitor) {
            if (!closing) {
                throw new IllegalStateException("Runtime Lease Must Be Closing Before Drain");
            }
            return executionBarrier.drainSignal();
        }
    }

    void fenceForProvider(ContractRef<ProviderId> provider, boolean cancel) {
        Objects.requireNonNull(provider, "Provider Is Required");
        if (!providerTokens.containsKey(provider)) {
            return;
        }
        fencedProviders.add(provider);
        bindings.values().stream()
            .filter(binding -> provider.equals(binding.provider()))
            .map(RuntimeBinding::key)
            .forEach(fencedBindings::add);
        RuntimeCancellationToken token = providerTokens.get(provider);
        if (cancel && token != null) {
            token.cancel();
        }
    }

    void fenceForBinding(RuntimeBindingKey key, boolean cancel) {
        if (!bindingTokens.containsKey(key)) {
            return;
        }
        fencedBindings.add(key);
        RuntimeCancellationToken token = bindingTokens.get(key);
        if (cancel && token != null) {
            token.cancel();
        }
    }

    void unfenceForProvider(ContractRef<ProviderId> provider) {
        if (released) {
            return;
        }
        fencedProviders.remove(provider);
        bindings.values().stream()
            .filter(binding -> provider.equals(binding.provider()))
            .map(RuntimeBinding::key)
            .forEach(fencedBindings::remove);
    }

    void unfenceForBinding(RuntimeBindingKey key) {
        if (!released) {
            fencedBindings.remove(key);
        }
    }

    boolean usesProvider(ContractRef<ProviderId> provider) {
        return providerTokens.containsKey(provider);
    }

    boolean usesBinding(RuntimeBindingKey key) {
        return bindingTokens.containsKey(key);
    }

    boolean hasActiveInvocations() {
        synchronized (monitor) {
            return inFlight > 0;
        }
    }

    int activeInvocations() {
        synchronized (monitor) {
            return inFlight;
        }
    }

    @Override
    public void close() {
        Runnable releaseNow = null;
        synchronized (monitor) {
            if (closing) {
                return;
            }
            closing = true;
            fenceExecutionAdmissions();
            if (inFlight == 0 && !released) {
                released = true;
                releaseNow = release;
            }
        }
        if (releaseNow != null) {
            releaseNow.run();
        }
    }

    public boolean isClosing() {
        synchronized (monitor) {
            return closing;
        }
    }

    public boolean isReleased() {
        synchronized (monitor) {
            return released;
        }
    }

}

package restudio.resync.flow.runtime;

import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class RuntimeExecutionContext {
    public static final long NO_DEADLINE = Long.MAX_VALUE;

    private final RuntimeBindingDescriptor descriptor;
    private final RuntimeAuthority authority;
    private final RuntimePrincipal principal;
    private final RuntimeSemantics semantics;
    private final long deadlineMillis;
    private final AtomicReference<CommitState> commitState = new AtomicReference<>(CommitState.READY);
    private final AtomicReference<RuntimeSemantics.ThreadMode> establishedThread = new AtomicReference<>();
    private final AtomicReference<RuntimeSemantics.Effect> establishedEffect = new AtomicReference<>();
    private final AtomicBoolean policyEvidence = new AtomicBoolean();
    private final AtomicBoolean handlerInvoked = new AtomicBoolean();
    private final Set<ServerResourceLocator> establishedReads = ConcurrentHashMap.newKeySet();
    private final Set<ServerResourceLocator> establishedWrites = ConcurrentHashMap.newKeySet();

    public RuntimeExecutionContext(RuntimeBindingDescriptor descriptor, RuntimeAuthority authority) {
        this(descriptor, authority, null, NO_DEADLINE);
    }

    public RuntimeExecutionContext(RuntimeBindingDescriptor descriptor, RuntimeAuthority authority, long deadlineMillis) {
        this(descriptor, authority, null, deadlineMillis);
    }

    public RuntimeExecutionContext(RuntimeBindingDescriptor descriptor, RuntimeAuthority authority,
                                   RuntimePrincipal principal, long deadlineMillis) {
        this.descriptor = Objects.requireNonNull(descriptor, "Runtime Binding Descriptor Is Required");
        this.authority = Objects.requireNonNull(authority, "Runtime Authority Is Required");
        if (deadlineMillis < 0) {
            throw new IllegalArgumentException("Runtime Deadline Cannot Be Negative");
        }
        if (principal != null && !principal.authorityIdentity().equals(authority.identity())) {
            throw new IllegalArgumentException("Runtime Principal Does Not Belong To Runtime Authority");
        }
        this.principal = principal;
        this.deadlineMillis = deadlineMillis;
        semantics = descriptor.semantics();
    }

    public RuntimeBindingDescriptor descriptor() {
        return descriptor;
    }

    public RuntimeAuthority authority() {
        return authority;
    }

    public RuntimePrincipal principal() {
        return principal;
    }

    public long deadlineMillis() {
        return deadlineMillis;
    }

    public long remainingMillis() {
        if (deadlineMillis == NO_DEADLINE) {
            return Long.MAX_VALUE;
        }
        return Math.max(0, deadlineMillis - System.currentTimeMillis());
    }

    public boolean deadlineExceeded() {
        return deadlineMillis != NO_DEADLINE && System.currentTimeMillis() >= deadlineMillis;
    }

    public RuntimeSemantics.Effect effect() {
        return semantics.effect();
    }

    public RuntimeSemantics.ThreadMode thread() {
        return semantics.thread();
    }

    public RuntimeFailureContract.CommitBoundary commitBoundary() {
        return semantics.failureContract().commitBoundary();
    }

    public void requireCommitBoundary(RuntimeFailureContract.CommitBoundary actual) {
        if (Objects.requireNonNull(actual, "Actual Commit Boundary Is Required") != commitBoundary()) {
            throw new IllegalStateException("Runtime Commit Boundary Was Violated");
        }
    }

    public Set<ContractRef<ResourceTypeId>> resourceReads() {
        return semantics.resourceReads();
    }

    public Set<ContractRef<ResourceTypeId>> resourceWrites() {
        return semantics.resourceWrites();
    }

    public void requireThread(RuntimeSemantics.ThreadMode actual) {
        if (Objects.requireNonNull(actual, "Actual Thread Mode Is Required") != thread()) {
            throw new IllegalStateException("Runtime Thread Policy Was Violated");
        }
    }

    public void establishThread(RuntimeSemantics.ThreadMode actual) {
        requireThread(actual);
        if (!establishedThread.compareAndSet(null, actual) && establishedThread.get() != actual) {
            throw new IllegalStateException("Runtime Thread Evidence Changed");
        }
    }

    public void requireEffect(RuntimeSemantics.Effect actual) {
        if (Objects.requireNonNull(actual, "Actual Effect Is Required") != effect()) {
            throw new IllegalStateException("Runtime Effect Policy Was Violated");
        }
    }

    public void establishEffect(RuntimeSemantics.Effect actual) {
        requireEffect(actual);
        if (!establishedEffect.compareAndSet(null, actual) && establishedEffect.get() != actual) {
            throw new IllegalStateException("Runtime Effect Evidence Changed");
        }
    }

    public void requireRead(ServerResourceLocator locator) {
        requireResource(locator, resourceReads(), "read");
    }

    public void requireWrite(ServerResourceLocator locator) {
        requireResource(locator, resourceWrites(), "write");
    }

    public void establishRead(ServerResourceLocator locator) {
        requireRead(locator);
        establishedReads.add(locator);
    }

    public void establishWrite(ServerResourceLocator locator) {
        requireWrite(locator);
        establishedWrites.add(locator);
    }

    public void beginCommit() {
        if (commitBoundary() == RuntimeFailureContract.CommitBoundary.NO_MUTATION) {
            throw new IllegalStateException("Runtime Commit Boundary Forbids Mutation");
        }
        if (!commitState.compareAndSet(CommitState.READY, CommitState.OPEN)) {
            throw new IllegalStateException("Runtime Commit Boundary Was Already Open");
        }
    }

    public void completeCommit() {
        if (!commitState.compareAndSet(CommitState.OPEN, CommitState.COMMITTED)) {
            throw new IllegalStateException("Runtime Commit Boundary Was Not Open");
        }
    }

    public void abortCommit() {
        if (!commitState.compareAndSet(CommitState.OPEN, CommitState.ABORTED)) {
            throw new IllegalStateException("Runtime Commit Boundary Was Not Open");
        }
    }

    void establishPolicyEvidence() {
        policyEvidence.set(true);
    }

    void recordHandlerInvocation() {
        policyEvidence.set(true);
        if (!handlerInvoked.compareAndSet(false, true)) {
            throw new IllegalStateException("Runtime Handler Was Invoked More Than Once");
        }
    }

    void verifySuccess() {
        verifyTerminal(RuntimeResult.Status.SUCCESS);
    }

    void verifyTerminal(RuntimeResult.Status status) {
        Objects.requireNonNull(status, "Terminal Result Status Is Required");
        if (!policyEvidence.get()) {
            throw new IllegalStateException("Runtime Policy Evidence Is Missing");
        }
        if (!handlerInvoked.get()) {
            throw new IllegalStateException("Runtime Handler Invocation Evidence Is Missing");
        }
        if (establishedThread.get() == null) {
            throw new IllegalStateException("Runtime Thread Evidence Is Missing");
        }
        if (establishedEffect.get() == null) {
            throw new IllegalStateException("Runtime Effect Evidence Is Missing");
        }
        if (!resourceAccessDeclared(establishedReads, resourceReads())
            || !resourceAccessDeclared(establishedWrites, resourceWrites())) {
            throw new IllegalStateException("Runtime Resource Evidence Is Undeclared");
        }
        if (status == RuntimeResult.Status.SUCCESS
            && commitBoundary() != RuntimeFailureContract.CommitBoundary.NO_MUTATION
            && commitState.get() != CommitState.COMMITTED) {
            throw new IllegalStateException("Runtime Commit Evidence Is Incomplete");
        }
        if (status != RuntimeResult.Status.SUCCESS && commitState.get() == CommitState.OPEN) {
            throw new IllegalStateException("Runtime Terminal Commit Evidence Is Incomplete");
        }
        if (status == RuntimeResult.Status.SUCCESS
            && commitBoundary() == RuntimeFailureContract.CommitBoundary.NO_MUTATION
            && commitState.get() != CommitState.READY) {
            throw new IllegalStateException("Runtime No-Mutation Commit Boundary Was Violated");
        }
    }

    public RuntimeOperationHandler handlerView(RuntimeOperationHandler delegate) {
        Objects.requireNonNull(delegate, "Runtime Handler Is Required");
        return invocation -> {
            RuntimeInvocation checked = Objects.requireNonNull(invocation, "Runtime Invocation Is Required")
                .withExecutionContext(this);
            if (!descriptor.key().equals(checked.binding())) {
                throw new IllegalStateException("Runtime Handler View Received The Wrong Binding");
            }
            checked.throwIfCancelled();
            recordHandlerInvocation();
            return delegate.execute(checked);
        };
    }

    private static void requireResource(
        ServerResourceLocator locator,
        Set<ContractRef<ResourceTypeId>> allowed,
        String mode
    ) {
        Objects.requireNonNull(locator, "Resource Locator Is Required");
        if (!allowed.contains(locator.type())) {
            throw new IllegalStateException("Runtime " + mode + " Resource Is Not Declared");
        }
    }

    private static boolean resourceAccessDeclared(
        Set<ServerResourceLocator> accessed,
        Set<ContractRef<ResourceTypeId>> allowed
    ) {
        return accessed.stream().allMatch(locator -> allowed.contains(locator.type()));
    }

    private enum CommitState {
        READY,
        OPEN,
        COMMITTED,
        ABORTED
    }
}

package restudio.resync.flow.runtime;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

final class RuntimeTestSupport {
    private static final RuntimeAuthority AUTHORITY = new RuntimeAuthority("test-authority");
    private static final ContentHash PLAN = ContentHash.of(CanonicalJson.sha256("runtime-test-plan", Map.of("id", "test")));

    private RuntimeTestSupport() {
    }

    static RuntimeBindingRegistry registry() {
        return registry(enforcingExecutionBoundary());
    }

    static RuntimeBindingRegistry registry(RuntimeExecutionBoundary execution) {
        return registry(execution, false);
    }

    static RuntimeBindingRegistry registry(RuntimeExecutionBoundary execution, boolean approvePolicyRetry) {
        return registry(execution, auditBoundary(), approvePolicyRetry);
    }

    static RuntimeBindingRegistry registry(RuntimeExecutionBoundary execution, RuntimeAuditBoundary audit) {
        return registry(execution, audit, false);
    }

    static RuntimeBindingRegistry registry(RuntimeExecutionBoundary execution, RuntimeSecurityBoundary security) {
        return new RuntimeBindingRegistry(security, auditBoundary(), execution, RuntimeReceiptStore.inMemory(true));
    }

    static RuntimeBindingRegistry registry(
        RuntimeExecutionBoundary execution,
        RuntimeSecurityBoundary security,
        RuntimeReceiptStore receiptStore
    ) {
        return new RuntimeBindingRegistry(security, auditBoundary(), execution, receiptStore);
    }

    static RuntimeBindingRegistry registry(
        RuntimeExecutionBoundary execution,
        RuntimeAuditBoundary audit,
        boolean approvePolicyRetry
    ) {
        RuntimeSecurityBoundary security = new RuntimeSecurityBoundary() {
            @Override
            public boolean authorize(RuntimeAuthority authority, ContractRef<CapabilityId> capability) {
                return AUTHORITY.equals(authority);
            }

            @Override
            public boolean confirm(RuntimeAuthority authority, RuntimeBindingKey binding, RuntimeSemantics.Confirmation confirmation) {
                return AUTHORITY.equals(authority);
            }

            @Override
            public boolean approvePolicyRetry(
                RuntimeAuthority authority,
                RuntimeBindingKey binding,
                RuntimeFailure failure,
                int attempt
            ) {
                return approvePolicyRetry && AUTHORITY.equals(authority) && attempt == 0;
            }
        };
        return new RuntimeBindingRegistry(security, audit, execution, RuntimeReceiptStore.inMemory(true));
    }

    private static RuntimeAuditBoundary auditBoundary() {
        return new RuntimeAuditBoundary() {
            @Override
            public boolean available(RuntimeSemantics.Audit policy) {
                return true;
            }

            @Override
            public void record(RuntimeLeaseInput.AuditEvent event) {
            }
        };
    }

    static RuntimeExecutionBoundary enforcingExecutionBoundary() {
        return new RuntimeExecutionBoundary() {
            @Override
            public boolean supports(RuntimeExecutionContext context) {
                return true;
            }

            @Override
            public CompletionStage<RuntimeResult> execute(
                RuntimeExecutionContext context,
                RuntimeInvocation invocation,
                RuntimeOperationHandler handler
            ) {
                context.establishThread(context.thread());
                context.establishEffect(context.effect());
                invocation.inputs().values().stream()
                    .map(value -> value.locator())
                    .filter(Objects::nonNull)
                    .forEach(locator -> {
                        if (context.resourceReads().contains(locator.type())) {
                            context.establishRead(locator);
                        }
                        if (context.resourceWrites().contains(locator.type())) {
                            context.establishWrite(locator);
                        }
                    });
                if (context.commitBoundary() != RuntimeFailureContract.CommitBoundary.NO_MUTATION) {
                    context.beginCommit();
                    context.completeCommit();
                }
                return handler.execute(invocation);
            }

            @Override
            public boolean approveSafeRetry(
                RuntimeExecutionContext context,
                RuntimeFailure failure,
                int attempt
            ) {
                return attempt == 0;
            }
        };
    }

    static RuntimeExecutionBoundary nonEnforcingExecutionBoundary() {
        return new RuntimeExecutionBoundary() {
            @Override
            public boolean supports(RuntimeExecutionContext context) {
                return true;
            }

            @Override
            public CompletionStage<RuntimeResult> execute(
                RuntimeExecutionContext context,
                RuntimeInvocation invocation,
                RuntimeOperationHandler handler
            ) {
                return handler.execute(invocation);
            }

            @Override
            public boolean approveSafeRetry(
                RuntimeExecutionContext context,
                RuntimeFailure failure,
                int attempt
            ) {
                return false;
            }
        };
    }

    static RuntimeLeaseInput input(RuntimeLeaseInput.BindingRequirement requirement) {
        return RuntimeLeaseInput.plan(List.of(requirement), PLAN, AUTHORITY);
    }
}

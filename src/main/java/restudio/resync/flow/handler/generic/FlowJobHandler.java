package restudio.resync.flow.handler.generic;

import restudio.flow.data.FlowJobReference;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowOperationResult;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.jobs.FlowJobRegistry;

import java.util.Map;
import java.util.Set;

public final class FlowJobHandler implements NodeHandler {
    private static final Set<String> OPERATIONS = Set.of("job_status", "job_cancel");
    private final FlowJobRegistry jobs;

    public FlowJobHandler(FlowJobRegistry jobs) {
        if (jobs == null) {
            throw new IllegalArgumentException("Flow job registry is required");
        }
        this.jobs = jobs;
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("FlowJobHandler", this);
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        if (!OPERATIONS.contains(operation)) {
            throw new IllegalArgumentException("Unknown Flow job operation: " + operation);
        }
        switch (operation) {
            case "job_status" -> status(ctx, node);
            case "job_cancel" -> cancel(ctx, node);
            default -> throw new IllegalArgumentException("Unknown Flow job operation: " + operation);
        }
    }

    @Override
    public Set<String> getSupportedOperations() {
        return OPERATIONS;
    }

    private void status(FlowContext ctx, FlowNode node) {
        FlowJobReference<?> reference = reference(ctx, node);
        if (reference == null) {
            fail(ctx, node, "JOB_NOT_FOUND", "Job Not Found", Map.of());
            return;
        }
        FlowJobReference.Snapshot<?> snapshot = reference.snapshot();
        ctx.setOutput(node, "job", reference);
        ctx.setOutput(node, "state", snapshot.state().name());
        ctx.setOutput(node, "progress", snapshot.progress());
        ctx.setOutput(node, "metadata", snapshot.metadata());
        ctx.setOutput(node, "outcome", snapshot.outcome());
        ctx.setOutput(node, "completed", terminal(snapshot.state()));
        ctx.setOutput(node, "succeeded", snapshot.state() == FlowJobReference.State.SUCCEEDED);
        ctx.setOutput(node, "cancelled", snapshot.state() == FlowJobReference.State.CANCELLED);
        ctx.setOutput(node, "success", true);
        ctx.setOutput(node, "error_code", "");
        ctx.setOutput(node, "message", "Job Found");
        ctx.triggerOutput("flow");
    }

    private void cancel(FlowContext ctx, FlowNode node) {
        FlowJobReference<?> reference = reference(ctx, node);
        if (reference == null) {
            fail(ctx, node, "JOB_NOT_FOUND", "Job Not Found", Map.of());
            return;
        }
        boolean cancelled = jobs.cancel(reference);
        if (!cancelled) {
            fail(ctx, node, "JOB_ALREADY_TERMINAL", "Job Is Already Complete", Map.of("jobId", reference.getId(), "state", reference.getState().name()));
            return;
        }
        FlowOperationResult<Boolean> result = FlowOperationResult.success(true);
        ctx.setOutput(node, "job", reference);
        ctx.setOutput(node, "result", result);
        ctx.setOutput(node, "success", true);
        ctx.setOutput(node, "error_code", "");
        ctx.setOutput(node, "message", "Job Cancelled");
        ctx.triggerOutput("flow");
    }

    private FlowJobReference<?> reference(FlowContext ctx, FlowNode node) {
        Object input = ctx.getInputValue(node, "job");
        if (input instanceof FlowJobReference<?> reference) {
            FlowJobReference<?> registered = jobs.get(reference.getId());
            return registered != null ? registered : reference;
        }
        String jobId = ctx.getInputValue(node, "job_id", String.class, "");
        return jobs.get(jobId);
    }

    private void fail(FlowContext ctx, FlowNode node, String code, String message, Map<String, Object> details) {
        ctx.setOutput(node, "result", FlowOperationResult.failure(code, message, details));
        ctx.setOutput(node, "success", false);
        ctx.setOutput(node, "error_code", code);
        ctx.setOutput(node, "message", message);
        ctx.triggerOutput("failed");
    }

    private boolean terminal(FlowJobReference.State state) {
        return state == FlowJobReference.State.SUCCEEDED || state == FlowJobReference.State.FAILED || state == FlowJobReference.State.CANCELLED;
    }
}

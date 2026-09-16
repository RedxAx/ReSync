package restudio.resync.modules.flow;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class FlowResourceMutationLease implements AutoCloseable {
    private final FlowResourceMutationAdmission admission;
    private final FlowResourceMutationAdmission.Claim claim;
    private final AtomicInteger references = new AtomicInteger(1);
    private final AtomicBoolean released = new AtomicBoolean();

    FlowResourceMutationLease(FlowResourceMutationAdmission admission, FlowResourceMutationAdmission.Claim claim) {
        this.admission = admission;
        this.claim = claim;
    }

    public String mutationId() {
        return claim.mutationId();
    }

    public List<FlowResourceKey> keys() {
        return claim.keys();
    }

    public List<FlowResourceKey> resourceKeys() {
        return keys();
    }

    public String id() {
        return mutationId();
    }

    public boolean owns(FlowResourceKey key) {
        return key != null && admission.owns(this, key);
    }

    public boolean owns(String typeId, String resourceId) {
        try {
            return owns(new FlowResourceKey(typeId, resourceId));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public boolean ownsAll(Iterable<FlowResourceKey> keys) {
        if (keys == null) {
            return false;
        }
        for (FlowResourceKey key : keys) {
            if (!owns(key)) {
                return false;
            }
        }
        return true;
    }

    public boolean isClosed() {
        return released.get();
    }

    public boolean closed() {
        return isClosed();
    }

    public boolean active() {
        return !isClosed();
    }

    public void release() {
        close();
    }

    public DeferredCompletion defer(Runnable completion) {
        Objects.requireNonNull(completion, "completion");
        if (!retain()) {
            throw new IllegalStateException("Resource mutation lease is closed");
        }
        return new DeferredCompletion(this, completion);
    }

    public void close() {
        for (;;) {
            int current = references.get();
            if (current == 0) {
                return;
            }
            if (!references.compareAndSet(current, current - 1)) {
                continue;
            }
            if (current == 1 && released.compareAndSet(false, true)) {
                admission.release(claim);
            }
            return;
        }
    }

    boolean retain() {
        for (;;) {
            int current = references.get();
            if (current == 0 || released.get()) {
                return false;
            }
            if (references.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    FlowResourceMutationAdmission admission() {
        return admission;
    }

    FlowResourceMutationAdmission.Claim claim() {
        return claim;
    }

    public static final class DeferredCompletion implements AutoCloseable {
        private final FlowResourceMutationLease lease;
        private final Runnable completion;
        private final AtomicBoolean settled = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private DeferredCompletion(FlowResourceMutationLease lease, Runnable completion) {
            this.lease = lease;
            this.completion = completion;
        }

        public void run() {
            if (!settled.compareAndSet(false, true)) {
                throw new IllegalStateException("Deferred resource completion has already been settled");
            }
            try {
                completion.run();
            } finally {
                lease.close();
            }
        }

        public boolean cancel() {
            if (!settled.compareAndSet(false, true)) {
                return false;
            }
            cancelled.set(true);
            lease.close();
            return true;
        }

        public boolean isPending() {
            return !settled.get();
        }

        public boolean completed() {
            return settled.get() && !cancelled.get();
        }

        public boolean cancelled() {
            return cancelled.get();
        }

        @Override
        public void close() {
            cancel();
        }
    }
}

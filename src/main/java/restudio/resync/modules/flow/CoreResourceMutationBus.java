package restudio.resync.modules.flow;

import restudio.resync.Log;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.ResourceActivationState;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class CoreResourceMutationBus {
    private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final Map<ServerResourceLocator, TransitionIdentity> highWater = new HashMap<>();
    private final Map<UUID, ServerResourceLocator> currentMutations = new HashMap<>();
    private final Set<ServerResourceLocator> publishing = new HashSet<>();

    public CoreResourceMutationRegistration addListener(Consumer<CoreResourceMutationTransition> listener) {
        Subscription subscription = new Subscription(Objects.requireNonNull(listener, "Core resource mutation listener is required"));
        subscriptions.add(subscription);
        return () -> subscriptions.remove(subscription);
    }

    public Publication publish(CoreResourceMutationTransition transition) {
        Objects.requireNonNull(transition, "Core resource mutation transition is required");
        TransitionIdentity identity = TransitionIdentity.from(transition.checkpoint());
        synchronized (highWater) {
            TransitionIdentity current = highWater.get(transition.locator());
            if (current != null && transition.revision() < current.revision()) {
                return Publication.STALE;
            }
            if (current != null && transition.revision() == current.revision()) {
                if (current.equals(identity)) {
                    return Publication.REPLAY;
                }
                throw new IllegalStateException("Core resource mutation transition revision collision: "
                    + transition.locator().canonicalText() + "@" + transition.revision());
            }
            ServerResourceLocator mutationOwner = currentMutations.get(transition.mutationId());
            if (mutationOwner != null) {
                throw new IllegalStateException("Core resource mutation ID was reused for a different transition: "
                    + transition.mutationId() + ":" + mutationOwner.canonicalText());
            }
            if (current != null) {
                currentMutations.remove(current.mutationId(), transition.locator());
            }
            if (subscriptions.isEmpty()) {
                return Publication.FAILED;
            }
            if (!publishing.add(transition.locator())) {
                throw new IllegalStateException("Core resource mutation transition publication is already active: "
                    + transition.locator().canonicalText());
            }
            boolean failed = false;
            try {
                for (Subscription subscription : subscriptions) {
                    try {
                        subscription.listener().accept(transition);
                    } catch (RuntimeException exception) {
                        String message = exception.getMessage() != null && !exception.getMessage().isBlank()
                            ? exception.getMessage() : exception.getClass().getSimpleName();
                        Log.warn("Publish Core resource mutation transition failed: " + message);
                        failed = true;
                    }
                }
            } finally {
                publishing.remove(transition.locator());
            }
            if (failed) {
                return Publication.FAILED;
            }
            highWater.put(transition.locator(), identity);
            currentMutations.put(transition.mutationId(), transition.locator());
        }
        return Publication.PUBLISHED;
    }

    public void restoreHighWater(CoreResourceMutationCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "Core resource mutation checkpoint is required");
        TransitionIdentity identity = TransitionIdentity.from(checkpoint);
        synchronized (highWater) {
            TransitionIdentity current = highWater.get(checkpoint.locator());
            if (current != null && checkpoint.revision() < current.revision()) {
                return;
            }
            if (current != null && checkpoint.revision() == current.revision()) {
                if (current.equals(identity)) {
                    return;
                }
                throw new IllegalStateException("Core resource mutation checkpoint revision collision: "
                    + checkpoint.locator().canonicalText() + "@" + checkpoint.revision());
            }
            ServerResourceLocator mutationOwner = currentMutations.get(checkpoint.mutationId());
            if (mutationOwner != null) {
                throw new IllegalStateException("Core resource mutation ID was reused for a different checkpoint: "
                    + checkpoint.mutationId() + ":" + mutationOwner.canonicalText());
            }
            if (current != null) {
                currentMutations.remove(current.mutationId(), checkpoint.locator());
            }
            highWater.put(checkpoint.locator(), identity);
            currentMutations.put(checkpoint.mutationId(), checkpoint.locator());
        }
    }

    public enum Publication {
        PUBLISHED,
        REPLAY,
        STALE,
        FAILED
    }

    private record Subscription(Consumer<CoreResourceMutationTransition> listener) {
    }

    private record TransitionIdentity(long revision, UUID mutationId, boolean deleted,
                                      ResourceActivationState activationState, String author,
                                      ContentHash canonicalEnvelopeHash) {
        private static TransitionIdentity from(CoreResourceMutationCheckpoint checkpoint) {
            return new TransitionIdentity(checkpoint.revision(), checkpoint.mutationId(), checkpoint.deleted(),
                checkpoint.activationState(), checkpoint.author(), checkpoint.canonicalEnvelopeHash());
        }
    }
}

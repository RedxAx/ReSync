package restudio.resync.upgrade;

import restudio.resync.migration.Snapshot;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

public final class InvocationBoundUpgradePlanner implements UpgradePlanner {
    private final UpgradePlanner delegate;
    private final String invocationHash;

    public InvocationBoundUpgradePlanner(UpgradePlanner delegate, String invocationHash) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.invocationHash = requireDigest(invocationHash);
    }

    @Override
    public UpgradeProposal plan(Snapshot sourceSnapshot, UpgradeSourceWindow sourceWindow) throws IOException {
        UpgradeProposal proposal = delegate.plan(sourceSnapshot, sourceWindow);
        if (!proposal.plan().invocationHash().isEmpty() && !proposal.plan().invocationHash().equals(invocationHash)) {
            throw new IllegalStateException("Upgrade Planner Returned A Different Invocation Binding");
        }
        return new UpgradeProposal(proposal.plan().withInvocationHash(invocationHash), proposal.quarantineReport(), proposal.diagnostics());
    }

    public String invocationHash() {
        return invocationHash;
    }

    private static String requireDigest(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("invocationHash Must Be A SHA-256 Digest");
        }
        return value.toLowerCase(Locale.ROOT);
    }
}

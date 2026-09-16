package restudio.resync.upgrade;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.migration.QuarantineRecord;
import restudio.resync.migration.Snapshot;
import restudio.resync.upgrade.adapter.OfflineUpgradeAdapterRegistry;
import restudio.resync.upgrade.adapter.OfflineUpgradeSnapshotAdapter;
import restudio.resync.upgrade.adapter.OfflineUpgradeSnapshotInput;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class SnapshotAdapterBinding {
    private static final String DOMAIN = "resync.offline-upgrader.snapshot-adapter-binding";

    private SnapshotAdapterBinding() {
    }

    public static Capture capture(Snapshot snapshot, OfflineUpgradeAdapterRegistry registry) throws IOException {
        Snapshot source = Objects.requireNonNull(snapshot, "snapshot");
        OfflineUpgradeAdapterRegistry adapters = Objects.requireNonNull(registry, "registry");
        ImmutableSnapshotAdapter.View view = ImmutableSnapshotAdapter.adapt(source);
        OfflineUpgradeSnapshotInput input = new OfflineUpgradeSnapshotInput(source.root(), view);
        List<OfflineUpgradeSnapshotAdapter> values = adapters.snapshotAdapters();
        List<Invocation> invocations = new ArrayList<>();
        List<Invocation> claimed = new ArrayList<>();
        for (OfflineUpgradeSnapshotAdapter adapter : values) {
            try {
                boolean claims = adapter.claims(input);
                Invocation invocation = Invocation.claimResult(adapter, claims, null, null);
                invocations.add(invocation);
                if (claims) {
                    claimed.add(invocation);
                }
            } catch (RuntimeException exception) {
                invocations.add(Invocation.failure(adapter, false, "claims", exception));
            }
        }
        if (claimed.size() == 1) {
            Invocation selected = claimed.getFirst();
            OfflineUpgradeSnapshotAdapter adapter = selected.adapter();
            try {
                OfflineUpgradeSnapshotAdapter.SnapshotTransform transform = Objects.requireNonNull(
                    adapter.transform(input), "snapshot adapter transform result");
                invocations.set(invocations.indexOf(selected), Invocation.claimResult(adapter, true, transform, null));
            } catch (IOException | RuntimeException exception) {
                invocations.set(invocations.indexOf(selected), Invocation.failure(adapter, true, "transform", exception));
            }
        }
        return new Capture(source, view, input, invocations);
    }

    public record Capture(Snapshot snapshot, ImmutableSnapshotAdapter.View view,
                          OfflineUpgradeSnapshotInput input, List<Invocation> invocations) {
        public Capture {
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            view = Objects.requireNonNull(view, "view");
            input = Objects.requireNonNull(input, "input");
            invocations = List.copyOf(invocations == null ? List.of() : invocations);
        }

        public String canonicalText() {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("format", 2);
            canonical.put("snapshotId", snapshot.metadata().snapshotId());
            canonical.put("manifestHash", view.manifestHash());
            canonical.put("adapters", invocations.stream()
                .sorted(Comparator.comparing((Invocation value) -> value.adapter().wireId())
                    .thenComparing(value -> value.adapter().owner())
                    .thenComparing(value -> value.adapter().getClass().getName()))
                .map(value -> value.canonical(view))
                .toList());
            return CanonicalJson.canonicalize(canonical);
        }

        public String bindingHash() {
            return CanonicalJson.sha256(DOMAIN, canonicalText());
        }

        public Invocation invocation(String wireId) {
            return invocations.stream().filter(value -> value.adapter().wireId().equals(wireId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                    "Snapshot Adapter Invocation Is Missing: " + wireId));
        }
    }

    public record Invocation(OfflineUpgradeSnapshotAdapter adapter, boolean claims,
                              OfflineUpgradeSnapshotAdapter.SnapshotTransform transform,
                              String failurePhase, String failureType, String failureMessage) {
        public Invocation {
            adapter = Objects.requireNonNull(adapter, "adapter");
            transform = transform;
            failurePhase = failurePhase == null ? "" : failurePhase;
            failureType = failureType == null ? "" : failureType;
            failureMessage = failureMessage == null ? "" : failureMessage;
            if (!failurePhase.isEmpty() && failureType.isEmpty()) {
                throw new IllegalArgumentException("Snapshot Adapter Failure Type Is Required");
            }
            if (!failurePhase.isEmpty() && failureMessage.isBlank()) {
                throw new IllegalArgumentException("Snapshot Adapter Failure Message Is Required");
            }
            if (!claims && transform != null) {
                throw new IllegalArgumentException("Unclaimed Snapshot Adapter Cannot Have A Transform");
            }
        }

        private static Invocation claimResult(OfflineUpgradeSnapshotAdapter adapter, boolean claims,
                                               OfflineUpgradeSnapshotAdapter.SnapshotTransform transform,
                                               Throwable ignored) {
            return new Invocation(adapter, claims, transform, "", "", "");
        }

        private static Invocation failure(OfflineUpgradeSnapshotAdapter adapter, boolean claims, String phase,
                                           Throwable exception) {
            Objects.requireNonNull(exception, "exception");
            String message = exception.getMessage();
            return new Invocation(adapter, claims, null, phase,
                exception.getClass().getName(), message == null || message.isBlank()
                    ? exception.getClass().getSimpleName() : message);
        }

        private Map<String, Object> canonical(ImmutableSnapshotAdapter.View view) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("wireId", adapter.wireId());
            result.put("owner", adapter.owner());
            result.put("implementation", adapter.getClass().getName());
            result.put("claims", claims);
            result.put("failurePhase", failurePhase);
            result.put("failureType", failureType);
            result.put("failureMessage", failureMessage);
            result.put("transform", transform == null ? null : transformCanonical(view, adapter, transform));
            return result;
        }
    }

    private static Map<String, Object> transformCanonical(ImmutableSnapshotAdapter.View view,
                                                          OfflineUpgradeSnapshotAdapter adapter,
                                                          OfflineUpgradeSnapshotAdapter.SnapshotTransform transform) {
        Map<String, ImmutableSnapshotAdapter.Entry> entries = view.entries().stream()
            .collect(Collectors.toUnmodifiableMap(ImmutableSnapshotAdapter.Entry::relativePath, value -> value));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", 1);
        result.put("claimedPaths", transform.claimedPaths().stream().sorted().toList());
        List<Map<String, Object>> files = new ArrayList<>();
        transform.files().stream().sorted(Comparator.comparing(OfflineUpgradeSnapshotAdapter.FileTransform::sourcePath)
            .thenComparing(OfflineUpgradeSnapshotAdapter.FileTransform::targetPath))
            .forEach(file -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("sourcePath", file.sourcePath());
                value.put("targetPath", file.targetPath());
                value.put("operationType", file.operationType() == null ? null : file.operationType().wireName());
                ImmutableSnapshotAdapter.Entry source = entries.get(file.sourcePath());
                ImmutableSnapshotAdapter.Entry target = entries.get(file.targetPath());
                value.put("sourceOwner", source == null ? null : source.owner());
                value.put("sourceHash", source == null ? null : source.sha256());
                value.put("targetOwner", target == null ? adapter.owner() : target.owner());
                value.put("targetHash", target == null ? null : target.sha256());
                value.put("bytesHash", sha256(file.bytes()));
                files.add(value);
            });
        result.put("files", files);
        result.put("quarantines", transform.quarantines().stream()
            .sorted(Comparator.comparing(QuarantineRecord::recordId))
            .map(SnapshotAdapterBinding::quarantineCanonical)
            .toList());
        return result;
    }

    private static Map<String, Object> quarantineCanonical(QuarantineRecord record) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recordId", record.recordId());
        result.put("code", record.code());
        result.put("sourceLocation", record.sourceLocation());
        result.put("reason", record.reason());
        result.put("affectedReferences", record.affectedReferences());
        result.put("suggestedAction", record.suggestedAction());
        result.put("sourceHash", record.sourceHash());
        return result;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 Is Unavailable", exception);
        }
    }
}

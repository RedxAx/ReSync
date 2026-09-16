package restudio.resync.server;

import restudio.resync.migration.PersistenceExternalInput;
import restudio.resync.migration.PersistenceParticipant;
import restudio.resync.migration.PersistenceParticipantClassification;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;
import restudio.resync.migration.PersistenceOwnershipProvider;
import restudio.resync.migration.PersistenceParticipantRegistry;
import restudio.resync.migration.PersistenceRootReadiness;
import restudio.resync.migration.RebindablePersistenceParticipant;
import restudio.resync.migration.ReSyncPersistenceCoordinator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ReSyncPersistenceTopology {
    private ReSyncPersistenceTopology() {
    }

    public static Registration register(ReSyncPersistenceCoordinator coordinator, Path dataRoot, Collection<Binding> bindings) throws IOException {
        return register(coordinator, dataRoot, bindings, List.of());
    }

    public static Registration register(ReSyncPersistenceCoordinator coordinator, Path dataRoot, Collection<Binding> bindings,
                                        Collection<PersistenceRootReadiness.UncoveredWriter> uncoveredWriters) throws IOException {
        Objects.requireNonNull(coordinator, "coordinator");
        Path root = requireDirectory(dataRoot, "dataRoot");
        List<PersistenceExternalInput.Input> externalInputs = PersistenceExternalInput.forDataRoot(coordinator.dataRoot());
        coordinator.registerExternalInputs(externalInputs);
        List<PersistenceRootReadiness.UncoveredWriter> inventory = uncoveredWriters == null
            ? List.of()
            : uncoveredWriters.stream().filter(Objects::nonNull).toList();
        List<Binding> candidates = bindings == null
            ? List.of()
            : bindings.stream().filter(Objects::nonNull).sorted(Comparator.comparing(Binding::owner)).toList();
        Set<String> owners = new HashSet<>();
        List<Binding> validated = new ArrayList<>();
        List<Binding> available = new ArrayList<>();
        List<Binding> unavailable = new ArrayList<>();
        Map<String, String> unavailableReasons = new LinkedHashMap<>();
        Map<String, PersistenceRootReadiness.Owner> readinessOwners = new LinkedHashMap<>();
        Map<String, PersistenceParticipant> effectiveParticipants = new LinkedHashMap<>();
        List<PersistenceParticipant> preRegistered = coordinator.registeredParticipants().stream().toList();
        for (Binding binding : candidates) {
            validateBinding(root, binding, owners, validated);
            validated.add(binding);
            Path participantRoot = requirePath(binding.root(), "participant root");
            if (Files.isSymbolicLink(participantRoot)) {
                throw new IllegalArgumentException("Persistence Participant Root Cannot Be A Symbolic Link: " + participantRoot);
            }
            boolean rootExists = Files.isDirectory(participantRoot, LinkOption.NOFOLLOW_LINKS)
                || Files.isRegularFile(participantRoot, LinkOption.NOFOLLOW_LINKS);
            if (!rootExists && (binding.participant() == null || !binding.participant().rootMayBeAbsent())) {
                if (binding.required()) {
                    markUnavailable(binding, unavailable, unavailableReasons, "Participant Root Is Not An Existing File Or Directory");
                }
                readinessOwners.put(binding.owner(), PersistenceRootReadiness.Owner.unavailable(binding.owner(), participantRoot,
                    binding.required(), binding.classification(), reasonOrFallback(binding, "Participant Root Is Not An Existing File Or Directory")));
                continue;
            }
            PersistenceParticipant participant = binding.participant();
            if (participant == null) {
                participant = findExact(preRegistered, binding.owner(), participantRoot, binding.classification());
            }
            if (participant == null) {
                if (binding.required()) {
                    markUnavailable(binding, unavailable, unavailableReasons, binding.unavailableReason());
                }
                readinessOwners.put(binding.owner(), PersistenceRootReadiness.Owner.unavailable(binding.owner(), participantRoot,
                    binding.required(), binding.classification(), reasonOrFallback(binding, "No Persistence Participant Is Configured")));
                continue;
            }
            if (!binding.owner().equals(participant.owner()) || !participantRoot.equals(requirePath(participant.root(), "participant root"))) {
                throw new IllegalArgumentException("Persistence Participant Identity Does Not Match Binding: " + binding.owner());
            }
            if (binding.classification() != participant.classification()) {
                throw new IllegalArgumentException("Persistence Participant Classification Does Not Match Binding: " + binding.owner());
            }
            PersistenceParticipant registered = findByOwner(preRegistered, binding.owner());
            if (registered != null && registered != participant) {
                String failure = "Binding Does Not Use The Exact Registered Persistence Participant Identity";
                if (binding.required()) {
                    markUnavailable(binding, unavailable, unavailableReasons, failure);
                }
                readinessOwners.put(binding.owner(), PersistenceRootReadiness.Owner.unavailable(binding.owner(), participantRoot,
                    binding.required(), binding.classification(), reasonOrFallback(binding, failure)));
                continue;
            }
            if (!binding.safeNoOp() && !hasExplicitLifecycle(participant)) {
                if (binding.required()) {
                    markUnavailable(binding, unavailable, unavailableReasons, "Participant Lifecycle Does Not Prove Flush, Quiesce, Resume, Rebind, And Health Check");
                }
                readinessOwners.put(binding.owner(), PersistenceRootReadiness.Owner.unavailable(binding.owner(), participantRoot,
                    binding.required(), binding.classification(), reasonOrFallback(binding, "Participant Lifecycle Does Not Prove Flush, Quiesce, Resume, Rebind, And Health Check")));
                continue;
            }
            if (binding.restoreSafe() && !(participant instanceof RebindablePersistenceParticipant)) {
                if (binding.required()) {
                    markUnavailable(binding, unavailable, unavailableReasons, "Participant Does Not Prove Atomic Rebind");
                }
                readinessOwners.put(binding.owner(), PersistenceRootReadiness.Owner.unavailable(binding.owner(), participantRoot,
                    binding.required(), binding.classification(), reasonOrFallback(binding, "Participant Does Not Prove Atomic Rebind")));
                continue;
            }
            if (!binding.unavailableReason().isBlank()) {
                if (binding.classification() == PersistenceParticipantClassification.DERIVED_CACHE && !binding.required()) {
                    available.add(binding);
                    effectiveParticipants.put(binding.owner(), participant);
                    readinessOwners.put(binding.owner(), PersistenceRootReadiness.Owner.unavailable(binding.owner(), participantRoot,
                        false, binding.classification(), binding.unavailableReason()));
                    continue;
                }
                if (binding.required()) {
                    markUnavailable(binding, unavailable, unavailableReasons, binding.unavailableReason());
                }
                readinessOwners.put(binding.owner(), PersistenceRootReadiness.Owner.unavailable(binding.owner(), participantRoot,
                    binding.required(), binding.classification(), binding.unavailableReason()));
                continue;
            }
            available.add(binding);
            effectiveParticipants.put(binding.owner(), participant);
            readinessOwners.put(binding.owner(), PersistenceRootReadiness.Owner.registered(binding.owner(), participantRoot,
                binding.required(), binding.classification()));
        }
        List<PersistenceParticipant> missingParticipants = available.stream()
            .map(binding -> effectiveParticipants.get(binding.owner()))
            .filter(participant -> preRegistered.stream().noneMatch(existing -> existing == participant))
            .toList();
        coordinator.registerAll(missingParticipants);
        List<PersistenceRootReadiness.UncoveredWriter> unresolvedInventory = new ArrayList<>();
        Map<String, String> resolutionFailures = new LinkedHashMap<>();
        PersistenceParticipantRegistry.OwnershipResolution ownershipResolution = null;
        String ownershipResolutionFailure = "";
        if (inventory.stream().anyMatch(PersistenceRootReadiness.UncoveredWriter::local)) {
            try {
                ownershipResolution = coordinator.participants().resolutionForRoot(root);
            } catch (IOException | RuntimeException exception) {
                ownershipResolutionFailure = reason(exception);
            }
        }
        for (PersistenceRootReadiness.UncoveredWriter writer : inventory) {
            if (writer.externalAffected()) {
                unresolvedInventory.add(writer);
                continue;
            }
            if (!resolveLocalWriter(writer, candidates, available, effectiveParticipants,
                coordinator.registeredParticipants(), ownershipResolution, ownershipResolutionFailure, resolutionFailures)) {
                unresolvedInventory.add(writer);
                PersistenceRootReadiness.Owner declaredOwner = readinessOwners.get(writer.id());
                String reason = declaredOwner != null && declaredOwner.state() == PersistenceRootReadiness.State.UNAVAILABLE
                    ? declaredOwner.reason()
                    : resolutionFailures.getOrDefault(writer.id(), writer.reason());
                readinessOwners.put(writer.id(), PersistenceRootReadiness.Owner.unavailable(
                    writer.id(), writer.root(), true, writer.classification(), reason));
                unavailableReasons.put(writer.id(), reason);
            }
        }
        List<PersistenceRootReadiness.UncoveredWriter> unresolvedLocal = unresolvedInventory.stream()
            .filter(PersistenceRootReadiness.UncoveredWriter::local)
            .toList();
        if (!unavailable.isEmpty() || available.isEmpty() || !unresolvedLocal.isEmpty()) {
            return new Registration(false, owners(available), owners(unavailable), unavailableReasons,
                new PersistenceRootReadiness(readinessOwners.values(), unresolvedInventory, externalInputs));
        }
        PersistenceRootReadiness readiness = new PersistenceRootReadiness(
            readinessOwners.values(), unresolvedInventory, externalInputs);
        long convergenceStarted = TemporaryLifecycleDiagnostics.start();
        try {
            boolean proofCapable = coordinator.registeredParticipants().stream()
                .allMatch(RebindablePersistenceParticipant.class::isInstance);
            if (proofCapable) {
                readiness = coordinator.seal(readiness).readiness();
                coordinator.lastConvergenceTiming().ifPresent(timing -> TemporaryLifecycleDiagnostics.event(
                    "persistence_convergence", convergenceStarted,
                    Map.ofEntries(Map.entry("outcome", timing.outcome()), Map.entry("bindingCount", validated.size()),
                        Map.entry("participantCount", coordinator.registeredParticipants().size()),
                        Map.entry("flushMs", timing.flushMillis()), Map.entry("quiesceMs", timing.quiesceMillis()),
                        Map.entry("rebindMs", timing.rebindMillis()),
                        Map.entry("authoritativeChecksMs", timing.authoritativeChecksMillis()),
                        Map.entry("resumeMs", timing.resumeMillis()), Map.entry("derivedChecksMs", timing.derivedChecksMillis()),
                        Map.entry("ownershipValidationMs", timing.ownershipValidationMillis()),
                        Map.entry("activationMs", timing.activationMillis()), Map.entry("convergenceMs", timing.totalMillis()),
                        Map.entry("cpuMs", timing.totalCpuMillis()), Map.entry("phaseCpuTimings", timing.phaseCpuTimingsMillis()),
                        Map.entry("participantTimings", timing.participantTimingsMillis()))));
                coordinator.lastConvergenceTiming().ifPresent(timing -> timing.participantTimingsMillis().forEach((key, elapsedMs) -> {
                    int separator = key.indexOf(':');
                    String operation = separator < 0 ? "unknown" : key.substring(0, separator);
                    String participantId = separator < 0 ? key : key.substring(separator + 1);
                    TemporaryLifecycleDiagnostics.eventElapsed("persistence_participant_timing", elapsedMs,
                        Map.of("participantId", participantId, "operation", operation, "participantElapsedMs", elapsedMs,
                            "elapsedMs", elapsedMs, "cpuMs", timing.participantCpuTimingsMillis().getOrDefault(key, 0L),
                            "outcome", "complete"));
                }));
            } else {
                coordinator.seal();
            }
            return new Registration(true, owners(available), List.of(), Map.of(),
                readiness);
        } catch (IOException | RuntimeException exception) {
            String failureReason = reason(exception);
            String sealOwner = "resync.persistence.seal";
            readinessOwners.put(sealOwner, PersistenceRootReadiness.Owner.unavailable(sealOwner, root, true, failureReason));
            unavailableReasons.put(sealOwner, failureReason);
            return new Registration(false, owners(available), owners(unavailable), unavailableReasons,
                new PersistenceRootReadiness(readinessOwners.values(), unresolvedInventory, externalInputs));
        } finally {
            coordinator.participants().ownershipValidationMetrics().forEach((phase, metrics) ->
                TemporaryLifecycleDiagnostics.eventElapsed("persistence_ownership_scan", metrics.elapsedMillis(),
                    Map.ofEntries(Map.entry("phase", phase), Map.entry("ordinal", metrics.ordinal()),
                        Map.entry("directoryCount", metrics.directoryCount()), Map.entry("fileCount", metrics.fileCount()),
                        Map.entry("bytes", metrics.bytes()), Map.entry("excludedCount", metrics.excludedCount()),
                        Map.entry("witnessHitCount", metrics.witnessHitCount()),
                        Map.entry("ownerResolutionCount", metrics.ownerResolutionCount()),
                        Map.entry("witnessCount", metrics.witnessCount()), Map.entry("cpuMs", metrics.cpuMillis()),
                        Map.entry("outcome", metrics.complete() ? "complete" : "failed"))));
        }
    }

    public static Registration refresh(ReSyncPersistenceCoordinator coordinator, Registration registration) {
        Objects.requireNonNull(coordinator, "coordinator");
        if (registration == null) {
            return null;
        }
        PersistenceRootReadiness readiness = registration.readiness();
        Map<String, PersistenceParticipant> liveParticipants = new LinkedHashMap<>();
        coordinator.registeredParticipants().stream()
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(PersistenceParticipant::owner))
            .forEach(participant -> liveParticipants.put(participant.owner(), participant));
        List<PersistenceRootReadiness.Owner> refreshedOwners = new ArrayList<>();
        for (PersistenceRootReadiness.Owner declared : readiness.owners()) {
            PersistenceParticipant participant = liveParticipants.remove(declared.owner());
            refreshedOwners.add(refreshOwner(declared, participant, registration.sealed()));
        }
        for (PersistenceParticipant participant : liveParticipants.values()) {
            PersistenceParticipantClassification classification = participant.classification();
            PersistenceRootReadiness.Owner declared = PersistenceRootReadiness.Owner.registered(
                participant.owner(), requireParticipantRoot(participant),
                classification != PersistenceParticipantClassification.DERIVED_CACHE, classification);
            refreshedOwners.add(refreshOwner(declared, participant, registration.sealed()));
        }
        PersistenceRootReadiness refreshed = new PersistenceRootReadiness(
            refreshedOwners, readiness.uncoveredWriters(), readiness.externalInputs());
        return new Registration(registration.sealed(), List.of(), List.of(), Map.of(), refreshed);
    }

    public static Binding required(String owner, Path root, PersistenceParticipant participant) {
        return new Binding(owner, root, true, false, false, participant);
    }

    public static Binding optional(String owner, Path root, PersistenceParticipant participant) {
        return new Binding(owner, root, false, false, false, participant);
    }

    public static Binding safeNoOp(String owner, Path root, PersistenceParticipant participant) {
        return new Binding(owner, root, true, true, false, participant);
    }

    public static Binding requiredForRestore(String owner, Path root, PersistenceParticipant participant) {
        return new Binding(owner, root, true, false, true, participant);
    }

    public static Binding derivedCache(String owner, Path root, PersistenceParticipant participant) {
        return new Binding(owner, root, false, false, false, participant, "", PersistenceParticipantClassification.DERIVED_CACHE);
    }

    public static Binding derivedUnavailable(String owner, Path root, String reason) {
        return new Binding(owner, root, false, false, false, null, reason, PersistenceParticipantClassification.DERIVED_CACHE);
    }

    public static Binding derivedUnavailable(String owner, Path root, PersistenceParticipant participant, String reason) {
        return new Binding(owner, root, false, false, false, participant, reason, PersistenceParticipantClassification.DERIVED_CACHE);
    }

    public static Binding unavailable(String owner, Path root, String reason) {
        return new Binding(owner, root, true, false, true, null, reason);
    }

    public static Registration failClosed(Path dataRoot, String reason) {
        return failClosed(dataRoot, reason, List.of());
    }

    public static Registration failClosed(Path dataRoot, String reason,
                                          Collection<PersistenceRootReadiness.UncoveredWriter> uncoveredWriters) {
        Path root = requirePath(dataRoot, "dataRoot");
        String failure = reason == null || reason.isBlank() ? "Persistence topology is unavailable" : reason.trim();
        List<PersistenceRootReadiness.UncoveredWriter> inventory = uncoveredWriters == null
            ? List.of()
            : uncoveredWriters.stream().filter(Objects::nonNull).toList();
        List<PersistenceExternalInput.Input> externalInputs = PersistenceExternalInput.forDataRoot(root);
        return new Registration(false, List.of(), List.of("resync.root"), Map.of("resync.root", failure),
            new PersistenceRootReadiness(List.of(PersistenceRootReadiness.Owner.unavailable(
                "resync.root", root, true, failure)), inventory, externalInputs));
    }

    private static void markUnavailable(Binding binding, Collection<Binding> unavailable, Map<String, String> reasons, String fallbackReason) {
        unavailable.add(binding);
        String reason = binding.unavailableReason();
        reasons.put(binding.owner(), reason == null || reason.isBlank() ? fallbackReason : reason);
    }

    private static String reasonOrFallback(Binding binding, String fallbackReason) {
        String reason = binding.unavailableReason();
        return reason == null || reason.isBlank() ? fallbackReason : reason;
    }

    private static void validateBinding(Path dataRoot, Binding binding, Set<String> owners, Collection<Binding> validated) {
        Objects.requireNonNull(binding, "binding");
        String owner = requireText(binding.owner(), "owner");
        if (!owners.add(owner)) {
            throw new IllegalArgumentException("Persistence Participant Owner Is Ambiguous: " + owner);
        }
        Path root = requirePath(binding.root(), "participant root");
        if (!root.startsWith(dataRoot)) {
            throw new IllegalArgumentException("Persistence Participant Root Must Be Inside ReSync Data Root: " + owner);
        }
        for (Binding existing : validated) {
            if (binding.participant() == null || existing.participant() == null) {
                continue;
            }
            Path existingRoot = requirePath(existing.root(), "participant root");
            if (root.startsWith(existingRoot) || existingRoot.startsWith(root)) {
                throw new IllegalArgumentException("Persistence Participant Root Is Ambiguous: " + owner);
            }
        }
    }

    private static boolean hasExplicitLifecycle(PersistenceParticipant participant) {
        try {
            Class<?> type = participant.getClass();
            return type.getMethod("flush").getDeclaringClass() != PersistenceParticipant.class
                && type.getMethod("quiesce").getDeclaringClass() != PersistenceParticipant.class
                && type.getMethod("resume").getDeclaringClass() != PersistenceParticipant.class
                && type.getMethod("rebind", Path.class).getDeclaringClass() != PersistenceParticipant.class
                && type.getMethod("healthCheck").getDeclaringClass() != PersistenceParticipant.class;
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException("Persistence Participant Lifecycle Contract Is Invalid", exception);
        }
    }

    private static List<String> owners(Collection<Binding> bindings) {
        return bindings.stream().map(Binding::owner).sorted().toList();
    }

    private static Path requireDirectory(Path path, String name) throws IOException {
        Path normalized = requirePath(path, name);
        if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(name + " Must Be An Existing Non-Symbolic-Link Directory");
        }
        return normalized;
    }

    private static Path requirePath(Path path, String name) {
        Objects.requireNonNull(path, name);
        return path.toAbsolutePath().normalize();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " Must Not Be Blank");
        }
        return value.trim();
    }

    private static String reason(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static PersistenceRootReadiness.Owner refreshOwner(PersistenceRootReadiness.Owner declared,
                                                               PersistenceParticipant participant,
                                                               boolean topologyWasSealed) {
        if (!topologyWasSealed && declared.required() && declared.state() == PersistenceRootReadiness.State.UNAVAILABLE) {
            return declared;
        }
        if (participant == null) {
            String reason = declared.reason().isBlank()
                ? "Persistence Participant Is Not Registered"
                : declared.reason();
            return PersistenceRootReadiness.Owner.unavailable(declared.owner(), declared.root(), declared.required(),
                declared.classification(), reason);
        }
        Path participantRoot;
        try {
            participantRoot = requireParticipantRoot(participant);
        } catch (RuntimeException exception) {
            return PersistenceRootReadiness.Owner.unavailable(declared.owner(), declared.root(), declared.required(),
                declared.classification(), reason(exception));
        }
        if (!declared.owner().equals(participant.owner())) {
            return PersistenceRootReadiness.Owner.unavailable(declared.owner(), participantRoot, declared.required(),
                declared.classification(), "Persistence Participant Owner Does Not Match Readiness Owner");
        }
        if (declared.classification() != participant.classification()) {
            return PersistenceRootReadiness.Owner.unavailable(declared.owner(), participantRoot, declared.required(),
                declared.classification(), "Persistence Participant Classification Does Not Match Readiness Owner");
        }
        try {
            participant.healthCheck();
            participant.readinessCheck();
            return PersistenceRootReadiness.Owner.registered(declared.owner(), participantRoot, declared.required(),
                declared.classification());
        } catch (IOException | RuntimeException exception) {
            return PersistenceRootReadiness.Owner.unavailable(declared.owner(), participantRoot, declared.required(),
                declared.classification(), reason(exception));
        }
    }

    private static Path requireParticipantRoot(PersistenceParticipant participant) {
        Path root = requirePath(participant.root(), "participant root");
        if (Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException("Participant Root Cannot Be A Symbolic Link: " + root);
        }
        return root;
    }

    private static boolean resolveLocalWriter(PersistenceRootReadiness.UncoveredWriter writer,
                                              Collection<Binding> candidates,
                                              Collection<Binding> available,
                                              Map<String, PersistenceParticipant> effectiveParticipants,
                                              Collection<PersistenceParticipant> preRegistered,
                                               PersistenceParticipantRegistry.OwnershipResolution ownershipResolution,
                                               String ownershipResolutionFailure,
                                               Map<String, String> resolutionFailures) {
        String resolvedOwner;
        if (ownershipResolution == null) {
            resolutionFailures.put(writer.id(), "No Registered Persistence Participant Owns The Local Writer: "
                + ownershipResolutionFailure);
            return false;
        }
        try {
            resolvedOwner = ownershipResolution.ownerFor(writer.root());
        } catch (IOException | RuntimeException exception) {
            resolutionFailures.put(writer.id(), "No Registered Persistence Participant Owns The Local Writer: "
                + reason(exception));
            return false;
        }
        PersistenceParticipant registered = findByOwner(preRegistered, resolvedOwner);
        Binding exactBinding = candidates.stream()
            .filter(candidate -> candidate.owner().equals(resolvedOwner)
                && candidate.classification() == writer.classification())
            .findFirst()
            .orElse(null);
        if (exactBinding != null) {
            PersistenceParticipant candidate = effectiveParticipants.get(exactBinding.owner());
            if (!available.contains(exactBinding) || candidate == null || registered != candidate) {
                resolutionFailures.put(writer.id(), "Local Writer Requires The Exact Registered Participant Identity, Root, And Classification");
                return false;
            }
            registered = candidate;
        }
        if (registered == null) {
            resolutionFailures.put(writer.id(), "No Registered Persistence Participant Matches The Exact Local Writer Owner, Root, And Classification");
            return false;
        }
        try {
            registered.healthCheck();
            return true;
        } catch (IOException | RuntimeException exception) {
            resolutionFailures.put(writer.id(), "Registered Persistence Participant Health Check Failed: " + reason(exception));
            return false;
        }
    }

    private static PersistenceParticipant findExact(Collection<PersistenceParticipant> participants,
                                                    String owner, Path root,
                                                    PersistenceParticipantClassification classification) {
        Path normalizedRoot = requirePath(root, "participant root");
        return participants.stream()
            .filter(Objects::nonNull)
            .filter(participant -> owner.equals(participant.owner()))
            .filter(participant -> normalizedRoot.equals(requirePath(participant.root(), "participant root")))
            .filter(participant -> participant.classification() == classification)
            .findFirst()
            .orElse(null);
    }

    private static PersistenceParticipant findByOwner(Collection<PersistenceParticipant> participants, String owner) {
        return participants.stream()
            .filter(Objects::nonNull)
            .filter(participant -> owner.equals(participant.owner()))
            .findFirst()
            .orElse(null);
    }

    static boolean ownsPath(Path dataRoot, Binding binding, Path file) {
        Objects.requireNonNull(binding, "binding");
        PersistenceParticipant participant = binding.participant();
        if (participant == null || !binding.unavailableReason().isBlank()) {
            return false;
        }
        Path root = requirePath(dataRoot, "dataRoot");
        Path candidate = requirePath(file, "file");
        if (!candidate.startsWith(root) || candidate.equals(root)) {
            return false;
        }
        try {
            if (participant instanceof PersistenceOwnershipProvider provider) {
                Path participantRoot = requirePath(participant.root(), "participant root");
                PersistenceOwnershipContext context = new PersistenceOwnershipContext(root, participantRoot);
                PersistenceOwnershipIndex index = provider.ownershipIndex(context);
                return index != null && index.owns(context.relativeToSource(candidate));
            }
            return participant.owns(candidate);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public record Binding(String owner, Path root, boolean required, boolean safeNoOp, boolean restoreSafe, PersistenceParticipant participant,
                          String unavailableReason, PersistenceParticipantClassification classification) {
        public Binding(String owner, Path root, boolean required, boolean safeNoOp, PersistenceParticipant participant) {
            this(owner, root, required, safeNoOp, false, participant, "", PersistenceParticipantClassification.AUTHORITATIVE);
        }

        public Binding(String owner, Path root, boolean required, boolean safeNoOp, boolean restoreSafe, PersistenceParticipant participant) {
            this(owner, root, required, safeNoOp, restoreSafe, participant, "", PersistenceParticipantClassification.AUTHORITATIVE);
        }

        public Binding(String owner, Path root, boolean required, boolean safeNoOp, boolean restoreSafe,
                       PersistenceParticipant participant, String unavailableReason) {
            this(owner, root, required, safeNoOp, restoreSafe, participant, unavailableReason,
                PersistenceParticipantClassification.AUTHORITATIVE);
        }

        public Binding {
            owner = requireText(owner, "owner");
            root = requirePath(root, "root");
            unavailableReason = unavailableReason == null ? "" : unavailableReason.trim();
            classification = Objects.requireNonNull(classification, "classification");
            if (participant == null && unavailableReason.isBlank()) {
                unavailableReason = "No Persistence Participant Is Configured";
            }
        }
    }

    public record Registration(boolean sealed, List<String> registeredOwners, List<String> unavailableOwners, Map<String, String> unavailableReasons,
                               PersistenceRootReadiness readiness) {
        public Registration(boolean sealed, List<String> registeredOwners, List<String> unavailableOwners) {
            this(sealed, registeredOwners, unavailableOwners, defaultReasons(unavailableOwners), PersistenceRootReadiness.empty());
        }

        public Registration(boolean sealed, List<String> registeredOwners, List<String> unavailableOwners, Map<String, String> unavailableReasons) {
            this(sealed, registeredOwners, unavailableOwners, unavailableReasons, PersistenceRootReadiness.empty());
        }

        public Registration {
            registeredOwners = List.copyOf(registeredOwners == null ? List.of() : registeredOwners);
            unavailableOwners = List.copyOf(unavailableOwners == null ? List.of() : unavailableOwners);
            unavailableReasons = Collections.unmodifiableMap(new LinkedHashMap<>(unavailableReasons == null ? Map.of() : unavailableReasons));
            readiness = readiness == null ? PersistenceRootReadiness.empty() : readiness;
            if (!readiness.owners().isEmpty()) {
                registeredOwners = readiness.owners().stream()
                    .filter(owner -> owner.state() == PersistenceRootReadiness.State.REGISTERED)
                    .map(PersistenceRootReadiness.Owner::owner)
                    .toList();
                unavailableOwners = readiness.unavailableOwners().stream()
                    .map(PersistenceRootReadiness.Owner::owner)
                    .toList();
                unavailableReasons = readiness.unavailableReasons();
            }
        }

        public Registration withReadiness(PersistenceRootReadiness readiness) {
            return new Registration(sealed, List.of(), List.of(), Map.of(), readiness);
        }

        private static Map<String, String> defaultReasons(List<String> owners) {
            Map<String, String> reasons = new LinkedHashMap<>();
            if (owners != null) {
                owners.stream().filter(Objects::nonNull).forEach(owner -> reasons.put(owner, "Persistence Participant Is Unavailable"));
            }
            return reasons;
        }
    }
}

package restudio.resync.migration;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class PersistenceParticipantRegistry {
    private static final int MAX_OWNER_WITNESS_ENTRIES = 16_384;
    private static final ThreadMXBean THREAD_CPU = ManagementFactory.getThreadMXBean();
    private static final AtomicLong OWNERSHIP_SCAN_ORDINAL = new AtomicLong();
    private final Map<String, PersistenceParticipant> participants = new LinkedHashMap<>();
    private final Map<String, Long> lifecycleTimingNanos = new LinkedHashMap<>();
    private final Map<String, Long> lifecycleCpuTimingNanos = new LinkedHashMap<>();
    private final Map<String, OwnershipScanMetrics> ownershipValidationMetrics = new LinkedHashMap<>();
    private final Path scopeRoot;
    private final Map<String, PersistenceExternalInput.Input> externalInputs = new LinkedHashMap<>();
    private final AtomicReference<PersistenceShutdownStatus.State> shutdownState =
        new AtomicReference<>(PersistenceShutdownStatus.State.OPEN);
    private final AtomicLong ownershipEpoch = new AtomicLong();
    private Path activeScopeRoot;
    private Map<String, String> verifiedTopologyOwners = Map.of();
    private Set<String> verifiedTopologyExclusions = Set.of();
    private OwnershipResolution validatedOwnershipResolution;
    private Path provisionalPreviousScope;
    private Map<String, String> provisionalPreviousTopologyOwners = Map.of();
    private Set<String> provisionalPreviousTopologyExclusions = Set.of();
    private PersistenceRebindStatus rebindStatus = PersistenceRebindStatus.notAttempted();
    private volatile OwnershipScanMetrics lastOwnershipScanMetrics;
    private Set<String> shutdownQuiescedOwners = Set.of();
    private volatile PersistenceShutdownStatus shutdownStatus = PersistenceShutdownStatus.open();

    public PersistenceParticipantRegistry() {
        this.scopeRoot = null;
    }

    public PersistenceParticipantRegistry(Path scopeRoot) {
        this.scopeRoot = MigrationPaths.requirePath(scopeRoot, "scopeRoot");
    }

    public void register(PersistenceParticipant participant) {
        requireOpenForMutation();
        synchronized (this) {
            requireOpenForMutation();
            RegistrationWitness witness = registrationWitness(participant);
            Path registrationRoot = activeScopeRoot == null ? scopeRoot : activeScopeRoot;
            registerWitnesses(List.of(witness), registrationRoot);
        }
    }

    synchronized void registerAll(Collection<RegistrationWitness> registrations, Path registrationRoot) {
        requireOpenForMutation();
        registerWitnesses(List.copyOf(Objects.requireNonNull(registrations, "registrations")), registrationRoot);
    }

    private void registerWitnesses(Collection<RegistrationWitness> registrations, Path registrationRoot) {
        List<RegistrationWitness> requested = List.copyOf(registrations);
        if (requested.isEmpty()) {
            return;
        }
        Path validationRoot = registrationRoot == null
            ? null
            : MigrationPaths.requirePath(registrationRoot, "registration root");
        List<Path> projectedExternalRoots = validationRoot == null
            ? externalInputs.values().stream().map(PersistenceExternalInput.Input::path).toList()
            : externalRootsFor(validationRoot);
        List<RegistrationWitness> validated = new ArrayList<>();
        for (Map.Entry<String, PersistenceParticipant> existing : participants.entrySet()) {
            RegistrationWitness witness = registrationWitness(existing.getValue());
            if (!existing.getKey().equals(witness.owner())) {
                throw new IllegalStateException("Registered Persistence Participant Owner Changed: " + existing.getKey());
            }
            validated.add(witness);
        }
        Set<String> owners = validated.stream().map(RegistrationWitness::owner).collect(Collectors.toSet());
        for (RegistrationWitness candidate : requested) {
            Objects.requireNonNull(candidate, "registration");
            if (!owners.add(candidate.owner())) {
                throw new IllegalArgumentException("Participant Owner Is Already Registered: " + candidate.owner());
            }
            for (Path externalRoot : projectedExternalRoots) {
                if (rootsOverlap(candidate.root(), externalRoot)
                    || ownsBoundary(candidate.participant(), candidate.customOwnership(), externalRoot)) {
                    throw new IllegalArgumentException("Persistence Participant Overlaps External Input: " + candidate.owner());
                }
            }
            for (RegistrationWitness existing : validated) {
                if (rootsOverlap(candidate.root(), existing.root())
                    || ownsBoundary(candidate.participant(), candidate.customOwnership(), existing.root())
                    || ownsBoundary(existing.participant(), existing.customOwnership(), candidate.root())) {
                    throw new IllegalArgumentException(
                        "Participant Roots Overlap: " + candidate.owner() + " And " + existing.owner());
                }
            }
            validated.add(candidate);
        }
        for (RegistrationWitness candidate : requested) {
            participants.put(candidate.owner(), candidate.participant());
        }
        ownershipEpoch.incrementAndGet();
        validatedOwnershipResolution = null;
    }

    static RegistrationWitness registrationWitness(PersistenceParticipant participant) {
        PersistenceParticipant candidate = Objects.requireNonNull(participant, "participant");
        String owner = MigrationCanonical.requireText(candidate.owner(), "participant owner");
        Path root = MigrationPaths.requirePath(candidate.root(), "participant root");
        PersistenceParticipantClassification classification = Objects.requireNonNull(
            candidate.classification(), "participant classification");
        return new RegistrationWitness(candidate, owner, root, classification, hasCustomOwnership(candidate));
    }

    record RegistrationWitness(PersistenceParticipant participant, String owner, Path root,
                               PersistenceParticipantClassification classification, boolean customOwnership) {
    }

    public synchronized Collection<PersistenceParticipant> participants() {
        return List.copyOf(sortedParticipants());
    }

    public long ownershipEpoch() {
        return ownershipEpoch.get();
    }

    public void registerExternalInputs(Collection<PersistenceExternalInput.Input> inputs) {
        requireOpenForMutation();
        synchronized (this) {
            requireOpenForMutation();
            if (scopeRoot == null) {
                throw new IllegalStateException("A Scope Root Is Required For External Persistence Inputs");
            }
            List<PersistenceExternalInput.Input> validated = PersistenceExternalInput.validate(scopeRoot, inputs);
            for (PersistenceExternalInput.Input input : validated) {
                PersistenceExternalInput.Input existingInput = externalInputs.get(input.id());
                if (existingInput != null && existingInput.equals(input)) {
                    continue;
                }
                if (existingInput != null) {
                    throw new IllegalArgumentException("External Input Is Already Registered: " + input.id());
                }
                for (PersistenceParticipant participant : participants.values()) {
                    Path participantRoot = MigrationPaths.requirePath(participant.root(), "participant root");
                    if (overlapsExternalInput(participant, participantRoot, hasCustomOwnership(participant), input)) {
                        throw new IllegalArgumentException("External Input Overlaps Persistence Participant: " + input.id());
                    }
                }
            }
            boolean mutated = false;
            for (PersistenceExternalInput.Input input : validated) {
                if (!externalInputs.containsKey(input.id())) {
                    externalInputs.put(input.id(), input);
                    mutated = true;
                }
            }
            if (mutated) {
                ownershipEpoch.incrementAndGet();
                validatedOwnershipResolution = null;
            }
        }
    }

    public synchronized Collection<PersistenceExternalInput.Input> externalInputs() {
        return List.copyOf(externalInputs.values());
    }

    public synchronized boolean isExternalPath(Path sourceRoot, Path path) {
        Path root = MigrationPaths.requirePath(sourceRoot, "sourceRoot");
        Path candidate = MigrationPaths.requirePath(path, "path");
        return isExternalPathValidated(candidate, externalRootsFor(root))
            || isVerifiedTopologyExclusion(root, candidate, verifiedTopologyExclusions);
    }

    public synchronized boolean isDerivedCachePath(Path sourceRoot, Path path) {
        Path root = MigrationPaths.requirePath(sourceRoot, "sourceRoot");
        Path candidate = MigrationPaths.requirePath(path, "path");
        return sortedParticipants().stream()
            .filter(participant -> participant.classification() == PersistenceParticipantClassification.DERIVED_CACHE)
            .map(participant -> MigrationPaths.requirePath(participant.root(), "participant root"))
            .anyMatch(participantRoot -> participantRootContains(root, participantRoot, candidate));
    }

    public synchronized void requireRegisteredParticipant(PersistenceParticipant participant) {
        Objects.requireNonNull(participant, "participant");
        String owner = MigrationCanonical.requireText(participant.owner(), "participant owner");
        PersistenceParticipant registered = participants.get(owner);
        if (registered != participant) {
            throw new IllegalStateException("Persistence Participant Is Not The Registered Authority: " + owner);
        }
        Path registeredRoot = MigrationPaths.requirePath(registered.root(), "registered participant root");
        Path candidateRoot = MigrationPaths.requirePath(participant.root(), "participant root");
        if (!registeredRoot.equals(candidateRoot)) {
            throw new IllegalStateException("Registered Persistence Participant Root Changed: " + owner);
        }
    }

    public synchronized OwnershipResolution resolutionForRoot(Path sourceRoot) throws IOException {
        Path root = MigrationPaths.requireDirectory(sourceRoot, "sourceRoot");
        return resolutionForValidatedRoot(root);
    }

    private OwnershipResolution resolutionForValidatedRoot(Path root) throws IOException {
        long epoch = ownershipEpoch.get();
        OwnershipResolution validated = validatedOwnershipResolution;
        if (validated != null && validated.matches(root, epoch)) {
            return validated;
        }
        OwnershipPlan plan = ownershipPlan(root, epoch);
        requireOwnershipEpoch(epoch);
        return new OwnershipResolution(root, plan.externalRoots(), plan.bindings(), plan.verifiedTopologyOwners(),
            plan.verifiedTopologyExclusions(), ownershipEpoch, epoch, this::recordOwnershipScanMetrics);
    }

    public Optional<OwnershipScanMetrics> lastOwnershipScanMetrics() {
        return Optional.ofNullable(lastOwnershipScanMetrics);
    }

    public synchronized Map<String, OwnershipScanMetrics> ownershipValidationMetrics() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(ownershipValidationMetrics));
    }

    private void recordOwnershipScanMetrics(OwnershipScanMetrics metrics) {
        lastOwnershipScanMetrics = metrics;
    }

    public synchronized void validateForRoot(Path sourceRoot) throws IOException {
        Path root = MigrationPaths.requireDirectory(sourceRoot, "sourceRoot");
        validatedOwnershipResolution = null;
        validateOwnershipResolution(root, "validation");
    }

    public synchronized String ownerFor(Path sourceRoot, Path file) throws IOException {
        Path root = MigrationPaths.requireDirectory(sourceRoot, "sourceRoot");
        long epoch = ownershipEpoch.get();
        OwnershipPlan plan = ownershipPlan(root, epoch);
        Path normalizedFile = MigrationPaths.requirePath(file, "file");
        if (!normalizedFile.startsWith(root) || normalizedFile.equals(root)) {
            throw new MigrationException("File Is Outside Snapshot Root: " + normalizedFile);
        }
        List<Path> externalRoots = plan.externalRoots();
        if (isExternalPathValidated(normalizedFile, externalRoots)
            || isVerifiedTopologyExclusion(root, normalizedFile, plan.verifiedTopologyExclusions())) {
            throw new MigrationException("File Is An External Persistence Input: " + normalizedFile);
        }
        String relative = relativePath(root, normalizedFile);
        String owner = plan.verifiedTopologyOwners().isEmpty()
            ? ownerForValidated(normalizedFile, relative, plan.bindings())
            : verifiedTopologyOwner(normalizedFile, relative, plan.verifiedTopologyOwners(), plan.bindings());
        requireOwnershipEpoch(epoch);
        return owner;
    }

    private void requireOwnershipEpoch(long expected) throws IOException {
        if (ownershipEpoch.get() != expected) {
            throw new MigrationException("Persistence Ownership Resolution Became Stale");
        }
    }

    private static String ownerForValidated(Path normalizedFile, String relative,
                                             List<OwnershipBinding> bindings) throws IOException {
        String owner = null;
        for (OwnershipBinding binding : bindings) {
            boolean owns = binding.owns(normalizedFile, relative);
            if (!owns) {
                continue;
            }
            if (owner != null) {
                throw new MigrationException("Persistence File Has Multiple Owners: "
                    + relative);
            }
            owner = binding.participant().owner();
        }
        if (owner != null) {
            return owner;
        }
        throw new MigrationException("No Persistence Participant Owns File: " + relative);
    }

    private static String verifiedTopologyOwner(Path file, String relative, Map<String, String> topologyOwners,
                                                List<OwnershipBinding> bindings) throws IOException {
        String owner = topologyOwners.get(relative);
        if (owner == null) {
            throw new MigrationException("No Persistence Participant Owns File: " + relative);
        }
        boolean registered = bindings.stream().anyMatch(binding -> binding.participant().owner().equals(owner));
        return registered ? owner : ownerForValidated(file, relative, bindings);
    }

    private OwnershipPlan ownershipPlan(Path root, long epoch) throws IOException {
        List<PersistenceParticipant> ordered = sortedParticipants();
        List<Path> externalRoots = externalRootsFor(root);
        Map<String, String> topologyOwners = verifiedTopologyOwners;
        List<OwnershipBinding> bindings = ownershipBindings(root, ordered, !topologyOwners.isEmpty());
        if (topologyOwners.isEmpty()) {
            validateParticipantOverlaps(bindings);
            validateIndexBoundaries(root, bindings, externalRoots);
        }
        requireOwnershipEpoch(epoch);
        return new OwnershipPlan(root, externalRoots, bindings, topologyOwners, verifiedTopologyExclusions);
    }

    private List<OwnershipBinding> ownershipBindings(
        Path sourceRoot, List<PersistenceParticipant> ordered, boolean verifiedTopology) throws IOException {
        List<OwnershipBinding> bindings = new ArrayList<>();
        for (PersistenceParticipant participant : ordered) {
            Path declaredRoot = Objects.requireNonNull(participant.root(), "participant root");
            Path participantRoot = declaredRoot.toAbsolutePath().normalize();
            requireParticipantInsideRoot(sourceRoot, participant, participantRoot);
            if (!verifiedTopology) {
                validateParticipantFilesystem(sourceRoot, participant, participantRoot);
            }
            PersistenceOwnershipIndex index = null;
            if (!verifiedTopology && participant instanceof PersistenceOwnershipProvider provider) {
                PersistenceOwnershipContext context = new PersistenceOwnershipContext(sourceRoot, participantRoot);
                index = provider.ownershipIndex(context);
                if (index == null) {
                    throw new IllegalArgumentException("Persistence Ownership Index Is Required: " + participant.owner());
                }
            }
            bindings.add(new OwnershipBinding(participant, participantRoot, relativeRoot(sourceRoot, participantRoot), index,
                hasCustomOwnership(participant)));
        }
        return List.copyOf(bindings);
    }

    private void validateParticipantFilesystem(Path root, PersistenceParticipant participant,
                                               Path participantRoot) throws IOException {
        Path relative = root.relativize(participantRoot);
        Path current = root;
        for (int index = 0; index < relative.getNameCount() - 1; index++) {
            current = current.resolve(relative.getName(index)).normalize();
            BasicFileAttributes ancestor;
            try {
                ancestor = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException exception) {
                if (participant.rootMayBeAbsent()) {
                    return;
                }
                throw new MigrationException("Participant Root Is Not An Existing File Or Directory: " + participant.owner(), exception);
            }
            if (ancestor.isSymbolicLink()) {
                throw new MigrationException("Symbolic Link Traversal Is Not Allowed: " + current);
            }
            if (!ancestor.isDirectory()) {
                throw new MigrationException("Participant Root Is Not An Existing File Or Directory: " + participant.owner());
            }
        }
        BasicFileAttributes attributes = null;
        try {
            attributes = Files.readAttributes(participantRoot, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException exception) {
            if (!participant.rootMayBeAbsent()) {
                throw new MigrationException("Participant Root Is Not An Existing File Or Directory: " + participant.owner(), exception);
            }
        }
        if (attributes != null && attributes.isSymbolicLink()) {
            throw new MigrationException("Participant Root Cannot Be A Symbolic Link: " + participant.owner());
        }
        if ((attributes != null && !attributes.isDirectory() && !attributes.isRegularFile())
            || (attributes == null && !participant.rootMayBeAbsent())) {
            throw new MigrationException("Participant Root Is Not An Existing File Or Directory: " + participant.owner());
        }
    }

    private void requireParticipantInsideRoot(Path root, PersistenceParticipant participant,
                                              Path participantRoot) throws IOException {
        if (!participantRoot.startsWith(root)) {
            throw new MigrationException("Participant Root Is Outside Snapshot Root: " + participant.owner());
        }
    }

    private void validateParticipantOverlaps(List<OwnershipBinding> bindings) throws IOException {
        for (int first = 0; first < bindings.size(); first++) {
            OwnershipBinding left = bindings.get(first);
            for (int second = first + 1; second < bindings.size(); second++) {
                OwnershipBinding right = bindings.get(second);
                if (rootsOverlap(left.root(), right.root())
                    || ownsBoundary(left, right.root()) || ownsBoundary(right, left.root())) {
                    throw new MigrationException("Participant Roots Overlap: " + left.participant().owner() + " And "
                        + right.participant().owner());
                }
            }
        }
    }

    private void validateIndexBoundaries(Path root, List<OwnershipBinding> bindings,
                                         List<Path> externalRoots) throws IOException {
        for (OwnershipBinding binding : bindings) {
            Path participantRoot = binding.root();
            for (Path externalRoot : externalRoots) {
                if (participantRoot.equals(externalRoot) || participantRoot.startsWith(externalRoot)
                    || externalRoot.startsWith(participantRoot) || ownsBoundary(binding, externalRoot)) {
                    throw new MigrationException("Persistence Participant Overlaps External Input: "
                        + binding.participant().owner() + " At " + relativeRoot(root, externalRoot));
                }
            }
            if (binding.index() == null) {
                continue;
            }
            for (OwnershipBinding other : bindings) {
                if (binding.participant() == other.participant()) {
                    continue;
                }
                String otherRoot = other.relativeRoot();
                if (indexOverlapsDirectory(binding.index(), otherRoot)) {
                    throw new MigrationException("Indexed Ownership Overlaps Participant Root: "
                        + binding.participant().owner() + " And " + other.participant().owner());
                }
            }
            for (Path externalRoot : externalRoots) {
                String relativeExternal = relativeRoot(root, externalRoot);
                if (indexOverlapsDirectory(binding.index(), relativeExternal)) {
                    throw new MigrationException("Indexed Ownership Overlaps External Input: "
                        + binding.participant().owner() + " At " + relativeExternal);
                }
            }
        }
        for (int first = 0; first < bindings.size(); first++) {
            PersistenceOwnershipIndex left = bindings.get(first).index();
            if (left == null) {
                continue;
            }
            for (int second = first + 1; second < bindings.size(); second++) {
                PersistenceOwnershipIndex right = bindings.get(second).index();
                if (right != null && indexesOverlap(left, right)) {
                    throw new MigrationException("Indexed Ownership Claims Overlap: "
                        + bindings.get(first).participant().owner() + " And "
                        + bindings.get(second).participant().owner());
                }
            }
        }
    }

    private static String relativeRoot(Path root, Path path) throws IOException {
        if (!path.startsWith(root)) {
            throw new MigrationException("Ownership Boundary Is Outside Snapshot Root: " + path);
        }
        return path.equals(root) ? "" : relativePath(root, path);
    }

    private static boolean indexOverlapsDirectory(PersistenceOwnershipIndex index, String directory) {
        for (String exact : index.exactPaths()) {
            if (pathsIntersect(directory, exact)) {
                return true;
            }
        }
        for (String subtree : index.subtrees()) {
            if (pathsIntersect(directory, subtree)) {
                return true;
            }
        }
        if (index.directChildClaims().stream()
            .anyMatch(claim -> directChildOverlapsDirectory(directory, claim))) {
            return true;
        }
        if (index.atomicTempClaims().stream()
            .anyMatch(claim -> claim.overlapsDirectory(directory))) {
            return true;
        }
        return index.rootSiblingClaims().stream()
            .anyMatch(claim -> rootSiblingOverlapsDirectory(claim, directory));
    }

    private static boolean indexesOverlap(PersistenceOwnershipIndex left, PersistenceOwnershipIndex right) {
        for (String exact : left.exactPaths()) {
            if (right.exactPaths().contains(exact)
                || right.subtrees().stream().anyMatch(subtree -> pathWithin(subtree, exact))
                || right.directChildClaims().stream().anyMatch(claim -> claim.matches(exact))
                || right.atomicTempClaims().stream().anyMatch(claim -> claim.matches(exact))
                || right.rootSiblingClaims().stream().anyMatch(claim -> claim.matches(exact))) {
                return true;
            }
        }
        for (String exact : right.exactPaths()) {
            if (left.subtrees().stream().anyMatch(subtree -> pathWithin(subtree, exact))
                || left.directChildClaims().stream().anyMatch(claim -> claim.matches(exact))
                || left.atomicTempClaims().stream().anyMatch(claim -> claim.matches(exact))
                || left.rootSiblingClaims().stream().anyMatch(claim -> claim.matches(exact))) {
                return true;
            }
        }
        for (String leftSubtree : left.subtrees()) {
            for (String rightSubtree : right.subtrees()) {
                if (pathsIntersect(leftSubtree, rightSubtree)) {
                    return true;
                }
            }
            for (PersistenceOwnershipIndex.DirectChildClaim rightClaim : right.directChildClaims()) {
                if (subtreeOverlapsMatcher(leftSubtree, rightClaim)) {
                    return true;
                }
            }
            for (PersistenceOwnershipIndex.RootSiblingClaim rightClaim : right.rootSiblingClaims()) {
                if (rootSiblingOverlapsDirectory(rightClaim, leftSubtree)) {
                    return true;
                }
            }
            for (PersistenceOwnershipIndex.AtomicTempClaim rightClaim : right.atomicTempClaims()) {
                if (rightClaim.overlapsDirectory(leftSubtree)) {
                    return true;
                }
            }
        }
        for (String rightSubtree : right.subtrees()) {
            for (PersistenceOwnershipIndex.DirectChildClaim leftClaim : left.directChildClaims()) {
                if (subtreeOverlapsMatcher(rightSubtree, leftClaim)) {
                    return true;
                }
            }
            for (PersistenceOwnershipIndex.RootSiblingClaim leftClaim : left.rootSiblingClaims()) {
                if (rootSiblingOverlapsDirectory(leftClaim, rightSubtree)) {
                    return true;
                }
            }
            for (PersistenceOwnershipIndex.AtomicTempClaim leftClaim : left.atomicTempClaims()) {
                if (leftClaim.overlapsDirectory(rightSubtree)) {
                    return true;
                }
            }
        }
        for (PersistenceOwnershipIndex.DirectChildClaim leftClaim : left.directChildClaims()) {
            for (PersistenceOwnershipIndex.DirectChildClaim rightClaim : right.directChildClaims()) {
                if (leftClaim.prefix().equals(rightClaim.prefix())
                    && leftClaim.matcher().overlaps(rightClaim.matcher())) {
                    return true;
                }
            }
            for (PersistenceOwnershipIndex.RootSiblingClaim rightClaim : right.rootSiblingClaims()) {
                if (rootSiblingOverlapsDirectChild(rightClaim, leftClaim)) {
                    return true;
                }
            }
            for (PersistenceOwnershipIndex.AtomicTempClaim rightClaim : right.atomicTempClaims()) {
                if (rightClaim.overlaps(leftClaim)) {
                    return true;
                }
            }
        }
        for (PersistenceOwnershipIndex.AtomicTempClaim leftClaim : left.atomicTempClaims()) {
            for (PersistenceOwnershipIndex.DirectChildClaim rightClaim : right.directChildClaims()) {
                if (leftClaim.overlaps(rightClaim)) {
                    return true;
                }
            }
            for (PersistenceOwnershipIndex.AtomicTempClaim rightClaim : right.atomicTempClaims()) {
                if (leftClaim.overlaps(rightClaim)) {
                    return true;
                }
            }
            for (PersistenceOwnershipIndex.RootSiblingClaim rightClaim : right.rootSiblingClaims()) {
                if (leftClaim.overlaps(rightClaim)) {
                    return true;
                }
            }
        }
        for (PersistenceOwnershipIndex.RootSiblingClaim leftClaim : left.rootSiblingClaims()) {
            for (PersistenceOwnershipIndex.DirectChildClaim rightClaim : right.directChildClaims()) {
                if (rootSiblingOverlapsDirectChild(leftClaim, rightClaim)) {
                    return true;
                }
            }
            for (PersistenceOwnershipIndex.AtomicTempClaim rightClaim : right.atomicTempClaims()) {
                if (rightClaim.overlaps(leftClaim)) {
                    return true;
                }
            }
            for (PersistenceOwnershipIndex.RootSiblingClaim rightClaim : right.rootSiblingClaims()) {
                if (leftClaim.overlaps(rightClaim)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean pathsIntersect(String first, String second) {
        return pathWithin(first, second) || pathWithin(second, first);
    }

    private static boolean pathWithin(String ancestor, String candidate) {
        return ancestor.isEmpty() || candidate.equals(ancestor) || candidate.startsWith(ancestor + "/");
    }

    private static boolean directChildOverlapsDirectory(String directory,
                                                        PersistenceOwnershipIndex.DirectChildClaim claim) {
        return directory.isEmpty() || pathWithin(directory, claim.prefix()) || claim.matches(directory);
    }

    private static boolean subtreeOverlapsMatcher(String subtree,
                                                  PersistenceOwnershipIndex.DirectChildClaim claim) {
        String prefix = claim.prefix();
        return subtree.isEmpty() || prefix.equals(subtree)
            || (!subtree.isEmpty() && prefix.startsWith(subtree + "/"))
            || claim.matches(subtree);
    }

    private static boolean rootSiblingOverlapsDirectory(PersistenceOwnershipIndex.RootSiblingClaim claim,
                                                        String directory) {
        if (claim.overlapsDirectory(directory)) {
            return true;
        }
        String siblingParent = switch (claim.matcher().kind()) {
            case UUID_SUFFIX, ATOMIC_TEMP -> claim.parent();
            case HASH_JSON -> appendRelative(claim.parent(), claim.matcher().value());
        };
        String prefix = siblingParent.isEmpty() ? "" : siblingParent + "/";
        if (!directory.startsWith(prefix)) {
            return false;
        }
        String remainder = directory.substring(prefix.length());
        int separator = remainder.indexOf('/');
        String siblingName = separator < 0 ? remainder : remainder.substring(0, separator);
        return !siblingName.isEmpty() && claim.matches(appendRelative(siblingParent, siblingName));
    }

    private static boolean rootSiblingOverlapsDirectChild(PersistenceOwnershipIndex.RootSiblingClaim sibling,
                                                          PersistenceOwnershipIndex.DirectChildClaim claim) {
        String claimParent = switch (sibling.matcher().kind()) {
            case UUID_SUFFIX, ATOMIC_TEMP -> sibling.parent();
            case HASH_JSON -> appendRelative(sibling.parent(), sibling.matcher().value());
        };
        if (!claimParent.equals(claim.prefix())) {
            return false;
        }
        return switch (sibling.matcher().kind()) {
            case UUID_SUFFIX -> fixedMatcherOverlaps(claim.matcher(), sibling.matcher().value().length() + 36,
                (position, value) -> uuidCharacterMatches(sibling.matcher().value(), position, value));
            case HASH_JSON -> fixedMatcherOverlaps(claim.matcher(), 69,
                PersistenceParticipantRegistry::hashJsonCharacterMatches);
            case ATOMIC_TEMP -> atomicMatcherOverlaps(claim.matcher(), sibling.rootName(), sibling.matcher().value());
        };
    }

    private static boolean fixedMatcherOverlaps(PersistenceOwnershipIndex.DirectChildMatcher matcher, int length,
                                                FixedCharacterMatcher fixed) {
        return switch (matcher.kind()) {
            case LITERAL -> matcher.value().length() == length
                && fixedMatcherMatches(matcher.value(), 0, length, fixed);
            case PREFIX -> matcher.value().length() <= length
                && fixedMatcherMatches(matcher.value(), 0, length, fixed);
            case SUFFIX -> matcher.value().length() <= length
                && fixedMatcherMatches(matcher.value(), length - matcher.value().length(), length, fixed);
        };
    }

    private static boolean fixedMatcherMatches(String value, int offset, int length, FixedCharacterMatcher fixed) {
        for (int index = 0; index < value.length(); index++) {
            if (!fixed.matches(offset + index, value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean uuidCharacterMatches(String prefix, int position, char value) {
        if (position < prefix.length()) {
            return prefix.charAt(position) == value;
        }
        int uuidPosition = position - prefix.length();
        if (uuidPosition == 8 || uuidPosition == 13 || uuidPosition == 18 || uuidPosition == 23) {
            return value == '-';
        }
        return isLowercaseHex(value);
    }

    private static boolean hashJsonCharacterMatches(int position, char value) {
        if (position < 64) {
            return isLowercaseHex(value);
        }
        return ".json".charAt(position - 64) == value;
    }

    private static boolean atomicMatcherOverlaps(PersistenceOwnershipIndex.DirectChildMatcher matcher,
                                                 String rootName, String suffix) {
        return switch (matcher.kind()) {
            case LITERAL -> atomicLiteralMatches(matcher.value(), rootName, suffix);
            case PREFIX -> atomicPrefixOverlaps(matcher.value(), rootName, suffix);
            case SUFFIX -> atomicSuffixOverlaps(matcher.value(), suffix);
        };
    }

    private static boolean atomicLiteralMatches(String candidate, String rootName, String suffix) {
        if (!candidate.startsWith(rootName) || !candidate.endsWith(suffix)) {
            return false;
        }
        int tokenStart = rootName.length();
        int tokenEnd = candidate.length() - suffix.length();
        return tokenEnd > tokenStart && isSafeToken(candidate, tokenStart, tokenEnd);
    }

    private static boolean atomicPrefixOverlaps(String prefix, String rootName, String suffix) {
        if (!rootName.startsWith(prefix) && !prefix.startsWith(rootName)) {
            return false;
        }
        if (prefix.length() <= rootName.length()) {
            return true;
        }
        String remaining = prefix.substring(rootName.length());
        for (int tokenLength = 1; tokenLength <= remaining.length(); tokenLength++) {
            if (isSafeToken(remaining, 0, tokenLength)
                && suffix.startsWith(remaining.substring(tokenLength))) {
                return true;
            }
        }
        return false;
    }

    private static boolean atomicSuffixOverlaps(String suffixMatcher, String rootSuffix) {
        if (suffixMatcher.length() <= rootSuffix.length()) {
            return rootSuffix.endsWith(suffixMatcher);
        }
        if (!suffixMatcher.endsWith(rootSuffix)) {
            return false;
        }
        String tokenTail = suffixMatcher.substring(0, suffixMatcher.length() - rootSuffix.length());
        return isSafeToken(tokenTail, 0, tokenTail.length());
    }

    private static boolean isSafeToken(String value, int from, int to) {
        if (from >= to) {
            return false;
        }
        for (int index = from; index < to; index++) {
            char character = value.charAt(index);
            if (!(character >= '0' && character <= '9')
                && !(character >= 'a' && character <= 'z')
                && !(character >= 'A' && character <= 'Z')
                && character != '_' && character != '-') {
                return false;
            }
        }
        return true;
    }

    private static boolean isLowercaseHex(char value) {
        return value >= '0' && value <= '9' || value >= 'a' && value <= 'f';
    }

    private static String appendRelative(String parent, String child) {
        return parent.isEmpty() ? child : parent + "/" + child;
    }

    @FunctionalInterface
    private interface FixedCharacterMatcher {
        boolean matches(int position, char value);
    }

    private static String relativePath(Path root, Path file) {
        return MigrationPaths.requireRelative(root.relativize(file).toString().replace(File.separatorChar, '/'));
    }

    public static final class OwnershipResolution {
        private final Path root;
        private final List<Path> externalRoots;
        private final List<OwnershipBinding> bindings;
        private final Map<String, String> verifiedTopologyOwners;
        private final Set<String> verifiedTopologyExclusions;
        private final List<IndexedSubtreeOwner> indexedSubtreeOwners;
        private final List<OwnershipBinding> unindexedCustomBindings;
        private final AtomicLong epochSource;
        private final long epoch;
        private final Consumer<OwnershipScanMetrics> metricsSink;
        private Map<String, String> ownerWitness = Map.of();

        private OwnershipResolution(Path root, List<Path> externalRoots, List<OwnershipBinding> bindings,
                                    Map<String, String> verifiedTopologyOwners, Set<String> verifiedTopologyExclusions,
                                    AtomicLong epochSource, long epoch, Consumer<OwnershipScanMetrics> metricsSink) {
            this.root = root;
            this.externalRoots = List.copyOf(externalRoots);
            this.bindings = List.copyOf(bindings);
            this.verifiedTopologyOwners = Map.copyOf(verifiedTopologyOwners);
            this.verifiedTopologyExclusions = Set.copyOf(verifiedTopologyExclusions);
            this.indexedSubtreeOwners = indexedSubtreeOwners(bindings);
            this.unindexedCustomBindings = bindings.stream()
                .filter(binding -> binding.customOwnership() && binding.index() == null)
                .toList();
            this.epochSource = Objects.requireNonNull(epochSource, "epochSource");
            this.epoch = epoch;
            this.metricsSink = Objects.requireNonNull(metricsSink, "metricsSink");
        }

        public Path root() {
            return root;
        }

        public boolean isExternalPath(Path path) {
            requireCurrent();
            Path candidate = MigrationPaths.requirePath(path, "path");
            boolean external = isExternalPathValidated(candidate, externalRoots)
                || isVerifiedTopologyExclusion(root, candidate, verifiedTopologyExclusions);
            requireCurrent();
            return external;
        }

        public String ownerFor(Path file) throws IOException {
            requireCurrent();
            Path normalizedFile = MigrationPaths.requirePath(file, "file");
            if (!normalizedFile.startsWith(root) || normalizedFile.equals(root)) {
                throw new MigrationException("File Is Outside Snapshot Root: " + normalizedFile);
            }
            if (isExternalPathValidated(normalizedFile, externalRoots)
                || isVerifiedTopologyExclusion(root, normalizedFile, verifiedTopologyExclusions)) {
                throw new MigrationException("File Is An External Persistence Input: " + normalizedFile);
            }
            String relative = relativePath(root, normalizedFile);
            String owner = ownerForResolved(normalizedFile, relative);
            requireCurrent();
            return owner;
        }

        public synchronized void validate() throws IOException {
            long ordinal = OWNERSHIP_SCAN_ORDINAL.incrementAndGet();
            long started = System.nanoTime();
            long cpuStarted = currentThreadCpuNanos();
            long[] counters = new long[6];
            boolean complete = false;
            requireCurrent();
            requireBindingRootsCurrent();
            Map<String, String> previousWitness = ownerWitness;
            Map<String, String> nextWitness = new LinkedHashMap<>();
            try {
                Files.walkFileTree(root, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                        requireCurrent();
                        counters[0]++;
                        if (attributes.isSymbolicLink()) {
                            throw new MigrationException("Symbolic Link Directory Is Not Allowed: " + directory);
                        }
                        if (!directory.equals(root) && isExternalPathValidated(directory, externalRoots)) {
                            counters[3]++;
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        if (!directory.equals(root) && isRetainedAssetHistory(relativePath(root, directory))) {
                            counters[3]++;
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                        requireCurrent();
                        counters[1]++;
                        counters[2] = saturatedAdd(counters[2], Math.max(0L, attributes.size()));
                        if (attributes.isSymbolicLink()) {
                            throw new MigrationException("Symbolic Link File Is Not Allowed: " + file);
                        }
                        if (!attributes.isRegularFile()) {
                            throw new MigrationException("Non-Regular File Is Not Allowed: " + file);
                        }
                        if (isExternalPathValidated(file, externalRoots)) {
                            counters[3]++;
                            return FileVisitResult.CONTINUE;
                        }
                        if (isVerifiedTopologyExclusion(root, file, verifiedTopologyExclusions)) {
                            counters[3]++;
                            return FileVisitResult.CONTINUE;
                        }
                        String relative = relativePath(root, file);
                        String owner = previousWitness.get(relative);
                        if (owner == null) {
                            counters[5]++;
                            owner = ownerForResolved(file, relative);
                        } else {
                            counters[4]++;
                        }
                        if (nextWitness.size() < MAX_OWNER_WITNESS_ENTRIES) {
                            nextWitness.put(relative, owner);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                        requireCurrent();
                        throw new MigrationException("Cannot Inspect Persistence File: " + file, exception);
                    }
                });
                requireCurrent();
                requireBindingRootsCurrent();
                ownerWitness = Map.copyOf(nextWitness);
                complete = true;
            } finally {
                metricsSink.accept(new OwnershipScanMetrics(ordinal, counters[0], counters[1], counters[2], counters[3],
                    counters[4], counters[5], nextWitness.size(), elapsedMillis(started), currentThreadCpuMillis(cpuStarted), complete));
            }
        }

        private String ownerForResolved(Path file, String relative) throws IOException {
            if (!verifiedTopologyOwners.isEmpty()) {
                return verifiedTopologyOwner(file, relative, verifiedTopologyOwners, bindings);
            }
            for (IndexedSubtreeOwner subtree : indexedSubtreeOwners) {
                if (subtree.owns(relative)) {
                    for (OwnershipBinding binding : unindexedCustomBindings) {
                        if (binding.owns(file, relative)) {
                            throw new MigrationException("Persistence File Has Multiple Owners: " + relative);
                        }
                    }
                    return subtree.owner();
                }
            }
            return ownerForValidated(file, relative, bindings);
        }

        private static List<IndexedSubtreeOwner> indexedSubtreeOwners(List<OwnershipBinding> bindings) {
            List<IndexedSubtreeOwner> owners = new ArrayList<>();
            for (OwnershipBinding binding : bindings) {
                if (binding.index() == null) {
                    if (!binding.customOwnership()) {
                        owners.add(new IndexedSubtreeOwner(binding.relativeRoot(), binding.participant().owner()));
                    }
                    continue;
                }
                for (String subtree : binding.index().subtrees()) {
                    owners.add(new IndexedSubtreeOwner(subtree, binding.participant().owner()));
                }
            }
            return owners.stream()
                .sorted(Comparator.comparingInt((IndexedSubtreeOwner value) -> value.subtree().length()).reversed())
                .toList();
        }

        private void requireBindingRootsCurrent() throws IOException {
            for (OwnershipBinding binding : bindings) {
                Path currentRoot = MigrationPaths.requirePath(binding.participant().root(), "participant root");
                if (!binding.root().equals(currentRoot)) {
                    throw new MigrationException("Persistence Participant Root Changed Without Ownership Epoch: "
                        + binding.participant().owner());
                }
            }
        }

        private boolean matches(Path candidateRoot, long candidateEpoch) {
            return root.equals(candidateRoot) && epoch == candidateEpoch && epochSource.get() == epoch;
        }

        private long epoch() {
            return epoch;
        }

        private void requireCurrent() {
            if (epochSource.get() != epoch) {
                throw new IllegalStateException("Persistence Ownership Resolution Is Stale");
            }
        }
    }

    public record OwnershipScanMetrics(long ordinal, long directoryCount, long fileCount, long bytes,
                                       long excludedCount, long witnessHitCount, long ownerResolutionCount,
                                       long witnessCount, long elapsedMillis, long cpuMillis, boolean complete) {
        public OwnershipScanMetrics {
            if (ordinal < 1L || directoryCount < 0L || fileCount < 0L || bytes < 0L || excludedCount < 0L
                || witnessHitCount < 0L || ownerResolutionCount < 0L || witnessCount < 0L || elapsedMillis < 0L
                || cpuMillis < 0L) {
                throw new IllegalArgumentException("Persistence Ownership Scan Metrics Cannot Be Negative");
            }
        }
    }

    private record IndexedSubtreeOwner(String subtree, String owner) {
        private IndexedSubtreeOwner {
            subtree = Objects.requireNonNull(subtree, "subtree");
            owner = MigrationCanonical.requireText(owner, "owner");
        }

        private boolean owns(String relative) {
            return subtree.isEmpty() || relative.equals(subtree) || relative.startsWith(subtree + "/");
        }
    }

    private record OwnershipPlan(Path root, List<Path> externalRoots, List<OwnershipBinding> bindings,
                                 Map<String, String> verifiedTopologyOwners, Set<String> verifiedTopologyExclusions) {
        private OwnershipPlan {
            root = Objects.requireNonNull(root, "root");
            externalRoots = List.copyOf(externalRoots);
            bindings = List.copyOf(bindings);
            verifiedTopologyOwners = Map.copyOf(verifiedTopologyOwners);
            verifiedTopologyExclusions = Set.copyOf(verifiedTopologyExclusions);
        }
    }

    private record OwnershipBinding(PersistenceParticipant participant, Path root, String relativeRoot,
                                    PersistenceOwnershipIndex index, boolean customOwnership) {
        private OwnershipBinding {
            participant = Objects.requireNonNull(participant, "participant");
            root = Objects.requireNonNull(root, "root");
            relativeRoot = Objects.requireNonNull(relativeRoot, "relativeRoot");
        }

        private boolean owns(Path normalizedFile, String relative) {
            if (index != null) {
                return index.owns(relative);
            }
            if (!customOwnership) {
                return normalizedFile.startsWith(root);
            }
            if (participant instanceof ResolvedPersistenceOwnership resolved) {
                return resolved.ownsResolved(normalizedFile);
            }
            return participant.owns(normalizedFile);
        }
    }

    private List<Path> externalRootsFor(Path root) {
        if (scopeRoot == null) {
            return externalInputs.values().stream().map(PersistenceExternalInput.Input::path).toList();
        }
        Path originalRoot = scopeRoot;
        return externalInputs.values().stream()
            .map(input -> root.resolve(originalRoot.relativize(input.path())).normalize())
            .toList();
    }

    private static boolean isExternalPathValidated(Path candidate, List<Path> externalRoots) {
        return externalRoots.stream().anyMatch(inputRoot -> candidate.equals(inputRoot) || candidate.startsWith(inputRoot));
    }

    private static boolean isVerifiedTopologyExclusion(Path root, Path candidate, Set<String> exclusions) {
        return candidate.startsWith(root) && !candidate.equals(root)
            && exclusions.contains(relativePath(root, candidate));
    }

    private static boolean isRetainedAssetHistory(String relative) {
        return relative.equals("assets/.transactions")
            || relative.startsWith("assets/.transactions/")
            || relative.equals("assets/.snapshots")
            || relative.startsWith("assets/.snapshots/");
    }

    public void flushAll() throws IOException {
        flushAll(null);
    }

    public void flushAll(PersistenceParticipantClassification classification) throws IOException {
        requireOpenForLifecycle();
        synchronized (this) {
            requireOpenForLifecycle();
            validateLifecycleDependencies();
            for (PersistenceParticipant participant : classifiedParticipantsOrAll(classification)) {
                long started = System.nanoTime();
                long cpuStarted = currentThreadCpuNanos();
                try {
                    participant.flush();
                } catch (IOException exception) {
                    throw new MigrationException("Persistence Participant Flush Failed: " + participant.owner(), exception);
                } finally {
                    recordLifecycleTiming("flush", participant, started, cpuStarted);
                }
            }
        }
    }

    void flushAllExcept(Collection<String> excludedOwners) throws IOException {
        requireOpenForLifecycle();
        synchronized (this) {
            requireOpenForLifecycle();
            validateLifecycleDependencies();
            Set<String> excluded = registeredOwnerScope(excludedOwners);
            for (PersistenceParticipant participant : sortedParticipants()) {
                if (excluded.contains(participant.owner())) {
                    continue;
                }
                long started = System.nanoTime();
                long cpuStarted = currentThreadCpuNanos();
                try {
                    participant.flush();
                } catch (IOException exception) {
                    throw new MigrationException("Persistence Participant Flush Failed: " + participant.owner(), exception);
                } finally {
                    recordLifecycleTiming("flush", participant, started, cpuStarted);
                }
            }
        }
    }

    public void quiesceAll() throws IOException {
        requireOpenForLifecycle();
        synchronized (this) {
            requireOpenForLifecycle();
            List<PersistenceParticipant> ordered = quiesceOrder(sortedParticipants());
            List<PersistenceParticipant> quiesced = new ArrayList<>();
            try {
                for (PersistenceParticipant participant : ordered) {
                    long started = System.nanoTime();
                    long cpuStarted = currentThreadCpuNanos();
                    try {
                        participant.quiesce();
                    } finally {
                        recordLifecycleTiming("quiesce", participant, started, cpuStarted);
                    }
                    quiesced.add(participant);
                }
            } catch (IOException | RuntimeException exception) {
                MigrationException failure = new MigrationException("Persistence Participant Quiesce Failed", exception);
                resume(quiesced, failure);
                throw failure;
            }
        }
    }

    public void resumeAll() throws IOException {
        resumeAll(null);
    }

    public void resumeAll(PersistenceParticipantClassification classification) throws IOException {
        requireOpenForLifecycle();
        synchronized (this) {
            requireOpenForLifecycle();
            resume(classifiedParticipantsOrAll(classification), null);
        }
    }

    void resumeAllExcept(Collection<String> excludedOwners) throws IOException {
        requireOpenForLifecycle();
        synchronized (this) {
            requireOpenForLifecycle();
            Set<String> excluded = registeredOwnerScope(excludedOwners);
            resume(sortedParticipants().stream()
                .filter(participant -> !excluded.contains(participant.owner()))
                .toList(), null);
        }
    }

    public void rebindAll(Path activeRoot) throws IOException {
        rebindAll(activeRoot, Map.of(), Set.of(), true);
    }

    void rebindAllProvisional(Path activeRoot) throws IOException {
        rebindAll(activeRoot, Map.of(), Set.of(), false);
    }

    synchronized TopologyTransition topologyTransition(VerifiedSnapshotAdmission topologyAdmission) throws IOException {
        VerifiedSnapshotAdmission admission = Objects.requireNonNull(topologyAdmission, "topologyAdmission");
        SnapshotManifest manifest = admission.snapshot().manifest();
        return new TopologyTransition(manifest, topologyOwners(manifest), null);
    }

    synchronized TopologyTransition topologyTransition(AcceptedStagePublisher.RecoveredTopology recoveredTopology) {
        AcceptedStagePublisher.RecoveredTopology recovered = Objects.requireNonNull(
            recoveredTopology, "recoveredTopology");
        SnapshotManifest manifest = recovered.manifest();
        return new TopologyTransition(manifest, topologyOwners(manifest), recovered);
    }

    private static Map<String, String> topologyOwners(SnapshotManifest manifest) {
        Map<String, String> topologyOwners = new LinkedHashMap<>();
        for (SnapshotManifest.Entry entry : manifest.entries()) {
            if (topologyOwners.putIfAbsent(entry.relativePath(), entry.owner()) != null) {
                throw new IllegalArgumentException("Verified Post-Stage Persistence Topology Contains A Duplicate File: "
                    + entry.relativePath());
            }
        }
        return Map.copyOf(topologyOwners);
    }

    void rebindAll(Path activeRoot, TopologyTransition topologyTransition) throws IOException {
        TopologyTransition transition = Objects.requireNonNull(topologyTransition, "topologyTransition");
        Path root = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
        if (transition.recoveredTopology() == null) {
            SnapshotVerification verification = transition.manifest().verify(root);
            verification.requireVerified();
            if (!verification.manifestHash().equals(transition.manifest().manifestHash())) {
                throw new MigrationException("Verified Post-Stage Persistence Topology Does Not Match Active Root");
            }
        } else {
            transition.recoveredTopology().requireCurrent(root);
        }
        rebindAll(root, transition.topologyOwners(), transition.excludedPaths(), true);
    }

    private void rebindAll(Path activeRoot, Map<String, String> topologyOwners,
                           Set<String> topologyExclusions, boolean commit) throws IOException {
        requireOpenForMutation();
        synchronized (this) {
            requireOpenForMutation();
            ownershipEpoch.incrementAndGet();
            validatedOwnershipResolution = null;
            Path root = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
            if (rebindStatus.state() == PersistenceRebindStatus.State.INCONSISTENT) {
                throw new MigrationException("Persistence Participant Rebind Is Inconsistent: " + rebindStatus.reason());
            }
            List<PersistenceParticipant> ordered = sortedParticipants();
            Path previousScope = activeScopeRoot == null ? initialScopeRoot(ordered) : activeScopeRoot;
            Map<String, String> previousTopologyOwners = verifiedTopologyOwners;
            Set<String> previousTopologyExclusions = verifiedTopologyExclusions;
            verifiedTopologyOwners = Map.copyOf(topologyOwners);
            verifiedTopologyExclusions = Set.copyOf(topologyExclusions);
            List<PersistenceParticipant> rebound = new ArrayList<>();
            for (PersistenceParticipant participant : ordered) {
                long started = System.nanoTime();
                long cpuStarted = currentThreadCpuNanos();
                try {
                    participant.rebind(root);
                    validateReboundRoot(participant, root);
                } catch (IOException exception) {
                    verifiedTopologyOwners = previousTopologyOwners;
                    verifiedTopologyExclusions = previousTopologyExclusions;
                    throw rebindFailure(root, previousScope, participant, rebound, exception);
                } catch (RuntimeException exception) {
                    verifiedTopologyOwners = previousTopologyOwners;
                    verifiedTopologyExclusions = previousTopologyExclusions;
                    throw rebindFailure(root, previousScope, participant, rebound, exception);
                } finally {
                    recordLifecycleTiming("rebind", participant, started, cpuStarted);
                }
                rebound.add(participant);
            }
            if (commit) {
                try {
                    validateOwnershipResolution(root, "rebind");
                } catch (IOException exception) {
                    verifiedTopologyOwners = previousTopologyOwners;
                    verifiedTopologyExclusions = previousTopologyExclusions;
                    throw rebindFailure(root, previousScope, null, rebound, exception);
                } catch (RuntimeException exception) {
                    verifiedTopologyOwners = previousTopologyOwners;
                    verifiedTopologyExclusions = previousTopologyExclusions;
                    throw rebindFailure(root, previousScope, null, rebound, exception);
                }
            }
            if (!topologyOwners.isEmpty()) {
                verifiedTopologyOwners = Map.of();
                verifiedTopologyExclusions = Set.of();
                ownershipEpoch.incrementAndGet();
                validatedOwnershipResolution = null;
            }
            activeScopeRoot = root;
            List<String> reboundOwners = rebound.stream().map(PersistenceParticipant::owner).toList();
            if (!commit) {
                provisionalPreviousScope = previousScope;
                provisionalPreviousTopologyOwners = previousTopologyOwners;
                provisionalPreviousTopologyExclusions = previousTopologyExclusions;
            }
            rebindStatus = commit
                ? PersistenceRebindStatus.committed(root, reboundOwners)
                : PersistenceRebindStatus.provisional(root, reboundOwners);
        }
    }

    synchronized void commitProvisionalRebind(Path activeRoot) throws IOException {
        requireOpenForMutation();
        Path root = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
        if (rebindStatus.state() != PersistenceRebindStatus.State.PROVISIONAL
            || !root.equals(activeScopeRoot)
            || validatedOwnershipResolution == null
            || !validatedOwnershipResolution.matches(root, ownershipEpoch.get())) {
            throw new MigrationException("Persistence Participant Rebind Requires Final Ownership Validation");
        }
        rebindStatus = PersistenceRebindStatus.committed(root, rebindStatus.reboundOwners());
        clearProvisionalRebind();
    }

    synchronized MigrationException failProvisionalRebind(Exception primary) {
        Objects.requireNonNull(primary, "provisional rebind failure");
        if (rebindStatus.state() != PersistenceRebindStatus.State.PROVISIONAL) {
            return primary instanceof MigrationException migration ? migration
                : new MigrationException("Persistence Participant Convergence Failed: " + reason(primary), primary);
        }
        Path requestedRoot = rebindStatus.requestedRoot();
        Map<String, PersistenceParticipant> byOwner = sortedParticipants().stream()
            .collect(Collectors.toMap(PersistenceParticipant::owner, participant -> participant));
        List<PersistenceParticipant> rebound = rebindStatus.reboundOwners().stream()
            .map(byOwner::get)
            .filter(Objects::nonNull)
            .toList();
        verifiedTopologyOwners = provisionalPreviousTopologyOwners;
        verifiedTopologyExclusions = provisionalPreviousTopologyExclusions;
        ownershipEpoch.incrementAndGet();
        validatedOwnershipResolution = null;
        MigrationException failure = rebindFailure(requestedRoot, provisionalPreviousScope, null, rebound, primary);
        clearProvisionalRebind();
        return failure;
    }

    private void clearProvisionalRebind() {
        provisionalPreviousScope = null;
        provisionalPreviousTopologyOwners = Map.of();
        provisionalPreviousTopologyExclusions = Set.of();
    }

    public synchronized PersistenceRebindStatus rebindStatus() {
        return rebindStatus;
    }

    static final class TopologyTransition {
        private final SnapshotManifest manifest;
        private final Map<String, String> topologyOwners;
        private final AcceptedStagePublisher.RecoveredTopology recoveredTopology;

        private TopologyTransition(SnapshotManifest manifest, Map<String, String> topologyOwners,
                                   AcceptedStagePublisher.RecoveredTopology recoveredTopology) {
            this.manifest = Objects.requireNonNull(manifest, "manifest");
            this.topologyOwners = Map.copyOf(topologyOwners);
            this.recoveredTopology = recoveredTopology;
        }

        private SnapshotManifest manifest() {
            return manifest;
        }

        private Map<String, String> topologyOwners() {
            return topologyOwners;
        }

        private Set<String> excludedPaths() {
            return recoveredTopology == null ? Set.of() : recoveredTopology.excludedPaths();
        }

        private AcceptedStagePublisher.RecoveredTopology recoveredTopology() {
            return recoveredTopology;
        }
    }

    public void beginShutdown() {
        if (shutdownState.compareAndSet(PersistenceShutdownStatus.State.OPEN, PersistenceShutdownStatus.State.QUIESCING)) {
            shutdownStatus = PersistenceShutdownStatus.quiescing();
        }
    }

    public synchronized void retryShutdown() throws IOException {
        if (shutdownState.get() == PersistenceShutdownStatus.State.FAILED) {
            Map<String, String> failures = new LinkedHashMap<>();
            MigrationException recovery = new MigrationException("Persistence Shutdown Retry Resume Failed");
            shutdownQuiescedOwners = resumeAfterShutdownFailure(shutdownQuiescedOwners, failures, recovery);
            if (!shutdownQuiescedOwners.isEmpty()) {
                String reason = "resume failed for " + shutdownQuiescedOwners.stream().sorted()
                    .collect(Collectors.joining(", "));
                shutdownStatus = PersistenceShutdownStatus.failed(shutdownStatus.flushedOwners(),
                    shutdownStatus.quiescedOwners(), failures, reason);
                throw recovery;
            }
            shutdownState.set(PersistenceShutdownStatus.State.QUIESCING);
            shutdownStatus = PersistenceShutdownStatus.quiescing();
        }
    }

    public synchronized PersistenceShutdownStatus quiesceForShutdown() throws IOException {
        requireShutdownState(PersistenceShutdownStatus.State.QUIESCING);
        validateLifecycleDependencies();
        List<String> flushedOwners = new ArrayList<>();
        List<String> quiescedOwners = new ArrayList<>();
        Map<String, String> failures = new LinkedHashMap<>();
        Exception primary = null;
        String phase = "flush";
        String failedOwner = "";
        List<PersistenceParticipant> resumeOrdered = resumeOrder(sortedParticipants());
        shutdownQuiescedOwners = Set.of();
        for (PersistenceParticipant participant : resumeOrdered) {
            if (participant.classification() == PersistenceParticipantClassification.DERIVED_CACHE) {
                continue;
            }
            try {
                participant.flush();
                flushedOwners.add(participant.owner());
            } catch (IOException | RuntimeException exception) {
                failedOwner = participant.owner();
                failures.put(failedOwner, "flush: " + reason(exception));
                primary = exception;
                break;
            }
        }
        if (primary == null) {
            phase = "quiesce";
            for (PersistenceParticipant participant : quiesceOrder(resumeOrdered)) {
                try {
                    participant.quiesce();
                    quiescedOwners.add(participant.owner());
                } catch (IOException | RuntimeException exception) {
                    failedOwner = participant.owner();
                    failures.put(failedOwner, "quiesce: " + reason(exception));
                    primary = exception;
                    break;
                }
            }
        }
        if (primary != null) {
            shutdownQuiescedOwners = resumeAfterShutdownFailure(quiescedOwners, failures, primary);
            shutdownStatus = PersistenceShutdownStatus.failed(flushedOwners, quiescedOwners, failures,
                phase + " failed for " + failedOwner + ": " + reason(primary));
            shutdownState.set(PersistenceShutdownStatus.State.FAILED);
            throw shutdownFailure(phase, failedOwner, primary, failures);
        }
        phase = "health check";
        for (PersistenceParticipant participant : resumeOrdered) {
            if (participant.classification() == PersistenceParticipantClassification.DERIVED_CACHE) {
                continue;
            }
            try {
                participant.healthCheck();
            } catch (IOException | RuntimeException exception) {
                failedOwner = participant.owner();
                failures.put(failedOwner, "health check: " + reason(exception));
                shutdownQuiescedOwners = resumeAfterShutdownFailure(quiescedOwners, failures, exception);
                shutdownStatus = PersistenceShutdownStatus.failed(flushedOwners, quiescedOwners, failures,
                    phase + " failed for " + failedOwner + ": " + reason(exception));
                shutdownState.set(PersistenceShutdownStatus.State.FAILED);
                throw shutdownFailure(phase, failedOwner, exception, failures);
            }
        }
        shutdownQuiescedOwners = Set.copyOf(quiescedOwners);
        shutdownStatus = PersistenceShutdownStatus.quiesced(flushedOwners, quiescedOwners);
        shutdownState.set(PersistenceShutdownStatus.State.QUIESCED);
        return shutdownStatus;
    }

    public synchronized PersistenceShutdownStatus closeForShutdown() {
        PersistenceShutdownStatus.State state = shutdownState.get();
        if (state == PersistenceShutdownStatus.State.CLOSED || state == PersistenceShutdownStatus.State.FAILED) {
            return shutdownStatus;
        }
        requireShutdownState(PersistenceShutdownStatus.State.QUIESCED);
        Map<String, String> failures = new LinkedHashMap<>();
        List<PersistenceParticipant> closers = reverseLifecycleOrder().stream()
            .filter(CloseablePersistenceParticipant.class::isInstance)
            .toList();
        for (PersistenceParticipant participant : closers) {
            try {
                ((CloseablePersistenceParticipant) participant).close();
            } catch (IOException | RuntimeException exception) {
                failures.put(participant.owner(), "close: " + reason(exception));
            }
        }
        if (!failures.isEmpty()) {
            String failure = "Persistence Participant Close Failed: " + failures;
            shutdownStatus = PersistenceShutdownStatus.failed(shutdownStatus.flushedOwners(), shutdownStatus.quiescedOwners(),
                failures, failure);
            shutdownState.set(PersistenceShutdownStatus.State.FAILED);
            return shutdownStatus;
        }
        shutdownQuiescedOwners = Set.of();
        shutdownStatus = PersistenceShutdownStatus.closed(shutdownStatus.flushedOwners(), shutdownStatus.quiescedOwners());
        shutdownState.set(PersistenceShutdownStatus.State.CLOSED);
        return shutdownStatus;
    }

    synchronized PersistenceShutdownStatus failShutdown(String phase, Exception exception) {
        PersistenceShutdownStatus.State state = shutdownState.get();
        if (state == PersistenceShutdownStatus.State.CLOSED || state == PersistenceShutdownStatus.State.FAILED) {
            return shutdownStatus;
        }
        String failure = phase + ": " + reason(exception);
        shutdownStatus = PersistenceShutdownStatus.failed(shutdownStatus.flushedOwners(), shutdownStatus.quiescedOwners(),
            Map.of("coordinator", failure), failure);
        shutdownState.set(PersistenceShutdownStatus.State.FAILED);
        return shutdownStatus;
    }

    public PersistenceShutdownStatus shutdownStatus() {
        return shutdownStatus;
    }

    public boolean shutdownStarted() {
        return shutdownState.get() != PersistenceShutdownStatus.State.OPEN;
    }

    private void validateReboundRoot(PersistenceParticipant participant, Path activeRoot) throws IOException {
        Path reboundRoot = MigrationPaths.requirePath(participant.root(), "rebound participant root");
        if (!reboundRoot.equals(activeRoot) && !reboundRoot.startsWith(activeRoot)) {
            throw new MigrationException("Persistence Participant Rebind Escaped Scope: " + participant.owner());
        }
        MigrationPaths.requireNoSymlinkTraversal(activeRoot, reboundRoot);
    }

    private MigrationException rebindFailure(Path requestedRoot, Path previousScope, PersistenceParticipant failed,
                                              List<PersistenceParticipant> rebound, Exception primary) {
        Map<String, String> rollbackFailures = new LinkedHashMap<>();
        List<String> rolledBack = new ArrayList<>();
        List<PersistenceParticipant> attempted = new ArrayList<>(rebound);
        if (failed != null && attempted.stream().noneMatch(participant -> participant == failed)) {
            attempted.add(failed);
        }
        attempted.sort(Comparator.comparing(PersistenceParticipant::owner).reversed());
        for (PersistenceParticipant participant : attempted) {
            try {
                if (previousScope == null) {
                    throw new MigrationException("Previous Persistence Participant Scope Is Unavailable");
                }
                participant.rebind(previousScope);
                validateReboundRoot(participant, previousScope);
                rolledBack.add(participant.owner());
            } catch (IOException | RuntimeException exception) {
                rollbackFailures.put(participant.owner(), reason(exception));
                primary.addSuppressed(exception);
            }
        }
        String failedOwner = failed == null ? "" : failed.owner();
        if (rollbackFailures.isEmpty()) {
            activeScopeRoot = previousScope;
            rebindStatus = new PersistenceRebindStatus(PersistenceRebindStatus.State.ROLLED_BACK, requestedRoot,
                previousScope == null ? Optional.empty() : Optional.of(previousScope), failedOwner,
                rebound.stream().map(PersistenceParticipant::owner).toList(), rolledBack, Map.of(), reason(primary));
        } else {
            activeScopeRoot = null;
            rebindStatus = new PersistenceRebindStatus(PersistenceRebindStatus.State.INCONSISTENT, requestedRoot,
                Optional.empty(), failedOwner, rebound.stream().map(PersistenceParticipant::owner).toList(), rolledBack,
                rollbackFailures, reason(primary));
        }
        String suffix = rollbackFailures.isEmpty()
            ? " (previous participant roots restored)"
            : " (participant roots are inconsistent; rollback failed for " + String.join(", ", rollbackFailures.keySet()) + ")";
        return new MigrationException("Persistence Participant Rebind Failed: "
            + (failed == null ? "topology validation" : failed.owner()) + ": " + reason(primary) + suffix, primary);
    }

    private Path initialScopeRoot(List<PersistenceParticipant> values) throws IOException {
        if (scopeRoot == null) {
            return inferScopeRoot(values);
        }
        if (values.isEmpty() || values.stream()
            .allMatch(participant -> MigrationPaths.requirePath(participant.root(), "participant root").startsWith(scopeRoot))) {
            return scopeRoot;
        }
        Path initialScope = inferScopeRoot(values);
        MigrationPaths.requireDirectory(initialScope, "initial participant scope");
        for (PersistenceParticipant participant : values) {
            if (!(participant instanceof RebindablePersistenceParticipant rebindable)
                || !initialScope.equals(MigrationPaths.requirePath(rebindable.rebindScope(), "rebind scope"))) {
                throw new MigrationException("Initial Persistence Participant Rebind Scope Is Ambiguous");
            }
            validateReboundRoot(participant, initialScope);
        }
        return initialScope;
    }

    private Path inferScopeRoot(List<PersistenceParticipant> values) throws IOException {
        if (values.isEmpty()) {
            return null;
        }
        List<Path> declared = values.stream()
            .map(participant -> participant instanceof RebindablePersistenceParticipant rebindable
                ? MigrationPaths.requirePath(rebindable.rebindScope(), "rebind scope")
                : MigrationPaths.requirePath(participant.root(), "participant root"))
            .toList();
        Path common = declared.getFirst();
        for (Path candidate : declared) {
            while (!candidate.startsWith(common)) {
                common = common.getParent();
                if (common == null) {
                    throw new MigrationException("Persistence Participant Rebind Scope Is Ambiguous");
                }
            }
        }
        return common;
    }

    private static String reason(Exception exception) {
        Throwable detail = exception;
        int depth = 0;
        while (detail.getCause() != null && detail.getCause() != detail && depth++ < 8) {
            detail = detail.getCause();
        }
        String message = detail.getMessage();
        if (message == null || message.isBlank()) {
            return detail.getClass().getSimpleName();
        }
        String normalized = message.strip();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.contains("payload") || lower.contains("authorization") || lower.contains("token")
            || lower.contains("secret") || lower.contains("password") || lower.contains("select ")
            || lower.contains("insert ") || lower.contains("update ") || lower.contains("delete ")) {
            return detail.getClass().getSimpleName();
        }
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240);
    }

    private static boolean rootsOverlap(Path first, Path second) {
        return first.equals(second) || first.startsWith(second) || second.startsWith(first);
    }

    private static boolean ownsBoundary(OwnershipBinding binding, Path candidate) {
        return ownsBoundary(binding.participant(), binding.customOwnership(), candidate);
    }

    private static boolean ownsBoundary(PersistenceParticipant participant, boolean customOwnership, Path candidate) {
        return customOwnership && participant.owns(candidate);
    }

    private static boolean hasCustomOwnership(PersistenceParticipant participant) {
        try {
            return participant.getClass().getMethod("owns", Path.class).getDeclaringClass() != PersistenceParticipant.class;
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException("Persistence Participant Ownership Method Is Unavailable", exception);
        }
    }

    private static boolean hasDistinctReadinessCheck(PersistenceParticipant participant) {
        try {
            return participant.getClass().getMethod("readinessCheck").getDeclaringClass() != PersistenceParticipant.class;
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException("Persistence Participant Readiness Method Is Unavailable", exception);
        }
    }

    private static boolean overlapsExternalInput(PersistenceParticipant participant, Path participantRoot,
                                                 boolean customOwnership, PersistenceExternalInput.Input input) {
        Path inputRoot = input.path();
        return rootsOverlap(participantRoot, inputRoot)
            || ownsBoundary(participant, customOwnership, inputRoot);
    }

    public synchronized void validateRestoreParticipants() throws IOException {
        if (participants.isEmpty()) {
            throw new MigrationException("At Least One Restore Persistence Participant Is Required");
        }
        for (PersistenceParticipant participant : sortedParticipants()) {
            if (!(participant instanceof RebindablePersistenceParticipant)) {
                throw new MigrationException("Persistence Participant Does Not Prove Atomic Rebind: " + participant.owner());
            }
        }
    }

    public synchronized void validateForRestore(Path activeRoot) throws IOException {
        Path root = MigrationPaths.requireDirectory(activeRoot, "activeRoot");
        validateRestoreParticipants();
        validateOwnershipResolution(root, "restore");
    }

    private void validateOwnershipResolution(Path root, String phase) throws IOException {
        OwnershipResolution resolution = resolutionForValidatedRoot(root);
        OwnershipScanMetrics previous = lastOwnershipScanMetrics;
        try {
            resolution.validate();
        } finally {
            OwnershipScanMetrics current = lastOwnershipScanMetrics;
            if (current != null && current != previous) {
                ownershipValidationMetrics.put(phase, current);
            }
        }
        requireOwnershipEpoch(resolution.epoch());
        validatedOwnershipResolution = resolution;
    }

    public synchronized void validateRestoreReadiness(Path activeRoot) throws IOException {
        validateForRestore(activeRoot);
    }

    public void healthCheckAll() throws IOException {
        requireOpenForLifecycle();
        synchronized (this) {
            requireOpenForLifecycle();
            for (PersistenceParticipant participant : sortedParticipants()) {
                try {
                    participant.healthCheck();
                } catch (IOException exception) {
                    throw new MigrationException("Persistence Participant Health Check Failed: " + participant.owner()
                        + ": " + reason(exception), exception);
                }
            }
        }
    }

    public void healthCheckAll(PersistenceParticipantClassification classification) throws IOException {
        requireOpenForLifecycle();
        Objects.requireNonNull(classification, "classification");
        synchronized (this) {
            requireOpenForLifecycle();
            for (PersistenceParticipant participant : classifiedParticipants(classification)) {
                try {
                    participant.healthCheck();
                } catch (IOException exception) {
                    throw new MigrationException("Persistence Participant Health Check Failed: " + participant.owner()
                        + ": " + reason(exception), exception);
                }
            }
        }
    }

    public void readinessCheckAll() throws IOException {
        requireOpenForLifecycle();
        synchronized (this) {
            requireOpenForLifecycle();
            for (PersistenceParticipant participant : sortedParticipants()) {
                try {
                    participant.readinessCheck();
                } catch (IOException exception) {
                    throw new MigrationException("Persistence Participant Readiness Check Failed: " + participant.owner(), exception);
                }
            }
        }
    }

    public void readinessCheckAll(PersistenceParticipantClassification classification) throws IOException {
        requireOpenForLifecycle();
        Objects.requireNonNull(classification, "classification");
        synchronized (this) {
            requireOpenForLifecycle();
            for (PersistenceParticipant participant : classifiedParticipants(classification)) {
                try {
                    participant.readinessCheck();
                } catch (IOException exception) {
                    throw new MigrationException("Persistence Participant Readiness Check Failed: " + participant.owner(), exception);
                }
            }
        }
    }

    public void healthAndReadinessCheckAll() throws IOException {
        healthAndReadinessCheckAll(null);
    }

    public void healthAndReadinessCheckAll(PersistenceParticipantClassification classification) throws IOException {
        requireOpenForLifecycle();
        synchronized (this) {
            requireOpenForLifecycle();
            List<PersistenceParticipant> selected = classifiedParticipantsOrAll(classification);
            for (PersistenceParticipant participant : selected) {
                long started = System.nanoTime();
                long cpuStarted = currentThreadCpuNanos();
                try {
                    participant.healthCheck();
                } catch (IOException exception) {
                    throw new MigrationException("Persistence Participant Health Check Failed: " + participant.owner()
                        + ": " + reason(exception), exception);
                } finally {
                    recordLifecycleTiming("health", participant, started, cpuStarted);
                }
            }
            for (PersistenceParticipant participant : selected) {
                if (!hasDistinctReadinessCheck(participant)) {
                    continue;
                }
                long started = System.nanoTime();
                long cpuStarted = currentThreadCpuNanos();
                try {
                    participant.readinessCheck();
                } catch (IOException exception) {
                    throw new MigrationException("Persistence Participant Readiness Check Failed: " + participant.owner(), exception);
                } finally {
                    recordLifecycleTiming("readiness", participant, started, cpuStarted);
                }
            }
        }
    }

    void healthAndReadinessCheckAllExcept(Collection<String> excludedOwners,
                                          PersistenceParticipantClassification classification) throws IOException {
        requireOpenForLifecycle();
        Objects.requireNonNull(classification, "classification");
        synchronized (this) {
            requireOpenForLifecycle();
            Set<String> excluded = registeredOwnerScope(excludedOwners);
            List<PersistenceParticipant> selected = classifiedParticipants(classification).stream()
                .filter(participant -> !excluded.contains(participant.owner()))
                .toList();
            for (PersistenceParticipant participant : selected) {
                long started = System.nanoTime();
                long cpuStarted = currentThreadCpuNanos();
                try {
                    participant.healthCheck();
                } catch (IOException exception) {
                    throw new MigrationException("Persistence Participant Health Check Failed: " + participant.owner()
                        + ": " + reason(exception), exception);
                } finally {
                    recordLifecycleTiming("health", participant, started, cpuStarted);
                }
            }
            for (PersistenceParticipant participant : selected) {
                if (!hasDistinctReadinessCheck(participant)) {
                    continue;
                }
                long started = System.nanoTime();
                long cpuStarted = currentThreadCpuNanos();
                try {
                    participant.readinessCheck();
                } catch (IOException exception) {
                    throw new MigrationException("Persistence Participant Readiness Check Failed: " + participant.owner(), exception);
                } finally {
                    recordLifecycleTiming("readiness", participant, started, cpuStarted);
                }
            }
        }
    }

    public void activateAndValidate(Collection<String> owners,
                                    PersistenceParticipantClassification classification) throws IOException {
        requireOpenForLifecycle();
        Objects.requireNonNull(classification, "classification");
        Set<String> requested = owners == null ? Set.of() : owners.stream()
            .map(owner -> MigrationCanonical.requireText(owner, "participant owner"))
            .collect(Collectors.toUnmodifiableSet());
        synchronized (this) {
            requireOpenForLifecycle();
            List<PersistenceParticipant> selected = sortedParticipants().stream()
                .filter(participant -> requested.contains(participant.owner()))
                .toList();
            if (selected.size() != requested.size()) {
                throw new MigrationException("Scoped Persistence Participant Is Not Registered");
            }
            if (selected.stream().anyMatch(participant -> participant.classification() != classification)) {
                throw new MigrationException("Scoped Persistence Participant Classification Does Not Match");
            }
            boolean resumed = false;
            try {
                resume(selected, null);
                resumed = true;
                for (PersistenceParticipant participant : selected) {
                    try {
                        participant.healthCheck();
                        participant.readinessCheck();
                    } catch (IOException | RuntimeException failure) {
                        throw new MigrationException(
                            "Persistence Participant Readiness Check Failed: " + participant.owner(), failure);
                    }
                }
            } catch (IOException | RuntimeException | Error failure) {
                if (resumed) {
                    quiesce(selected, failure);
                }
                throw failure;
            }
        }
    }

    void quiesceScope(Collection<String> owners, PersistenceParticipantClassification classification) throws IOException {
        requireOpenForLifecycle();
        Objects.requireNonNull(classification, "classification");
        Set<String> requested = owners == null ? Set.of() : owners.stream()
            .map(owner -> MigrationCanonical.requireText(owner, "participant owner"))
            .collect(Collectors.toUnmodifiableSet());
        synchronized (this) {
            requireOpenForLifecycle();
            List<PersistenceParticipant> selected = sortedParticipants().stream()
                .filter(participant -> requested.contains(participant.owner()))
                .toList();
            if (selected.size() != requested.size()) {
                throw new MigrationException("Scoped Persistence Participant Is Not Registered");
            }
            if (selected.stream().anyMatch(participant -> participant.classification() != classification)) {
                throw new MigrationException("Scoped Persistence Participant Classification Does Not Match");
            }
            IOException failure = null;
            for (PersistenceParticipant participant : quiesceOrder(selected)) {
                try {
                    participant.quiesce();
                } catch (IOException | RuntimeException exception) {
                    if (failure == null) {
                        failure = new MigrationException(
                            "Persistence Participant Quiesce Failed: " + participant.owner(), exception);
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private void quiesce(Collection<PersistenceParticipant> source, Throwable primary) {
        try {
            for (PersistenceParticipant participant : quiesceOrder(source)) {
                try {
                    participant.quiesce();
                } catch (IOException | RuntimeException failure) {
                    primary.addSuppressed(failure);
                }
            }
        } catch (IOException failure) {
            primary.addSuppressed(failure);
        }
    }

    private void resume(Collection<PersistenceParticipant> source, IOException primary) throws IOException {
        IOException failure = primary;
        List<PersistenceParticipant> resumed = new ArrayList<>();
        for (PersistenceParticipant participant : resumeOrder(source)) {
            long started = System.nanoTime();
            long cpuStarted = currentThreadCpuNanos();
            try {
                participant.resume();
                resumed.add(participant);
            } catch (IOException | RuntimeException exception) {
                if (failure == null) {
                    failure = new MigrationException("Persistence Participant Resume Failed: " + participant.owner()
                        + ": " + reason(exception), exception);
                } else {
                    failure.addSuppressed(exception);
                }
                if (primary == null) {
                    break;
                }
            } finally {
                recordLifecycleTiming("resume", participant, started, cpuStarted);
            }
        }
        if (failure != null && primary == null) {
            quiesce(resumed, failure);
            throw failure;
        }
    }

    synchronized void resetLifecycleTimings() {
        lifecycleTimingNanos.clear();
        lifecycleCpuTimingNanos.clear();
        ownershipValidationMetrics.clear();
    }

    synchronized Map<String, Long> lifecycleTimingsMillis() {
        LinkedHashMap<String, Long> timings = new LinkedHashMap<>();
        lifecycleTimingNanos.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .forEach(entry -> timings.put(entry.getKey(), TimeUnit.NANOSECONDS.toMillis(entry.getValue())));
        return Collections.unmodifiableMap(timings);
    }

    synchronized Map<String, Long> lifecycleCpuTimingsMillis() {
        LinkedHashMap<String, Long> timings = new LinkedHashMap<>();
        lifecycleCpuTimingNanos.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .forEach(entry -> timings.put(entry.getKey(), TimeUnit.NANOSECONDS.toMillis(entry.getValue())));
        return Collections.unmodifiableMap(timings);
    }

    private synchronized void recordLifecycleTiming(String operation, PersistenceParticipant participant, long started,
                                                    long cpuStarted) {
        String key = operation + ":" + participant.owner();
        lifecycleTimingNanos.merge(key, Math.max(0L, System.nanoTime() - started), Long::sum);
        lifecycleCpuTimingNanos.merge(key, elapsedCpuNanos(cpuStarted), Long::sum);
    }

    private static long elapsedMillis(long started) {
        return started <= 0L ? 0L : TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - started));
    }

    private static long currentThreadCpuNanos() {
        return THREAD_CPU.isCurrentThreadCpuTimeSupported() ? Math.max(0L, THREAD_CPU.getCurrentThreadCpuTime()) : 0L;
    }

    private static long elapsedCpuNanos(long started) {
        return started <= 0L ? 0L : Math.max(0L, currentThreadCpuNanos() - started);
    }

    private static long currentThreadCpuMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(elapsedCpuNanos(started));
    }

    private static long saturatedAdd(long current, long value) {
        return value > Long.MAX_VALUE - current ? Long.MAX_VALUE : current + value;
    }

    private boolean participantRootContains(Path sourceRoot, Path participantRoot, Path candidate) {
        if (!participantRoot.startsWith(sourceRoot)) {
            return false;
        }
        return candidate.equals(participantRoot) || candidate.startsWith(participantRoot);
    }

    private Set<String> resumeAfterShutdownFailure(Collection<String> quiescedOwners, Map<String, String> failures,
                                                   Exception primary) {
        Set<String> remaining = new LinkedHashSet<>(quiescedOwners);
        List<PersistenceParticipant> quiesced;
        try {
            quiesced = resumeOrder(sortedParticipants().stream()
                .filter(participant -> quiescedOwners.contains(participant.owner()))
                .toList());
        } catch (IOException exception) {
            failures.put("lifecycle", "resume dependencies: " + reason(exception));
            primary.addSuppressed(exception);
            return Set.copyOf(remaining);
        }
        for (PersistenceParticipant participant : quiesced) {
            try {
                participant.resume();
                remaining.remove(participant.owner());
            } catch (IOException | RuntimeException exception) {
                failures.put(participant.owner(), "resume: " + reason(exception));
                primary.addSuppressed(exception);
            }
        }
        return Set.copyOf(remaining);
    }

    private MigrationException shutdownFailure(String phase, String owner, Exception primary, Map<String, String> failures) {
        String suffix = failures.size() == 1 ? "" : " (failures: " + String.join(", ", failures.keySet()) + ")";
        return new MigrationException("Persistence Shutdown " + phase + " Failed: " + owner + suffix, primary);
    }

    private void requireOpenForMutation() {
        PersistenceShutdownStatus.State state = shutdownState.get();
        if (state != PersistenceShutdownStatus.State.OPEN) {
            throw new IllegalStateException("Persistence Participant Registry Is " + state.name());
        }
    }

    private void requireOpenForLifecycle() {
        requireOpenForMutation();
    }

    private void requireShutdownState(PersistenceShutdownStatus.State expected) {
        PersistenceShutdownStatus.State actual = shutdownState.get();
        if (actual != expected) {
            throw new IllegalStateException("Persistence Participant Registry State Is " + actual.name()
                + "; Expected " + expected.name());
        }
    }

    private List<PersistenceParticipant> sortedParticipants() {
        return participants.values().stream()
                .sorted(Comparator.comparing(PersistenceParticipant::owner))
                .toList();
    }

    private List<PersistenceParticipant> classifiedParticipants(PersistenceParticipantClassification classification) {
        return sortedParticipants().stream()
            .filter(participant -> participant.classification() == classification)
            .toList();
    }

    private List<PersistenceParticipant> classifiedParticipantsOrAll(PersistenceParticipantClassification classification) {
        return classification == null ? sortedParticipants() : classifiedParticipants(classification);
    }

    private Set<String> registeredOwnerScope(Collection<String> owners) throws MigrationException {
        Set<String> requested = owners == null ? Set.of() : owners.stream()
            .map(owner -> MigrationCanonical.requireText(owner, "participant owner"))
            .collect(Collectors.toUnmodifiableSet());
        if (!participants.keySet().containsAll(requested)) {
            throw new MigrationException("Scoped Persistence Participant Is Not Registered");
        }
        return requested;
    }

    private List<PersistenceParticipant> resumeOrder(Collection<PersistenceParticipant> source) throws MigrationException {
        return lifecycleOrder(source);
    }

    private List<PersistenceParticipant> quiesceOrder(Collection<PersistenceParticipant> source) throws MigrationException {
        List<PersistenceParticipant> ordered = new ArrayList<>(lifecycleOrder(source));
        Collections.reverse(ordered);
        return List.copyOf(ordered);
    }

    private void validateLifecycleDependencies() throws MigrationException {
        lifecycleOrder(participants.values());
    }

    private List<PersistenceParticipant> lifecycleOrder(Collection<PersistenceParticipant> source)
        throws MigrationException {
        Map<String, PersistenceParticipant> registered = new LinkedHashMap<>();
        for (PersistenceParticipant participant : sortedParticipants()) {
            registered.put(participant.owner(), participant);
        }
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        for (PersistenceParticipant participant : registered.values()) {
            Set<String> declared = participant.resumeDependencies();
            if (declared == null) {
                throw new MigrationException("Persistence Participant Resume Dependencies Are Required: "
                    + participant.owner());
            }
            List<String> normalized = new ArrayList<>();
            for (String dependency : declared) {
                try {
                    normalized.add(MigrationCanonical.requireText(dependency, "resume dependency"));
                } catch (RuntimeException exception) {
                    throw new MigrationException("Persistence Participant Resume Dependency Is Invalid: "
                        + participant.owner(), exception);
                }
            }
            Set<String> participantDependencies = Set.copyOf(normalized);
            for (String dependency : participantDependencies) {
                if (!registered.containsKey(dependency)) {
                    throw new MigrationException("Persistence Participant Resume Dependency Is Not Registered: "
                        + participant.owner() + " Requires " + dependency);
                }
            }
            dependencies.put(participant.owner(), participantDependencies);
        }

        Map<String, PersistenceParticipant> selected = new LinkedHashMap<>();
        for (PersistenceParticipant participant : source) {
            PersistenceParticipant registeredParticipant = registered.get(participant.owner());
            if (registeredParticipant != participant) {
                throw new MigrationException("Persistence Lifecycle Participant Is Not Registered: "
                    + participant.owner());
            }
            selected.put(participant.owner(), participant);
        }
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new LinkedHashMap<>();
        for (String owner : selected.keySet()) {
            indegree.put(owner, 0);
            dependents.put(owner, new ArrayList<>());
        }
        for (String owner : selected.keySet()) {
            for (String dependency : dependencies.get(owner)) {
                if (!selected.containsKey(dependency)) {
                    continue;
                }
                indegree.compute(owner, (ignored, value) -> value + 1);
                dependents.get(dependency).add(owner);
            }
        }
        Comparator<PersistenceParticipant> stable = Comparator
            .comparingInt(PersistenceParticipantRegistry::resumeRank)
            .thenComparing(PersistenceParticipant::owner, Comparator.reverseOrder());
        PriorityQueue<PersistenceParticipant> ready = new PriorityQueue<>(stable);
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(selected.get(entry.getKey()));
            }
        }
        List<PersistenceParticipant> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            PersistenceParticipant participant = ready.remove();
            ordered.add(participant);
            for (String dependent : dependents.get(participant.owner())) {
                int remaining = indegree.compute(dependent, (ignored, value) -> value - 1);
                if (remaining == 0) {
                    ready.add(selected.get(dependent));
                }
            }
        }
        if (ordered.size() != selected.size()) {
            List<String> blocked = indegree.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
            throw new MigrationException("Persistence Participant Resume Dependency Cycle: "
                + String.join(", ", blocked));
        }
        return List.copyOf(ordered);
    }

    private static int resumeRank(PersistenceParticipant participant) {
        return participant.classification() == PersistenceParticipantClassification.DERIVED_CACHE ? 1 : 0;
    }

    private List<PersistenceParticipant> reverseLifecycleOrder() {
        try {
            return quiesceOrder(sortedParticipants());
        } catch (IOException exception) {
            throw new IllegalStateException("Persistence Lifecycle Dependencies Are Invalid", exception);
        }
    }
}

package restudio.resync.upgrade.lifecycle;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogVersionRange;
import restudio.resync.migration.MigrationPaths;
import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.QuarantineRecord;
import restudio.resync.upgrade.TypedLifecycleMigrationAdapter;

public final class ExtensionStateMigrationAdapter implements TypedLifecycleMigrationAdapter {
    public static final String ID = "resync.lifecycle.extension-state";
    public static final String OWNER = ProductionPersistenceOwners.EXTENSIONS;
    public static final String REGISTRY_PATH = "extensions/extensions.json";
    public static final String CODE_REGISTRY_MISSING = "MIGRATION.EXTENSION_REGISTRY_MISSING";
    public static final String CODE_REGISTRY_INVALID = "MIGRATION.EXTENSION_REGISTRY_INVALID";
    public static final String CODE_VERSION_MISMATCH = "MIGRATION.EXTENSION_VERSION_MISMATCH";
    public static final String CODE_STATE_MISSING = "MIGRATION.EXTENSION_STATE_MISSING";
    public static final String CODE_STATE_INVALID = "MIGRATION.EXTENSION_STATE_INVALID";
    public static final String CODE_ARTIFACT_MISSING = "MIGRATION.EXTENSION_ARTIFACT_MISSING";
    public static final String CODE_ARTIFACT_INCOMPATIBLE = "MIGRATION.EXTENSION_ARTIFACT_INCOMPATIBLE";
    public static final String CODE_DEPENDENCY_MISSING = "MIGRATION.EXTENSION_DEPENDENCY_MISSING";
    public static final String CODE_DEPENDENCY_INCOMPATIBLE = "MIGRATION.EXTENSION_DEPENDENCY_INCOMPATIBLE";
    public static final String CODE_ORPHAN_FILE = "MIGRATION.EXTENSION_ORPHAN_FILE";
    private static final int SNAPSHOT_BINDING_VERSION = 1;
    private static final String SNAPSHOT_BINDING_HASH = "bindingHash";
    private static final List<String> SNAPSHOT_BINDING_FIELDS = List.of(
        "bindingVersion", "formatVersion", "snapshotId", "createdAt", "manifestHash", "build", "catalogChecksum", "extensionVersions"
    );

    @Override
    public String adapterId() {
        return ID;
    }

    @Override
    public Adaptation adapt(Input input) throws IOException {
        Objects.requireNonNull(input, "input");
        List<SourceFile> sources = input.files().stream().filter(ExtensionStateMigrationAdapter::isExtensionPath).toList();
        List<Claim> claims = sources.stream().map(source -> new Claim(source.relativePath(), OWNER)).toList();
        if (sources.isEmpty()) {
            return Adaptation.claimed(claims);
        }
        if (sources.stream().anyMatch(source -> !source.owner().equals(OWNER))) {
            return Adaptation.claimed(claims);
        }
        Map<String, SourceFile> files = new HashMap<>();
        Map<String, byte[]> bytes = new HashMap<>();
        for (SourceFile source : sources) {
            files.put(source.relativePath(), source);
            bytes.put(source.relativePath(), input.read(source));
        }
        ArrayList<Issue> issues = new ArrayList<>();
        SourceFile registryFile = files.get(REGISTRY_PATH);
        if (registryFile == null) {
            SourceFile evidence = sources.stream().min(Comparator.comparing(SourceFile::relativePath)).orElseThrow();
            issues.add(new Issue(evidence.relativePath(), evidence.sha256(), CODE_REGISTRY_MISSING,
                "Extension files exist without the authoritative extension registry.", List.of(),
                "Restore extensions/extensions.json or explicitly quarantine the orphaned extension state."));
            return blocked(input, claims, issues);
        }
        Map<String, Object> registry;
        try {
            registry = object(CanonicalJson.parseOpaque(bytes.get(REGISTRY_PATH)), "extension registry");
        } catch (IllegalArgumentException exception) {
            issues.add(new Issue(REGISTRY_PATH, registryFile.sha256(), CODE_REGISTRY_INVALID, exception.getMessage(), List.of(),
                "Restore a valid extension registry before migrating extension state."));
            return blocked(input, claims, issues);
        }

        List<Object> extensionRows;
        List<Object> jarRows;
        Map<String, Object> registrySnapshot;
        try {
            extensionRows = array(registry.get("extensions"), "extensions");
            jarRows = array(registry.get("jars"), "jars");
            registrySnapshot = mergedSnapshotBinding(registry.get("snapshot"), input);
        } catch (IllegalArgumentException exception) {
            issues.add(new Issue(REGISTRY_PATH, registryFile.sha256(), CODE_REGISTRY_INVALID, exception.getMessage(), List.of(),
                "Restore the required extensions and jars arrays."));
            return blocked(input, claims, issues);
        }

        TreeMap<String, ExtensionEntry> extensions = new TreeMap<>();
        for (Object row : extensionRows) {
            try {
                Map<String, Object> value = object(row, "extension");
                ExtensionEntry extension = extension(value);
                if (extensions.putIfAbsent(extension.id(), extension) != null) {
                    throw new IllegalArgumentException("Duplicate extension id: " + extension.id());
                }
            } catch (IllegalArgumentException exception) {
                issues.add(new Issue(REGISTRY_PATH, registryFile.sha256(), CODE_REGISTRY_INVALID, exception.getMessage(), List.of(),
                    "Give every extension one valid, unique registry entry."));
            }
        }

        Map<String, JarEntry> jarsById = new HashMap<>();
        Map<String, JarEntry> jarsByPath = new HashMap<>();
        for (Object row : jarRows) {
            try {
                JarEntry jar = jar(row);
                if (jarsByPath.putIfAbsent(jar.path(), jar) != null) {
                    throw new IllegalArgumentException("Duplicate extension jar path: " + jar.path());
                }
                if (jar.id() != null && jarsById.putIfAbsent(jar.id(), jar) != null) {
                    throw new IllegalArgumentException("Duplicate extension jar id: " + jar.id());
                }
            } catch (IllegalArgumentException exception) {
                issues.add(new Issue(REGISTRY_PATH, registryFile.sha256(), CODE_REGISTRY_INVALID, exception.getMessage(), List.of(),
                    "Bind each local extension to one unique jar inventory entry."));
            }
        }

        Map<String, String> availableVersions = new HashMap<>(input.metadata().extensionVersions());
        extensions.forEach((id, extension) -> availableVersions.put(id, extension.version()));
        HashSet<String> referencedJars = new HashSet<>();
        LinkedHashMap<String, byte[]> canonicalStates = new LinkedHashMap<>();
        for (ExtensionEntry extension : extensions.values()) {
            validateVersion(input, registryFile, extension, issues);
            validateArtifact(files, extension, jarsById, jarsByPath, referencedJars, issues, registryFile);
            validateDependencies(extension, extensions.keySet(), availableVersions, registryFile, issues);
            validateState(files, bytes, extension, registrySnapshot, canonicalStates, registryFile, issues);
        }
        Set<String> declaredExternalDependencies = extensions.values().stream()
            .flatMap(extension -> extension.dependencies().stream())
            .filter(Dependency::external)
            .map(Dependency::id)
            .collect(Collectors.toSet());
        input.metadata().extensionVersions().forEach((id, version) -> {
            if (!extensions.containsKey(id) && !declaredExternalDependencies.contains(id)) {
                issues.add(new Issue(REGISTRY_PATH, registryFile.sha256(), CODE_VERSION_MISMATCH,
                    "The verified snapshot metadata contains an extension with no registry or external dependency declaration.", List.of(
                        "observedExtension=" + id,
                        "observedVersion=" + version,
                        "expectedDeclaration=<missing>"
                    ), "Declare the extension in the registry or as an explicit external dependency."));
            }
        });

        for (JarEntry jar : jarsByPath.values()) {
            if (!referencedJars.contains(jar.path())) {
                SourceFile evidence = files.getOrDefault(jar.path(), registryFile);
                issues.add(new Issue(evidence.relativePath(), evidence.sha256(), CODE_ARTIFACT_INCOMPATIBLE,
                    "The jar inventory entry is not owned by any registered local extension.", List.of(jar.id()),
                    "Remove the stale inventory entry or bind it to its extension."));
            }
        }
        for (SourceFile source : sources) {
            String path = source.relativePath();
            if (path.equals(REGISTRY_PATH)) {
                continue;
            }
            if (path.startsWith("extensions/jars/")) {
                if (!jarsByPath.containsKey(path)) {
                    issues.add(new Issue(path, source.sha256(), CODE_ORPHAN_FILE,
                        "An extension artifact file is not declared by the authoritative jar inventory.", List.of(path),
                        "Add the exact artifact identity, version, and hash to the registry or quarantine the orphaned file."));
                }
                continue;
            }
            if (path.toLowerCase(Locale.ROOT).endsWith(".jar") && !jarsByPath.containsKey(path)) {
                issues.add(new Issue(path, source.sha256(), CODE_ORPHAN_FILE,
                    "An extension jar is outside or absent from the authoritative jar inventory.", List.of(path),
                    "Move the jar into extensions/jars and declare its exact identity, version, path, and hash."));
                continue;
            }
            String directoryId = extensionDirectoryId(path);
            if (directoryId != null && !extensions.containsKey(directoryId)) {
                issues.add(new Issue(path, source.sha256(), CODE_ORPHAN_FILE,
                    "An extension directory file belongs to an extension that is not present in the registry.", List.of(directoryId),
                    "Restore the registry entry or retain the orphaned extension directory in quarantine."));
            }
        }
        if (!issues.isEmpty()) {
            return blocked(input, claims, issues);
        }

        ArrayList<Change> changes = new ArrayList<>();
        Map<String, Object> canonicalRegistry = new LinkedHashMap<>(registry);
        canonicalRegistry.put("extensions", extensions.values().stream().map(ExtensionEntry::value).toList());
        canonicalRegistry.put("jars", jarsByPath.values().stream().sorted(Comparator.comparing(JarEntry::path)).map(JarEntry::value).toList());
        canonicalRegistry.put("snapshot", registrySnapshot);
        addChanged(changes, registryFile, CanonicalJson.canonicalBytes(canonicalRegistry));
        canonicalStates.forEach((path, target) -> addChanged(changes, files.get(path), target));
        return new Adaptation(claims, changes, List.of());
    }

    private static void validateVersion(Input input, SourceFile registryFile, ExtensionEntry extension, List<Issue> issues) {
        String metadataVersion = input.metadata().extensionVersions().get(extension.id());
        if (!extension.version().equals(metadataVersion)) {
            issues.add(new Issue(REGISTRY_PATH, registryFile.sha256(), CODE_VERSION_MISMATCH,
                metadataVersion == null
                    ? "The extension is absent from the verified snapshot metadata."
                    : "The registry version does not match the verified snapshot metadata.",
                List.of(
                    "extension=" + extension.id(),
                    "expectedVersion=" + (metadataVersion == null ? "<missing>" : metadataVersion),
                    "observedVersion=" + extension.version()
                ), "Recreate the snapshot with the exact installed extension version."));
        }
    }

    private static void validateArtifact(Map<String, SourceFile> files, ExtensionEntry extension, Map<String, JarEntry> jarsById,
                                         Map<String, JarEntry> jarsByPath, Set<String> referencedJars, List<Issue> issues, SourceFile registryFile) {
        JarEntry jar = extension.artifact() == null ? jarsById.get(extension.id()) : jarsByPath.get(extension.artifact());
        if (extension.external()) {
            if (jar != null || extension.artifact() != null) {
                issues.add(new Issue(REGISTRY_PATH, registryFile.sha256(), CODE_ARTIFACT_INCOMPATIBLE,
                    "An externally supplied extension cannot also own a local jar.", List.of(extension.id()),
                    "Declare either a local jar or an external extension source, never both."));
            }
            return;
        }
        if (jar == null) {
            issues.add(new Issue(REGISTRY_PATH, registryFile.sha256(), CODE_ARTIFACT_MISSING,
                "The local extension has no matching jar inventory entry.", List.of(
                    "expectedId=" + extension.id(),
                    "expectedVersion=" + extension.version(),
                    "observedArtifact=<missing>"
                ),
                "Restore the exact jar or explicitly declare the extension as externally supplied."));
            return;
        }
        referencedJars.add(jar.path());
        SourceFile artifact = files.get(jar.path());
        if (artifact == null) {
            issues.add(new Issue(REGISTRY_PATH, registryFile.sha256(), CODE_ARTIFACT_MISSING,
                "The extension jar declared by the inventory is missing from the snapshot.", List.of(
                    "expectedId=" + extension.id(),
                    "expectedVersion=" + extension.version(),
                    "expectedHash=" + jar.sha256(),
                    "expectedPath=" + jar.path(),
                    "observedArtifact=<missing>"
                ),
                "Restore the declared jar before migration."));
            return;
        }
        if (!jar.id().equals(extension.id()) || !jar.version().equals(extension.version()) || !jar.sha256().equals(artifact.sha256())) {
            issues.add(new Issue(jar.path(), artifact.sha256(), CODE_ARTIFACT_INCOMPATIBLE,
                "The extension jar identity, version, or hash does not match the registry.", List.of(
                    "expectedId=" + extension.id(),
                    "observedId=" + jar.id(),
                    "expectedVersion=" + extension.version(),
                    "observedVersion=" + jar.version(),
                    "expectedHash=" + jar.sha256(),
                    "observedHash=" + artifact.sha256()
                ),
                "Install the exact jar recorded for this extension version."));
        }
    }

    private static void validateDependencies(ExtensionEntry extension, Set<String> registered, Map<String, String> availableVersions,
                                             SourceFile registryFile, List<Issue> issues) {
        for (Dependency dependency : extension.dependencies()) {
            String version = availableVersions.get(dependency.id());
            if (version == null || !registered.contains(dependency.id()) && !dependency.external()) {
                if (!dependency.optional()) {
                    issues.add(new Issue(REGISTRY_PATH, registryFile.sha256(), CODE_DEPENDENCY_MISSING,
                        "A required extension dependency is absent or is not declared as externally supplied.", List.of(
                            "extension=" + extension.id(),
                            "dependency=" + dependency.id(),
                            "expectedRange=" + dependency.versionRange(),
                            "observedVersion=" + (version == null ? "<missing>" : version)
                        ),
                        "Install the dependency, declare its external source, or make it optional."));
                }
                continue;
            }
            try {
                if (!CatalogVersionRange.parse(dependency.versionRange()).includes(version)) {
                    issues.add(new Issue(REGISTRY_PATH, registryFile.sha256(), CODE_DEPENDENCY_INCOMPATIBLE,
                        "An extension dependency version is outside the declared range.", List.of(
                            "extension=" + extension.id(),
                            "dependency=" + dependency.id(),
                            "expectedRange=" + dependency.versionRange(),
                            "observedVersion=" + version
                        ),
                        "Install a compatible dependency version before migration."));
                }
            } catch (IllegalArgumentException exception) {
                issues.add(new Issue(REGISTRY_PATH, registryFile.sha256(), CODE_DEPENDENCY_INCOMPATIBLE,
                    "The extension dependency version or range is invalid.", List.of(
                        "extension=" + extension.id(),
                        "dependency=" + dependency.id(),
                        "expectedRange=" + dependency.versionRange(),
                        "observedVersion=" + version
                    ),
                    "Use a valid semantic version range for the dependency."));
            }
        }
    }

    private static void validateState(Map<String, SourceFile> files, Map<String, byte[]> bytes, ExtensionEntry extension,
                                      Map<String, Object> registrySnapshot, Map<String, byte[]> canonicalStates,
                                      SourceFile registryFile, List<Issue> issues) {
        String path = statePath(extension.id());
        SourceFile stateFile = files.get(path);
        if (stateFile == null) {
            issues.add(new Issue(REGISTRY_PATH, registryFile.sha256(), CODE_STATE_MISSING,
                "The extension has no durable state file.", List.of(
                    "extension=" + extension.id(),
                    "expectedVersion=" + extension.version(),
                    "expectedPath=" + path,
                    "observedState=<missing>"
                ), "Restore the extension state before migration."));
            return;
        }
        String observedId = "<unavailable>";
        String observedVersion = "<unavailable>";
        try {
            Map<String, Object> state = object(CanonicalJson.parseOpaque(bytes.get(path)), "extension state");
            positiveInteger(state.get("revision"), "state revision");
            text(state.get("mutationId"), "state mutationId");
            text(state.get("state"), "state status");
            String boundId = optionalId(state.get("extensionId"), "state extensionId");
            observedId = boundId == null ? "<legacy-unbound>" : boundId;
            if (boundId != null && !boundId.equals(extension.id())) {
                throw new IllegalArgumentException("Extension state identity does not match the registry");
            }
            String boundVersion = optionalText(state.get("extensionVersion"), "state extensionVersion");
            observedVersion = boundVersion == null ? "<legacy-unbound>" : boundVersion;
            if (boundVersion != null && !boundVersion.equals(extension.version())) {
                throw new IllegalArgumentException("Extension state version does not match the registry");
            }
            Map<String, Object> normalized = new LinkedHashMap<>(state);
            normalized.put("extensionId", extension.id());
            normalized.put("extensionVersion", extension.version());
            normalized.put("snapshot", mergedStateSnapshotBinding(state.get("snapshot"), registrySnapshot));
            canonicalStates.put(path, CanonicalJson.canonicalBytes(normalized));
        } catch (IllegalArgumentException exception) {
            issues.add(new Issue(path, stateFile.sha256(), CODE_STATE_INVALID, exception.getMessage(), List.of(
                "expectedId=" + extension.id(),
                "observedId=" + observedId,
                "expectedVersion=" + extension.version(),
                "observedVersion=" + observedVersion
            ),
                "Restore a valid revisioned state document that matches the registered extension version."));
        }
    }

    private static Adaptation blocked(Input input, List<Claim> claims, List<Issue> issues) {
        List<Issue> expanded = expandRegistryWideIssues(input, issues);
        List<QuarantineRecord> quarantine = expanded.stream().distinct().map(issue -> quarantine(input, issue.path(), issue.sourceHash(), issue.code(),
            issue.reason(), issue.references(), issue.action())).toList();
        return new Adaptation(claims, List.of(), quarantine);
    }

    private static List<Issue> expandRegistryWideIssues(Input input, List<Issue> issues) {
        List<Issue> registryWide = issues.stream().filter(issue -> issue.code().equals(CODE_REGISTRY_MISSING) || issue.code().equals(CODE_REGISTRY_INVALID)).toList();
        if (registryWide.isEmpty()) {
            return List.copyOf(issues);
        }
        String code = registryWide.stream().anyMatch(issue -> issue.code().equals(CODE_REGISTRY_MISSING)) ? CODE_REGISTRY_MISSING : CODE_REGISTRY_INVALID;
        String reason = registryWide.stream().map(Issue::reason).distinct().sorted().collect(Collectors.joining("; "));
        String action = registryWide.stream().map(Issue::action).distinct().sorted().collect(Collectors.joining(" "));
        List<String> references = registryWide.stream().flatMap(issue -> issue.references().stream()).distinct().sorted().toList();
        ArrayList<Issue> expanded = new ArrayList<>(issues.stream().filter(issue -> !registryWide.contains(issue)).toList());
        for (SourceFile source : input.files()) {
            if (isExtensionPath(source)) {
                expanded.add(new Issue(source.relativePath(), source.sha256(), code, reason, references, action));
            }
        }
        return List.copyOf(expanded);
    }

    private static QuarantineRecord quarantine(Input input, String path, String sourceHash, String code, String reason,
                                                List<String> references, String action) {
        ArrayList<String> evidence = new ArrayList<>(references);
        evidence.add("snapshotId=" + input.metadata().snapshotId());
        evidence.add("createdAt=" + input.metadata().createdAt());
        evidence.add("manifestHash=" + input.manifestHash());
        evidence.add("formatVersion=" + input.metadata().formatVersion());
        evidence.add("build=" + input.metadata().build());
        evidence.add("catalogChecksum=" + input.metadata().catalogChecksum());
        evidence.add("extensionVersions=" + CanonicalJson.canonicalize(new TreeMap<>(input.metadata().extensionVersions())));
        List<String> normalizedEvidence = evidence.stream().distinct().sorted().toList();
        String recordId = "extension-" + CanonicalJson.sha256("migration.extension-quarantine",
            List.of(path, sourceHash, code, reason, normalizedEvidence)).substring(0, 24);
        return new QuarantineRecord(recordId, code, path, reason, normalizedEvidence, action, sourceHash);
    }

    private static ExtensionEntry extension(Map<String, Object> value) {
        String id = id(value.get("id"), "extension id");
        String version = version(value.get("version"), "extension version");
        text(value.get("contractRange"), "extension contractRange");
        requiredBoolean(value.get("enabled"), "extension enabled");
        boolean external = booleanValue(value.get("external"), false, "extension external");
        String source = optionalText(value.get("source"), "extension source");
        if (source != null) {
            if (!source.equals("local") && !source.equals("external")) {
                throw new IllegalArgumentException("Extension source must be local or external");
            }
            if (value.containsKey("external") && external != source.equals("external")) {
                throw new IllegalArgumentException("Extension source and external declaration disagree");
            }
            external = source.equals("external");
        }
        String artifact = optionalPath(value.get("artifact"), "extension artifact");
        List<Dependency> dependencies = new ArrayList<>();
        Object dependencyValue = value.get("dependencies");
        if (dependencyValue != null) {
            for (Object dependency : array(dependencyValue, "extension dependencies")) {
                dependencies.add(dependency(dependency));
            }
        }
        return new ExtensionEntry(id, version, external, artifact, List.copyOf(dependencies), new LinkedHashMap<>(value));
    }

    private static Dependency dependency(Object row) {
        if (row instanceof String id) {
            return new Dependency(id(id, "dependency id"), "*", false, false);
        }
        Map<String, Object> value = object(row, "extension dependency");
        String versionRange = value.containsKey("versionRange") ? text(value.get("versionRange"), "dependency versionRange") : "*";
        CatalogVersionRange.parse(versionRange);
        return new Dependency(
            id(value.get("id"), "dependency id"),
            versionRange,
            booleanValue(value.get("optional"), false, "dependency optional"),
            booleanValue(value.get("external"), false, "dependency external")
        );
    }

    private static JarEntry jar(Object row) {
        if (row instanceof String) {
            throw new IllegalArgumentException("Jar inventory entries must bind an id, version, path, and sha256");
        }
        Map<String, Object> value = object(row, "jar inventory entry");
        String id = id(value.get("id"), "jar id");
        String version = version(value.get("version"), "jar version");
        String path = path(value.get("path"), "jar path");
        String sha256 = requiredDigest(value.get("sha256"), "jar sha256");
        return new JarEntry(id, version, path, sha256, new LinkedHashMap<>(value));
    }

    private static Map<String, Object> snapshotBinding(Input input) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("bindingVersion", SNAPSHOT_BINDING_VERSION);
        value.put("formatVersion", input.metadata().formatVersion());
        value.put("snapshotId", input.metadata().snapshotId());
        value.put("createdAt", input.metadata().createdAt().toString());
        value.put("manifestHash", input.manifestHash());
        value.put("build", input.metadata().build());
        value.put("catalogChecksum", input.metadata().catalogChecksum());
        value.put("extensionVersions", new TreeMap<>(input.metadata().extensionVersions()));
        value.put(SNAPSHOT_BINDING_HASH, bindingHash(value));
        return value;
    }

    private static Map<String, Object> mergedSnapshotBinding(Object existing, Input input) {
        LinkedHashMap<String, Object> value = existing == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(object(existing, "state snapshot"));
        Map<String, Object> expected = snapshotBinding(input);
        if (value.containsKey("bindingVersion")) {
            requireCompletedBinding(value);
            return value;
        }
        if (value.containsKey(SNAPSHOT_BINDING_HASH)) {
            throw new IllegalArgumentException("Snapshot binding hash exists without a binding version");
        }
        expected.forEach((key, expectedValue) -> {
            if (value.containsKey(key) && !CanonicalJson.canonicalize(value.get(key)).equals(CanonicalJson.canonicalize(expectedValue))) {
                throw new IllegalArgumentException("Snapshot binding conflict for " + key + ": expected "
                    + CanonicalJson.canonicalize(expectedValue) + " but observed " + CanonicalJson.canonicalize(value.get(key)));
            }
            value.put(key, expectedValue);
        });
        return value;
    }

    private static void requireCompletedBinding(Map<String, Object> value) {
        if (positiveInteger(value.get("bindingVersion"), "snapshot binding version") != SNAPSHOT_BINDING_VERSION) {
            throw new IllegalArgumentException("Snapshot binding version is unsupported");
        }
        positiveInteger(value.get("formatVersion"), "snapshot format version");
        text(value.get("snapshotId"), "snapshot id");
        try {
            Instant.parse(text(value.get("createdAt"), "snapshot creation time"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Snapshot creation time is invalid", exception);
        }
        requiredDigest(value.get("manifestHash"), "snapshot manifest hash");
        text(value.get("build"), "snapshot build");
        requiredDigest(value.get("catalogChecksum"), "snapshot catalog checksum");
        Map<String, Object> extensionVersions = object(value.get("extensionVersions"), "snapshot extension versions");
        extensionVersions.forEach((id, version) -> {
            id(id, "snapshot extension id");
            version(version, "snapshot extension version");
        });
        String observedHash = requiredDigest(value.get(SNAPSHOT_BINDING_HASH), "snapshot binding hash");
        LinkedHashMap<String, Object> material = new LinkedHashMap<>();
        for (String key : SNAPSHOT_BINDING_FIELDS) {
            if (!value.containsKey(key)) {
                throw new IllegalArgumentException("Snapshot binding is missing " + key);
            }
            material.put(key, value.get(key));
        }
        String expectedHash = bindingHash(material);
        if (!expectedHash.equals(observedHash)) {
            throw new IllegalArgumentException("Snapshot binding hash does not match its source provenance");
        }
    }

    private static String bindingHash(Map<String, Object> value) {
        return CanonicalJson.sha256("migration.extension-snapshot-binding", value);
    }

    private static Map<String, Object> mergedStateSnapshotBinding(Object existing, Map<String, Object> registrySnapshot) {
        LinkedHashMap<String, Object> value = existing == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(object(existing, "state snapshot"));
        if (value.containsKey("bindingVersion")) {
            requireCompletedBinding(value);
            requireMatchingBinding(registrySnapshot, value);
            return value;
        }
        if (value.containsKey(SNAPSHOT_BINDING_HASH)) {
            throw new IllegalArgumentException("State snapshot binding hash exists without a binding version");
        }
        for (String key : SNAPSHOT_BINDING_FIELDS) {
            Object expectedValue = registrySnapshot.get(key);
            if (value.containsKey(key) && !CanonicalJson.canonicalize(value.get(key)).equals(CanonicalJson.canonicalize(expectedValue))) {
                throw new IllegalArgumentException("State snapshot binding conflict for " + key + ": expected "
                    + CanonicalJson.canonicalize(expectedValue) + " but observed " + CanonicalJson.canonicalize(value.get(key)));
            }
            value.put(key, expectedValue);
        }
        value.put(SNAPSHOT_BINDING_HASH, registrySnapshot.get(SNAPSHOT_BINDING_HASH));
        requireMatchingBinding(registrySnapshot, value);
        return value;
    }

    private static void requireMatchingBinding(Map<String, Object> registrySnapshot, Map<String, Object> stateSnapshot) {
        String expectedHash = requiredDigest(registrySnapshot.get(SNAPSHOT_BINDING_HASH), "registry snapshot binding hash");
        String observedHash = requiredDigest(stateSnapshot.get(SNAPSHOT_BINDING_HASH), "state snapshot binding hash");
        if (!expectedHash.equals(observedHash)) {
            throw new IllegalArgumentException("State snapshot provenance does not match the authoritative registry binding: expected "
                + expectedHash + " but observed " + observedHash);
        }
        for (String key : SNAPSHOT_BINDING_FIELDS) {
            if (!CanonicalJson.canonicalize(registrySnapshot.get(key)).equals(CanonicalJson.canonicalize(stateSnapshot.get(key)))) {
                throw new IllegalArgumentException("State snapshot provenance differs from the authoritative registry binding for " + key);
            }
        }
    }

    private static void addChanged(List<Change> changes, SourceFile source, byte[] target) {
        if (!source.sha256().equals(sha256(target))) {
            changes.add(new Change("extension-state", source.relativePath(), source.relativePath(), target));
        }
    }

    private static boolean isExtensionPath(SourceFile source) {
        return source.relativePath().equals(REGISTRY_PATH) || source.relativePath().startsWith("extensions/");
    }

    private static String statePath(String id) {
        return "extensions/" + id + "/state.json";
    }

    private static String extensionDirectoryId(String path) {
        if (!path.startsWith("extensions/")) {
            return null;
        }
        String relative = path.substring("extensions/".length());
        int separator = relative.indexOf('/');
        return separator < 0 ? relative : relative.substring(0, separator);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String label) {
        if (!(value instanceof Map<?, ?> raw) || raw.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw new IllegalArgumentException(label + " must be an object");
        }
        return (Map<String, Object>) raw;
    }

    private static List<Object> array(Object value, String label) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(label + " must be an array");
        }
        return new ArrayList<>(list);
    }

    private static String text(Object value, String label) {
        if (!(value instanceof String text) || text.isBlank() || text.indexOf('\u0000') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return text;
    }

    private static String optionalText(Object value, String label) {
        return value == null ? null : text(value, label);
    }

    private static String id(Object value, String label) {
        String id = text(value, label);
        if (!id.equals(id.toLowerCase(Locale.ROOT)) || !id.matches("[a-z0-9][a-z0-9._-]*")) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return id;
    }

    private static String optionalId(Object value, String label) {
        return value == null ? null : id(value, label);
    }

    private static String version(Object value, String label) {
        String version = text(value, label);
        CatalogVersionRange.parse(version).includes(version);
        return version;
    }

    private static String path(Object value, String label) {
        String path = text(value, label);
        if (!path.startsWith("extensions/jars/") || path.indexOf('\\') >= 0 || !path.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return MigrationPaths.requireRelative(path);
    }

    private static String optionalPath(Object value, String label) {
        return value == null ? null : path(value, label);
    }

    private static String optionalDigest(Object value, String label) {
        if (value == null) {
            return null;
        }
        String digest = text(value, label).toLowerCase(Locale.ROOT);
        if (!digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return digest;
    }

    private static String requiredDigest(Object value, String label) {
        String digest = optionalDigest(value, label);
        if (digest == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        return digest;
    }

    private static boolean booleanValue(Object value, boolean fallback, String label) {
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Boolean booleanValue)) {
            throw new IllegalArgumentException(label + " must be a boolean");
        }
        return booleanValue;
    }

    private static boolean requiredBoolean(Object value, String label) {
        if (!(value instanceof Boolean booleanValue)) {
            throw new IllegalArgumentException(label + " must be a boolean");
        }
        return booleanValue;
    }

    private static long positiveInteger(Object value, String label) {
        if (!(value instanceof BigDecimal decimal)) {
            throw new IllegalArgumentException(label + " must be a positive integer");
        }
        try {
            long number = decimal.longValueExact();
            if (number < 1) {
                throw new ArithmeticException();
            }
            return number;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(label + " must be a positive integer", exception);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record ExtensionEntry(String id, String version, boolean external, String artifact, List<Dependency> dependencies, Map<String, Object> value) {
        private ExtensionEntry {
            dependencies = List.copyOf(dependencies);
            value = Collections.unmodifiableMap(new LinkedHashMap<>(value));
        }
    }

    private record Dependency(String id, String versionRange, boolean optional, boolean external) {
    }

    private record JarEntry(String id, String version, String path, String sha256, Object value) {
    }

    private record Issue(String path, String sourceHash, String code, String reason, List<String> references, String action) {
        private Issue {
            references = List.copyOf(references);
        }
    }
}

package restudio.resync.upgrade.flow;

import restudio.resync.flow.CoreGraphProjectionContext;
import restudio.resync.flow.catalog.CatalogNodeDescriptor;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class LegacyNodeReferenceResolver {
    private static final int MAX_MIGRATIONS = 4096;
    private static final int MAX_ALIAS_LENGTH = 128;
    private final CoreGraphProjectionContext context;
    private final Map<String, String> migration;
    private final Map<String, CoreGraphProjectionContext.ResolvedNode> references;
    private final Map<String, Set<ContractRef<NodeId>>> localCandidates;
    private final Map<String, Set<ContractRef<NodeId>>> aliases;

    public LegacyNodeReferenceResolver(CoreGraphProjectionContext context) {
        this(context, Map.of());
    }

    public LegacyNodeReferenceResolver(CoreGraphProjectionContext context, Map<String, String> migration) {
        this.context = Objects.requireNonNull(context, "context");
        this.migration = immutableMigration(migration);
        this.references = indexReferences(context);
        this.localCandidates = indexLocalCandidates(references);
        this.aliases = indexMetadataAliases(references);
        validateAliasConflicts();
        validateMigrationTargets();
    }

    public CoreGraphProjectionContext.ResolvedNode resolve(String persistedType) {
        return resolve(persistedType, Optional.empty());
    }

    public CoreGraphProjectionContext.ResolvedNode resolve(String persistedType, OwnerId owner) {
        return resolve(persistedType, Optional.ofNullable(owner));
    }

    public CoreGraphProjectionContext.ResolvedNode resolve(String persistedType, Optional<OwnerId> owner) {
        String persisted = requiredText(persistedType, "Persisted node type");
        Optional<OwnerId> expectedOwner = Objects.requireNonNull(owner, "owner");
        String target = migration.getOrDefault(persisted, persisted);
        if (!target.equals(persisted) && migration.containsKey(target)) {
            throw new IllegalArgumentException("Legacy node migration must resolve in one step: " + persisted);
        }
        Set<ContractRef<NodeId>> candidates = candidates(target);
        if (expectedOwner.isPresent()) {
            candidates = candidates.stream()
                .filter(reference -> reference.owner().equals(expectedOwner.orElseThrow()))
                .collect(Collectors.toUnmodifiableSet());
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("Catalog node is unavailable for persisted type: " + persisted);
        }
        if (candidates.size() != 1) {
            throw new IllegalArgumentException("Persisted node type is ambiguous: " + persisted + " -> "
                + candidates.stream().map(ContractRef::canonicalText).sorted().toList());
        }
        return references.get(candidates.iterator().next().canonicalText());
    }

    public Map<String, String> migration() {
        return migration;
    }

    public CoreGraphProjectionContext context() {
        return context;
    }

    private Set<ContractRef<NodeId>> candidates(String value) {
        Optional<ContractRef<NodeId>> exact = exactReference(value);
        if (exact.isPresent()) {
            return references.containsKey(exact.orElseThrow().canonicalText())
                ? Set.of(exact.orElseThrow()) : Set.of();
        }
        Set<ContractRef<NodeId>> result = new LinkedHashSet<>();
        result.addAll(localCandidates.getOrDefault(value, Set.of()));
        result.addAll(aliases.getOrDefault(value, Set.of()));
        return Set.copyOf(result);
    }

    private void validateMigrationTargets() {
        for (Map.Entry<String, String> entry : migration.entrySet()) {
            if (entry.getKey().equals(entry.getValue())) {
                throw new IllegalArgumentException("Legacy node migration contains a self-cycle: " + entry.getKey());
            }
            if (migration.containsKey(entry.getValue())) {
                throw new IllegalArgumentException("Legacy node migration contains a chain: " + entry.getKey());
            }
            Set<ContractRef<NodeId>> targetCandidates = candidates(entry.getValue());
            if (targetCandidates.isEmpty()) {
                throw new IllegalArgumentException("Legacy node migration target is unavailable: " + entry.getValue());
            }
            if (targetCandidates.size() != 1) {
                throw new IllegalArgumentException("Legacy node migration target is ambiguous: " + entry.getValue());
            }
            Set<ContractRef<NodeId>> directCandidates = candidatesWithoutMigration(entry.getKey());
            if (!directCandidates.isEmpty() && !directCandidates.equals(targetCandidates)) {
                throw new IllegalArgumentException("Legacy node migration conflicts with a catalog alias: " + entry.getKey());
            }
        }
    }

    private void validateAliasConflicts() {
        for (Map.Entry<String, Set<ContractRef<NodeId>>> entry : aliases.entrySet()) {
            Set<ContractRef<NodeId>> local = localCandidates.get(entry.getKey());
            if (local != null && !local.equals(entry.getValue())) {
                throw new IllegalArgumentException("Catalog alias conflicts with a local node identity: " + entry.getKey());
            }
        }
    }

    private Set<ContractRef<NodeId>> candidatesWithoutMigration(String value) {
        Optional<ContractRef<NodeId>> exact = exactReference(value);
        if (exact.isPresent()) {
            return references.containsKey(exact.orElseThrow().canonicalText())
                ? Set.of(exact.orElseThrow()) : Set.of();
        }
        Set<ContractRef<NodeId>> result = new LinkedHashSet<>();
        result.addAll(localCandidates.getOrDefault(value, Set.of()));
        result.addAll(aliases.getOrDefault(value, Set.of()));
        return Set.copyOf(result);
    }

    private static Map<String, CoreGraphProjectionContext.ResolvedNode> indexReferences(CoreGraphProjectionContext context) {
        Map<String, CoreGraphProjectionContext.ResolvedNode> result = new LinkedHashMap<>();
        for (var owned : context.catalog().definitions()) {
            ContractRef<NodeId> reference = ContractRef.of(owned.key().owner(), owned.descriptor().id());
            CoreGraphProjectionContext.ResolvedNode resolved = context.requireNode(reference);
            if (result.putIfAbsent(reference.canonicalText(), resolved) != null) {
                throw new IllegalArgumentException("Duplicate catalog node reference: " + reference.canonicalText());
            }
        }
        return Map.copyOf(result);
    }

    private static Map<String, Set<ContractRef<NodeId>>> indexLocalCandidates(
        Map<String, CoreGraphProjectionContext.ResolvedNode> references) {
        Map<String, Set<ContractRef<NodeId>>> result = new LinkedHashMap<>();
        for (CoreGraphProjectionContext.ResolvedNode node : references.values()) {
            addCandidate(result, node.reference().localId(), node.reference());
        }
        return immutableIndex(result);
    }

    private static Map<String, Set<ContractRef<NodeId>>> indexMetadataAliases(
        Map<String, CoreGraphProjectionContext.ResolvedNode> references) {
        Map<String, Set<ContractRef<NodeId>>> result = new LinkedHashMap<>();
        for (CoreGraphProjectionContext.ResolvedNode node : references.values()) {
            CatalogNodeDescriptor descriptor = node.descriptor();
            Map<?, ?> authored = descriptor.metadata().get("authoredSource") instanceof Map<?, ?> value ? value : Map.of();
            addMetadataAlias(result, authored.get("canonicalId"), node.reference(), "canonicalId");
            addMetadataAlias(result, authored.get("sourceNodeId"), node.reference(), "sourceNodeId");
            addMetadataAliases(result, authored.get("legacyIds"), node.reference(), "legacyIds");
        }
        for (Map.Entry<String, Set<ContractRef<NodeId>>> entry : result.entrySet()) {
            if (entry.getValue().size() > 1) {
                throw new IllegalArgumentException("Catalog alias maps to multiple definitions: " + entry.getKey());
            }
        }
        return immutableIndex(result);
    }

    private static void addMetadataAliases(Map<String, Set<ContractRef<NodeId>>> result, Object value,
                                           ContractRef<NodeId> reference, String field) {
        if (value == null) {
            return;
        }
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                addMetadataAlias(result, item, reference, field);
            }
            return;
        }
        if (value.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(value); index++) {
                addMetadataAlias(result, Array.get(value, index), reference, field);
            }
            return;
        }
        addMetadataAlias(result, value, reference, field);
    }

    private static void addMetadataAlias(Map<String, Set<ContractRef<NodeId>>> result, Object value,
                                         ContractRef<NodeId> reference, String field) {
        if (value == null) {
            return;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("Catalog " + field + " metadata must contain text aliases");
        }
        String alias = requiredText(text, "Catalog " + field + " alias");
        if (alias.indexOf('/') >= 0) {
            throw new IllegalArgumentException("Catalog alias cannot contain an owner separator: " + alias);
        }
        addCandidate(result, alias, reference);
    }

    private static void addCandidate(Map<String, Set<ContractRef<NodeId>>> result, String key,
                                     ContractRef<NodeId> reference) {
        result.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(reference);
    }

    private static Map<String, Set<ContractRef<NodeId>>> immutableIndex(Map<String, Set<ContractRef<NodeId>>> value) {
        Map<String, Set<ContractRef<NodeId>>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Set<ContractRef<NodeId>>> entry : value.entrySet()) {
            result.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        return Map.copyOf(result);
    }

    private static Map<String, String> immutableMigration(Map<String, String> source) {
        Objects.requireNonNull(source, "migration");
        if (source.size() > MAX_MIGRATIONS) {
            throw new IllegalArgumentException("Legacy migration map exceeds " + MAX_MIGRATIONS + " entries");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = requiredText(entry.getKey(), "Legacy migration source");
            String value = requiredText(entry.getValue(), "Legacy migration target");
            if (key.indexOf('/') >= 0 && exactReference(key).isEmpty()) {
                throw new IllegalArgumentException("Legacy migration source is not a canonical reference: " + key);
            }
            if (value.indexOf('/') >= 0 && exactReference(value).isEmpty()) {
                throw new IllegalArgumentException("Legacy migration target is not a canonical reference: " + value);
            }
            String previous = result.putIfAbsent(key, value);
            if (previous != null && !previous.equals(value)) {
                throw new IllegalArgumentException("Legacy migration source has conflicting targets: " + key);
            }
        }
        return Map.copyOf(result);
    }

    private static Optional<ContractRef<NodeId>> exactReference(String value) {
        if (value.indexOf('/') < 0) {
            return Optional.empty();
        }
        return Optional.of(ContractRef.parseCanonicalText(value, NodeId::of));
    }

    private static String requiredText(String value, String field) {
        Objects.requireNonNull(value, field + " is required");
        if (value.isBlank() || value.length() > MAX_ALIAS_LENGTH || !value.equals(value.strip())) {
            throw new IllegalArgumentException(field + " must be non-blank canonical text");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException(field + " must not contain control characters");
            }
        }
        return value;
    }
}

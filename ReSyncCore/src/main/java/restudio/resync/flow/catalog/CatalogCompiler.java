package restudio.resync.flow.catalog;

import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.diagnostic.DiagnosticPhase;
import restudio.resync.flow.diagnostic.DiagnosticProvenance;
import restudio.resync.flow.diagnostic.DiagnosticSet;
import restudio.resync.flow.diagnostic.DiagnosticSeverity;
import restudio.resync.flow.diagnostic.DiagnosticSourceKind;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.inspector.InspectorCapability;
import restudio.resync.flow.inspector.InspectorCondition;
import restudio.resync.flow.inspector.InspectorDescriptor;
import restudio.resync.flow.inspector.InspectorField;
import restudio.resync.flow.inspector.InspectorOptionSource;
import restudio.resync.flow.inspector.InspectorRow;
import restudio.resync.flow.inspector.InspectorSection;
import restudio.resync.flow.inspector.InspectorValidationRule;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.type.ConversionGraph;
import restudio.resync.flow.type.TypeDescriptor;
import restudio.resync.flow.type.TypeReference;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class CatalogCompiler {
    private static final CatalogVersion OPTION_QUERY_SCHEMA_CONTRACT = new CatalogVersion(1, 3);
    private final CatalogVersion contractVersion;
    private final CatalogBindingProof bindingProof;

    public CatalogCompiler() {
        this(new CatalogVersion(1, 0), CatalogBindingProof.unavailable());
    }

    public CatalogCompiler(CatalogVersion contractVersion) {
        this(contractVersion, CatalogBindingProof.unavailable());
    }

    public CatalogCompiler(CatalogVersion contractVersion, CatalogBindingProof bindingProof) {
        this.contractVersion = Objects.requireNonNull(contractVersion, "contractVersion");
        this.bindingProof = Objects.requireNonNull(bindingProof, "bindingProof");
    }

    public CatalogVersion contractVersion() {
        return contractVersion;
    }

    public CatalogCompilationResult compile(Collection<CatalogContribution> input) {
        return compile(input, 1);
    }

    public CatalogCompilationResult compile(Collection<CatalogContribution> input, long generation) {
        Validation validation = new Validation();
        if (generation < 1) {
            validation.error("CATALOG.GENERATION_INVALID", DiagnosticPhase.SEMANTIC, "generation", "Catalog generation must be positive", null, null, null);
            return CatalogCompilationResult.rejected(validation.sorted());
        }
        if (input == null) {
            validation.error("CATALOG.CONTRIBUTION_REJECTED", DiagnosticPhase.SYNTACTIC, "contribution", "Catalog contributions are required", null, null, null);
            return CatalogCompilationResult.rejected(validation.sorted());
        }
        CatalogBindingProof activeBindingProof = Objects.requireNonNull(bindingProof.capture(), "Captured Catalog Binding Proof Is Required");

        List<CatalogContribution> contributions = input.stream().filter(Objects::nonNull).sorted(Comparator.comparing(CatalogContribution::ownerId)).toList();
        if (contributions.size() != input.size()) {
            validation.error("CATALOG.CONTRIBUTION_REJECTED", DiagnosticPhase.SYNTACTIC, "contribution", "Null catalog contributions are not allowed", null, null, null);
        }

        Map<OwnerId, CatalogContribution> byOwner = new HashMap<>();
        for (CatalogContribution contribution : contributions) {
            OwnerId owner = contribution.ownerId();
            if (byOwner.putIfAbsent(owner, contribution) != null) {
                validation.error("CATALOG.IDENTITY_COLLISION", DiagnosticPhase.SEMANTIC, "identity", "Multiple contributions claim the owner namespace", owner, owner.canonicalText(), contribution);
            }
            try {
                CatalogVersionRange.parse(contribution.version());
            } catch (RuntimeException exception) {
                validation.error("CATALOG.VERSION_INVALID", DiagnosticPhase.SYNTACTIC, "contribution", "Contribution version is not valid semantic version text", owner, owner.canonicalText(), contribution);
            }
            if (!contribution.contractRange().contains(contractVersion)) {
                validation.error("CATALOG.CONTRACT_UNSUPPORTED", DiagnosticPhase.CAPABILITY, "contract", "Contribution does not support the active catalog contract", owner, owner.canonicalText(), contribution);
            }
            if (contractVersion.compareTo(OPTION_QUERY_SCHEMA_CONTRACT) < 0) {
                contribution.optionSources().stream().sorted(Comparator.comparing(value -> value.id().canonicalText())).forEach(optionSource ->
                    validation.error("CATALOG.OPTION_SCHEMA_UNSUPPORTED", DiagnosticPhase.CAPABILITY, "optionSource",
                        "Option sources require catalog contract 1.3", owner, optionSource.id().canonicalText(), contribution));
            }
        }

        validateDependencies(contributions, byOwner, validation);

        Map<ContractRef<?>, CatalogNodeDescriptor> nodes = new HashMap<>();
        Map<TypeReference, TypeDescriptor> types = new HashMap<>();
        Map<TypeReference, ConversionGraph.ConversionEdge> conversions = new HashMap<>();
        Map<ContractRef<?>, CatalogCategoryDescriptor> categories = new HashMap<>();
        Map<ContractRef<?>, InspectorDescriptor> inspectors = new HashMap<>();
        Map<ContractRef<?>, CatalogCapabilityDescriptor> capabilities = new HashMap<>();
        Map<String, RuntimeOperationDescriptor> runtimeRequirements = new HashMap<>();
        Map<ContractRef<?>, CatalogMigrationEdge> migrations = new HashMap<>();
        Map<ContractRef<?>, InspectorOptionSource> optionSources = new HashMap<>();
        Map<ContractRef<?>, InspectorValidationRule> validators = new HashMap<>();
        Map<ContractRef<?>, InspectorCapability> editors = new HashMap<>();
        Map<ContractRef<?>, InspectorCapability> previews = new HashMap<>();

        for (CatalogContribution contribution : contributions) {
            OwnerId owner = contribution.ownerId();
            register(contribution.definitions(), value -> ContractRef.of(owner, value.id()), nodes, "definition", validation, owner, contribution);
            registerTypes(contribution.types(), types, validation, owner, contribution);
            registerConversions(contribution.conversions(), conversions, validation, owner, contribution);
            register(contribution.categories(), value -> ContractRef.of(owner, value.id()), categories, "category", validation, owner, contribution);
            register(contribution.inspectors(), value -> ContractRef.of(value.owner(), value.id()), inspectors, "inspector", validation, owner, contribution);
            register(contribution.capabilities(), value -> ContractRef.of(owner, value.id()), capabilities, "capability", validation, owner, contribution);
            register(contribution.optionSources(), value -> ContractRef.of(owner, value.id()), optionSources, "option source", validation, owner, contribution);
            register(contribution.validators(), value -> ContractRef.of(owner, value.id()), validators, "validator", validation, owner, contribution);
            register(contribution.editors(), value -> value.id(), editors, "editor", validation, owner, contribution);
            register(contribution.previews(), value -> value.id(), previews, "preview", validation, owner, contribution);
            for (RuntimeOperationDescriptor requirement : contribution.runtimeRequirements()) {
                if (runtimeRequirements.putIfAbsent(runtimeKey(requirement), requirement) != null) {
                    validation.error("CATALOG.IDENTITY_COLLISION", DiagnosticPhase.SEMANTIC, "identity", "Multiple runtime requirements claim the same capability and operation", owner, runtimeKey(requirement), contribution);
                }
            }
            register(contribution.migrations(), value -> ContractRef.of(owner, value.id()), migrations, "migration", validation, owner, contribution);
        }

        ValidationIndexes indexes = ValidationIndexes.build(nodes, types, conversions, inspectors, optionSources);
        for (CatalogContribution contribution : contributions) {
            validateContribution(contribution, nodes, types, conversions, categories, inspectors, capabilities, runtimeRequirements, migrations, validators, editors, previews, activeBindingProof, indexes, validation);
        }

        Optional<ContentHash> activeManifestHash = activeBindingProof.activeBindingManifestHash();
        if (!runtimeRequirements.isEmpty() && activeManifestHash.isEmpty()) {
            for (CatalogContribution contribution : contributions) {
                if (!contribution.runtimeRequirements().isEmpty()) {
                    validation.error("CATALOG.BINDING_MISSING", DiagnosticPhase.CAPABILITY, "binding",
                        "Runtime-bound catalog contributions require an active runtime binding manifest hash",
                        contribution.ownerId(), contribution.runtimeRequirements().getFirst().operation().canonicalText(), contribution);
                }
            }
        }

        if (validation.hasErrors()) {
            validation.withContributionRejections(contributions);
            return CatalogCompilationResult.rejected(validation.sorted());
        }

        Set<ContractRef<CapabilityId>> minimumClientCapabilities = new HashSet<>();
        for (CatalogContribution contribution : contributions) {
            for (CatalogCapabilityDescriptor capability : contribution.capabilities()) {
                if (!capability.optional()) {
                    minimumClientCapabilities.add(capability.reference(contribution.ownerId()));
                }
            }
        }

        List<Diagnostic> diagnostics = validation.sorted();
        ContentHash bindingManifestHash = activeManifestHash
            .orElseGet(() -> CatalogCanonicalizer.bindingManifestHash(contributions));
        CatalogCanonicalizer.DerivedSnapshot derived = CatalogCanonicalizer.deriveSnapshot(
            generation, contractVersion, contributions, minimumClientCapabilities, diagnostics, bindingManifestHash);
        CatalogSnapshot snapshot = new CatalogSnapshot(
            generation,
            contractVersion,
            minimumClientCapabilities,
            contributions,
            diagnostics,
            derived
        );
        return CatalogCompilationResult.accepted(snapshot, diagnostics);
    }

    private static void validateDependencies(List<CatalogContribution> contributions, Map<OwnerId, CatalogContribution> byOwner, Validation validation) {
        for (CatalogContribution contribution : contributions) {
            for (CatalogDependency dependency : contribution.dependencies()) {
                CatalogContribution target = byOwner.get(dependency.ownerId());
                if (target == null) {
                    if (!dependency.optional()) {
                        validation.error("CATALOG.DEPENDENCY_MISSING", DiagnosticPhase.SEMANTIC, "dependency", "Required catalog dependency is not present", contribution.ownerId(), dependency.ownerId().canonicalText(), contribution);
                    }
                    continue;
                }
                try {
                    if (!CatalogVersionRange.parse(dependency.versionRange()).includes(target.version())) {
                        validation.error("CATALOG.DEPENDENCY_UNSATISFIED", DiagnosticPhase.SEMANTIC, "dependency", "Catalog dependency version is outside its declared range", contribution.ownerId(), dependency.ownerId().canonicalText(), contribution);
                    }
                } catch (RuntimeException exception) {
                    validation.error("CATALOG.DEPENDENCY_RANGE_INVALID", DiagnosticPhase.SYNTACTIC, "dependency", "Catalog dependency version range is invalid", contribution.ownerId(), dependency.ownerId().canonicalText(), contribution);
                }
            }
        }
    }

    private static <T> void register(List<T> values, Function<T, ContractRef<?>> keyFunction, Map<ContractRef<?>, T> target, String kind, Validation validation, OwnerId owner, CatalogContribution contribution) {
        for (T value : values) {
            ContractRef<?> key = keyFunction.apply(value);
            if (target.putIfAbsent(key, value) != null) {
                validation.error("CATALOG.IDENTITY_COLLISION", DiagnosticPhase.SEMANTIC, "identity", "Duplicate " + kind + " identity", owner, key.canonicalText(), contribution);
            }
        }
    }

    private static void registerTypes(List<TypeDescriptor> values, Map<TypeReference, TypeDescriptor> target, Validation validation, OwnerId owner, CatalogContribution contribution) {
        for (TypeDescriptor value : values) {
            if (target.putIfAbsent(value.id(), value) != null) {
                validation.error("CATALOG.IDENTITY_COLLISION", DiagnosticPhase.SEMANTIC, "identity", "Duplicate type identity", owner, value.id().canonicalKey(), contribution);
            }
        }
    }

    private static void registerConversions(List<ConversionGraph.ConversionEdge> values, Map<TypeReference, ConversionGraph.ConversionEdge> target, Validation validation, OwnerId owner, CatalogContribution contribution) {
        for (ConversionGraph.ConversionEdge value : values) {
            if (target.putIfAbsent(value.id(), value) != null) {
                validation.error("CATALOG.IDENTITY_COLLISION", DiagnosticPhase.SEMANTIC, "identity", "Duplicate conversion identity", owner, value.id().canonicalKey(), contribution);
            }
        }
    }

    private static void validateContribution(CatalogContribution contribution, Map<ContractRef<?>, CatalogNodeDescriptor> nodes, Map<TypeReference, TypeDescriptor> types, Map<TypeReference, ConversionGraph.ConversionEdge> conversions, Map<ContractRef<?>, CatalogCategoryDescriptor> categories, Map<ContractRef<?>, InspectorDescriptor> inspectors, Map<ContractRef<?>, CatalogCapabilityDescriptor> capabilities, Map<String, RuntimeOperationDescriptor> runtimeRequirements, Map<ContractRef<?>, CatalogMigrationEdge> migrations, Map<ContractRef<?>, InspectorValidationRule> validators, Map<ContractRef<?>, InspectorCapability> editors, Map<ContractRef<?>, InspectorCapability> previews, CatalogBindingProof bindingProof, ValidationIndexes indexes, Validation validation) {
        OwnerId owner = contribution.ownerId();
        for (String provenanceError : contribution.provenanceErrors()) {
            String[] parts = provenanceError.split(":", 2);
            String code = "CATALOG.CONTRIBUTION_REJECTED";
            String subject = provenanceError;
            validation.error(code, DiagnosticPhase.SEMANTIC, "provenance", "Authored catalog provenance is missing, invalid, or ambiguous", owner, subject, contribution);
        }
        for (InspectorDescriptor inspector : contribution.inspectors()) {
            if (!owner.equals(inspector.owner())) {
                validation.error("CATALOG.OWNER_MISMATCH", DiagnosticPhase.SEMANTIC, "inspector", "Inspector descriptor owner does not match its catalog contribution", owner, inspector.id().value(), contribution);
            }
        }
        uniqueIdentity(contribution.optionSources().stream().map(value -> value.id().canonicalText()).toList(), "option source", owner, contribution, validation);
        uniqueIdentity(contribution.validators().stream().map(value -> value.id().canonicalText()).toList(), "validator", owner, contribution, validation);
        uniqueIdentity(contribution.editors().stream().map(value -> value.id().canonicalText()).toList(), "editor", owner, contribution, validation);
        uniqueIdentity(contribution.previews().stream().map(value -> value.id().canonicalText()).toList(), "preview", owner, contribution, validation);
        for (InspectorOptionSource optionSource : contribution.optionSources()) {
            requireCapability(optionSource.capability(), capabilities, indexes, "CATALOG.SELECTOR_UNRESOLVED", "Option source capability is unresolved", owner, contribution, validation);
        }
        for (InspectorValidationRule validator : contribution.validators()) {
            requireCapability(validator.capability(), capabilities, indexes, "CATALOG.VALIDATOR_UNRESOLVED", "Validator capability is unresolved", owner, contribution, validation);
        }
        for (InspectorCapability editor : contribution.editors()) {
            if (!editor.id().owner().equals(owner)) {
                validation.error("CATALOG.OWNER_MISMATCH", DiagnosticPhase.SEMANTIC, "editor", "Editor capability owner does not match its catalog contribution", owner, editor.id().canonicalText(), contribution);
            }
        }
        for (InspectorCapability preview : contribution.previews()) {
            if (!preview.id().owner().equals(owner)) {
                validation.error("CATALOG.OWNER_MISMATCH", DiagnosticPhase.SEMANTIC, "preview", "Preview capability owner does not match its catalog contribution", owner, preview.id().canonicalText(), contribution);
            }
        }
        for (CatalogNodeDescriptor node : contribution.definitions()) {
            ContractRef<NodeId> nodeKey = ContractRef.of(owner, node.id());
            validateAuthoredHandlerCapability(node, owner, capabilities, contribution, validation);
            if (node.lifecycle() == CatalogNodeDescriptor.Lifecycle.MIGRATION_ONLY) {
                validation.error("CATALOG.LEGACY_ACTIVE", DiagnosticPhase.SEMANTIC, "definition", "Migration-only definitions cannot enter the active catalog", owner, nodeKey.canonicalText(), contribution);
            }
            if (node.lifecycle() == CatalogNodeDescriptor.Lifecycle.RETIRING && node.replacementIdentity() == null) {
                validation.error("CATALOG.REPLACEMENT_MISSING", DiagnosticPhase.SEMANTIC, "definition", "Retiring definitions require a replacement identity", owner, nodeKey.canonicalText(), contribution);
            }
            require(categories, node.category(), "CATALOG.CATEGORY_UNRESOLVED", "Node category is unresolved", owner, contribution, validation);
            requireCapability(node.handler().capability(), capabilities, indexes, "CATALOG.BINDING_MISSING", "Node handler capability is unresolved", owner, contribution, validation);
            RuntimeOperationDescriptor requirement = runtimeRequirements.get(runtimeKey(node.handler().capability(), node.handler().operation()));
            if (requirement == null) {
                validation.error("CATALOG.BINDING_MISSING", DiagnosticPhase.CAPABILITY, "binding", "Node handler capability and operation have no runtime requirement", owner, node.handler().operation().canonicalText(), contribution);
            } else if (!matches(node, requirement)) {
                validation.error("CATALOG.BINDING_MISSING", DiagnosticPhase.CAPABILITY, "binding", "Node handler runtime requirement does not exactly match its pins and semantics", owner, node.handler().operation().canonicalText(), contribution);
            }
            for (ContractRef<CapabilityId> required : node.requiredCapabilities()) {
                requireCapability(required, capabilities, indexes, "CATALOG.CAPABILITY_UNRESOLVED", "Node required capability is unresolved", owner, contribution, validation);
            }
            validateNodeStructure(node, inspectors, capabilities, indexes, owner, contribution, validation);
            if (node.replacementIdentity() != null) {
                require(nodes, node.replacementIdentity(), "CATALOG.REPLACEMENT_UNRESOLVED", "Node replacement identity is unresolved", owner, contribution, validation);
            }
            if (node.preview().capability() != null) {
                requireCapability(node.preview().capability(), capabilities, indexes, "CATALOG.PREVIEW_UNRESOLVED", "Node preview capability is unresolved", owner, contribution, validation);
            }
            if (node.preview().fallback() != null) {
                requireCapability(node.preview().fallback(), capabilities, indexes, "CATALOG.PREVIEW_UNRESOLVED", "Node preview fallback capability is unresolved", owner, contribution, validation);
            }
        }

        for (TypeDescriptor type : contribution.types()) {
            if (!owner.value().equals(type.id().ownerId())) {
                validation.error("CATALOG.OWNER_MISMATCH", DiagnosticPhase.SEMANTIC, "type", "Type descriptor owner does not match its catalog contribution", owner, type.id().canonicalKey(), contribution);
            }
            requireCapability(type.editor(), capabilities, indexes, "CATALOG.EDITOR_UNRESOLVED", "Type editor capability is unresolved", owner, contribution, validation);
        }

        for (ConversionGraph.ConversionEdge conversion : contribution.conversions()) {
            if (!owner.value().equals(conversion.id().ownerId())) {
                validation.error("CATALOG.OWNER_MISMATCH", DiagnosticPhase.SEMANTIC, "conversion", "Conversion descriptor owner does not match its catalog contribution", owner, conversion.id().canonicalKey(), contribution);
            }
            requireCapability(conversion.capability(), capabilities, indexes, "CATALOG.BINDING_MISSING", "Conversion capability is unresolved", owner, contribution, validation);
            if (!runtimeRequirements.containsKey(runtimeKey(conversion.capability(), conversion.operation()))) {
                validation.error("CATALOG.BINDING_MISSING", DiagnosticPhase.CAPABILITY, "binding", "Conversion capability and operation have no runtime requirement", owner, conversion.operation().canonicalText(), contribution);
            }
        }

        for (InspectorDescriptor inspector : contribution.inspectors()) {
            for (InspectorCapability capability : inspector.capabilities()) {
                if (capability.fallbackCapability() != null && !hasInspectorCapability(indexes, capability.fallbackCapability())) {
                    validation.error("CATALOG.CAPABILITY_UNRESOLVED", DiagnosticPhase.CAPABILITY, "inspector", "Inspector fallback capability is unresolved", owner, capability.fallbackCapability().canonicalText(), contribution);
                }
            }
        }

        for (RuntimeOperationDescriptor requirement : contribution.runtimeRequirements()) {
            requireCapability(requirement.capability(), capabilities, indexes, "CATALOG.BINDING_MISSING", "Runtime requirement capability is unresolved", owner, contribution, validation);
            if (!bindingProof.proves(requirement)) {
                validation.error("CATALOG.BINDING_MISSING", DiagnosticPhase.CAPABILITY, "binding", "Runtime requirement has no active execution fingerprint proof", owner, requirement.operation().canonicalText(), contribution);
            }
        }

        for (CatalogMigrationEdge migration : contribution.migrations()) {
            if (migration.toVersion() <= migration.fromVersion() || migration.touchedIds().isEmpty()) {
                validation.error("CATALOG.MIGRATION_BROKEN", DiagnosticPhase.SEMANTIC, "migration", "Migration edge must advance a version and touch at least one identity", owner, migration.id().canonicalText(), contribution);
            }
            if (migration.connectionPolicy() == CatalogMigrationEdge.ConnectionPolicy.QUARANTINE && migration.quarantineCodes().isEmpty()) {
                validation.error("CATALOG.MIGRATION_BROKEN", DiagnosticPhase.SEMANTIC, "migration", "Quarantined migration edges require quarantine codes", owner, migration.id().canonicalText(), contribution);
            }
            for (String touchedId : migration.touchedIds()) {
                if (!containsLocal(indexes.nodeIds(), owner, touchedId) && !containsTypedLocal(indexes.typeIds(), owner, touchedId) && !containsTypedLocal(indexes.conversionIds(), owner, touchedId)) {
                    validation.error("CATALOG.REFERENCE_UNRESOLVED", DiagnosticPhase.SEMANTIC, "migration", "Migration touched identity is unresolved", owner, migration.id().canonicalText(), contribution);
                }
            }
            validateMigrationMappings(migration, owner, nodes, indexes, contribution, validation);
        }
    }

    private static void validateMigrationMappings(
        CatalogMigrationEdge migration,
        OwnerId contributionOwner,
        Map<ContractRef<?>, CatalogNodeDescriptor> nodes,
        ValidationIndexes indexes,
        CatalogContribution contribution,
        Validation validation
    ) {
        List<CatalogMigrationEdge.PinMapping> mappings = migration.pinMappings();
        if (mappings.isEmpty()) {
            return;
        }
        if (migration.ownerId() != null && !contributionOwner.equals(migration.ownerId())) {
            validation.error("CATALOG.OWNER_MISMATCH", DiagnosticPhase.SEMANTIC, "migration", "Migration pin mapping owner does not match its catalog contribution", contributionOwner, migration.id().canonicalText(), contribution);
        }
        OwnerId scopeOwner = migration.ownerId();
        NodeId scopeNode = migration.nodeId();
        Set<DirectionalLegacyPin> sources = new HashSet<>();
        Set<DirectionalPin> targets = new HashSet<>();
        Map<DirectionalLegacyPin, DirectionalPin> edges = new HashMap<>();
        boolean scopeValid = true;
        for (CatalogMigrationEdge.PinMapping mapping : mappings) {
            CatalogMigrationEdge.PinScope scope = mapping.scope();
            if (scopeOwner == null) {
                scopeOwner = scope.ownerId();
            } else if (!scopeOwner.equals(scope.ownerId())) {
                scopeValid = false;
            }
            if (scopeNode == null) {
                scopeNode = scope.nodeId();
            } else if (!scopeNode.equals(scope.nodeId())) {
                scopeValid = false;
            }
            if (scope.sourceSchemaVersion() != migration.fromVersion()
                || scope.targetSchemaVersion() != migration.toVersion()) {
                scopeValid = false;
            }
            DirectionalLegacyPin source = new DirectionalLegacyPin(mapping.source(), mapping.direction());
            DirectionalPin target = new DirectionalPin(mapping.target().value(), mapping.direction());
            if (!sources.add(source)) {
                validation.error("CATALOG.MIGRATION_BROKEN", DiagnosticPhase.SEMANTIC, "migration", "Migration pin mappings cannot collide on a source pin", contributionOwner, migration.id().canonicalText(), contribution);
            }
            if (!targets.add(target)) {
                validation.error("CATALOG.MIGRATION_BROKEN", DiagnosticPhase.SEMANTIC, "migration", "Migration pin mappings cannot collide on a target pin", contributionOwner, migration.id().canonicalText(), contribution);
            }
            edges.put(source, target);
        }
        if (!scopeValid || scopeOwner == null || scopeNode == null) {
            validation.error("CATALOG.MIGRATION_BROKEN", DiagnosticPhase.SEMANTIC, "migration", "Migration pin mappings must share the exact edge scope", contributionOwner, migration.id().canonicalText(), contribution);
            return;
        }
        ContractRef<NodeId> nodeKey = ContractRef.of(scopeOwner, scopeNode);
        CatalogNodeDescriptor scopedDescriptor = nodes.get(nodeKey);
        if (scopedDescriptor == null) {
            validation.error("CATALOG.REFERENCE_UNRESOLVED", DiagnosticPhase.SEMANTIC, "migration", "Migration pin mapping node identity is unresolved", contributionOwner, nodeKey.canonicalText(), contribution);
            return;
        }
        DescriptorPair descriptors = migrationDescriptors(migration, nodeKey, scopedDescriptor, nodes, indexes);
        validateMappedPins(migration, descriptors.source(), sources, true, contributionOwner, contribution, validation);
        validateMappedPins(migration, descriptors.target(), targets, false, contributionOwner, contribution, validation);
        if (hasPinMappingCycle(edges, mappings)) {
            validation.error("CATALOG.MIGRATION_BROKEN", DiagnosticPhase.SEMANTIC, "migration", "Migration pin mappings cannot contain a cycle", contributionOwner, migration.id().canonicalText(), contribution);
        }
    }

    private static DescriptorPair migrationDescriptors(
        CatalogMigrationEdge migration,
        ContractRef<NodeId> nodeKey,
        CatalogNodeDescriptor scopedDescriptor,
        Map<ContractRef<?>, CatalogNodeDescriptor> nodes,
        ValidationIndexes indexes
    ) {
        CatalogNodeDescriptor source = scopedDescriptor.schemaVersion() == migration.fromVersion() ? scopedDescriptor : null;
        CatalogNodeDescriptor target = scopedDescriptor.schemaVersion() == migration.toVersion() ? scopedDescriptor : null;
        if (source != null && source.replacementIdentity() != null) {
            CatalogNodeDescriptor replacement = nodes.get(source.replacementIdentity());
            if (replacement != null && replacement.schemaVersion() == migration.toVersion()) {
                target = replacement;
            }
        }
        if (target != null) {
            CatalogNodeDescriptor candidate = indexes.replacementSources()
                .getOrDefault(nodeKey, Map.of())
                .get(migration.fromVersion());
            if (candidate != null) {
                source = candidate;
            }
        }
        return new DescriptorPair(source, target);
    }

    private static void validateMappedPins(
        CatalogMigrationEdge migration,
        CatalogNodeDescriptor descriptor,
        Set<?> mapped,
        boolean source,
        OwnerId owner,
        CatalogContribution contribution,
        Validation validation
    ) {
        if (descriptor == null) {
            return;
        }
        Map<String, CatalogNodeDescriptor.Direction> directions = new HashMap<>();
        for (CatalogNodeDescriptor.Pin pin : descriptor.pins()) {
            directions.put(pin.id().value(), pin.direction());
        }
        Map<CatalogNodeDescriptor.Direction, Integer> expectedDirections = new HashMap<>();
        for (CatalogNodeDescriptor.Direction direction : directions.values()) {
            expectedDirections.merge(direction, 1, Integer::sum);
        }
        Map<CatalogNodeDescriptor.Direction, Integer> mappedDirections = new HashMap<>();
        for (CatalogMigrationEdge.PinMapping mapping : migration.pinMappings()) {
            String pinId = source ? mapping.source().value() : mapping.target().value();
            CatalogNodeDescriptor.Direction direction = directions.get(pinId);
            if (direction == null) {
                validation.error("CATALOG.PIN_REFERENCE_INVALID", DiagnosticPhase.SEMANTIC, "migration", "Migration pin mapping references an unresolved " + (source ? "source" : "target") + " pin", owner, pinId, contribution);
            } else if (direction != mapping.direction()) {
                validation.error("CATALOG.MIGRATION_BROKEN", DiagnosticPhase.SEMANTIC, "migration", "Migration pin mapping direction does not match its descriptor", owner, pinId, contribution);
            }
            mappedDirections.merge(mapping.direction(), 1, Integer::sum);
        }
        if (source && (directions.size() != mapped.size() || !expectedDirections.equals(mappedDirections))) {
            validation.error("CATALOG.MIGRATION_BROKEN", DiagnosticPhase.SEMANTIC, "migration", "Migration pin mappings must completely cover the available source descriptor pins", owner, descriptor.id().canonicalText(), contribution);
        }
    }

    private static boolean hasPinMappingCycle(Map<DirectionalLegacyPin, DirectionalPin> edges, List<CatalogMigrationEdge.PinMapping> mappings) {
        Set<DirectionalLegacyPin> identityPins = mappings.stream()
            .filter(CatalogMigrationEdge.PinMapping::identityMeaningful)
            .filter(value -> value.source().value().equals(value.target().value()))
            .map(value -> new DirectionalLegacyPin(value.source(), value.direction()))
            .collect(Collectors.toSet());
        Map<DirectionalPin, DirectionalLegacyPin> sourceByValue = new HashMap<>();
        for (DirectionalLegacyPin source : edges.keySet()) {
            sourceByValue.put(new DirectionalPin(source.value().value(), source.direction()), source);
        }
        Set<DirectionalLegacyPin> visitingSources = new HashSet<>();
        Set<DirectionalLegacyPin> visitedSources = new HashSet<>();
        for (DirectionalLegacyPin source : edges.keySet()) {
            if (identityPins.contains(source)) {
                continue;
            }
            if (hasPinMappingCycle(source, edges, sourceByValue, visitingSources, visitedSources)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPinMappingCycle(
        DirectionalLegacyPin source,
        Map<DirectionalLegacyPin, DirectionalPin> edges,
        Map<DirectionalPin, DirectionalLegacyPin> sourceByValue,
        Set<DirectionalLegacyPin> visiting,
        Set<DirectionalLegacyPin> visited
    ) {
        if (visiting.contains(source)) {
            return true;
        }
        if (!visited.add(source)) {
            return false;
        }
        visiting.add(source);
        DirectionalPin target = edges.get(source);
        DirectionalLegacyPin targetSource = target == null ? null : sourceByValue.get(target);
        boolean cycle = targetSource != null && !targetSource.value().value().equals(source.value().value())
            && hasPinMappingCycle(targetSource, edges, sourceByValue, visiting, visited);
        visiting.remove(source);
        return cycle;
    }

    private record ValidationIndexes(
        Map<ContractRef<?>, Map<Integer, CatalogNodeDescriptor>> replacementSources,
        Map<OwnerId, Set<String>> nodeIds,
        Map<OwnerId, Set<String>> typeIds,
        Map<OwnerId, Set<String>> conversionIds,
        Set<ContractRef<CapabilityId>> inspectorCapabilities,
        Set<ContractRef<?>> optionSources,
        Map<ContractRef<?>, Set<String>> inspectorFields
    ) {
        private static ValidationIndexes build(
            Map<ContractRef<?>, CatalogNodeDescriptor> nodes,
            Map<TypeReference, TypeDescriptor> types,
            Map<TypeReference, ConversionGraph.ConversionEdge> conversions,
            Map<ContractRef<?>, InspectorDescriptor> inspectors,
            Map<ContractRef<?>, InspectorOptionSource> optionSources
        ) {
            Map<ContractRef<?>, Map<Integer, CatalogNodeDescriptor>> replacementSources = new HashMap<>();
            Map<OwnerId, Set<String>> nodeIds = new HashMap<>();
            for (Map.Entry<ContractRef<?>, CatalogNodeDescriptor> entry : nodes.entrySet()) {
                ContractRef<?> key = entry.getKey();
                CatalogNodeDescriptor node = entry.getValue();
                nodeIds.computeIfAbsent(key.owner(), ignored -> new HashSet<>()).add(key.id().value());
                if (node.replacementIdentity() != null) {
                    replacementSources.computeIfAbsent(node.replacementIdentity(), ignored -> new HashMap<>())
                        .putIfAbsent(node.schemaVersion(), node);
                }
            }

            Map<OwnerId, Set<String>> typeIds = new HashMap<>();
            for (TypeReference type : types.keySet()) {
                typeIds.computeIfAbsent(OwnerId.of(type.ownerId()), ignored -> new HashSet<>()).add(type.localId());
            }

            Map<OwnerId, Set<String>> conversionIds = new HashMap<>();
            for (TypeReference conversion : conversions.keySet()) {
                conversionIds.computeIfAbsent(OwnerId.of(conversion.ownerId()), ignored -> new HashSet<>()).add(conversion.localId());
            }

            Set<ContractRef<CapabilityId>> inspectorCapabilities = new HashSet<>();
            Set<ContractRef<?>> allOptionSources = new HashSet<>(optionSources.keySet());
            Map<ContractRef<?>, Set<String>> inspectorFields = new HashMap<>();
            for (Map.Entry<ContractRef<?>, InspectorDescriptor> entry : inspectors.entrySet()) {
                InspectorDescriptor descriptor = entry.getValue();
                for (InspectorCapability capability : descriptor.capabilities()) {
                    inspectorCapabilities.add(capability.id());
                }
                for (InspectorOptionSource source : descriptor.optionSources()) {
                    allOptionSources.add(ContractRef.of(descriptor.owner(), source.id()));
                }
                Set<String> fields = new HashSet<>();
                for (InspectorSection section : descriptor.sections()) {
                    for (InspectorRow row : section.rows()) {
                        for (InspectorField field : row.fields()) {
                            collectFields(field, fields);
                        }
                    }
                }
                inspectorFields.put(entry.getKey(), Set.copyOf(fields));
            }

            Map<ContractRef<?>, Map<Integer, CatalogNodeDescriptor>> immutableReplacementSources = new HashMap<>();
            for (Map.Entry<ContractRef<?>, Map<Integer, CatalogNodeDescriptor>> entry : replacementSources.entrySet()) {
                immutableReplacementSources.put(entry.getKey(), Map.copyOf(entry.getValue()));
            }
            return new ValidationIndexes(
                Map.copyOf(immutableReplacementSources),
                immutableOwnerSets(nodeIds),
                immutableOwnerSets(typeIds),
                immutableOwnerSets(conversionIds),
                Set.copyOf(inspectorCapabilities),
                Set.copyOf(allOptionSources),
                Map.copyOf(inspectorFields));
        }

        private static Map<OwnerId, Set<String>> immutableOwnerSets(Map<OwnerId, Set<String>> values) {
            Map<OwnerId, Set<String>> copy = new HashMap<>();
            for (Map.Entry<OwnerId, Set<String>> entry : values.entrySet()) {
                copy.put(entry.getKey(), Set.copyOf(entry.getValue()));
            }
            return Map.copyOf(copy);
        }
    }

    private record DescriptorPair(CatalogNodeDescriptor source, CatalogNodeDescriptor target) {
    }

    private record DirectionalLegacyPin(CatalogMigrationEdge.LegacyPinId value, CatalogNodeDescriptor.Direction direction) {
    }

    private record DirectionalPin(String value, CatalogNodeDescriptor.Direction direction) {
    }

    private static void validateNodeStructure(CatalogNodeDescriptor node, Map<ContractRef<?>, InspectorDescriptor> inspectors, Map<ContractRef<?>, CatalogCapabilityDescriptor> capabilities, ValidationIndexes indexes, OwnerId owner, CatalogContribution contribution, Validation validation) {
        Set<String> branchIds = new HashSet<>();
        unique(node.pins().stream().map(value -> value.id().canonicalText()).toList(), "pin", owner, contribution, validation, new HashSet<>());
        unique(node.modes().stream().map(value -> value.id().canonicalText()).toList(), "mode", owner, contribution, validation, new HashSet<>());
        unique(node.branches().stream().map(value -> value.id().canonicalText()).toList(), "branch", owner, contribution, validation, branchIds);
        unique(node.repeatables().stream().map(value -> value.id().canonicalText()).toList(), "repeatable", owner, contribution, validation, new HashSet<>());
        for (CatalogNodeDescriptor.Branch branch : node.branches()) {
            unique(branch.cases().stream().map(value -> value.id().canonicalText()).toList(), "case", owner, contribution, validation, new HashSet<>());
        }
        for (CatalogNodeDescriptor.Pin pin : node.pins()) {
            requireCapability(pin.editor(), capabilities, indexes, "CATALOG.EDITOR_UNRESOLVED", "Pin editor capability is unresolved", owner, contribution, validation);
            if (pin.optionSource() != null && !hasOptionSource(indexes, pin.optionSource())) {
                validation.error("CATALOG.SELECTOR_UNRESOLVED", DiagnosticPhase.CAPABILITY, "reference", "Pin selector option source is unresolved", owner, pin.optionSource().canonicalText(), contribution);
            }
            validateCondition(pin.visibility(), node, inspectors, indexes, owner, contribution, validation);
        }
        for (CatalogNodeDescriptor.Mode mode : node.modes()) {
            validateCondition(mode.visibility(), node, inspectors, indexes, owner, contribution, validation);
        }
        validateIds(node.semantics().successBranches(), branchIds, "success branch", owner, contribution, validation);
        validateIds(node.semantics().failureBranches(), branchIds, "failure branch", owner, contribution, validation);
        validateIds(node.semantics().cancellationBranches(), branchIds, "cancellation branch", owner, contribution, validation);
        validateIds(node.semantics().failureContract().branches(), branchIds, "failure contract branch", owner, contribution, validation);
    }

    private static void validateCondition(InspectorCondition condition, CatalogNodeDescriptor node, Map<ContractRef<?>, InspectorDescriptor> inspectors, ValidationIndexes indexes, OwnerId owner, CatalogContribution contribution, Validation validation) {
        if (node.inspector() == null) {
            if (!condition.references().isEmpty()) {
                validation.error("CATALOG.PIN_REFERENCE_INVALID", DiagnosticPhase.SEMANTIC, "reference", "Inspector condition references fields without an inspector descriptor", owner, node.id().canonicalText(), contribution);
            }
            return;
        }
        InspectorDescriptor descriptor = inspectors.get(ContractRef.of(owner, node.inspector()));
        if (descriptor == null) {
            validation.error("CATALOG.INSPECTOR_UNRESOLVED", DiagnosticPhase.CAPABILITY, "reference", "Node inspector descriptor is unresolved", owner, node.inspector().value(), contribution);
            return;
        }
        Set<String> fields = indexes.inspectorFields().getOrDefault(ContractRef.of(owner, node.inspector()), Set.of());
        for (var reference : condition.references()) {
            if (!fields.contains(reference.canonicalText())) {
                validation.error("CATALOG.PIN_REFERENCE_INVALID", DiagnosticPhase.SEMANTIC, "reference", "Inspector condition field identity is unresolved", owner, reference.canonicalText(), contribution);
            }
        }
    }

    private static void collectFields(InspectorField field, Set<String> fields) {
        fields.add(field.id().canonicalText());
        for (InspectorField child : field.children()) {
            collectFields(child, fields);
        }
    }

    private static void unique(List<String> values, String kind, OwnerId owner, CatalogContribution contribution, Validation validation, Set<String> all) {
        for (String value : values) {
            if (!all.add(value)) {
                validation.error("CATALOG.STRUCTURE_DUPLICATE", DiagnosticPhase.SEMANTIC, "structure", "Duplicate " + kind + " identity", owner, value, contribution);
            }
        }
    }

    private static void validateAuthoredHandlerCapability(
        CatalogNodeDescriptor node,
        OwnerId owner,
        Map<ContractRef<?>, CatalogCapabilityDescriptor> capabilities,
        CatalogContribution contribution,
        Validation validation
    ) {
        Object sourceValue = node.metadata().get("authoredSource");
        if (!(sourceValue instanceof Map<?, ?> source)) {
            return;
        }
        Object capabilityValue = source.get("handlerCapability");
        if (!(capabilityValue instanceof String authoredCapability) || authoredCapability.isBlank()) {
            validation.error("CATALOG.BINDING_MISSING", DiagnosticPhase.CAPABILITY, "binding",
                "Authored replacement node has no valid handler capability", owner, node.id().value(), contribution);
            return;
        }
        ContractRef<CapabilityId> authoredReference;
        try {
            authoredReference = ContractRef.of(owner, CapabilityId.of(authoredCapability));
        } catch (RuntimeException exception) {
            validation.error("CATALOG.BINDING_MISSING", DiagnosticPhase.CAPABILITY, "binding",
                "Authored replacement handler capability is not a valid Core capability identity", owner,
                node.id().value(), contribution);
            return;
        }
        if (!authoredReference.equals(node.handler().capability())) {
            validation.error("CATALOG.BINDING_MISSING", DiagnosticPhase.CAPABILITY, "binding",
                "Authored replacement handler capability does not match the Core handler reference", owner,
                node.id().value(), contribution);
        }
        if (!capabilities.containsKey(authoredReference)) {
            validation.error("CATALOG.BINDING_MISSING", DiagnosticPhase.CAPABILITY, "binding",
                "Authored replacement handler capability is not registered by the contribution", owner,
                authoredReference.canonicalText(), contribution);
        }
    }

    private static void uniqueIdentity(List<String> values, String kind, OwnerId owner, CatalogContribution contribution, Validation validation) {
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            if (!seen.add(value)) {
                validation.error("CATALOG.IDENTITY_COLLISION", DiagnosticPhase.SEMANTIC, "identity", "Duplicate " + kind + " identity", owner, value, contribution);
            }
        }
    }

    private static void validateIds(Collection<String> values, Set<String> known, String kind, OwnerId owner, CatalogContribution contribution, Validation validation) {
        for (String value : values) {
            if (!known.contains(value)) {
                validation.error("CATALOG.PIN_REFERENCE_INVALID", DiagnosticPhase.SEMANTIC, "reference", "Unresolved " + kind + " identity", owner, value, contribution);
            }
        }
    }

    private static <T> void require(Map<ContractRef<?>, T> values, ContractRef<?> key, String code, String message, OwnerId owner, CatalogContribution contribution, Validation validation) {
        if (key == null || !values.containsKey(key)) {
            validation.error(code, DiagnosticPhase.CAPABILITY, "reference", message, owner, key == null ? null : key.canonicalText(), contribution);
        }
    }

    private static void requireCapability(ContractRef<CapabilityId> key, Map<ContractRef<?>, CatalogCapabilityDescriptor> capabilities, ValidationIndexes indexes, String code, String message, OwnerId owner, CatalogContribution contribution, Validation validation) {
        if (!hasCapability(key, capabilities, indexes)) {
            validation.error(code, DiagnosticPhase.CAPABILITY, "reference", message, owner, key == null ? null : key.canonicalText(), contribution);
        }
    }

    private static boolean hasCapability(ContractRef<CapabilityId> key, Map<ContractRef<?>, CatalogCapabilityDescriptor> capabilities, ValidationIndexes indexes) {
        return key != null && (capabilities.containsKey(key) || hasInspectorCapability(indexes, key));
    }

    private static boolean hasInspectorCapability(ValidationIndexes indexes, ContractRef<CapabilityId> key) {
        return indexes.inspectorCapabilities().contains(key);
    }

    private static boolean hasOptionSource(ValidationIndexes indexes, ContractRef<?> key) {
        return indexes.optionSources().contains(key);
    }

    private static boolean containsLocal(Map<OwnerId, Set<String>> values, OwnerId owner, String localId) {
        return values.getOrDefault(owner, Set.of()).contains(localId);
    }

    private static boolean containsTypedLocal(Map<OwnerId, Set<String>> values, OwnerId owner, String localId) {
        return values.getOrDefault(owner, Set.of()).contains(localId);
    }

    private static String runtimeKey(RuntimeOperationDescriptor requirement) {
        return requirement.capability().canonicalText() + "\0" + requirement.operation().canonicalText();
    }

    private static String runtimeKey(ContractRef<CapabilityId> capability, ContractRef<OperationId> operation) {
        return capability.canonicalText() + "\0" + operation.canonicalText();
    }

    private static boolean matches(CatalogNodeDescriptor node, RuntimeOperationDescriptor requirement) {
        return runtimePins(node).equals(requirement.pins()) && node.semantics().equals(requirement.semantics());
    }

    private static List<RuntimeOperationDescriptor.Pin> runtimePins(CatalogNodeDescriptor node) {
        return node.pins().stream()
            .map(pin -> new RuntimeOperationDescriptor.Pin(pin.id(),
                pin.direction() == CatalogNodeDescriptor.Direction.INPUT
                    ? RuntimeOperationDescriptor.Direction.INPUT
                    : RuntimeOperationDescriptor.Direction.OUTPUT,
                pin.type()))
            .toList();
    }

    private static final class Validation {
        private final List<Diagnostic> diagnostics = new ArrayList<>();
        private final Set<OwnerId> errorOwners = new HashSet<>();
        private final Map<OwnerId, CatalogContribution> contributions = new HashMap<>();

        void error(String code, DiagnosticPhase phase, String stage, String message, OwnerId owner, String subject, CatalogContribution contribution) {
            if (contribution != null) {
                contributions.putIfAbsent(contribution.ownerId(), contribution);
            }
            if (owner != null) {
                errorOwners.add(owner);
            }
            String evidenceSubject = subject == null ? "" : subject;
            String ownerText = owner == null ? "" : owner.canonicalText();
            Diagnostic.Builder builder = Diagnostic.builder(code, DiagnosticSeverity.ERROR, phase, stage)
                .messageKey(ContractRef.of(OwnerId.of("resync.catalog"), CapabilityId.of("diagnostic")))
                .message(message)
                .remediation("Correct the catalog contribution and recompile the catalog.")
                .evidence(Map.of("owner", ownerText, "subject", evidenceSubject))
                .correlationId(UUID.nameUUIDFromBytes((code + "\0" + ownerText + "\0" + evidenceSubject).getBytes(StandardCharsets.UTF_8)));
            if (owner != null) {
                CatalogContribution source = contribution == null ? contributions.get(owner) : contribution;
                if (source != null) {
                    CatalogProvenance provenance = source.provenance();
                    builder.provenance(new DiagnosticProvenance(owner, DiagnosticSourceKind.valueOf(provenance.sourceKind().name()), provenance.sourceUri(), provenance.sourceHash().canonicalText(), provenance.sourceVersion(), provenance.buildId(), null));
                }
            }
            diagnostics.add(builder.build());
        }

        boolean hasErrors() {
            return diagnostics.stream().anyMatch(value -> value.severity() == DiagnosticSeverity.ERROR);
        }

        void withContributionRejections(List<CatalogContribution> values) {
            for (CatalogContribution contribution : values) {
                if (errorOwners.contains(contribution.ownerId())) {
                    error("CATALOG.CONTRIBUTION_REJECTED", DiagnosticPhase.SEMANTIC, "contribution", "Catalog contribution was rejected as a unit", contribution.ownerId(), contribution.ownerId().canonicalText(), contribution);
                }
            }
        }

        List<Diagnostic> sorted() {
            return new DiagnosticSet(diagnostics).diagnostics();
        }
    }
}

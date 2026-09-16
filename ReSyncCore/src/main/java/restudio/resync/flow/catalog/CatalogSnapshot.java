package restudio.resync.flow.catalog;

import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.inspector.InspectorCapability;
import restudio.resync.flow.inspector.InspectorDescriptor;
import restudio.resync.flow.inspector.InspectorOptionSource;
import restudio.resync.flow.inspector.InspectorValidationRule;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.type.ConversionGraph;
import restudio.resync.flow.type.TypeDescriptor;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class CatalogSnapshot {
    private final long generation;
    private final CatalogVersion contractVersion;
    private final ContentHash contentChecksum;
    private final ContentHash bindingManifestHash;
    private final Set<ContractRef<CapabilityId>> minimumClientCapabilities;
    private final List<CatalogContribution> contributions;
    private final List<CatalogOwned<CatalogNodeDescriptor>> definitions;
    private final List<CatalogOwned<TypeDescriptor>> types;
    private final List<CatalogOwned<ConversionGraph.ConversionEdge>> conversions;
    private final List<CatalogOwned<CatalogCategoryDescriptor>> categories;
    private final List<CatalogOwned<InspectorDescriptor>> inspectors;
    private final List<CatalogOwned<CatalogCapabilityDescriptor>> capabilities;
    private final List<CatalogOwned<RuntimeOperationDescriptor>> runtimeRequirements;
    private final List<CatalogOwned<InspectorOptionSource>> optionSources;
    private final List<CatalogOwned<InspectorValidationRule>> validators;
    private final List<CatalogOwned<InspectorCapability>> editors;
    private final List<CatalogOwned<InspectorCapability>> previews;
    private final List<CatalogOwned<CatalogMigrationEdge>> migrations;
    private final List<CatalogProvenance> provenance;
    private final List<Diagnostic> diagnostics;
    private final String canonicalContent;
    private final Map<ContractRef<?>, CatalogOwned<CatalogNodeDescriptor>> definitionsByKey;
    private final Map<ContractRef<?>, CatalogOwned<InspectorDescriptor>> inspectorsByKey;
    private final Map<ContractRef<?>, CatalogOwned<CatalogCapabilityDescriptor>> capabilitiesByKey;
    private final Map<ContractRef<?>, CatalogOwned<InspectorOptionSource>> optionSourcesByKey;
    private final Map<ContractRef<?>, CatalogOwned<InspectorValidationRule>> validatorsByKey;
    private final Map<ContractRef<?>, CatalogOwned<InspectorCapability>> editorsByKey;
    private final Map<ContractRef<?>, CatalogOwned<InspectorCapability>> previewsByKey;

    public CatalogSnapshot(long generation, CatalogVersion contractVersion, ContentHash contentChecksum, ContentHash bindingManifestHash, Set<ContractRef<CapabilityId>> minimumClientCapabilities, List<CatalogContribution> contributions, List<CatalogOwned<CatalogNodeDescriptor>> definitions, List<CatalogOwned<TypeDescriptor>> types, List<CatalogOwned<ConversionGraph.ConversionEdge>> conversions, List<CatalogOwned<CatalogCategoryDescriptor>> categories, List<CatalogOwned<InspectorDescriptor>> inspectors, List<CatalogOwned<CatalogCapabilityDescriptor>> capabilities, List<CatalogOwned<RuntimeOperationDescriptor>> runtimeRequirements, List<CatalogOwned<CatalogMigrationEdge>> migrations, List<CatalogProvenance> provenance, List<Diagnostic> diagnostics, String canonicalContent) {
        this(generation, contractVersion, contentChecksum, bindingManifestHash, minimumClientCapabilities, contributions, definitions, types, conversions, categories, inspectors, capabilities, runtimeRequirements, List.of(), List.of(), List.of(), List.of(), migrations, provenance, diagnostics, canonicalContent);
    }

    public CatalogSnapshot(long generation, CatalogVersion contractVersion, ContentHash contentChecksum, ContentHash bindingManifestHash, Set<ContractRef<CapabilityId>> minimumClientCapabilities, List<CatalogContribution> contributions, List<CatalogOwned<CatalogNodeDescriptor>> definitions, List<CatalogOwned<TypeDescriptor>> types, List<CatalogOwned<ConversionGraph.ConversionEdge>> conversions, List<CatalogOwned<CatalogCategoryDescriptor>> categories, List<CatalogOwned<InspectorDescriptor>> inspectors, List<CatalogOwned<CatalogCapabilityDescriptor>> capabilities, List<CatalogOwned<RuntimeOperationDescriptor>> runtimeRequirements, List<CatalogOwned<InspectorOptionSource>> optionSources, List<CatalogOwned<InspectorValidationRule>> validators, List<CatalogOwned<InspectorCapability>> editors, List<CatalogOwned<InspectorCapability>> previews, List<CatalogOwned<CatalogMigrationEdge>> migrations, List<CatalogProvenance> provenance, List<Diagnostic> diagnostics, String canonicalContent) {
        this(generation, contractVersion, contentChecksum, bindingManifestHash, minimumClientCapabilities, contributions, definitions, types, conversions,
            categories, inspectors, capabilities, runtimeRequirements, optionSources, validators, editors, previews, migrations, provenance,
            diagnostics, canonicalContent, CatalogCanonicalizer.bindingManifestHash(contributions == null ? List.of() : contributions));
    }

    public CatalogSnapshot(long generation, CatalogVersion contractVersion, ContentHash contentChecksum, ContentHash bindingManifestHash, Set<ContractRef<CapabilityId>> minimumClientCapabilities, List<CatalogContribution> contributions, List<CatalogOwned<CatalogNodeDescriptor>> definitions, List<CatalogOwned<TypeDescriptor>> types, List<CatalogOwned<ConversionGraph.ConversionEdge>> conversions, List<CatalogOwned<CatalogCategoryDescriptor>> categories, List<CatalogOwned<InspectorDescriptor>> inspectors, List<CatalogOwned<CatalogCapabilityDescriptor>> capabilities, List<CatalogOwned<RuntimeOperationDescriptor>> runtimeRequirements, List<CatalogOwned<InspectorOptionSource>> optionSources, List<CatalogOwned<InspectorValidationRule>> validators, List<CatalogOwned<InspectorCapability>> editors, List<CatalogOwned<InspectorCapability>> previews, List<CatalogOwned<CatalogMigrationEdge>> migrations, List<CatalogProvenance> provenance, List<Diagnostic> diagnostics, String canonicalContent, ContentHash bindingManifestHashOverride) {
        if (generation < 1) {
            throw new IllegalArgumentException("Catalog generation must be positive");
        }
        this.generation = generation;
        this.contractVersion = Objects.requireNonNull(contractVersion, "contractVersion");
        this.contributions = immutable(contributions).stream().sorted(Comparator.comparing(CatalogContribution::ownerId)).toList();
        this.diagnostics = immutable(diagnostics);
        this.minimumClientCapabilities = minimumCapabilities(this.contributions);
        this.definitions = ownedDefinitions(this.contributions);
        this.types = owned(this.contributions, CatalogContribution::types, value -> CatalogIds.reference(value.descriptor().id()));
        this.conversions = owned(this.contributions, CatalogContribution::conversions, value -> CatalogIds.reference(value.descriptor().id()));
        this.categories = owned(this.contributions, CatalogContribution::categories, value -> ContractRef.of(value.owner(), value.descriptor().id()));
        this.inspectors = owned(this.contributions, CatalogContribution::inspectors, value -> ContractRef.of(value.descriptor().owner(), value.descriptor().id()));
        this.capabilities = owned(this.contributions, CatalogContribution::capabilities, value -> ContractRef.of(value.owner(), value.descriptor().id()));
        this.runtimeRequirements = owned(this.contributions, CatalogContribution::runtimeRequirements, value -> ContractRef.of(value.owner(), CapabilityId.of(runtimeLocalId(value.descriptor()))));
        this.optionSources = owned(this.contributions, CatalogContribution::optionSources, value -> ContractRef.of(value.owner(), value.descriptor().id()));
        this.validators = owned(this.contributions, CatalogContribution::validators, value -> ContractRef.of(value.owner(), value.descriptor().id()));
        this.editors = owned(this.contributions, CatalogContribution::editors, value -> value.descriptor().id());
        this.previews = owned(this.contributions, CatalogContribution::previews, value -> value.descriptor().id());
        this.migrations = owned(this.contributions, CatalogContribution::migrations, value -> ContractRef.of(value.owner(), value.descriptor().id()));
        this.provenance = this.contributions.stream().map(CatalogContribution::provenance).sorted(Comparator.comparing(CatalogProvenance::sourceUri)).toList();
        this.canonicalContent = CatalogCanonicalizer.canonicalSnapshotContent(generation, this.contractVersion, this.contributions, this.minimumClientCapabilities, this.diagnostics,
            Objects.requireNonNull(bindingManifestHashOverride, "bindingManifestHashOverride"));
        this.contentChecksum = CatalogCanonicalizer.contentChecksum(generation, this.contractVersion, this.contributions, this.minimumClientCapabilities, this.diagnostics);
        this.bindingManifestHash = bindingManifestHashOverride;
        requireEqual(Objects.requireNonNull(contentChecksum, "contentChecksum"), this.contentChecksum, "Catalog content checksum does not match derived content");
        requireEqual(Objects.requireNonNull(bindingManifestHash, "bindingManifestHash"), this.bindingManifestHash, "Catalog binding manifest hash does not match derived bindings");
        requireEqual(minimumClientCapabilities == null ? Set.of() : Set.copyOf(minimumClientCapabilities), this.minimumClientCapabilities, "Catalog minimum capabilities do not match derived capabilities");
        requireEqual(CatalogIds.required(canonicalContent, "canonicalContent"), this.canonicalContent, "Catalog canonical content does not match derived state");
        this.definitionsByKey = index(this.definitions);
        this.inspectorsByKey = index(this.inspectors);
        this.capabilitiesByKey = index(this.capabilities);
        this.optionSourcesByKey = index(this.optionSources);
        this.validatorsByKey = index(this.validators);
        this.editorsByKey = index(this.editors);
        this.previewsByKey = index(this.previews);
    }

    CatalogSnapshot(long generation, CatalogVersion contractVersion,
                    Set<ContractRef<CapabilityId>> minimumClientCapabilities,
                    List<CatalogContribution> contributions,
                    List<Diagnostic> diagnostics,
                    CatalogCanonicalizer.DerivedSnapshot derived) {
        CatalogCanonicalizer.DerivedSnapshot result = Objects.requireNonNull(derived, "derived");
        if (generation < 1) {
            throw new IllegalArgumentException("Catalog generation must be positive");
        }
        this.generation = generation;
        this.contractVersion = Objects.requireNonNull(contractVersion, "contractVersion");
        this.contributions = immutable(contributions).stream().sorted(Comparator.comparing(CatalogContribution::ownerId)).toList();
        this.diagnostics = immutable(diagnostics);
        this.minimumClientCapabilities = minimumCapabilities(this.contributions);
        requireEqual(minimumClientCapabilities == null ? Set.of() : Set.copyOf(minimumClientCapabilities), this.minimumClientCapabilities, "Catalog minimum capabilities do not match derived capabilities");
        this.definitions = ownedDefinitions(this.contributions);
        this.types = owned(this.contributions, CatalogContribution::types, value -> CatalogIds.reference(value.descriptor().id()));
        this.conversions = owned(this.contributions, CatalogContribution::conversions, value -> CatalogIds.reference(value.descriptor().id()));
        this.categories = owned(this.contributions, CatalogContribution::categories, value -> ContractRef.of(value.owner(), value.descriptor().id()));
        this.inspectors = owned(this.contributions, CatalogContribution::inspectors, value -> ContractRef.of(value.descriptor().owner(), value.descriptor().id()));
        this.capabilities = owned(this.contributions, CatalogContribution::capabilities, value -> ContractRef.of(value.owner(), value.descriptor().id()));
        this.runtimeRequirements = owned(this.contributions, CatalogContribution::runtimeRequirements, value -> ContractRef.of(value.owner(), CapabilityId.of(runtimeLocalId(value.descriptor()))));
        this.optionSources = owned(this.contributions, CatalogContribution::optionSources, value -> ContractRef.of(value.owner(), value.descriptor().id()));
        this.validators = owned(this.contributions, CatalogContribution::validators, value -> ContractRef.of(value.owner(), value.descriptor().id()));
        this.editors = owned(this.contributions, CatalogContribution::editors, value -> value.descriptor().id());
        this.previews = owned(this.contributions, CatalogContribution::previews, value -> value.descriptor().id());
        this.migrations = owned(this.contributions, CatalogContribution::migrations, value -> ContractRef.of(value.owner(), value.descriptor().id()));
        this.provenance = this.contributions.stream().map(CatalogContribution::provenance).sorted(Comparator.comparing(CatalogProvenance::sourceUri)).toList();
        this.canonicalContent = result.canonicalContent();
        this.contentChecksum = result.contentChecksum();
        this.bindingManifestHash = result.bindingManifestHash();
        this.definitionsByKey = index(this.definitions);
        this.inspectorsByKey = index(this.inspectors);
        this.capabilitiesByKey = index(this.capabilities);
        this.optionSourcesByKey = index(this.optionSources);
        this.validatorsByKey = index(this.validators);
        this.editorsByKey = index(this.editors);
        this.previewsByKey = index(this.previews);
    }

    public static CatalogSnapshot empty(CatalogVersion contractVersion) {
        Set<ContractRef<CapabilityId>> minimum = Set.of();
        String canonical = CatalogCanonicalizer.canonicalSnapshotContent(contractVersion, List.of(), minimum);
        return new CatalogSnapshot(1, contractVersion, CatalogCanonicalizer.contentChecksum(contractVersion, List.of(), minimum), CatalogCanonicalizer.bindingManifestHash(List.of()), minimum, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), canonical);
    }

    public long generation() { return generation; }
    public CatalogVersion contractVersion() { return contractVersion; }
    public ContentHash contentChecksum() { return contentChecksum; }
    public ContentHash checksum() { return contentChecksum; }
    public ContentHash bindingManifestHash() { return bindingManifestHash; }
    public Set<ContractRef<CapabilityId>> minimumClientCapabilities() { return minimumClientCapabilities; }
    public List<CatalogContribution> contributions() { return contributions; }
    public List<CatalogOwned<CatalogNodeDescriptor>> definitions() { return definitions; }
    public List<CatalogOwned<TypeDescriptor>> types() { return types; }
    public List<CatalogOwned<ConversionGraph.ConversionEdge>> conversions() { return conversions; }
    public List<CatalogOwned<CatalogCategoryDescriptor>> categories() { return categories; }
    public List<CatalogOwned<InspectorDescriptor>> inspectors() { return inspectors; }
    public List<CatalogOwned<CatalogCapabilityDescriptor>> capabilities() { return capabilities; }
    public List<CatalogOwned<RuntimeOperationDescriptor>> runtimeRequirements() { return runtimeRequirements; }
    public List<CatalogOwned<InspectorOptionSource>> optionSources() { return optionSources; }
    public List<CatalogOwned<InspectorValidationRule>> validators() { return validators; }
    public List<CatalogOwned<InspectorCapability>> editors() { return editors; }
    public List<CatalogOwned<InspectorCapability>> previews() { return previews; }
    public List<CatalogOwned<CatalogMigrationEdge>> migrations() { return migrations; }
    public List<CatalogProvenance> provenance() { return provenance; }
    public List<Diagnostic> diagnostics() { return diagnostics; }
    public String canonicalContent() { return canonicalContent; }
    public byte[] canonicalBytes() { return canonicalContent.getBytes(StandardCharsets.UTF_8).clone(); }

    public CatalogSnapshot withGeneration(long generation) {
        if (generation < 1L) {
            throw new IllegalArgumentException("Catalog generation must be positive");
        }
        if (this.generation == generation) {
            return this;
        }
        return new CatalogSnapshot(this, generation);
    }

    public Optional<CatalogOwned<CatalogNodeDescriptor>> definition(ContractRef<?> key) { return Optional.ofNullable(definitionsByKey.get(key)); }
    public Optional<CatalogOwned<InspectorDescriptor>> inspector(ContractRef<?> key) { return Optional.ofNullable(inspectorsByKey.get(key)); }
    public Optional<CatalogOwned<CatalogCapabilityDescriptor>> capability(ContractRef<?> key) { return Optional.ofNullable(capabilitiesByKey.get(key)); }
    public Optional<CatalogOwned<InspectorOptionSource>> optionSource(ContractRef<?> key) { return Optional.ofNullable(optionSourcesByKey.get(key)); }
    public Optional<CatalogOwned<InspectorValidationRule>> validator(ContractRef<?> key) { return Optional.ofNullable(validatorsByKey.get(key)); }
    public Optional<CatalogOwned<InspectorCapability>> editor(ContractRef<?> key) { return Optional.ofNullable(editorsByKey.get(key)); }
    public Optional<CatalogOwned<InspectorCapability>> preview(ContractRef<?> key) { return Optional.ofNullable(previewsByKey.get(key)); }

    private CatalogSnapshot(CatalogSnapshot source, long generation) {
        this.generation = generation;
        this.contractVersion = source.contractVersion;
        this.contentChecksum = source.contentChecksum;
        this.bindingManifestHash = source.bindingManifestHash;
        this.minimumClientCapabilities = source.minimumClientCapabilities;
        this.contributions = source.contributions;
        this.definitions = source.definitions;
        this.types = source.types;
        this.conversions = source.conversions;
        this.categories = source.categories;
        this.inspectors = source.inspectors;
        this.capabilities = source.capabilities;
        this.runtimeRequirements = source.runtimeRequirements;
        this.optionSources = source.optionSources;
        this.validators = source.validators;
        this.editors = source.editors;
        this.previews = source.previews;
        this.migrations = source.migrations;
        this.provenance = source.provenance;
        this.diagnostics = source.diagnostics;
        this.canonicalContent = CatalogCanonicalizer.canonicalSnapshotContent(generation, contractVersion,
            contributions, minimumClientCapabilities, diagnostics, bindingManifestHash);
        this.definitionsByKey = source.definitionsByKey;
        this.inspectorsByKey = source.inspectorsByKey;
        this.capabilitiesByKey = source.capabilitiesByKey;
        this.optionSourcesByKey = source.optionSourcesByKey;
        this.validatorsByKey = source.validatorsByKey;
        this.editorsByKey = source.editorsByKey;
        this.previewsByKey = source.previewsByKey;
    }

    private static <T> List<T> immutable(Collection<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static Set<ContractRef<CapabilityId>> minimumCapabilities(List<CatalogContribution> contributions) {
        Set<ContractRef<CapabilityId>> minimum = new HashSet<>();
        for (CatalogContribution contribution : contributions) {
            for (CatalogCapabilityDescriptor capability : contribution.capabilities()) {
                if (!capability.optional()) {
                    minimum.add(capability.reference(contribution.ownerId()));
                }
            }
        }
        return Set.copyOf(minimum);
    }

    private static <T> List<CatalogOwned<T>> owned(List<CatalogContribution> contributions, Function<CatalogContribution, List<T>> values, Function<OwnedValue<T>, ContractRef<?>> key) {
        List<CatalogOwned<T>> result = new ArrayList<>();
        for (CatalogContribution contribution : contributions) {
            for (T descriptor : values.apply(contribution)) {
                OwnedValue<T> value = new OwnedValue<>(contribution.ownerId(), descriptor);
                result.add(new CatalogOwned<>(key.apply(value), descriptor, contribution.provenance()));
            }
        }
        result.sort(Comparator.comparing(value -> value.key().canonicalText()));
        return List.copyOf(result);
    }

    private static List<CatalogOwned<CatalogNodeDescriptor>> ownedDefinitions(List<CatalogContribution> contributions) {
        List<CatalogOwned<CatalogNodeDescriptor>> result = new ArrayList<>();
        for (CatalogContribution contribution : contributions) {
            for (CatalogNodeDescriptor descriptor : contribution.definitions()) {
                result.add(new CatalogOwned<>(ContractRef.of(contribution.ownerId(), descriptor.id()), descriptor, contribution.provenanceFor(descriptor)));
            }
        }
        result.sort(Comparator.comparing(value -> value.key().canonicalText()));
        return List.copyOf(result);
    }

    private static String runtimeLocalId(RuntimeOperationDescriptor requirement) {
        return CatalogIds.local(requirement.capability().id().canonicalText() + "-" + requirement.operation().id().canonicalText(), "runtimeRequirementId");
    }

    private static void requireEqual(Object supplied, Object derived, String message) {
        if (!supplied.equals(derived)) {
            throw new IllegalArgumentException(message);
        }
    }

    private record OwnedValue<T>(OwnerId owner, T descriptor) {
    }

    private static <T> Map<ContractRef<?>, CatalogOwned<T>> index(Collection<CatalogOwned<T>> values) {
        return values.stream().collect(Collectors.toUnmodifiableMap(CatalogOwned::key, Function.identity()));
    }
}

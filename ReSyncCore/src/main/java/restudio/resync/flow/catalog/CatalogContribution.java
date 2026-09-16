package restudio.resync.flow.catalog;

import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.inspector.InspectorCapability;
import restudio.resync.flow.inspector.InspectorDescriptor;
import restudio.resync.flow.inspector.InspectorField;
import restudio.resync.flow.inspector.InspectorOptionSource;
import restudio.resync.flow.inspector.InspectorSelectorField;
import restudio.resync.flow.inspector.InspectorValidationRule;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.type.ConversionGraph;
import restudio.resync.flow.type.TypeDescriptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CatalogContribution {
    private final OwnerId ownerId;
    private final String version;
    private final CatalogContractRange contractRange;
    private final List<CatalogDependency> dependencies;
    private final List<CatalogNodeDescriptor> definitions;
    private final List<TypeDescriptor> types;
    private final List<ConversionGraph.ConversionEdge> conversions;
    private final List<CatalogCategoryDescriptor> categories;
    private final List<InspectorDescriptor> inspectors;
    private final List<CatalogCapabilityDescriptor> capabilities;
    private final List<RuntimeOperationDescriptor> runtimeRequirements;
    private final List<CatalogMigrationEdge> migrations;
    private final List<InspectorOptionSource> optionSources;
    private final List<InspectorValidationRule> validators;
    private final List<InspectorCapability> editors;
    private final List<InspectorCapability> previews;
    private final CatalogProvenance provenance;
    private final Map<String, CatalogProvenance.SourceEntry> definitionProvenance;
    private final List<String> provenanceErrors;

    public CatalogContribution(OwnerId ownerId, String version, CatalogContractRange contractRange, List<CatalogDependency> dependencies, List<CatalogNodeDescriptor> definitions, List<TypeDescriptor> types, List<ConversionGraph.ConversionEdge> conversions, List<CatalogCategoryDescriptor> categories, List<InspectorDescriptor> inspectors, List<CatalogCapabilityDescriptor> capabilities, List<RuntimeOperationDescriptor> runtimeRequirements, List<CatalogMigrationEdge> migrations, CatalogProvenance provenance) {
        this(ownerId, version, contractRange, dependencies, definitions, types, conversions, categories, inspectors, capabilities, runtimeRequirements, migrations, null, null, List.of(), List.of(), provenance);
    }

    public CatalogContribution(OwnerId ownerId, String version, CatalogContractRange contractRange, List<CatalogDependency> dependencies, List<CatalogNodeDescriptor> definitions, List<TypeDescriptor> types, List<ConversionGraph.ConversionEdge> conversions, List<CatalogCategoryDescriptor> categories, List<InspectorDescriptor> inspectors, List<CatalogCapabilityDescriptor> capabilities, List<RuntimeOperationDescriptor> runtimeRequirements, List<CatalogMigrationEdge> migrations, List<InspectorOptionSource> optionSources, List<InspectorValidationRule> validators, List<InspectorCapability> editors, List<InspectorCapability> previews, CatalogProvenance provenance) {
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.version = CatalogIds.text(version, "version", 128);
        this.contractRange = Objects.requireNonNull(contractRange, "contractRange");
        this.dependencies = immutable(dependencies);
        this.definitions = immutable(definitions);
        this.types = immutable(types);
        this.conversions = immutable(conversions);
        this.categories = immutable(categories);
        this.inspectors = immutable(inspectors);
        this.capabilities = immutable(capabilities);
        this.runtimeRequirements = immutable(runtimeRequirements);
        this.migrations = immutable(migrations);
        this.optionSources = immutable(combine(flattenOptionSources(inspectors), optionSources));
        this.validators = immutable(combine(flattenValidators(inspectors), validators));
        this.editors = immutable(editors);
        this.previews = immutable(previews);
        for (InspectorCapability editor : this.editors) {
            if (editor.fallbackCapability() == null) {
                throw new IllegalArgumentException("Editor capabilities require a fallback capability");
            }
        }
        for (InspectorCapability preview : this.previews) {
            if (preview.fallbackCapability() == null) {
                throw new IllegalArgumentException("Preview capabilities require a fallback capability");
            }
        }
        CatalogProvenance suppliedProvenance = Objects.requireNonNull(provenance, "provenance");
        ProvenanceData provenanceData = sourceProvenance(this.definitions, ownerId, suppliedProvenance);
        this.definitionProvenance = provenanceData.entries();
        this.provenanceErrors = provenanceData.errors();
        this.provenance = suppliedProvenance.withEntries(provenanceData.allEntries());
    }

    public OwnerId ownerId() { return ownerId; }
    public String version() { return version; }
    public CatalogContractRange contractRange() { return contractRange; }
    public List<CatalogDependency> dependencies() { return dependencies; }
    public List<CatalogNodeDescriptor> definitions() { return definitions; }
    public List<TypeDescriptor> types() { return types; }
    public List<ConversionGraph.ConversionEdge> conversions() { return conversions; }
    public List<CatalogCategoryDescriptor> categories() { return categories; }
    public List<InspectorDescriptor> inspectors() { return inspectors; }
    public List<CatalogCapabilityDescriptor> capabilities() { return capabilities; }
    public List<RuntimeOperationDescriptor> runtimeRequirements() { return runtimeRequirements; }
    public List<CatalogMigrationEdge> migrations() { return migrations; }
    public List<InspectorOptionSource> optionSources() { return optionSources; }
    public List<InspectorValidationRule> validators() { return validators; }
    public List<InspectorCapability> editors() { return editors; }
    public List<InspectorCapability> previews() { return previews; }
    public CatalogProvenance provenance() { return provenance; }
    public Map<String, CatalogProvenance.SourceEntry> definitionProvenance() { return definitionProvenance; }
    public List<String> provenanceErrors() { return provenanceErrors; }

    public CatalogProvenance provenanceFor(CatalogNodeDescriptor definition) {
        Objects.requireNonNull(definition, "definition");
        CatalogProvenance.SourceEntry entry = definitionProvenance.get(definition.id().value());
        return entry == null ? provenance : provenance.forDefinition(definition.id().value());
    }

    public static Builder builder(OwnerId ownerId, String version, CatalogContractRange contractRange, CatalogProvenance provenance) {
        return new Builder(ownerId, version, contractRange, provenance);
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static ProvenanceData sourceProvenance(List<CatalogNodeDescriptor> definitions, OwnerId owner,
                                                   CatalogProvenance supplied) {
        Map<String, CatalogProvenance.SourceEntry> entries = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        for (CatalogNodeDescriptor definition : definitions) {
            Object authoredValue = definition.metadata().get("authoredSource");
            if (!(authoredValue instanceof Map<?, ?> authored)) {
                continue;
            }
            String nodeId = definition.id().value();
            String authoredId = text(authored.get("id"));
            if (!nodeId.equals(authoredId)) {
                errors.add("CATALOG.PROVENANCE_INVALID:" + nodeId);
                continue;
            }
            Object sourceValue = authored.get("sourceProvenance");
            if (!(sourceValue instanceof Map<?, ?> source)) {
                errors.add("CATALOG.PROVENANCE_MISSING:" + nodeId);
                continue;
            }
            try {
                String sourceUri = text(source.get("sourceUri"));
                String sourceOwner = text(source.get("owner"));
                String sourceHash = text(source.get("sourceHash"));
                Object rowValue = source.get("rowIndex");
                if (!(rowValue instanceof Number number)) {
                    throw new IllegalArgumentException("row");
                }
                int row;
                try {
                    row = new BigDecimal(number.toString()).intValueExact();
                } catch (ArithmeticException exception) {
                    throw new IllegalArgumentException("row", exception);
                }
                if (row < 0 || !owner.value().equals(sourceOwner)) {
                    errors.add("CATALOG.PROVENANCE_OWNER_MISMATCH:" + nodeId);
                    continue;
                }
                CatalogProvenance.SourceEntry entry = new CatalogProvenance.SourceEntry(sourceUri, row, owner, nodeId, ContentHash.parseCanonicalText(sourceHash));
                CatalogProvenance.SourceEntry previous = entries.putIfAbsent(nodeId, entry);
                if (previous != null && !previous.equals(entry)) {
                    errors.add("CATALOG.PROVENANCE_AMBIGUOUS:" + nodeId);
                }
            } catch (RuntimeException exception) {
                errors.add("CATALOG.PROVENANCE_INVALID:" + nodeId);
            }
        }
        for (CatalogProvenance.SourceEntry entry : supplied.entries()) {
            CatalogProvenance.SourceEntry previous = entries.putIfAbsent(entry.definitionId(), entry);
            if (previous != null && !previous.equals(entry)) {
                errors.add("CATALOG.PROVENANCE_AMBIGUOUS:" + entry.definitionId());
            }
        }
        List<CatalogProvenance.SourceEntry> allEntries = new ArrayList<>(entries.values());
        return new ProvenanceData(Collections.unmodifiableMap(new LinkedHashMap<>(entries)), List.copyOf(errors), List.copyOf(allEntries));
    }

    private static String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private record ProvenanceData(Map<String, CatalogProvenance.SourceEntry> entries, List<String> errors,
                                  List<CatalogProvenance.SourceEntry> allEntries) {
    }

    private static List<InspectorOptionSource> flattenOptionSources(List<InspectorDescriptor> values) {
        if (values == null) {
            return List.of();
        }
        List<InspectorOptionSource> result = new ArrayList<>();
        for (InspectorDescriptor descriptor : values) {
            if (descriptor == null) {
                continue;
            }
            List<InspectorOptionSource> local = new ArrayList<>(descriptor.optionSources());
            for (var section : descriptor.sections()) {
                for (var row : section.rows()) {
                    for (InspectorField field : row.fields()) {
                        collectOptionSources(field, local);
                    }
                }
            }
            result.addAll(local);
        }
        return result;
    }

    private static void collectOptionSources(InspectorField field, List<InspectorOptionSource> result) {
        if (field instanceof InspectorSelectorField selector && !result.contains(selector.optionSource())) {
            result.add(selector.optionSource());
        }
        for (InspectorField child : field.children()) {
            collectOptionSources(child, result);
        }
    }

    private static List<InspectorValidationRule> flattenValidators(List<InspectorDescriptor> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(Objects::nonNull).flatMap(value -> value.validationRules().stream()).toList();
    }

    private static <T> List<T> combine(List<T> nested, List<T> declared) {
        List<T> values = new ArrayList<>();
        if (nested != null) {
            values.addAll(nested);
        }
        if (declared != null) {
            values.addAll(declared);
        }
        return values;
    }

    public static final class Builder {
        private final OwnerId ownerId;
        private final String version;
        private final CatalogContractRange contractRange;
        private final CatalogProvenance provenance;
        private List<CatalogDependency> dependencies = List.of();
        private List<CatalogNodeDescriptor> definitions = List.of();
        private List<TypeDescriptor> types = List.of();
        private List<ConversionGraph.ConversionEdge> conversions = List.of();
        private List<CatalogCategoryDescriptor> categories = List.of();
        private List<InspectorDescriptor> inspectors = List.of();
        private List<CatalogCapabilityDescriptor> capabilities = List.of();
        private List<RuntimeOperationDescriptor> runtimeRequirements = List.of();
        private List<CatalogMigrationEdge> migrations = List.of();
        private List<InspectorOptionSource> optionSources;
        private List<InspectorValidationRule> validators;
        private List<InspectorCapability> editors;
        private List<InspectorCapability> previews;

        private Builder(OwnerId ownerId, String version, CatalogContractRange contractRange, CatalogProvenance provenance) {
            this.ownerId = ownerId;
            this.version = version;
            this.contractRange = contractRange;
            this.provenance = provenance;
        }

        public Builder dependencies(List<CatalogDependency> value) { dependencies = value; return this; }
        public Builder definitions(List<CatalogNodeDescriptor> value) { definitions = value; return this; }
        public Builder types(List<TypeDescriptor> value) { types = value; return this; }
        public Builder conversions(List<ConversionGraph.ConversionEdge> value) { conversions = value; return this; }
        public Builder categories(List<CatalogCategoryDescriptor> value) { categories = value; return this; }
        public Builder inspectors(List<InspectorDescriptor> value) { inspectors = value; return this; }
        public Builder capabilities(List<CatalogCapabilityDescriptor> value) { capabilities = value; return this; }
        public Builder runtimeRequirements(List<RuntimeOperationDescriptor> value) { runtimeRequirements = value; return this; }
        public Builder migrations(List<CatalogMigrationEdge> value) { migrations = value; return this; }
        public Builder optionSources(List<InspectorOptionSource> value) { optionSources = value; return this; }
        public Builder validators(List<InspectorValidationRule> value) { validators = value; return this; }
        public Builder editors(List<InspectorCapability> value) { editors = value; return this; }
        public Builder previews(List<InspectorCapability> value) { previews = value; return this; }

        public CatalogContribution build() {
            if (optionSources == null && validators == null && editors == null && previews == null) {
                return new CatalogContribution(ownerId, version, contractRange, dependencies, definitions, types, conversions, categories, inspectors, capabilities, runtimeRequirements, migrations, provenance);
            }
            return new CatalogContribution(ownerId, version, contractRange, dependencies, definitions, types, conversions, categories, inspectors, capabilities, runtimeRequirements, migrations, optionSources, validators, editors, previews, provenance);
        }
    }
}

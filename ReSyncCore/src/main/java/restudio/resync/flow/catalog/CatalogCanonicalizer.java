package restudio.resync.flow.catalog;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.canonical.CanonicalLimits;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.inspector.InspectorCapability;
import restudio.resync.flow.inspector.InspectorBranchCase;
import restudio.resync.flow.inspector.InspectorBranchField;
import restudio.resync.flow.inspector.InspectorCondition;
import restudio.resync.flow.inspector.InspectorConstraint;
import restudio.resync.flow.inspector.InspectorDescriptor;
import restudio.resync.flow.inspector.InspectorField;
import restudio.resync.flow.inspector.InspectorFallback;
import restudio.resync.flow.inspector.InspectorFunctionField;
import restudio.resync.flow.inspector.InspectorFunctionParameter;
import restudio.resync.flow.inspector.InspectorFunctionSignature;
import restudio.resync.flow.inspector.InspectorListField;
import restudio.resync.flow.inspector.InspectorMapField;
import restudio.resync.flow.inspector.InspectorObjectField;
import restudio.resync.flow.inspector.InspectorOptionSource;
import restudio.resync.flow.inspector.InspectorPreviewField;
import restudio.resync.flow.inspector.InspectorRepeatableField;
import restudio.resync.flow.inspector.InspectorRow;
import restudio.resync.flow.inspector.InspectorScalarField;
import restudio.resync.flow.inspector.InspectorSelectorField;
import restudio.resync.flow.inspector.InspectorSection;
import restudio.resync.flow.inspector.InspectorSummaryField;
import restudio.resync.flow.inspector.InspectorTaggedUnionField;
import restudio.resync.flow.inspector.InspectorUnionCase;
import restudio.resync.flow.inspector.InspectorValidationRule;
import restudio.resync.flow.inspector.InspectorValueSchema;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.type.ConversionGraph;
import restudio.resync.flow.type.TypeDescriptor;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.math.BigDecimal;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

public final class CatalogCanonicalizer {
    public static final String CATALOG_DOMAIN = CanonicalJson.CATALOG_HASH_DOMAIN;
    public static final String BINDING_DOMAIN = "catalog.binding";
    private static final CanonicalLimits CATALOG_LIMITS = CanonicalLimits.catalog();

    private CatalogCanonicalizer() {
    }

    public static String canonicalContribution(CatalogContribution contribution) {
        return canonical(canonicalContributionObject(Objects.requireNonNull(contribution, "contribution")));
    }

    public static String canonicalSnapshotContent(Collection<CatalogContribution> contributions, Set<? extends ContractRef<?>> minimumClientCapabilities) {
        return canonicalSnapshotContent(new CatalogVersion(1, 0), contributions, minimumClientCapabilities);
    }

    public static String canonicalSnapshotContent(CatalogVersion contractVersion, Collection<CatalogContribution> contributions, Set<? extends ContractRef<?>> minimumClientCapabilities) {
        return canonicalSnapshotContent(1, contractVersion, contributions, minimumClientCapabilities, List.of());
    }

    public static String canonicalSnapshotContent(long generation, CatalogVersion contractVersion, Collection<CatalogContribution> contributions, Set<? extends ContractRef<?>> minimumClientCapabilities, Collection<Diagnostic> diagnostics) {
        return canonicalSnapshotContent(generation, contractVersion, contributions, minimumClientCapabilities, diagnostics,
            bindingManifestHash(contributions == null ? List.of() : contributions));
    }

    public static String canonicalSnapshotContent(long generation, CatalogVersion contractVersion,
                                                  Collection<CatalogContribution> contributions,
                                                  Set<? extends ContractRef<?>> minimumClientCapabilities,
                                                  Collection<Diagnostic> diagnostics,
                                                  ContentHash bindingManifestHash) {
        return canonical(canonicalSnapshotObject(generation, contractVersion, contributions, minimumClientCapabilities, diagnostics,
            bindingManifestHash));
    }

    static DerivedSnapshot deriveSnapshot(long generation, CatalogVersion contractVersion,
                                          Collection<CatalogContribution> contributions,
                                          Set<? extends ContractRef<?>> minimumClientCapabilities,
                                          Collection<Diagnostic> diagnostics,
                                          ContentHash bindingManifestHash) {
        Objects.requireNonNull(bindingManifestHash, "bindingManifestHash");
        Map<String, Object> base = canonicalSnapshotBaseObject(contractVersion.generation(), contractVersion, contributions,
            minimumClientCapabilities, diagnostics);
        ContentHash contentChecksum = hash(CATALOG_DOMAIN, base);
        String canonicalContent = canonical(canonicalSnapshotObject(generation, base, contentChecksum, bindingManifestHash));
        return new DerivedSnapshot(contentChecksum, bindingManifestHash, canonicalContent);
    }

    public static String canonicalBindingManifest(Collection<CatalogContribution> contributions) {
        return canonical(bindingManifestObject(contributions));
    }

    private static Map<String, Object> bindingManifestObject(Collection<CatalogContribution> contributions) {
        List<Object> capabilities = new ArrayList<>();
        List<Object> requirements = new ArrayList<>();
        if (contributions != null) {
            for (CatalogContribution contribution : contributions.stream().filter(Objects::nonNull).toList()) {
                for (CatalogCapabilityDescriptor capability : contribution.capabilities()) {
                    capabilities.add(object("owner", contribution.ownerId().canonicalText(), "descriptor", canonicalCapabilityObject(capability)));
                }
                for (RuntimeOperationDescriptor requirement : contribution.runtimeRequirements()) {
                    requirements.add(object("owner", contribution.ownerId().canonicalText(), "descriptor", canonicalRuntimeObject(requirement)));
                }
            }
        }
        return object("kind", "binding-manifest", "capabilities", sorted(capabilities), "requirements", sorted(requirements));
    }

    public static ContentHash contentChecksum(Collection<CatalogContribution> contributions, Set<? extends ContractRef<?>> minimumClientCapabilities) {
        return contentChecksum(new CatalogVersion(1, 0), contributions, minimumClientCapabilities);
    }

    public static ContentHash contentChecksum(CatalogVersion contractVersion, Collection<CatalogContribution> contributions, Set<? extends ContractRef<?>> minimumClientCapabilities) {
        return contentChecksum(1, contractVersion, contributions, minimumClientCapabilities, List.of());
    }

    public static ContentHash contentChecksum(long generation, CatalogVersion contractVersion, Collection<CatalogContribution> contributions, Set<? extends ContractRef<?>> minimumClientCapabilities, Collection<Diagnostic> diagnostics) {
        return hash(CATALOG_DOMAIN, canonicalSnapshotBaseObject(contractVersion.generation(), contractVersion, contributions, minimumClientCapabilities, diagnostics));
    }

    public static ContentHash checksumForCanonicalContent(String canonicalContent) {
        Object parsed = CanonicalJson.parse(CatalogIds.required(canonicalContent, "canonicalContent"), CATALOG_LIMITS);
        if (parsed instanceof Map<?, ?> map && map.containsKey("contentChecksum")) {
            Map<String, Object> base = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("Canonical catalog maps require string keys");
                }
                if (!key.equals("contentChecksum") && !key.equals("bindingManifestHash") && !key.equals("generation")) {
                    base.put(key, entry.getValue());
                }
            }
            Object contract = base.get("contractVersion");
            if (contract instanceof Map<?, ?> version && version.get("generation") instanceof Number generation) {
                try {
                    base.put("generation", new BigDecimal(generation.toString()).intValueExact());
                } catch (ArithmeticException exception) {
                    throw new IllegalArgumentException("Catalog contract generation is outside integer range", exception);
                }
            }
            parsed = base;
        }
        return hash(CATALOG_DOMAIN, parsed);
    }

    public static ContentHash bindingManifestHash(Collection<CatalogContribution> contributions) {
        return hash(BINDING_DOMAIN, bindingManifestObject(contributions));
    }

    static Object canonicalReference(ContractRef<?> reference) {
        return reference == null ? null : reference.canonicalValue();
    }

    private static Object canonicalReference(String ownerId, String localId) {
        return object("ownerId", ownerId, "localId", localId);
    }

    static String canonicalNode(CatalogNodeDescriptor node) {
        return canonical(canonicalNodeObject(node));
    }

    public static String canonicalNodeContent(CatalogNodeDescriptor node) {
        return canonicalNode(Objects.requireNonNull(node, "node"));
    }

    public static String canonicalNodeContent(CatalogNodeDescriptor node, CatalogContribution contribution) {
        return canonical(canonicalNodeObject(Objects.requireNonNull(node, "node"), Objects.requireNonNull(contribution, "contribution")));
    }

    public static String canonicalMigrationContent(CatalogMigrationEdge migration) {
        return canonical(canonicalMigrationObject(Objects.requireNonNull(migration, "migration")));
    }

    public static ContentHash migrationChecksum(CatalogMigrationEdge migration) {
        return hash(CATALOG_DOMAIN, canonicalMigrationObject(Objects.requireNonNull(migration, "migration")));
    }

    private static Map<String, Object> canonicalNodeObject(CatalogNodeDescriptor node, CatalogContribution contribution) {
        InspectorDescriptor inspector = findInspector(node, contribution);
        return canonicalNodeObject(node, inspector);
    }

    private static InspectorDescriptor findInspector(CatalogNodeDescriptor node, CatalogContribution contribution) {
        if (node.inspector() == null) {
            return null;
        }
        return contribution.inspectors().stream()
            .filter(value -> value.id().equals(node.inspector()))
            .findFirst()
            .orElse(null);
    }

    private static Map<String, Object> canonicalInspectorLayout(InspectorDescriptor inspector) {
        if (inspector == null) {
            return object("intent", "none", "sections", List.of());
        }
        return object(
            "intent", "generic",
            "id", inspector.id().canonicalText(),
            "title", inspector.title(),
            "description", inspector.description(),
            "sections", inspector.sections().stream().map(CatalogCanonicalizer::canonicalSectionObject).toList(),
            "capabilities", inspector.capabilities().stream().map(CatalogCanonicalizer::canonicalInspectorCapabilityObject).toList(),
            "optionSources", inspector.optionSources().stream().map(CatalogCanonicalizer::canonicalOptionSourceObject).toList(),
            "functionSignatures", inspector.functionSignatures().stream().map(CatalogCanonicalizer::canonicalFunctionSignatureObject).toList(),
            "validationRules", inspector.validationRules().stream().map(CatalogCanonicalizer::canonicalValidationRuleObject).toList());
    }

    private static Map<String, Object> canonicalPreviewIntent(CatalogNodeDescriptor.PreviewIntent preview) {
        return object("intent", preview.intent(), "capability", canonicalReference(preview.capability()), "fallback", canonicalReference(preview.fallback()), "readOnly", preview.readOnly());
    }

    private static List<Object> optionSources(CatalogContribution contribution) {
        return contribution.optionSources().stream()
            .map(CatalogCanonicalizer::canonicalOptionSourceObject)
            .map(value -> (Object) value)
            .toList();
    }

    private static List<Object> validators(CatalogContribution contribution) {
        return contribution.validators().stream()
            .map(CatalogCanonicalizer::canonicalValidationRuleObject)
            .map(value -> (Object) value)
            .toList();
    }

    private static List<Object> editors(CatalogContribution contribution) {
        return contribution.editors().stream()
            .map(CatalogCanonicalizer::canonicalEditorObject)
            .map(value -> (Object) value)
            .toList();
    }

    private static Map<String, Object> owned(OwnerId owner, Map<String, Object> descriptor) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ownerId", owner.canonicalText());
        result.putAll(descriptor);
        return new CanonicalObject(result);
    }

    private static Map<String, Object> canonicalSnapshotObject(long generation, CatalogVersion contractVersion,
                                                               Collection<CatalogContribution> contributions,
                                                               Set<? extends ContractRef<?>> minimumClientCapabilities,
                                                               Collection<Diagnostic> diagnostics,
                                                               ContentHash bindingManifestHash) {
        Map<String, Object> base = canonicalSnapshotBaseObject(contractVersion.generation(), contractVersion, contributions, minimumClientCapabilities, diagnostics);
        ContentHash contentChecksum = hash(CATALOG_DOMAIN, base);
        return canonicalSnapshotObject(generation, base, contentChecksum, bindingManifestHash);
    }

    private static Map<String, Object> canonicalSnapshotObject(long generation, Map<String, Object> base,
                                                               ContentHash contentChecksum,
                                                               ContentHash bindingManifestHash) {
        return object(
            "kind", "snapshot",
            "generation", generation,
            "contractVersion", base.get("contractVersion"),
            "contentChecksum", contentChecksum.canonicalText(),
            "bindingManifestHash", Objects.requireNonNull(bindingManifestHash, "bindingManifestHash").canonicalText(),
            "minimumClientCapabilities", base.get("minimumClientCapabilities"),
            "contributions", base.get("contributions"),
            "definitions", base.get("definitions"),
            "types", base.get("types"),
            "conversions", base.get("conversions"),
            "categories", base.get("categories"),
            "optionSources", base.get("optionSources"),
            "validators", base.get("validators"),
            "editors", base.get("editors"),
            "previews", base.get("previews"),
            "capabilities", base.get("capabilities"),
            "runtimeRequirements", base.get("runtimeRequirements"),
            "provenance", base.get("provenance"),
            "diagnostics", base.get("diagnostics"));
    }

    private static Map<String, Object> canonicalSnapshotBaseObject(long generation, CatalogVersion contractVersion, Collection<CatalogContribution> contributions, Set<? extends ContractRef<?>> minimumClientCapabilities, Collection<Diagnostic> diagnostics) {
        Objects.requireNonNull(contractVersion, "contractVersion");
        if (generation < 1) {
            throw new IllegalArgumentException("Catalog generation must be positive");
        }
        List<CatalogContribution> values = contributions == null ? List.of() : contributions.stream().filter(Objects::nonNull).sorted(Comparator.comparing(CatalogContribution::ownerId)).toList();
        List<Object> contributionObjects = values.stream().map(value -> canonicalContributionObject(value, false)).map(value -> (Object) value).toList();
        List<Object> capabilityObjects = minimumClientCapabilities == null ? List.of() : minimumClientCapabilities.stream().map(CatalogCanonicalizer::canonicalReference).toList();
        List<Object> definitions = new ArrayList<>();
        List<Object> types = new ArrayList<>();
        List<Object> conversions = new ArrayList<>();
        List<Object> categories = new ArrayList<>();
        List<Object> optionSources = new ArrayList<>();
        List<Object> validators = new ArrayList<>();
        List<Object> editors = new ArrayList<>();
        List<Object> previews = new ArrayList<>();
        List<Object> capabilities = new ArrayList<>();
        List<Object> runtimeRequirements = new ArrayList<>();
        List<Object> provenance = new ArrayList<>();
        for (CatalogContribution contribution : values) {
            OwnerId owner = contribution.ownerId();
            contribution.definitions().forEach(value -> definitions.add(owned(owner, canonicalNodeObject(value, contribution))));
            contribution.types().forEach(value -> types.add(owned(owner, canonicalTypeObject(value))));
            contribution.conversions().forEach(value -> conversions.add(owned(owner, canonicalConversionObject(value))));
            contribution.categories().forEach(value -> categories.add(owned(owner, canonicalCategoryObject(value))));
            contribution.optionSources().forEach(value -> optionSources.add(owned(owner, canonicalOptionSourceObject(value))));
            contribution.validators().forEach(value -> validators.add(owned(owner, canonicalValidationRuleObject(value))));
            contribution.editors().forEach(value -> editors.add(owned(owner, canonicalEditorObject(value))));
            contribution.previews().forEach(value -> previews.add(owned(owner, canonicalPreviewObject(value))));
            contribution.capabilities().forEach(value -> capabilities.add(owned(owner, canonicalCapabilityObject(value))));
            contribution.runtimeRequirements().forEach(value -> runtimeRequirements.add(owned(owner, canonicalRuntimeObject(value))));
            provenance.add(canonicalProvenanceObject(contribution.provenance()));
        }
        List<Object> diagnosticObjects = diagnostics == null ? List.of() : diagnostics.stream().filter(Objects::nonNull).map(value -> (Object) canonicalValue(value.toMap())).toList();
        return object(
            "kind", "snapshot",
            "generation", generation,
            "contractVersion", versionObject(contractVersion),
            "minimumClientCapabilities", sorted(capabilityObjects),
            "contributions", sorted(contributionObjects),
            "definitions", sorted(definitions),
            "types", sorted(types),
            "conversions", sorted(conversions),
            "categories", sorted(categories),
            "optionSources", sorted(optionSources),
            "validators", sorted(validators),
            "editors", sorted(editors),
            "previews", sorted(previews),
            "capabilities", sorted(capabilities),
            "runtimeRequirements", sorted(runtimeRequirements),
            "provenance", ordered(provenance),
            "diagnostics", ordered(diagnosticObjects));
    }

    private static Map<String, Object> canonicalContributionObject(CatalogContribution contribution) {
        return canonicalContributionObject(contribution, true);
    }

    private static Map<String, Object> canonicalContributionObject(CatalogContribution contribution, boolean includeRuntimeRequirements) {
        return object(
            "kind", "contribution",
            "ownerId", contribution.ownerId().canonicalText(),
            "version", contribution.version(),
            "contractRange", object("minimum", versionObject(contribution.contractRange().minimum()), "maximum", versionObject(contribution.contractRange().maximum())),
            "dependencies", ordered(contribution.dependencies().stream().map(CatalogCanonicalizer::canonicalDependencyObject).map(value -> (Object) value).toList()),
            "definitions", ordered(contribution.definitions().stream().map(value -> canonicalNodeObject(value, contribution)).map(value -> (Object) value).toList()),
            "types", ordered(contribution.types().stream().map(CatalogCanonicalizer::canonicalTypeObject).map(value -> (Object) value).toList()),
            "conversions", ordered(contribution.conversions().stream().map(CatalogCanonicalizer::canonicalConversionObject).map(value -> (Object) value).toList()),
            "categories", ordered(contribution.categories().stream().map(CatalogCanonicalizer::canonicalCategoryObject).map(value -> (Object) value).toList()),
            "optionSources", ordered(optionSources(contribution)),
            "validators", ordered(validators(contribution)),
            "editors", ordered(editors(contribution)),
            "previews", ordered(contribution.previews().stream().map(CatalogCanonicalizer::canonicalPreviewObject).map(value -> (Object) value).toList()),
            "capabilities", ordered(contribution.capabilities().stream().map(CatalogCanonicalizer::canonicalCapabilityObject).map(value -> (Object) value).toList()),
            "runtimeRequirements", includeRuntimeRequirements ? ordered(contribution.runtimeRequirements().stream().map(CatalogCanonicalizer::canonicalRuntimeObject).map(value -> (Object) value).toList()) : List.of(),
            "migrations", ordered(contribution.migrations().stream().map(CatalogCanonicalizer::canonicalMigrationObject).map(value -> (Object) value).toList()),
            "provenance", canonicalProvenanceObject(contribution.provenance())
        );
    }

    private static Map<String, Object> canonicalNodeObject(CatalogNodeDescriptor node) {
        return canonicalNodeObject(node, (InspectorDescriptor) null);
    }

    private static Map<String, Object> canonicalNodeObject(CatalogNodeDescriptor node, InspectorDescriptor inspector) {
        return object(
            "kind", "node",
            "id", node.id().canonicalText(),
            "schemaVersion", node.schemaVersion(),
            "lifecycle", node.lifecycle().name().toLowerCase(Locale.ROOT).replace('_', '-'),
            "domain", node.domain(),
            "family", node.family(),
            "displayName", node.displayName(),
            "description", node.description(),
            "category", canonicalReference(node.category()),
            "pins", ordered(node.pins().stream().map(CatalogCanonicalizer::canonicalPinObject).map(value -> (Object) value).toList()),
            "modes", ordered(node.modes().stream().map(CatalogCanonicalizer::canonicalModeObject).map(value -> (Object) value).toList()),
            "branches", ordered(node.branches().stream().map(CatalogCanonicalizer::canonicalBranchObject).map(value -> (Object) value).toList()),
            "repeatables", ordered(node.repeatables().stream().map(CatalogCanonicalizer::canonicalRepeatableObject).map(value -> (Object) value).toList()),
            "inspector", canonicalInspectorLayout(inspector),
            "preview", canonicalPreviewIntent(node.preview()),
            "handler", object("capability", canonicalReference(node.handler().capability()), "operation", canonicalReference(node.handler().operation())),
            "semantics", canonicalRuntimeSemantics(node.semantics()),
            "requiredCapabilities", sorted(node.requiredCapabilities().stream().map(CatalogCanonicalizer::canonicalReference).toList()),
            "replacementIdentity", canonicalReference(node.replacementIdentity()),
            "metadata", canonicalValue(node.metadata())
        );
    }

    private static Map<String, Object> canonicalPinObject(CatalogNodeDescriptor.Pin pin) {
        return object("kind", "pin", "id", pin.id().canonicalText(), "direction", pin.direction().name().toLowerCase(Locale.ROOT), "type", json(pin.type().canonicalJson()), "displayName", pin.displayName(), "description", pin.description(), "requirement", pin.requirement().name().toLowerCase(Locale.ROOT), "default", pin.defaultValue() == null ? null : json(pin.defaultValue().canonicalJson()), "editor", canonicalReference(pin.editor()), "optionSource", canonicalReference(pin.optionSource()), "visibility", canonicalConditionObject(pin.visibility()), "repeatable", object("enabled", pin.repeatable().enabled(), "minimum", pin.repeatable().minimum(), "maximum", pin.repeatable().maximum(), "ordered", pin.repeatable().ordered(), "groupId", pin.repeatable().groupId() == null ? null : pin.repeatable().groupId().canonicalText()), "resourceRole", pin.resourceRole(), "presentation", canonicalPinPresentationObject(pin.presentation()));
    }

    private static Map<String, Object> canonicalPinPresentationObject(CatalogNodeDescriptor.PinPresentation presentation) {
        return object("widget", presentation.widget(),
            "options", presentation.options().stream()
                .map(value -> json(value.canonicalJson()))
                .map(value -> (Object) value)
                .toList(),
            "constraints", canonicalValue(presentation.constraints()),
            "visibleWhen", canonicalValue(presentation.visibleWhen()));
    }

    private static Map<String, Object> canonicalModeObject(CatalogNodeDescriptor.Mode mode) {
        return object("kind", "mode", "id", mode.id().canonicalText(), "displayName", mode.displayName(), "description", mode.description(), "visibility", canonicalConditionObject(mode.visibility()), "transformation", canonicalReference(mode.transformation()));
    }

    private static Map<String, Object> canonicalBranchObject(CatalogNodeDescriptor.Branch branch) {
        List<Object> cases = branch.cases().stream().map(value -> object("id", value.id().canonicalText(), "title", value.title(), "description", value.description())).map(value -> (Object) value).toList();
        return object("kind", "branch", "id", branch.id().canonicalText(), "title", branch.title(), "description", branch.description(), "cases", ordered(cases));
    }

    private static Map<String, Object> canonicalRepeatableObject(CatalogNodeDescriptor.RepeatableGroup group) {
        Map<String, Object> values = new LinkedHashMap<>(object("kind", "repeatable", "id", group.id().canonicalText(),
            "title", group.title(), "description", group.description(), "elementType", json(group.elementType().canonicalJson()),
            "minimum", group.minimum(), "maximum", group.maximum(), "ordered", group.ordered()));
        if (!group.members().isEmpty()) {
            values.put("members", ordered(group.members().stream()
                .map(CatalogCanonicalizer::canonicalRepeatableMemberObject).map(value -> (Object) value).toList()));
        }
        return new CanonicalObject(values);
    }

    private static Map<String, Object> canonicalRepeatableMemberObject(CatalogNodeDescriptor.RepeatableMember member) {
        return object("pinId", member.pinId().canonicalText(),
            "direction", member.direction().name().toLowerCase(Locale.ROOT), "type", json(member.type().canonicalJson()));
    }

    private static Map<String, Object> canonicalDependencyObject(CatalogDependency dependency) {
        return object("ownerId", dependency.ownerId().canonicalText(), "versionRange", dependency.versionRange(), "optional", dependency.optional());
    }

    private static Map<String, Object> canonicalTypeObject(TypeDescriptor type) {
        return object("kind", "type", "id", type.id().canonicalValue(), "displayName", type.displayName(), "expression", json(type.expression().canonicalJson()), "literalSchema", type.literalSchema(), "storageCodec", type.storageCodec().id().canonicalValue(), "networkCodec", type.networkCodec().id().canonicalValue(), "editor", canonicalReference(type.editor()), "validators", ordered(type.validators().stream().map(CatalogCanonicalizer::canonicalTypeReference).map(value -> (Object) value).toList()), "transportable", type.transportable(), "persistable", type.persistable());
    }

    private static Map<String, Object> canonicalConversionObject(ConversionGraph.ConversionEdge conversion) {
        return object("kind", "conversion", "id", conversion.id().canonicalValue(), "source", json(conversion.source().canonicalJson()), "target", json(conversion.target().canonicalJson()), "cost", conversion.cost(), "losslessness", conversion.losslessness().wireName(), "failure", conversion.failure().wireName(), "capability", canonicalReference(conversion.capability()), "operation", canonicalReference(conversion.operation()));
    }

    private static Map<String, Object> canonicalCategoryObject(CatalogCategoryDescriptor category) {
        return object("kind", "category", "id", category.id().canonicalText(), "displayName", category.displayName(), "description", category.description(), "order", category.order());
    }

    private static Map<String, Object> canonicalCapabilityObject(CatalogCapabilityDescriptor capability) {
        return object("kind", "capability", "id", capability.id().canonicalText(), "version", capability.version(), "optional", capability.optional(), "fallback", capability.fallback().wireName());
    }

    private static Map<String, Object> canonicalRuntimeObject(RuntimeOperationDescriptor requirement) {
        return object("kind", "runtimeRequirement", "capability", canonicalReference(requirement.capability()), "operation", canonicalReference(requirement.operation()), "inputs", requirement.inputs().stream().map(value -> json(value.canonicalJson())).toList(), "outputs", requirement.outputs().stream().map(value -> json(value.canonicalJson())).toList(), "pins", canonicalRuntimePins(requirement), "semantics", canonicalRuntimeSemantics(requirement.semantics()));
    }

    private static List<Object> canonicalRuntimePins(RuntimeOperationDescriptor requirement) {
        return ordered(requirement.pins().stream()
            .map(CatalogCanonicalizer::canonicalRuntimePinObject)
            .map(value -> (Object) value)
            .toList());
    }

    private static Map<String, Object> canonicalRuntimePinObject(RuntimeOperationDescriptor.Pin pin) {
        return object("id", pin.id().canonicalText(), "direction", pin.direction().name().toLowerCase(Locale.ROOT), "type", json(pin.type().canonicalJson()));
    }

    private static Map<String, Object> canonicalRuntimeSemantics(RuntimeSemantics semantics) {
        return object(
            "effect", semantics.effect().wireValue(),
            "thread", semantics.thread().wireValue(),
            "authorization", canonicalReference(semantics.authorization()),
            "cancellation", semantics.cancellation().wireValue(),
            "timeoutMillis", semantics.timeoutMillis(),
            "drainDeadlineMillis", semantics.drainDeadlineMillis(),
            "hardDeadlineMillis", semantics.hardDeadlineMillis(),
            "unloadPolicy", semantics.unloadPolicy().wireValue(),
            "retry", semantics.retry().wireValue(),
            "idempotency", semantics.idempotency().wireValue(),
            "audit", semantics.audit().wireValue(),
            "confirmation", semantics.confirmation().wireValue(),
            "sensitiveData", semantics.sensitiveData().wireValue(),
            "determinism", semantics.determinism().wireValue(),
            "success", sorted(semantics.successBranches()),
            "failure", sorted(semantics.failureBranches()),
            "cancelled", sorted(semantics.cancellationBranches()),
            "failureContract", json(semantics.failureContract().canonical()),
            "resourceReads", sorted(semantics.resourceReads()),
            "resourceWrites", sorted(semantics.resourceWrites()));
    }

    private static Map<String, Object> canonicalMigrationObject(CatalogMigrationEdge migration) {
        Map<String, Object> known = object(
            "kind", migration.kind().name().toLowerCase(Locale.ROOT).replace('_', '-'),
            "id", migration.id().canonicalText(),
            "fromVersion", migration.fromVersion(),
            "toVersion", migration.toVersion(),
            "touchedIds", migration.touchedIds(),
            "connectionPolicy", migration.connectionPolicy().name().toLowerCase(Locale.ROOT),
            "quarantineCodes", migration.quarantineCodes(),
            "pinMappings", sorted(migration.pinMappings().stream().map(CatalogCanonicalizer::canonicalPinMappingObject).map(value -> (Object) value).toList()),
            "ownerId", migration.ownerId() == null ? null : migration.ownerId().canonicalText(),
            "nodeId", migration.nodeId() == null ? null : migration.nodeId().canonicalText());
        return mergeUnknown(migration.unknown(), known);
    }

    private static Map<String, Object> canonicalPinMappingObject(CatalogMigrationEdge.PinMapping mapping) {
        Map<String, Object> known = object(
            "kind", "pin-mapping",
            "ownerId", mapping.ownerId().canonicalText(),
            "nodeId", mapping.nodeId().canonicalText(),
            "sourceSchemaVersion", mapping.sourceSchemaVersion(),
            "targetSchemaVersion", mapping.targetSchemaVersion(),
            "sourcePinId", mapping.source().canonicalText(),
            "targetPinId", mapping.target().canonicalText(),
            "direction", mapping.direction().name().toLowerCase(Locale.ROOT),
            "identityMeaningful", mapping.identityMeaningful());
        return mergeUnknown(mapping.unknown(), known);
    }

    private static Map<String, Object> mergeUnknown(Map<String, Object> unknown, Map<String, Object> known) {
        if (unknown == null || unknown.isEmpty()) {
            return known;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : unknown.entrySet()) {
            if (known.containsKey(entry.getKey())) {
                throw new IllegalArgumentException("Unknown data collides with known canonical field: " + entry.getKey());
            }
            result.put(entry.getKey(), canonicalValue(entry.getValue()));
        }
        result.putAll(known);
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> canonicalProvenanceObject(CatalogProvenance provenance) {
        List<Object> entries = provenance.entries().stream().map(value -> object(
            "sourceUri", value.sourceUri(),
            "rowIndex", value.rowIndex(),
            "owner", value.owner().canonicalText(),
            "definitionId", value.definitionId(),
            "sourceHash", value.sourceHash().canonicalText())).map(value -> (Object) value).toList();
        return object("sourceKind", provenance.sourceKind().name().toLowerCase(Locale.ROOT), "sourceUri", provenance.sourceUri(), "sourceHash", provenance.sourceHash().canonicalText(), "sourceVersion", provenance.sourceVersion(), "buildId", provenance.buildId(), "loadedAt", provenance.loadedAt(), "entries", entries);
    }

    private static Map<String, Object> canonicalSectionObject(InspectorSection section) {
        return object("id", section.id().canonicalText(), "title", section.title(), "description", section.description(), "visibility", canonicalConditionObject(section.visibility()), "rows", section.rows().stream().map(CatalogCanonicalizer::canonicalRowObject).toList());
    }

    private static Map<String, Object> canonicalRowObject(InspectorRow row) {
        return object("id", row.id().canonicalText(), "title", row.title(), "description", row.description(), "visibility", canonicalConditionObject(row.visibility()), "fields", row.fields().stream().map(CatalogCanonicalizer::canonicalInspectorFieldObject).toList());
    }

    private static Map<String, Object> canonicalInspectorFieldObject(InspectorField field) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", field.id().canonicalText());
        values.put("kind", field.kind().wireName());
        values.put("title", field.title());
        values.put("description", field.description());
        values.put("valueType", json(field.valueType().canonicalJson()));
        values.put("editor", canonicalReference(field.editor().id()));
        values.put("visibility", canonicalConditionObject(field.visibility()));
        values.put("fallback", field.fallback().wireName());
        if (field instanceof InspectorScalarField scalar) {
            values.put("required", scalar.required());
            values.put("defaultValue", scalar.defaultValue() == null ? null : json(scalar.defaultValue().canonicalJson()));
            values.put("constraints", scalar.constraints().stream().map(CatalogCanonicalizer::canonicalConstraintObject).toList());
        } else if (field instanceof InspectorSelectorField selector) {
            values.put("optionSource", canonicalOptionSourceObject(selector.optionSource()));
            values.put("searchable", selector.searchable());
            values.put("allowAbsent", selector.allowAbsent());
        } else if (field instanceof InspectorListField list) {
            values.put("elementType", json(list.elementType().canonicalJson()));
            values.put("minimum", list.minimum());
            values.put("maximum", list.maximum());
            values.put("ordered", list.ordered());
            values.put("element", list.element() == null ? null : canonicalInspectorFieldObject(list.element()));
        } else if (field instanceof InspectorMapField map) {
            values.put("keyType", json(map.keyType().canonicalJson()));
            values.put("mapValueType", json(map.mapValueType().canonicalJson()));
            values.put("minimum", map.minimum());
            values.put("maximum", map.maximum());
            values.put("value", map.value() == null ? null : canonicalInspectorFieldObject(map.value()));
        } else if (field instanceof InspectorObjectField object) {
            values.put("fields", object.fields().stream().map(CatalogCanonicalizer::canonicalInspectorFieldObject).toList());
        } else if (field instanceof InspectorTaggedUnionField union) {
            values.put("cases", union.cases().stream().map(CatalogCanonicalizer::canonicalUnionCaseObject).toList());
        } else if (field instanceof InspectorRepeatableField repeatable) {
            values.put("groupId", repeatable.groupId().canonicalText());
            values.put("minimum", repeatable.minimum());
            values.put("maximum", repeatable.maximum());
            values.put("ordered", repeatable.ordered());
            values.put("element", canonicalInspectorFieldObject(repeatable.element()));
        } else if (field instanceof InspectorBranchField branch) {
            values.put("branchId", branch.branchId().canonicalText());
            values.put("selectorField", branch.selectorField().canonicalText());
            values.put("cases", branch.cases().stream().map(CatalogCanonicalizer::canonicalBranchCaseObject).toList());
        } else if (field instanceof InspectorFunctionField function) {
            values.put("signature", canonicalFunctionSignatureObject(function.signature()));
        } else if (field instanceof InspectorSummaryField summary) {
            values.put("sources", summary.sources().stream().map(value -> value.canonicalText()).toList());
            values.put("presentation", summary.presentation());
        } else if (field instanceof InspectorPreviewField preview) {
            values.put("previewCapability", canonicalInspectorCapabilityObject(preview.previewCapability()));
            values.put("sources", preview.sources().stream().map(value -> value.canonicalText()).toList());
            values.put("readOnly", preview.readOnly());
        } else if (!field.children().isEmpty()) {
            values.put("children", field.children().stream().map(CatalogCanonicalizer::canonicalInspectorFieldObject).toList());
        }
        return Collections.unmodifiableMap(values);
    }

    private static Map<String, Object> canonicalUnionCaseObject(InspectorUnionCase value) {
        return object("id", value.id().canonicalText(), "title", value.title(), "description", value.description(), "type", json(value.type().canonicalJson()), "field", canonicalInspectorFieldObject(value.field()), "visibility", canonicalConditionObject(value.visibility()));
    }

    private static Map<String, Object> canonicalBranchCaseObject(InspectorBranchCase value) {
        return object("id", value.id().canonicalText(), "title", value.title(), "description", value.description(), "when", canonicalConditionObject(value.when()), "fields", value.fields().stream().map(CatalogCanonicalizer::canonicalInspectorFieldObject).toList());
    }

    private static Map<String, Object> canonicalInspectorCapabilityObject(InspectorCapability capability) {
        return object(
            "id", canonicalReference(capability.id()),
            "title", capability.title(),
            "description", capability.description(),
            "inputSchema", canonicalSchemaObject(capability.inputSchema()),
            "outputSchema", canonicalSchemaObject(capability.outputSchema()),
            "constraints", capability.constraints().stream().map(CatalogCanonicalizer::canonicalConstraintObject).toList(),
            "fallback", canonicalReference(capability.fallbackCapability()),
            "readOnlyFallback", readOnlyFallback(capability.fallback()),
            "draftSerialization", object("format", capability.draftSerialization().format(), "version", capability.draftSerialization().version(), "canonical", capability.draftSerialization().canonical(), "preservesUnknown", capability.draftSerialization().preservesUnknown()),
            "validation", object("phases", capability.validation().phases().stream().map(value -> value.wireName()).toList(), "preserveDraftOnFailure", capability.validation().preserveDraftOnFailure(), "rejectUnknownFields", capability.validation().rejectUnknownFields()));
    }

    private static Map<String, Object> canonicalEditorObject(InspectorCapability capability) {
        return canonicalInspectorCapabilityObject(capability);
    }

    private static Map<String, Object> canonicalPreviewObject(InspectorCapability capability) {
        return object("id", canonicalReference(capability.id()), "title", capability.title(), "description", capability.description(), "inputSchema", canonicalSchemaObject(capability.inputSchema()), "outputSchema", canonicalSchemaObject(capability.outputSchema()), "readOnly", true, "fallback", canonicalReference(capability.fallbackCapability()));
    }

    private static String readOnlyFallback(InspectorFallback fallback) {
        return switch (fallback) {
            case GENERIC, READ_ONLY_FIELD -> "field";
            case READ_ONLY_NODE -> "node";
            case READ_ONLY_GRAPH -> "graph";
            case REJECT -> "reject";
        };
    }

    private static Map<String, Object> canonicalSchemaObject(InspectorValueSchema schema) {
        return object("type", json(schema.type().canonicalJson()), "constraints", schema.constraints().stream().map(CatalogCanonicalizer::canonicalConstraintObject).toList());
    }

    private static Map<String, Object> canonicalConstraintObject(InspectorConstraint constraint) {
        return object("id", constraint.id(), "title", constraint.title(), "description", constraint.description(), "parameters", canonicalValue(constraint.parameters()));
    }

    private static Object canonicalConstraintReference(InspectorConstraint constraint) {
        return canonicalReference(constraint.id());
    }

    private static Map<String, Object> canonicalOptionSourceObject(InspectorOptionSource source) {
        return object("id", source.id().canonicalText(), "title", source.title(), "description", source.description(), "valueType", json(source.optionType().canonicalJson()), "querySchema", canonicalValue(source.querySchema().canonicalValue()), "capability", canonicalReference(source.capability()), "pageLimit", source.pageLimit(), "invalidationKey", source.invalidationKey());
    }

    private static Map<String, Object> canonicalValidationRuleObject(InspectorValidationRule rule) {
        return object("id", rule.id().canonicalText(), "phase", rule.phase().wireName(), "stage", rule.stage().canonicalText(), "appliesTo", rule.appliesTo().stream().map(value -> canonicalReference(rule.capability().owner().canonicalText(), value.canonicalText())).toList(), "capability", canonicalReference(rule.capability()), "diagnosticCodes", rule.diagnosticCodes());
    }

    private static Map<String, Object> canonicalFunctionSignatureObject(InspectorFunctionSignature signature) {
        return object("id", signature.id().canonicalText(), "title", signature.title(), "description", signature.description(), "parameters", signature.parameters().stream().map(CatalogCanonicalizer::canonicalFunctionParameterObject).toList(), "returnType", json(signature.returnType().canonicalJson()), "visibility", canonicalConditionObject(signature.visibility()));
    }

    private static Map<String, Object> canonicalFunctionParameterObject(InspectorFunctionParameter parameter) {
        return object("id", parameter.id().canonicalText(), "title", parameter.title(), "description", parameter.description(), "type", json(parameter.type().canonicalJson()), "required", parameter.required(), "defaultValue", parameter.defaultValue() == null ? null : json(parameter.defaultValue().canonicalJson()));
    }

    private static Map<String, Object> canonicalConditionObject(InspectorCondition condition) {
        if (condition instanceof InspectorCondition.Always) {
            return object("kind", "always");
        }
        if (condition instanceof InspectorCondition.Present present) {
            return object("kind", "present", "fieldId", present.field().canonicalText());
        }
        if (condition instanceof InspectorCondition.Equals equals) {
            return object("kind", "equals", "fieldId", equals.field().canonicalText(), "value", json(equals.value().canonicalJson()));
        }
        if (condition instanceof InspectorCondition.NotEquals notEquals) {
            return object("kind", "not-equals", "fieldId", notEquals.field().canonicalText(), "value", json(notEquals.value().canonicalJson()));
        }
        if (condition instanceof InspectorCondition.All all) {
            return object("kind", "all", "children", all.conditions().stream().map(CatalogCanonicalizer::canonicalConditionObject).toList());
        }
        if (condition instanceof InspectorCondition.Any any) {
            return object("kind", "any", "children", any.conditions().stream().map(CatalogCanonicalizer::canonicalConditionObject).toList());
        }
        return object("kind", "not", "children", List.of(canonicalConditionObject(((InspectorCondition.Not) condition).condition())));
    }

    private static Object canonicalTypeReference(TypeReference reference) {
        return reference.canonicalValue();
    }

    private static Object canonicalValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof CanonicalObject || value instanceof CanonicalList) {
            return value;
        }
        if (value instanceof UUID uuid) {
            return uuid.toString();
        }
        if (value instanceof TypedValue typedValue) {
            return json(typedValue.canonicalJson());
        }
        if (value instanceof TypeExpr type) {
            return json(type.canonicalJson());
        }
        if (value instanceof ContractRef<?> reference) {
            return canonicalReference(reference);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("Canonical catalog maps require string keys");
                }
                result.put(key, canonicalValue(entry.getValue()));
            }
            return new CanonicalObject(result);
        }
        if (value instanceof Collection<?> collection) {
            return new CanonicalList(collection.stream().map(CatalogCanonicalizer::canonicalValue).toList());
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        throw new IllegalArgumentException("Unsupported catalog canonical value");
    }

    private static Object json(String canonicalJson) {
        return CanonicalJson.parse(Objects.requireNonNull(canonicalJson, "canonicalJson"));
    }

    private static String canonical(Object value) {
        return CanonicalJson.canonicalize(value, CATALOG_LIMITS);
    }

    private static Map<String, Object> versionObject(CatalogVersion version) {
        return object("generation", version.generation(), "minor", version.minor());
    }

    private static ContentHash hash(String domain, Object value) {
        return ContentHash.of(CanonicalJson.sha256(domain, value, CATALOG_LIMITS));
    }

    private static List<Object> sorted(Collection<?> values) {
        List<CanonicalJson.CanonicalFragment> canonicalValues = new ArrayList<>(values.size());
        for (Object value : values) {
            canonicalValues.add(CanonicalJson.prepare(canonicalValue(value), CATALOG_LIMITS));
        }
        return new CanonicalList(stableSortedBy(canonicalValues, CanonicalJson.CanonicalFragment::content).stream().map(value -> (Object) value).toList());
    }

    static <T> List<T> stableSortedBy(Collection<T> values, Function<? super T, String> keyExtractor) {
        List<DecoratedValue<T>> decorated = new ArrayList<>(values.size());
        for (T value : values) {
            decorated.add(new DecoratedValue<>(value, keyExtractor.apply(value)));
        }
        decorated.sort(Comparator.comparing((DecoratedValue<T> value) -> value.key()));
        List<T> result = new ArrayList<>(decorated.size());
        for (DecoratedValue<T> value : decorated) {
            result.add(value.value());
        }
        return Collections.unmodifiableList(result);
    }

    private record DecoratedValue<T>(T value, String key) {
    }

    private static List<Object> ordered(Collection<?> values) {
        List<Object> result = new ArrayList<>(values.size());
        result.addAll(values.stream().map(CatalogCanonicalizer::canonicalValue).toList());
        return new CanonicalList(result);
    }

    private static Map<String, Object> object(Object... values) {
        if (values.length % 2 != 0) {
            throw new IllegalArgumentException("Canonical catalog object entries must be paired");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            String key = Objects.requireNonNull((String) values[index], "canonical key");
            Object value = values[index + 1];
            if (value != null) {
                result.put(key, canonicalValue(value));
            }
        }
        return new CanonicalObject(result);
    }

    private static final class CanonicalObject extends AbstractMap<String, Object> {
        private final Map<String, Object> values;

        private CanonicalObject(Map<String, Object> values) {
            this.values = Collections.unmodifiableMap(values);
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            return values.entrySet();
        }
    }

    private static final class CanonicalList extends AbstractList<Object> {
        private final List<Object> values;

        private CanonicalList(List<Object> values) {
            this.values = Collections.unmodifiableList(values);
        }

        @Override
        public Object get(int index) {
            return values.get(index);
        }

        @Override
        public int size() {
            return values.size();
        }
    }

    static final class DerivedSnapshot {
        private final ContentHash contentChecksum;
        private final ContentHash bindingManifestHash;
        private final String canonicalContent;

        private DerivedSnapshot(ContentHash contentChecksum, ContentHash bindingManifestHash, String canonicalContent) {
            this.contentChecksum = Objects.requireNonNull(contentChecksum, "contentChecksum");
            this.bindingManifestHash = Objects.requireNonNull(bindingManifestHash, "bindingManifestHash");
            this.canonicalContent = Objects.requireNonNull(canonicalContent, "canonicalContent");
        }

        ContentHash contentChecksum() {
            return contentChecksum;
        }

        ContentHash bindingManifestHash() {
            return bindingManifestHash;
        }

        String canonicalContent() {
            return canonicalContent;
        }
    }
}

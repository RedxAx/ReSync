package restudio.resync.flow.catalog;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.RepeatableGroupId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.inspector.InspectorCapability;
import restudio.resync.flow.inspector.InspectorFallback;
import restudio.resync.flow.inspector.InspectorOptionSource;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

public final class CatalogSourceIngestor {
    private static final String TYPE_UNKNOWN = "TYPE.UNKNOWN";
    private static final String TYPE_VALUE_INVALID = "TYPE.VALUE_INVALID";
    private static final Set<String> BUILTIN_TYPES = Set.of(
        "advancement", "advancement_tree_definition", "any", "biome", "block", "boolean", "chat_profile", "command_definition",
        "component", "custom_content_definition", "difficulty", "dialog_definition", "duration", "enchantment", "entity", "entity_data",
        "entity_type", "execution", "flow_definition", "function_definition", "gamemode", "gui_definition", "http_response", "instant",
        "integer", "inventory", "item", "item_attribute", "item_component", "item_component_list", "item_components", "itemstack",
        "job_reference", "living_entity", "location", "loot_entry_definition", "loot_pool_definition", "loot_table_definition", "material",
        "message_rule", "motd_profile", "network_scope", "network_transfer_result", "network_variable", "npc_definition", "npc_handle", "number",
        "permission", "permission_context", "permission_group", "player", "potion_effect", "recipe_definition", "recipe_ingredient_definition", "rgb_color",
        "runtime_data_category", "runtime_data_entry", "scheduled_task", "scoreboard_definition", "sound", "string", "tab_definition", "text_template",
        "trade_definition", "trade_profile", "uuid", "vector", "world", "worldgen_job");
    private static final Set<String> RESOURCE_LIKE_TYPES = Set.of(
        "function", "network_node", "network_route", "permission_track", "player_identity", "resource_reference", "worldgen_project");

    public CatalogContribution ingest(CatalogSource source, CatalogIngestionContext context) {
        return ingest(source, context, ignored -> true);
    }

    public CatalogContribution ingest(CatalogSource source, CatalogIngestionContext context,
                                      Predicate<ContractRef<NodeId>> availability) {
        Objects.requireNonNull(source, "Catalog source is required");
        Objects.requireNonNull(context, "Catalog ingestion context is required");
        Objects.requireNonNull(availability, "Catalog node availability predicate is required");
        ParsedSource parsed = source.prepared != null
            ? source.prepared.parsed()
            : prepareSource(source.owner, source.bytes, null).parsed();
        List<ParsedNode> nodes = parseNodes(source, parsed).stream()
            .filter(node -> availability.test(ContractRef.of(node.owner(), node.id())))
            .toList();
        Map<String, CatalogCategoryDescriptor> categories = categories(context.categories());
        Map<ContractRef<CapabilityId>, CatalogCapabilityDescriptor> capabilities = new LinkedHashMap<>();
        ContractRef<CapabilityId> editor = context.editorCapability().id();
        capabilities.put(editor, new CatalogCapabilityDescriptor(editor.id(), 1, false,
            context.editorCapability().fallback()));
        Map<InspectorFieldId, InspectorOptionSource> optionSources = new LinkedHashMap<>();
        List<CatalogNodeDescriptor> definitions = new ArrayList<>(nodes.size());
        List<RuntimeOperationDescriptor> runtimeRequirements = new ArrayList<>(nodes.size());
        List<CatalogMigrationEdge> migrations = new ArrayList<>(nodes.size());
        for (ParsedNode node : nodes) {
            CatalogCategoryDescriptor category = resolveCategory(node, categories);
            rejectUnsupportedMaterial(node);
            ParsedPins pins = parsePins(node, context);
            migrations.addAll(parseMigrations(node));
            validateTrigger(node);
            for (InspectorOptionSource optionSource : pins.optionSources()) {
                InspectorOptionSource previous = optionSources.putIfAbsent(optionSource.id(), optionSource);
                if (previous != null && !previous.equals(optionSource)) {
                    throw new IllegalArgumentException("Conflicting catalog option source: " + optionSource.id().value());
                }
            }
            for (ContractRef<CapabilityId> optionCapability : pins.optionCapabilities()) {
                if (optionCapability.owner().equals(source.owner())) {
                    capabilities.putIfAbsent(optionCapability,
                        new CatalogCapabilityDescriptor(optionCapability.id(), 1, false, InspectorFallback.GENERIC));
                }
            }
            ContractRef<CapabilityId> capability = ContractRef.of(node.owner(), CapabilityId.of(node.handlerCapability()));
            ContractRef<OperationId> operation = ContractRef.of(node.owner(), OperationId.of(operation(node)));
            Map<String, Object> sourceDescriptor = sourceDescriptor(node.source());
            CatalogSourceIngestor.RuntimeRequest request = new RuntimeRequest(node.owner(), node.id(), capability,
                operation, pins.runtimePins(), node.handler(), node.handlerConfig(), node.trigger(),
                node.source());
            RuntimeOperationDescriptor runtime = context.runtimeRequirements().resolve(request)
                .orElseThrow(() -> new IllegalArgumentException("No runtime requirement for authored node: " + node.id().value()));
            if (!runtime.capability().equals(capability) || !runtime.operation().equals(operation)
                || !runtime.pins().equals(pins.runtimePins())) {
                throw new IllegalArgumentException("Runtime requirement does not exactly match authored node: " + node.id().value());
            }
            if (node.trigger() && (!Objects.equals(runtime.unknown().get("eventType"), node.eventType())
                || !Objects.equals(runtime.unknown().get("handlerConfig"), node.handlerConfig()))) {
                throw new IllegalArgumentException("Trigger runtime metadata does not exactly match authored node: " + node.id().value());
            }
            capabilities.putIfAbsent(capability, new CatalogCapabilityDescriptor(capability.id(), 1, false, InspectorFallback.GENERIC));
            definitions.add(descriptor(node, category, runtime, pins.catalogPins(), pins.repeatables(), sourceDescriptor));
            runtimeRequirements.add(runtime);
        }
        CatalogProvenance provenance = new CatalogProvenance(source.sourceKind(), source.sourceUri(), parsed.sourceHash(),
            source.sourceVersion(), source.buildId(), null, nodes.stream().map(ParsedNode::provenance).toList());
        return CatalogContribution.builder(source.owner(), source.sourceVersion(), context.contractRange(), provenance)
            .definitions(definitions)
            .categories(List.copyOf(context.categories()))
            .capabilities(new ArrayList<>(capabilities.values()))
            .optionSources(new ArrayList<>(optionSources.values()))
            .runtimeRequirements(runtimeRequirements)
            .migrations(migrations)
            .editors(List.of(context.editorCapability()))
            .build();
    }

    public static CatalogContribution combineContributions(List<CatalogContribution> contributions) {
        if (contributions == null || contributions.isEmpty()) {
            throw new IllegalArgumentException("Catalog contributions are required");
        }
        List<CatalogContribution> values = new ArrayList<>(contributions.size());
        for (CatalogContribution contribution : contributions) {
            values.add(Objects.requireNonNull(contribution, "Catalog contribution cannot be null"));
        }
        CatalogContribution first = values.getFirst();
        validateCombineContext(first, values);
        List<CatalogProvenance.SourceEntry> entries = combinedProvenanceEntries(first.ownerId(), values);
        List<SourceIdentity> sourceIdentities = combinedSourceIdentities(values);
        CatalogProvenance provenance = aggregateProvenance(first, sourceIdentities, entries);
        return CatalogContribution.builder(first.ownerId(), first.version(), first.contractRange(), provenance)
            .dependencies(mergeShared(values, CatalogContribution::dependencies,
                value -> value.ownerId().canonicalText(), "dependency"))
            .definitions(combineDefinitions(values))
            .types(mergeShared(values, CatalogContribution::types,
                value -> value.id().canonicalKey(), "type"))
            .conversions(mergeShared(values, CatalogContribution::conversions,
                value -> value.id().canonicalKey(), "conversion"))
            .categories(mergeShared(values, CatalogContribution::categories,
                value -> value.id().canonicalText(), "category"))
            .inspectors(mergeShared(values, CatalogContribution::inspectors,
                value -> value.owner().canonicalText() + '\u0000' + value.id().canonicalText(), "inspector"))
            .capabilities(mergeShared(values, CatalogContribution::capabilities,
                value -> value.id().canonicalText(), "capability"))
            .runtimeRequirements(mergeShared(values, CatalogContribution::runtimeRequirements,
                value -> value.key().canonical(), "runtime requirement"))
            .migrations(combineMigrations(values))
            .optionSources(mergeShared(values, CatalogContribution::optionSources,
                value -> value.id().canonicalText(), "option source"))
            .validators(mergeShared(values, CatalogContribution::validators,
                value -> value.id().canonicalText(), "validator"))
            .editors(mergeShared(values, CatalogContribution::editors,
                value -> value.id().canonicalText(), "editor"))
            .previews(mergeShared(values, CatalogContribution::previews,
                value -> value.id().canonicalText(), "preview"))
            .build();
    }

    public static CatalogContribution combine(List<CatalogContribution> contributions) {
        return combineContributions(contributions);
    }

    private static void validateCombineContext(CatalogContribution first, List<CatalogContribution> values) {
        CatalogProvenance firstProvenance = first.provenance();
        if (!first.version().equals(firstProvenance.sourceVersion())) {
            throw new IllegalArgumentException("Catalog contribution version does not match its provenance version");
        }
        for (CatalogContribution value : values) {
            if (!value.provenanceErrors().isEmpty()) {
                throw new IllegalArgumentException("Catalog contribution contains invalid provenance");
            }
            CatalogProvenance provenance = value.provenance();
            if (!first.ownerId().equals(value.ownerId())) {
                throw new IllegalArgumentException("Catalog contributions must have the same owner");
            }
            if (!first.version().equals(value.version())) {
                throw new IllegalArgumentException("Catalog contributions must have the same version");
            }
            if (!first.contractRange().equals(value.contractRange())) {
                throw new IllegalArgumentException("Catalog contributions must have the same contract range");
            }
            if (!firstProvenance.sourceKind().equals(provenance.sourceKind())) {
                throw new IllegalArgumentException("Catalog contributions must have the same source kind");
            }
            if (!firstProvenance.sourceVersion().equals(provenance.sourceVersion())) {
                throw new IllegalArgumentException("Catalog contributions must have the same provenance version");
            }
            if (!firstProvenance.buildId().equals(provenance.buildId())) {
                throw new IllegalArgumentException("Catalog contributions must have the same build context");
            }
        }
    }

    private static List<CatalogNodeDescriptor> combineDefinitions(List<CatalogContribution> values) {
        Map<String, CatalogNodeDescriptor> definitions = new LinkedHashMap<>();
        for (CatalogContribution contribution : values) {
            for (CatalogNodeDescriptor definition : contribution.definitions()) {
                Objects.requireNonNull(definition, "Catalog definition cannot be null");
                String identity = definition.id().canonicalText();
                if (definitions.putIfAbsent(identity, definition) != null) {
                    throw new IllegalArgumentException("Duplicate catalog definition identity: " + identity);
                }
            }
        }
        List<CatalogNodeDescriptor> result = new ArrayList<>(definitions.values());
        result.sort(Comparator.comparing(value -> value.id().canonicalText()));
        return List.copyOf(result);
    }

    private static List<CatalogMigrationEdge> combineMigrations(List<CatalogContribution> values) {
        Map<String, CatalogMigrationEdge> migrations = new LinkedHashMap<>();
        for (CatalogContribution contribution : values) {
            for (CatalogMigrationEdge migration : contribution.migrations()) {
                Objects.requireNonNull(migration, "Catalog migration cannot be null");
                String identity = migration.id().canonicalText();
                if (migrations.putIfAbsent(identity, migration) != null) {
                    throw new IllegalArgumentException("Duplicate catalog migration identity: " + identity);
                }
            }
        }
        List<CatalogMigrationEdge> result = new ArrayList<>(migrations.values());
        result.sort(Comparator.comparing(value -> value.id().canonicalText()));
        return List.copyOf(result);
    }

    private static <T> List<T> mergeShared(List<CatalogContribution> values,
                                           Function<CatalogContribution, List<T>> extractor,
                                           Function<T, String> identity,
                                           String label) {
        Map<String, T> merged = new LinkedHashMap<>();
        for (CatalogContribution contribution : values) {
            for (T value : extractor.apply(contribution)) {
                Objects.requireNonNull(value, "Catalog " + label + " cannot be null");
                String key = Objects.requireNonNull(identity.apply(value), "Catalog " + label + " identity is required");
                T previous = merged.putIfAbsent(key, value);
                if (previous != null && !previous.equals(value)) {
                    throw new IllegalArgumentException("Conflicting catalog " + label + " identity: " + key);
                }
            }
        }
        List<T> result = new ArrayList<>(merged.values());
        result.sort(Comparator.comparing(identity));
        return List.copyOf(result);
    }

    private static List<CatalogProvenance.SourceEntry> combinedProvenanceEntries(OwnerId owner,
                                                                                   List<CatalogContribution> values) {
        Map<String, CatalogProvenance.SourceEntry> entries = new LinkedHashMap<>();
        for (CatalogContribution contribution : values) {
            for (CatalogProvenance.SourceEntry entry : contribution.provenance().entries()) {
                if (!owner.equals(entry.owner())) {
                    throw new IllegalArgumentException("Catalog provenance owner does not match contribution owner");
                }
                if (entries.putIfAbsent(entry.definitionId(), entry) != null) {
                    throw new IllegalArgumentException("Duplicate catalog definition identity: " + entry.definitionId());
                }
            }
        }
        List<CatalogProvenance.SourceEntry> result = new ArrayList<>(entries.values());
        result.sort(Comparator.comparing(CatalogProvenance.SourceEntry::sourceUri)
            .thenComparingInt(CatalogProvenance.SourceEntry::rowIndex)
            .thenComparing(value -> value.owner().canonicalText())
            .thenComparing(CatalogProvenance.SourceEntry::definitionId));
        return List.copyOf(result);
    }

    private static List<SourceIdentity> combinedSourceIdentities(List<CatalogContribution> values) {
        Map<String, ContentHash> hashes = new LinkedHashMap<>();
        Set<SourceIdentity> identities = new LinkedHashSet<>();
        for (CatalogContribution contribution : values) {
            List<SourceIdentity> local = contribution.provenance().entries().stream()
                .map(value -> new SourceIdentity(value.sourceUri(), value.sourceHash()))
                .distinct()
                .toList();
            if (local.isEmpty()) {
                local = List.of(new SourceIdentity(contribution.provenance().sourceUri(), contribution.provenance().sourceHash()));
            }
            for (SourceIdentity identity : local) {
                ContentHash previous = hashes.putIfAbsent(identity.uri(), identity.hash());
                if (previous != null && !previous.equals(identity.hash())) {
                    throw new IllegalArgumentException("Conflicting catalog source identity: " + identity.uri());
                }
                identities.add(identity);
            }
        }
        List<SourceIdentity> result = new ArrayList<>(identities);
        result.sort(Comparator.comparing(SourceIdentity::uri)
            .thenComparing(value -> value.hash().canonicalText()));
        return List.copyOf(result);
    }

    private static CatalogProvenance aggregateProvenance(CatalogContribution first,
                                                          List<SourceIdentity> sourceIdentities,
                                                          List<CatalogProvenance.SourceEntry> entries) {
        List<Map<String, Object>> identities = sourceIdentities.stream()
            .map(value -> Map.<String, Object>of("sourceUri", value.uri(), "sourceHash", value.hash().canonicalText()))
            .toList();
        ContentHash hash = ContentHash.of(CanonicalJson.genericCanonicalContentHash(identities));
        String sourceUri = "catalog://aggregate/" + first.ownerId().canonicalText() + "/" + hash.canonicalText();
        CatalogProvenance source = first.provenance();
        return new CatalogProvenance(source.sourceKind(), sourceUri, hash, first.version(), source.buildId(), null, entries);
    }

    private record SourceIdentity(String uri, ContentHash hash) {
        private SourceIdentity {
            uri = CatalogIds.text(uri, "sourceIdentity.sourceUri", 1024);
            hash = Objects.requireNonNull(hash, "sourceIdentity.sourceHash");
        }
    }

    private static PreparedCatalogSource prepareSource(OwnerId owner, byte[] bytes, ContentHash expectedSourceHash) {
        byte[] sourceBytes = Arrays.copyOf(Objects.requireNonNull(bytes, "Catalog source bytes are required"), bytes.length);
        Object parsed = CanonicalJson.parse(sourceBytes);
        ContentHash sourceHash = ContentHash.of(CanonicalJson.genericCanonicalContentHash(parsed));
        if (expectedSourceHash != null && !expectedSourceHash.equals(sourceHash)) {
            throw new IllegalArgumentException("Catalog source hash does not match its prepared source proof");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        if (parsed instanceof Map<?, ?> object) {
            rows.add(immutableObject(object));
        } else if (parsed instanceof List<?> array) {
            if (array.isEmpty()) {
                throw new IllegalArgumentException("Catalog source array must not be empty");
            }
            for (Object value : array) {
                if (!(value instanceof Map<?, ?> object)) {
                    throw new IllegalArgumentException("Catalog source array entries must be objects");
                }
                rows.add(immutableObject(object));
            }
        } else {
            throw new IllegalArgumentException("Catalog source root must be an object or nonempty array of objects");
        }
        if (expectedSourceHash != null && !rows.getFirst().containsKey("owner")) {
            throw new IllegalArgumentException("Catalog source owner proof is missing");
        }
        for (Map<String, Object> row : rows) {
            readOwner(row, owner);
        }
        return new PreparedCatalogSource(sourceBytes, new ParsedSource(rows, sourceHash), owner);
    }

    private List<ParsedNode> parseNodes(CatalogSource source, ParsedSource parsed) {
        Objects.requireNonNull(source, "Catalog source is required");
        Objects.requireNonNull(parsed, "Parsed catalog source is required");
        Set<NodeId> ids = new LinkedHashSet<>();
        List<ParsedNode> nodes = new ArrayList<>(parsed.rows().size());
        for (int rowIndex = 0; rowIndex < parsed.rows().size(); rowIndex++) {
            Map<String, Object> row = parsed.rows().get(rowIndex);
            OwnerId owner = readOwner(row, source.owner());
            NodeId id = readNodeId(row, "id");
            if (!ids.add(id)) {
                throw new IllegalArgumentException("Duplicate catalog node ID: " + id.value());
            }
            String displayName = readText(row, "displayName", 128);
            String description = readText(row, "description", 280);
            String domain = readLocal(row, "domain");
            String family = readLocal(row, "family");
            String lifecycle = readText(row, "lifecycle", 64);
            String handlerCapability = readLocal(row, "handlerCapability");
            readText(row, "selectorIntent", 64);
            readText(row, "inspectorIntent", 64);
            String category = readText(row, "category", 128);
            int schemaVersion = readPositiveInt(row, "schemaVersion");
            String kind = readText(row, "kind", 64);
            boolean trigger = readBoolean(row, "trigger", false);
            if (trigger && !"EVENT".equalsIgnoreCase(kind)) {
                throw new IllegalArgumentException("Trigger catalog nodes must use kind EVENT: " + id.value());
            }
            if (!trigger && "EVENT".equalsIgnoreCase(kind)) {
                throw new IllegalArgumentException("EVENT catalog nodes must be triggers: " + id.value());
            }
            String eventType = trigger ? readText(row, "eventType", 512) : null;
            String handler = trigger ? readOptionalText(row, "handler", 256) : readText(row, "handler", 256);
            Map<String, Object> handlerConfig = readMap(row, "handlerConfig");
            CatalogProvenance.SourceEntry provenance = new CatalogProvenance.SourceEntry(
                source.sourceUri(), rowIndex, owner, id.value(), parsed.sourceHash());
            nodes.add(new ParsedNode(owner, id, displayName, description, domain, family, lifecycle,
                handlerCapability, category, schemaVersion, kind, trigger, eventType, handler, handlerConfig, row, provenance));
        }
        return List.copyOf(nodes);
    }

    private static Map<String, CatalogCategoryDescriptor> categories(List<CatalogCategoryDescriptor> values) {
        Map<String, CatalogCategoryDescriptor> result = new LinkedHashMap<>();
        for (CatalogCategoryDescriptor value : values) {
            Objects.requireNonNull(value, "Catalog categories cannot contain null");
            String key = value.id().value();
            if (result.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("Duplicate catalog category ID: " + key);
            }
        }
        return result;
    }

    private static CatalogCategoryDescriptor resolveCategory(ParsedNode node, Map<String, CatalogCategoryDescriptor> categories) {
        String categoryId = node.category().toLowerCase(Locale.ROOT);
        CatalogCategoryDescriptor category = categories.get(categoryId);
        if (category == null) {
            throw new IllegalArgumentException("Unknown catalog category: " + node.category());
        }
        return category;
    }

    private static ParsedPins parsePins(ParsedNode node, CatalogIngestionContext context) {
        Set<PinId> identities = new LinkedHashSet<>();
        List<CatalogNodeDescriptor.Pin> catalogPins = new ArrayList<>();
        List<RuntimeOperationDescriptor.Pin> runtimePins = new ArrayList<>();
        List<InspectorOptionSource> optionSources = new ArrayList<>();
        List<ContractRef<CapabilityId>> optionCapabilities = new ArrayList<>();
        List<ParsedRepeatablePin> repeatablePins = new ArrayList<>();
        parsePinList(node, context, "inputs", CatalogNodeDescriptor.Direction.INPUT,
            RuntimeOperationDescriptor.Direction.INPUT, identities, catalogPins, runtimePins, optionSources,
            optionCapabilities, repeatablePins);
        parsePinList(node, context, "outputs", CatalogNodeDescriptor.Direction.OUTPUT,
            RuntimeOperationDescriptor.Direction.OUTPUT, identities, catalogPins, runtimePins, optionSources,
            optionCapabilities, repeatablePins);
        return new ParsedPins(List.copyOf(catalogPins), List.copyOf(runtimePins), List.copyOf(optionSources),
            List.copyOf(optionCapabilities), materializeRepeatables(repeatablePins));
    }

    private static void parsePinList(ParsedNode node, CatalogIngestionContext context, String field,
                                     CatalogNodeDescriptor.Direction catalogDirection,
                                     RuntimeOperationDescriptor.Direction runtimeDirection,
                                     Set<PinId> identities, List<CatalogNodeDescriptor.Pin> catalogPins,
                                     List<RuntimeOperationDescriptor.Pin> runtimePins,
                                     List<InspectorOptionSource> optionSources,
                                     List<ContractRef<CapabilityId>> optionCapabilities,
                                     List<ParsedRepeatablePin> repeatablePins) {
        Object raw = node.source().get(field);
        if (!node.source().containsKey(field)) {
            return;
        }
        if (!(raw instanceof List<?> values)) {
            throw new IllegalArgumentException("Catalog node " + field + " must be an array");
        }
        for (int index = 0; index < values.size(); index++) {
            Object value = values.get(index);
            if (!(value instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("Catalog node " + field + " entry " + index + " must be an object");
            }
            Map<String, Object> pin = immutableObject(map);
            PinId id = readPinId(pin, field, index);
            if (!identities.add(id)) {
                throw new IllegalArgumentException("Duplicate catalog pin ID: " + id.value());
            }
            String displayName = readText(pin, "displayName", 128);
            String description = readText(pin, "description", 240);
            String authoredResourceRole = readOptionalText(pin, "resourceRole", 240);
            PinType pinType = readPinType(pin, field, index);
            TypeExpr type = parsePinType(pin, pinType, node.owner(), context.resourceTypes(), field, index);
            TypedValue defaultValue = readDefaultValue(pin, type, pinType);
            CatalogNodeDescriptor.PinPresentation presentation = readPinPresentation(pin, type, pinType, field, index);
            OptionBinding option = resolveOptionSource(pin, type, node.owner(), context.optionSources(), field, index);
            ParsedRepeatable repeatable = readRepeatable(pin, field, index);
            CatalogNodeDescriptor.Requirement requirement = defaultValue != null
                ? CatalogNodeDescriptor.Requirement.DEFAULTED : readBoolean(pin, "optional", false)
                ? CatalogNodeDescriptor.Requirement.OPTIONAL : CatalogNodeDescriptor.Requirement.REQUIRED;
            String resourceRole = authoredResourceRole;
            if (type instanceof TypeExpr.ResourceType) {
                if (authoredResourceRole != null && !"reference".equals(authoredResourceRole)) {
                    throw new IllegalArgumentException("Resource pins must use the reference role: " + id.value());
                }
                resourceRole = "reference";
            }
            CatalogNodeDescriptor.RepeatableIntent repeatableIntent = repeatable == null
                ? CatalogNodeDescriptor.RepeatableIntent.disabled()
                : new CatalogNodeDescriptor.RepeatableIntent(repeatable.groupId(), repeatable.minimum(),
                    repeatable.maximum(), repeatable.ordered());
            CatalogNodeDescriptor.Pin catalogPin = new CatalogNodeDescriptor.Pin(id, catalogDirection, type,
                displayName, description, requirement, defaultValue, context.editorCapability().id(), option.reference(), null,
                repeatableIntent, resourceRole, presentation);
            catalogPins.add(catalogPin);
            runtimePins.add(new RuntimeOperationDescriptor.Pin(id, runtimeDirection, type));
            if (repeatable != null) {
                repeatablePins.add(new ParsedRepeatablePin(repeatable,
                    new CatalogNodeDescriptor.RepeatableMember(id, catalogDirection, type)));
            }
            if (option.source() != null) {
                optionSources.add(option.source());
                optionCapabilities.add(option.source().capability());
            }
        }
    }

    private static ParsedRepeatable readRepeatable(Map<String, Object> pin, String field, int index) {
        if (!pin.containsKey("repeatable")) {
            return null;
        }
        Object raw = pin.get("repeatable");
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Catalog node " + field + " entry " + index + " repeatable must be an object");
        }
        Map<String, Object> repeatable = immutableObject(map);
        requireExactKeys(repeatable, Set.of("groupId", "minItems", "maxItems", "itemLabel"), Set.of("ordered"),
            field + " repeatable", Integer.toString(index));
        RepeatableGroupId groupId = RepeatableGroupId.of(readString(repeatable, "groupId"));
        int minimum = readNonNegativeInt(repeatable, "minItems");
        int maximum = readPositiveInt(repeatable, "maxItems");
        if (maximum < minimum) {
            throw new IllegalArgumentException("Catalog repeatable maximum must be at least its minimum: " + field + "#" + index);
        }
        String itemLabel = readText(repeatable, "itemLabel", 128);
        return new ParsedRepeatable(groupId, minimum, maximum, readBoolean(repeatable, "ordered", true), itemLabel);
    }

    private static List<CatalogNodeDescriptor.RepeatableGroup> materializeRepeatables(
        List<ParsedRepeatablePin> repeatablePins) {
        Map<RepeatableGroupId, ParsedRepeatable> definitions = new LinkedHashMap<>();
        Map<RepeatableGroupId, List<CatalogNodeDescriptor.RepeatableMember>> members = new LinkedHashMap<>();
        for (ParsedRepeatablePin pin : repeatablePins) {
            ParsedRepeatable repeatable = pin.repeatable();
            ParsedRepeatable previous = definitions.putIfAbsent(repeatable.groupId(), repeatable);
            if (previous != null && !previous.equals(repeatable)) {
                throw new IllegalArgumentException("Conflicting catalog repeatable group metadata: "
                    + repeatable.groupId().value());
            }
            members.computeIfAbsent(repeatable.groupId(), ignored -> new ArrayList<>()).add(pin.member());
        }
        List<CatalogNodeDescriptor.RepeatableGroup> groups = new ArrayList<>(definitions.size());
        for (ParsedRepeatable repeatable : definitions.values()) {
            groups.add(CatalogNodeDescriptor.RepeatableGroup.withMembers(repeatable.groupId(), repeatable.itemLabel(),
                repeatableDescription(repeatable.itemLabel()), repeatable.minimum(), repeatable.maximum(),
                repeatable.ordered(), members.get(repeatable.groupId())));
        }
        return List.copyOf(groups);
    }

    private static String repeatableDescription(String itemLabel) {
        return "Groups repeated " + itemLabel + " members for this node.";
    }

    private static TypedValue readDefaultValue(Map<String, Object> pin, TypeExpr type, PinType pinType) {
        if (!pin.containsKey("defaultValue")) {
            return null;
        }
        if (pinType == PinType.FLOW) {
            throw valueError("Flow pins cannot declare a default value", null);
        }
        try {
            Object raw = pin.get("defaultValue");
            if (raw == null) {
                if (!allowsNull(type)) {
                    throw new IllegalArgumentException("Catalog resource defaults cannot be null");
                }
                return TypedValue.nullValue(type);
            }
            return TypedValue.value(type, defaultMaterial(raw, type, 0));
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage() != null && exception.getMessage().startsWith(TYPE_VALUE_INVALID + ":")) {
                throw exception;
            }
            throw valueError("Invalid catalog default value: " + exception.getMessage(), exception);
        }
    }

    private static CatalogNodeDescriptor.PinPresentation readPinPresentation(Map<String, Object> pin, TypeExpr type,
                                                                               PinType pinType, String field, int index) {
        String widget = readOptionalText(pin, "widget", 128);
        List<TypedValue> options = readStaticOptions(pin, type, pinType, field, index);
        Map<String, Object> constraints = readPresentationMap(pin, "constraints", field, index);
        Map<String, Object> visibleWhen = readPresentationMap(pin, "visibleWhen", field, index);
        return new CatalogNodeDescriptor.PinPresentation(widget, options, constraints, visibleWhen);
    }

    private static List<TypedValue> readStaticOptions(Map<String, Object> pin, TypeExpr type, PinType pinType,
                                                       String field, int index) {
        if (!pin.containsKey("options")) {
            return List.of();
        }
        if (pinType == PinType.FLOW) {
            throw new IllegalArgumentException("Flow pins cannot declare static options at " + field + " entry " + index);
        }
        Object raw = pin.get("options");
        if (!(raw instanceof List<?> values)) {
            throw new IllegalArgumentException("Catalog pin options must be an array at " + field + " entry " + index);
        }
        List<TypedValue> options = new ArrayList<>(values.size());
        for (Object value : values) {
            if (value == null) {
                if (!allowsNull(type)) {
                    throw new IllegalArgumentException("Catalog pin options cannot contain null for this type at " + field + " entry " + index);
                }
                options.add(TypedValue.nullValue(type));
            } else {
                options.add(TypedValue.value(type, defaultMaterial(value, type, 0)));
            }
        }
        return List.copyOf(options);
    }

    private static Map<String, Object> readPresentationMap(Map<String, Object> pin, String name, String field, int index) {
        if (!pin.containsKey(name)) {
            return Map.of();
        }
        Object raw = pin.get(name);
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Catalog pin " + name + " must be an object at " + field + " entry " + index);
        }
        return immutableObject(map);
    }

    private static boolean allowsNull(TypeExpr type) {
        return !containsResource(type) || type instanceof TypeExpr.OptionalType;
    }

    private static boolean containsResource(TypeExpr type) {
        return switch (type) {
            case TypeExpr.ResourceType ignored -> true;
            case TypeExpr.Named named -> named.arguments().stream().anyMatch(CatalogSourceIngestor::containsResource);
            case TypeExpr.OptionalType optional -> containsResource(optional.element());
            case TypeExpr.ListType list -> containsResource(list.element());
            case TypeExpr.MapType map -> containsResource(map.key()) || containsResource(map.value());
            case TypeExpr.TupleType tuple -> tuple.elements().stream().anyMatch(CatalogSourceIngestor::containsResource);
            case TypeExpr.ResultType result -> containsResource(result.success()) || containsResource(result.failure());
            case TypeExpr.UnionType union -> union.variants().stream().map(TypeExpr.UnionVariant::type).anyMatch(CatalogSourceIngestor::containsResource);
            case TypeExpr.OpaqueType ignored -> false;
        };
    }

    private static Object defaultMaterial(Object raw, TypeExpr type, int depth) {
        if (depth > 32) {
            throw new IllegalArgumentException("Catalog default value is too deeply nested");
        }
        return switch (type) {
            case TypeExpr.Named named -> namedDefaultMaterial(raw, named);
            case TypeExpr.OptionalType optional -> raw == null ? null : defaultMaterial(raw, optional.element(), depth + 1);
            case TypeExpr.ListType list -> listDefaultMaterial(raw, list.element(), depth + 1);
            case TypeExpr.MapType map -> mapDefaultMaterial(raw, map, depth + 1);
            case TypeExpr.ResultType result -> resultDefaultMaterial(raw, result, depth + 1);
            case TypeExpr.ResourceType resource -> throw unsupportedDefaultType(resource);
            case TypeExpr.TupleType tuple -> throw unsupportedDefaultType(tuple);
            case TypeExpr.UnionType union -> throw unsupportedDefaultType(union);
            case TypeExpr.OpaqueType opaque -> throw unsupportedDefaultType(opaque);
        };
    }

    private static IllegalArgumentException unsupportedDefaultType(TypeExpr type) {
        return new IllegalArgumentException("Catalog defaults do not support type: " + type.kind());
    }

    private static Object namedDefaultMaterial(Object raw, TypeExpr.Named named) {
        if (!"builtin".equals(named.reference().ownerId()) || !named.arguments().isEmpty()) {
            throw unsupportedDefaultType(named);
        }
        return switch (named.reference().localId()) {
            case "any", "string", "boolean", "location", "rgb_color", "scheduled_task" -> raw;
            case "integer", "duration", "instant" -> integralMaterial(raw);
            case "number" -> decimalMaterial(raw);
            case "uuid" -> uuidMaterial(raw);
            case "execution" -> throw new IllegalArgumentException("Flow execution pins cannot have defaults");
            default -> raw;
        };
    }

    private static List<Object> listDefaultMaterial(Object raw, TypeExpr element, int depth) {
        if (!(raw instanceof List<?> values)) {
            throw new IllegalArgumentException("Catalog list default must be an array");
        }
        List<Object> result = new ArrayList<>(values.size());
        for (Object value : values) {
            result.add(value == null ? null : defaultMaterial(value, element, depth));
        }
        return Collections.unmodifiableList(result);
    }

    private static Map<String, Object> mapDefaultMaterial(Object raw, TypeExpr.MapType type, int depth) {
        if (!(raw instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Catalog map default must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("Catalog map default keys must be text");
            }
            Object decodedKey = defaultMaterial(key, type.key(), depth);
            if (!(decodedKey instanceof String decoded)) {
                throw new IllegalArgumentException("Catalog map default keys must decode to text");
            }
            if (result.containsKey(decoded)) {
                throw new IllegalArgumentException("Duplicate catalog map default key: " + decoded);
            }
            result.put(decoded, entry.getValue() == null ? null : defaultMaterial(entry.getValue(), type.value(), depth));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> resultDefaultMaterial(Object raw, TypeExpr.ResultType type, int depth) {
        if (!(raw instanceof Map<?, ?> values) || !values.containsKey("success") || !values.containsKey("value")) {
            throw new IllegalArgumentException("Catalog result default requires success and value");
        }
        Object successRaw = values.get("success");
        if (!(successRaw instanceof Boolean success)) {
            throw new IllegalArgumentException("Catalog result default success must be boolean");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("Catalog result default keys must be text");
            }
            Object value = entry.getValue();
            if ("success".equals(key)) {
                result.put(key, success);
            } else if ("value".equals(key)) {
                result.put(key, value == null ? null : defaultMaterial(value, success ? type.success() : type.failure(), depth));
            } else {
                result.put(key, value);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static BigDecimal decimalMaterial(Object raw) {
        if (raw instanceof BigDecimal decimal) {
            return decimal;
        }
        if (raw instanceof BigInteger integer) {
            return new BigDecimal(integer);
        }
        if (raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long) {
            return BigDecimal.valueOf(((Number) raw).longValue());
        }
        if (raw instanceof Float || raw instanceof Double) {
            double value = ((Number) raw).doubleValue();
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Catalog decimal default must be finite");
            }
            return BigDecimal.valueOf(value);
        }
        throw new IllegalArgumentException("Catalog decimal default must be numeric");
    }

    private static BigInteger integralMaterial(Object raw) {
        try {
            return decimalMaterial(raw).toBigIntegerExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Catalog integer default must be integral", exception);
        }
    }

    private static UUID uuidMaterial(Object raw) {
        if (!(raw instanceof String text)) {
            throw new IllegalArgumentException("Catalog UUID default must be text");
        }
        try {
            UUID value = UUID.fromString(text);
            if (!value.toString().equals(text)) {
                throw new IllegalArgumentException("Catalog UUID default must be canonical");
            }
            return value;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Catalog UUID default must be a canonical UUID", exception);
        }
    }

    private static OptionBinding resolveOptionSource(Map<String, Object> pin, TypeExpr type, OwnerId owner,
                                                     OptionSourceResolver resolver, String field, int index) {
        if (!pin.containsKey("optionsSource") || pin.get("optionsSource") == null) {
            return OptionBinding.none();
        }
        Object raw = pin.get("optionsSource");
        if (!(raw instanceof String source)) {
            throw new IllegalArgumentException("Catalog pin optionsSource must be text at " + field + " entry " + index);
        }
        if (source.isBlank()) {
            return OptionBinding.none();
        }
        InspectorFieldId id = InspectorFieldId.of(derivedLocalId(source, "option-source"));
        InspectorOptionSource option = resolver.resolve(id)
            .orElseThrow(() -> new IllegalArgumentException("Unknown catalog option source: " + id.value()));
        if (!option.id().equals(id)) {
            throw new IllegalArgumentException("Catalog option source ID does not match derived ID: " + id.value());
        }
        if (!option.optionType().equals(type)) {
            throw new IllegalArgumentException("Catalog option source type does not match pin: " + id.value());
        }
        return new OptionBinding(option, ContractRef.of(owner, id));
    }

    private static String derivedLocalId(String value, String fallback) {
        String source = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
        String[] parts = source.split("[._-]+");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            String normalized = part.replaceAll("[^a-z0-9]+", "");
            if (normalized.isBlank()) {
                continue;
            }
            if (!Character.isLetter(normalized.charAt(0))) {
                normalized = "x" + normalized;
            }
            if (normalized.length() > 32) {
                normalized = normalized.substring(0, 32);
            }
            if (!result.isEmpty()) {
                result.append('-');
            }
            result.append(normalized);
        }
        String normalized = result.isEmpty() ? fallback : result.toString();
        return normalized.length() > 128 ? normalized.substring(0, 128) : normalized;
    }

    private static PinId readPinId(Map<String, Object> pin, String field, int index) {
        String source = readString(pin, "id");
        try {
            return PinId.of(source);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid catalog pin ID at " + field + " entry " + index, exception);
        }
    }

    private static String readOptionalText(Map<String, Object> source, String field, int maximum) {
        if (!source.containsKey(field)) {
            return null;
        }
        Object value = source.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("Catalog field must be text: " + field);
        }
        return CatalogIds.text(text, field, maximum);
    }

    private static PinType readPinType(Map<String, Object> pin, String field, int index) {
        if (!pin.containsKey("pinType")) {
            return PinType.DATA;
        }
        String value = readString(pin, "pinType").toUpperCase(Locale.ROOT);
        try {
            return PinType.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown catalog pin type at " + field + " entry " + index + ": " + value,
                exception);
        }
    }

    private static TypeExpr parsePinType(Map<String, Object> pin, PinType pinType, OwnerId owner,
                                         ResourceTypeResolver resources, String field, int index) {
        if (!pin.containsKey("dataType")) {
            throw typeError("Catalog pin dataType is required at " + field + " entry " + index, null);
        }
        Object value = pin.get("dataType");
        if (!(value instanceof String expression) || expression.isBlank()) {
            throw typeError("Catalog pin dataType must be nonblank text at " + field + " entry " + index, null);
        }
        TypeExpr parsed = parseTypeExpression(expression, owner, resources);
        if (pinType == PinType.FLOW && !parsed.equals(parseTypeExpression("execution", owner, resources))) {
            throw typeError("Flow pins must use the execution type at " + field + " entry " + index, null);
        }
        return parsed;
    }

    static TypeExpr parseTypeExpression(String expression, OwnerId owner, ResourceTypeResolver resources) {
        try {
            return new TypeExpressionParser(expression, owner, resources).parse();
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage() != null && exception.getMessage().startsWith(TYPE_UNKNOWN + ":")) {
                throw exception;
            }
            throw typeError("Invalid type expression: " + exception.getMessage(), exception);
        }
    }

    private enum PinType {
        DATA,
        FLOW
    }

    private record ParsedPins(List<CatalogNodeDescriptor.Pin> catalogPins,
                              List<RuntimeOperationDescriptor.Pin> runtimePins,
                              List<InspectorOptionSource> optionSources,
                              List<ContractRef<CapabilityId>> optionCapabilities,
                              List<CatalogNodeDescriptor.RepeatableGroup> repeatables) {
        private ParsedPins {
            catalogPins = List.copyOf(catalogPins);
            runtimePins = List.copyOf(runtimePins);
            optionSources = List.copyOf(optionSources);
            optionCapabilities = List.copyOf(optionCapabilities);
            repeatables = List.copyOf(repeatables);
        }
    }

    private record ParsedRepeatable(RepeatableGroupId groupId, int minimum, int maximum, boolean ordered,
                                    String itemLabel) {
    }

    private record ParsedRepeatablePin(ParsedRepeatable repeatable,
                                       CatalogNodeDescriptor.RepeatableMember member) {
    }

    private record OptionBinding(InspectorOptionSource source, ContractRef<InspectorFieldId> reference) {
        private OptionBinding {
            if ((source == null) != (reference == null)) {
                throw new IllegalArgumentException("Option source and reference must be provided together");
            }
        }

        private static OptionBinding none() {
            return new OptionBinding(null, null);
        }
    }

    private static final class TypeExpressionParser {
        private final String expression;
        private final OwnerId owner;
        private final ResourceTypeResolver resources;
        private int offset;
        private int depth;

        private TypeExpressionParser(String expression, OwnerId owner, ResourceTypeResolver resources) {
            this.expression = CatalogIds.text(expression, "dataType", 256);
            this.owner = Objects.requireNonNull(owner, "Type owner is required");
            this.resources = Objects.requireNonNull(resources, "Resource type resolver is required");
        }

        private TypeExpr parse() {
            TypeExpr result = parseType();
            skipWhitespace();
            if (offset != expression.length()) {
                throw error("Unexpected type expression");
            }
            return result;
        }

        private TypeExpr parseType() {
            if (++depth > 32) {
                throw error("Type expression is too deeply nested");
            }
            try {
                skipWhitespace();
                String id = parseIdentifier();
                skipWhitespace();
                List<TypeExpr> arguments = parseArguments(id);
                return build(id, arguments);
            } finally {
                depth--;
            }
        }

        private String parseIdentifier() {
            int start = offset;
            while (offset < expression.length() && isIdentifierCharacter(expression.charAt(offset))) {
                offset++;
            }
            if (start == offset) {
                throw error("Expected type identifier");
            }
            return expression.substring(start, offset).toLowerCase(Locale.ROOT);
        }

        private List<TypeExpr> parseArguments(String parentId) {
            if (offset >= expression.length() || expression.charAt(offset) != '<') {
                return List.of();
            }
            offset++;
            List<TypeExpr> arguments = new ArrayList<>();
            skipWhitespace();
            if (offset < expression.length() && expression.charAt(offset) == '>') {
                throw error("Type arguments cannot be empty");
            }
            while (true) {
                arguments.add(resourceConstructor(parentId) ? parseResourceArgument() : parseType());
                skipWhitespace();
                if (offset >= expression.length()) {
                    throw error("Unclosed type arguments");
                }
                char separator = expression.charAt(offset++);
                if (separator == '>') {
                    return List.copyOf(arguments);
                }
                if (separator != ',') {
                    throw error("Expected a type argument separator");
                }
                skipWhitespace();
                if (offset >= expression.length() || expression.charAt(offset) == '>' || expression.charAt(offset) == ',') {
                    throw error("Missing type argument");
                }
            }
        }

        private TypeExpr parseResourceArgument() {
            skipWhitespace();
            String id = parseIdentifier();
            skipWhitespace();
            if (offset < expression.length() && expression.charAt(offset) == '<') {
                throw error("Resource type arguments must be simple identifiers");
            }
            return TypeExpr.named(reference(id, true));
        }

        private TypeExpr build(String id, List<TypeExpr> arguments) {
            return switch (id) {
                case "list" -> one(id, arguments, TypeExpr::list);
                case "optional" -> one(id, arguments, TypeExpr::optional);
                case "map" -> two(id, arguments, TypeExpr::map);
                case "result" -> result(arguments);
                case "resource", "resource_reference" -> resource(id, arguments);
                default -> named(id, arguments);
            };
        }

        private static boolean resourceConstructor(String id) {
            return "resource".equals(id) || "resource_reference".equals(id);
        }

        private TypeExpr named(String id, List<TypeExpr> arguments) {
            if (resourceLike(id)) {
                if (!arguments.isEmpty()) {
                    throw error("Resource-like types cannot have generic arguments");
                }
                return resource(id);
            }
            return TypeExpr.named(reference(id), arguments);
        }

        private static boolean resourceLike(String id) {
            String base = baseId(id);
            return !BUILTIN_TYPES.contains(base)
                && (RESOURCE_LIKE_TYPES.contains(base) || base.endsWith("_reference") || base.endsWith("_id"));
        }

        private TypeExpr resource(String id) {
            return resourceReference(reference(id, true));
        }

        private TypeReference reference(String id) {
            return reference(id, false);
        }

        private TypeReference reference(String id, boolean resourceArgument) {
            String value = id;
            int separator = value.indexOf(':');
            if (separator >= 0) {
                if (separator != value.lastIndexOf(':')) {
                    throw error("Type reference has too many owner separators");
                }
                String ownerId = value.substring(0, separator);
                String localId = value.substring(separator + 1);
                if (ownerId.equals("type")) {
                    return new TypeReference("type", localId);
                }
                return new TypeReference(ownerId, localId);
            }
            if (value.startsWith("type:") && value.length() > "type:".length()) {
                return new TypeReference("type", value.substring("type:".length()));
            }
            if (BUILTIN_TYPES.contains(value)) {
                return new TypeReference("builtin", value);
            }
            if (resourceArgument) {
                return new TypeReference(owner.value(), value);
            }
            throw error("Unknown unqualified type: " + value);
        }

        private static String baseId(String id) {
            int separator = id.indexOf(':');
            return separator < 0 ? id : id.substring(separator + 1);
        }

        private TypeExpr result(List<TypeExpr> arguments) {
            if (arguments.size() == 1) {
                return TypeExpr.result(arguments.getFirst(), TypeExpr.named(reference("any")));
            }
            if (arguments.size() == 2) {
                return TypeExpr.result(arguments.get(0), arguments.get(1));
            }
            throw error("result requires one or two type arguments");
        }

        private TypeExpr resource(String constructor, List<TypeExpr> arguments) {
            if (arguments.size() != 1 || !(arguments.getFirst() instanceof TypeExpr.Named named)
                || !named.arguments().isEmpty()) {
                throw error(constructor + " requires one simple type argument");
            }
            return resourceReference(named.reference());
        }

        private TypeExpr resourceReference(TypeReference requested) {
            ContractRef<ResourceTypeId> request = ContractRef.of(OwnerId.of(requested.ownerId()),
                ResourceTypeId.of(requested.localId()));
            ContractRef<ResourceTypeId> resolved = resources.resolve(request)
                .orElseThrow(() -> error("Unknown resource type: " + request.canonicalText()));
            return TypeExpr.resource(new TypeReference(resolved.owner().value(), resolved.id().value()));
        }

        private TypeExpr one(String id, List<TypeExpr> arguments, Function<TypeExpr, TypeExpr> factory) {
            if (arguments.size() != 1) {
                throw error(id + " requires one type argument");
            }
            return factory.apply(arguments.getFirst());
        }

        private TypeExpr two(String id, List<TypeExpr> arguments,
                             BiFunction<TypeExpr, TypeExpr, TypeExpr> factory) {
            if (arguments.size() != 2) {
                throw error(id + " requires two type arguments");
            }
            return factory.apply(arguments.get(0), arguments.get(1));
        }

        private void skipWhitespace() {
            while (offset < expression.length() && Character.isWhitespace(expression.charAt(offset))) {
                offset++;
            }
        }

        private static boolean isIdentifierCharacter(char value) {
            return Character.isLetterOrDigit(value) || value == '_' || value == ':' || value == '.' || value == '-';
        }

        private IllegalArgumentException error(String message) {
            return typeError(message + " at offset " + offset + " in " + expression, null);
        }
    }

    private static IllegalArgumentException typeError(String message, Throwable cause) {
        return new IllegalArgumentException(TYPE_UNKNOWN + ": " + message, cause);
    }

    private static IllegalArgumentException valueError(String message, Throwable cause) {
        return new IllegalArgumentException(TYPE_VALUE_INVALID + ": " + message, cause);
    }

    private static CatalogNodeDescriptor descriptor(ParsedNode node, CatalogCategoryDescriptor category,
                                                     RuntimeOperationDescriptor runtime,
                                                     List<CatalogNodeDescriptor.Pin> pins,
                                                     List<CatalogNodeDescriptor.RepeatableGroup> repeatables,
                                                     Map<String, Object> sourceDescriptor) {
        Map<String, Object> authored = new LinkedHashMap<>(node.source());
        authored.put("sourceProvenance", Map.of(
            "sourceUri", node.provenance().sourceUri(),
            "rowIndex", node.provenance().rowIndex(),
            "owner", node.provenance().owner().value(),
            "definitionId", node.provenance().definitionId(),
            "sourceHash", node.provenance().sourceHash().canonicalText()));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sourceDescriptor", sourceDescriptor);
        metadata.put("authoredSource", authored);
        List<CatalogNodeDescriptor.Branch> branches = materializeBranches(runtime.semantics());
        CatalogNodeDescriptor.Builder builder = CatalogNodeDescriptor.builder(node.id())
            .schemaVersion(node.schemaVersion())
            .lifecycle(lifecycle(node.lifecycle()))
            .domain(node.domain())
            .family(node.family())
            .displayName(node.displayName())
            .description(node.description())
            .category(category.reference(node.owner()))
            .pins(pins)
            .modes(List.of())
            .branches(branches)
            .repeatables(repeatables)
            .handler(new CatalogNodeDescriptor.Handler(runtime.capability(), runtime.operation()))
            .semantics(runtime.semantics())
            .requiredCapabilities(Set.of(runtime.capability()))
            .metadata(metadata);
        String replacementFor = readOptionalText(node.source(), "replacementFor", 256);
        if (replacementFor != null) {
            builder.replacementIdentity(ContractRef.of(node.owner(), NodeId.of(replacementFor)));
        }
        return builder.build();
    }

    private static List<CatalogNodeDescriptor.Branch> materializeBranches(RuntimeSemantics semantics) {
        Set<String> success = semantics.successBranches();
        Set<String> failure = semantics.failureBranches();
        Set<String> cancellation = semantics.cancellationBranches();
        rejectBranchOverlap(success, failure);
        rejectBranchOverlap(success, cancellation);
        rejectBranchOverlap(failure, cancellation);
        Set<String> identities = new LinkedHashSet<>();
        identities.addAll(success);
        identities.addAll(failure);
        identities.addAll(cancellation);
        List<String> ordered = new ArrayList<>(identities);
        ordered.sort(String::compareTo);
        return ordered.stream().map(identity -> branch(identity, success.contains(identity)
            ? BranchKind.SUCCESS : failure.contains(identity) ? BranchKind.FAILURE : BranchKind.CANCELLATION)).toList();
    }

    private static void rejectBranchOverlap(Set<String> first, Set<String> second) {
        for (String identity : first) {
            if (second.contains(identity)) {
                throw new IllegalArgumentException("Runtime branch is classified in multiple outcome sets: " + identity);
            }
        }
    }

    private static CatalogNodeDescriptor.Branch branch(String identity, BranchKind kind) {
        if (kind == BranchKind.FAILURE && identity.equals("failure")) {
            return new CatalogNodeDescriptor.Branch("failure", "Failure",
                "Reports that this flow capability could not complete.", List.of(
                    new CatalogNodeDescriptor.Case("failure", "Failure",
                        "The flow capability reported a structured failure.")));
        }
        String title = branchTitle(identity);
        String description = switch (kind) {
            case SUCCESS -> "Reports that this flow capability completed successfully.";
            case FAILURE -> "Reports that this flow capability could not complete.";
            case CANCELLATION -> "Reports that this flow capability was cancelled.";
        };
        String caseDescription = switch (kind) {
            case SUCCESS -> "The flow capability completed successfully.";
            case FAILURE -> "The flow capability reported a structured failure.";
            case CANCELLATION -> "The flow capability was cancelled before completion.";
        };
        return new CatalogNodeDescriptor.Branch(identity, title, description,
            List.of(new CatalogNodeDescriptor.Case(identity, title, caseDescription)));
    }

    private static String branchTitle(String identity) {
        StringBuilder title = new StringBuilder(identity.length());
        boolean capitalize = true;
        for (int index = 0; index < identity.length(); index++) {
            char value = identity.charAt(index);
            if (value == '.' || value == '-' || value == '_') {
                title.append(' ');
                capitalize = true;
            } else if (capitalize) {
                title.append(Character.toUpperCase(value));
                capitalize = false;
            } else {
                title.append(value);
            }
        }
        return title.toString();
    }

    private enum BranchKind {
        SUCCESS,
        FAILURE,
        CANCELLATION
    }

    private static CatalogNodeDescriptor.Lifecycle lifecycle(String value) {
        try {
            return CatalogNodeDescriptor.Lifecycle.valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown catalog node lifecycle: " + value, exception);
        }
    }

    private static Map<String, Object> sourceDescriptor(Map<String, Object> source) {
        if (!source.containsKey("sourceDescriptor")) {
            return Map.of();
        }
        Object value = source.get("sourceDescriptor");
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Catalog sourceDescriptor must be an object");
        }
        return immutableObject(map);
    }

    private static List<CatalogMigrationEdge> parseMigrations(ParsedNode node) {
        if (!node.source().containsKey("migrationMapping")) {
            if (node.source().containsKey("migrationHistory")) {
                throw new IllegalArgumentException("Catalog migration history requires a current mapping: " + node.id().value());
            }
            return List.of();
        }
        CatalogMigrationEdge current = parseMigration(node, node.source().get("migrationMapping"), false);
        if (!node.source().containsKey("migrationHistory")) {
            return List.of(current);
        }
        Object rawHistory = node.source().get("migrationHistory");
        if (!(rawHistory instanceof List<?> history) || history.isEmpty() || history.size() > 64) {
            throw new IllegalArgumentException("Catalog migration history must contain one to 64 edges: " + node.id().value());
        }
        Map<?, ?> currentSource = (Map<?, ?>) node.source().get("migrationMapping");
        if (!currentSource.containsKey("id")) {
            throw new IllegalArgumentException("A versioned catalog migration requires an explicit current ID: " + node.id().value());
        }
        List<CatalogMigrationEdge> edges = new ArrayList<>(history.size() + 1);
        for (Object value : history) {
            edges.add(parseMigration(node, value, true));
        }
        edges.add(current);
        edges.sort(Comparator.comparingInt(CatalogMigrationEdge::fromVersion)
            .thenComparingInt(CatalogMigrationEdge::toVersion));
        Set<CapabilityId> identities = new LinkedHashSet<>();
        int previousTarget = edges.getFirst().fromVersion();
        for (CatalogMigrationEdge edge : edges) {
            if (!identities.add(edge.id()) || edge.fromVersion() != previousTarget) {
                throw new IllegalArgumentException("Catalog migration history must have unique IDs and one continuous version chain: " + node.id().value());
            }
            previousTarget = edge.toVersion();
        }
        if (edges.getLast() != current || previousTarget != node.schemaVersion()) {
            throw new IllegalArgumentException("Catalog migration history must end at the current mapping: " + node.id().value());
        }
        return List.copyOf(edges);
    }

    private static CatalogMigrationEdge parseMigration(ParsedNode node, Object rawMigration, boolean historical) {
        if (!(rawMigration instanceof Map<?, ?> migrationObject)) {
            throw new IllegalArgumentException("Catalog node migrationMapping must be an object: " + node.id().value());
        }
        Map<String, Object> migration = immutableObject(migrationObject);
        requireExactKeys(migration, Set.of("sourceSchemaVersion", "targetSchemaVersion", "complete", "pins"), Set.of("id"),
            "migrationMapping", node.id().value());
        if (historical && !migration.containsKey("id")) {
            throw new IllegalArgumentException("Historical catalog migration IDs must be explicit: " + node.id().value());
        }
        int sourceVersion = readPositiveInt(migration, "sourceSchemaVersion");
        int targetVersion = readPositiveInt(migration, "targetSchemaVersion");
        if (targetVersion <= sourceVersion) {
            throw new IllegalArgumentException("Catalog node migrationMapping target version must advance the source: " + node.id().value());
        }
        if (historical ? targetVersion >= node.schemaVersion() : targetVersion != node.schemaVersion()) {
            throw new IllegalArgumentException("Catalog node migrationMapping target version must match the node schema version: " + node.id().value());
        }
        Object rawComplete = migration.get("complete");
        if (!(rawComplete instanceof Boolean complete) || !complete) {
            throw new IllegalArgumentException("Catalog node migrationMapping must be complete: " + node.id().value());
        }
        Object rawPins = migration.get("pins");
        if (!(rawPins instanceof List<?> pins) || pins.isEmpty()) {
            throw new IllegalArgumentException("Catalog node migrationMapping must contain nonempty pins: " + node.id().value());
        }
        List<CatalogMigrationEdge.PinMapping> mappings = new ArrayList<>(pins.size());
        Set<String> sources = new LinkedHashSet<>();
        Set<String> targets = new LinkedHashSet<>();
        for (int index = 0; index < pins.size(); index++) {
            Object rawPin = pins.get(index);
            if (!(rawPin instanceof Map<?, ?> pinObject)) {
                throw new IllegalArgumentException("Catalog node migrationMapping pin must be an object: " + node.id().value() + "#" + index);
            }
            Map<String, Object> pin = immutableObject(pinObject);
            requireExactKeys(pin, Set.of("source", "target", "direction"), Set.of("identityMeaningful"),
                "migrationMapping pin", node.id().value() + "#" + index);
            String source = readString(pin, "source");
            String target = readString(pin, "target");
            PinId targetId = PinId.of(target);
            CatalogNodeDescriptor.Direction direction = migrationDirection(pin.get("direction"), node.id().value(), index);
            boolean identityMeaningful = source.equals(target);
            if (pin.containsKey("identityMeaningful")) {
                Object rawIdentity = pin.get("identityMeaningful");
                if (!(rawIdentity instanceof Boolean authoredIdentity) || authoredIdentity != identityMeaningful) {
                    throw new IllegalArgumentException("Catalog node migrationMapping identity marker is inconsistent: " + node.id().value() + "#" + index);
                }
            }
            String sourceKey = direction.name() + '\u0000' + source;
            String targetKey = direction.name() + '\u0000' + targetId.value();
            if (!sources.add(sourceKey) || !targets.add(targetKey)) {
                throw new IllegalArgumentException("Catalog node migrationMapping pin identities must be unique per direction: " + node.id().value());
            }
            mappings.add(new CatalogMigrationEdge.PinMapping(node.owner(), node.id(), sourceVersion, targetVersion,
                CatalogMigrationEdge.LegacyPinId.of(source), targetId, direction, identityMeaningful));
        }
        CapabilityId identity = CapabilityId.of(migration.containsKey("id") ? readString(migration, "id") : migrationId(node));
        return new CatalogMigrationEdge(identity, node.owner(), node.id(), sourceVersion, targetVersion,
            CatalogMigrationEdge.Kind.DECLARATIVE, List.of(node.id().value()), CatalogMigrationEdge.ConnectionPolicy.REMAP,
            List.of(), mappings);
    }

    private static String migrationId(ParsedNode node) {
        String value = "migration." + node.id().value();
        if (value.length() <= 128) {
            return value;
        }
        return "migration." + CanonicalJson.sha256("catalog.migration.id", node.id().value()).substring(0, 64);
    }

    private static CatalogNodeDescriptor.Direction migrationDirection(Object value, String nodeId, int index) {
        if (!(value instanceof String direction)) {
            throw new IllegalArgumentException("Catalog node migrationMapping direction must be text: " + nodeId + "#" + index);
        }
        try {
            return CatalogNodeDescriptor.Direction.valueOf(direction.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Catalog node migrationMapping direction is invalid: " + nodeId + "#" + index, exception);
        }
    }

    private static void requireExactKeys(Map<String, Object> value, Set<String> required, String field, String nodeId) {
        requireExactKeys(value, required, Set.of(), field, nodeId);
    }

    private static void requireExactKeys(Map<String, Object> value, Set<String> required, Set<String> optional,
                                         String field, String nodeId) {
        Set<String> allowed = new LinkedHashSet<>(required);
        allowed.addAll(optional);
        if (!value.keySet().equals(allowed) && !value.keySet().equals(required)) {
            throw new IllegalArgumentException("Catalog node " + field + " has unsupported fields: " + nodeId);
        }
        if (!value.keySet().containsAll(required)) {
            throw new IllegalArgumentException("Catalog node " + field + " is missing required fields: " + nodeId);
        }
    }

    private static void rejectUnsupportedMaterial(ParsedNode node) {
        rejectEmptyList(node.source(), "pins");
        rejectEmptyContainer(node.source(), "migration");
        rejectEmptyContainer(node.source(), "migrations");
        rejectEmptyContainer(node.source(), "pinMappings");
    }

    private static void validateTrigger(ParsedNode node) {
        if (!node.trigger()) {
            return;
        }
        Object rawOutputs = node.source().get("outputs");
        if (!(rawOutputs instanceof List<?> outputs)) {
            throw new IllegalArgumentException("Trigger catalog nodes must declare outputs: " + node.id().value());
        }
        for (int index = 0; index < outputs.size(); index++) {
            Object value = outputs.get(index);
            if (!(value instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("Catalog node outputs entry " + index + " must be an object");
            }
            if (readPinType(immutableObject(map), "outputs", index) == PinType.FLOW) {
                return;
            }
        }
        throw new IllegalArgumentException("Trigger catalog nodes must declare a FLOW output: " + node.id().value());
    }

    private static void rejectEmptyList(Map<String, Object> source, String field) {
        if (!source.containsKey(field)) {
            return;
        }
        Object value = source.get(field);
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Catalog node " + field + " must be an array");
        }
        if (!list.isEmpty()) {
            throw new IllegalArgumentException("Catalog node pin material is not supported yet: " + field);
        }
    }

    private static void rejectEmptyContainer(Map<String, Object> source, String field) {
        if (!source.containsKey(field)) {
            return;
        }
        Object value = source.get(field);
        if (value == null) {
            throw new IllegalArgumentException("Catalog node migration material must not be null: " + field);
        }
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return;
        }
        if (value instanceof List<?> list && list.isEmpty()) {
            return;
        }
        throw new IllegalArgumentException("Catalog node migration material is not supported yet: " + field);
    }

    private static Map<String, Object> immutableObject(Map<?, ?> object) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : object.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("Catalog source object keys must be strings");
            }
            copy.put(key, immutableValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> object) {
            return immutableObject(object);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object entry : list) {
                copy.add(immutableValue(entry));
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    private static OwnerId readOwner(Map<String, Object> row, OwnerId sourceOwner) {
        if (!row.containsKey("owner")) {
            return sourceOwner;
        }
        OwnerId owner = OwnerId.of(readString(row, "owner"));
        if (!sourceOwner.equals(owner)) {
            throw new IllegalArgumentException("Catalog node owner must match its source owner");
        }
        return owner;
    }

    private static NodeId readNodeId(Map<String, Object> row, String field) {
        return NodeId.of(readString(row, field));
    }

    private static String readLocal(Map<String, Object> row, String field) {
        return CatalogIds.local(readString(row, field), field);
    }

    private static String operation(ParsedNode node) {
        Object raw = node.handlerConfig().get("operation");
        if (raw instanceof String value && !value.isBlank()) {
            return CatalogIds.local(value.strip(), "operation");
        }
        if (node.trigger() && (raw == null || raw instanceof String)) {
            return "trigger_" + node.id().value();
        }
        throw new IllegalArgumentException("Catalog node handler operation must be a non-blank string: " + node.id().value());
    }

    private static String readText(Map<String, Object> row, String field, int maximum) {
        return CatalogIds.text(readString(row, field), field, maximum);
    }

    private static String readString(Map<String, Object> row, String field) {
        Object value = row.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Catalog node field must be nonblank text: " + field);
        }
        return text;
    }

    private static int readPositiveInt(Map<String, Object> row, String field) {
        Object value = row.get(field);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Catalog node field must be a positive integer: " + field);
        }
        try {
            int result = new BigDecimal(number.toString()).intValueExact();
            if (result < 1) {
                throw new IllegalArgumentException("Catalog node field must be a positive integer: " + field);
            }
            return result;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Catalog node field must be a positive integer: " + field, exception);
        }
    }

    private static int readNonNegativeInt(Map<String, Object> row, String field) {
        Object value = row.get(field);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Catalog node field must be a non-negative integer: " + field);
        }
        try {
            int result = new BigDecimal(number.toString()).intValueExact();
            if (result < 0) {
                throw new IllegalArgumentException("Catalog node field must be a non-negative integer: " + field);
            }
            return result;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Catalog node field must be a non-negative integer: " + field, exception);
        }
    }

    private static boolean readBoolean(Map<String, Object> row, String field, boolean fallback) {
        if (!row.containsKey(field)) {
            return fallback;
        }
        Object value = row.get(field);
        if (!(value instanceof Boolean result)) {
            throw new IllegalArgumentException("Catalog node field must be a boolean: " + field);
        }
        return result;
    }

    private static Map<String, Object> readMap(Map<String, Object> row, String field) {
        Object value = row.get(field);
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Catalog node field must be an object: " + field);
        }
        return immutableObject(map);
    }

    private record ParsedSource(List<Map<String, Object>> rows, ContentHash sourceHash) {
        private ParsedSource {
            rows = List.copyOf(rows);
            sourceHash = Objects.requireNonNull(sourceHash, "Catalog source hash is required");
        }
    }

    private record ParsedNode(
        OwnerId owner,
        NodeId id,
        String displayName,
        String description,
        String domain,
        String family,
        String lifecycle,
        String handlerCapability,
        String category,
        int schemaVersion,
        String kind,
        boolean trigger,
        String eventType,
        String handler,
        Map<String, Object> handlerConfig,
        Map<String, Object> source,
        CatalogProvenance.SourceEntry provenance
    ) {
        private ParsedNode {
            owner = Objects.requireNonNull(owner, "Parsed node owner is required");
            id = Objects.requireNonNull(id, "Parsed node ID is required");
            displayName = Objects.requireNonNull(displayName, "Parsed node display name is required");
            description = Objects.requireNonNull(description, "Parsed node description is required");
            domain = Objects.requireNonNull(domain, "Parsed node domain is required");
            family = Objects.requireNonNull(family, "Parsed node family is required");
            lifecycle = Objects.requireNonNull(lifecycle, "Parsed node lifecycle is required");
            handlerCapability = Objects.requireNonNull(handlerCapability, "Parsed node handler capability is required");
            category = Objects.requireNonNull(category, "Parsed node category is required");
            if (schemaVersion < 1) {
                throw new IllegalArgumentException("Parsed node schema version must be positive");
            }
            kind = CatalogIds.text(kind, "kind", 64);
            if (trigger && !"EVENT".equalsIgnoreCase(kind)) {
                throw new IllegalArgumentException("Trigger catalog nodes must use kind EVENT");
            }
            if (!trigger && "EVENT".equalsIgnoreCase(kind)) {
                throw new IllegalArgumentException("EVENT catalog nodes must be triggers");
            }
            eventType = trigger ? CatalogIds.text(eventType, "eventType", 512) : null;
            handler = handler == null ? null : CatalogIds.text(handler, "handler", 256);
            handlerConfig = immutableObject(Objects.requireNonNull(handlerConfig, "Parsed node handler config is required"));
            source = immutableObject(Objects.requireNonNull(source, "Parsed node source is required"));
            provenance = Objects.requireNonNull(provenance, "Parsed node provenance is required");
        }
    }

    private record PreparedCatalogSource(byte[] bytes, ParsedSource parsed, OwnerId ownerProof) {
        private PreparedCatalogSource {
            bytes = Arrays.copyOf(Objects.requireNonNull(bytes, "Prepared catalog source bytes are required"), bytes.length);
            parsed = Objects.requireNonNull(parsed, "Prepared catalog source rows are required");
            ownerProof = Objects.requireNonNull(ownerProof, "Prepared catalog source owner proof is required");
        }

        @Override
        public byte[] bytes() {
            return Arrays.copyOf(bytes, bytes.length);
        }
    }

    public static final class CatalogSource {
        private final OwnerId owner;
        private final CatalogProvenance.SourceKind sourceKind;
        private final String sourceUri;
        private final String sourceVersion;
        private final String buildId;
        private final byte[] bytes;
        private final PreparedCatalogSource prepared;

        public CatalogSource(OwnerId owner, CatalogProvenance.SourceKind sourceKind, String sourceUri,
                             String sourceVersion, String buildId, byte[] bytes) {
            this.owner = Objects.requireNonNull(owner, "Catalog source owner is required");
            this.sourceKind = Objects.requireNonNull(sourceKind, "Catalog source kind is required");
            this.sourceUri = CatalogIds.text(sourceUri, "sourceUri", 1024);
            this.sourceVersion = CatalogIds.text(sourceVersion, "sourceVersion", 128);
            this.buildId = CatalogIds.text(buildId, "buildId", 128);
            this.bytes = Arrays.copyOf(Objects.requireNonNull(bytes, "Catalog source bytes are required"), bytes.length);
            this.prepared = null;
        }

        private CatalogSource(OwnerId owner, CatalogProvenance.SourceKind sourceKind, String sourceUri,
                              String sourceVersion, String buildId, PreparedCatalogSource prepared) {
            this.owner = Objects.requireNonNull(owner, "Catalog source owner is required");
            this.sourceKind = Objects.requireNonNull(sourceKind, "Catalog source kind is required");
            this.sourceUri = CatalogIds.text(sourceUri, "sourceUri", 1024);
            this.sourceVersion = CatalogIds.text(sourceVersion, "sourceVersion", 128);
            this.buildId = CatalogIds.text(buildId, "buildId", 128);
            this.prepared = Objects.requireNonNull(prepared, "Prepared catalog source is required");
            this.bytes = prepared.bytes();
        }

        public static CatalogSource prepared(OwnerId owner, CatalogProvenance.SourceKind sourceKind, String sourceUri,
                                             String sourceVersion, String buildId, byte[] bytes,
                                             ContentHash expectedSourceHash) {
            OwnerId sourceOwner = Objects.requireNonNull(owner, "Catalog source owner is required");
            ContentHash sourceHash = Objects.requireNonNull(expectedSourceHash,
                "Prepared catalog source hash proof is required");
            return new CatalogSource(sourceOwner, sourceKind, sourceUri, sourceVersion, buildId,
                prepareSource(sourceOwner, bytes, sourceHash));
        }

        public OwnerId owner() {
            return owner;
        }

        public CatalogProvenance.SourceKind sourceKind() {
            return sourceKind;
        }

        public String sourceUri() {
            return sourceUri;
        }

        public String sourceVersion() {
            return sourceVersion;
        }

        public String buildId() {
            return buildId;
        }

        public byte[] bytes() {
            return Arrays.copyOf(bytes, bytes.length);
        }

        public ContentHash sourceHash() {
            return prepared != null ? prepared.parsed().sourceHash()
                : prepareSource(owner, bytes, null).parsed().sourceHash();
        }

        public OwnerId ownerProof() {
            return prepared != null ? prepared.ownerProof() : owner;
        }
    }

    public record CatalogIngestionContext(
        CatalogContractRange contractRange,
        List<CatalogCategoryDescriptor> categories,
        InspectorCapability editorCapability,
        OptionSourceResolver optionSources,
        RuntimeRequirementResolver runtimeRequirements,
        ResourceTypeResolver resourceTypes
    ) {
        public CatalogIngestionContext {
            contractRange = Objects.requireNonNull(contractRange, "Catalog contract range is required");
            categories = List.copyOf(Objects.requireNonNull(categories, "Catalog categories are required"));
            editorCapability = Objects.requireNonNull(editorCapability, "Catalog editor capability is required");
            optionSources = Objects.requireNonNull(optionSources, "Option source resolver is required");
            runtimeRequirements = Objects.requireNonNull(runtimeRequirements, "Runtime requirement resolver is required");
            resourceTypes = Objects.requireNonNull(resourceTypes, "Resource type resolver is required");
        }
    }

    @FunctionalInterface
    public interface OptionSourceResolver {
        Optional<InspectorOptionSource> resolve(InspectorFieldId source);
    }

    @FunctionalInterface
    public interface RuntimeRequirementResolver {
        Optional<RuntimeOperationDescriptor> resolve(RuntimeRequest request);
    }

    @FunctionalInterface
    public interface ResourceTypeResolver {
        Optional<ContractRef<ResourceTypeId>> resolve(ContractRef<ResourceTypeId> resourceType);
    }

    public record RuntimeRequest(
        OwnerId owner,
        NodeId node,
        ContractRef<CapabilityId> capability,
        ContractRef<OperationId> operation,
        List<RuntimeOperationDescriptor.Pin> pins,
        String handler,
        Map<String, Object> handlerConfig,
        boolean trigger,
        Map<String, Object> source
    ) {
        public RuntimeRequest {
            owner = Objects.requireNonNull(owner, "Runtime owner is required");
            node = Objects.requireNonNull(node, "Runtime node is required");
            capability = Objects.requireNonNull(capability, "Runtime capability is required");
            operation = Objects.requireNonNull(operation, "Runtime operation is required");
            pins = List.copyOf(Objects.requireNonNull(pins, "Runtime pins are required"));
            handler = handler == null ? null : CatalogIds.text(handler, "handler", 256);
            handlerConfig = Map.copyOf(Objects.requireNonNull(handlerConfig, "Runtime handler config is required"));
            source = Map.copyOf(Objects.requireNonNull(source, "Runtime source is required"));
        }
    }
}

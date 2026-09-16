package restudio.resync.upgrade.lifecycle;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.QuarantineRecord;
import restudio.resync.upgrade.TypedLifecycleMigrationAdapter;

public final class TriggerCommandMigrationAdapter implements TypedLifecycleMigrationAdapter {
    public static final String ID = "resync.lifecycle.trigger-command-v1";
    private static final String TRIGGERS = "triggers.json";
    private static final List<String> COMMAND_PREFIXES = List.of("assets/commands/", "assets/blueprints/commands/");

    @Override
    public String adapterId() {
        return ID;
    }

    @Override
    public Adaptation adapt(Input input) throws IOException {
        Objects.requireNonNull(input, "input");
        Optional<SourceFile> triggerSource = input.file(TRIGGERS);
        List<Claim> claims = new ArrayList<>();
        List<QuarantineRecord> quarantine = new ArrayList<>();
        List<CommandGraph> graphs = commandGraphs(input, claims, quarantine);
        if (triggerSource.isEmpty()) {
            return ownersMatch(input, claims) ? new Adaptation(claims, List.of(), quarantine) : Adaptation.claimed(claims);
        }
        claims.add(new Claim(TRIGGERS, ProductionPersistenceOwners.TRIGGERS));
        if (!ownersMatch(input, claims)) {
            return Adaptation.claimed(claims);
        }

        Object triggerValue;
        try {
            triggerValue = CanonicalJson.parse(input.read(triggerSource.get()));
        } catch (IllegalArgumentException exception) {
            quarantine.add(quarantine(
                triggerSource.get(),
                "MIGRATION.COMMAND_BINDING_INVALID",
                TRIGGERS,
                "The trigger registry is not valid JSON and command rows cannot be selected safely.",
                List.of(),
                "Repair or restore the trigger registry before migrating command bindings."));
            return new Adaptation(claims, List.of(), quarantine);
        }

        TriggerDocument triggers;
        try {
            triggers = TriggerDocument.parse(triggerValue);
        } catch (IllegalArgumentException exception) {
            quarantine.add(quarantine(
                triggerSource.get(),
                "MIGRATION.COMMAND_BINDING_INVALID",
                TRIGGERS,
                exception.getMessage(),
                List.of(),
                "Repair the unsupported command rows before migrating the trigger registry."));
            return new Adaptation(claims, List.of(), quarantine);
        }
        if (triggers.commands().isEmpty()) {
            return new Adaptation(claims, List.of(), quarantine);
        }

        Map<String, List<CommandGraph>> graphsById = new LinkedHashMap<>();
        graphs.forEach(graph -> graphsById.computeIfAbsent(graph.id(), ignored -> new ArrayList<>()).add(graph));
        List<Change> changes = new ArrayList<>();
        Set<Integer> migratedRows = new LinkedHashSet<>();
        Map<CommandGraph, List<CommandBinding>> bindingsByGraph = new LinkedHashMap<>();

        for (CommandBinding binding : triggers.commands()) {
            List<CommandGraph> matches = graphsById.getOrDefault(binding.resourceId(), List.of());
            if (matches.isEmpty()) {
                quarantine.add(quarantine(triggerSource.get(), "MIGRATION.COMMAND_RESOURCE_MISSING", binding.location(),
                    "The command binding has no typed Command graph with the same resource ID.", List.of("command/" + binding.resourceId()),
                    "Restore the missing Command graph or remove the stale binding after review."));
            } else if (matches.size() > 1) {
                quarantine.add(quarantine(triggerSource.get(), "MIGRATION.COMMAND_RESOURCE_AMBIGUOUS", binding.location(),
                    "More than one typed Command graph has this resource ID.", matches.stream().map(graph -> graph.source().relativePath()).toList(),
                    "Keep one authoritative Command graph and quarantine the duplicates before retrying."));
            } else {
                CommandGraph graph = matches.getFirst();
                if (!graph.hasAuthority()) {
                    continue;
                }
                if (!binding.matchesAuthority(graph)) {
                    quarantine.add(quarantine(triggerSource.get(), "MIGRATION.COMMAND_AUTHORITY_MISMATCH", binding.location(),
                        "The binding revision or mutation ID does not match the authoritative Command graph.", List.of(graph.source().relativePath()),
                        "Reconcile the stale binding with the authoritative Command graph before retrying."));
                } else {
                    bindingsByGraph.computeIfAbsent(graph, ignored -> new ArrayList<>()).add(binding);
                }
            }
        }

        for (Map.Entry<CommandGraph, List<CommandBinding>> entry : bindingsByGraph.entrySet()) {
            CommandGraph graph = entry.getKey();
            List<CommandBinding> bindings = entry.getValue();
            CommandOwnership ownership = CommandOwnership.from(bindings);
            if (ownership == null) {
                quarantine.add(quarantine(triggerSource.get(), "MIGRATION.COMMAND_BINDING_AMBIGUOUS", "triggers.json#command/" + graph.id(),
                    "Command rows disagree on the command label or structured mode.", bindings.stream().map(CommandBinding::location).toList(),
                    "Resolve the conflicting command rows and keep one authoritative command definition."));
                continue;
            }
            Map<String, Object> migrated = graph.migrate(ownership);
            if (migrated == null) {
                quarantine.add(quarantine(graph.source(), "MIGRATION.COMMAND_GRAPH_CONFLICT", graph.source().relativePath(),
                    "The Command graph already contains command ownership fields that conflict with the legacy bindings.",
                    bindings.stream().map(CommandBinding::location).toList(),
                    "Reconcile commandLabel, structured, and commandPaths in the graph before retrying."));
                continue;
            }
            byte[] target = CanonicalJson.canonicalBytes(migrated);
            if (!graph.source().sha256().equals(sha256(target))) {
                changes.add(new Change("migrate-command-binding", graph.source().relativePath(), graph.source().relativePath(), target));
            }
            bindings.forEach(binding -> migratedRows.add(binding.index()));
        }

        if (!migratedRows.isEmpty()) {
            byte[] target = CanonicalJson.canonicalBytes(triggers.without(migratedRows));
            if (!triggerSource.get().sha256().equals(sha256(target))) {
                changes.add(new Change("remove-migrated-command-rows", TRIGGERS, TRIGGERS, target));
            }
        }
        return new Adaptation(claims, quarantine.isEmpty() ? changes : List.of(), quarantine);
    }

    static CommandReferences commandReferences(byte[] bytes) {
        try {
            TriggerDocument document = TriggerDocument.parse(CanonicalJson.parse(bytes));
            return new CommandReferences(true, document.commands().stream().map(CommandBinding::resourceId)
                .collect(Collectors.toUnmodifiableSet()));
        } catch (IllegalArgumentException exception) {
            return new CommandReferences(false, Set.of());
        }
    }

    record CommandReferences(boolean valid, Set<String> resourceIds) {
        CommandReferences {
            resourceIds = Set.copyOf(resourceIds);
        }

        boolean contains(String resourceId) {
            return resourceIds.contains(resourceId);
        }
    }

    private static boolean ownersMatch(Input input, List<Claim> claims) {
        return claims.stream().allMatch(claim -> input.file(claim.relativePath()).map(source -> source.owner().equals(claim.owner())).orElse(false));
    }

    private static List<CommandGraph> commandGraphs(Input input, List<Claim> claims, List<QuarantineRecord> quarantine) throws IOException {
        List<CommandGraph> graphs = new ArrayList<>();
        for (SourceFile source : input.files()) {
            String path = source.relativePath().toLowerCase(Locale.ROOT);
            if (!path.endsWith(".json") || source.relativePath().equals(TRIGGERS)) {
                continue;
            }
            boolean commandPath = COMMAND_PREFIXES.stream().anyMatch(path::startsWith);
            if (commandPath) {
                claims.add(new Claim(source.relativePath(), ProductionPersistenceOwners.FLOW_ASSETS));
            }
            Object value;
            try {
                value = CanonicalJson.parse(input.read(source));
            } catch (IllegalArgumentException exception) {
                if (commandPath) {
                    quarantine.add(quarantine(source, "MIGRATION.COMMAND_GRAPH_INVALID", source.relativePath(),
                        "The Command graph is not valid canonical JSON: " + exception.getMessage(), List.of(source.relativePath()),
                        "Repair or restore the Command graph before retrying."));
                }
                continue;
            }
            if (!(value instanceof Map<?, ?> raw)) {
                if (commandPath) {
                    quarantine.add(quarantine(source, "MIGRATION.COMMAND_GRAPH_INVALID", source.relativePath(),
                        "The Command graph must be a JSON object.", List.of(source.relativePath()),
                        "Repair or restore the Command graph before retrying."));
                }
                continue;
            }
            Map<String, Object> graph = object(raw, source.relativePath());
            if (!"command".equals(graph.get("resourceType"))) {
                if (commandPath) {
                    quarantine.add(quarantine(source, "MIGRATION.COMMAND_GRAPH_INVALID", source.relativePath(),
                        "The Command graph path does not contain a declared Command resource.", List.of(source.relativePath()),
                        "Restore resourceType to command or relocate the resource through an explicit typed transaction."));
                }
                continue;
            }
            if (!commandPath) {
                claims.add(new Claim(source.relativePath(), ProductionPersistenceOwners.FLOW_ASSETS));
            }
            try {
                String id = text(graph.get("id"), source.relativePath() + ".id");
                CommandGraph command = new CommandGraph(source, id, graph);
                graphs.add(command);
                if (!command.hasAuthority()) {
                    quarantine.add(quarantine(source, "MIGRATION.COMMAND_AUTHORITY_INVALID", source.relativePath(),
                        "The typed Command graph is missing a valid resource revision or mutation ID.", List.of("command/" + id),
                        "Restore the authoritative resource revision and mutation ID before retrying."));
                }
            } catch (IllegalArgumentException exception) {
                quarantine.add(quarantine(source, "MIGRATION.COMMAND_GRAPH_INVALID", source.relativePath(),
                    "The declared Command graph has an invalid typed identity: " + exception.getMessage(), List.of(source.relativePath()),
                    "Restore the exact Command resource ID before retrying."));
            }
        }
        graphs.sort(Comparator.comparing(graph -> graph.source().relativePath()));
        return List.copyOf(graphs);
    }

    private static QuarantineRecord quarantine(SourceFile source, String code, String location, String reason, List<String> references, String action) {
        String recordId = "command-" + CanonicalJson.sha256("migration.trigger-command-quarantine", List.of(code, location, source.sha256())).substring(0, 24);
        return new QuarantineRecord(recordId, code, source.relativePath(), reason, references, action, source.sha256());
    }

    private static String sha256(byte[] value) {
        return HexFormat.of().formatHex(digest(value));
    }

    private static byte[] digest(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 Is Unavailable", exception);
        }
    }

    private record CommandGraph(SourceFile source, String id, Map<String, Object> value) {
        private long revision() {
            Object revision = value.get("resourceRevision");
            if (!(revision instanceof BigDecimal number)) {
                return -1;
            }
            try {
                long parsedRevision = number.longValueExact();
                return parsedRevision < 0 ? -1 : parsedRevision;
            } catch (ArithmeticException exception) {
                return -1;
            }
        }

        private String mutationId() {
            Object mutation = value.containsKey("mutationId") ? value.get("mutationId") : value.get("resourceMutationId");
            return mutation instanceof String text ? text : "";
        }

        private boolean hasAuthority() {
            return revision() >= 0 && !mutationId().isBlank();
        }

        private Map<String, Object> migrate(CommandOwnership ownership) {
            if (!compatible("commandLabel", ownership.commandLabel()) || !compatible("structured", ownership.structured())
                || !compatiblePaths(value.get("commandPaths"), ownership.commandPaths())) {
                return null;
            }
            Map<String, Object> migrated = new LinkedHashMap<>(value);
            migrated.put("commandLabel", ownership.commandLabel());
            migrated.put("structured", ownership.structured());
            migrated.put("commandPaths", ownership.commandPaths());
            return migrated;
        }

        private boolean compatible(String field, Object expected) {
            return !value.containsKey(field) || Objects.equals(value.get(field), expected);
        }

        private boolean compatiblePaths(Object current, List<String> expected) {
            if (current == null) {
                return true;
            }
            if (!(current instanceof List<?> list)) {
                return false;
            }
            try {
                return canonicalPaths(list).equals(expected);
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }
    }

    private record CommandOwnership(String commandLabel, boolean structured, List<String> commandPaths) {
        private static CommandOwnership from(List<CommandBinding> bindings) {
            Set<String> labels = new LinkedHashSet<>();
            Set<Boolean> modes = new LinkedHashSet<>();
            Set<String> paths = new LinkedHashSet<>();
            for (CommandBinding binding : bindings) {
                labels.add(binding.commandLabel());
                modes.add(binding.structured());
                paths.addAll(binding.commandPaths());
            }
            if (labels.size() != 1 || modes.size() != 1) {
                return null;
            }
            return new CommandOwnership(labels.iterator().next(), modes.iterator().next(), paths.stream().sorted().toList());
        }
    }

    private record CommandBinding(int index, String resourceId, String commandLabel, boolean structured, List<String> commandPaths,
                                  Long revision, String mutationId, String location) {
        private boolean matchesAuthority(CommandGraph graph) {
            return (revision == null || revision == graph.revision()) && (mutationId == null || mutationId.equals(graph.mutationId()));
        }
    }

    private record TriggerDocument(Object source, List<CommandBinding> commands, boolean flat) {
        private static TriggerDocument parse(Object source) {
            if (source instanceof List<?> rows) {
                return flat(rows);
            }
            if (source instanceof Map<?, ?> raw) {
                return structured(object(raw, TRIGGERS));
            }
            throw new IllegalArgumentException("The trigger registry must be a JSON array or object.");
        }

        private static TriggerDocument flat(List<?> rows) {
            List<Object> values = new ArrayList<>(rows);
            List<CommandBinding> commands = new ArrayList<>();
            for (int index = 0; index < values.size(); index++) {
                Object value = values.get(index);
                if (!(value instanceof Map<?, ?> raw)) {
                    throw new IllegalArgumentException("Trigger rows must be JSON objects.");
                }
                Map<String, Object> row = object(raw, "triggers.json[" + index + "]");
                if (!(row.get("type") instanceof String type) || !type.equalsIgnoreCase("command")) {
                    continue;
                }
                String resourceId = text(row.get("flowId"), "triggers.json[" + index + "].flowId");
                Context context = Context.parse(row.get("context"), "triggers.json[" + index + "].context");
                commands.add(new CommandBinding(index, resourceId, context.commandLabel(), context.structured(), context.commandPaths(),
                    optionalLong(row, "resourceRevision"), optionalText(row, "mutationId", "resourceMutationId"), "triggers.json[" + index + "]"));
            }
            return new TriggerDocument(values, List.copyOf(commands), true);
        }

        private static TriggerDocument structured(Map<String, Object> root) {
            if (!root.containsKey("commands")) {
                return new TriggerDocument(root, List.of(), false);
            }
            Object commandValue = root.get("commands");
            if (!(commandValue instanceof List<?> rows)) {
                throw new IllegalArgumentException("triggers.json.commands must be an array.");
            }
            List<CommandBinding> commands = new ArrayList<>();
            for (int index = 0; index < rows.size(); index++) {
                if (!(rows.get(index) instanceof Map<?, ?> raw)) {
                    throw new IllegalArgumentException("Command trigger rows must be JSON objects.");
                }
                Map<String, Object> row = object(raw, "triggers.json.commands[" + index + "]");
                Map<String, Object> resource = object(row.get("resource"), "triggers.json.commands[" + index + "].resource");
                if (!"command".equals(text(resource.get("type"), "resource.type"))) {
                    throw new IllegalArgumentException("Command trigger resources must use the Command type.");
                }
                String path = canonicalPath(text(row.get("path"), "command.path"));
                String label = path.substring(0, path.indexOf(' ') < 0 ? path.length() : path.indexOf(' '));
                commands.add(new CommandBinding(index, text(resource.get("id"), "resource.id"), label,
                    optionalBoolean(row, "structured"), List.of(path), optionalLong(row, "resourceRevision", "revision"),
                    optionalText(row, "mutationId", "resourceMutationId"), "triggers.json.commands[" + index + "]"));
            }
            return new TriggerDocument(root, List.copyOf(commands), false);
        }

        private Object without(Set<Integer> migrated) {
            if (flat) {
                List<Object> rows = new ArrayList<>((List<?>) source);
                return IntStream.range(0, rows.size()).filter(index -> !migrated.contains(index)).mapToObj(rows::get).toList();
            }
            Map<String, Object> root = new LinkedHashMap<>(castObject(source));
            List<?> rows = (List<?>) root.get("commands");
            root.put("commands", IntStream.range(0, rows.size()).filter(index -> !migrated.contains(index)).mapToObj(rows::get).toList());
            return root;
        }
    }

    private record Context(String commandLabel, boolean structured, List<String> commandPaths) {
        private static Context parse(Object value, String field) {
            String context = text(value, field).trim();
            if (!context.startsWith("{")) {
                String label = canonicalLabel(context);
                return new Context(label, false, List.of(label));
            }
            Object parsed = CanonicalJson.parse(context);
            Map<String, Object> object = castObject(parsed);
            String label = canonicalLabel(text(object.get("command"), field + ".command"));
            boolean structured = optionalBoolean(object, "structured");
            List<String> paths = new ArrayList<>();
            Object subcommands = object.get("subcommands");
            if (object.containsKey("subcommands") && !(subcommands instanceof List<?>)) {
                throw new IllegalArgumentException(field + ".subcommands must be an array.");
            }
            if (subcommands instanceof List<?> list && !list.isEmpty()) {
                for (Object subcommand : list) {
                    paths.add(canonicalPath(label + " " + text(subcommand, field + ".subcommands[]")));
                }
            } else {
                paths.add(label);
            }
            return new Context(label, structured, paths.stream().distinct().sorted().toList());
        }
    }

    private static List<String> canonicalPaths(List<?> values) {
        return values.stream().map(value -> canonicalPath(text(value, "commandPaths[]"))).distinct().sorted().toList();
    }

    private static String canonicalPath(String value) {
        String normalized = value.trim().replaceAll("\\s+", " ");
        int separator = normalized.indexOf(' ');
        String label = separator < 0 ? normalized : normalized.substring(0, separator);
        String suffix = separator < 0 ? "" : normalized.substring(separator);
        return canonicalLabel(label) + suffix;
    }

    private static String canonicalLabel(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        int namespace = normalized.indexOf(':');
        if (namespace >= 0 && namespace < normalized.length() - 1) {
            normalized = normalized.substring(namespace + 1);
        }
        if (normalized.isBlank() || normalized.indexOf(' ') >= 0) {
            throw new IllegalArgumentException("Command labels must be non-blank single tokens.");
        }
        return normalized;
    }

    private static Long optionalLong(Map<String, Object> value, String... fields) {
        for (String field : fields) {
            if (!value.containsKey(field)) {
                continue;
            }
            if (!(value.get(field) instanceof BigDecimal number)) {
                throw new IllegalArgumentException(field + " must be an integer.");
            }
            try {
                long revision = number.longValueExact();
                if (revision < 0) {
                    throw new IllegalArgumentException(field + " must be non-negative.");
                }
                return revision;
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException(field + " must be an integer.", exception);
            }
        }
        return null;
    }

    private static String optionalText(Map<String, Object> value, String... fields) {
        for (String field : fields) {
            if (!value.containsKey(field)) {
                continue;
            }
            return text(value.get(field), field);
        }
        return null;
    }

    private static boolean optionalBoolean(Map<String, Object> value, String field) {
        if (!value.containsKey(field)) {
            return false;
        }
        if (!(value.get(field) instanceof Boolean result)) {
            throw new IllegalArgumentException(field + " must be a boolean.");
        }
        return result;
    }

    private static Map<String, Object> object(Object value, String field) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(field + " must be a JSON object.");
        }
        return object(raw, field);
    }

    private static Map<String, Object> object(Map<?, ?> raw, String field) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(field + " contains a non-string key.");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static Map<String, Object> castObject(Object value) {
        return object(value, "value");
    }

    private static String text(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank() || text.indexOf('\u0000') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " must be non-blank text.");
        }
        return CanonicalJson.requireNfc(text);
    }
}

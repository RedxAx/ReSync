package restudio.resync.customcontent;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import restudio.flow.data.CustomContentDefinition;
import restudio.resync.Log;
import restudio.resync.ReSync;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.CanonicalHash;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.util.TextFormatter;
import restudio.resync.migration.AtomicFiles;
import restudio.resync.migration.MigrationPaths;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public class VanillaContentProvider implements CustomContentProvider {
    private final NamespacedKey contentTypeKey;
    private final NamespacedKey contentIdKey;
    private final NamespacedKey contentVersionKey;
    private final NamespacedKey instanceIdKey;
    private final ItemAttributeSchemaService attributeSchemaService;
    private final Object persistenceMonitor = new Object();
    private Map<String, String> blocks = new LinkedHashMap<>();
    private volatile Map<String, String> publishedBlocks = Map.of();
    private volatile ActiveBinding activeBinding;
    private boolean quiesced;
    private boolean quiescing;
    private boolean quiesceFailed;
    private boolean closing;
    private boolean closed;
    private IOException persistenceFailure;

    private static final String DOCUMENT_KIND = "resync.custom-blocks";
    private static final int DOCUMENT_VERSION = 1;
    private static final String FILE_NAME = "custom-blocks.json";
    private static final int MAX_WORLD_NAME_BYTES = 256;
    private static final Pattern BLOCK_ID = Pattern.compile("[A-Za-z0-9_.-]{1,96}");
    private static final Pattern COORDINATE_INTEGER = Pattern.compile("(?:0|[1-9][0-9]*|-[1-9][0-9]*)");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> DOCUMENT_FIELDS = Set.of("contentHash", "kind", "mappings", "version");
    private static final Set<String> MAPPING_FIELDS = Set.of("block", "world", "x", "y", "z");

    public VanillaContentProvider() {
        this(ReSync.getInstance(), new ItemAttributeSchemaService());
    }

    public VanillaContentProvider(ItemAttributeSchemaService attributeSchemaService) {
        this(ReSync.getInstance(), attributeSchemaService);
    }

    public VanillaContentProvider(JavaPlugin plugin, ItemAttributeSchemaService attributeSchemaService) {
        this(plugin, attributeSchemaService, plugin == null ? null : plugin.getDataFolder().toPath());
    }

    public VanillaContentProvider(JavaPlugin plugin, ItemAttributeSchemaService attributeSchemaService, Path dataRoot) {
        if (plugin == null) {
            throw new IllegalArgumentException("Plugin cannot be null");
        }
        this.contentTypeKey = new NamespacedKey(plugin, "content_type");
        this.contentIdKey = new NamespacedKey(plugin, "content_id");
        this.contentVersionKey = new NamespacedKey(plugin, "content_version");
        this.instanceIdKey = new NamespacedKey(plugin, "instance_id");
        this.attributeSchemaService = attributeSchemaService != null ? attributeSchemaService : new ItemAttributeSchemaService();
        Path file = MigrationPaths.requirePath(dataRoot, "dataRoot").resolve(FILE_NAME);
        try {
            Files.createDirectories(requireParent(file));
            DecodedBlocks decoded = readBlocks(file, false);
            synchronized (persistenceMonitor) {
                activeBinding = new ActiveBinding(file, 0L);
                blocks = decoded.blocks();
                boolean exists = Files.exists(file, LinkOption.NOFOLLOW_LINKS);
                if (!exists) {
                    writeBlocks(file, blocks);
                }
                publishPlacedBlocks();
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Failed to initialize custom block persistence", exception);
        }
    }

    @Override
    public String getId() {
        return "vanilla";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public ItemStack createItem(CustomContentDefinition definition, int amount) {
        Material material = Material.matchMaterial(definition.getMaterial() != null ? definition.getMaterial() : "STICK");
        ItemStack item = new ItemStack(material != null ? material : Material.STICK, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (definition.getDisplayName() != null && !definition.getDisplayName().isBlank()) {
                meta.displayName(TextFormatter.parseItemName(definition.getDisplayName()));
            }
            if (definition.getLore() != null && !definition.getLore().isEmpty()) {
                meta.lore(definition.getLore().stream().map(TextFormatter::parseItemLore).toList());
            }
            if (definition.getCustomModelData() != null) {
                meta.setCustomModelData(definition.getCustomModelData());
            }
            item.setItemMeta(meta);
        }
        item = applyComponents(item, definition);
        return stampItem(item, definition);
    }

    public ItemStack stampItem(ItemStack item, CustomContentDefinition definition) {
        return stampItem(item, definition, UUID.randomUUID().toString());
    }

    public ItemStack restampItem(ItemStack item, CustomContentDefinition definition, String instanceId) {
        return stampItem(item, definition, instanceId != null && !instanceId.isBlank() ? instanceId : UUID.randomUUID().toString());
    }

    private ItemStack stampItem(ItemStack item, CustomContentDefinition definition, String instanceId) {
        if (item == null || definition == null) {
            return item;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.getPersistentDataContainer().set(contentTypeKey, PersistentDataType.STRING, definition.getType());
        meta.getPersistentDataContainer().set(contentIdKey, PersistentDataType.STRING, definition.getId());
        meta.getPersistentDataContainer().set(contentVersionKey, PersistentDataType.INTEGER, definition.getVersion());
        meta.getPersistentDataContainer().set(instanceIdKey, PersistentDataType.STRING, instanceId);
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public String identifyItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        return meta.getPersistentDataContainer().get(contentIdKey, PersistentDataType.STRING);
    }

    public String getInstanceId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return "";
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null ? meta.getPersistentDataContainer().getOrDefault(instanceIdKey, PersistentDataType.STRING, "") : "";
    }

    public String getStampedContentId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return "";
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null ? meta.getPersistentDataContainer().getOrDefault(contentIdKey, PersistentDataType.STRING, "") : "";
    }

    public ItemStack clearStamp(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return item;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.getPersistentDataContainer().remove(contentTypeKey);
        meta.getPersistentDataContainer().remove(contentIdKey);
        meta.getPersistentDataContainer().remove(contentVersionKey);
        meta.getPersistentDataContainer().remove(instanceIdKey);
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public String identifyBlock(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        return publishedBlocks.get(blockKey(location));
    }

    @Override
    public void markPlacedBlock(Location location, CustomContentDefinition definition) {
        if (location == null || definition == null) {
            return;
        }
        synchronized (persistenceMonitor) {
            requireMutationAdmission();
            String key = blockKey(location);
            String id = definition.getId();
            if (id == null || !BLOCK_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("Custom block definition ID is required");
            }
            String previous = blocks.put(key, id);
            try {
                writeBlocks(activeBinding.file(), blocks);
                publishPlacedBlocks();
            } catch (IOException exception) {
                if (previous == null) {
                    blocks.remove(key);
                } else {
                    blocks.put(key, previous);
                }
                persistenceFailure = exception;
                throw new IllegalStateException("Failed to save custom blocks", exception);
            }
        }
    }

    @Override
    public void clearPlacedBlock(Location location) {
        if (location == null) {
            return;
        }
        synchronized (persistenceMonitor) {
            requireMutationAdmission();
            String key = blockKey(location);
            String previous = blocks.remove(key);
            try {
                writeBlocks(activeBinding.file(), blocks);
                publishPlacedBlocks();
            } catch (IOException exception) {
                if (previous != null) {
                    blocks.put(key, previous);
                }
                persistenceFailure = exception;
                throw new IllegalStateException("Failed to save custom blocks", exception);
            }
        }
    }

    public Map<String, String> getPlacedBlocks() {
        return publishedBlocks;
    }

    public Path persistenceRoot() {
        return activeBinding.file();
    }

    public Path path() {
        return persistenceRoot();
    }

    public long persistenceGeneration() {
        return activeBinding.generation();
    }

    public long generation() {
        return persistenceGeneration();
    }

    public boolean isQuiesced() {
        synchronized (persistenceMonitor) {
            return quiesced;
        }
    }

    public boolean isClosed() {
        synchronized (persistenceMonitor) {
            return closed;
        }
    }

    public void flushPersistence() throws IOException {
        synchronized (persistenceMonitor) {
            ensureNotClosed();
            ensureHealthy();
            try {
                writeBlocks(activeBinding.file(), blocks);
            } catch (IOException exception) {
                persistenceFailure = exception;
                throw exception;
            }
        }
    }

    public void quiescePersistence() throws IOException {
        synchronized (persistenceMonitor) {
            ensureNotClosed();
            if (quiesced) {
                ensureHealthy();
                return;
            }
            quiescing = true;
            quiesceFailed = false;
            try {
                ensureHealthy();
                writeBlocks(activeBinding.file(), blocks);
                persistenceFailure = null;
                quiesced = true;
                quiescing = false;
            } catch (IOException | RuntimeException exception) {
                persistenceFailure = exception instanceof IOException ioException ? ioException : new IOException(exception);
                quiesceFailed = true;
                quiescing = true;
                if (exception instanceof IOException ioException) {
                    throw ioException;
                }
                throw exception;
            } finally {
                persistenceMonitor.notifyAll();
            }
        }
    }

    public void resumePersistence() throws IOException {
        synchronized (persistenceMonitor) {
            ensureNotClosed();
            if (closing || (quiescing && !quiesceFailed)) {
                throw new IOException("Custom block persistence lifecycle transition is active");
            }
            if (!quiesced && !quiesceFailed) {
                return;
            }
            if (persistenceFailure != null) {
                throw new IOException("Custom block persistence is unavailable", persistenceFailure);
            }
            Map<String, String> persisted = readBlocks(activeBinding.file(), true).blocks();
            if (!persisted.equals(blocks)) {
                throw new IOException("Custom blocks mappings are out of sync with the active file");
            }
            quiesced = false;
            quiescing = false;
            quiesceFailed = false;
        }
    }

    public void rebindPersistence(Path candidateFile) throws IOException {
        synchronized (persistenceMonitor) {
            ensureNotClosed();
            if (closing || quiescing || !quiesced) {
                throw new IOException("Custom block persistence must be quiesced before rebind");
            }
            Path nextFile = MigrationPaths.requirePath(candidateFile, "candidateFile");
            DecodedBlocks decoded = readBlocks(nextFile, true);
            long generation;
            try {
                generation = Math.addExact(activeBinding.generation(), 1L);
            } catch (ArithmeticException exception) {
                throw new IOException("Custom block persistence generation overflowed", exception);
            }
            ActiveBinding nextBinding = new ActiveBinding(nextFile, generation);
            blocks = decoded.blocks();
            activeBinding = nextBinding;
            publishPlacedBlocks();
        }
    }

    public void healthCheckPersistence() throws IOException {
        synchronized (persistenceMonitor) {
            ensureNotClosed();
            ensureHealthy();
            Map<String, String> persisted = readBlocks(activeBinding.file(), true).blocks();
            if (!persisted.equals(blocks)) {
                throw new IOException("Custom blocks mappings are out of sync with the active file");
            }
        }
    }

    public void closePersistence() throws IOException {
        synchronized (persistenceMonitor) {
            if (closed) {
                return;
            }
            if (closing) {
                throw new IOException("Custom block persistence shutdown is already in progress");
            }
            closing = true;
            try {
                if (!quiesced) {
                    quiescing = true;
                    quiesceFailed = false;
                    ensureHealthy();
                    writeBlocks(activeBinding.file(), blocks);
                    persistenceFailure = null;
                    quiesced = true;
                    quiescing = false;
                } else {
                    ensureHealthy();
                    Map<String, String> persisted = readBlocks(activeBinding.file(), true).blocks();
                    if (!persisted.equals(blocks)) {
                        throw new IOException("Custom blocks mappings are out of sync with the active file");
                    }
                }
                closed = true;
            } catch (IOException | RuntimeException exception) {
                persistenceFailure = exception instanceof IOException ioException ? ioException : new IOException(exception);
                quiesceFailed = true;
                quiescing = true;
                if (exception instanceof IOException ioException) {
                    throw ioException;
                }
                throw exception;
            } finally {
                if (!closed) {
                    closing = false;
                }
                persistenceMonitor.notifyAll();
            }
        }
    }

    private ItemStack applyComponents(ItemStack item, CustomContentDefinition definition) {
        if (definition.getComponents() == null || definition.getComponents().isEmpty()) {
            return item;
        }
        try {
            return attributeSchemaService.applyComponents(item, definition.getComponents());
        } catch (RuntimeException failure) {
            Log.warn("Failed to apply custom content components for " + definition.getId() + ": " + failure.getMessage());
            return item;
        }
    }

    private void publishPlacedBlocks() {
        publishedBlocks = Map.copyOf(blocks);
    }

    private String blockKey(Location location) {
        if (location.getWorld() == null) {
            throw new IllegalArgumentException("Custom block world identity is required");
        }
        String world = location.getWorld().getName();
        if (!isValidWorldName(world)) {
            throw new IllegalArgumentException("Custom block world identity is invalid");
        }
        int y = location.getBlockY();
        return world + ":" + location.getBlockX() + ":" + y + ":" + location.getBlockZ();
    }

    private DecodedBlocks readBlocks(Path file, boolean required) throws IOException {
        Path normalized = MigrationPaths.requirePath(file, "blockFile");
        if (Files.notExists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            if (required) {
                throw new IOException("Custom blocks file does not exist: " + normalized);
            }
            return new DecodedBlocks(new LinkedHashMap<>());
        }
        if (Files.isSymbolicLink(normalized) || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Custom blocks file is not a regular file: " + normalized);
        }
        try {
            byte[] bytes = Files.readAllBytes(normalized);
            JsonValue value;
            try {
                value = CanonicalCodec.decode(bytes);
            } catch (IllegalArgumentException canonicalFailure) {
                throw new IOException("Custom blocks file is not canonical: " + normalized, canonicalFailure);
            }
            if (value instanceof JsonValue.JsonObject object && object.fields().keySet().equals(DOCUMENT_FIELDS)) {
                return new DecodedBlocks(decodeCanonical(object));
            }
            throw new IOException("Custom blocks file is not a versioned document: " + normalized);
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("Custom blocks file is malformed: " + normalized, exception);
        }
    }

    private void writeBlocks(Path file, Map<String, String> values) throws IOException {
        Path normalized = MigrationPaths.requirePath(file, "blockFile");
        Path parent = requireParent(normalized);
        Files.createDirectories(parent);
        AtomicFiles.write(normalized, canonicalBytes(values));
    }

    private void requireMutationAdmission() {
        if (closed || closing) {
            throw new IllegalStateException("Custom block persistence is closed");
        }
        if (quiesced || quiescing || quiesceFailed || persistenceFailure != null) {
            throw new IllegalStateException("Custom block persistence is quiesced");
        }
    }

    private void ensureNotClosed() {
        if (closed) {
            throw new IllegalStateException("Custom block persistence is closed");
        }
    }

    private void ensureHealthy() throws IOException {
        if (persistenceFailure != null) {
            throw new IOException("Custom block persistence is unavailable", persistenceFailure);
        }
        if (quiesceFailed) {
            throw new IOException("Custom block persistence lifecycle transition failed");
        }
    }

    private Map<String, String> decodeCanonical(JsonValue.JsonObject document) throws IOException {
        if (!DOCUMENT_KIND.equals(requiredString(document, "kind"))) {
            throw new IOException("Custom blocks document kind is unsupported");
        }
        if (requiredLong(document, "version") != DOCUMENT_VERSION) {
            throw new IOException("Custom blocks document version is unsupported");
        }
        String expectedHash = requiredString(document, "contentHash");
        if (!HASH.matcher(expectedHash).matches()) {
            throw new IOException("Custom blocks content hash is invalid");
        }
        JsonValue mappingsValue = requiredField(document, "mappings");
        if (!(mappingsValue instanceof JsonValue.JsonArray mappings)) {
            throw new IOException("Custom blocks mappings must be an array");
        }
        Map<String, String> result = new LinkedHashMap<>();
        Set<String> foldedKeys = new HashSet<>();
        for (JsonValue value : mappings.values()) {
            if (!(value instanceof JsonValue.JsonObject mapping)) {
                throw new IOException("Custom block mappings must be objects");
            }
            if (!mapping.fields().keySet().equals(MAPPING_FIELDS)) {
                throw new IOException("Custom block mapping fields are invalid");
            }
            String world = requiredString(mapping, "world");
            int x = requiredInt(mapping, "x");
            int y = requiredInt(mapping, "y");
            int z = requiredInt(mapping, "z");
            String block = requiredString(mapping, "block");
            String key = coordinateKey(world, x, y, z);
            if (!foldedKeys.add(foldedKey(world, x, y, z)) || result.put(key, requireBlockId(block)) != null) {
                throw new IOException("Custom blocks contain duplicate or case-colliding coordinates");
            }
        }
        if (!expectedHash.equals(contentHash(result))) {
            throw new IOException("Custom blocks content hash does not match mappings");
        }
        if (!new ArrayList<>(result.keySet()).equals(result.keySet().stream()
            .sorted(CanonicalJson::compareCodePoints).toList())) {
            throw new IOException("Custom block mappings are not in canonical order");
        }
        return result;
    }

    private byte[] canonicalBytes(Map<String, String> values) throws IOException {
        Map<String, String> normalized = validateMappings(values);
        List<Map<String, Object>> mappings = new ArrayList<>();
        normalized.entrySet().stream().sorted(Map.Entry.comparingByKey(CanonicalJson::compareCodePoints)).forEach(entry -> {
            Coordinate coordinate = coordinate(entry.getKey());
            Map<String, Object> mapping = new LinkedHashMap<>();
            mapping.put("world", coordinate.world());
            mapping.put("x", coordinate.x());
            mapping.put("y", coordinate.y());
            mapping.put("z", coordinate.z());
            mapping.put("block", entry.getValue());
            mappings.add(mapping);
        });
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", DOCUMENT_KIND);
        body.put("version", DOCUMENT_VERSION);
        body.put("mappings", mappings);
        body.put("contentHash", CanonicalHash.sha256(JsonValue.fromJava(body)));
        try {
            return JsonValue.fromJava(body).canonicalBytes();
        } catch (RuntimeException exception) {
            throw new IOException("Custom blocks document is not canonical JSON", exception);
        }
    }

    private String contentHash(Map<String, String> values) throws IOException {
        Map<String, String> normalized = validateMappings(values);
        List<Map<String, Object>> mappings = new ArrayList<>();
        normalized.entrySet().stream().sorted(Map.Entry.comparingByKey(CanonicalJson::compareCodePoints)).forEach(entry -> {
            Coordinate coordinate = coordinate(entry.getKey());
            Map<String, Object> mapping = new LinkedHashMap<>();
            mapping.put("world", coordinate.world());
            mapping.put("x", coordinate.x());
            mapping.put("y", coordinate.y());
            mapping.put("z", coordinate.z());
            mapping.put("block", entry.getValue());
            mappings.add(mapping);
        });
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", DOCUMENT_KIND);
        body.put("version", DOCUMENT_VERSION);
        body.put("mappings", mappings);
        try {
            return CanonicalHash.sha256(JsonValue.fromJava(body));
        } catch (RuntimeException exception) {
            throw new IOException("Custom blocks content hash cannot be computed", exception);
        }
    }

    private Map<String, String> validateMappings(Map<String, String> values) throws IOException {
        Map<String, String> normalized = new LinkedHashMap<>();
        Set<String> foldedKeys = new HashSet<>();
        if (values == null) {
            return normalized;
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = parseCoordinateKey(entry.getKey());
            String block = requireBlockId(entry.getValue());
            Coordinate coordinate = coordinate(key);
            if (!foldedKeys.add(foldedKey(coordinate.world(), coordinate.x(), coordinate.y(), coordinate.z()))
                || normalized.put(key, block) != null) {
                throw new IOException("Custom blocks contain duplicate or case-colliding coordinates");
            }
        }
        return normalized;
    }

    private String parseCoordinateKey(String key) throws IOException {
        if (key == null) {
            throw new IOException("Custom block coordinate key is required");
        }
        String[] parts = key.split(":", -1);
        if (parts.length != 4 || !isValidWorldName(parts[0])
            || !COORDINATE_INTEGER.matcher(parts[1]).matches()
            || !COORDINATE_INTEGER.matcher(parts[2]).matches()
            || !COORDINATE_INTEGER.matcher(parts[3]).matches()) {
            throw new IOException("Custom block coordinate key has invalid grammar: " + key);
        }
        try {
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            return coordinateKey(parts[0], x, y, z);
        } catch (NumberFormatException exception) {
            throw new IOException("Custom block coordinate is out of bounds", exception);
        }
    }

    private String coordinateKey(String world, int x, int y, int z) throws IOException {
        if (!isValidWorldName(world)) {
            throw new IOException("Custom block world identity is invalid");
        }
        return world + ":" + x + ":" + y + ":" + z;
    }

    private Coordinate coordinate(String key) {
        String[] parts = key == null ? new String[0] : key.split(":", -1);
        if (parts.length != 4 || !isValidWorldName(parts[0])
            || !COORDINATE_INTEGER.matcher(parts[1]).matches()
            || !COORDINATE_INTEGER.matcher(parts[2]).matches()
            || !COORDINATE_INTEGER.matcher(parts[3]).matches()) {
            throw new IllegalArgumentException("Invalid custom block coordinate key");
        }
        try {
            return new Coordinate(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid custom block coordinate key", exception);
        }
    }

    private boolean isValidWorldName(String world) {
        if (world == null || world.isEmpty()
            || world.equals(".") || world.equals("..")
            || world.indexOf(':') >= 0 || world.indexOf('/') >= 0 || world.indexOf('\\') >= 0) {
            return false;
        }
        if (world.codePoints().anyMatch(Character::isISOControl)) {
            return false;
        }
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(world));
            return encoded.remaining() <= MAX_WORLD_NAME_BYTES;
        } catch (CharacterCodingException exception) {
            return false;
        }
    }

    private String foldedKey(String world, int x, int y, int z) {
        return world.toLowerCase(Locale.ROOT) + ":" + x + ":" + y + ":" + z;
    }

    private String requireBlockId(String value) throws IOException {
        if (value == null || !BLOCK_ID.matcher(value).matches() || value.equals(".") || value.equals("..")
            || value.contains("..") || value.endsWith(".json")) {
            throw new IOException("Custom block value has invalid schema");
        }
        return value;
    }

    private JsonValue requiredField(JsonValue.JsonObject object, String name) throws IOException {
        JsonValue value = object.value(name);
        if (value == null) {
            throw new IOException("Required custom blocks field is missing: " + name);
        }
        return value;
    }

    private String requiredString(JsonValue.JsonObject object, String name) throws IOException {
        JsonValue value = requiredField(object, name);
        if (!(value instanceof JsonValue.JsonString string)) {
            throw new IOException("Custom blocks field must be a string: " + name);
        }
        return string.value();
    }

    private long requiredLong(JsonValue.JsonObject object, String name) throws IOException {
        JsonValue value = requiredField(object, name);
        if (!(value instanceof JsonValue.JsonNumber number) || number.value().scale() > 0) {
            throw new IOException("Custom blocks field must be an integer: " + name);
        }
        try {
            return number.value().longValueExact();
        } catch (ArithmeticException exception) {
            throw new IOException("Custom blocks integer is out of range: " + name, exception);
        }
    }

    private int requiredInt(JsonValue.JsonObject object, String name) throws IOException {
        long value = requiredLong(object, name);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IOException("Custom blocks coordinate is out of bounds: " + name);
        }
        return (int) value;
    }

    private Path requireParent(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent == null) {
            throw new IOException("Custom blocks file has no parent: " + file);
        }
        return parent;
    }

    private record ActiveBinding(Path file, long generation) {
        private ActiveBinding {
            file = MigrationPaths.requirePath(file, "blockFile");
            if (generation < 0L) {
                throw new IllegalArgumentException("Custom block generation must not be negative");
            }
        }
    }

    private record Coordinate(String world, int x, int y, int z) {
    }

    private record DecodedBlocks(Map<String, String> blocks) {
    }
}

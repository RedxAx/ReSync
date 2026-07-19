package restudio.resync.customcontent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import restudio.resync.Log;
import restudio.resync.ReSync;
import restudio.resync.advancement.PaperUnsafe;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

class CustomContentOfflinePlayerDataReconciler {
    private final CustomContentItemReconciler itemReconciler;
    private final Map<Path, FileReconcileState> fileStates = new ConcurrentHashMap<>();

    CustomContentOfflinePlayerDataReconciler(CustomContentItemReconciler itemReconciler) {
        this.itemReconciler = itemReconciler;
    }

    void reconcileAsync(String contentId, boolean clearDeleted) {
        JavaPlugin plugin = ReSync.getInstance();
        if (!pluginActive(plugin)) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            reconcileAsync(plugin, contentId, clearDeleted);
            return;
        }
        scheduleSync(plugin, () -> reconcileAsync(plugin, contentId, clearDeleted));
    }

    private void reconcileAsync(JavaPlugin plugin, String contentId, boolean clearDeleted) {
        if (!pluginActive(plugin) || !PaperUnsafe.itemJsonRoundTripSupported()) {
            return;
        }
        OfflinePlayerDataSnapshot snapshot = snapshotPlayerDataState();
        if (snapshot.directories().isEmpty() && snapshot.worldContainer() == null) {
            return;
        }
        scheduleAsync(plugin, () -> reconcileSnapshot(plugin, snapshot, contentId, clearDeleted));
    }

    private OfflinePlayerDataSnapshot snapshotPlayerDataState() {
        Set<UUID> onlinePlayers = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            onlinePlayers.add(player.getUniqueId());
        }
        Set<Path> directories = new LinkedHashSet<>();
        for (World world : Bukkit.getWorlds()) {
            directories.add(world.getWorldFolder().toPath().resolve("playerdata"));
        }
        Path container = Bukkit.getWorldContainer().toPath();
        return new OfflinePlayerDataSnapshot(directories, container, onlinePlayers);
    }

    private void reconcileSnapshot(JavaPlugin plugin, OfflinePlayerDataSnapshot snapshot, String contentId, boolean clearDeleted) {
        for (Path playerDataDirectory : playerDataDirectories(snapshot)) {
            if (!pluginActive(plugin)) {
                return;
            }
            reconcileDirectory(plugin, playerDataDirectory, contentId, clearDeleted, snapshot.onlinePlayers());
        }
    }

    private void reconcileDirectory(JavaPlugin plugin, Path directory, String contentId, boolean clearDeleted, Set<UUID> onlinePlayers) {
        if (directory == null || !Files.isDirectory(directory)) {
            return;
        }
        try (var stream = Files.list(directory)) {
            for (Path file : stream.filter(path -> playerIdFromFile(path) != null).toList()) {
                if (!pluginActive(plugin)) {
                    return;
                }
                if (isOnlinePlayerFile(file, onlinePlayers)) {
                    continue;
                }
                reconcileFile(plugin, file, contentId, clearDeleted, onlinePlayers);
            }
        } catch (IOException exception) {
            Log.warn("Failed to scan offline player data: " + exception.getMessage());
        }
    }

    private void reconcileFile(JavaPlugin plugin, Path file, String contentId, boolean clearDeleted, Set<UUID> onlinePlayers) {
        Path fileKey = file.toAbsolutePath().normalize();
        FileReconcileState state = fileStates.computeIfAbsent(fileKey, ignored -> new FileReconcileState());
        synchronized (state) {
            if (state.active) {
                state.pending.add(new FileReconcileRequest(file, contentId, clearDeleted, onlinePlayers));
                return;
            }
            state.active = true;
        }
        reconcileFilePass(plugin, file, fileKey, state, contentId, clearDeleted, onlinePlayers);
    }

    private void reconcileFilePass(JavaPlugin plugin, Path file, Path fileKey, FileReconcileState state, String contentId, boolean clearDeleted, Set<UUID> onlinePlayers) {
        try {
            NbtTag root = Nbt.readCompressed(file);
            if (root == null || root.type() != Nbt.COMPOUND || !(root.value() instanceof List<?>)) {
                finishFile(plugin, fileKey, state);
                return;
            }
            transformFile(plugin, file, fileKey, state, root, contentId, clearDeleted, onlinePlayers);
        } catch (Exception exception) {
            finishFile(plugin, fileKey, state);
            Log.warn("Failed to reconcile offline player items in " + file.getFileName() + ": " + exception.getMessage());
        }
    }

    private void transformFile(JavaPlugin plugin, Path file, Path fileKey, FileReconcileState state, NbtTag root, String contentId, boolean clearDeleted, Set<UUID> onlinePlayers) {
        if (!pluginActive(plugin)) {
            finishFile(plugin, fileKey, state);
            return;
        }
        if (!scheduleSync(plugin, () -> {
            if (!pluginActive(plugin)) {
                finishFile(plugin, fileKey, state);
                return;
            }
            if (isOnlinePlayerFile(file, onlinePlayers) || isCurrentlyOnlinePlayerFile(file)) {
                finishFile(plugin, fileKey, state);
                return;
            }
            try {
                boolean changed = false;
                changed |= reconcileItemList(root, "Inventory", contentId, clearDeleted);
                changed |= reconcileItemList(root, "EnderItems", contentId, clearDeleted);
                if (changed) {
                    writeFile(plugin, file, fileKey, state, root, onlinePlayers);
                } else {
                    finishFile(plugin, fileKey, state);
                }
            } catch (RuntimeException exception) {
                finishFile(plugin, fileKey, state);
                Log.warn("Failed to transform offline player items in " + file.getFileName() + ": " + exception.getMessage());
            }
        })) {
            finishFile(plugin, fileKey, state);
        }
    }

    private void writeFile(JavaPlugin plugin, Path file, Path fileKey, FileReconcileState state, NbtTag root, Set<UUID> onlinePlayers) {
        if (!pluginActive(plugin)) {
            finishFile(plugin, fileKey, state);
            return;
        }
        if (isOnlinePlayerFile(file, onlinePlayers) || isCurrentlyOnlinePlayerFile(file)) {
            finishFile(plugin, fileKey, state);
            return;
        }
        if (!scheduleSync(plugin, () -> {
            if (!pluginActive(plugin)) {
                finishFile(plugin, fileKey, state);
                return;
            }
            if (isOnlinePlayerFile(file, onlinePlayers) || isCurrentlyOnlinePlayerFile(file)) {
                finishFile(plugin, fileKey, state);
                return;
            }
            try {
                Nbt.writeCompressed(file, root);
            } catch (Exception exception) {
                Log.warn("Failed to write reconciled offline player items in " + file.getFileName() + ": " + exception.getMessage());
            } finally {
                finishFile(plugin, fileKey, state);
            }
        })) {
            finishFile(plugin, fileKey, state);
        }
    }

    private void finishFile(JavaPlugin plugin, Path fileKey, FileReconcileState state) {
        FileReconcileRequest pending;
        synchronized (state) {
            if (!pluginActive(plugin)) {
                state.active = false;
                return;
            }
            pending = state.pending.pollFirst();
            if (pending == null) {
                state.active = false;
                return;
            }
        }
        FileReconcileRequest next = pending;
        if (!scheduleAsync(plugin, () -> reconcileFilePass(plugin, next.file(), fileKey, state, next.contentId(), next.clearDeleted(), next.onlinePlayers()))) {
            synchronized (state) {
                state.pending.addFirst(next);
                state.active = false;
            }
        }
    }

    private boolean reconcileItemList(NbtTag root, String key, String contentId, boolean clearDeleted) {
        NbtTag tag = Nbt.find(root, key);
        if (tag == null || tag.type() != Nbt.LIST || !(tag.value() instanceof NbtList list) || list.elementType() != Nbt.COMPOUND) {
            return false;
        }
        boolean changed = false;
        for (int index = 0; index < list.values().size(); index++) {
            Object value = list.values().get(index);
            if (!(value instanceof List<?> compound)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            List<NbtTag> currentCompound = (List<NbtTag>) compound;
            List<NbtTag> updatedCompound = reconcileItemCompound(currentCompound, contentId, clearDeleted);
            if (updatedCompound != currentCompound) {
                list.values().set(index, updatedCompound);
                changed = true;
            }
        }
        return changed;
    }

    private List<NbtTag> reconcileItemCompound(List<NbtTag> compound, String contentId, boolean clearDeleted) {
        JsonObject json = Nbt.toJsonObject(compound);
        if (!json.has("count") && json.has("Count")) {
            json.add("count", json.get("Count"));
        }
        json.remove("Count");
        if (!json.has("id") || !json.has("count")) {
            return compound;
        }
        ItemStack item;
        try {
            item = PaperUnsafe.deserializeItemFromJson(json);
        } catch (RuntimeException ignored) {
            return compound;
        }
        ItemStack updated = itemReconciler.transformItem(item, contentId, clearDeleted);
        if (updated == item) {
            return compound;
        }
        JsonObject updatedJson;
        try {
            updatedJson = PaperUnsafe.serializeItemAsJson(updated);
        } catch (RuntimeException ignored) {
            return compound;
        }
        updatedJson.remove("DataVersion");
        NbtTag slot = Nbt.find(compound, "Slot");
        List<NbtTag> replacement = Nbt.fromJsonObject(updatedJson, compound);
        if (slot != null) {
            Nbt.put(replacement, slot);
        }
        return replacement;
    }

    private boolean isOnlinePlayerFile(Path file, Set<UUID> onlinePlayers) {
        UUID playerId = playerIdFromFile(file);
        return playerId != null && onlinePlayers.contains(playerId);
    }

    private boolean isCurrentlyOnlinePlayerFile(Path file) {
        UUID playerId = playerIdFromFile(file);
        return playerId != null && Bukkit.getPlayer(playerId) != null;
    }

    static UUID playerIdFromFile(Path file) {
        if (file == null || file.getFileName() == null) {
            return null;
        }
        String name = file.getFileName().toString();
        if (!name.endsWith(".dat")) {
            return null;
        }
        String value = name.substring(0, name.length() - 4);
        try {
            UUID playerId = UUID.fromString(value);
            return playerId.toString().equalsIgnoreCase(value) ? playerId : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private Set<Path> playerDataDirectories(OfflinePlayerDataSnapshot snapshot) {
        Set<Path> directories = new LinkedHashSet<>(snapshot.directories());
        Path container = snapshot.worldContainer();
        if (container != null && Files.isDirectory(container)) {
            try (var stream = Files.list(container)) {
                for (Path path : stream.toList()) {
                    Path playerData = path.resolve("playerdata");
                    if (Files.isDirectory(playerData)) {
                        directories.add(playerData);
                    }
                }
            } catch (IOException exception) {
                Log.warn("Failed to scan world playerdata directories: " + exception.getMessage());
            }
        }
        return directories;
    }

    private boolean pluginActive(JavaPlugin plugin) {
        return plugin != null && plugin.isEnabled();
    }

    private boolean scheduleSync(JavaPlugin plugin, Runnable action) {
        if (!pluginActive(plugin)) {
            return false;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, action);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean scheduleAsync(JavaPlugin plugin, Runnable action) {
        if (!pluginActive(plugin)) {
            return false;
        }
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, action);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private record OfflinePlayerDataSnapshot(Set<Path> directories, Path worldContainer, Set<UUID> onlinePlayers) {
    }

    private record FileReconcileRequest(Path file, String contentId, boolean clearDeleted, Set<UUID> onlinePlayers) {
    }

    private static final class FileReconcileState {
        private final ArrayDeque<FileReconcileRequest> pending = new ArrayDeque<>();
        private boolean active;
    }

    private record NbtTag(byte type, String name, Object value) {
    }

    private record NbtList(byte elementType, List<Object> values) {
    }

    private static final class Nbt {
        private static final byte END = 0;
        private static final byte BYTE = 1;
        private static final byte SHORT = 2;
        private static final byte INT = 3;
        private static final byte LONG = 4;
        private static final byte FLOAT = 5;
        private static final byte DOUBLE = 6;
        private static final byte BYTE_ARRAY = 7;
        private static final byte STRING = 8;
        private static final byte LIST = 9;
        private static final byte COMPOUND = 10;
        private static final byte INT_ARRAY = 11;
        private static final byte LONG_ARRAY = 12;

        private Nbt() {
        }

        private static NbtTag readCompressed(Path file) throws IOException {
            try (DataInputStream input = new DataInputStream(new GZIPInputStream(Files.newInputStream(file)))) {
                return read(input);
            }
        }

        private static void writeCompressed(Path file, NbtTag root) throws IOException {
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream fileOutput = Files.newOutputStream(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                 DataOutputStream output = new DataOutputStream(new GZIPOutputStream(fileOutput))) {
                write(output, root);
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private static NbtTag read(DataInputStream input) throws IOException {
            byte type = input.readByte();
            if (type == END) {
                return null;
            }
            String name = readString(input);
            return new NbtTag(type, name, readPayload(input, type));
        }

        private static Object readPayload(DataInputStream input, byte type) throws IOException {
            return switch (type) {
                case BYTE -> input.readByte();
                case SHORT -> input.readShort();
                case INT -> input.readInt();
                case LONG -> input.readLong();
                case FLOAT -> input.readFloat();
                case DOUBLE -> input.readDouble();
                case BYTE_ARRAY -> readByteArray(input);
                case STRING -> readString(input);
                case LIST -> readList(input);
                case COMPOUND -> readCompound(input);
                case INT_ARRAY -> readIntArray(input);
                case LONG_ARRAY -> readLongArray(input);
                default -> null;
            };
        }

        private static List<NbtTag> readCompound(DataInputStream input) throws IOException {
            List<NbtTag> tags = new ArrayList<>();
            while (true) {
                byte type = input.readByte();
                if (type == END) {
                    return tags;
                }
                tags.add(new NbtTag(type, readString(input), readPayload(input, type)));
            }
        }

        private static NbtList readList(DataInputStream input) throws IOException {
            byte elementType = input.readByte();
            int size = input.readInt();
            List<Object> values = new ArrayList<>(Math.max(0, size));
            for (int index = 0; index < size; index++) {
                values.add(readPayload(input, elementType));
            }
            return new NbtList(elementType, values);
        }

        private static byte[] readByteArray(DataInputStream input) throws IOException {
            byte[] values = new byte[input.readInt()];
            input.readFully(values);
            return values;
        }

        private static int[] readIntArray(DataInputStream input) throws IOException {
            int[] values = new int[input.readInt()];
            for (int index = 0; index < values.length; index++) {
                values[index] = input.readInt();
            }
            return values;
        }

        private static long[] readLongArray(DataInputStream input) throws IOException {
            long[] values = new long[input.readInt()];
            for (int index = 0; index < values.length; index++) {
                values[index] = input.readLong();
            }
            return values;
        }

        private static void write(DataOutputStream output, NbtTag tag) throws IOException {
            output.writeByte(tag.type());
            if (tag.type() == END) {
                return;
            }
            writeString(output, tag.name());
            writePayload(output, tag.type(), tag.value());
        }

        private static void writePayload(DataOutputStream output, byte type, Object value) throws IOException {
            switch (type) {
                case BYTE -> output.writeByte(((Number) value).byteValue());
                case SHORT -> output.writeShort(((Number) value).shortValue());
                case INT -> output.writeInt(((Number) value).intValue());
                case LONG -> output.writeLong(((Number) value).longValue());
                case FLOAT -> output.writeFloat(((Number) value).floatValue());
                case DOUBLE -> output.writeDouble(((Number) value).doubleValue());
                case BYTE_ARRAY -> writeByteArray(output, value instanceof byte[] bytes ? bytes : new byte[0]);
                case STRING -> writeString(output, String.valueOf(value));
                case LIST -> writeList(output, value instanceof NbtList list ? list : new NbtList(END, new ArrayList<>()));
                case COMPOUND -> writeCompound(output, compoundValue(value));
                case INT_ARRAY -> writeIntArray(output, value instanceof int[] ints ? ints : new int[0]);
                case LONG_ARRAY -> writeLongArray(output, value instanceof long[] longs ? longs : new long[0]);
                default -> {
                }
            }
        }

        private static void writeCompound(DataOutputStream output, List<NbtTag> tags) throws IOException {
            for (NbtTag tag : tags) {
                write(output, tag);
            }
            output.writeByte(END);
        }

        private static void writeList(DataOutputStream output, NbtList list) throws IOException {
            output.writeByte(list.elementType());
            output.writeInt(list.values().size());
            for (Object value : list.values()) {
                writePayload(output, list.elementType(), value);
            }
        }

        private static void writeByteArray(DataOutputStream output, byte[] values) throws IOException {
            output.writeInt(values.length);
            output.write(values);
        }

        private static void writeIntArray(DataOutputStream output, int[] values) throws IOException {
            output.writeInt(values.length);
            for (int value : values) {
                output.writeInt(value);
            }
        }

        private static void writeLongArray(DataOutputStream output, long[] values) throws IOException {
            output.writeInt(values.length);
            for (long value : values) {
                output.writeLong(value);
            }
        }

        private static String readString(DataInputStream input) throws IOException {
            byte[] bytes = new byte[input.readUnsignedShort()];
            input.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }

        private static void writeString(DataOutputStream output, String value) throws IOException {
            byte[] bytes = value != null ? value.getBytes(StandardCharsets.UTF_8) : new byte[0];
            output.writeShort(bytes.length);
            output.write(bytes);
        }

        private static NbtTag find(NbtTag tag, String name) {
            if (tag == null || tag.type() != COMPOUND) {
                return null;
            }
            return find(compoundValue(tag.value()), name);
        }

        private static NbtTag find(List<NbtTag> compound, String name) {
            if (compound == null) {
                return null;
            }
            for (NbtTag child : compound) {
                if (name.equals(child.name())) {
                    return child;
                }
            }
            return null;
        }

        private static void put(List<NbtTag> compound, NbtTag tag) {
            for (int index = 0; index < compound.size(); index++) {
                if (compound.get(index).name().equals(tag.name())) {
                    compound.set(index, tag);
                    return;
                }
            }
            compound.add(tag);
        }

        private static JsonObject toJsonObject(List<NbtTag> compound) {
            JsonObject object = new JsonObject();
            for (NbtTag tag : compound) {
                if (!"Slot".equals(tag.name())) {
                    object.add(tag.name(), toJson(tag));
                }
            }
            return object;
        }

        private static JsonElement toJson(NbtTag tag) {
            return switch (tag.type()) {
                case BYTE, SHORT, INT, LONG, FLOAT, DOUBLE -> new JsonPrimitive((Number) tag.value());
                case STRING -> new JsonPrimitive(String.valueOf(tag.value()));
                case COMPOUND -> toJsonObject(compoundValue(tag.value()));
                case LIST -> toJsonArray((NbtList) tag.value());
                case BYTE_ARRAY -> toJsonArray((byte[]) tag.value());
                case INT_ARRAY -> toJsonArray((int[]) tag.value());
                case LONG_ARRAY -> toJsonArray((long[]) tag.value());
                default -> new JsonObject();
            };
        }

        private static JsonArray toJsonArray(NbtList list) {
            JsonArray array = new JsonArray();
            for (Object value : list.values()) {
                array.add(toJson(new NbtTag(list.elementType(), "", value)));
            }
            return array;
        }

        private static JsonArray toJsonArray(byte[] values) {
            JsonArray array = new JsonArray();
            for (byte value : values) {
                array.add(value);
            }
            return array;
        }

        private static JsonArray toJsonArray(int[] values) {
            JsonArray array = new JsonArray();
            for (int value : values) {
                array.add(value);
            }
            return array;
        }

        private static JsonArray toJsonArray(long[] values) {
            JsonArray array = new JsonArray();
            for (long value : values) {
                array.add(value);
            }
            return array;
        }

        private static List<NbtTag> fromJsonObject(JsonObject object, List<NbtTag> template) {
            List<NbtTag> tags = new ArrayList<>();
            for (String key : object.keySet()) {
                tags.add(fromJson(key, object.get(key), find(template, key)));
            }
            return tags;
        }

        private static NbtTag fromJson(String name, JsonElement element) {
            return fromJson(name, element, null);
        }

        private static NbtTag fromJson(String name, JsonElement element, NbtTag template) {
            if (element == null || element.isJsonNull()) {
                return new NbtTag(template != null ? template.type() : STRING, name, defaultValue(template));
            }
            if (element.isJsonObject()) {
                List<NbtTag> templateCompound = template != null && template.type() == COMPOUND ? compoundValue(template.value()) : List.of();
                return new NbtTag(COMPOUND, name, fromJsonObject(element.getAsJsonObject(), templateCompound));
            }
            if (element.isJsonArray()) {
                return fromJsonArrayTag(name, element.getAsJsonArray(), template);
            }
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (template != null && isNumericType(template.type()) && primitive.isNumber()) {
                return new NbtTag(template.type(), name, numberValue(primitive, template.type()));
            }
            if (template != null && template.type() == BYTE && primitive.isBoolean()) {
                return new NbtTag(BYTE, name, (byte) (primitive.getAsBoolean() ? 1 : 0));
            }
            if (template != null && template.type() == STRING) {
                return new NbtTag(STRING, name, primitive.isString() ? primitive.getAsString() : primitive.toString());
            }
            if (primitive.isString()) {
                return new NbtTag(STRING, name, primitive.getAsString());
            }
            if (primitive.isBoolean()) {
                return new NbtTag(BYTE, name, (byte) (primitive.getAsBoolean() ? 1 : 0));
            }
            if ("count".equals(name) || isIntegral(primitive)) {
                return new NbtTag(INT, name, primitive.getAsInt());
            }
            return new NbtTag(DOUBLE, name, primitive.getAsDouble());
        }

        private static NbtTag fromJsonArrayTag(String name, JsonArray array, NbtTag template) {
            if (template != null) {
                if (template.type() == BYTE_ARRAY) {
                    return new NbtTag(BYTE_ARRAY, name, byteArrayFromJson(array));
                }
                if (template.type() == INT_ARRAY) {
                    return new NbtTag(INT_ARRAY, name, intArrayFromJson(array));
                }
                if (template.type() == LONG_ARRAY) {
                    return new NbtTag(LONG_ARRAY, name, longArrayFromJson(array));
                }
                if (template.type() == LIST && template.value() instanceof NbtList templateList) {
                    return new NbtTag(LIST, name, fromJsonArray(array, templateList));
                }
            }
            return new NbtTag(LIST, name, fromJsonArray(array));
        }

        private static NbtList fromJsonArray(JsonArray array) {
            return fromJsonArray(array, null);
        }

        private static NbtList fromJsonArray(JsonArray array, NbtList template) {
            if (array.isEmpty()) {
                return new NbtList(template != null ? template.elementType() : END, new ArrayList<>());
            }
            byte elementType = template != null && template.elementType() != END ? template.elementType() : arrayElementType(array);
            List<Object> values = new ArrayList<>();
            List<Object> templateValues = template != null ? template.values() : List.of();
            for (int index = 0; index < array.size(); index++) {
                NbtTag templateTag = index < templateValues.size() ? new NbtTag(elementType, "", templateValues.get(index)) : null;
                NbtTag tag = fromJson("", array.get(index), templateTag);
                values.add(tag.value());
            }
            return new NbtList(elementType, values);
        }

        private static byte arrayElementType(JsonArray array) {
            for (JsonElement element : array) {
                if (element != null && !element.isJsonNull()) {
                    return fromJson("", element).type();
                }
            }
            return END;
        }

        private static byte[] byteArrayFromJson(JsonArray array) {
            byte[] values = new byte[array.size()];
            for (int index = 0; index < array.size(); index++) {
                values[index] = array.get(index).getAsByte();
            }
            return values;
        }

        private static int[] intArrayFromJson(JsonArray array) {
            int[] values = new int[array.size()];
            for (int index = 0; index < array.size(); index++) {
                values[index] = array.get(index).getAsInt();
            }
            return values;
        }

        private static long[] longArrayFromJson(JsonArray array) {
            long[] values = new long[array.size()];
            for (int index = 0; index < array.size(); index++) {
                values[index] = array.get(index).getAsLong();
            }
            return values;
        }

        private static boolean isNumericType(byte type) {
            return type == BYTE || type == SHORT || type == INT || type == LONG || type == FLOAT || type == DOUBLE;
        }

        private static Object numberValue(JsonPrimitive primitive, byte type) {
            return switch (type) {
                case BYTE -> primitive.getAsByte();
                case SHORT -> primitive.getAsShort();
                case INT -> primitive.getAsInt();
                case LONG -> primitive.getAsLong();
                case FLOAT -> primitive.getAsFloat();
                case DOUBLE -> primitive.getAsDouble();
                default -> primitive.getAsInt();
            };
        }

        private static Object defaultValue(NbtTag template) {
            if (template == null) {
                return "";
            }
            return switch (template.type()) {
                case BYTE -> (byte) 0;
                case SHORT -> (short) 0;
                case INT -> 0;
                case LONG -> 0L;
                case FLOAT -> 0.0f;
                case DOUBLE -> 0.0d;
                case BYTE_ARRAY -> new byte[0];
                case LIST -> template.value() instanceof NbtList list ? new NbtList(list.elementType(), new ArrayList<>()) : new NbtList(END, new ArrayList<>());
                case COMPOUND -> new ArrayList<NbtTag>();
                case INT_ARRAY -> new int[0];
                case LONG_ARRAY -> new long[0];
                default -> "";
            };
        }

        @SuppressWarnings("unchecked")
        private static List<NbtTag> compoundValue(Object value) {
            if (value instanceof List<?> list) {
                return (List<NbtTag>) list;
            }
            return new ArrayList<>();
        }

        private static boolean isIntegral(JsonPrimitive primitive) {
            if (!primitive.isNumber()) {
                return false;
            }
            try {
                double value = primitive.getAsDouble();
                return Math.rint(value) == value;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
    }
}

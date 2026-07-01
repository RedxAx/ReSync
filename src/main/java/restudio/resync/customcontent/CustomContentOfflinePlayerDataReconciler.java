package restudio.resync.customcontent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import restudio.resync.Log;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

class CustomContentOfflinePlayerDataReconciler {
    private final CustomContentItemReconciler itemReconciler;

    CustomContentOfflinePlayerDataReconciler(CustomContentItemReconciler itemReconciler) {
        this.itemReconciler = itemReconciler;
    }

    void reconcile(String contentId, boolean clearDeleted) {
        if (!PaperUnsafe.itemJsonRoundTripSupported()) {
            return;
        }
        Set<UUID> onlinePlayers = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            onlinePlayers.add(player.getUniqueId());
        }
        for (Path playerDataDirectory : playerDataDirectories()) {
            reconcileDirectory(playerDataDirectory, contentId, clearDeleted, onlinePlayers);
        }
    }

    private void reconcileDirectory(Path directory, String contentId, boolean clearDeleted, Set<UUID> onlinePlayers) {
        if (directory == null || !Files.isDirectory(directory)) {
            return;
        }
        try (var stream = Files.list(directory)) {
            for (Path file : stream.filter(path -> path.getFileName().toString().endsWith(".dat")).toList()) {
                if (isOnlinePlayerFile(file, onlinePlayers)) {
                    continue;
                }
                reconcileFile(file, contentId, clearDeleted);
            }
        } catch (IOException exception) {
            Log.warn("Failed to scan offline player data: " + exception.getMessage());
        }
    }

    private void reconcileFile(Path file, String contentId, boolean clearDeleted) {
        try {
            NbtTag root = Nbt.readCompressed(file);
            if (root == null || root.type() != Nbt.COMPOUND || !(root.value() instanceof List<?>)) {
                return;
            }
            boolean changed = false;
            changed |= reconcileItemList(root, "Inventory", contentId, clearDeleted);
            changed |= reconcileItemList(root, "EnderItems", contentId, clearDeleted);
            if (changed) {
                Nbt.writeCompressed(file, root);
            }
        } catch (Exception exception) {
            Log.warn("Failed to reconcile offline player items in " + file.getFileName() + ": " + exception.getMessage());
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
        List<NbtTag> replacement = Nbt.fromJsonObject(updatedJson);
        if (slot != null) {
            Nbt.put(replacement, slot);
        }
        return replacement;
    }

    private boolean isOnlinePlayerFile(Path file, Set<UUID> onlinePlayers) {
        String name = file.getFileName().toString();
        if (!name.endsWith(".dat")) {
            return false;
        }
        try {
            UUID playerId = UUID.fromString(name.substring(0, name.length() - 4));
            return onlinePlayers.contains(playerId);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private Set<Path> playerDataDirectories() {
        Set<Path> directories = new LinkedHashSet<>();
        for (World world : Bukkit.getWorlds()) {
            Path folder = world.getWorldFolder().toPath().resolve("playerdata");
            if (Files.isDirectory(folder)) {
                directories.add(folder);
            }
        }
        Path container = Bukkit.getWorldContainer().toPath();
        if (Files.isDirectory(container)) {
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

        private static List<NbtTag> fromJsonObject(JsonObject object) {
            List<NbtTag> tags = new ArrayList<>();
            for (String key : object.keySet()) {
                tags.add(fromJson(key, object.get(key)));
            }
            return tags;
        }

        private static NbtTag fromJson(String name, JsonElement element) {
            if (element == null || element.isJsonNull()) {
                return new NbtTag(STRING, name, "");
            }
            if (element.isJsonObject()) {
                return new NbtTag(COMPOUND, name, fromJsonObject(element.getAsJsonObject()));
            }
            if (element.isJsonArray()) {
                return new NbtTag(LIST, name, fromJsonArray(element.getAsJsonArray()));
            }
            JsonPrimitive primitive = element.getAsJsonPrimitive();
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

        private static NbtList fromJsonArray(JsonArray array) {
            if (array.isEmpty()) {
                return new NbtList(END, new ArrayList<>());
            }
            byte elementType = arrayElementType(array);
            List<Object> values = new ArrayList<>();
            for (JsonElement element : array) {
                NbtTag tag = fromJson("", element);
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

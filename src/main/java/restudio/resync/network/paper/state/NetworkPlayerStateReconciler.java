package restudio.resync.network.paper.state;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.inventory.ItemStack;
import restudio.resync.ReSync;
import restudio.resync.network.NetworkStateReconciliationTask;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class NetworkPlayerStateReconciler implements Listener {
    private static final Set<String> SUPPORTED_FAMILIES = Set.of("inventory", "ender-chest");
    private final ReSync plugin;
    private final Set<UUID> lockedPlayers = ConcurrentHashMap.newKeySet();

    public NetworkPlayerStateReconciler(ReSync plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public CompletableFuture<Void> reconcile(NetworkStateReconciliationTask task) {
        if (!SUPPORTED_FAMILIES.containsAll(task.families())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Only Item State Can Be Reconciled"));
        }
        if (task.playerIds().isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        lockedPlayers.addAll(task.playerIds());
        CompletableFuture<Void> result = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> begin(task, result));
        return result.whenComplete((unused, throwable) -> lockedPlayers.removeAll(task.playerIds()));
    }

    public void shutdown() {
        HandlerList.unregisterAll(this);
        lockedPlayers.clear();
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (lockedPlayers.contains(event.getUniqueId())) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "Player State Is Being Reconciled");
        }
    }

    private void begin(NetworkStateReconciliationTask task, CompletableFuture<Void> result) {
        Set<UUID> online = new LinkedHashSet<>();
        List<CompletableFuture<Void>> onlineClears = new ArrayList<>();
        for (UUID playerId : task.playerIds()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                continue;
            }
            online.add(playerId);
            CompletableFuture<Void> cleared = new CompletableFuture<>();
            boolean scheduled = player.getScheduler().execute(plugin, () -> {
                try {
                    clearOnline(player, task.families());
                    player.saveData();
                    cleared.complete(null);
                } catch (RuntimeException exception) {
                    cleared.completeExceptionally(exception);
                }
            }, () -> cleared.completeExceptionally(new IllegalStateException("Player Left During State Reconciliation")), 1L);
            if (!scheduled) {
                cleared.completeExceptionally(new IllegalStateException("Player State Reconciliation Could Not Be Scheduled"));
            }
            onlineClears.add(cleared);
        }
        Set<Path> directories = playerDataDirectories();
        CompletableFuture.allOf(onlineClears.toArray(new CompletableFuture[0])).whenComplete((unused, throwable) -> {
            if (throwable != null) {
                result.completeExceptionally(throwable);
                return;
            }
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> reconcileOffline(task, online, directories, result));
        });
    }

    private void reconcileOffline(NetworkStateReconciliationTask task, Set<UUID> online, Set<Path> directories, CompletableFuture<Void> result) {
        try {
            Path backupDirectory = Path.of(plugin.getDataFolder().getPath(), "network", "reconciliation-backups", safeName(task.transitionId()));
            for (UUID playerId : task.playerIds()) {
                if (online.contains(playerId)) {
                    continue;
                }
                for (Path directory : directories) {
                    Path file = directory.resolve(playerId + ".dat");
                    if (Files.isRegularFile(file)) {
                        String directoryId = Integer.toUnsignedString(directory.toAbsolutePath().normalize().toString().hashCode(), 16);
                        reconcileFile(file, backupDirectory.resolve(directoryId).resolve(playerId + ".dat"), task.families());
                    }
                }
            }
            result.complete(null);
        } catch (Exception exception) {
            result.completeExceptionally(exception);
        }
    }

    private void reconcileFile(Path file, Path backup, Set<String> families) throws IOException {
        NbtTag root = Nbt.readCompressed(file);
        if (root == null || root.type() != Nbt.COMPOUND) {
            throw new IOException("Player Data Is Not A Compound: " + file.getFileName());
        }
        boolean changed = false;
        if (families.contains("inventory")) {
            changed |= Nbt.clearList(root, "Inventory");
        }
        if (families.contains("ender-chest")) {
            changed |= Nbt.clearList(root, "EnderItems");
        }
        if (!changed) {
            return;
        }
        Files.createDirectories(backup.getParent());
        if (!Files.exists(backup)) {
            Files.copy(file, backup);
        }
        Nbt.writeCompressed(file, root);
    }

    private void clearOnline(Player player, Set<String> families) {
        if (families.contains("inventory")) {
            player.getInventory().clear();
            player.getInventory().setArmorContents(new ItemStack[player.getInventory().getArmorContents().length]);
            player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
            player.setItemOnCursor(new ItemStack(Material.AIR));
        }
        if (families.contains("ender-chest")) {
            player.getEnderChest().clear();
        }
        player.updateInventory();
    }

    private Set<Path> playerDataDirectories() {
        Set<Path> directories = new LinkedHashSet<>();
        for (World world : Bukkit.getWorlds()) {
            directories.add(world.getWorldFolder().toPath().resolve("playerdata"));
        }
        Path container = Bukkit.getWorldContainer().toPath();
        if (Files.isDirectory(container)) {
            try (var stream = Files.list(container)) {
                stream.map(path -> path.resolve("playerdata")).filter(Files::isDirectory).forEach(directories::add);
            } catch (IOException exception) {
                throw new IllegalStateException("Scan Player Data Directories Failed", exception);
            }
        }
        return Set.copyOf(directories);
    }

    private String safeName(String value) {
        String safe = value.replaceAll("[^a-zA-Z0-9._-]", "_");
        return safe.isBlank() ? UUID.randomUUID().toString() : safe;
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
        private static final int MAXIMUM_COLLECTION_SIZE = 16_777_216;

        private static NbtTag readCompressed(Path file) throws IOException {
            try (DataInputStream input = new DataInputStream(new GZIPInputStream(Files.newInputStream(file)))) {
                return read(input);
            }
        }

        private static void writeCompressed(Path file, NbtTag root) throws IOException {
            Path temp = file.resolveSibling(file.getFileName() + ".resync.tmp");
            try (OutputStream fileOutput = Files.newOutputStream(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE); DataOutputStream output = new DataOutputStream(new GZIPOutputStream(fileOutput))) {
                write(output, root);
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException exception) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private static boolean clearList(NbtTag root, String name) {
            List<NbtTag> compound = compound(root.value());
            for (int index = 0; index < compound.size(); index++) {
                NbtTag tag = compound.get(index);
                if (tag.name().equals(name) && tag.type() == LIST) {
                    NbtList list = tag.value() instanceof NbtList value ? value : new NbtList(COMPOUND, List.of());
                    if (list.values().isEmpty()) {
                        return false;
                    }
                    compound.set(index, new NbtTag(LIST, name, new NbtList(list.elementType(), new ArrayList<>())));
                    return true;
                }
            }
            return false;
        }

        private static NbtTag read(DataInputStream input) throws IOException {
            byte type = input.readByte();
            return type == END ? null : new NbtTag(type, readString(input), readPayload(input, type));
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
                default -> throw new IOException("Unsupported NBT Tag " + type);
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
            byte type = input.readByte();
            int size = boundedSize(input.readInt());
            List<Object> values = new ArrayList<>(size);
            for (int index = 0; index < size; index++) {
                values.add(readPayload(input, type));
            }
            return new NbtList(type, values);
        }

        private static byte[] readByteArray(DataInputStream input) throws IOException {
            byte[] values = new byte[boundedSize(input.readInt())];
            input.readFully(values);
            return values;
        }

        private static int[] readIntArray(DataInputStream input) throws IOException {
            int[] values = new int[boundedSize(input.readInt())];
            for (int index = 0; index < values.length; index++) {
                values[index] = input.readInt();
            }
            return values;
        }

        private static long[] readLongArray(DataInputStream input) throws IOException {
            long[] values = new long[boundedSize(input.readInt())];
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
                case BYTE_ARRAY -> writeByteArray(output, (byte[]) value);
                case STRING -> writeString(output, String.valueOf(value));
                case LIST -> writeList(output, (NbtList) value);
                case COMPOUND -> writeCompound(output, compound(value));
                case INT_ARRAY -> writeIntArray(output, (int[]) value);
                case LONG_ARRAY -> writeLongArray(output, (long[]) value);
                default -> throw new IOException("Unsupported NBT Tag " + type);
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
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 65_535) {
                throw new IOException("NBT String Is Too Long");
            }
            output.writeShort(bytes.length);
            output.write(bytes);
        }

        @SuppressWarnings("unchecked")
        private static List<NbtTag> compound(Object value) {
            return (List<NbtTag>) value;
        }

        private static int boundedSize(int size) throws IOException {
            if (size < 0 || size > MAXIMUM_COLLECTION_SIZE) {
                throw new IOException("NBT Collection Size Is Invalid");
            }
            return size;
        }
    }
}

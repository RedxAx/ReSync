package restudio.resync.replacement.evidence.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

public final class CatalogEvidenceScanner {
    private CatalogEvidenceScanner() {
    }

    public static Inventory scan(Path root) throws IOException {
        List<Path> files;
        try (var paths = Files.walk(root)) {
            files = paths.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".json"))
                .filter(path -> !path.getFileName().toString().startsWith("_"))
                .sorted(Comparator.comparing(path -> root.relativize(path).toString().replace('\\', '/'), pathComparator()))
                .toList();
        }
        int definitions = 0;
        int inputs = 0;
        int outputs = 0;
        int missingDescriptions = 0;
        int missingNodeDescriptions = 0;
        int missingInputs = 0;
        List<String> missingInputIds = new ArrayList<>();
        for (Path file : files) {
            JsonElement rootElement = JsonParser.parseString(Files.readString(file));
            List<JsonObject> nodes = rootElement.isJsonArray() ? objects(rootElement.getAsJsonArray()) : List.of(rootElement.getAsJsonObject());
            for (JsonObject node : nodes) {
                definitions++;
                if (text(node, "description").isBlank()) {
                    missingNodeDescriptions++;
                }
                if (!node.has("inputs")) {
                    missingInputs++;
                    missingInputIds.add(text(node, "id"));
                }
                inputs += pinCount(node, "inputs");
                outputs += pinCount(node, "outputs");
                missingDescriptions += missingPinDescriptions(node, "inputs") + missingPinDescriptions(node, "outputs");
            }
        }
        return new Inventory(files.size(), definitions, inputs, outputs, missingNodeDescriptions, missingDescriptions, missingInputs,
            List.copyOf(missingInputIds), treeHash(root, files));
    }

    private static List<JsonObject> objects(JsonArray values) {
        List<JsonObject> objects = new ArrayList<>();
        for (JsonElement value : values) {
            objects.add(value.getAsJsonObject());
        }
        return objects;
    }

    private static Comparator<String> pathComparator() {
        Collator collator = Collator.getInstance(Locale.ROOT);
        collator.setStrength(Collator.PRIMARY);
        collator.setDecomposition(Collator.CANONICAL_DECOMPOSITION);
        return collator::compare;
    }

    private static int pinCount(JsonObject node, String name) {
        return node.has(name) && node.get(name).isJsonArray() ? node.getAsJsonArray(name).size() : 0;
    }

    private static int missingPinDescriptions(JsonObject node, String name) {
        if (!node.has(name) || !node.get(name).isJsonArray()) {
            return 0;
        }
        int count = 0;
        for (JsonElement pin : node.getAsJsonArray(name)) {
            if (text(pin.getAsJsonObject(), "description").isBlank()) {
                count++;
            }
        }
        return count;
    }

    private static String treeHash(Path root, List<Path> files) throws IOException {
        StringBuilder records = new StringBuilder();
        for (Path file : files) {
            records.append(hash(Files.readAllBytes(file))).append("  ")
                .append(root.relativize(file).toString().replace('\\', '/')).append('\n');
        }
        return hash(records.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String hash(byte[] input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String text(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonPrimitive() ? object.get(name).getAsString() : "";
    }

    public record Inventory(int files, int definitions, int inputs, int outputs, int missingNodeDescriptions,
                            int missingPhysicalPinDescriptions, int definitionsWithoutInputs, List<String> missingInputIds,
                            String sourceTreeHash) {
        public int physicalPins() {
            return inputs + outputs;
        }

        public int logicalCompatibilityPins() {
            return physicalPins() + definitionsWithoutInputs;
        }

        public int missingLogicalCompatibilityPinDescriptions() {
            return missingPhysicalPinDescriptions + definitionsWithoutInputs;
        }
    }
}

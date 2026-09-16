package restudio.resync.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.internal.LazilyParsedNumber;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OptionCatalogItemTest {
    @Test
    void metadataIsRecursivelyDetachedAndUnmodifiable() {
        List<Object> nested = new ArrayList<>(List.of("first"));
        Object[] objects = new Object[]{nested, new String[]{"array"}};
        int[] primitives = new int[]{3, 5};
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("nested", nested);
        metadata.put("objects", objects);
        metadata.put("primitives", primitives);

        OptionCatalogItem item = new OptionCatalogItem("value", "Value", "Description", "", "", metadata);
        nested.add("second");
        objects[1] = new String[]{"changed"};
        primitives[0] = 9;
        metadata.put("later", true);

        assertEquals(Map.of(
            "nested", List.of("first"),
            "objects", List.of(List.of("first"), List.of("array")),
            "primitives", List.of(3, 5)
        ), item.metadata());
        assertThrows(UnsupportedOperationException.class, () -> item.metadata().put("blocked", true));
        assertThrows(UnsupportedOperationException.class,
            () -> ((List<Object>) item.metadata().get("nested")).add("blocked"));
    }

    @Test
    void rejectsUnsupportedKeysValuesAndCycles() {
        Map<Object, Object> nonStringKey = new LinkedHashMap<>();
        nonStringKey.put(1, "value");
        Map<String, Object> unsupported = Map.of("live", new StringBuilder("value"));
        List<Object> cycle = new ArrayList<>();
        cycle.add(cycle);

        assertThrows(IllegalArgumentException.class,
            () -> new OptionCatalogItem("value", "Value", "", "", "", (Map<String, Object>) (Map<?, ?>) nonStringKey));
        assertThrows(IllegalArgumentException.class,
            () -> new OptionCatalogItem("value", "Value", "", "", "", unsupported));
        assertThrows(IllegalArgumentException.class,
            () -> new OptionCatalogItem("value", "Value", "", "", "", Map.of("cycle", cycle)));
    }

    @Test
    void acceptsDetachedGsonIntegerAndDecimalNumbers() {
        JsonObject json = JsonParser.parseString("{\"integer\":42,\"decimal\":1.25}").getAsJsonObject();
        OptionCatalogItem item = new OptionCatalogItem("value", "Value", "", "", "", Map.of(
            "integerPrimitive", json.getAsJsonPrimitive("integer"),
            "integerNumber", json.getAsJsonPrimitive("integer").getAsNumber(),
            "decimalPrimitive", json.getAsJsonPrimitive("decimal"),
            "decimalNumber", json.getAsJsonPrimitive("decimal").getAsNumber()));

        assertEquals(new BigInteger("42"), item.metadata().get("integerPrimitive"));
        assertEquals(new BigInteger("42"), item.metadata().get("integerNumber"));
        assertEquals(new BigDecimal("1.25"), item.metadata().get("decimalPrimitive"));
        assertEquals(new BigDecimal("1.25"), item.metadata().get("decimalNumber"));
    }

    @Test
    void acceptsNumbersFromDefaultGsonRecordDecoding() {
        OptionCatalogItem item = new Gson().fromJson(
            "{\"value\":\"value\",\"metadata\":{\"integer\":42,\"decimal\":1.25,\"nested\":[3,4.5]}}",
            OptionCatalogItem.class);

        assertEquals(new BigDecimal("42.0"), item.metadata().get("integer"));
        assertEquals(new BigDecimal("1.25"), item.metadata().get("decimal"));
        assertEquals(List.of(new BigDecimal("3.0"), new BigDecimal("4.5")), item.metadata().get("nested"));
    }

    @Test
    void rejectsNonfiniteInvalidAndOversizedGsonNumbersBeforeExpansion() {
        String excessivePrecision = "1".repeat(1025);

        assertThrows(IllegalArgumentException.class,
            () -> new OptionCatalogItem("value", "Value", "", "", "", Map.of("number", new JsonPrimitive(Double.NaN))));
        assertThrows(IllegalArgumentException.class,
            () -> new OptionCatalogItem("value", "Value", "", "", "", Map.of("number", new JsonPrimitive(
                new LazilyParsedNumber("1e")))));
        assertThrows(IllegalArgumentException.class,
            () -> new OptionCatalogItem("value", "Value", "", "", "", Map.of("number", new JsonPrimitive(
                new LazilyParsedNumber(excessivePrecision)))));
        assertThrows(IllegalArgumentException.class,
            () -> new OptionCatalogItem("value", "Value", "", "", "", Map.of("number", new JsonPrimitive(
                new LazilyParsedNumber("1e2147483647")))));
    }
}

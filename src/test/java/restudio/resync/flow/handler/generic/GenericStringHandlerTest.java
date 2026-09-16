package restudio.resync.flow.handler.generic;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.modules.flow.FlowPacketSender;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericStringHandlerTest {
    @Test
    void basicOperationsPublishTheirExactOutputPins() {
        Map<String, Map<String, Object>> cases = Map.of(
            "concat", Map.of("a", "Hello, ", "b", "world", "output", "result", "expected", "Hello, world"),
            "trim", Map.of("value", "  text  ", "output", "result", "expected", "text"),
            "length", Map.of("value", "text", "output", "result", "expected", 4),
            "is_empty", Map.of("text", "", "output", "is_empty", "expected", true),
            "is_blank", Map.of("text", " \t", "output", "is_blank", "expected", true),
            "is_numeric", Map.of("text", "-12.50", "output", "is_numeric", "expected", true)
        );

        for (Map.Entry<String, Map<String, Object>> entry : cases.entrySet()) {
            Map<String, Object> inputs = new HashMap<>(entry.getValue());
            String output = (String) inputs.remove("output");
            Object expected = inputs.remove("expected");
            TestFlowContext context = execute(entry.getKey(), inputs);

            assertEquals(expected, context.outputs.get(output), entry.getKey());
            assertEquals(Set.of(output), context.outputs.keySet(), entry.getKey());
        }
    }

    @Test
    void upperAndLowerUseRootLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            TestFlowContext upper = execute("upper", Map.of("value", "iI"));
            TestFlowContext lower = execute("lower", Map.of("value", "iI"));

            assertEquals("II", upper.outputs.get("result"));
            assertEquals("ii", lower.outputs.get("result"));
            assertEquals(Set.of("result"), upper.outputs.keySet());
            assertEquals(Set.of("result"), lower.outputs.keySet());
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void caseAndTemplateOperationsPreserveOutputsAndDoNotContinueFlow() {
        Map<String, Map<String, Object>> cases = Map.ofEntries(
            Map.entry("slugify", Map.of("text", "-- Hello, World! --", "slug", "hello-world")),
            Map.entry("camel_case", Map.of("text", "hello-world.foo", "camel_case", "helloWorldFoo")),
            Map.entry("pascal_case", Map.of("text", "hello-world.foo", "pascal_case", "HelloWorldFoo")),
            Map.entry("snake_case", Map.of("text", "helloWorld.foo", "snake_case", "hello_world_foo")),
            Map.entry("kebab_case", Map.of("text", "helloWorld.foo", "kebab_case", "hello-world-foo")),
            Map.entry("template", Map.of("template", "Keep {{value}} exactly", "result", "Keep {{value}} exactly")),
            Map.entry("capitalize", Map.of("value", "hello, WORLD", "result", "Hello, World"))
        );

        for (Map.Entry<String, Map<String, Object>> entry : cases.entrySet()) {
            Map<String, Object> inputs = new HashMap<>(entry.getValue());
            String operation = entry.getKey();
            String output = switch (operation) {
                case "slugify" -> "slug";
                case "camel_case", "pascal_case", "snake_case", "kebab_case" -> operation;
                default -> "result";
            };
            Object expected = inputs.remove(output);

            TestFlowContext context = execute(operation, inputs);

            assertEquals(Map.of(output, expected), context.outputs, operation);
            assertTrue(context.triggeredOutputs.isEmpty(), operation);
        }

        Map<String, String> inputPins = Map.of(
            "slugify", "text",
            "camel_case", "text",
            "pascal_case", "text",
            "snake_case", "text",
            "kebab_case", "text",
            "template", "template",
            "capitalize", "value");
        Map<String, String> outputPins = Map.of(
            "slugify", "slug",
            "camel_case", "camel_case",
            "pascal_case", "pascal_case",
            "snake_case", "snake_case",
            "kebab_case", "kebab_case",
            "template", "result",
            "capitalize", "result");
        for (String operation : inputPins.keySet()) {
            Map<String, Object> emptyInput = Map.of(inputPins.get(operation), "");
            assertEquals(Map.of(outputPins.get(operation), ""), execute(operation, emptyInput).outputs, operation + " empty");

            Map<String, Object> nullInput = new HashMap<>();
            nullInput.put(inputPins.get(operation), null);
            TestFlowContext nullContext = execute(operation, nullInput);
            assertEquals(Map.of(outputPins.get(operation), ""), nullContext.outputs, operation + " null");
            assertTrue(nullContext.triggeredOutputs.isEmpty(), operation + " null");
        }
    }

    @Test
    void caseOperationsUseRootLocaleForEveryCaseConversion() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertEquals("i-i", execute("slugify", Map.of("text", "I İ")).outputs.get("slug"));
            assertEquals("iCase", execute("camel_case", Map.of("text", "I case")).outputs.get("camel_case"));
            assertEquals("ICase", execute("pascal_case", Map.of("text", "I case")).outputs.get("pascal_case"));
            assertEquals("i_case", execute("snake_case", Map.of("text", "I Case")).outputs.get("snake_case"));
            assertEquals("i-case", execute("kebab_case", Map.of("text", "I Case")).outputs.get("kebab_case"));
            assertEquals("Ii", execute("capitalize", Map.of("value", "iI")).outputs.get("result"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void caseOperationsPreserveUnicodeWordsAndIgnoreLeadingSeparators() {
        assertEquals("helloWorld", execute("camel_case", Map.of("text", "--hello\u2003world")).outputs.get("camel_case"));
        assertEquals("Café", execute("pascal_case", Map.of("text", "café")).outputs.get("pascal_case"));
        assertEquals("naïve_value", execute("snake_case", Map.of("text", "naïveValue")).outputs.get("snake_case"));
        assertEquals("naïve-value", execute("kebab_case", Map.of("text", "naïveValue")).outputs.get("kebab_case"));
        assertEquals("café١٢", execute("camel_case", Map.of("text", " café\u00a0١٢ ")).outputs.get("camel_case"));
        assertEquals("Hello World", execute("capitalize", Map.of("value", "hello\u2003WORLD")).outputs.get("result"));
    }

    @Test
    void caseAndTemplateOperationsRejectOversizedInputsAndResultsBeforeOutput() {
        Map<String, String> inputPins = Map.of(
            "slugify", "text",
            "camel_case", "text",
            "pascal_case", "text",
            "snake_case", "text",
            "kebab_case", "text",
            "template", "template",
            "capitalize", "value");
        Map<String, String> labels = Map.of(
            "slugify", "Slugify source",
            "camel_case", "Camel case source",
            "pascal_case", "Pascal case source",
            "snake_case", "Snake case source",
            "kebab_case", "Kebab case source",
            "template", "Template source",
            "capitalize", "Capitalize source");

        for (String operation : inputPins.keySet()) {
            TestFlowContext context = new TestFlowContext(Map.of(inputPins.get(operation),
                "x".repeat(FlowPacketSender.MAX_STRING_LENGTH + 1)));
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> execute(operation, context), operation);
            assertEquals(labels.get(operation) + " cannot exceed " + FlowPacketSender.MAX_STRING_LENGTH + " characters",
                exception.getMessage(), operation);
            assertTrue(context.outputs.isEmpty(), operation);
            assertTrue(context.triggeredOutputs.isEmpty(), operation);
        }

        String expandingValue = ("aİ").repeat(FlowPacketSender.MAX_STRING_LENGTH / 2);
        TestFlowContext resultContext = new TestFlowContext(Map.of("value", expandingValue));
        IllegalArgumentException resultException = assertThrows(IllegalArgumentException.class,
            () -> execute("capitalize", resultContext));
        assertEquals("Capitalize result cannot exceed " + FlowPacketSender.MAX_STRING_LENGTH + " characters",
            resultException.getMessage());
        assertTrue(resultContext.outputs.isEmpty());
        assertTrue(resultContext.triggeredOutputs.isEmpty());
    }

    @Test
    void dataOnlyOperationsPublishExactDataWithoutFlowContinuation() {
        Map<String, Map<String, Object>> cases = Map.ofEntries(
            Map.entry("concat", Map.of("a", "Hello, ", "b", "world", "result", "Hello, world")),
            Map.entry("upper", Map.of("value", "hello", "result", "HELLO")),
            Map.entry("lower", Map.of("value", "HELLO", "result", "hello")),
            Map.entry("trim", Map.of("value", "  text  ", "result", "text")),
            Map.entry("length", Map.of("value", "text", "result", 4)),
            Map.entry("is_empty", Map.of("text", "", "is_empty", true)),
            Map.entry("is_blank", Map.of("text", " \t", "is_blank", true)),
            Map.entry("is_numeric", Map.of("text", "-12.50", "is_numeric", true)),
            Map.entry("md5", Map.of("text", "text", "hash", "1cb251ec0d568de6a929b520c4aed8d1")),
            Map.entry("sha256", Map.of("text", "text", "hash", "982d9e3eb996f559e633f4d194def3761d909f5a3b647d1a851fead67c32c9d1")),
            Map.entry("sha512", Map.of("text", "text", "hash", "eaf2c12742cb8c161bcbd84b032b9bb98999a23282542672ca01cc6edd268f7dce9987ad6b2bc79305634f89d90b90102bcd59a57e7135b8e3ceb93c0597117b")),
            Map.entry("is_alpha", Map.of("text", "Text", "is_alpha", true)),
            Map.entry("is_alphanumeric", Map.of("text", "Text123", "is_alphanumeric", true)),
            Map.entry("is_email", Map.of("text", "user@example.com", "is_email", true)),
            Map.entry("contains", Map.of("value", "Hello, world", "substring", "world", "result", true)),
            Map.entry("starts_with", Map.of("value", "prefix value", "prefix", "prefix", "result", true)),
            Map.entry("ends_with", Map.of("value", "prefix value", "suffix", "value", "result", true)),
            Map.entry("replace", Map.of("target", "old", "replacement", "new", "result", "")),
            Map.entry("base64_encode", Map.of("encoded", "")),
            Map.entry("url_encode", Map.of("text", "Hello world/世界", "encoded", "Hello+world%2F%E4%B8%96%E7%95%8C")),
            Map.entry("join", Map.of("list", List.of("one", "two", "three"), "separator", "|", "result", "one|two|three")),
            Map.entry("pad_left", Map.of("text", "x", "length", 3, "pad_char", "ab", "padded", "aax")),
            Map.entry("pad_right", Map.of("text", "x", "length", 3, "pad_char", "", "padded", "x  ")),
            Map.entry("truncate", Map.of("text", "Hello, world", "length", 8, "add_ellipsis", true, "truncated", "Hello...")),
            Map.entry("reverse", Map.of("text", "abc", "value", "legacy", "reversed", "cba")),
            Map.entry("repeat", Map.of("text", "ab", "value", "legacy", "count", 3, "repeated", "ababab")),
            Map.entry("levenshtein", Map.of("text1", "kitten", "text2", "sitting", "distance", 3)),
            Map.entry("substring", Map.of("value", "abcdef", "start", 2, "length", 3, "result", "cde")),
            Map.entry("split", Map.of("value", "a|b||c", "delimiter", "|", "result", List.of("a", "b", "", "c")))
        );
        Set<String> outputPins = Set.of("result", "hash", "encoded", "is_empty", "is_blank", "is_numeric",
            "is_alpha", "is_alphanumeric", "is_email", "padded", "truncated", "reversed", "repeated", "distance");

        for (Map.Entry<String, Map<String, Object>> entry : cases.entrySet()) {
            Map<String, Object> inputs = new HashMap<>(entry.getValue());
            Map<String, Object> expected = new HashMap<>(inputs);
            inputs.entrySet().removeIf(value -> outputPins.contains(value.getKey()));
            expected.entrySet().removeIf(value -> !outputPins.contains(value.getKey()));

            TestFlowContext context = execute(entry.getKey(), inputs);

            assertEquals(expected, context.outputs, entry.getKey());
            assertTrue(context.triggeredOutputs.isEmpty(), entry.getKey());
        }
    }

    @Test
    void boundedStringLengthsRejectNegativeAndOversizedValues() {
        for (String operation : List.of("pad_left", "pad_right", "truncate")) {
            for (int length : List.of(-1, FlowPacketSender.MAX_STRING_LENGTH + 1)) {
                IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> execute(operation, Map.of("text", "value", "length", length)));
                assertEquals("String length must be between 0 and " + FlowPacketSender.MAX_STRING_LENGTH,
                    exception.getMessage(), operation + " length " + length);
            }
        }
    }

    @Test
    void canonicalStringOperationsPublishDataWithoutFlowContinuation() {
        TestFlowContext matches = execute("matches", Map.of("value", "hello", "pattern", "h.*o"));
        assertEquals(Map.of("result", true), matches.outputs);
        assertTrue(matches.triggeredOutputs.isEmpty());

        TestFlowContext equalsIgnoreCase = execute("equals_ignore_case", Map.of("value", "Hello", "other", "hELLo"));
        assertEquals(Map.of("result", true), equalsIgnoreCase.outputs);
        assertTrue(equalsIgnoreCase.triggeredOutputs.isEmpty());

        TestFlowContext toJson = execute("to_json", Map.of("value", Map.of("key", "value")));
        assertEquals(Map.of("json", "{\"key\":\"value\"}"), toJson.outputs);
        assertTrue(toJson.triggeredOutputs.isEmpty());

        TestFlowContext fromJson = execute("from_json", Map.of("json", "[\"one\",\"two\"]", "type_name", "list"));
        assertEquals(Map.of("result", List.of("one", "two")), fromJson.outputs);
        assertTrue(fromJson.triggeredOutputs.isEmpty());

        TestFlowContext shuffle = execute("shuffle", Map.of("text", "aabbcc"));
        String shuffled = (String) shuffle.outputs.get("shuffled");
        assertEquals(List.of(97, 97, 98, 98, 99, 99), shuffled.chars().sorted().boxed().toList());
        assertTrue(shuffle.triggeredOutputs.isEmpty());

        TestFlowContext soundex = execute("soundex", Map.of("text", "Robert"));
        assertEquals(Map.of("code", "R163"), soundex.outputs);
        assertTrue(soundex.triggeredOutputs.isEmpty());

        TestFlowContext metaphone = execute("metaphone", Map.of("text", "Robert"));
        assertEquals(Map.of("code", "RPRT"), metaphone.outputs);
        assertTrue(metaphone.triggeredOutputs.isEmpty());

        TestFlowContext indexOf = execute("index_of", Map.of("value", "one two two", "substring", "two"));
        assertEquals(Map.of("index", 4), indexOf.outputs);
        assertTrue(indexOf.triggeredOutputs.isEmpty());

        TestFlowContext lastIndexOf = execute("last_index_of", Map.of("value", "one two two", "substring", "two"));
        assertEquals(Map.of("index", 8), lastIndexOf.outputs);
        assertTrue(lastIndexOf.triggeredOutputs.isEmpty());

        TestFlowContext replaceRegex = execute("replace_regex",
            Map.of("value", "one two", "pattern", "\\s+", "replacement", "-"));
        assertEquals(Map.of("result", "one-two"), replaceRegex.outputs);
        assertTrue(replaceRegex.triggeredOutputs.isEmpty());
    }

    @Test
    void repeatAndLevenshteinRejectOversizedResultsBeforeAllocation() {
        IllegalArgumentException repeat = assertThrows(IllegalArgumentException.class,
            () -> execute("repeat", Map.of("text", "ab", "count", 32_769)));
        assertEquals("Repeated string result cannot exceed " + FlowPacketSender.MAX_STRING_LENGTH + " characters", repeat.getMessage());

        for (String input : List.of("text1", "text2")) {
            IllegalArgumentException levenshtein = assertThrows(IllegalArgumentException.class,
                () -> execute("levenshtein", Map.of(input, "x".repeat(1_025))));
            assertEquals("Levenshtein input cannot exceed 1024 characters", levenshtein.getMessage());
        }
    }

    @Test
    void repeatCountBoundsRejectBeforeOutputEvenForEmptyText() {
        List<Map<String, Object>> invalidInputs = List.of(
            Map.of("text", "text", "count", -1),
            Map.of("text", "text", "count", FlowPacketSender.MAX_STRING_LENGTH + 1),
            Map.of("text", "", "count", -1),
            Map.of("text", "", "count", FlowPacketSender.MAX_STRING_LENGTH + 1));
        String expectedMessage = "Repeat count must be between 0 and " + FlowPacketSender.MAX_STRING_LENGTH;

        for (Map<String, Object> inputs : invalidInputs) {
            TestFlowContext context = new TestFlowContext(inputs);
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> execute("repeat", context), inputs.toString());
            assertEquals(expectedMessage, exception.getMessage(), inputs.toString());
            assertTrue(context.outputs.isEmpty(), inputs.toString());
            assertTrue(context.triggeredOutputs.isEmpty(), inputs.toString());
        }
    }

    @Test
    void substringAndSplitPreserveDefaultsAndRejectOversizedInputs() {
        TestFlowContext substringDefaults = execute("substring", Map.of());
        assertEquals(Map.of("result", ""), substringDefaults.outputs);
        assertTrue(substringDefaults.triggeredOutputs.isEmpty());

        TestFlowContext missingLength = execute("substring", Map.of("value", "abcdef", "start", 2));
        assertEquals(Map.of("result", "cdef"), missingLength.outputs);
        assertTrue(missingLength.triggeredOutputs.isEmpty());

        TestFlowContext clippedLength = execute("substring", Map.of("value", "abcdef", "start", 4, "length", 10));
        assertEquals(Map.of("result", "ef"), clippedLength.outputs);
        assertTrue(clippedLength.triggeredOutputs.isEmpty());

        TestFlowContext maximumStart = execute("substring", Map.of("value", "abcdef", "start", FlowPacketSender.MAX_STRING_LENGTH));
        assertEquals(Map.of("result", ""), maximumStart.outputs);
        assertTrue(maximumStart.triggeredOutputs.isEmpty());

        TestFlowContext splitDefaults = execute("split", Map.of());
        assertEquals(Map.of("result", List.of("")), splitDefaults.outputs);
        assertTrue(splitDefaults.triggeredOutputs.isEmpty());

        Map<String, String> invalidSubstringBounds = Map.of(
            "negative start", "Substring start must be between 0 and " + FlowPacketSender.MAX_STRING_LENGTH,
            "oversized start", "Substring start must be between 0 and " + FlowPacketSender.MAX_STRING_LENGTH,
            "negative length", "Substring length must be between 0 and " + FlowPacketSender.MAX_STRING_LENGTH,
            "oversized length", "Substring length must be between 0 and " + FlowPacketSender.MAX_STRING_LENGTH);
        Map<String, Map<String, Object>> invalidSubstringInputs = Map.of(
            "negative start", Map.of("value", "value", "start", -1),
            "oversized start", Map.of("value", "value", "start", FlowPacketSender.MAX_STRING_LENGTH + 1),
            "negative length", Map.of("value", "value", "start", 1, "length", -1),
            "oversized length", Map.of("value", "value", "start", 1, "length", FlowPacketSender.MAX_STRING_LENGTH + 1));
        for (Map.Entry<String, String> invalidBound : invalidSubstringBounds.entrySet()) {
            TestFlowContext context = new TestFlowContext(invalidSubstringInputs.get(invalidBound.getKey()));
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> execute("substring", context), invalidBound.getKey());
            assertEquals(invalidBound.getValue(), exception.getMessage(), invalidBound.getKey());
            assertTrue(context.outputs.isEmpty(), invalidBound.getKey());
            assertTrue(context.triggeredOutputs.isEmpty(), invalidBound.getKey());
        }

        IllegalArgumentException oversizedSubstring = assertThrows(IllegalArgumentException.class,
            () -> execute("substring", Map.of("value", "x".repeat(FlowPacketSender.MAX_STRING_LENGTH + 1))));
        assertEquals("Substring source cannot exceed " + FlowPacketSender.MAX_STRING_LENGTH + " characters",
            oversizedSubstring.getMessage());

        IllegalArgumentException oversizedSplitValue = assertThrows(IllegalArgumentException.class,
            () -> execute("split", Map.of("value", "x".repeat(FlowPacketSender.MAX_STRING_LENGTH + 1))));
        assertEquals("Split value cannot exceed " + FlowPacketSender.MAX_STRING_LENGTH + " characters",
            oversizedSplitValue.getMessage());

        IllegalArgumentException oversizedSplitDelimiter = assertThrows(IllegalArgumentException.class,
            () -> execute("split", Map.of("value", "value", "delimiter", "x".repeat(FlowPacketSender.MAX_STRING_LENGTH + 1))));
        assertEquals("Split delimiter cannot exceed " + FlowPacketSender.MAX_STRING_LENGTH + " characters",
            oversizedSplitDelimiter.getMessage());
    }

    @Test
    void encodedDecodersUseUtf8FormSemanticsAndDoNotContinueFlow() {
        TestFlowContext base64 = execute("base64_decode", Map.of("encoded", "SGVsbG8sIOS4lueVjA=="));
        assertEquals(Map.of("decoded", "Hello, 世界"), base64.outputs);
        assertTrue(base64.triggeredOutputs.isEmpty());

        TestFlowContext url = execute("url_decode", Map.of("encoded", "Hello+world%2F%E4%B8%96%E7%95%8C"));
        assertEquals(Map.of("decoded", "Hello world/世界"), url.outputs);
        assertTrue(url.triggeredOutputs.isEmpty());

        Map<String, Object> nullEncoded = new HashMap<>();
        nullEncoded.put("encoded", null);
        assertEquals(Map.of("decoded", ""), execute("base64_decode", nullEncoded).outputs);
        assertEquals(Map.of("decoded", ""), execute("url_decode", nullEncoded).outputs);
    }

    @Test
    void encodedDecodersRejectMalformedAndOversizedInputsBeforeOutput() {
        for (String encoded : List.of("SGVsbG8", "AB==", "not-base64", "/w==")) {
            TestFlowContext context = new TestFlowContext(Map.of("encoded", encoded));
            assertThrows(IllegalArgumentException.class, () -> execute("base64_decode", context), encoded);
            assertTrue(context.outputs.isEmpty(), encoded);
            assertTrue(context.triggeredOutputs.isEmpty(), encoded);
        }

        for (String encoded : List.of("%", "%ZZ", "%E2%82")) {
            TestFlowContext context = new TestFlowContext(Map.of("encoded", encoded));
            assertThrows(IllegalArgumentException.class, () -> execute("url_decode", context), encoded);
            assertTrue(context.outputs.isEmpty(), encoded);
            assertTrue(context.triggeredOutputs.isEmpty(), encoded);
        }

        for (String operation : List.of("base64_decode", "url_decode")) {
            String oversized = "x".repeat(FlowPacketSender.MAX_STRING_LENGTH + 1);
            TestFlowContext context = new TestFlowContext(Map.of("encoded", oversized));
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> execute(operation, context), operation);
            assertTrue(failure.getMessage().contains("cannot exceed " + FlowPacketSender.MAX_STRING_LENGTH), operation);
            assertTrue(context.outputs.isEmpty(), operation);
            assertTrue(context.triggeredOutputs.isEmpty(), operation);
        }
    }

    @Test
    void wordWrapNormalizesUnicodeWhitespaceAndPreservesOverlongWords() {
        TestFlowContext normalized = execute("word_wrap",
            Map.of("text", "alpha\t\u2003beta\u00a0gamma", "width", 10));
        assertEquals(Map.of("wrapped_lines_list", List.of("alpha beta", "gamma")), normalized.outputs);
        assertTrue(normalized.triggeredOutputs.isEmpty());

        TestFlowContext overlong = execute("word_wrap",
            Map.of("text", "tiny supercalifragilistic end", "width", 5));
        assertEquals(Map.of("wrapped_lines_list", List.of("tiny", "supercalifragilistic", "end")), overlong.outputs);
        assertTrue(overlong.triggeredOutputs.isEmpty());

        assertEquals(Map.of("wrapped_lines_list", List.of()), execute("word_wrap", Map.of()).outputs);

        TestFlowContext detached = execute("word_wrap", Map.of("text", "one two", "width", 20));
        assertTrue(detached.outputs.get("wrapped_lines_list") instanceof ArrayList);
    }

    @Test
    void wordWrapAcceptsFiniteWholeNumberWidthsAndRejectsInvalidWidthsBeforeOutput() {
        TestFlowContext doubleWidth = execute("word_wrap", Map.of("text", "one two", "width", 3.0));
        assertEquals(Map.of("wrapped_lines_list", List.of("one", "two")), doubleWidth.outputs);
        assertTrue(doubleWidth.triggeredOutputs.isEmpty());

        List<Object> invalidWidths = List.of(0, -1, FlowPacketSender.MAX_STRING_LENGTH + 1,
            3.5, Double.NaN, Double.POSITIVE_INFINITY, "3");
        for (Object width : invalidWidths) {
            TestFlowContext context = new TestFlowContext(Map.of("text", "text", "width", width));
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> execute("word_wrap", context), String.valueOf(width));
            assertTrue(failure.getMessage().contains("finite whole number between 1 and "
                + FlowPacketSender.MAX_STRING_LENGTH), String.valueOf(width));
            assertTrue(context.outputs.isEmpty(), String.valueOf(width));
            assertTrue(context.triggeredOutputs.isEmpty(), String.valueOf(width));
        }

        TestFlowContext oversized = new TestFlowContext(Map.of("text",
            "x".repeat(FlowPacketSender.MAX_STRING_LENGTH + 1)));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> execute("word_wrap", oversized));
        assertEquals("Word wrap text cannot exceed " + FlowPacketSender.MAX_STRING_LENGTH + " characters",
            failure.getMessage());
        assertTrue(oversized.outputs.isEmpty());
        assertTrue(oversized.triggeredOutputs.isEmpty());
    }

    private TestFlowContext execute(String operation, Map<String, Object> inputs) {
        TestFlowContext context = new TestFlowContext(inputs);
        execute(operation, context);
        return context;
    }

    private void execute(String operation, TestFlowContext context) {
        HandlerRegistry registry = new HandlerRegistry();
        new GenericStringHandler().registerTo(registry);
        NodeHandler handler = registry.getHandler("GenericStringHandler");
        FlowNode node = new FlowNode("string." + operation, 0, 0, Map.of());
        node.setHandlerConfig(Map.of("operation", operation));
        handler.execute(context, node);
    }

    private static class TestFlowContext extends FlowContext {
        private final Map<String, Object> inputs;
        private final Map<String, Object> outputs = new HashMap<>();
        private final Map<String, Object> triggeredOutputs = new HashMap<>();

        private TestFlowContext(Map<String, Object> inputs) {
            super(null, null, null);
            this.inputs = inputs;
        }

        @Override
        public <T> T getInputValue(FlowNode node, String pinName, Class<T> type, T defaultValue) {
            Object value = inputs.get(pinName);
            return value != null ? type.cast(value) : defaultValue;
        }

        @Override
        public void setOutput(FlowNode node, String pinName, Object value) {
            outputs.put(pinName, value);
        }

        @Override
        public void triggerOutput(String pinName) {
            triggeredOutputs.put(pinName, true);
        }
    }
}

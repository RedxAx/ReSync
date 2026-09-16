package restudio.resync.flow.handler.generic;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericListHandlerTest {
    @Test
    void getPublishesValueThroughRegisteredOutputPin() {
        HandlerRegistry registry = new HandlerRegistry();
        new GenericListHandler().registerTo(registry);
        NodeHandler handler = registry.getHandler("GenericListHandler");
        FlowNode node = new FlowNode("list.get", 0, 0, Map.of());
        node.setHandlerConfig(Map.of("operation", "get"));
        TestFlowContext context = new TestFlowContext(Map.of("list", List.of("name", "#AAAAAA"), "index", 1));

        handler.execute(context, node);

        assertEquals("#AAAAAA", context.outputs.get("value"));
        assertNull(context.triggeredOutput);
    }

    @Test
    void basicDataOperationsCoerceNumberIndicesAndDoNotTriggerFlow() {
        TestFlowContext create = execute("create", Map.of());
        assertEquals(List.of(), create.outputs.get("list"));
        assertFalse(create.outputs.containsKey("output_list"));
        assertNull(create.triggeredOutput);

        TestFlowContext add = execute("add", Map.of("list", List.of("first")));
        assertEquals(Arrays.asList("first", null), add.outputs.get("output_list"));
        assertFalse(add.outputs.containsKey("list"));
        assertNull(add.triggeredOutput);

        TestFlowContext remove = execute("remove", Map.of("list", List.of("first", "second"), "value", "first"));
        assertEquals(List.of("second"), remove.outputs.get("output_list"));
        assertFalse(remove.outputs.containsKey("list"));
        assertNull(remove.triggeredOutput);

        TestFlowContext removeAt = execute("remove_at", Map.of("list", List.of("first", "second"), "index", 1.9D));
        assertEquals(List.of("first"), removeAt.outputs.get("output_list"));
        assertFalse(removeAt.outputs.containsKey("list"));
        assertNull(removeAt.triggeredOutput);

        TestFlowContext clear = execute("clear", Map.of("list", List.of("first", "second")));
        assertEquals(List.of(), clear.outputs.get("output_list"));
        assertFalse(clear.outputs.containsKey("list"));
        assertNull(clear.triggeredOutput);

        TestFlowContext get = execute("get", Map.of("list", List.of("first", "second"), "index", 1.0D));
        assertEquals("second", get.outputs.get("value"));
        assertNull(get.triggeredOutput);

        TestFlowContext set = execute("set", Map.of("list", List.of("first", "second"), "index", 1.9D, "value", "updated"));
        assertEquals(List.of("first", "updated"), set.outputs.get("output_list"));
        assertFalse(set.outputs.containsKey("list"));
        assertNull(set.triggeredOutput);

        TestFlowContext size = execute("size", Map.of("list", List.of("first", "second")));
        assertEquals(2, size.outputs.get("size"));
        assertNull(size.triggeredOutput);

        TestFlowContext empty = execute("is_empty", Map.of());
        assertEquals(true, empty.outputs.get("empty"));
        assertNull(empty.triggeredOutput);

        TestFlowContext contains = execute("contains", Map.of("list", List.of("first", "second"), "value", "second"));
        assertEquals(true, contains.outputs.get("contains"));
        assertNull(contains.triggeredOutput);

        TestFlowContext invalidGet = execute("get", Map.of("list", List.of("first"), "index", 4.0D));
        assertNull(invalidGet.outputs.get("value"));
        assertNull(invalidGet.triggeredOutput);

        TestFlowContext invalidMutation = execute("set", Map.of("list", List.of("first"), "index", 4.0D, "value", "updated"));
        assertEquals(List.of("first"), invalidMutation.outputs.get("output_list"));
        assertFalse(invalidMutation.outputs.containsKey("list"));
        assertNull(invalidMutation.triggeredOutput);

        TestFlowContext addAt = execute("add_at", Map.of("list", List.of("first"), "index", 1.0D, "value", "second"));
        assertEquals(List.of("first", "second"), addAt.outputs.get("list"));
        assertEquals(Set.of("list"), addAt.outputs.keySet());
        assertEquals("flow", addAt.triggeredOutput);
    }

    @Test
    void queryOperationsHandleNullsDuplicatesAndImmutableInputsWithoutFlow() {
        List<Object> values = Arrays.<Object>asList(null, "duplicate", null, "duplicate");
        Map<String, Object> missingValueInputs = new HashMap<>();
        missingValueInputs.put("list", values);

        TestFlowContext indexOfMissingValue = execute("index_of", missingValueInputs);
        assertEquals(0, indexOfMissingValue.outputs.get("index"));
        assertEquals(Set.of("index"), indexOfMissingValue.outputs.keySet());
        assertNull(indexOfMissingValue.triggeredOutput);

        TestFlowContext indexOfDuplicate = execute("index_of", Map.of("list", values, "value", "duplicate"));
        assertEquals(1, indexOfDuplicate.outputs.get("index"));
        assertEquals(Set.of("index"), indexOfDuplicate.outputs.keySet());
        assertNull(indexOfDuplicate.triggeredOutput);

        TestFlowContext countNull = execute("count", missingValueInputs);
        assertEquals(2, countNull.outputs.get("count"));
        assertEquals(Set.of("count"), countNull.outputs.keySet());
        assertNull(countNull.triggeredOutput);

        TestFlowContext countDuplicate = execute("count", Map.of("list", values, "value", "duplicate"));
        assertEquals(2, countDuplicate.outputs.get("count"));
        assertEquals(Set.of("count"), countDuplicate.outputs.keySet());
        assertNull(countDuplicate.triggeredOutput);

        TestFlowContext firstNull = execute("first", Map.of("list", values));
        assertNull(firstNull.outputs.get("item"));
        assertEquals(Set.of("item"), firstNull.outputs.keySet());
        assertNull(firstNull.triggeredOutput);

        List<Object> endingWithNull = Arrays.<Object>asList("first", null);
        TestFlowContext lastNull = execute("last", Map.of("list", endingWithNull));
        assertNull(lastNull.outputs.get("item"));
        assertEquals(Set.of("item"), lastNull.outputs.keySet());
        assertNull(lastNull.triggeredOutput);

        List<String> immutable = List.of("first", "second", "first");
        TestFlowContext first = execute("first", Map.of("list", immutable));
        assertEquals("first", first.outputs.get("item"));
        assertEquals(Set.of("item"), first.outputs.keySet());
        assertNull(first.triggeredOutput);

        TestFlowContext last = execute("last", Map.of("list", immutable));
        assertEquals("first", last.outputs.get("item"));
        assertEquals(Set.of("item"), last.outputs.keySet());
        assertNull(last.triggeredOutput);

        TestFlowContext emptyIndexOf = execute("index_of", Map.of());
        assertEquals(-1, emptyIndexOf.outputs.get("index"));
        assertEquals(Set.of("index"), emptyIndexOf.outputs.keySet());
        assertNull(emptyIndexOf.triggeredOutput);

        TestFlowContext emptyCount = execute("count", Map.of());
        assertEquals(0, emptyCount.outputs.get("count"));
        assertEquals(Set.of("count"), emptyCount.outputs.keySet());
        assertNull(emptyCount.triggeredOutput);

        TestFlowContext emptyFirst = execute("first", Map.of());
        assertNull(emptyFirst.outputs.get("item"));
        assertEquals(Set.of("item"), emptyFirst.outputs.keySet());
        assertNull(emptyFirst.triggeredOutput);

        TestFlowContext emptyLast = execute("last", Map.of());
        assertNull(emptyLast.outputs.get("item"));
        assertEquals(Set.of("item"), emptyLast.outputs.keySet());
        assertNull(emptyLast.triggeredOutput);

        Map<String, Object> nullListInputs = new HashMap<>();
        nullListInputs.put("list", null);
        TestFlowContext nullListFirst = execute("first", nullListInputs);
        assertNull(nullListFirst.outputs.get("item"));
        assertEquals(Set.of("item"), nullListFirst.outputs.keySet());
        assertNull(nullListFirst.triggeredOutput);

        TestFlowContext nullListLast = execute("last", nullListInputs);
        assertNull(nullListLast.outputs.get("item"));
        assertEquals(Set.of("item"), nullListLast.outputs.keySet());
        assertNull(nullListLast.triggeredOutput);

        assertEquals(Arrays.<Object>asList(null, "duplicate", null, "duplicate"), values);
        assertEquals(List.of("first", "second", "first"), immutable);
    }

    @Test
    void numericAggregatesIgnoreNonNumbersReturnDoublesAndDoNotTriggerFlow() {
        List<Object> values = Arrays.asList(1, "ignored", 2.5D, null, true, -3L);

        TestFlowContext sum = execute("sum", Map.of("list", values));
        TestFlowContext average = execute("average", Map.of("list", values));
        TestFlowContext min = execute("min", Map.of("list", values));
        TestFlowContext max = execute("max", Map.of("list", values));

        assertEquals(0.5D, sum.outputs.get("sum"));
        assertEquals(0.5D / 3.0D, average.outputs.get("average"));
        assertEquals(-3.0D, min.outputs.get("min"));
        assertEquals(2.5D, max.outputs.get("max"));
        assertTrue(sum.outputs.get("sum") instanceof Double);
        assertTrue(average.outputs.get("average") instanceof Double);
        assertTrue(min.outputs.get("min") instanceof Double);
        assertTrue(max.outputs.get("max") instanceof Double);
        assertNull(sum.triggeredOutput);
        assertNull(average.triggeredOutput);
        assertNull(min.triggeredOutput);
        assertNull(max.triggeredOutput);

        for (String operation : List.of("sum", "average", "min", "max")) {
            TestFlowContext missing = execute(operation, Map.of());
            TestFlowContext empty = execute(operation, Map.of("list", List.of()));
            TestFlowContext numberFree = execute(operation, Map.of("list", Arrays.asList(null, "ignored", true)));
            assertEquals(0.0D, missing.outputs.get(operation), operation + " missing");
            assertEquals(0.0D, empty.outputs.get(operation), operation + " empty");
            assertEquals(0.0D, numberFree.outputs.get(operation), operation + " number-free");
            assertNull(missing.triggeredOutput, operation + " missing flow");
            assertNull(empty.triggeredOutput, operation + " empty flow");
            assertNull(numberFree.triggeredOutput, operation + " number-free flow");
        }
    }

    @Test
    void numericAggregatesRejectNonFiniteValuesOverflowAndOversizedInputs() {
        for (String operation : List.of("sum", "average", "min", "max")) {
            assertThrows(IllegalArgumentException.class, () -> execute(operation, Map.of("list", List.of(Double.NaN))), operation + " NaN");
            assertThrows(IllegalArgumentException.class, () -> execute(operation, Map.of("list", List.of(Double.POSITIVE_INFINITY))), operation + " infinity");
            List<Object> oversized = new ArrayList<>(Collections.nCopies(65_537, 1));
            assertThrows(IllegalArgumentException.class, () -> execute(operation, Map.of("list", oversized)), operation + " size");
        }
        for (String operation : List.of("sum", "average")) {
            assertThrows(IllegalArgumentException.class, () -> execute(operation, Map.of("list", List.of(Double.MAX_VALUE, Double.MAX_VALUE))), operation + " overflow");
        }
    }

    @Test
    void publishedCollectionPinsDriveFilteringMappingAndReduction() {
        List<Map<String, Object>> values = List.of(Map.of("name", "Low", "score", 2), Map.of("name", "High", "score", 8));

        TestFlowContext filter = execute("filter", Map.of("list", values, "property_name", "score", "operator", "greater_than", "compare_value", 4));
        TestFlowContext map = execute("map", Map.of("list", values, "transformation_type", "property:name"));
        TestFlowContext reduce = execute("reduce", Map.of("list", List.of("A", "B", "C"), "operation", "concat", "separator", "-"));

        assertEquals(List.of(values.get(1)), filter.outputs.get("filtered_list"));
        assertEquals(List.of("Low", "High"), map.outputs.get("transformed_list"));
        assertEquals("A-B-C", reduce.outputs.get("result"));
    }

    @Test
    void groupingAndQuantifiersUseTheSamePredicateContract() {
        List<Map<String, Object>> values = List.of(Map.of("team", "red", "score", 2), Map.of("team", "blue", "score", 8), Map.of("team", "red", "score", 10));

        TestFlowContext groups = execute("group_by", Map.of("list", values, "property_name", "team"));
        TestFlowContext any = execute("any", Map.of("list", values, "property_name", "score", "operator", "greater_than", "compare_value", 9));
        TestFlowContext all = execute("all", Map.of("list", values, "property_name", "score", "operator", "greater_than", "compare_value", 1));
        TestFlowContext none = execute("none", Map.of("list", values, "property_name", "team", "operator", "equals", "compare_value", "green"));

        assertEquals(List.of(values.get(0), values.get(2)), ((Map<?, ?>) groups.outputs.get("groups")).get("red"));
        assertTrue((Boolean) any.outputs.get("matches"));
        assertTrue((Boolean) all.outputs.get("matches"));
        assertTrue((Boolean) none.outputs.get("matches"));
        assertTrue((Boolean) execute("all", Map.of("list", List.of(), "operator", "equals", "compare_value", 1)).outputs.get("matches"));
        assertFalse((Boolean) execute("any", Map.of("list", List.of(), "operator", "equals", "compare_value", 1)).outputs.get("matches"));
    }

    @Test
    void mutationNodesCopyConnectedListsBeforeChangingThem() {
        List<String> source = List.of("first");

        TestFlowContext context = execute("add", Map.of("list", source, "value", "second"));

        assertEquals(List.of("first"), source);
        assertEquals(List.of("first", "second"), context.outputs.get("output_list"));
        assertFalse(context.outputs.containsKey("list"));
    }

    @Test
    void boundedTransformsPreserveTheirDeclaredSemanticsWithoutFlow() {
        List<Object> source = new ArrayList<>(Arrays.asList(null, "a", "a", "b"));
        List<Object> nested = new ArrayList<>(Arrays.asList(1, Arrays.asList(2, null), List.of(3), 4));

        TestFlowContext slice = execute("slice", Map.of("list", source, "start_index", 1.0D, "end_index", -1L));
        TestFlowContext reverse = execute("reverse", Map.of("list", source));
        TestFlowContext unique = execute("unique", Map.of("list", source));
        TestFlowContext flatten = execute("flatten", Map.of("list", nested));
        TestFlowContext intersect = execute("intersect", Map.of("list1", source, "list2", Arrays.asList(null, "a")));
        TestFlowContext difference = execute("difference", Map.of("list1", source, "list2", List.of("a")));
        TestFlowContext zip = execute("zip", Map.of("list1", Arrays.asList("a", null), "list2", List.of(1, 2, 3)));
        TestFlowContext concat = execute("concat", Map.of("lista", Arrays.asList("a", null), "listb", List.of("b")));
        Map<String, Object> nullPair = new HashMap<>();
        nullPair.put("first", null);
        nullPair.put("second", 2);

        assertEquals(List.of("a", "a"), slice.outputs.get("slice_list"));
        assertEquals(Arrays.asList("b", "a", "a", null), reverse.outputs.get("reversed_list"));
        assertEquals(Arrays.asList(null, "a", "b"), unique.outputs.get("unique_list"));
        assertEquals(Arrays.asList(1, 2, null, 3, 4), flatten.outputs.get("flattened_list"));
        assertEquals(Arrays.asList(null, "a"), intersect.outputs.get("intersection_list"));
        assertEquals(Arrays.asList(null, "b"), difference.outputs.get("difference_list"));
        assertEquals(List.of(Map.of("first", "a", "second", 1), nullPair), zip.outputs.get("pairs_list"));
        assertEquals(Arrays.asList("a", null, "b"), concat.outputs.get("list"));

        assertNotSame(source, slice.outputs.get("slice_list"));
        assertNotSame(source, reverse.outputs.get("reversed_list"));
        assertNotSame(source, unique.outputs.get("unique_list"));
        assertNotSame(nested, flatten.outputs.get("flattened_list"));
        assertNotSame(source, concat.outputs.get("list"));
        assertEquals(Arrays.asList(null, "a", "a", "b"), source);
        assertEquals(Arrays.asList(1, Arrays.asList(2, null), List.of(3), 4), nested);
        assertNull(slice.triggeredOutput);
        assertNull(reverse.triggeredOutput);
        assertNull(unique.triggeredOutput);
        assertNull(flatten.triggeredOutput);
        assertNull(intersect.triggeredOutput);
        assertNull(difference.triggeredOutput);
        assertNull(zip.triggeredOutput);
        assertNull(concat.triggeredOutput);
    }

    @Test
    void orderingIsStableDetachedAndDataOnlyWithExactOrderValues() {
        Ranked highFirst = new Ranked("high-first", 2);
        Ranked low = new Ranked("low", 1);
        Ranked highSecond = new Ranked("high-second", 2);
        List<Object> source = new ArrayList<>(Arrays.asList(null, highFirst, low, highSecond));

        TestFlowContext ascending = execute("sort", Map.of("list", source));
        TestFlowContext descending = execute("sort", Map.of("list", source, "sort_order", "DeScEnDiNg"));
        TestFlowContext explicitDescending = execute("sort_descending", Map.of("list", source));

        assertEquals(Arrays.asList(low, highFirst, highSecond, null), ascending.outputs.get("sorted_list"));
        assertEquals(Arrays.asList(null, highFirst, highSecond, low), descending.outputs.get("sorted_list"));
        assertEquals(Arrays.asList(null, highFirst, highSecond, low), explicitDescending.outputs.get("output_list"));
        assertNotSame(source, ascending.outputs.get("sorted_list"));
        assertSame(highFirst, ((List<?>) ascending.outputs.get("sorted_list")).get(1));
        assertNull(ascending.triggeredOutput);
        assertNull(descending.triggeredOutput);
        assertNull(explicitDescending.triggeredOutput);

        ((List<?>) ascending.outputs.get("sorted_list")).clear();
        assertEquals(Arrays.asList(null, highFirst, low, highSecond), source);
    }

    @Test
    void orderingComparesFiniteNumbersWithoutDoublePrecisionLoss() {
        BigDecimal aboveSafeInteger = new BigDecimal("9007199254740993");
        BigInteger safeInteger = new BigInteger("9007199254740992");
        BigDecimal betweenIntegers = new BigDecimal("9007199254740992.5");
        Long largest = 9007199254740994L;
        List<Object> values = Arrays.asList(aboveSafeInteger, largest, betweenIntegers, safeInteger);

        TestFlowContext context = execute("sort", Map.of("list", values));

        assertEquals(Arrays.asList(safeInteger, betweenIntegers, aboveSafeInteger, largest), context.outputs.get("sorted_list"));
        assertNull(context.triggeredOutput);
        assertEquals(List.of(false, true), execute("sort", Map.of("list", List.of(true, false))).outputs.get("sorted_list"));
        assertThrows(IllegalArgumentException.class, () -> execute("sort", Map.of("list", List.of(Double.NaN))));
        assertThrows(IllegalArgumentException.class, () -> execute("sort_descending", Map.of("list", List.of(Double.POSITIVE_INFINITY))));
    }

    @Test
    void orderingRejectsInvalidOrderMixedValuesAndUnsupportedObjectsBeforePublishing() {
        for (String order : List.of("desc", "ascending ", "sideways")) {
            TestFlowContext context = new TestFlowContext(Map.of("list", List.of(1, 2), "sort_order", order));
            assertThrows(IllegalArgumentException.class, () -> execute("sort", context), order);
            assertTrue(context.outputs.isEmpty(), order);
            assertNull(context.triggeredOutput, order);
        }

        for (List<?> values : List.of(Arrays.<Object>asList("1", 1), Arrays.<Object>asList(new Object(), new Object()))) {
            TestFlowContext context = new TestFlowContext(Map.of("list", values));
            assertThrows(IllegalArgumentException.class, () -> execute("sort", context));
            assertTrue(context.outputs.isEmpty());
            assertNull(context.triggeredOutput);
        }
    }

    @Test
    void orderingRejectsOversizedInputsBeforePublishing() {
        List<Object> oversized = new ArrayList<>(Collections.nCopies(65_537, 1));
        for (String operation : List.of("sort", "sort_descending")) {
            TestFlowContext context = new TestFlowContext(Map.of("list", oversized));
            assertThrows(IllegalArgumentException.class, () -> execute(operation, context), operation);
            assertTrue(context.outputs.isEmpty(), operation);
            assertNull(context.triggeredOutput, operation);
        }
    }

    @Test
    void boundedTransformsTreatNullListsAsEmptyLists() {
        Map<String, Object> nullList = new HashMap<>();
        nullList.put("list", null);
        TestFlowContext slice = execute("slice", nullList);
        TestFlowContext reverse = execute("reverse", nullList);
        TestFlowContext unique = execute("unique", nullList);
        TestFlowContext flatten = execute("flatten", nullList);

        Map<String, Object> nullBinaryLists = new HashMap<>();
        nullBinaryLists.put("lista", null);
        nullBinaryLists.put("listb", null);
        TestFlowContext concat = execute("concat", nullBinaryLists);
        nullBinaryLists.put("list1", null);
        nullBinaryLists.put("list2", null);
        TestFlowContext intersect = execute("intersect", nullBinaryLists);
        TestFlowContext difference = execute("difference", nullBinaryLists);
        TestFlowContext zip = execute("zip", nullBinaryLists);

        assertEquals(List.of(), slice.outputs.get("slice_list"));
        assertEquals(List.of(), reverse.outputs.get("reversed_list"));
        assertEquals(List.of(), unique.outputs.get("unique_list"));
        assertEquals(List.of(), flatten.outputs.get("flattened_list"));
        assertEquals(List.of(), concat.outputs.get("list"));
        assertEquals(List.of(), intersect.outputs.get("intersection_list"));
        assertEquals(List.of(), difference.outputs.get("difference_list"));
        assertEquals(List.of(), zip.outputs.get("pairs_list"));
    }

    @Test
    void boundedTransformsRejectOversizedInputsAndOutputsBeforePublishing() {
        List<Object> oversized = new ArrayList<>(Collections.nCopies(65_537, "item"));
        for (String operation : List.of("slice", "reverse", "unique", "flatten")) {
            assertThrows(IllegalArgumentException.class, () -> execute(operation, Map.of("list", oversized)), operation);
        }

        List<Object> half = new ArrayList<>(Collections.nCopies(32_768, "item"));
        List<Object> tooLargeFlatten = List.of(half, half, List.of("item"));
        assertThrows(IllegalArgumentException.class, () -> execute("flatten", Map.of("list", tooLargeFlatten)));
        assertThrows(IllegalArgumentException.class, () -> execute("concat", Map.of("lista", half, "listb", new ArrayList<>(Collections.nCopies(32_769, "item")))));

        assertThrows(IllegalArgumentException.class, () -> execute("intersect", Map.of("list1", oversized, "list2", List.of())));
        assertThrows(IllegalArgumentException.class, () -> execute("difference", Map.of("list1", List.of(), "list2", oversized)));
        assertThrows(IllegalArgumentException.class, () -> execute("zip", Map.of("list1", List.of(), "list2", oversized)));
    }

    @Test
    void sliceRequiresFiniteWholeIntIndexesAndClampsAfterNegativeResolution() {
        List<Object> values = List.of("zero", "one", "two", "three");
        assertEquals(List.of("one", "two"), execute("slice", Map.of("list", values, "start_index", -3L, "end_index", -1.0D)).outputs.get("slice_list"));
        assertEquals(List.of(), execute("slice", Map.of("list", values, "start_index", 99, "end_index", 100)).outputs.get("slice_list"));
        assertEquals(List.of("zero", "one", "two", "three"), execute("slice", Map.of("list", values, "start_index", -99, "end_index", 99)).outputs.get("slice_list"));

        for (Object invalid : List.of(1.5D, Double.NaN, Double.POSITIVE_INFINITY, Long.MAX_VALUE, "1")) {
            assertThrows(IllegalArgumentException.class, () -> execute("slice", Map.of("list", values, "start_index", invalid)), String.valueOf(invalid));
        }

        Map<String, Object> nullIndexes = new HashMap<>();
        nullIndexes.put("list", values);
        nullIndexes.put("start_index", null);
        nullIndexes.put("end_index", null);
        TestFlowContext defaults = execute("slice", nullIndexes);
        assertEquals(values, defaults.outputs.get("slice_list"));
        assertNull(defaults.triggeredOutput);
    }

    @Test
    void embeddedFilterSubflowsComposeWithoutBlockingTheHandler() {
        CompletableFuture<Boolean> first = new CompletableFuture<>();
        CompletableFuture<Boolean> second = new CompletableFuture<>();
        AsyncSubFlowContext context = new AsyncSubFlowContext(Map.of("list", List.of("first", "second")), Map.of(0, first, 1, second));
        HandlerRegistry registry = new HandlerRegistry();
        new GenericListHandler().registerTo(registry);
        FlowNode node = new FlowNode("list.filter", 0, 0, Map.of());
        node.setHandlerConfig(Map.of("operation", "filter"));

        registry.getHandler("GenericListHandler").execute(context, node);

        assertFalse(context.awaited.isDone());
        assertFalse(context.outputs.containsKey("filtered_list"));
        first.complete(true);
        assertFalse(context.awaited.isDone());
        second.complete(false);
        context.awaited.join();
        assertEquals(List.of("first"), context.outputs.get("filtered_list"));
    }

    private TestFlowContext execute(String operation, Map<String, Object> inputs) {
        TestFlowContext context = new TestFlowContext(inputs);
        execute(operation, context);
        return context;
    }

    private void execute(String operation, TestFlowContext context) {
        HandlerRegistry registry = new HandlerRegistry();
        new GenericListHandler().registerTo(registry);
        FlowNode node = new FlowNode("list." + operation, 0, 0, Map.of());
        node.setHandlerConfig(Map.of("operation", operation));
        registry.getHandler("GenericListHandler").execute(context, node);
    }

    private record Ranked(String id, int rank) implements Comparable<Ranked> {
        @Override
        public int compareTo(Ranked other) {
            return Integer.compare(rank, other.rank);
        }
    }

    private static class TestFlowContext extends FlowContext {
        private final Map<String, Object> inputs;
        protected final Map<String, Object> outputs = new HashMap<>();
        private String triggeredOutput;

        private TestFlowContext(Map<String, Object> inputs) {
            super(null, null, null);
            this.inputs = inputs;
        }

        @Override
        public <T> T getInputValue(FlowNode node, String pinName, Class<T> type, T defaultValue) {
            Object value = inputs.get(pinName);
            if (value == null) return defaultValue;
            return type != null ? type.cast(value) : (T) value;
        }

        @Override
        public FlowGraph extractSubGraph(FlowNode node, String pinName) {
            return null;
        }

        @Override
        public void setOutput(FlowNode node, String pinName, Object value) {
            outputs.put(pinName, value);
        }

        @Override
        public void triggerOutput(String pinName) {
            triggeredOutput = pinName;
        }
    }

    private static final class AsyncSubFlowContext extends TestFlowContext {
        private final FlowGraph subGraph = new FlowGraph();
        private final Map<Integer, CompletableFuture<Boolean>> results;
        private CompletableFuture<Void> awaited = CompletableFuture.completedFuture(null);

        private AsyncSubFlowContext(Map<String, Object> inputs, Map<Integer, CompletableFuture<Boolean>> results) {
            super(inputs);
            this.results = results;
            subGraph.getNodes().put("terminal", new FlowNode("test", 0, 0, Map.of()));
        }

        @Override
        public FlowGraph extractSubGraph(FlowNode node, String pinName) {
            return subGraph;
        }

        @Override
        public CompletableFuture<Boolean> executeSubFlowBooleanAsync(FlowGraph graph, FlowNode node, Map<String, Object> extraInputs) {
            return results.get(((Number) extraInputs.get("index")).intValue());
        }

        @Override
        public CompletableFuture<Void> awaitBeforeContinuation(CompletableFuture<?> operation) {
            awaited = operation.thenApply(ignored -> null);
            return awaited;
        }
    }
}

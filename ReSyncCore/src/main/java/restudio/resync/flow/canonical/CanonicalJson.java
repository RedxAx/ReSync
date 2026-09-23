package restudio.resync.flow.canonical;

import restudio.resync.contract.canonical.CanonicalArrays;
import restudio.resync.contract.canonical.CanonicalDigests;
import restudio.resync.contract.canonical.CanonicalText;
import restudio.resync.contract.canonical.JsonValue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class CanonicalJson {
    public static final String HASH_NAMESPACE = "restudio.resync.contract.canonical/1";
    public static final String CATALOG_HASH_DOMAIN = "catalog";
    public static final String GRAPH_HASH_DOMAIN = "graph";
    public static final String PLAN_HASH_DOMAIN = "plan";
    public static final String RESOURCE_HASH_DOMAIN = "resource";
    public static final String MIGRATION_HASH_DOMAIN = "migration";
    private static final byte[] HASH_NAMESPACE_BYTES = HASH_NAMESPACE.getBytes(StandardCharsets.US_ASCII);
    private static final Pattern HASH_SEMANTIC_DOMAIN = Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");
    private static final int MAX_HASH_SEMANTIC_DOMAIN_CODE_POINTS = 64;

    private CanonicalJson() {
    }

    public static String canonicalize(Object value) {
        return canonicalize(value, CanonicalLimits.standard());
    }

    public static String canonicalize(Object value, CanonicalLimits limits) {
        return new Writer(Objects.requireNonNull(limits, "Limits are required"), 0).write(value);
    }

    public static CanonicalFragment prepare(Object value, CanonicalLimits limits) {
        CanonicalLimits checkedLimits = Objects.requireNonNull(limits, "Limits are required");
        Writer writer = new Writer(checkedLimits, 0);
        String content = writer.write(value);
        return new CanonicalFragment(checkedLimits, content, writer.outputBytes, writer.tokenCounter.value, writer.maximumDepth);
    }

    public static CanonicalFragment adopt(String content, CanonicalLimits limits, long tokens, int maximumDepth) {
        CanonicalLimits checkedLimits = Objects.requireNonNull(limits, "Limits are required");
        if (tokens < 0 || maximumDepth < 0) {
            throw new IllegalArgumentException("Canonical fragment accounting must not be negative");
        }
        byte[] encoded = utf8(Objects.requireNonNull(content, "Canonical content is required"), checkedLimits.canonicalBytes());
        return new CanonicalFragment(checkedLimits, content, encoded.length, tokens, maximumDepth);
    }

    public static byte[] canonicalBytes(Object value) {
        return canonicalBytes(value, CanonicalLimits.standard());
    }

    public static byte[] canonicalBytes(Object value, CanonicalLimits limits) {
        return utf8(canonicalize(value, limits), limits.canonicalBytes());
    }

    public static String sha256(String semanticDomain, Object value) {
        return sha256(semanticDomain, value, CanonicalLimits.standard());
    }

    public static String sha256(String semanticDomain, Object value, CanonicalLimits limits) {
        return sha256Canonical(semanticDomain, canonicalBytes(value, limits), limits);
    }

    public static String sha256Canonical(String semanticDomain, byte[] canonicalBytes) {
        return sha256Canonical(semanticDomain, canonicalBytes, CanonicalLimits.standard());
    }

    public static String sha256Canonical(String semanticDomain, byte[] canonicalBytes, CanonicalLimits limits) {
        byte[] semanticDomainBytes = semanticDomainBytes(semanticDomain);
        return hash(semanticDomainBytes, canonicalBytes, limits);
    }

    public static String genericCanonicalContentHash(Object value) {
        return genericCanonicalContentHash(value, CanonicalLimits.standard());
    }

    public static String genericCanonicalContentHash(Object value, CanonicalLimits limits) {
        return genericCanonicalContentHash(canonicalBytes(value, limits), limits);
    }

    public static String genericCanonicalContentHash(byte[] canonicalBytes) {
        return genericCanonicalContentHash(canonicalBytes, CanonicalLimits.standard());
    }

    public static String genericCanonicalContentHash(byte[] canonicalBytes, CanonicalLimits limits) {
        Objects.requireNonNull(canonicalBytes, "Canonical bytes are required");
        Objects.requireNonNull(limits, "Limits are required");
        if (canonicalBytes.length > limits.canonicalBytes()) {
            throw new IllegalArgumentException("Canonical JSON exceeds " + limits.canonicalBytes() + " bytes");
        }
        return hash(null, canonicalBytes, limits);
    }

    private static String hash(byte[] semanticDomainBytes, byte[] canonicalBytes, CanonicalLimits limits) {
        Objects.requireNonNull(canonicalBytes, "Canonical bytes are required");
        Objects.requireNonNull(limits, "Limits are required");
        if (canonicalBytes.length > limits.canonicalBytes()) {
            throw new IllegalArgumentException("Canonical JSON exceeds " + limits.canonicalBytes() + " bytes");
        }
        try {
            if (semanticDomainBytes == null) {
                return CanonicalDigests.hex(CanonicalDigests.sha256(HASH_NAMESPACE_BYTES, new byte[]{0}, canonicalBytes));
            }
            return CanonicalDigests.hex(CanonicalDigests.sha256(
                HASH_NAMESPACE_BYTES, new byte[]{0}, semanticDomainBytes, new byte[]{0}, canonicalBytes));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static byte[] semanticDomainBytes(String semanticDomain) {
        Objects.requireNonNull(semanticDomain, "Semantic hash domain is required");
        if (semanticDomain.codePointCount(0, semanticDomain.length()) > MAX_HASH_SEMANTIC_DOMAIN_CODE_POINTS || !HASH_SEMANTIC_DOMAIN.matcher(semanticDomain).matches()) {
            throw new IllegalArgumentException("Invalid semantic hash domain: " + semanticDomain);
        }
        return semanticDomain.getBytes(StandardCharsets.US_ASCII);
    }

    public static Object parse(byte[] input) {
        return parse(input, CanonicalLimits.standard());
    }

    public static Object parse(byte[] input, CanonicalLimits limits) {
        return mutableJava(parseTreeResult(input, limits, false).value());
    }

    public static Object parseOpaque(byte[] input) {
        return parseOpaque(input, CanonicalLimits.standard());
    }

    public static Object parseOpaque(byte[] input, CanonicalLimits limits) {
        return mutableJava(parseTreeResult(input, limits, true).value());
    }

    public static Object parseOpaque(String input) {
        return parseOpaque(input, CanonicalLimits.standard());
    }

    public static Object parseOpaque(String input, CanonicalLimits limits) {
        Objects.requireNonNull(input, "Input is required");
        CanonicalLimits checkedLimits = Objects.requireNonNull(limits, "Limits are required");
        return parseOpaque(utf8(input, checkedLimits.inputBytes()), checkedLimits);
    }

    public static Object parse(String input) {
        return parse(input, CanonicalLimits.standard());
    }

    public static Object parse(String input, CanonicalLimits limits) {
        Objects.requireNonNull(input, "Input is required");
        return parse(utf8(input, Objects.requireNonNull(limits, "Limits are required").inputBytes()), limits);
    }

    public static String canonicalizeJson(byte[] input) {
        return canonicalizeJson(input, CanonicalLimits.standard());
    }

    public static String canonicalizeJson(byte[] input, CanonicalLimits limits) {
        ParsedTree parsed = parseTreeResult(input, limits);
        return parsed.exactCanonical() ? parsed.canonicalText(parsed.value()) : canonicalize(parsed.value(), limits);
    }

    public static String canonicalDecimal(BigDecimal value) {
        return canonicalDecimal(value, CanonicalLimits.standard());
    }

    public static String canonicalDecimal(BigDecimal value, CanonicalLimits limits) {
        Objects.requireNonNull(value, "Decimal value is required");
        Objects.requireNonNull(limits, "Limits are required");
        return new Writer(limits, 0).writeNumberValue(value, true);
    }

    public static BigDecimal parseDecimal(String input) {
        return parseDecimal(input, CanonicalLimits.standard());
    }

    public static BigDecimal parseDecimal(String input, CanonicalLimits limits) {
        Object value = parse(input, limits);
        if (!(value instanceof BigDecimal decimal)) {
            throw new IllegalArgumentException("A JSON number is required");
        }
        canonicalDecimal(decimal, limits);
        return decimal.signum() == 0 ? BigDecimal.ZERO : decimal.stripTrailingZeros();
    }

    public static String requireNfc(String value) {
        return requireNfc(value, CanonicalLimits.standard());
    }

    public static String requireNfc(String value, CanonicalLimits limits) {
        Objects.requireNonNull(value, "Text is required");
        CanonicalLimits checkedLimits = Objects.requireNonNull(limits, "Limits are required");
        validateUnicode(value, checkedLimits.stringCodePoints(), "Text");
        if (!CanonicalText.isNfc(value)) {
            throw new IllegalArgumentException("Text must be NFC");
        }
        return value;
    }

    public static JsonValue parseTree(byte[] input) {
        return parseTree(input, CanonicalLimits.standard());
    }

    public static JsonValue parseTree(byte[] input, CanonicalLimits limits) {
        return parseTreeResult(input, limits).value();
    }

    public static JsonValue parseTree(String input) {
        return parseTree(input, CanonicalLimits.standard());
    }

    public static JsonValue parseTree(String input, CanonicalLimits limits) {
        return parseTreeResult(input, limits).value();
    }

    public static ParsedTree parseTreeResult(byte[] input) {
        return parseTreeResult(input, CanonicalLimits.standard());
    }

    public static ParsedTree parseTreeResult(byte[] input, CanonicalLimits limits) {
        return parseTreeResult(input, limits, false);
    }

    public static ParsedTree parseOpaqueTreeResult(byte[] input, CanonicalLimits limits) {
        return parseTreeResult(input, limits, true);
    }

    public static ParsedTree parseOpaqueTreeResult(String input, CanonicalLimits limits) {
        Objects.requireNonNull(input, "Input is required");
        CanonicalLimits checkedLimits = Objects.requireNonNull(limits, "Limits are required");
        return parseTreeResult(utf8(input, checkedLimits.inputBytes()), checkedLimits, true);
    }

    public static ParsedTree parseTreeResult(String input) {
        return parseTreeResult(input, CanonicalLimits.standard());
    }

    public static ParsedTree parseTreeResult(String input, CanonicalLimits limits) {
        Objects.requireNonNull(input, "Input is required");
        CanonicalLimits checkedLimits = Objects.requireNonNull(limits, "Limits are required");
        return parseTreeResult(utf8(input, checkedLimits.inputBytes()), checkedLimits);
    }

    private static ParsedTree parseTreeResult(byte[] input, CanonicalLimits limits, boolean enforceOpaque) {
        return new Parser(input, limits, enforceOpaque).parse();
    }

    private static Object mutableJava(JsonValue value) {
        if (value instanceof JsonValue.JsonNull) {
            return null;
        }
        if (value instanceof JsonValue.JsonBoolean booleanValue) {
            return booleanValue.value();
        }
        if (value instanceof JsonValue.JsonNumber number) {
            return number.value();
        }
        if (value instanceof JsonValue.JsonString string) {
            return string.value();
        }
        if (value instanceof JsonValue.JsonArray array) {
            List<Object> values = new ArrayList<>(array.values().size());
            array.values().forEach(member -> values.add(mutableJava(member)));
            return values;
        }
        JsonValue.JsonObject object = (JsonValue.JsonObject) value;
        Map<String, Object> values = new LinkedHashMap<>();
        object.values().forEach((key, member) -> values.put(key, mutableJava(member)));
        return values;
    }

    private static byte[] utf8(String value, int maximumBytes) {
        String checkedValue = Objects.requireNonNull(value, "Text is required");
        return utf8(checkedValue, 0, checkedValue.length(), maximumBytes);
    }

    private static byte[] utf8(String value, int start, int end, int maximumBytes) {
        Objects.requireNonNull(value, "Text is required");
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            ByteBuffer encoded = encoder.encode(CharBuffer.wrap(value.substring(start, end)));
            if (encoded.remaining() > maximumBytes) {
                throw new IllegalArgumentException("UTF-8 input exceeds " + maximumBytes + " bytes");
            }
            byte[] result = new byte[encoded.remaining()];
            encoded.get(result);
            return result;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Invalid UTF-8 text", exception);
        }
    }

    private static String decodeUtf8(byte[] input, CanonicalLimits limits) {
        Objects.requireNonNull(input, "Input is required");
        if (input.length > limits.inputBytes()) {
            throw new IllegalArgumentException("Input exceeds " + limits.inputBytes() + " bytes");
        }
        if (limits.rejectBom() && input.length >= 3 && (input[0] & 0xff) == 0xef && (input[1] & 0xff) == 0xbb && (input[2] & 0xff) == 0xbf) {
            throw new IllegalArgumentException("UTF-8 BOM is not allowed");
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(input)).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Invalid UTF-8 input", exception);
        }
    }

    private static void validateUnicode(String value, int maximumCodePoints, String label) {
        int codePoints = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(label + " contains an invalid Unicode surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(label + " contains an invalid Unicode surrogate");
            }
            codePoints++;
            if (codePoints > maximumCodePoints) {
                throw new IllegalArgumentException(label + " exceeds " + maximumCodePoints + " code points");
            }
        }
    }

    public static int compareCodePoints(String left, String right) {
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            int leftPoint = left.codePointAt(leftIndex);
            int rightPoint = right.codePointAt(rightIndex);
            if (leftPoint != rightPoint) {
                return Integer.compare(leftPoint, rightPoint);
            }
            leftIndex += Character.charCount(leftPoint);
            rightIndex += Character.charCount(rightPoint);
        }
        return Integer.compare(left.length(), right.length());
    }

    public static final class CanonicalFragment {
        private final CanonicalLimits limits;
        private final String content;
        private final int bytes;
        private final long tokens;
        private final int maximumDepth;

        private CanonicalFragment(CanonicalLimits limits, String content, int bytes, long tokens, int maximumDepth) {
            this.limits = limits;
            this.content = content;
            this.bytes = bytes;
            this.tokens = tokens;
            this.maximumDepth = maximumDepth;
        }

        public String content() {
            return content;
        }

        public CanonicalLimits limits() {
            return limits;
        }

        public int bytes() {
            return bytes;
        }

        public long tokens() {
            return tokens;
        }

        public int maximumDepth() {
            return maximumDepth;
        }
    }

    public static final class ParsedTree {
        private final CanonicalLimits limits;
        private final String source;
        private final JsonValue value;
        private final boolean exactCanonical;
        private final IdentityHashMap<JsonValue, SourceRange> ranges;

        private ParsedTree(CanonicalLimits limits, String source, JsonValue value, boolean exactCanonical,
                           IdentityHashMap<JsonValue, SourceRange> ranges) {
            this.limits = limits;
            this.source = source;
            this.value = value;
            this.exactCanonical = exactCanonical;
            this.ranges = ranges;
        }

        public JsonValue value() {
            return value;
        }

        public boolean exactCanonical() {
            return exactCanonical;
        }

        public String canonicalText(JsonValue subtree) {
            SourceRange range = validatedRange(subtree);
            return source.substring(range.start(), range.end());
        }

        public byte[] canonicalBytes(JsonValue subtree) {
            SourceRange range = validatedRange(subtree);
            return utf8(source, range.start(), range.end(), limits.canonicalBytes());
        }

        private SourceRange validatedRange(JsonValue subtree) {
            Objects.requireNonNull(subtree, "JSON subtree is required");
            if (!exactCanonical) {
                throw new IllegalStateException("Parsed input is not exact canonical JSON");
            }
            SourceRange range = ranges.get(subtree);
            if (range == null) {
                throw new IllegalArgumentException("JSON value does not belong to this parsed input");
            }
            return range;
        }
    }

    private static final class Writer {
        private final CanonicalLimits limits;
        private final StringBuilder output = new StringBuilder();
        private final IdentityHashMap<Object, Boolean> activeContainers;
        private final TokenCounter tokenCounter;
        private int outputBytes;
        private int maximumDepth;

        private Writer(CanonicalLimits limits, int initialDepth) {
            this(limits, initialDepth, new IdentityHashMap<>(), new TokenCounter());
        }

        private Writer(CanonicalLimits limits, int initialDepth, IdentityHashMap<Object, Boolean> activeContainers) {
            this(limits, initialDepth, activeContainers, new TokenCounter());
        }

        private Writer(CanonicalLimits limits, int initialDepth, IdentityHashMap<Object, Boolean> activeContainers, TokenCounter tokenCounter) {
            this.limits = limits;
            this.depth = initialDepth;
            this.activeContainers = activeContainers;
            this.tokenCounter = tokenCounter;
        }

        private int depth;

        private String write(Object value) {
            writeValue(value);
            return output.toString();
        }

        private String writeNumberValue(BigDecimal value, boolean decimal) {
            countToken();
            appendNumber(number(value, decimal));
            return output.toString();
        }

        private void writeValue(Object value) {
            if (value instanceof CanonicalFragment fragment) {
                appendFragment(fragment);
            } else if (value instanceof JsonValue jsonValue) {
                writeJsonValue(jsonValue);
            } else if (value == null) {
                countToken();
                appendAscii("null");
            } else if (value instanceof String string) {
                writeString(string);
            } else if (value instanceof Character character) {
                writeString(character.toString());
            } else if (value instanceof UUID uuid) {
                writeString(uuid.toString());
            } else if (value instanceof Boolean booleanValue) {
                countToken();
                appendAscii(booleanValue ? "true" : "false");
            } else if (value instanceof Number number) {
                countToken();
                appendNumber(number(number, false));
            } else if (value instanceof Map<?, ?> map) {
                writeMap(map);
            } else if (value instanceof Set<?> set) {
                writeSet(set);
            } else if (value instanceof Iterable<?> iterable) {
                writeIterable(iterable);
            } else {
                Object[] array = CanonicalArrays.boxed(value);
                if (array != null) {
                    writeIterable(List.of(array));
                } else {
                    throw new IllegalArgumentException("Unsupported canonical value");
                }
            }
        }

        private void writeJsonValue(JsonValue value) {
            if (value instanceof JsonValue.JsonNull) {
                countToken();
                appendAscii("null");
            } else if (value instanceof JsonValue.JsonBoolean booleanValue) {
                countToken();
                appendAscii(booleanValue.value() ? "true" : "false");
            } else if (value instanceof JsonValue.JsonNumber numberValue) {
                countToken();
                appendNumber(number(numberValue.value(), false));
            } else if (value instanceof JsonValue.JsonString string) {
                writeString(string.value());
            } else if (value instanceof JsonValue.JsonArray array) {
                writeIterable(array.values());
            } else {
                writeMap(((JsonValue.JsonObject) value).values());
            }
        }

        private void writeMap(Map<?, ?> map) {
            enterContainer();
            enterReference(map);
            try {
                List<KeyValue> entries = new ArrayList<>(map.size());
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new IllegalArgumentException("Canonical object keys must be strings");
                    }
                    validateUnicode(key, limits.stringCodePoints(), "Object key");
                    entries.add(new KeyValue(key, entry.getValue()));
                }
                entries.sort((left, right) -> compareCodePoints(left.key(), right.key()));
                for (int index = 1; index < entries.size(); index++) {
                    if (entries.get(index - 1).key().equals(entries.get(index).key())) {
                        throw new IllegalArgumentException("Map contains duplicate canonical keys");
                    }
                }
                countToken();
                appendAscii("{");
                for (int index = 0; index < entries.size(); index++) {
                    if (index > 0) {
                        appendAscii(",");
                    }
                    writeString(entries.get(index).key());
                    appendAscii(":");
                    writeValue(entries.get(index).value());
                }
                countToken();
                appendAscii("}");
            } finally {
                leaveReference(map);
                leaveContainer();
            }
        }

        private void writeSet(Set<?> set) {
            enterContainer();
            enterReference(set);
            try {
                List<String> values = new ArrayList<>(set.size());
                for (Object value : set) {
                    Writer writer = new Writer(limits, depth, activeContainers, tokenCounter);
                    values.add(writer.write(value));
                    maximumDepth = Math.max(maximumDepth, writer.maximumDepth);
                }
                values.sort(CanonicalJson::compareCodePoints);
                for (int index = 1; index < values.size(); index++) {
                    if (values.get(index - 1).equals(values.get(index))) {
                        throw new IllegalArgumentException("Set contains canonically equal values");
                    }
                }
                countToken();
                appendAscii("[");
                for (int index = 0; index < values.size(); index++) {
                    if (index > 0) {
                        appendAscii(",");
                    }
                    appendRaw(values.get(index));
                }
                countToken();
                appendAscii("]");
            } finally {
                leaveReference(set);
                leaveContainer();
            }
        }

        private void writeIterable(Iterable<?> iterable) {
            enterContainer();
            enterReference(iterable);
            try {
                countToken();
                appendAscii("[");
                int index = 0;
                for (Object value : iterable) {
                    if (index++ > 0) {
                        appendAscii(",");
                    }
                    writeValue(value);
                }
                countToken();
                appendAscii("]");
            } finally {
                leaveReference(iterable);
                leaveContainer();
            }
        }

        private void enterReference(Object value) {
            if (activeContainers.put(value, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("Canonical value graph contains a cycle");
            }
        }

        private void leaveReference(Object value) {
            activeContainers.remove(value);
        }

        private void writeString(String value) {
            countToken();
            validateUnicode(value, limits.stringCodePoints(), "String");
            appendAscii("\"");
            for (int index = 0; index < value.length(); index++) {
                char current = value.charAt(index);
                switch (current) {
                    case '"' -> appendAscii("\\\"");
                    case '\\' -> appendAscii("\\\\");
                    case '\b' -> appendAscii("\\b");
                    case '\f' -> appendAscii("\\f");
                    case '\n' -> appendAscii("\\n");
                    case '\r' -> appendAscii("\\r");
                    case '\t' -> appendAscii("\\t");
                    default -> {
                        if (current < 0x20) {
                            appendAscii("\\u" + String.format(Locale.ROOT, "%04x", (int) current));
                        } else if (Character.isHighSurrogate(current)) {
                            appendCodePoint(Character.toCodePoint(current, value.charAt(++index)));
                        } else {
                            appendCodePoint(current);
                        }
                    }
                }
            }
            appendAscii("\"");
        }

        private void appendNumber(String value) {
            appendRaw(value);
        }

        private void enterContainer() {
            if (++depth > limits.depth()) {
                throw new IllegalArgumentException("Canonical JSON exceeds depth " + limits.depth());
            }
            maximumDepth = Math.max(maximumDepth, depth);
        }

        private void leaveContainer() {
            depth--;
        }

        private void appendAscii(String value) {
            output.append(value);
            outputBytes = Math.addExact(outputBytes, value.length());
            ensureOutputLimit();
        }

        private void appendRaw(String value) {
            output.append(value);
            outputBytes = Math.addExact(outputBytes, value.getBytes(StandardCharsets.UTF_8).length);
            ensureOutputLimit();
        }

        private void appendFragment(CanonicalFragment fragment) {
            if (!limits.equals(fragment.limits)) {
                throw new IllegalArgumentException("Canonical fragment limits do not match the writer limits");
            }
            if ((long) depth + fragment.maximumDepth > limits.depth()) {
                throw new IllegalArgumentException("Canonical JSON exceeds depth " + limits.depth());
            }
            tokenCounter.value = Math.addExact(tokenCounter.value, fragment.tokens);
            if (tokenCounter.value > limits.tokens()) {
                throw new IllegalArgumentException("Canonical JSON exceeds " + limits.tokens() + " tokens");
            }
            maximumDepth = Math.max(maximumDepth, Math.addExact(depth, fragment.maximumDepth));
            output.append(fragment.content);
            outputBytes = Math.addExact(outputBytes, fragment.bytes);
            ensureOutputLimit();
        }

        private void ensureOutputLimit() {
            if (outputBytes > limits.canonicalBytes()) {
                throw new IllegalArgumentException("Canonical JSON exceeds " + limits.canonicalBytes() + " bytes");
            }
        }

        private void appendCodePoint(int codePoint) {
            output.appendCodePoint(codePoint);
            int bytes = codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
            outputBytes = Math.addExact(outputBytes, bytes);
            if (outputBytes > limits.canonicalBytes()) {
                throw new IllegalArgumentException("Canonical JSON exceeds " + limits.canonicalBytes() + " bytes");
            }
        }

        private String number(Number value, boolean decimal) {
            BigDecimal decimalValue;
            if (value instanceof BigDecimal bigDecimal) {
                decimalValue = bigDecimal;
            } else if (value instanceof BigInteger bigInteger) {
                decimalValue = new BigDecimal(bigInteger);
            } else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                decimalValue = BigDecimal.valueOf(value.longValue());
            } else if (value instanceof Float || value instanceof Double) {
                double doubleValue = value.doubleValue();
                if (!Double.isFinite(doubleValue)) {
                    throw new IllegalArgumentException("Canonical numbers must be finite");
                }
                decimalValue = BigDecimal.valueOf(doubleValue);
            } else {
                try {
                    decimalValue = new BigDecimal(value.toString());
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("Unsupported number", exception);
                }
            }
            BigDecimal normalized = decimalValue.signum() == 0 ? BigDecimal.ZERO : decimalValue.stripTrailingZeros();
            if (normalized.precision() > (decimal ? limits.decimalPrecisionDigits() : limits.numericPrecisionDigits())) {
                throw new IllegalArgumentException("Numeric precision exceeds the configured limit");
            }
            if (decimal && normalized.scale() > limits.decimalScale()) {
                throw new IllegalArgumentException("Decimal scale exceeds " + limits.decimalScale());
            }
            long expansion = plainLength(normalized);
            if (expansion > limits.canonicalNumericExpansionCodePoints()) {
                throw new IllegalArgumentException("Canonical numeric expansion exceeds " + limits.canonicalNumericExpansionCodePoints() + " code points");
            }
            String plain = normalized.toPlainString();
            return plain;
        }

        private long plainLength(BigDecimal value) {
            if (value.signum() == 0) {
                return 1;
            }
            long precision = value.precision();
            long scale = (long) value.scale();
            long length = scale <= 0 ? precision - scale : precision > scale ? precision + 1 : 2 + scale;
            return value.signum() < 0 ? length + 1 : length;
        }

        private void countToken() {
            if (++tokenCounter.value > limits.tokens()) {
                throw new IllegalArgumentException("Canonical JSON exceeds " + limits.tokens() + " tokens");
            }
        }
    }

    private static final class TokenCounter {
        private long value;
    }

    private static final class Parser {
        private final CanonicalLimits limits;
        private final String input;
        private final boolean enforceOpaque;
        private final IdentityHashMap<JsonValue, SourceRange> ranges = new IdentityHashMap<>();
        private int position;
        private long tokens;
        private boolean exactCanonical = true;

        private Parser(byte[] bytes, CanonicalLimits limits, boolean enforceOpaque) {
            this.limits = Objects.requireNonNull(limits, "Limits are required");
            this.input = decodeUtf8(bytes, limits);
            this.enforceOpaque = enforceOpaque;
        }

        private ParsedTree parse() {
            skipWhitespace();
            if (position == input.length()) {
                throw error("JSON value is required");
            }
            ParsedNode result = parseValue(0);
            skipWhitespace();
            if (position != input.length()) {
                throw error("Trailing JSON content is not allowed");
            }
            if (enforceOpaque && result.canonicalBytes() > limits.opaqueSubtreeBytes()) {
                throw error("Opaque JSON exceeds " + limits.opaqueSubtreeBytes() + " bytes");
            }
            return new ParsedTree(limits, input, result.value(), exactCanonical, ranges);
        }

        private ParsedNode parseValue(int parentDepth) {
            if (position == input.length()) {
                throw error("JSON value is required");
            }
            int start = position;
            ParsedNode result = switch (input.charAt(position)) {
                case '{' -> parseObject(parentDepth);
                case '[' -> parseArray(parentDepth);
                case '"' -> stringNode(parseStringToken());
                case 't' -> parseLiteral("true", new JsonValue.JsonBoolean(true));
                case 'f' -> parseLiteral("false", new JsonValue.JsonBoolean(false));
                case 'n' -> parseLiteral("null", new JsonValue.JsonNull());
                default -> parseNumber();
            };
            if (exactCanonical) {
                ranges.put(result.value(), new SourceRange(start, position));
            }
            return result;
        }

        private ParsedNode parseObject(int parentDepth) {
            enter(parentDepth);
            countToken();
            position++;
            LinkedHashMap<String, JsonValue> result = new LinkedHashMap<>();
            int canonicalBytes = checkedBytes(2);
            String previousKey = null;
            skipWhitespace();
            if (consume('}')) {
                countToken();
                return new ParsedNode(new JsonValue.JsonObject(result), canonicalBytes);
            }
            while (true) {
                skipWhitespace();
                if (position == input.length() || input.charAt(position) != '"') {
                    throw error("Object key must be a string");
                }
                ParsedString keyToken = parseStringToken();
                String key = keyToken.value();
                if (result.containsKey(key)) {
                    throw error("Duplicate object key: " + key);
                }
                if (previousKey != null && compareCodePoints(previousKey, key) >= 0) {
                    markNoncanonical();
                }
                previousKey = key;
                skipWhitespace();
                require(':');
                skipWhitespace();
                ParsedNode value = parseValue(parentDepth + 1);
                result.put(key, value.value());
                if (result.size() > 1) {
                    canonicalBytes = addBytes(canonicalBytes, 1);
                }
                canonicalBytes = addBytes(canonicalBytes, keyToken.canonicalBytes(), 1, value.canonicalBytes());
                skipWhitespace();
                if (consume(',')) {
                    continue;
                }
                if (consume('}')) {
                    countToken();
                    return new ParsedNode(new JsonValue.JsonObject(result), canonicalBytes);
                }
                throw error("Object must end with a closing brace");
            }
        }

        private ParsedNode parseArray(int parentDepth) {
            enter(parentDepth);
            countToken();
            position++;
            ArrayList<JsonValue> result = new ArrayList<>();
            int canonicalBytes = checkedBytes(2);
            skipWhitespace();
            if (consume(']')) {
                countToken();
                return new ParsedNode(new JsonValue.JsonArray(result), canonicalBytes);
            }
            while (true) {
                skipWhitespace();
                ParsedNode value = parseValue(parentDepth + 1);
                if (!result.isEmpty()) {
                    canonicalBytes = addBytes(canonicalBytes, 1);
                }
                result.add(value.value());
                canonicalBytes = addBytes(canonicalBytes, value.canonicalBytes());
                skipWhitespace();
                if (consume(',')) {
                    continue;
                }
                if (consume(']')) {
                    countToken();
                    return new ParsedNode(new JsonValue.JsonArray(result), canonicalBytes);
                }
                throw error("Array must end with a closing bracket");
            }
        }

        private ParsedString parseStringToken() {
            countToken();
            require('"');
            int start = position;
            while (position < input.length()) {
                char current = input.charAt(position);
                if (current == '"') {
                    String value = input.substring(start, position++);
                    validateUnicode(value, limits.stringCodePoints(), "String");
                    return new ParsedString(value, canonicalStringBytes(value));
                }
                if (current == '\\' || current < 0x20) {
                    break;
                }
                position++;
            }
            position = start;
            StringBuilder result = new StringBuilder();
            while (position < input.length()) {
                char current = input.charAt(position++);
                if (current == '"') {
                    String value = result.toString();
                    validateUnicode(value, limits.stringCodePoints(), "String");
                    return new ParsedString(value, canonicalStringBytes(value));
                }
                if (current == '\\') {
                    if (position == input.length()) {
                        throw error("Incomplete JSON escape");
                    }
                    char escaped = input.charAt(position++);
                    switch (escaped) {
                        case '"', '\\' -> result.append(escaped);
                        case '/' -> {
                            markNoncanonical();
                            result.append('/');
                        }
                        case 'b' -> result.append('\b');
                        case 'f' -> result.append('\f');
                        case 'n' -> result.append('\n');
                        case 'r' -> result.append('\r');
                        case 't' -> result.append('\t');
                        case 'u' -> {
                            int digitsStart = position;
                            char decoded = parseUnicodeEscape();
                            if (!canonicalUnicodeEscape(decoded, digitsStart)) {
                                markNoncanonical();
                            }
                            result.append(decoded);
                        }
                        default -> throw error("Invalid JSON escape");
                    }
                } else {
                    if (current < 0x20) {
                        throw error("Unescaped JSON control character");
                    }
                    result.append(current);
                }
            }
            throw error("Unterminated JSON string");
        }

        private char parseUnicodeEscape() {
            if (position + 4 > input.length()) {
                throw error("Incomplete Unicode escape");
            }
            int value = 0;
            for (int index = 0; index < 4; index++) {
                int digit = Character.digit(input.charAt(position++), 16);
                if (digit < 0) {
                    throw error("Invalid Unicode escape");
                }
                value = value * 16 + digit;
            }
            return (char) value;
        }

        private ParsedNode parseLiteral(String literal, JsonValue value) {
            if (!input.startsWith(literal, position)) {
                throw error("Invalid JSON literal");
            }
            countToken();
            position += literal.length();
            return new ParsedNode(value, checkedBytes(literal.length()));
        }

        private ParsedNode parseNumber() {
            int start = position;
            if (consume('-')) {
                if (position == input.length()) {
                    throw error("Invalid JSON number");
                }
            }
            if (consume('0')) {
                if (position < input.length() && Character.isDigit(input.charAt(position))) {
                    throw error("Leading zero is not allowed");
                }
            } else {
                if (position == input.length() || input.charAt(position) < '1' || input.charAt(position) > '9') {
                    throw error("Invalid JSON value");
                }
                while (position < input.length() && Character.isDigit(input.charAt(position))) {
                    position++;
                }
            }
            if (consume('.')) {
                int fractionStart = position;
                while (position < input.length() && Character.isDigit(input.charAt(position))) {
                    position++;
                }
                if (fractionStart == position) {
                    throw error("JSON number fraction is incomplete");
                }
            }
            if (position < input.length() && (input.charAt(position) == 'e' || input.charAt(position) == 'E')) {
                position++;
                consume('+');
                consume('-');
                int exponentStart = position;
                while (position < input.length() && Character.isDigit(input.charAt(position))) {
                    position++;
                }
                if (exponentStart == position) {
                    throw error("JSON number exponent is incomplete");
                }
            }
            String token = input.substring(start, position);
            int tokenCodePoints = token.codePointCount(0, token.length());
            if (tokenCodePoints > limits.canonicalNumericExpansionCodePoints()) {
                throw error("Numeric token exceeds " + limits.canonicalNumericExpansionCodePoints() + " code points");
            }
            countToken();
            try {
                BigDecimal result = new BigDecimal(token);
                BigDecimal normalized = result.signum() == 0 ? BigDecimal.ZERO : result.stripTrailingZeros();
                if (normalized.precision() > limits.numericPrecisionDigits()) {
                    throw error("Numeric precision exceeds " + limits.numericPrecisionDigits() + " digits");
                }
                String plain = normalized.toPlainString();
                if (tokenCodePoints > limits.numericTokenCodePoints() && !token.equals(plain)) {
                    throw error("Noncanonical numeric token exceeds " + limits.numericTokenCodePoints() + " code points");
                }
                if (plainLength(normalized) > limits.canonicalNumericExpansionCodePoints()) {
                    throw error("Canonical numeric expansion exceeds " + limits.canonicalNumericExpansionCodePoints() + " code points");
                }
                if (!token.equals(plain)) {
                    markNoncanonical();
                }
                return new ParsedNode(new JsonValue.JsonNumber(normalized), checkedBytes(plain.length()));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid JSON number", exception);
            }
        }

        private ParsedNode stringNode(ParsedString value) {
            return new ParsedNode(new JsonValue.JsonString(value.value()), value.canonicalBytes());
        }

        private boolean canonicalUnicodeEscape(char value, int digitsStart) {
            if (value >= 0x20 || value == '\b' || value == '\f' || value == '\n' || value == '\r' || value == '\t') {
                return false;
            }
            return input.charAt(digitsStart) == '0'
                && input.charAt(digitsStart + 1) == '0'
                && input.charAt(digitsStart + 2) == hexDigit(value >>> 4)
                && input.charAt(digitsStart + 3) == hexDigit(value & 0xf);
        }

        private char hexDigit(int value) {
            return (char) (value < 10 ? '0' + value : 'a' + value - 10);
        }

        private int canonicalStringBytes(String value) {
            long bytes = 2;
            for (int index = 0; index < value.length();) {
                int codePoint = value.codePointAt(index);
                index += Character.charCount(codePoint);
                if (codePoint == '"' || codePoint == '\\' || codePoint == '\b' || codePoint == '\f'
                    || codePoint == '\n' || codePoint == '\r' || codePoint == '\t') {
                    bytes += 2;
                } else if (codePoint < 0x20) {
                    bytes += 6;
                } else if (codePoint <= 0x7f) {
                    bytes++;
                } else if (codePoint <= 0x7ff) {
                    bytes += 2;
                } else if (codePoint <= 0xffff) {
                    bytes += 3;
                } else {
                    bytes += 4;
                }
                if (bytes > limits.canonicalBytes()) {
                    throw error("Canonical JSON exceeds " + limits.canonicalBytes() + " bytes");
                }
            }
            return (int) bytes;
        }

        private int addBytes(int current, int... additions) {
            long total = current;
            for (int addition : additions) {
                total += addition;
            }
            return checkedBytes(total);
        }

        private int checkedBytes(long bytes) {
            if (bytes > limits.canonicalBytes()) {
                throw error("Canonical JSON exceeds " + limits.canonicalBytes() + " bytes");
            }
            return Math.toIntExact(bytes);
        }

        private long plainLength(BigDecimal value) {
            if (value.signum() == 0) {
                return 1;
            }
            long precision = value.precision();
            long scale = (long) value.scale();
            long length = scale <= 0 ? precision - scale : precision > scale ? precision + 1 : 2 + scale;
            return value.signum() < 0 ? length + 1 : length;
        }

        private void enter(int parentDepth) {
            if (parentDepth + 1 > limits.depth()) {
                throw error("JSON exceeds depth " + limits.depth());
            }
        }

        private void countToken() {
            if (++tokens > limits.tokens()) {
                throw error("JSON exceeds " + limits.tokens() + " tokens");
            }
        }

        private void skipWhitespace() {
            int start = position;
            while (position < input.length()) {
                char current = input.charAt(position);
                if (current == ' ' || current == '\t' || current == '\n' || current == '\r') {
                    position++;
                } else {
                    break;
                }
            }
            if (position != start) {
                markNoncanonical();
            }
        }

        private void markNoncanonical() {
            if (exactCanonical) {
                exactCanonical = false;
                ranges.clear();
            }
        }

        private boolean consume(char expected) {
            if (position < input.length() && input.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void require(char expected) {
            if (!consume(expected)) {
                throw error("Expected '" + expected + "'");
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at character " + position);
        }
    }

    private record ParsedNode(JsonValue value, int canonicalBytes) {
    }

    private record ParsedString(String value, int canonicalBytes) {
    }

    private record SourceRange(int start, int end) {
    }

    private record KeyValue(String key, Object value) {
    }
}

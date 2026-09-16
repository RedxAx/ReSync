package restudio.resync.flow.handler.generic;

import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.modules.flow.FlowPacketSender;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

import com.google.gson.Gson;

public class GenericStringHandler implements NodeHandler {

    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();
    private static final Gson GSON = new Gson();
    private static final String TEMPLATE_INPUT = "template";
    private static final int MAX_LEVENSHTEIN_INPUT_LENGTH = 1024;
    private static final Set<String> DATA_ONLY_OPERATIONS = Set.of(
        "concat", "upper", "lower", "trim", "length", "is_empty", "is_blank", "is_numeric",
        "md5", "sha256", "sha512", "is_alpha", "is_alphanumeric", "is_email", "contains",
        "starts_with", "ends_with", "replace", "base64_encode", "url_encode", "join",
        "pad_left", "pad_right", "truncate", "reverse", "repeat", "levenshtein", "substring", "split",
        "base64_decode", "url_decode", "word_wrap", "template", "capitalize", "slugify", "camel_case", "pascal_case", "snake_case", "kebab_case",
        "matches", "equals_ignore_case", "to_json", "from_json", "shuffle", "soundex", "metaphone",
        "index_of", "last_index_of", "replace_regex");

    public GenericStringHandler() {
        registerBasicOperations();
        registerAdvancedOperations();
    }

    private void registerBasicOperations() {
        operations.put("concat", (ctx, node) -> {
            String a = ctx.getInputValue(node, "a", String.class, "");
            String b = ctx.getInputValue(node, "b", String.class, "");
            ctx.setOutput(node, "result", a + b);
        });
        operations.put("template", (ctx, node) -> {
            String template = ctx.getInputValue(node, TEMPLATE_INPUT, String.class, "");
            validateStringValue(template, "Template source");
            String result = template != null ? template : "";
            validateStringValue(result, "Template result");
            ctx.setOutput(node, "result", result);
        });
        operations.put("substring", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            Integer start = ctx.getInputValue(node, "start", Integer.class, 0);
            Integer length = ctx.getInputValue(node, "length", Integer.class, null);
            validateStringValue(value, "Substring source");
            validateSubstringBound(start, "Substring start");
            validateSubstringBound(length, "Substring length");
            int safeStart = start != null ? start : 0;
            if (value != null && safeStart <= value.length()) {
                int end = length != null ? (int) Math.min((long) safeStart + length, value.length()) : value.length();
                validateStringResultLength(end - safeStart);
                ctx.setOutput(node, "result", value.substring(safeStart, end));
            } else {
                ctx.setOutput(node, "result", "");
            }
        });
        operations.put("split", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            String delimiter = ctx.getInputValue(node, "delimiter", String.class, ",");
            validateStringValue(value, "Split value");
            validateStringValue(delimiter, "Split delimiter");
            if (value != null && delimiter != null) {
                ctx.setOutput(node, "result", List.of(value.split(Pattern.quote(delimiter), -1)));
            } else {
                ctx.setOutput(node, "result", List.of());
            }
        });
        operations.put("join", (ctx, node) -> {
            List<String> list = ctx.getInputValue(node, "list", List.class, List.of());
            String separator = ctx.getInputValue(node, "separator", String.class, ",");
            ctx.setOutput(node, "result", String.join(separator, list));
        });
        operations.put("replace", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            String target = ctx.getInputValue(node, "target", String.class, "");
            String replacement = ctx.getInputValue(node, "replacement", String.class, "");
            ctx.setOutput(node, "result", value != null ? value.replace(target, replacement) : "");
        });
        operations.put("replace_regex", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            String pattern = ctx.getInputValue(node, "pattern", String.class, "");
            String replacement = ctx.getInputValue(node, "replacement", String.class, "");
            if (value != null && pattern != null) {
                ctx.setOutput(node, "result", value.replaceAll(pattern, replacement));
            } else {
                ctx.setOutput(node, "result", "");
            }
        });
        operations.put("upper", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            ctx.setOutput(node, "result", value != null ? value.toUpperCase(Locale.ROOT) : "");
        });
        operations.put("lower", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            ctx.setOutput(node, "result", value != null ? value.toLowerCase(Locale.ROOT) : "");
        });
        operations.put("capitalize", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            validateStringValue(value, "Capitalize source");
            String result = "";
            if (value != null && !value.isEmpty()) {
                List<String> words = splitWhitespaceWords(value);
                StringBuilder builder = new StringBuilder();
                for (String word : words) {
                    if (!word.isEmpty()) {
                        builder.append(capitalizeFirstCodePoint(word));
                        builder.append(" ");
                    }
                }
                result = builder.toString().trim();
            }
            validateStringValue(result, "Capitalize result");
            ctx.setOutput(node, "result", result);
        });
        operations.put("trim", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            ctx.setOutput(node, "result", value != null ? value.trim() : "");
        });
        operations.put("length", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            ctx.setOutput(node, "result", value != null ? value.length() : 0);
        });
        operations.put("reverse", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            String reversed = text != null ? new StringBuilder(text).reverse().toString() : "";
            ctx.setOutput(node, "reversed", reversed);
        });
        operations.put("repeat", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            Integer count = ctx.getInputValue(node, "count", Integer.class, 1);
            int safeCount = count != null ? count : 1;
            validateRepeatCount(safeCount);
            String repeated = "";
            if (text != null && safeCount > 0) {
                long resultLength = (long) text.length() * safeCount;
                if (resultLength > FlowPacketSender.MAX_STRING_LENGTH) {
                    throw new IllegalArgumentException("Repeated string result cannot exceed "
                        + FlowPacketSender.MAX_STRING_LENGTH + " characters");
                }
                repeated = text.repeat(safeCount);
            }
            ctx.setOutput(node, "repeated", repeated);
        });
        operations.put("contains", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            String substring = ctx.getInputValue(node, "substring", String.class, "");
            ctx.setOutput(node, "result", value != null && substring != null && value.contains(substring));
        });
        operations.put("starts_with", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            String prefix = ctx.getInputValue(node, "prefix", String.class, "");
            ctx.setOutput(node, "result", value != null && prefix != null && value.startsWith(prefix));
        });
        operations.put("ends_with", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            String suffix = ctx.getInputValue(node, "suffix", String.class, "");
            ctx.setOutput(node, "result", value != null && suffix != null && value.endsWith(suffix));
        });
        operations.put("matches", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            String pattern = ctx.getInputValue(node, "pattern", String.class, "");
            if (value != null && pattern != null) {
                ctx.setOutput(node, "result", Pattern.matches(pattern, value));
            } else {
                ctx.setOutput(node, "result", false);
            }
        });
        operations.put("equals_ignore_case", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            String other = ctx.getInputValue(node, "other", String.class, "");
            ctx.setOutput(node, "result", value != null && other != null && value.equalsIgnoreCase(other));
        });
        operations.put("index_of", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            String substring = ctx.getInputValue(node, "substring", String.class, "");
            ctx.setOutput(node, "index", value != null && substring != null ? value.indexOf(substring) : -1);
        });
        operations.put("last_index_of", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            String substring = ctx.getInputValue(node, "substring", String.class, "");
            ctx.setOutput(node, "index", value != null && substring != null ? value.lastIndexOf(substring) : -1);
        });
        operations.put("to_json", (ctx, node) -> {
            Object value = ctx.getInputValue(node, "value", Object.class, null);
            ctx.setOutput(node, "json", value != null ? GSON.toJson(value) : "null");
        });
        operations.put("from_json", (ctx, node) -> {
            String json = ctx.getInputValue(node, "json", String.class, "");
            String typeName = ctx.getInputValue(node, "type_name", String.class, "string");
            Object result = null;
            if (json != null) {
                if ("list".equals(typeName)) {
                    result = GSON.fromJson(json, List.class);
                } else if ("map".equals(typeName)) {
                    result = GSON.fromJson(json, Map.class);
                } else {
                    result = GSON.fromJson(json, String.class);
                }
            }
            ctx.setOutput(node, "result", result);
        });
        operations.put("soundex", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            ctx.setOutput(node, "code", text != null ? soundex(text) : "");
        });
        operations.put("metaphone", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            ctx.setOutput(node, "code", text != null ? metaphone(text) : "");
        });
    }

    private void registerAdvancedOperations() {
        operations.put("base64_encode", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            ctx.setOutput(node, "encoded", text != null ? Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)) : "");
        });
        operations.put("base64_decode", (ctx, node) -> {
            String encoded = ctx.getInputValue(node, "encoded", String.class, "");
            validateStringValue(encoded, "Base64 input");
            String decoded = decodeBase64(encoded);
            validateStringValue(decoded, "Base64 result");
            ctx.setOutput(node, "decoded", decoded);
        });
        operations.put("url_encode", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            String encoded = text != null ? URLEncoder.encode(text, StandardCharsets.UTF_8) : "";
            ctx.setOutput(node, "encoded", encoded);
        });
        operations.put("url_decode", (ctx, node) -> {
            String encoded = ctx.getInputValue(node, "encoded", String.class, "");
            validateStringValue(encoded, "URL input");
            String decoded = decodeUrlForm(encoded);
            validateStringValue(decoded, "URL result");
            ctx.setOutput(node, "decoded", decoded);
        });
        operations.put("md5", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            ctx.setOutput(node, "hash", hash(text, "MD5"));
        });
        operations.put("sha256", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            ctx.setOutput(node, "hash", hash(text, "SHA-256"));
        });
        operations.put("sha512", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            ctx.setOutput(node, "hash", hash(text, "SHA-512"));
        });
        operations.put("pad_left", (ctx, node) -> {
            Integer length = ctx.getInputValue(node, "length", Integer.class, 0);
            validateStringLength(length);
            String text = ctx.getInputValue(node, "text", String.class, "");
            String padChar = ctx.getInputValue(node, "pad_char", String.class, " ");
            String padded = text != null ? text : "";
            if (length != null && length > 0 && padded.length() < length) {
                char pc = padChar != null && !padChar.isEmpty() ? padChar.charAt(0) : ' ';
                padded = String.valueOf(pc).repeat(length - padded.length()) + padded;
            }
            ctx.setOutput(node, "padded", padded);
        });
        operations.put("pad_right", (ctx, node) -> {
            Integer length = ctx.getInputValue(node, "length", Integer.class, 0);
            validateStringLength(length);
            String text = ctx.getInputValue(node, "text", String.class, "");
            String padChar = ctx.getInputValue(node, "pad_char", String.class, " ");
            String padded = text != null ? text : "";
            if (length != null && length > 0 && padded.length() < length) {
                char pc = padChar != null && !padChar.isEmpty() ? padChar.charAt(0) : ' ';
                padded = padded + String.valueOf(pc).repeat(length - padded.length());
            }
            ctx.setOutput(node, "padded", padded);
        });
        operations.put("truncate", (ctx, node) -> {
            Integer length = ctx.getInputValue(node, "length", Integer.class, 0);
            validateStringLength(length);
            String text = ctx.getInputValue(node, "text", String.class, "");
            Boolean addEllipsis = ctx.getInputValue(node, "add_ellipsis", Boolean.class, false);
            String truncated = text != null ? text : "";
            if (length != null && length > 0 && truncated.length() > length) {
                if (Boolean.TRUE.equals(addEllipsis) && length > 3) {
                    truncated = truncated.substring(0, length - 3) + "...";
                } else {
                    truncated = truncated.substring(0, length);
                }
            }
            ctx.setOutput(node, "truncated", truncated);
        });
        operations.put("word_wrap", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            Object widthInput = ctx.getInputValue(node, "width", Object.class, 80);
            validateStringValue(text, "Word wrap text");
            int width = validateWordWrapWidth(widthInput);
            List<String> lines = new ArrayList<>();
            StringBuilder currentLine = new StringBuilder();
            for (String word : splitWhitespaceWords(text != null ? text : "")) {
                if (currentLine.isEmpty()) {
                    currentLine.append(word);
                } else if (currentLine.length() + 1 + word.length() <= width) {
                    currentLine.append(" ").append(word);
                } else {
                    lines.add(currentLine.toString());
                    currentLine.setLength(0);
                    currentLine.append(word);
                }
            }
            if (!currentLine.isEmpty()) {
                lines.add(currentLine.toString());
            }
            ctx.setOutput(node, "wrapped_lines_list", new ArrayList<>(lines));
        });
        operations.put("levenshtein", (ctx, node) -> {
            String text1 = ctx.getInputValue(node, "text1", String.class, "");
            String text2 = ctx.getInputValue(node, "text2", String.class, "");
            validateLevenshteinInput(text1);
            validateLevenshteinInput(text2);
            int distance = 0;
            if (text1 != null && text2 != null) {
                int len1 = text1.length();
                int len2 = text2.length();
                int[][] dp = new int[len1 + 1][len2 + 1];
                for (int i = 0; i <= len1; i++) dp[i][0] = i;
                for (int j = 0; j <= len2; j++) dp[0][j] = j;
                for (int i = 1; i <= len1; i++) {
                    for (int j = 1; j <= len2; j++) {
                        int cost = text1.charAt(i - 1) == text2.charAt(j - 1) ? 0 : 1;
                        dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
                    }
                }
                distance = dp[len1][len2];
            }
            ctx.setOutput(node, "distance", distance);
        });
        operations.put("slugify", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            validateStringValue(text, "Slugify source");
            String slug = "";
            if (text != null) {
                slug = text.toLowerCase(Locale.ROOT).trim();
                slug = slug.replaceAll("[^a-z0-9\\s-]", "");
                slug = slug.replaceAll("\\s+", "-");
                slug = slug.replaceAll("-+", "-");
                slug = slug.replaceAll("^-+", "");
                slug = slug.replaceAll("-+$", "");
            }
            validateStringValue(slug, "Slugify result");
            ctx.setOutput(node, "slug", slug);
        });
        operations.put("camel_case", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            validateStringValue(text, "Camel case source");
            String camelCase = "";
            if (text != null && !text.isEmpty()) {
                List<String> words = splitWords(text, false);
                StringBuilder result = new StringBuilder();
                for (int i = 0; i < words.size(); i++) {
                    String word = words.get(i).toLowerCase(Locale.ROOT);
                    if (!word.isEmpty()) {
                        if (i == 0) {
                            result.append(word);
                        } else {
                            result.append(capitalizeFirstCodePoint(word));
                        }
                    }
                }
                camelCase = result.toString();
            }
            validateStringValue(camelCase, "Camel case result");
            ctx.setOutput(node, "camel_case", camelCase);
        });
        operations.put("pascal_case", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            validateStringValue(text, "Pascal case source");
            String pascalCase = "";
            if (text != null && !text.isEmpty()) {
                List<String> words = splitWords(text, false);
                StringBuilder result = new StringBuilder();
                for (String word : words) {
                    String lowerWord = word.toLowerCase(Locale.ROOT);
                    if (!lowerWord.isEmpty()) {
                        result.append(capitalizeFirstCodePoint(lowerWord));
                    }
                }
                pascalCase = result.toString();
            }
            validateStringValue(pascalCase, "Pascal case result");
            ctx.setOutput(node, "pascal_case", pascalCase);
        });
        operations.put("snake_case", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            validateStringValue(text, "Snake case source");
            String snakeCase = "";
            if (text != null) {
                List<String> words = splitWords(text, true);
                snakeCase = String.join("_", words.stream().map(word -> word.toLowerCase(Locale.ROOT)).toList());
            }
            validateStringValue(snakeCase, "Snake case result");
            ctx.setOutput(node, "snake_case", snakeCase);
        });
        operations.put("kebab_case", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            validateStringValue(text, "Kebab case source");
            String kebabCase = "";
            if (text != null) {
                List<String> words = splitWords(text, true);
                kebabCase = String.join("-", words.stream().map(word -> word.toLowerCase(Locale.ROOT)).toList());
            }
            validateStringValue(kebabCase, "Kebab case result");
            ctx.setOutput(node, "kebab_case", kebabCase);
        });
        operations.put("shuffle", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            String shuffled = text != null ? text : "";
            if (!shuffled.isEmpty()) {
                List<Character> chars = new ArrayList<>();
                for (char c : shuffled.toCharArray()) chars.add(c);
                Collections.shuffle(chars);
                StringBuilder sb = new StringBuilder();
                for (char c : chars) sb.append(c);
                shuffled = sb.toString();
            }
            ctx.setOutput(node, "shuffled", shuffled);
        });
        operations.put("is_empty", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            ctx.setOutput(node, "is_empty", text == null || text.isEmpty());
        });
        operations.put("is_blank", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            ctx.setOutput(node, "is_blank", text == null || text.isBlank());
        });
        operations.put("is_numeric", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            ctx.setOutput(node, "is_numeric", text != null && !text.isEmpty() && text.matches("-?\\d+(\\.\\d+)?"));
        });
        operations.put("is_alpha", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            ctx.setOutput(node, "is_alpha", text != null && !text.isEmpty() && text.matches("^[a-zA-Z]+$"));
        });
        operations.put("is_alphanumeric", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            ctx.setOutput(node, "is_alphanumeric", text != null && !text.isEmpty() && text.matches("^[a-zA-Z0-9]+$"));
        });
        operations.put("is_email", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            ctx.setOutput(node, "is_email", text != null && !text.isEmpty() && text.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"));
        });
    }

    private static void validateStringLength(Integer length) {
        if (length != null && (length < 0 || length > FlowPacketSender.MAX_STRING_LENGTH)) {
            throw new IllegalArgumentException("String length must be between 0 and " + FlowPacketSender.MAX_STRING_LENGTH);
        }
    }

    private static List<String> splitWords(String text, boolean splitCamelCase) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        int previousCodePoint = -1;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            boolean wordCodePoint = Character.isLetterOrDigit(codePoint)
                || (!word.isEmpty() && isMark(codePoint));
            if (!wordCodePoint) {
                appendWord(words, word);
                previousCodePoint = -1;
            } else {
                if (splitCamelCase && !word.isEmpty()
                        && Character.isUpperCase(codePoint) && Character.isLowerCase(previousCodePoint)) {
                    appendWord(words, word);
                }
                word.appendCodePoint(codePoint);
                if (Character.isLetterOrDigit(codePoint)) {
                    previousCodePoint = codePoint;
                }
            }
            offset += Character.charCount(codePoint);
        }
        appendWord(words, word);
        return words;
    }

    private static List<String> splitWhitespaceWords(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                appendWord(words, word);
            } else {
                word.appendCodePoint(codePoint);
            }
            offset += Character.charCount(codePoint);
        }
        appendWord(words, word);
        return words;
    }

    private static void appendWord(List<String> words, StringBuilder word) {
        if (!word.isEmpty()) {
            words.add(word.toString());
            word.setLength(0);
        }
    }

    private static boolean isMark(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK
            || type == Character.COMBINING_SPACING_MARK
            || type == Character.ENCLOSING_MARK;
    }

    private static String capitalizeFirstCodePoint(String word) {
        if (word.isEmpty()) {
            return "";
        }
        int firstCodePointLength = Character.charCount(word.codePointAt(0));
        String first = word.substring(0, firstCodePointLength).toUpperCase(Locale.ROOT);
        String rest = word.substring(firstCodePointLength).toLowerCase(Locale.ROOT);
        return first + rest;
    }

    private static void validateStringValue(String value, String label) {
        if (value != null && value.length() > FlowPacketSender.MAX_STRING_LENGTH) {
            throw new IllegalArgumentException(label + " cannot exceed " + FlowPacketSender.MAX_STRING_LENGTH + " characters");
        }
    }

    private static void validateSubstringBound(Integer bound, String label) {
        if (bound != null && (bound < 0 || bound > FlowPacketSender.MAX_STRING_LENGTH)) {
            throw new IllegalArgumentException(label + " must be between 0 and " + FlowPacketSender.MAX_STRING_LENGTH);
        }
    }

    private static void validateRepeatCount(int count) {
        if (count < 0 || count > FlowPacketSender.MAX_STRING_LENGTH) {
            throw new IllegalArgumentException("Repeat count must be between 0 and " + FlowPacketSender.MAX_STRING_LENGTH);
        }
    }

    private static void validateStringResultLength(int length) {
        if (length > FlowPacketSender.MAX_STRING_LENGTH) {
            throw new IllegalArgumentException("Substring result cannot exceed " + FlowPacketSender.MAX_STRING_LENGTH + " characters");
        }
    }

    private static void validateLevenshteinInput(String text) {
        if (text != null && text.length() > MAX_LEVENSHTEIN_INPUT_LENGTH) {
            throw new IllegalArgumentException("Levenshtein input cannot exceed " + MAX_LEVENSHTEIN_INPUT_LENGTH + " characters");
        }
    }

    private static String hash(String text, String algorithm) {
        if (text == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Hash algorithm is unavailable: " + algorithm, exception);
        }
    }

    private static String soundex(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String upper = text.toUpperCase();
        char first = upper.charAt(0);
        StringBuilder result = new StringBuilder();
        result.append(first);
        char prevCode = soundexCode(first);
        for (int i = 1; i < upper.length() && result.length() < 4; i++) {
            char c = upper.charAt(i);
            if (c == 'H' || c == 'W') {
                continue;
            }
            char code = soundexCode(c);
            if (code != '0' && code != prevCode) {
                result.append(code);
            }
            if (c != 'H' && c != 'W') {
                prevCode = code;
            }
        }
        while (result.length() < 4) {
            result.append('0');
        }
        return result.toString();
    }

    private static char soundexCode(char c) {
        return switch (c) {
            case 'B', 'F', 'P', 'V' -> '1';
            case 'C', 'G', 'J', 'K', 'Q', 'S', 'X', 'Z' -> '2';
            case 'D', 'T' -> '3';
            case 'L' -> '4';
            case 'M', 'N' -> '5';
            case 'R' -> '6';
            default -> '0';
        };
    }

    private static String metaphone(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder clean = new StringBuilder();
        for (char c : text.toUpperCase().toCharArray()) {
            if (c >= 'A' && c <= 'Z') {
                clean.append(c);
            }
        }
        String input = clean.toString();
        if (input.isEmpty()) {
            return "";
        }

        int i = 0;
        int n = input.length();
        StringBuilder result = new StringBuilder();

        if (input.startsWith("KN") || input.startsWith("GN") || input.startsWith("PN")
                || input.startsWith("AE") || input.startsWith("WR")) {
            i = 1;
        } else if (input.charAt(0) == 'X') {
            result.append('S');
            i = 1;
        }

        while (i < n) {
            char c = input.charAt(i);
            char prev = i > 0 ? input.charAt(i - 1) : 0;
            char next = i < n - 1 ? input.charAt(i + 1) : 0;
            char nextNext = i < n - 2 ? input.charAt(i + 2) : 0;

            switch (c) {
                case 'B' -> {
                    if (i < n - 1 || prev != 'M') {
                        result.append('P');
                    }
                }
                case 'C' -> {
                    if (next == 'H' && prev != 'S') {
                        result.append('X');
                        i++;
                    } else if (next == 'I' && nextNext == 'A') {
                        result.append('X');
                    } else if (next == 'I' || next == 'E' || next == 'Y') {
                        result.append('S');
                        i++;
                    } else if (next == 'K') {
                        result.append('K');
                        i++;
                    } else {
                        result.append('K');
                    }
                }
                case 'D' -> {
                    if (next == 'G' && (nextNext == 'E' || nextNext == 'I' || nextNext == 'Y')) {
                        result.append('J');
                        i += 2;
                    } else {
                        result.append('T');
                    }
                }
                case 'F' -> result.append('F');
                case 'G' -> {
                    if (next == 'H') {
                        if (i == 0 || isVowel(nextNext)) {
                            result.append('K');
                            i++;
                        } else {
                            i++;
                        }
                    } else if (next == 'N') {
                        result.append('N');
                        i++;
                    } else if (next == 'I' || next == 'E' || next == 'Y') {
                        result.append('J');
                        i++;
                    } else {
                        result.append('K');
                    }
                }
                case 'H' -> {
                    if (i == 0 || isVowel(next)) {
                        result.append('H');
                    } else if (!isVowel(prev)) {
                        result.append('H');
                    }
                }
                case 'J' -> result.append('J');
                case 'K' -> result.append('K');
                case 'L' -> result.append('L');
                case 'M' -> result.append('M');
                case 'N' -> result.append('N');
                case 'P' -> {
                    if (next == 'H') {
                        result.append('F');
                        i++;
                    } else {
                        result.append('P');
                    }
                }
                case 'Q' -> result.append('K');
                case 'R' -> result.append('R');
                case 'S' -> {
                    if (next == 'H') {
                        result.append('X');
                        i++;
                    } else if (next == 'I' && (nextNext == 'O' || nextNext == 'A')) {
                        result.append('X');
                        i += 2;
                    } else {
                        result.append('S');
                    }
                }
                case 'T' -> {
                    if (next == 'H') {
                        result.append('0');
                        i++;
                    } else if (next == 'I' && (nextNext == 'O' || nextNext == 'A')) {
                        result.append('X');
                        i += 2;
                    } else {
                        result.append('T');
                    }
                }
                case 'V' -> result.append('F');
                case 'W' -> {
                    if (isVowel(next)) {
                        result.append('W');
                    }
                }
                case 'X' -> {
                    if (i == 0) {
                        result.append('S');
                    } else {
                        result.append('K').append('S');
                    }
                }
                case 'Y' -> {
                    if (isVowel(next)) {
                        result.append('Y');
                    }
                }
                case 'Z' -> result.append('S');
            }
            i++;
        }

        return result.toString();
    }

    private static boolean isVowel(char c) {
        return c == 'A' || c == 'E' || c == 'I' || c == 'O' || c == 'U';
    }

    public void registerTo(HandlerRegistry registry) {
        registry.register("GenericStringHandler", this);
    }

    private static String decodeBase64(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return "";
        }
        if ((encoded.length() & 3) != 0) {
            throw new IllegalArgumentException("Base64 input is malformed");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Base64 input is malformed", exception);
        }
        if (!encoded.equals(Base64.getEncoder().encodeToString(bytes))) {
            throw new IllegalArgumentException("Base64 input is malformed");
        }
        return decodeUtf8(bytes, "Base64 input must be valid UTF-8");
    }

    private static String decodeUrlForm(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder(encoded.length());
        for (int index = 0; index < encoded.length();) {
            char character = encoded.charAt(index);
            if (character == '+') {
                result.append(' ');
                index++;
            } else if (character == '%') {
                int byteStart = index;
                while (index < encoded.length() && encoded.charAt(index) == '%') {
                    if (index + 2 >= encoded.length()) {
                        throw new IllegalArgumentException("URL input is malformed");
                    }
                    if (hexDigit(encoded.charAt(index + 1)) < 0 || hexDigit(encoded.charAt(index + 2)) < 0) {
                        throw new IllegalArgumentException("URL input is malformed");
                    }
                    index += 3;
                }
                byte[] bytes = new byte[(index - byteStart) / 3];
                int byteIndex = 0;
                for (int offset = byteStart; offset < index; offset += 3) {
                    bytes[byteIndex++] = (byte) ((hexDigit(encoded.charAt(offset + 1)) << 4)
                        | hexDigit(encoded.charAt(offset + 2)));
                }
                result.append(decodeUtf8(bytes, "URL input must be valid UTF-8"));
            } else {
                result.append(character);
                index++;
            }
            if (result.length() > FlowPacketSender.MAX_STRING_LENGTH) {
                throw new IllegalArgumentException("URL result cannot exceed "
                    + FlowPacketSender.MAX_STRING_LENGTH + " characters");
            }
        }
        return result.toString();
    }

    private static String decodeUtf8(byte[] bytes, String message) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(message, exception);
        }
    }

    private static int hexDigit(char character) {
        if (character >= '0' && character <= '9') {
            return character - '0';
        }
        if (character >= 'A' && character <= 'F') {
            return character - 'A' + 10;
        }
        if (character >= 'a' && character <= 'f') {
            return character - 'a' + 10;
        }
        return -1;
    }

    private static int validateWordWrapWidth(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Word wrap width must be a finite whole number between 1 and "
                + FlowPacketSender.MAX_STRING_LENGTH);
        }
        double numericWidth = number.doubleValue();
        if (!Double.isFinite(numericWidth) || numericWidth != Math.rint(numericWidth)
                || numericWidth < 1 || numericWidth > FlowPacketSender.MAX_STRING_LENGTH) {
            throw new IllegalArgumentException("Word wrap width must be a finite whole number between 1 and "
                + FlowPacketSender.MAX_STRING_LENGTH);
        }
        return (int) numericWidth;
    }

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op == null) {
            throw new IllegalArgumentException("Unknown string operation: " + operation);
        }
        op.accept(ctx, node);
        if (!DATA_ONLY_OPERATIONS.contains(operation)) {
            ctx.triggerOutput("flow");
        }
    }
}

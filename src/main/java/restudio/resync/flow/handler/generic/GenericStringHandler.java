package restudio.resync.flow.handler.generic;

import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

import com.google.gson.Gson;

public class GenericStringHandler implements NodeHandler {

    private final Map<String, BiConsumer<FlowContext, FlowNode>> operations = new ConcurrentHashMap<>();
    private static final Gson GSON = new Gson();
    private static final String TEMPLATE_INPUT = "template";

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
            ctx.setOutput(node, "result", template);
        });
        operations.put("substring", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            Integer start = ctx.getInputValue(node, "start", Integer.class, 0);
            Integer length = ctx.getInputValue(node, "length", Integer.class, null);
            if (value != null && start >= 0 && start < value.length()) {
                int end = length != null ? Math.min(start + length, value.length()) : value.length();
                ctx.setOutput(node, "result", value.substring(start, end));
            } else {
                ctx.setOutput(node, "result", "");
            }
        });
        operations.put("split", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            String delimiter = ctx.getInputValue(node, "delimiter", String.class, ",");
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
            ctx.setOutput(node, "result", value != null ? value.toUpperCase() : "");
        });
        operations.put("lower", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            ctx.setOutput(node, "result", value != null ? value.toLowerCase() : "");
        });
        operations.put("capitalize", (ctx, node) -> {
            String value = ctx.getInputValue(node, "value", String.class, "");
            if (value != null && !value.isEmpty()) {
                String[] words = value.split("\\s+");
                StringBuilder result = new StringBuilder();
                for (String word : words) {
                    if (!word.isEmpty()) {
                        result.append(Character.toUpperCase(word.charAt(0)));
                        if (word.length() > 1) result.append(word.substring(1).toLowerCase());
                        result.append(" ");
                    }
                }
                ctx.setOutput(node, "result", result.toString().trim());
            } else {
                ctx.setOutput(node, "result", "");
            }
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
            String text = getStringInput(ctx, node, "text", "value");
            String reversed = text != null ? new StringBuilder(text).reverse().toString() : "";
            setStringOutput(ctx, node, reversed, "reversed", "result");
        });
        operations.put("repeat", (ctx, node) -> {
            String text = getStringInput(ctx, node, "text", "value");
            Integer count = ctx.getInputValue(node, "count", Integer.class, 1);
            String repeated = text != null && count > 0 ? text.repeat(count) : "";
            setStringOutput(ctx, node, repeated, "repeated", "result");
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
            String decoded = "";
            if (encoded != null && !encoded.isEmpty()) {
                decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            }
            ctx.setOutput(node, "decoded", decoded);
        });
        operations.put("url_encode", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            String encoded = text != null ? URLEncoder.encode(text, StandardCharsets.UTF_8) : "";
            ctx.setOutput(node, "encoded", encoded);
        });
        operations.put("url_decode", (ctx, node) -> {
            String encoded = ctx.getInputValue(node, "encoded", String.class, "");
            String decoded = encoded != null ? URLDecoder.decode(encoded, StandardCharsets.UTF_8) : "";
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
            String text = ctx.getInputValue(node, "text", String.class, "");
            Integer length = ctx.getInputValue(node, "length", Integer.class, 0);
            String padChar = ctx.getInputValue(node, "pad_char", String.class, " ");
            String padded = text != null ? text : "";
            if (length != null && length > 0 && padded.length() < length) {
                char pc = padChar != null && !padChar.isEmpty() ? padChar.charAt(0) : ' ';
                padded = String.valueOf(pc).repeat(length - padded.length()) + padded;
            }
            ctx.setOutput(node, "padded", padded);
        });
        operations.put("pad_right", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            Integer length = ctx.getInputValue(node, "length", Integer.class, 0);
            String padChar = ctx.getInputValue(node, "pad_char", String.class, " ");
            String padded = text != null ? text : "";
            if (length != null && length > 0 && padded.length() < length) {
                char pc = padChar != null && !padChar.isEmpty() ? padChar.charAt(0) : ' ';
                padded = padded + String.valueOf(pc).repeat(length - padded.length());
            }
            ctx.setOutput(node, "padded", padded);
        });
        operations.put("truncate", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            Integer length = ctx.getInputValue(node, "length", Integer.class, 0);
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
            Integer width = ctx.getInputValue(node, "width", Integer.class, 80);
            List<String> lines = new ArrayList<>();
            if (text != null && width != null && width > 0) {
                String[] words = text.split("\\s+");
                StringBuilder currentLine = new StringBuilder();
                for (String word : words) {
                    if (currentLine.isEmpty()) {
                        currentLine.append(word);
                    } else if (currentLine.length() + 1 + word.length() <= width) {
                        currentLine.append(" ").append(word);
                    } else {
                        lines.add(currentLine.toString());
                        currentLine = new StringBuilder(word);
                    }
                }
                if (!currentLine.isEmpty()) {
                    lines.add(currentLine.toString());
                }
            }
            ctx.setOutput(node, "wrapped_lines_list", lines);
        });
        operations.put("levenshtein", (ctx, node) -> {
            String text1 = ctx.getInputValue(node, "text1", String.class, "");
            String text2 = ctx.getInputValue(node, "text2", String.class, "");
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
            String slug = "";
            if (text != null) {
                slug = text.toLowerCase().trim();
                slug = slug.replaceAll("[^a-z0-9\\s-]", "");
                slug = slug.replaceAll("\\s+", "-");
                slug = slug.replaceAll("-+", "-");
                slug = slug.replaceAll("^-+", "");
                slug = slug.replaceAll("-+$", "");
            }
            ctx.setOutput(node, "slug", slug);
        });
        operations.put("camel_case", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            String camelCase = "";
            if (text != null && !text.isEmpty()) {
                String[] words = text.replaceAll("[^a-zA-Z0-9\\s]", " ").split("\\s+");
                StringBuilder result = new StringBuilder();
                for (int i = 0; i < words.length; i++) {
                    String word = words[i].toLowerCase();
                    if (!word.isEmpty()) {
                        if (i == 0) {
                            result.append(word);
                        } else {
                            result.append(Character.toUpperCase(word.charAt(0)));
                            if (word.length() > 1) result.append(word.substring(1));
                        }
                    }
                }
                camelCase = result.toString();
            }
            ctx.setOutput(node, "camel_case", camelCase);
        });
        operations.put("pascal_case", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            String pascalCase = "";
            if (text != null && !text.isEmpty()) {
                String[] words = text.replaceAll("[^a-zA-Z0-9\\s]", " ").split("\\s+");
                StringBuilder result = new StringBuilder();
                for (String word : words) {
                    String lowerWord = word.toLowerCase();
                    if (!lowerWord.isEmpty()) {
                        result.append(Character.toUpperCase(lowerWord.charAt(0)));
                        if (lowerWord.length() > 1) result.append(lowerWord.substring(1));
                    }
                }
                pascalCase = result.toString();
            }
            ctx.setOutput(node, "pascal_case", pascalCase);
        });
        operations.put("snake_case", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            String snakeCase = "";
            if (text != null) {
                snakeCase = text.replaceAll("[^a-zA-Z0-9\\s]", " ");
                snakeCase = snakeCase.replaceAll("([a-z])([A-Z])", "$1 $2");
                snakeCase = snakeCase.trim().toLowerCase().replaceAll("\\s+", "_");
            }
            ctx.setOutput(node, "snake_case", snakeCase);
        });
        operations.put("kebab_case", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            String kebabCase = "";
            if (text != null) {
                kebabCase = text.replaceAll("[^a-zA-Z0-9\\s]", " ");
                kebabCase = kebabCase.replaceAll("([a-z])([A-Z])", "$1 $2");
                kebabCase = kebabCase.trim().toLowerCase().replaceAll("\\s+", "-");
            }
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

    private static String getStringInput(FlowContext ctx, FlowNode node, String primaryPin, String legacyPin) {
        String primary = ctx.getInputValue(node, primaryPin, String.class, null);
        return primary != null ? primary : ctx.getInputValue(node, legacyPin, String.class, "");
    }

    private static void setStringOutput(FlowContext ctx, FlowNode node, String value, String primaryPin, String legacyPin) {
        ctx.setOutput(node, primaryPin, value);
        ctx.setOutput(node, legacyPin, value);
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

    @Override
    public void execute(FlowContext ctx, FlowNode node) {
        String operation = node.getHandlerConfig().getString("operation");
        BiConsumer<FlowContext, FlowNode> op = operation != null ? operations.get(operation) : null;
        if (op == null) {
            throw new IllegalArgumentException("Unknown string operation: " + operation);
        }
        op.accept(ctx, node);
        ctx.triggerOutput("flow");
    }
}

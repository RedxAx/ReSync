package restudio.resync.flow.nodes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import restudio.flow.data.FlowNode;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRegistry;
import restudio.resync.flow.NodeCategory;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class StringAdvancedNodes implements NodeCategory {

    private static final Gson GSON = new GsonBuilder().create();

    @Override
    public void registerNodes(FlowRegistry registry) {
        registry.register("string_base64_encode", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            String nodeId = findNodeId(ctx, node);
            String encoded = text != null ? Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)) : "";
            ctx.setNodeOutput(nodeId, "encoded", encoded);
        });

        registry.register("string_base64_decode", (ctx, node) -> {
            String encoded = ctx.getInputValue(node, "encoded", String.class, "");
            String nodeId = findNodeId(ctx, node);
            String decoded = "";
            if (encoded != null && !encoded.isEmpty()) {
                try {
                    decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    decoded = "";
                }
            }
            ctx.setNodeOutput(nodeId, "decoded", decoded);
        });

        registry.register("string_url_encode", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            String nodeId = findNodeId(ctx, node);
            String encoded = "";
            if (text != null) {
                try {
                    encoded = URLEncoder.encode(text, StandardCharsets.UTF_8.name());
                } catch (Exception e) {
                    encoded = "";
                }
            }
            ctx.setNodeOutput(nodeId, "encoded", encoded);
        });

        registry.register("string_url_decode", (ctx, node) -> {
            String encoded = ctx.getInputValue(node, "encoded", String.class, "");
            String nodeId = findNodeId(ctx, node);
            String decoded = "";
            if (encoded != null) {
                try {
                    decoded = URLDecoder.decode(encoded, StandardCharsets.UTF_8.name());
                } catch (Exception e) {
                    decoded = "";
                }
            }
            ctx.setNodeOutput(nodeId, "decoded", decoded);
        });

        registry.register("string_to_json", (ctx, node) -> {
            String jsonString = ctx.getInputValue(node, "json_string", String.class, "{}");
            String nodeId = findNodeId(ctx, node);
            Object jsonObject = new HashMap<>();
            if (jsonString != null && !jsonString.isEmpty()) {
                try {
                    jsonObject = GSON.fromJson(jsonString, Object.class);
                } catch (Exception e) {
                    jsonObject = new HashMap<>();
                }
            }
            ctx.setNodeOutput(nodeId, "json_object", jsonObject);
        });

        registry.register("string_from_json", (ctx, node) -> {
            Object object = ctx.getInputValue(node, "object", null);
            String nodeId = findNodeId(ctx, node);
            String jsonString = GSON.toJson(object);
            ctx.setNodeOutput(nodeId, "json_string", jsonString);
        });

        registry.register("string_md5", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            String nodeId = findNodeId(ctx, node);
            String hash = "";
            if (text != null) {
                try {
                    MessageDigest md = MessageDigest.getInstance("MD5");
                    byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    for (byte b : digest) {
                        sb.append(String.format("%02x", b));
                    }
                    hash = sb.toString();
                } catch (Exception e) {
                    hash = "";
                }
            }
            ctx.setNodeOutput(nodeId, "hash", hash);
        });

        registry.register("string_sha256", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            String nodeId = findNodeId(ctx, node);
            String hash = "";
            if (text != null) {
                try {
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    for (byte b : digest) {
                        sb.append(String.format("%02x", b));
                    }
                    hash = sb.toString();
                } catch (Exception e) {
                    hash = "";
                }
            }
            ctx.setNodeOutput(nodeId, "hash", hash);
        });

        registry.register("string_sha512", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            String nodeId = findNodeId(ctx, node);
            String hash = "";
            if (text != null) {
                try {
                    MessageDigest md = MessageDigest.getInstance("SHA-512");
                    byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    for (byte b : digest) {
                        sb.append(String.format("%02x", b));
                    }
                    hash = sb.toString();
                } catch (Exception e) {
                    hash = "";
                }
            }
            ctx.setNodeOutput(nodeId, "hash", hash);
        });

        registry.register("string_pad_left", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            Integer length = ctx.getInputValue(node, "length", Integer.class, 0);
            String padChar = ctx.getInputValue(node, "pad_char", String.class, " ");
            String nodeId = findNodeId(ctx, node);
            String padded = text != null ? text : "";
            if (length != null && length > 0 && padded.length() < length) {
                char pc = padChar != null && !padChar.isEmpty() ? padChar.charAt(0) : ' ';
                padded = String.valueOf(pc).repeat(length - padded.length()) + padded;
            }
            ctx.setNodeOutput(nodeId, "padded", padded);
        });

        registry.register("string_pad_right", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            Integer length = ctx.getInputValue(node, "length", Integer.class, 0);
            String padChar = ctx.getInputValue(node, "pad_char", String.class, " ");
            String nodeId = findNodeId(ctx, node);
            String padded = text != null ? text : "";
            if (length != null && length > 0 && padded.length() < length) {
                char pc = padChar != null && !padChar.isEmpty() ? padChar.charAt(0) : ' ';
                padded = padded + String.valueOf(pc).repeat(length - padded.length());
            }
            ctx.setNodeOutput(nodeId, "padded", padded);
        });

        registry.register("string_truncate", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            Integer length = ctx.getInputValue(node, "length", Integer.class, 0);
            Boolean addEllipsis = ctx.getInputValue(node, "add_ellipsis", Boolean.class, false);
            String nodeId = findNodeId(ctx, node);
            String truncated = text != null ? text : "";
            if (length != null && length > 0 && truncated.length() > length) {
                if (Boolean.TRUE.equals(addEllipsis) && length > 3) {
                    truncated = truncated.substring(0, length - 3) + "...";
                } else {
                    truncated = truncated.substring(0, length);
                }
            }
            ctx.setNodeOutput(nodeId, "truncated", truncated);
        });

        registry.register("string_word_wrap", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            Integer width = ctx.getInputValue(node, "width", Integer.class, 80);
            String nodeId = findNodeId(ctx, node);
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
            ctx.setNodeOutput(nodeId, "wrapped_lines_list", lines);
        });

        registry.register("string_levenshtein", (ctx, node) -> {
            String text1 = ctx.getInputValue(node, "text1", String.class, "");
            String text2 = ctx.getInputValue(node, "text2", String.class, "");
            String nodeId = findNodeId(ctx, node);
            int distance = 0;
            if (text1 != null && text2 != null) {
                int len1 = text1.length();
                int len2 = text2.length();
                int[][] dp = new int[len1 + 1][len2 + 1];
                for (int i = 0; i <= len1; i++) {
                    dp[i][0] = i;
                }
                for (int j = 0; j <= len2; j++) {
                    dp[0][j] = j;
                }
                for (int i = 1; i <= len1; i++) {
                    for (int j = 1; j <= len2; j++) {
                        int cost = text1.charAt(i - 1) == text2.charAt(j - 1) ? 0 : 1;
                        dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
                    }
                }
                distance = dp[len1][len2];
            }
            ctx.setNodeOutput(nodeId, "distance", distance);
        });

        registry.register("string_soundex", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            String nodeId = findNodeId(ctx, node);
            String soundex = "";
            if (text != null && !text.isEmpty()) {
                String input = text.toUpperCase().replaceAll("[^A-Z]", "");
                if (!input.isEmpty()) {
                    soundex = String.valueOf(input.charAt(0));
                    Map<Character, Character> soundexMap = new HashMap<>();
                    soundexMap.put('B', '1');
                    soundexMap.put('F', '1');
                    soundexMap.put('P', '1');
                    soundexMap.put('V', '1');
                    soundexMap.put('C', '2');
                    soundexMap.put('G', '2');
                    soundexMap.put('J', '2');
                    soundexMap.put('K', '2');
                    soundexMap.put('Q', '2');
                    soundexMap.put('S', '2');
                    soundexMap.put('X', '2');
                    soundexMap.put('Z', '2');
                    soundexMap.put('D', '3');
                    soundexMap.put('T', '3');
                    soundexMap.put('L', '4');
                    soundexMap.put('M', '5');
                    soundexMap.put('N', '5');
                    soundexMap.put('R', '6');
                    char prevCode = '\0';
                    for (int i = 1; i < input.length(); i++) {
                        char c = input.charAt(i);
                        Character code = soundexMap.get(c);
                        if (code != null && code != prevCode) {
                            soundex += code;
                            prevCode = code;
                        }
                    }
                    while (soundex.length() < 4) {
                        soundex += "0";
                    }
                    if (soundex.length() > 4) {
                        soundex = soundex.substring(0, 4);
                    }
                }
            }
            ctx.setNodeOutput(nodeId, "soundex", soundex);
        });

        registry.register("string_metaphone", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            String nodeId = findNodeId(ctx, node);
            String metaphone = "";
            if (text != null && !text.isEmpty()) {
                String input = text.toUpperCase().replaceAll("[^A-Z]", "");
                if (!input.isEmpty()) {
                    metaphone = input;
                    metaphone = metaphone.replaceAll("^KN", "N");
                    metaphone = metaphone.replaceAll("GN$", "N");
                    metaphone = metaphone.replaceAll("^GN", "N");
                    metaphone = metaphone.replaceAll("^PH", "F");
                    metaphone = metaphone.replaceAll("MB$", "M");
                    metaphone = metaphone.replaceAll("B([^AEIOUY]|$)", "");
                    metaphone = metaphone.replaceAll("CK", "K");
                    metaphone = metaphone.replaceAll("C([EYI])", "S$1");
                    metaphone = metaphone.replaceAll("C", "K");
                    metaphone = metaphone.replaceAll("DGE", "J");
                    metaphone = metaphone.replaceAll("DGIY", "J");
                    metaphone = metaphone.replaceAll("DG", "G");
                    metaphone = metaphone.replaceAll("GH([^AEIOU]|$)", "");
                    metaphone = metaphone.replaceAll("G([EYI])", "J$1");
                    metaphone = metaphone.replaceAll("([^AEIOU])H", "$1");
                    metaphone = metaphone.replaceAll("([^AEIOUY])H([^AEIOUY])", "$1");
                    metaphone = metaphone.replaceAll("K", "C");
                    metaphone = metaphone.replaceAll("PH", "F");
                    metaphone = metaphone.replaceAll("Q", "C");
                    metaphone = metaphone.replaceAll("SIA", "X");
                    metaphone = metaphone.replaceAll("SIO", "X");
                    metaphone = metaphone.replaceAll("TH", "0");
                    metaphone = metaphone.replaceAll("TIO", "X");
                    metaphone = metaphone.replaceAll("TIA", "X");
                    metaphone = metaphone.replaceAll("WH", "C");
                    metaphone = metaphone.replaceAll("W", "");
                    metaphone = metaphone.replaceAll("V", "F");
                    metaphone = metaphone.replaceAll("Y", "");
                    metaphone = metaphone.replaceAll("Z", "S");
                    metaphone = metaphone.replaceAll("X", "KS");
                    metaphone = metaphone.replaceAll("(.)\\1+", "$1");
                    metaphone = metaphone.replaceAll("[AEIOU]", "");
                    metaphone = metaphone.substring(0, Math.min(4, metaphone.length()));
                }
            }
            ctx.setNodeOutput(nodeId, "metaphone", metaphone);
        });

        registry.register("string_slugify", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            String nodeId = findNodeId(ctx, node);
            String slug = "";
            if (text != null) {
                slug = text.toLowerCase().trim();
                slug = slug.replaceAll("[^a-z0-9\\s-]", "");
                slug = slug.replaceAll("\\s+", "-");
                slug = slug.replaceAll("-+", "-");
                slug = slug.replaceAll("^-+", "");
                slug = slug.replaceAll("-+$", "");
            }
            ctx.setNodeOutput(nodeId, "slug", slug);
        });

        registry.register("string_camel_case", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            String nodeId = findNodeId(ctx, node);
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
                            if (word.length() > 1) {
                                result.append(word.substring(1));
                            }
                        }
                    }
                }
                camelCase = result.toString();
            }
            ctx.setNodeOutput(nodeId, "camel_case", camelCase);
        });

        registry.register("string_pascal_case", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            String nodeId = findNodeId(ctx, node);
            String pascalCase = "";
            if (text != null && !text.isEmpty()) {
                String[] words = text.replaceAll("[^a-zA-Z0-9\\s]", " ").split("\\s+");
                StringBuilder result = new StringBuilder();
                for (String word : words) {
                    String lowerWord = word.toLowerCase();
                    if (!lowerWord.isEmpty()) {
                        result.append(Character.toUpperCase(lowerWord.charAt(0)));
                        if (lowerWord.length() > 1) {
                            result.append(lowerWord.substring(1));
                        }
                    }
                }
                pascalCase = result.toString();
            }
            ctx.setNodeOutput(nodeId, "pascal_case", pascalCase);
        });

        registry.register("string_snake_case", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            String nodeId = findNodeId(ctx, node);
            String snakeCase = "";
            if (text != null) {
                snakeCase = text.replaceAll("[^a-zA-Z0-9\\s]", " ");
                snakeCase = snakeCase.replaceAll("([a-z])([A-Z])", "$1 $2");
                snakeCase = snakeCase.trim().toLowerCase().replaceAll("\\s+", "_");
            }
            ctx.setNodeOutput(nodeId, "snake_case", snakeCase);
        });

        registry.register("string_kebab_case", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            String nodeId = findNodeId(ctx, node);
            String kebabCase = "";
            if (text != null) {
                kebabCase = text.replaceAll("[^a-zA-Z0-9\\s]", " ");
                kebabCase = kebabCase.replaceAll("([a-z])([A-Z])", "$1 $2");
                kebabCase = kebabCase.trim().toLowerCase().replaceAll("\\s+", "-");
            }
            ctx.setNodeOutput(nodeId, "kebab_case", kebabCase);
        });

        registry.register("string_reverse", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            String nodeId = findNodeId(ctx, node);
            String reversed = text != null ? new StringBuilder(text).reverse().toString() : "";
            ctx.setNodeOutput(nodeId, "reversed", reversed);
        });

        registry.register("string_shuffle", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            String nodeId = findNodeId(ctx, node);
            String shuffled = text != null ? text : "";
            if (!shuffled.isEmpty()) {
                List<Character> chars = new ArrayList<>();
                for (char c : shuffled.toCharArray()) {
                    chars.add(c);
                }
                Collections.shuffle(chars);
                StringBuilder sb = new StringBuilder();
                for (char c : chars) {
                    sb.append(c);
                }
                shuffled = sb.toString();
            }
            ctx.setNodeOutput(nodeId, "shuffled", shuffled);
        });

        registry.register("string_repeat", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            Integer count = ctx.getInputValue(node, "count", Integer.class, 1);
            String nodeId = findNodeId(ctx, node);
            String repeated = "";
            if (text != null && count != null && count > 0) {
                repeated = text.repeat(count);
            }
            ctx.setNodeOutput(nodeId, "repeated", repeated);
        });

        registry.register("string_is_empty", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            String nodeId = findNodeId(ctx, node);
            boolean isEmpty = text == null || text.isEmpty();
            ctx.setNodeOutput(nodeId, "is_empty", isEmpty);
        });

        registry.register("string_is_blank", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            String nodeId = findNodeId(ctx, node);
            boolean isBlank = text == null || text.isBlank();
            ctx.setNodeOutput(nodeId, "is_blank", isBlank);
        });

        registry.register("string_is_numeric", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            String nodeId = findNodeId(ctx, node);
            boolean isNumeric = text != null && !text.isEmpty() && text.matches("-?\\d+(\\.\\d+)?");
            ctx.setNodeOutput(nodeId, "is_numeric", isNumeric);
        });

        registry.register("string_is_alpha", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            String nodeId = findNodeId(ctx, node);
            boolean isAlpha = text != null && !text.isEmpty() && text.matches("^[a-zA-Z]+$");
            ctx.setNodeOutput(nodeId, "is_alpha", isAlpha);
        });

        registry.register("string_is_alphanumeric", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            String nodeId = findNodeId(ctx, node);
            boolean isAlphanumeric = text != null && !text.isEmpty() && text.matches("^[a-zA-Z0-9]+$");
            ctx.setNodeOutput(nodeId, "is_alphanumeric", isAlphanumeric);
        });

        registry.register("string_is_email", (ctx, node) -> {
            String text = ctx.getInputValue(node, "text", String.class, "");
            String nodeId = findNodeId(ctx, node);
            boolean isEmail = text != null && !text.isEmpty() && text.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
            ctx.setNodeOutput(nodeId, "is_email", isEmail);
        });
    }

    private static String findNodeId(FlowContext ctx, FlowNode node) {
        for (var entry : ctx.getRuntime().getGraph().getNodes().entrySet()) {
            if (entry.getValue() == node) {
                return entry.getKey();
            }
        }
        return null;
    }
}

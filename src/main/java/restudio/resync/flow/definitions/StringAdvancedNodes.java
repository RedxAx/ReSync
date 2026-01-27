package restudio.resync.flow.definitions;

import restudio.flow.data.FlowType;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

public class StringAdvancedNodes implements NodeDefinitionCategory {
    @Override
    public void registerNodes(NodeDefinitionRegistry registry) {
        registry.register(new NodeDefinition.Builder("string_base64_encode", "Base64 Encode", NodeDefinition.NodeCategory.DATA)
            .input("text", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("encoded", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("string_base64_decode", "Base64 Decode", NodeDefinition.NodeCategory.DATA)
            .input("encoded", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("decoded", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("string_url_encode", "URL Encode", NodeDefinition.NodeCategory.DATA)
            .input("text", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("encoded", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("string_url_decode", "URL Decode", NodeDefinition.NodeCategory.DATA)
            .input("encoded", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("decoded", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("string_to_json", "String To JSON", NodeDefinition.NodeCategory.DATA)
            .input("json_string", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("json_object", NodeDefinition.PinType.DATA, FlowType.ANY)
            .build());

        registry.register(new NodeDefinition.Builder("string_from_json", "String From JSON", NodeDefinition.NodeCategory.DATA)
            .input("object", NodeDefinition.PinType.DATA, FlowType.ANY)
            .output("json_string", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("string_md5", "String MD5", NodeDefinition.NodeCategory.DATA)
            .input("text", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("hash", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("string_sha256", "String SHA256", NodeDefinition.NodeCategory.DATA)
            .input("text", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("hash", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("string_sha512", "String SHA512", NodeDefinition.NodeCategory.DATA)
            .input("text", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("hash", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("string_pad_left", "Pad Left", NodeDefinition.NodeCategory.DATA)
            .input("text", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("length", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("pad_char", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("padded", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("string_pad_right", "Pad Right", NodeDefinition.NodeCategory.DATA)
            .input("text", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("length", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("pad_char", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("padded", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("string_truncate", "Truncate", NodeDefinition.NodeCategory.DATA)
            .input("text", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("length", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .input("add_ellipsis", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .output("truncated", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("string_word_wrap", "Word Wrap", NodeDefinition.NodeCategory.DATA)
            .input("text", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("width", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("wrapped_lines_list", NodeDefinition.PinType.DATA, FlowType.LIST)
            .build());

        registry.register(new NodeDefinition.Builder("string_levenshtein", "Levenshtein Distance", NodeDefinition.NodeCategory.DATA)
            .input("text1", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("text2", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("distance", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("string_soundex", "Soundex", NodeDefinition.NodeCategory.DATA)
            .input("text", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("soundex", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("string_metaphone", "Metaphone", NodeDefinition.NodeCategory.DATA)
            .input("text", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("metaphone", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("string_slugify", "Slugify", NodeDefinition.NodeCategory.DATA)
            .input("text", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("slug", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("string_camel_case", "Camel Case", NodeDefinition.NodeCategory.DATA)
            .input("text", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("camel_case", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("string_pascal_case", "Pascal Case", NodeDefinition.NodeCategory.DATA)
            .input("text", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("pascal_case", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("string_snake_case", "Snake Case", NodeDefinition.NodeCategory.DATA)
            .input("text", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("snake_case", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("string_kebab_case", "Kebab Case", NodeDefinition.NodeCategory.DATA)
            .input("text", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("kebab_case", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("string_reverse", "Reverse", NodeDefinition.NodeCategory.DATA)
            .input("text", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("reversed", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("string_shuffle", "Shuffle", NodeDefinition.NodeCategory.DATA)
            .input("text", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("shuffled", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("string_repeat", "Repeat", NodeDefinition.NodeCategory.DATA)
            .input("text", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("count", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("repeated", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("string_is_empty", "Is Empty", NodeDefinition.NodeCategory.LOGIC)
            .input("text", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("is_empty", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("string_is_blank", "Is Blank", NodeDefinition.NodeCategory.LOGIC)
            .input("text", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("is_blank", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("string_is_numeric", "Is Numeric", NodeDefinition.NodeCategory.LOGIC)
            .input("text", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("is_numeric", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("string_is_alpha", "Is Alpha", NodeDefinition.NodeCategory.LOGIC)
            .input("text", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("is_alpha", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("string_is_alphanumeric", "Is Alphanumeric", NodeDefinition.NodeCategory.LOGIC)
            .input("text", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("is_alphanumeric", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("string_is_email", "Is Email", NodeDefinition.NodeCategory.LOGIC)
            .input("text", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("is_email", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());
    }
}

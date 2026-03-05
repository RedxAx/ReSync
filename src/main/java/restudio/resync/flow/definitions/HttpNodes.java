package restudio.resync.flow.definitions;

import restudio.flow.data.FlowType;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionRegistry;

public class HttpNodes implements NodeDefinitionCategory {

    @Override
    public void registerNodes(NodeDefinitionRegistry registry) {
        registry.register(new NodeDefinition.Builder("http_get", "HTTP: Get Request", NodeDefinition.NodeCategory.HTTP)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("url", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("headers", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("response", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .build());

        registry.register(new NodeDefinition.Builder("http_post", "HTTP: Post Request", NodeDefinition.NodeCategory.HTTP)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("url", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("body", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .input("headers", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("response", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .build());

        registry.register(new NodeDefinition.Builder("http_put", "HTTP: Put Request", NodeDefinition.NodeCategory.HTTP)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("url", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("body", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .input("headers", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("response", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .build());

        registry.register(new NodeDefinition.Builder("http_delete", "HTTP: Delete Request", NodeDefinition.NodeCategory.HTTP)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("url", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("headers", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("response", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .build());

        registry.register(new NodeDefinition.Builder("http_patch", "HTTP: Patch Request", NodeDefinition.NodeCategory.HTTP)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("url", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("body", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .input("headers", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("response", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .build());

        registry.register(new NodeDefinition.Builder("http_request", "HTTP: Custom Request", NodeDefinition.NodeCategory.HTTP)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("method", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("url", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("body", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .input("headers", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .input("query_params", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("response", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .build());

        registry.register(new NodeDefinition.Builder("http_get_status", "HTTP: Get Status Code", NodeDefinition.NodeCategory.HTTP)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("response", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("status_code", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .build());

        registry.register(new NodeDefinition.Builder("http_get_body", "HTTP: Get Response Body", NodeDefinition.NodeCategory.HTTP)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("response", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("body", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("http_get_header", "HTTP: Get Response Header", NodeDefinition.NodeCategory.HTTP)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("response", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .input("header_name", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("header_value", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("http_get_headers", "HTTP: Get All Headers", NodeDefinition.NodeCategory.HTTP)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("response", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("headers", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .build());

        registry.register(new NodeDefinition.Builder("http_is_success", "HTTP: Check Success", NodeDefinition.NodeCategory.HTTP)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("response", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("success", NodeDefinition.PinType.DATA, FlowType.BOOLEAN)
            .build());

        registry.register(new NodeDefinition.Builder("http_parse_json", "HTTP: Parse Response JSON", NodeDefinition.NodeCategory.HTTP)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("response", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("json", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .build());

        registry.register(new NodeDefinition.Builder("http_build_query", "HTTP: Build Query String", NodeDefinition.NodeCategory.HTTP)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("params", NodeDefinition.PinType.DATA, FlowType.JSON_OBJECT)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("query_string", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("http_build_url", "HTTP: Build URL", NodeDefinition.NodeCategory.HTTP)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("base_url", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("path", NodeDefinition.PinType.DATA, FlowType.STRING)
            .input("query", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("url", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("http_set_timeout", "HTTP: Set Timeout", NodeDefinition.NodeCategory.HTTP)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("timeout_ms", NodeDefinition.PinType.DATA, FlowType.NUMBER)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .build());

        registry.register(new NodeDefinition.Builder("http_encode_url", "HTTP: Encode URL", NodeDefinition.NodeCategory.HTTP)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("url", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("encoded_url", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());

        registry.register(new NodeDefinition.Builder("http_decode_url", "HTTP: Decode URL", NodeDefinition.NodeCategory.HTTP)
            .input("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .input("url", NodeDefinition.PinType.DATA, FlowType.STRING)
            .output("flow", NodeDefinition.PinType.FLOW, FlowType.EXECUTION)
            .output("decoded_url", NodeDefinition.PinType.DATA, FlowType.STRING)
            .build());
    }
}

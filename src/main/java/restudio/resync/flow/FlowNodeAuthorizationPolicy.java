package restudio.resync.flow;

import restudio.flow.data.FlowNode;
import restudio.resync.flow.registry.NodeDefinition;

import java.util.Map;

@FunctionalInterface
public interface FlowNodeAuthorizationPolicy {
    AuthorizationDecision authorize(FlowContext context, FlowNode node, NodeDefinition definition);

    record AuthorizationDecision(boolean allowed, String code, String message, Map<String, Object> details) {
        public AuthorizationDecision {
            code = code != null && !code.isBlank() ? code : "AUTHORIZATION_DENIED";
            message = message != null ? message : "";
            details = details != null ? Map.copyOf(details) : Map.of();
        }

        public static AuthorizationDecision allow() {
            return new AuthorizationDecision(true, "AUTHORIZED", "", Map.of());
        }

        public static AuthorizationDecision deny(String policy, String nodeId) {
            return new AuthorizationDecision(false, "AUTHORIZATION_DENIED", "Flow capability is not authorized", Map.of(
                "policy", policy != null ? policy : "",
                "node", nodeId != null ? nodeId : ""
            ));
        }
    }
}

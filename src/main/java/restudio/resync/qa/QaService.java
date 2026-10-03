package restudio.resync.qa;

import org.bukkit.command.CommandSender;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.canonical.CanonicalLimits;
import restudio.resync.flow.resource.ResourcePayloadCodecs;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface QaService extends AutoCloseable {
    Map<String, Object> describe();

    Map<String, Object> describe(String operation);

    Map<String, Object> submit(CommandSender actor, String operation, Map<String, Object> input);

    Map<String, Object> poll(CommandSender actor, UUID runId);

    default Map<String, Object> parse(String json) {
        if (json == null) throw new IllegalArgumentException("QA input is required");
        Object value = CanonicalJson.parse(json, CanonicalLimits.standard());
        if (!(value instanceof Map<?, ?> object)) throw new IllegalArgumentException("QA input must be a JSON object");
        return ResourcePayloadCodecs.json().normalize((Map<String, Object>) object);
    }

    default String stringify(Map<String, Object> result) {
        return CanonicalJson.canonicalize(result, CanonicalLimits.standard());
    }

    @Override
    void close();

    @FunctionalInterface
    interface Handler {
        CompletionStage<Map<String, Object>> invoke(CommandSender actor, String operation, Map<String, Object> input);
    }
}

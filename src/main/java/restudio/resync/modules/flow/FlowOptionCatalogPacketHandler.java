package restudio.resync.modules.flow;

import com.google.gson.Gson;
import restudio.resync.api.OptionCatalogItem;
import restudio.resync.api.OptionCatalogProvider;
import restudio.resync.api.OptionCatalogQuery;
import restudio.resync.api.OptionCatalogRegistry;
import restudio.resync.customcontent.CustomContentService;
import restudio.resync.customcontent.ItemAttributeSchemaService;
import restudio.resync.core.Session;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public class FlowOptionCatalogPacketHandler {
    private final FlowPacketSender sender;
    private final OptionCatalogRegistry optionCatalogRegistry;
    private final Gson gson = new Gson();
    private final Map<String, CatalogSnapshot> customContentCatalogSnapshots = new HashMap<>();
    private final AtomicLong catalogSequence = new AtomicLong();

    public FlowOptionCatalogPacketHandler(FlowPacketSender sender, CustomContentService customContentService) {
        this(sender, customContentService, null);
    }

    public FlowOptionCatalogPacketHandler(FlowPacketSender sender, CustomContentService customContentService, OptionCatalogRegistry optionCatalogRegistry) {
        this(sender, optionCatalogRegistry, new BuiltinOptionCatalogService(() -> customContentService, new ItemAttributeSchemaService()));
    }

    public FlowOptionCatalogPacketHandler(FlowPacketSender sender, OptionCatalogRegistry optionCatalogRegistry, BuiltinOptionCatalogService builtinCatalogs) {
        this.sender = sender;
        this.optionCatalogRegistry = optionCatalogRegistry;
        if (optionCatalogRegistry != null && builtinCatalogs != null) {
            builtinCatalogs.registerProviders(optionCatalogRegistry);
        }
    }

    public void handle(Session session, ByteBuffer buffer) {
        if (buffer.remaining() < 4) {
            sender.sendOptionCatalog(session, "", "", List.of(), List.of(), "invalid:source-length", catalogSequence.incrementAndGet(),
                "invalid", "Catalog request is missing the source length");
            return;
        }
        int sourceLength = buffer.getInt();
        if (sourceLength < 0 || sourceLength > FlowPacketSender.MAX_STRING_LENGTH || sourceLength > buffer.remaining()) {
            sender.sendOptionCatalog(session, "", "", List.of(), List.of(), "invalid:source", catalogSequence.incrementAndGet(),
                "invalid", "Catalog request has an invalid source length");
            return;
        }
        byte[] sourceBytes = new byte[sourceLength];
        buffer.get(sourceBytes);
        String sourceId = new String(sourceBytes, StandardCharsets.UTF_8);
        CatalogRequest request;
        try {
            request = readRequest(buffer);
        } catch (IllegalArgumentException exception) {
            sender.sendOptionCatalog(session, sourceId, "", List.of(), List.of(), "invalid:request", catalogSequence.incrementAndGet(),
                "invalid", exception.getMessage());
            return;
        }
        Map<String, Object> queryContext = new LinkedHashMap<>(request.context());
        queryContext.put("$sessionId", session.getSessionId());
        queryContext.put("$clientId", session.getClientId());
        queryContext.put("$clientCapabilities", session.getConnection() != null ? session.getConnection().getClientCapabilities() : Set.of());
        OptionCatalogQuery query = new OptionCatalogQuery(sourceId, queryContext);
        long sequence = catalogSequence.incrementAndGet();
        if (request.version() > 2) {
            sender.sendOptionCatalog(session, sourceId, request.contextKey(), List.of(), List.of(), "incompatible:" + request.version(), sequence,
                "incompatible", "Unsupported catalog request version");
            return;
        }
        OptionCatalogProvider provider = optionCatalogRegistry != null ? optionCatalogRegistry.provider(sourceId) : null;
        if (provider != null) {
            List<OptionCatalogItem> items = optionCatalogRegistry.items(sourceId, query);
            List<String> values = items.stream().map(OptionCatalogItem::value).toList();
            sender.sendOptionCatalog(session, sourceId, request.contextKey(), values, items, provider.revision(query), sequence,
                provider.status(query), provider.diagnostic(query));
            return;
        }
        sender.sendOptionCatalog(session, sourceId, request.contextKey(), List.of(), List.of(), "missing:" + sourceId, sequence, "missing", "Catalog provider is not registered");
    }

    private CatalogRequest readRequest(ByteBuffer buffer) {
        if (buffer.remaining() < Integer.BYTES) {
            throw new IllegalArgumentException("Catalog request is missing its context length");
        }
        int length = buffer.getInt();
        if (length < 0 || length > FlowPacketSender.MAX_STRING_LENGTH || length > buffer.remaining()) {
            throw new IllegalArgumentException("Catalog request has an invalid context length");
        }
        if (length == 0) return CatalogRequest.EMPTY;
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        CatalogRequest request;
        try {
            request = gson.fromJson(new String(bytes, StandardCharsets.UTF_8), CatalogRequest.class);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Catalog request context is invalid JSON", exception);
        }
        if (request == null) throw new IllegalArgumentException("Catalog request context is empty");
        return request.normalized();
    }

    public void broadcastCustomContentCatalogs() {
        for (CatalogSnapshot snapshot : customContentCatalogSnapshots()) {
            customContentCatalogSnapshots.put(snapshot.sourceId(), snapshot);
            snapshot.broadcast(sender, catalogSequence.incrementAndGet());
        }
    }

    public void broadcastChangedCustomContentCatalogs() {
        for (CatalogSnapshot snapshot : customContentCatalogSnapshots()) {
            CatalogSnapshot previous = customContentCatalogSnapshots.get(snapshot.sourceId());
            if (snapshot.equals(previous)) {
                continue;
            }
            customContentCatalogSnapshots.put(snapshot.sourceId(), snapshot);
            snapshot.broadcast(sender, catalogSequence.incrementAndGet());
        }
    }

    public void broadcastCatalog(String sourceId) {
        long sequence = catalogSequence.incrementAndGet();
        OptionCatalogProvider provider = optionCatalogRegistry != null ? optionCatalogRegistry.provider(sourceId) : null;
        if (provider != null) {
            OptionCatalogQuery query = new OptionCatalogQuery(sourceId, Map.of());
            sender.broadcastOptionCatalog(sourceId, optionCatalogRegistry.values(sourceId, query), optionCatalogRegistry.items(sourceId, query), provider.revision(query), sequence, provider.status(query), provider.diagnostic(query));
        } else {
            sender.broadcastOptionCatalog(sourceId, List.of(), List.of(), "missing:" + sourceId, sequence, "missing", "Catalog provider is not registered");
        }
    }

    private List<CatalogSnapshot> customContentCatalogSnapshots() {
        List<CatalogSnapshot> snapshots = new ArrayList<>();
        if (optionCatalogRegistry == null) {
            return snapshots;
        }
        for (OptionCatalogProvider provider : optionCatalogRegistry.providers()) {
            if ("custom_content".equals(provider.providerId())) {
                snapshots.add(customContentCatalogSnapshot(provider.sourceId()));
            }
        }
        return snapshots;
    }

    private CatalogSnapshot customContentCatalogSnapshot(String sourceId) {
        OptionCatalogProvider provider = optionCatalogRegistry != null ? optionCatalogRegistry.provider(sourceId) : null;
        if (provider != null) {
            OptionCatalogQuery query = new OptionCatalogQuery(sourceId, Map.of());
            return new CatalogSnapshot(sourceId, optionCatalogRegistry.values(sourceId, query), optionCatalogRegistry.items(sourceId, query), provider.revision(query), provider.status(query), provider.diagnostic(query));
        }
        return new CatalogSnapshot(sourceId, List.of(), List.of(), "missing:" + sourceId, "missing", "Catalog provider is not registered");
    }

    private record CatalogSnapshot(String sourceId, List<String> values, List<OptionCatalogItem> items, String revision, String status, String diagnostic) {
        CatalogSnapshot {
            sourceId = sourceId != null ? sourceId : "";
            values = values != null ? List.copyOf(values) : List.of();
            items = items != null ? items.stream().map(FlowOptionCatalogPacketHandler::normalizeCatalogItem).toList() : List.of();
            revision = revision != null ? revision : "";
            status = status != null && !status.isBlank() ? status : "available";
            diagnostic = diagnostic != null ? diagnostic : "";
        }

        private void broadcast(FlowPacketSender sender, long sequence) {
            sender.broadcastOptionCatalog(sourceId, values, items, revision, sequence, status, diagnostic);
        }
    }

    private record CatalogRequest(int version, String contextKey, Map<String, Object> context) {
        private static final CatalogRequest EMPTY = new CatalogRequest(1, "", Map.of());

        private CatalogRequest normalized() {
            return new CatalogRequest(Math.max(1, version), contextKey != null ? contextKey : "", context != null ? Collections.unmodifiableMap(new LinkedHashMap<>(context)) : Map.of());
        }
    }

    private static OptionCatalogItem normalizeCatalogItem(OptionCatalogItem item) {
        if (item == null) {
            return new OptionCatalogItem("");
        }
        return new OptionCatalogItem(item.value(), item.label(), item.description(), item.icon(), item.group(), normalizeCatalogMetadata(item.metadata()));
    }

    private static Map<String, Object> normalizeCatalogMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            normalized.put(entry.getKey(), normalizeCatalogMetadataValue(entry.getValue()));
        }
        return normalized;
    }

    private static Object normalizeCatalogMetadataValue(Object value) {
        if (value == null) {
            return null;
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            int length = Array.getLength(value);
            List<Object> normalized = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                normalized.add(normalizeCatalogMetadataValue(Array.get(value, i)));
            }
            return normalized;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(FlowOptionCatalogPacketHandler::normalizeCatalogMetadataValue).toList();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), normalizeCatalogMetadataValue(entry.getValue()));
            }
            return normalized;
        }
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString()).stripTrailingZeros();
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Catalog metadata contains an invalid number: " + number, exception);
            }
        }
        return value;
    }

}

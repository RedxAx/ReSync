package restudio.resync.flow.sync;

import restudio.flow.data.FlowDataType;
import restudio.resync.flow.contract.FlowCategoryMetadata;
import restudio.resync.flow.contract.FlowTypeMetadata;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class NodeRegistrySnapshot {
    private int contractVersion;
    private int minimumClientContractVersion;
    private String serverIdentity = "";
    private long compatibleUntil;
    private List<String> capabilities = new ArrayList<>();
    private Map<String, Object> registryDiagnostics = Map.of();
    private boolean fullSync;
    private String baseRegistryChecksum = "";
    private String registryChecksum;
    private long generatedAt;
    private long catalogGeneration = -1L;
    private String catalogChecksum = "";
    private String catalogProjectionIdentity = "";
    private List<Map<String, Object>> dropContributions = new ArrayList<>();
    private List<Map<String, Object>> functionBoundaries = new ArrayList<>();
    private Map<String, Object> catalogMetadata = Map.of();
    private Map<String, Object> opaqueData = Map.of();
    private List<String> nodeIds = new ArrayList<>();
    private List<NodePluginPayload> plugins = new ArrayList<>();
    private List<String> removedPlugins = new ArrayList<>();
    private Map<String, Map<String, List<String>>> propertyActions;
    private Map<String, Map<String, FlowDataType>> propertyOutputTypes;
    private List<FlowPropertyMetadata> propertyMetadata = new ArrayList<>();
    private List<FlowResourceMetadata> resourceMetadata = new ArrayList<>();
    private List<FlowTypeMetadata> typeMetadata = new ArrayList<>();
    private List<FlowCategoryMetadata> categoryMetadata = new ArrayList<>();
    private List<FlowOptionSourceMetadata> optionSourceMetadata = new ArrayList<>();
    private List<FlowConversionRule> conversionRules = new ArrayList<>();

    public int getContractVersion() {
        return contractVersion;
    }

    public void setContractVersion(int contractVersion) {
        this.contractVersion = contractVersion;
    }

    public int getMinimumClientContractVersion() {
        return minimumClientContractVersion;
    }

    public void setMinimumClientContractVersion(int minimumClientContractVersion) {
        this.minimumClientContractVersion = minimumClientContractVersion;
    }

    public String getServerIdentity() {
        return serverIdentity != null ? serverIdentity : "";
    }

    public void setServerIdentity(String serverIdentity) {
        this.serverIdentity = serverIdentity != null ? serverIdentity : "";
    }

    public long getCompatibleUntil() {
        return compatibleUntil;
    }

    public void setCompatibleUntil(long compatibleUntil) {
        this.compatibleUntil = compatibleUntil;
    }

    public List<String> getCapabilities() {
        return capabilities != null ? capabilities : List.of();
    }

    public void setCapabilities(List<String> capabilities) {
        this.capabilities = capabilities != null ? capabilities : new ArrayList<>();
    }

    public Map<String, Object> getRegistryDiagnostics() {
        return registryDiagnostics != null ? registryDiagnostics : Map.of();
    }

    public void setRegistryDiagnostics(Map<String, Object> registryDiagnostics) {
        this.registryDiagnostics = registryDiagnostics != null ? Map.copyOf(registryDiagnostics) : Map.of();
    }

    public boolean isFullSync() {
        return fullSync;
    }

    public void setFullSync(boolean fullSync) {
        this.fullSync = fullSync;
    }

    public String getBaseRegistryChecksum() {
        return baseRegistryChecksum != null ? baseRegistryChecksum : "";
    }

    public void setBaseRegistryChecksum(String baseRegistryChecksum) {
        this.baseRegistryChecksum = baseRegistryChecksum != null ? baseRegistryChecksum : "";
    }

    public boolean canApplyTo(String currentRegistryChecksum) {
        return fullSync || !getBaseRegistryChecksum().isBlank() && getBaseRegistryChecksum().equals(currentRegistryChecksum);
    }

    public String getRegistryChecksum() {
        return registryChecksum;
    }

    public void setRegistryChecksum(String registryChecksum) {
        this.registryChecksum = registryChecksum;
    }

    public long getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(long generatedAt) {
        this.generatedAt = generatedAt;
    }

    public long getCatalogGeneration() {
        return catalogGeneration;
    }

    public void setCatalogGeneration(long catalogGeneration) {
        this.catalogGeneration = catalogGeneration;
    }

    public String getCatalogChecksum() {
        return catalogChecksum != null ? catalogChecksum : "";
    }

    public void setCatalogChecksum(String catalogChecksum) {
        this.catalogChecksum = catalogChecksum != null ? catalogChecksum : "";
    }

    public String getCatalogProjectionIdentity() {
        return catalogProjectionIdentity != null ? catalogProjectionIdentity : "";
    }

    public void setCatalogProjectionIdentity(String catalogProjectionIdentity) {
        this.catalogProjectionIdentity = catalogProjectionIdentity != null ? catalogProjectionIdentity : "";
    }

    public List<Map<String, Object>> getDropContributions() {
        return dropContributions != null ? dropContributions : List.of();
    }

    public void setDropContributions(List<Map<String, Object>> dropContributions) {
        this.dropContributions = copyEntries(dropContributions);
    }

    public List<Map<String, Object>> getFunctionBoundaries() {
        return functionBoundaries != null ? functionBoundaries : List.of();
    }

    public void setFunctionBoundaries(List<Map<String, Object>> functionBoundaries) {
        this.functionBoundaries = copyEntries(functionBoundaries);
    }

    public List<Map<String, Object>> getFunctionBoundaryIntents() {
        return getFunctionBoundaries();
    }

    public void setFunctionBoundaryIntents(List<Map<String, Object>> functionBoundaryIntents) {
        setFunctionBoundaries(functionBoundaryIntents);
    }

    public Map<String, Object> getCatalogMetadata() {
        return catalogMetadata != null ? catalogMetadata : Map.of();
    }

    public void setCatalogMetadata(Map<String, Object> catalogMetadata) {
        this.catalogMetadata = copyMap(catalogMetadata);
    }

    public Map<String, Object> getOpaqueData() {
        return opaqueData != null ? opaqueData : Map.of();
    }

    public void setOpaqueData(Map<String, Object> opaqueData) {
        this.opaqueData = copyMap(opaqueData);
    }

    public List<String> getNodeIds() {
        return nodeIds != null ? nodeIds : List.of();
    }

    public void setNodeIds(List<String> nodeIds) {
        this.nodeIds = nodeIds != null ? nodeIds : new ArrayList<>();
    }

    public List<NodePluginPayload> getPlugins() {
        return plugins != null ? plugins : List.of();
    }

    public void setPlugins(List<NodePluginPayload> plugins) {
        this.plugins = plugins != null ? plugins : new ArrayList<>();
    }

    public List<String> getRemovedPlugins() {
        return removedPlugins != null ? removedPlugins : List.of();
    }

    public void setRemovedPlugins(List<String> removedPlugins) {
        this.removedPlugins = removedPlugins != null ? removedPlugins : new ArrayList<>();
    }

    public Map<String, Map<String, List<String>>> getPropertyActions() {
        return propertyActions;
    }

    public void setPropertyActions(Map<String, Map<String, List<String>>> propertyActions) {
        this.propertyActions = propertyActions;
    }

    public Map<String, Map<String, FlowDataType>> getPropertyOutputTypes() {
        return propertyOutputTypes;
    }

    public void setPropertyOutputTypes(Map<String, Map<String, FlowDataType>> propertyOutputTypes) {
        this.propertyOutputTypes = propertyOutputTypes;
    }

    public List<FlowPropertyMetadata> getPropertyMetadata() {
        return propertyMetadata != null ? propertyMetadata : List.of();
    }

    public void setPropertyMetadata(List<FlowPropertyMetadata> propertyMetadata) {
        this.propertyMetadata = propertyMetadata != null ? propertyMetadata : new ArrayList<>();
    }

    public List<FlowResourceMetadata> getResourceMetadata() {
        return resourceMetadata != null ? resourceMetadata : List.of();
    }

    public void setResourceMetadata(List<FlowResourceMetadata> resourceMetadata) {
        this.resourceMetadata = resourceMetadata != null ? resourceMetadata : new ArrayList<>();
    }

    public List<FlowTypeMetadata> getTypeMetadata() {
        return typeMetadata != null ? typeMetadata : List.of();
    }

    public void setTypeMetadata(List<FlowTypeMetadata> typeMetadata) {
        this.typeMetadata = typeMetadata != null ? typeMetadata : new ArrayList<>();
        resolveOptionSourceTypes();
    }

    public List<FlowCategoryMetadata> getCategoryMetadata() {
        return categoryMetadata != null ? categoryMetadata : List.of();
    }

    public void setCategoryMetadata(List<FlowCategoryMetadata> categoryMetadata) {
        this.categoryMetadata = categoryMetadata != null ? categoryMetadata : new ArrayList<>();
    }

    public List<FlowOptionSourceMetadata> getOptionSourceMetadata() {
        resolveOptionSourceTypes();
        return optionSourceMetadata != null ? optionSourceMetadata : List.of();
    }

    public void setOptionSourceMetadata(List<FlowOptionSourceMetadata> optionSourceMetadata) {
        this.optionSourceMetadata = optionSourceMetadata != null ? optionSourceMetadata : new ArrayList<>();
        resolveOptionSourceTypes();
    }

    private void resolveOptionSourceTypes() {
        List<FlowOptionSourceMetadata> sources = optionSourceMetadata != null ? optionSourceMetadata : List.of();
        for (FlowOptionSourceMetadata source : sources) {
            if (source == null || source.getId() == null || !"string".equals(source.getValueType())) {
                continue;
            }
            List<String> matchingTypes = getTypeMetadata().stream()
                .filter(type -> type != null && source.getId().equals(type.getCatalogSource()))
                .map(FlowTypeMetadata::getId)
                .filter(typeId -> typeId != null && !typeId.isBlank())
                .distinct()
                .toList();
            if (matchingTypes.size() == 1) {
                source.setValueType(matchingTypes.getFirst());
            }
        }
    }

    public List<FlowConversionRule> getConversionRules() {
        return conversionRules != null ? conversionRules : List.of();
    }

    public void setConversionRules(List<FlowConversionRule> conversionRules) {
        this.conversionRules = conversionRules != null ? conversionRules : new ArrayList<>();
    }

    private static List<Map<String, Object>> copyEntries(List<Map<String, Object>> values) {
        if (values == null || values.isEmpty()) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> copy = new ArrayList<>(values.size());
        for (Map<String, Object> value : values) {
            copy.add(value != null ? copyMap(value) : null);
        }
        return Collections.unmodifiableList(copy);
    }

    private static Map<String, Object> copyMap(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            copy.put(entry.getKey(), copyValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object copyValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("Registry metadata map keys must be strings");
                }
                copy.put(key, copyValue(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Iterable<?> iterable) {
            return Collections.unmodifiableList(java.util.stream.StreamSupport.stream(iterable.spliterator(), false)
                .map(NodeRegistrySnapshot::copyValue).toList());
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> copy = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                copy.add(copyValue(Array.get(value, index)));
            }
            return Collections.unmodifiableList(copy);
        }
        return String.valueOf(value);
    }
}

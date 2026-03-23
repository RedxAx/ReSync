package restudio.resync.world;

public class WorldGeneratorDescriptor {
    private String id;
    private String displayName;
    private boolean builtIn;
    private boolean configurable;
    private String configPlaceholder;
    private String defaultConfig;

    public WorldGeneratorDescriptor copy() {
        WorldGeneratorDescriptor copy = new WorldGeneratorDescriptor();
        copy.id = id;
        copy.displayName = displayName;
        copy.builtIn = builtIn;
        copy.configurable = configurable;
        copy.configPlaceholder = configPlaceholder;
        copy.defaultConfig = defaultConfig;
        return copy;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public boolean isBuiltIn() {
        return builtIn;
    }

    public void setBuiltIn(boolean builtIn) {
        this.builtIn = builtIn;
    }

    public boolean isConfigurable() {
        return configurable;
    }

    public void setConfigurable(boolean configurable) {
        this.configurable = configurable;
    }

    public String getConfigPlaceholder() {
        return configPlaceholder;
    }

    public void setConfigPlaceholder(String configPlaceholder) {
        this.configPlaceholder = configPlaceholder;
    }

    public String getDefaultConfig() {
        return defaultConfig;
    }

    public void setDefaultConfig(String defaultConfig) {
        this.defaultConfig = defaultConfig;
    }
}

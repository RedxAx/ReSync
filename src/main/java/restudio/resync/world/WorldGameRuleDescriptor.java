package restudio.resync.world;

public class WorldGameRuleDescriptor {
    private String name;
    private String type;

    public WorldGameRuleDescriptor copy() {
        WorldGameRuleDescriptor copy = new WorldGameRuleDescriptor();
        copy.name = name;
        copy.type = type;
        return copy;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}

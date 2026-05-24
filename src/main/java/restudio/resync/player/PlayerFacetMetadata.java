package restudio.resync.player;

public class PlayerFacetMetadata {
    private String title;
    private String tabName;
    private int priority;
    private boolean tab;

    public PlayerFacetMetadata() {
    }

    public PlayerFacetMetadata(String title, String tabName, int priority, boolean tab) {
        this.title = title;
        this.tabName = tabName;
        this.priority = priority;
        this.tab = tab;
    }

    public static PlayerFacetMetadata tab(String title, String tabName, int priority) {
        return new PlayerFacetMetadata(title, tabName, priority, true);
    }

    public PlayerFacetMetadata copy() {
        return new PlayerFacetMetadata(title, tabName, priority, tab);
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getTabName() {
        return tabName;
    }

    public void setTabName(String tabName) {
        this.tabName = tabName;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public boolean isTab() {
        return tab;
    }

    public void setTab(boolean tab) {
        this.tab = tab;
    }
}

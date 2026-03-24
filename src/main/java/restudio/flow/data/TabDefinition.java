package restudio.flow.data;

public class TabDefinition {
    private String id;
    private String header;
    private String entryFormat;
    private String footer;

    public TabDefinition() {
        this.entryFormat = "%player%";
    }

    public TabDefinition(String id) {
        this.id = id;
        this.entryFormat = "%player%";
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getHeader() {
        return header;
    }

    public void setHeader(String header) {
        this.header = header;
    }

    public String getEntryFormat() {
        return entryFormat;
    }

    public void setEntryFormat(String entryFormat) {
        this.entryFormat = entryFormat;
    }

    public String getFooter() {
        return footer;
    }

    public void setFooter(String footer) {
        this.footer = footer;
    }

}

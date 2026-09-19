package restudio.resync.metadata;

public record MetadataCoordinate(String edition, String minecraftVersion, Integer dataVersion, Integer protocolVersion,
                                 String softwareFamily, String softwareVersion, String distribution) {
    public MetadataCoordinate {
        edition = MetadataValidation.id(edition, "Metadata edition");
        minecraftVersion = MetadataValidation.optionalText(minecraftVersion, "Minecraft version", 128);
        dataVersion = nonNegative(dataVersion, "Data version");
        protocolVersion = nonNegative(protocolVersion, "Protocol version");
        softwareFamily = MetadataValidation.optionalId(softwareFamily, "Software family");
        softwareVersion = MetadataValidation.optionalText(softwareVersion, "Software version", 128);
        distribution = MetadataValidation.optionalId(distribution, "Software distribution");
        if (softwareVersion != null && softwareFamily == null) {
            throw new IllegalArgumentException("Software version requires a software family");
        }
    }

    private static Integer nonNegative(Integer value, String field) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return value;
    }
}

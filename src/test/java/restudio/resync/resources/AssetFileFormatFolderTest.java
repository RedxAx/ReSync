package restudio.resync.resources;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AssetFileFormatFolderTest {
    @Test
    void stripsAJsonFilePathDownToItsParentFolder() {
        assertEquals("Content/Advancements",
            AssetFileFormat.canonicalFolder("Content/Advancements/asd.json", "Content/Advancements"));
        assertEquals("Content/Advancements",
            AssetFileFormat.canonicalFolder("Content/Advancements/asd.json/asd_copy.json", "Content/Advancements"));
        assertEquals("Content/Advancements/quests",
            AssetFileFormat.canonicalFolder("Content/Advancements/quests", "Content/Advancements"));
        assertEquals("Content/Advancements", AssetFileFormat.canonicalFolder("", "Content/Advancements"));
        assertEquals("Content/Advancements",
            AssetFileFormat.canonicalFolder("Blueprints/Flows/other.json", "Content/Advancements"));
    }
}

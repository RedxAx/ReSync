package restudio.resync.metadata;

import org.junit.jupiter.api.Test;
import restudio.resync.metadata.ServerSoftwareBundle.GameRelease;
import restudio.resync.metadata.ServerSoftwareBundle.SoftwareBuild;
import restudio.resync.metadata.ServerSoftwareBundle.SoftwareCategory;
import restudio.resync.metadata.ServerSoftwareBundle.SoftwareFamily;
import restudio.resync.metadata.ServerSoftwareBundle.SoftwareVersion;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerSoftwareBundleContractTest {
    private static final ServerSoftwareBundleCodec CODEC = new ServerSoftwareBundleCodec();

    @Test
    void writesExactCanonicalServerSoftwareBytes() {
        ServerSoftwareBundle bundle = fixture();
        String expected = "{\"artifactFamily\":\"server_software\",\"categories\":[{\"display\":\"Plugin Servers\","
            + "\"id\":\"plugin\"}],\"createdAt\":\"2026-09-19T12:00:00Z\",\"families\":[{\"categoryIds\":[\"plugin\"],"
            + "\"color\":\"#444444\",\"compatibility\":[\"paper\"],\"compatibleGameReleaseIds\":[\"26.1\"],"
            + "\"deprecated\":false,\"description\":\"Paper server\",\"display\":\"Paper\",\"experimental\":false,"
            + "\"homepage\":\"https://papermc.io/\",\"icon\":\"https://example.com/paper.png\",\"id\":\"paper\",\"versions\":[{"
            + "\"buildCount\":1,\"builds\":[{\"display\":\"Build 15\",\"id\":\"build:15\",\"order\":0}],"
            + "\"createdAt\":\"2026-09-18T09:30:00Z\",\"display\":\"Paper 26.1 Build 15\","
            + "\"gameReleaseId\":\"26.1\",\"id\":\"26.1-R0.1+build.15\",\"latestBuildDisplay\":\"Build 15\","
            + "\"latestBuildId\":\"build:15\",\"order\":0,\"supported\":true}]}],\"formatVersion\":1,\"gameReleases\":[{"
            + "\"display\":\"Minecraft 26.1\",\"id\":\"26.1\",\"order\":0,\"releaseTime\":\"release:26.1\","
            + "\"supported\":true,\"type\":\"release\"}]}";

        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), CODEC.encodeBytes(bundle));
        assertEquals(bundle, CODEC.decodeBytes(expected.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void sortsEveryCatalogWithoutInterpretingFutureReleaseOrVersionSemantics() {
        List<GameRelease> releases = new ArrayList<>(List.of(
            release("27.1"), release("26.3"), release("26.2"), release("26.1")));
        List<SoftwareBuild> builds = new ArrayList<>(List.of(build("2"), build("10")));
        SoftwareVersion version = version("27.1+future.2", "27.1", "2", 2, builds);
        SoftwareFamily paper = family("paper", "Paper", List.of("plugin"), List.of("paper"),
            new ArrayList<>(List.of("27.1", "26.3", "26.2", "26.1")), new ArrayList<>(List.of(version)));
        SoftwareFamily vanilla = family("vanilla", "Vanilla", List.of("official"), List.of(), List.of("27.1"),
            List.of(version("27.1", "27.1", "1", 1, List.of(build("1")))));
        ServerSoftwareBundle bundle = new ServerSoftwareBundle("upstream-created-at-text", releases,
            new ArrayList<>(List.of(new SoftwareCategory("plugin", "Plugin Servers"),
                new SoftwareCategory("official", "Official"))), new ArrayList<>(List.of(vanilla, paper)));

        assertEquals(List.of("26.1", "26.2", "26.3", "27.1"), bundle.gameReleases().stream().map(GameRelease::id).toList());
        assertEquals(List.of("official", "plugin"), bundle.categories().stream().map(SoftwareCategory::id).toList());
        assertEquals(List.of("paper", "vanilla"), bundle.families().stream().map(SoftwareFamily::id).toList());
        assertEquals(List.of("26.1", "26.2", "26.3", "27.1"), paper.compatibleGameReleaseIds());
        assertEquals(List.of("10", "2"), version.builds().stream().map(SoftwareBuild::id).toList());
        assertEquals("27.1+future.2", version.id());
        assertEquals("upstream-created-at-text", bundle.createdAt());
    }

    @Test
    void freezesAllCallerArraysAndPreservesSupportedFlags() {
        List<SoftwareBuild> builds = new ArrayList<>(List.of(build("1")));
        SoftwareVersion version = version("26.1", "26.1", "1", 1, builds, false);
        List<String> compatible = new ArrayList<>(List.of("26.1"));
        List<SoftwareVersion> versions = new ArrayList<>(List.of(version));
        SoftwareFamily family = family("paper", "Paper", List.of("plugin"), List.of("paper"), compatible, versions);
        List<GameRelease> releases = new ArrayList<>(List.of(new GameRelease("26.1", "Minecraft 26.1", "release", "created", false, 0)));
        List<SoftwareCategory> categories = new ArrayList<>(List.of(new SoftwareCategory("plugin", "Plugin Servers")));
        List<SoftwareFamily> families = new ArrayList<>(List.of(family));
        ServerSoftwareBundle bundle = new ServerSoftwareBundle("created", releases, categories, families);

        builds.clear();
        compatible.clear();
        versions.clear();
        releases.clear();
        categories.clear();
        families.clear();

        assertEquals(1, bundle.gameReleases().size());
        assertEquals(1, bundle.categories().size());
        assertEquals(1, bundle.families().size());
        assertEquals(1, family.compatibleGameReleaseIds().size());
        assertEquals(1, family.versions().size());
        assertEquals(1, version.builds().size());
        assertFalse(bundle.gameReleases().getFirst().supported());
        assertFalse(version.supported());
        assertThrows(UnsupportedOperationException.class, () -> version.builds().clear());
    }

    @Test
    void rejectsDuplicateOrIncompleteBuildCatalogs() {
        assertThrows(IllegalArgumentException.class,
            () -> version("26.1", "26.1", "1", 2, List.of(build("1"))));
        assertThrows(IllegalArgumentException.class,
            () -> version("26.1", "26.1", "missing", 1, List.of(build("1"))));
        assertThrows(IllegalArgumentException.class,
            () -> new SoftwareVersion("26.1", "Paper 26.1", "26.1", true, "created", 0, "1", "Wrong", 1,
                List.of(build("1"))));
        assertThrows(IllegalArgumentException.class,
            () -> version("26.1", "26.1", "1", 2, List.of(build("1"), build("1"))));
        assertThrows(IllegalArgumentException.class,
            () -> version("26.1", "26.1", "1", 0, List.of()));
    }

    @Test
    void rejectsEmptyDuplicateOrOrphanCatalogEntriesAndUnsafeIds() {
        GameRelease release = release("26.1");
        SoftwareCategory category = new SoftwareCategory("plugin", "Plugin Servers");
        SoftwareVersion version = version("26.1", "26.1", "1", 1, List.of(build("1")));
        SoftwareFamily family = family("paper", "Paper", List.of("plugin"), List.of("paper"), List.of("26.1"), List.of(version));

        assertThrows(IllegalArgumentException.class, () -> new ServerSoftwareBundle("created", List.of(), List.of(category),
            List.of(family)));
        assertThrows(IllegalArgumentException.class, () -> new ServerSoftwareBundle("created", List.of(release, release),
            List.of(category), List.of(family)));
        assertThrows(IllegalArgumentException.class, () -> new ServerSoftwareBundle("created", List.of(release),
            List.of(category, category), List.of(family)));
        assertThrows(IllegalArgumentException.class, () -> new ServerSoftwareBundle("created", List.of(release),
            List.of(category), List.of(family, family)));
        assertThrows(IllegalArgumentException.class,
            () -> family("paper", "Paper", List.of("plugin"), List.of("paper"), List.of("26.1", "26.1"), List.of(version)));
        assertThrows(IllegalArgumentException.class,
            () -> family("paper", "Paper", List.of("plugin"), List.of("paper"), List.of("26.1"), List.of(version, version)));
        assertThrows(IllegalArgumentException.class, () -> new ServerSoftwareBundle("created", List.of(release),
            List.of(category), List.of(family("paper", "Paper", List.of("missing"), List.of("paper"), List.of("26.1"), List.of(version)))));
        assertThrows(IllegalArgumentException.class, () -> new ServerSoftwareBundle("created", List.of(release),
            List.of(category), List.of(family("paper", "Paper", List.of("plugin"), List.of("paper"), List.of("27.1"), List.of(version)))));
        assertThrows(IllegalArgumentException.class, () -> new ServerSoftwareBundle("created", List.of(release),
            List.of(category), List.of(family("paper", "Paper", List.of("plugin"), List.of("paper"), List.of("26.1"),
                List.of(version("27.1", "27.1", "1", 1, List.of(build("1"))))))));
        assertThrows(IllegalArgumentException.class, () -> new GameRelease("../26.1", "Unsafe", "release", "created", true, 0));
        assertThrows(IllegalArgumentException.class, () -> new SoftwareBuild("build/1", "Unsafe", 0));
        assertThrows(IllegalArgumentException.class,
            () -> family("Paper Server", "Unsafe", List.of("plugin"), List.of("paper"), List.of("26.1"), List.of(version)));
    }

    @Test
    void decoderRejectsUnknownFieldsAndNoncanonicalArrayOrder() {
        ServerSoftwareBundle ordered = new ServerSoftwareBundle("created", List.of(release("26.1"), release("27.1")),
            List.of(new SoftwareCategory("plugin", "Plugin Servers")), List.of(family("paper", "Paper", List.of("plugin"), List.of("paper"),
                List.of("26.1", "27.1"), List.of(version("26.1", "26.1", "1", 1, List.of(build("1")))))));
        String canonical = CODEC.encodeText(ordered);
        String unknown = canonical.substring(0, canonical.length() - 1) + ",\"z\":true}";
        assertThrows(IllegalArgumentException.class, () -> CODEC.decodeText(unknown));
        assertThrows(IllegalArgumentException.class, () -> CODEC.decodeText(canonical + "\n"));

        String first = "{\"display\":\"Minecraft 26.1\",\"id\":\"26.1\",\"order\":0,\"releaseTime\":\"release:26.1\",\"supported\":true,\"type\":\"release\"}";
        String second = "{\"display\":\"Minecraft 27.1\",\"id\":\"27.1\",\"order\":0,\"releaseTime\":\"release:27.1\",\"supported\":true,\"type\":\"release\"}";
        String reversed = canonical.replace(first + "," + second, second + "," + first);
        assertTrue(reversed.contains(second + "," + first));
        assertThrows(IllegalArgumentException.class, () -> CODEC.decodeText(reversed));
    }

    private static ServerSoftwareBundle fixture() {
        SoftwareVersion version = new SoftwareVersion("26.1-R0.1+build.15", "Paper 26.1 Build 15", "26.1", true,
            "2026-09-18T09:30:00Z", 0, "build:15", "Build 15", 1, List.of(new SoftwareBuild("build:15", "Build 15", 0)));
        SoftwareFamily family = family("paper", "Paper", List.of("plugin"), List.of("paper"), List.of("26.1"), List.of(version));
        return new ServerSoftwareBundle("2026-09-19T12:00:00Z",
            List.of(new GameRelease("26.1", "Minecraft 26.1", "release", "release:26.1", true, 0)),
            List.of(new SoftwareCategory("plugin", "Plugin Servers")), List.of(family));
    }

    private static GameRelease release(String id) {
        return new GameRelease(id, "Minecraft " + id, "release", "release:" + id, true, 0);
    }

    private static SoftwareBuild build(String id) {
        return new SoftwareBuild(id, "Build " + id, 0);
    }

    private static SoftwareVersion version(String id, String release, String latestBuild, int count,
                                           List<SoftwareBuild> builds) {
        return version(id, release, latestBuild, count, builds, true);
    }

    private static SoftwareVersion version(String id, String release, String latestBuild, int count,
                                           List<SoftwareBuild> builds, boolean supported) {
        return new SoftwareVersion(id, "Software " + id, release, supported, "created:" + id, 0, latestBuild,
            "Build " + latestBuild, count, builds);
    }

    private static SoftwareFamily family(String id, String display, List<String> categories, List<String> compatibility,
                                         List<String> releases, List<SoftwareVersion> versions) {
        return new SoftwareFamily(id, display, "Paper server", "https://papermc.io/", "https://example.com/paper.png",
            "#444444", false, false, categories, compatibility, releases, versions);
    }
}

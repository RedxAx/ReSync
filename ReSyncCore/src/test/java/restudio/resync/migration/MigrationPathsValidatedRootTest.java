package restudio.resync.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MigrationPathsValidatedRootTest {
    @Test
    void validatesAncestorsAtEachBatchBoundaryAndInspectsChildrenInWholeSetPasses(@TempDir Path temporary)
        throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path present = Files.writeString(root.resolve("present.db"), "content");
        Path absent = root.resolve("absent.db");
        Map<Path, Integer> reads = new HashMap<>();
        List<Path> order = new ArrayList<>();
        MigrationPaths.PathAttributeReader reader = path -> {
            Path normalized = path.toAbsolutePath().normalize();
            reads.merge(normalized, 1, Integer::sum);
            order.add(normalized);
            return Files.readAttributes(normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        };

        MigrationPaths.ValidatedRoot validated = MigrationPaths.requireValidatedRoot(root, "root", reader);
        var inspected = validated.inspectDirectRegularFiles(List.of(present, absent), "authority artifact");

        assertTrue(inspected.getFirst().isPresent());
        assertTrue(inspected.get(1).isEmpty());
        assertEquals(6, reads.get(root.toAbsolutePath().normalize()));
        assertEquals(2, reads.get(present.toAbsolutePath().normalize()));
        assertEquals(2, reads.get(absent.toAbsolutePath().normalize()));
        assertEquals(List.of(present, absent, present, absent), order.stream()
            .filter(path -> path.equals(present) || path.equals(absent))
            .toList());
        Path ancestor = root.toAbsolutePath().normalize().getParent();
        Path current = ancestor.getRoot();
        for (Path part : ancestor) {
            current = current == null ? part : current.resolve(part);
            assertEquals(3, reads.get(current), current.toString());
        }
    }

    @Test
    void acceptsAnAbsentDirectChild(@TempDir Path temporary) throws Exception {
        MigrationPaths.ValidatedRoot root = MigrationPaths.requireValidatedRoot(temporary, "root");

        assertTrue(root.inspectDirectRegularFile(temporary.resolve("missing.db"), "artifact").isEmpty());
    }

    @Test
    void rejectsAChildThatAppearsDuringInspection(@TempDir Path temporary) throws Exception {
        Path artifact = temporary.resolve("appearing.db");
        Path source = Files.writeString(temporary.resolve("source.db"), "content");
        AtomicInteger artifactReads = new AtomicInteger();
        MigrationPaths.PathAttributeReader reader = path -> {
            if (path.toAbsolutePath().normalize().equals(artifact.toAbsolutePath().normalize())
                && artifactReads.getAndIncrement() == 0) {
                throw new NoSuchFileException(path.toString());
            }
            Path inspected = path.toAbsolutePath().normalize().equals(artifact.toAbsolutePath().normalize())
                ? source : path;
            return Files.readAttributes(inspected, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        };
        MigrationPaths.ValidatedRoot root = MigrationPaths.requireValidatedRoot(temporary, "root", reader);

        assertThrows(MigrationException.class, () -> root.inspectDirectRegularFile(artifact, "artifact"));
    }

    @Test
    void rejectsASymbolicLinkArtifact(@TempDir Path temporary) throws Exception {
        Path outside = Files.writeString(temporary.resolve("outside.db"), "outside");
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path artifact = root.resolve("authority.db");
        assumeTrue(createSymbolicLink(artifact, outside));
        MigrationPaths.ValidatedRoot validated = MigrationPaths.requireValidatedRoot(root, "root");

        assertThrows(MigrationException.class,
            () -> validated.inspectDirectRegularFile(artifact, "authority artifact"));
        assertEquals("outside", Files.readString(outside));
    }

    @Test
    void rejectsASymbolicLinkRoot(@TempDir Path temporary) throws Exception {
        Path target = Files.createDirectory(temporary.resolve("target"));
        Path root = temporary.resolve("root");
        assumeTrue(createSymbolicLink(root, target));

        assertThrows(MigrationException.class, () -> MigrationPaths.requireValidatedRoot(root, "root"));
    }

    @Test
    void rejectsASymbolicLinkAncestor(@TempDir Path temporary) throws Exception {
        Path target = Files.createDirectory(temporary.resolve("target"));
        Files.createDirectory(target.resolve("root"));
        Path ancestor = temporary.resolve("linked-parent");
        assumeTrue(createSymbolicLink(ancestor, target));

        assertThrows(MigrationException.class,
            () -> MigrationPaths.requireValidatedRoot(ancestor.resolve("root"), "root"));
    }

    @Test
    void rechecksAncestorsWhenASymlinkRetargetPreservesTheTerminalRoot(@TempDir Path temporary) throws Exception {
        Path probeTarget = Files.createDirectory(temporary.resolve("probe-target"));
        Path probeLink = temporary.resolve("probe-link");
        assumeTrue(createSymbolicLink(probeLink, probeTarget));
        Files.delete(probeLink);
        Path parent = Files.createDirectory(temporary.resolve("parent"));
        Path root = Files.createDirectory(parent.resolve("root"));
        Path moved = temporary.resolve("moved-parent");
        AtomicInteger parentReads = new AtomicInteger();
        MigrationPaths.PathAttributeReader reader = path -> {
            Path normalized = path.toAbsolutePath().normalize();
            if (normalized.equals(parent.toAbsolutePath().normalize()) && parentReads.incrementAndGet() == 2) {
                Files.move(parent, moved);
                Files.createSymbolicLink(parent, moved);
            }
            return Files.readAttributes(normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        };
        MigrationPaths.ValidatedRoot validated = MigrationPaths.requireValidatedRoot(root, "root", reader);

        assertThrows(MigrationException.class,
            () -> validated.inspectDirectRegularFile(root.resolve("missing.db"), "artifact"));
        assertTrue(Files.isDirectory(root));
    }

    private static boolean createSymbolicLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException | IOException exception) {
            return false;
        }
    }
}

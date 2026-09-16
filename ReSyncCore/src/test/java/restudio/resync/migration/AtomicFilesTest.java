package restudio.resync.migration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicFilesTest {
    @Test
    void writesAllBytesWhenTheBackendReturnsShortWrites(@TempDir Path temporary) throws Exception {
        Path target = temporary.resolve("atomic.txt");
        byte[] expected = "short writes remain complete".getBytes(StandardCharsets.UTF_8);
        AtomicInteger calls = new AtomicInteger();

        AtomicFiles.write(target, expected, (channel, buffer) -> {
            calls.incrementAndGet();
            int limit = buffer.limit();
            buffer.limit(Math.min(limit, buffer.position() + 2));
            try {
                return channel.write(buffer);
            } finally {
                buffer.limit(limit);
            }
        });

        assertEquals(new String(expected, StandardCharsets.UTF_8), Files.readString(target));
        assertTrue(calls.get() > 1);
    }

    @Test
    void rejectsAWriteThatMakesNoProgressAndCleansTheTemporaryFile(@TempDir Path temporary) throws Exception {
        Path target = temporary.resolve("atomic.txt");

        assertThrows(IOException.class, () -> AtomicFiles.write(target, new byte[] {1, 2, 3}, (channel, buffer) -> 0));

        assertFalse(Files.exists(target));
        try (var entries = Files.list(temporary)) {
            assertFalse(entries.findAny().isPresent());
        }
    }

    @Test
    void replacesAnExistingFileOnlyAfterCompleteWrite(@TempDir Path temporary) throws Exception {
        Path target = temporary.resolve("atomic.txt");
        Files.writeString(target, "old");

        AtomicFiles.write(target, "new".getBytes(StandardCharsets.UTF_8));

        assertEquals("new", Files.readString(target));
    }

    @Test
    void writeNewNeverReplacesAnExistingFile(@TempDir Path temporary) throws Exception {
        Path target = temporary.resolve("atomic.txt");
        Files.writeString(target, "old");

        assertThrows(FileAlreadyExistsException.class,
            () -> AtomicFiles.writeNew(target, "new".getBytes(StandardCharsets.UTF_8)));

        assertEquals("old", Files.readString(target));
    }

    @Test
    void concurrentWriteNewPublishesExactlyOneCompleteValue(@TempDir Path temporary) throws Exception {
        Path target = temporary.resolve("atomic.txt");
        byte[] expected = "one complete first-write value".getBytes(StandardCharsets.UTF_8);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = submitFirstWrite(executor, ready, start, target, expected);
            Future<Boolean> second = submitFirstWrite(executor, ready, start, target, expected);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            assertEquals(1, (first.get() ? 1 : 0) + (second.get() ? 1 : 0));
            assertArrayEquals(expected, Files.readAllBytes(target));
        } finally {
            executor.shutdownNow();
        }
    }

    private static Future<Boolean> submitFirstWrite(ExecutorService executor, CountDownLatch ready,
                                                     CountDownLatch start, Path target, byte[] expected) {
        return executor.submit(() -> {
            ready.countDown();
            start.await();
            try {
                AtomicFiles.writeNew(target, expected);
                return true;
            } catch (FileAlreadyExistsException exception) {
                return false;
            }
        });
    }

    @Test
    void copiesWithWriteCapableForceAndVerifiesShortWrites(@TempDir Path temporary) throws Exception {
        Path source = temporary.resolve("source.txt");
        Path target = temporary.resolve("target.txt");
        byte[] expected = "durable copied bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(source, expected);
        AtomicReference<Set<OpenOption>> opened = new AtomicReference<>();
        AtomicBoolean forced = new AtomicBoolean();

        AtomicFiles.copy(source, target, (channel, buffer) -> {
            int limit = buffer.limit();
            buffer.limit(Math.min(limit, buffer.position() + 2));
            try {
                return channel.write(buffer);
            } finally {
                buffer.limit(limit);
            }
        }, (path, options) -> {
            opened.set(Set.copyOf(Arrays.asList(options)));
            return FileChannel.open(path, options);
        }, channel -> {
            forced.set(true);
            channel.force(true);
        });

        assertArrayEquals(expected, Files.readAllBytes(target));
        assertTrue(opened.get().contains(StandardOpenOption.WRITE));
        assertTrue(forced.get());
    }

    @Test
    void copiesAReadOnlySourceAfterWritingAndForcingTheTemporaryContent(@TempDir Path temporary) throws Exception {
        Path source = temporary.resolve("source.txt");
        Path target = temporary.resolve("target.txt");
        Files.writeString(source, "read-only source");
        DosFileAttributeView sourceAttributes = Files.getFileAttributeView(source, DosFileAttributeView.class,
            java.nio.file.LinkOption.NOFOLLOW_LINKS);
        Assumptions.assumeTrue(sourceAttributes != null, "DOS attributes are unavailable");
        sourceAttributes.setReadOnly(true);
        try {
            AtomicFiles.copy(source, target);

            assertEquals("read-only source", Files.readString(target));
            DosFileAttributeView targetAttributes = Files.getFileAttributeView(target, DosFileAttributeView.class,
                java.nio.file.LinkOption.NOFOLLOW_LINKS);
            Assumptions.assumeTrue(targetAttributes != null, "DOS attributes are unavailable on the target");
            assertTrue(targetAttributes.readAttributes().isReadOnly());
            targetAttributes.setReadOnly(false);
        } finally {
            sourceAttributes.setReadOnly(false);
            DosFileAttributeView targetAttributes = Files.getFileAttributeView(target, DosFileAttributeView.class,
                java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (targetAttributes != null && Files.exists(target)) {
                targetAttributes.setReadOnly(false);
            }
        }
    }

    @Test
    void rejectsZeroProgressCopyWithoutPublishing(@TempDir Path temporary) throws Exception {
        Path source = temporary.resolve("source.txt");
        Path target = temporary.resolve("target.txt");
        Files.write(source, new byte[] {1, 2, 3});
        AtomicBoolean forced = new AtomicBoolean();

        assertThrows(IOException.class, () -> AtomicFiles.copy(source, target, (channel, buffer) -> 0,
            (path, options) -> FileChannel.open(path, options), channel -> forced.set(true)));

        assertFalse(Files.exists(target));
        assertFalse(forced.get());
        try (var entries = Files.list(temporary)) {
            assertEquals(Set.of(source), entries.collect(Collectors.toSet()));
        }
    }

    @Test
    void forcesTheTargetParentAfterASuccessfulReplacement(@TempDir Path temporary) throws Exception {
        Path target = temporary.resolve("atomic.txt");
        AtomicReference<Path> forcedDirectory = new AtomicReference<>();

        AtomicFiles.write(target, "durable".getBytes(StandardCharsets.UTF_8), FileChannel::write,
            (path, options) -> FileChannel.open(path, options), channel -> channel.force(true),
            forcedDirectory::set, (source, destination, options) -> Files.move(source, destination, options));

        assertEquals(target.getParent().toAbsolutePath().normalize(), forcedDirectory.get());
        assertEquals("durable", Files.readString(target));
    }

    @Test
    void doesNotDowngradeAnArbitraryAtomicMoveFailure(@TempDir Path temporary) throws Exception {
        Path target = temporary.resolve("atomic.txt");
        Files.writeString(target, "old");
        AtomicInteger moveCalls = new AtomicInteger();

        assertThrows(IOException.class, () -> AtomicFiles.write(target, "new".getBytes(StandardCharsets.UTF_8),
            FileChannel::write, (path, options) -> FileChannel.open(path, options), channel -> channel.force(true),
            ignored -> { }, (source, destination, options) -> {
                moveCalls.incrementAndGet();
                throw new IOException("arbitrary move failure");
            }));

        assertEquals(1, moveCalls.get());
        assertEquals("old", Files.readString(target));
    }

    @Test
    void preservesAnAtomicMoveUnsupportedFailureAndDoesNotPublish(@TempDir Path temporary) throws Exception {
        Path target = temporary.resolve("atomic.txt");
        Files.writeString(target, "old");

        assertThrows(MigrationException.class, () -> AtomicFiles.write(target, "new".getBytes(StandardCharsets.UTF_8),
            FileChannel::write, (path, options) -> FileChannel.open(path, options), channel -> channel.force(true),
            ignored -> { }, (source, destination, options) -> {
                throw new AtomicMoveNotSupportedException(source.toString(), destination.toString(), "unsupported");
            }));

        assertEquals("old", Files.readString(target));
        try (var entries = Files.list(temporary)) {
            assertTrue(entries.anyMatch(path -> path.getFileName().toString().startsWith(".resync-")));
        }
    }

    @Test
    void rejectsASymbolicLinkTargetWithoutFollowingIt(@TempDir Path temporary) throws Exception {
        Path outside = temporary.resolve("outside.txt");
        Path target = temporary.resolve("atomic.txt");
        Files.writeString(outside, "outside");
        try {
            Files.createSymbolicLink(target, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable");
            return;
        }

        assertThrows(IllegalArgumentException.class,
            () -> AtomicFiles.write(target, "inside".getBytes(StandardCharsets.UTF_8)));
        assertEquals("outside", Files.readString(outside));
    }
}

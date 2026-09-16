package restudio.resync.filesystem.windows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WindowsFileMutationContractTest {
    private static final byte[] FILE_ID = {
        1, 2, 3, 4, 5, 6, 7, 8,
        9, 10, 11, 12, 13, 14, 15, 16
    };

    @Test
    void observeDelegatesAndMutationsNormalizePaths() throws IOException {
        FakeBackend backend = new FakeBackend();
        Path requested = Path.of(".").resolve("mutation").resolve("..").resolve("mutation.txt");
        Path normalized = requested.toAbsolutePath().normalize();
        backend.entries.put(normalized, FakeBackend.Entry.fileEntry());

        WindowsFileMutation mutation = new WindowsFileMutation(backend);
        assertEquals(normalized, mutation.observe(requested).path());
        mutation.rename(requested, normalized.resolveSibling("renamed.txt"));
        assertTrue(backend.entries.containsKey(normalized.resolveSibling("renamed.txt")));
        mutation.deleteFile(normalized.resolveSibling("renamed.txt"));
        assertFalse(backend.entries.containsKey(normalized.resolveSibling("renamed.txt")));
        assertEquals(2, backend.flushes);
    }

    @Test
    void renameFailsWhenDestinationAppearsAfterTheInitialCheck() {
        FakeBackend backend = new FakeBackend();
        Path root = Path.of("mutation-race").toAbsolutePath().normalize();
        Path source = root.resolve("source");
        Path destination = root.resolve("destination");
        backend.entries.put(source, FakeBackend.Entry.fileEntry());
        backend.destinationAppearsDuringRename = true;

        WindowsFileMutation mutation = new WindowsFileMutation(backend);

        assertThrows(IOException.class, () -> mutation.rename(source, destination));
        assertTrue(backend.entries.containsKey(source));
        assertTrue(backend.entries.containsKey(destination));
    }

    @Test
    void deleteTreeFailsClosedOnReparseEntry() {
        FakeBackend backend = new FakeBackend();
        Path root = Path.of("mutation-tree").toAbsolutePath().normalize();
        backend.entries.put(root, FakeBackend.Entry.directoryEntry());
        backend.entries.put(root.resolve("link"), FakeBackend.Entry.reparseEntry());

        WindowsFileMutation mutation = new WindowsFileMutation(backend);

        assertThrows(IOException.class, () -> mutation.deleteTree(root));
        assertTrue(backend.entries.containsKey(root));
        assertTrue(backend.entries.containsKey(root.resolve("link")));
    }

    @Test
    void realWindowsRenameDeleteAndFlushAreHandleSafe(@TempDir Path temp) throws Exception {
        WindowsFileMutation mutation = new WindowsFileMutation();
        assumeTrue(mutation.available());
        Path source = Files.writeString(temp.resolve("source.txt"), "source");
        Path destination = temp.resolve("destination.txt");

        mutation.rename(source, destination);
        assertFalse(Files.exists(source));
        assertEquals("source", Files.readString(destination));
        mutation.flush(destination);
        mutation.flushParent(destination);
        mutation.deleteFile(destination);
        assertFalse(Files.exists(destination));
    }

    @Test
    void realWindowsTreeDeleteIsHandleSafe(@TempDir Path temp) throws Exception {
        WindowsFileMutation mutation = new WindowsFileMutation();
        assumeTrue(mutation.available());
        Path tree = Files.createDirectories(temp.resolve("tree"));
        Files.writeString(Files.createDirectories(tree.resolve("nested")).resolve("file.txt"), "tree");

        mutation.deleteTree(tree);

        assertFalse(Files.exists(tree));
    }

    @Test
    void realWindowsRenameCannotFollowAncestorRebind(@TempDir Path temp) throws Exception {
        WindowsMutationNative backend = new WindowsMutationNative();
        WindowsFileMutation mutation = new WindowsFileMutation(backend);
        assumeTrue(mutation.available());
        Path ancestor = Files.createDirectories(temp.resolve("ancestor"));
        Path destinationParent = Files.createDirectories(ancestor.resolve("destination"));
        Path source = Files.writeString(temp.resolve("source.txt"), "source");
        Path destination = destinationParent.resolve("renamed.txt");
        Path movedAncestor = temp.resolve("ancestor-old");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        NativeRenameBarrier barrier = new NativeRenameBarrier(backend);
        Future<?> operation = null;
        boolean rebound = false;
        try {
            barrier.install();
            operation = executor.submit(() -> {
                mutation.rename(source, destination);
                return null;
            });
            assertTrue(barrier.entered.await(10, TimeUnit.SECONDS));
            try {
                Files.move(ancestor, movedAncestor);
                Files.createDirectory(ancestor);
                rebound = true;
            } catch (IOException ignored) {
            }
            barrier.release();
            try {
                operation.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException exception) {
                assertTrue(exception.getCause() instanceof IOException,
                    () -> "Unexpected native rename failure: " + exception.getCause());
            }
            Path heldDestination = (rebound ? movedAncestor : ancestor).resolve("destination").resolve("renamed.txt");
            Path redirectedDestination = (rebound ? ancestor : movedAncestor).resolve("destination")
                .resolve("renamed.txt");
            assertTrue(Files.exists(heldDestination));
            assertFalse(Files.exists(redirectedDestination));
        } finally {
            barrier.release();
            if (operation != null) {
                try {
                    operation.get(10, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
            }
            barrier.close();
            executor.shutdownNow();
        }
    }

    private static final class NativeRenameBarrier implements AutoCloseable {
        private final WindowsMutationNative backend;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private Field kernelField;
        private Object originalKernel;

        private NativeRenameBarrier(WindowsMutationNative backend) {
            this.backend = backend;
        }

        private void install() throws Exception {
            Method kernelMethod = WindowsMutationNative.class.getDeclaredMethod("kernel");
            kernelMethod.setAccessible(true);
            originalKernel = kernelMethod.invoke(backend);
            Class<?> kernelType = Class.forName(WindowsMutationNative.class.getName() + "$Kernel32");
            InvocationHandler handler = (proxy, method, arguments) -> {
                if (method.getName().equals("SetFileInformationByHandle")) {
                    entered.countDown();
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }
                method.setAccessible(true);
                try {
                    return method.invoke(originalKernel, arguments);
                } catch (InvocationTargetException exception) {
                    throw exception.getCause();
                }
            };
            Object proxy = Proxy.newProxyInstance(kernelType.getClassLoader(), new Class<?>[]{kernelType}, handler);
            kernelField = WindowsMutationNative.class.getDeclaredField("kernel32");
            kernelField.setAccessible(true);
            kernelField.set(backend, proxy);
        }

        private void release() {
            release.countDown();
        }

        @Override
        public void close() throws IllegalAccessException {
            if (kernelField != null) {
                kernelField.set(backend, originalKernel);
            }
        }
    }

    private static final class FakeBackend implements WindowsFileMutation.Backend {
        private final Map<Path, Entry> entries = new HashMap<>();
        private boolean destinationAppearsDuringRename;
        private int flushes;

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public WindowsFileIdentity.Observation observe(Path path) throws IOException {
            Entry entry = entries.get(path);
            if (entry == null) {
                throw new IOException("missing " + path);
            }
            return new WindowsFileIdentity.Observation(path, new WindowsFileIdentity.FileIdInfo(1, FILE_ID, false),
                entry.directory, !entry.directory && !entry.reparse, false, entry.reparse, entry.reparse ? 1 : 0);
        }

        @Override
        public void flush(Path path) {
            flushes++;
        }

        @Override
        public void rename(Path source, Path destination) throws IOException {
            if (destinationAppearsDuringRename) {
                entries.put(destination, Entry.fileEntry());
            }
            if (entries.containsKey(destination)) {
                throw new IOException("destination appeared");
            }
            Entry entry = entries.remove(source);
            if (entry == null) {
                throw new IOException("source missing");
            }
            entries.put(destination, entry);
        }

        @Override
        public void deleteFile(Path path) throws IOException {
            Entry entry = entries.get(path);
            if (entry == null) {
                return;
            }
            if (entry.directory || entry.reparse) {
                throw new IOException("unsafe file");
            }
            entries.remove(path);
            flush(path);
            flush(path.getParent());
        }

        @Override
        public void deleteTree(Path path) throws IOException {
            Entry root = entries.get(path);
            if (root == null) {
                return;
            }
            if (!root.directory || root.reparse) {
                throw new IOException("unsafe tree root");
            }
            for (Map.Entry<Path, Entry> entry : entries.entrySet()) {
                if (!entry.getKey().equals(path) && entry.getKey().startsWith(path) && entry.getValue().reparse) {
                    throw new IOException("unsafe tree entry");
                }
            }
            entries.keySet().removeIf(candidate -> candidate.equals(path) || candidate.startsWith(path));
        }

        private record Entry(boolean directory, boolean reparse) {
            private static Entry fileEntry() {
                return new Entry(false, false);
            }

            private static Entry directoryEntry() {
                return new Entry(true, false);
            }

            private static Entry reparseEntry() {
                return new Entry(false, true);
            }
        }
    }
}

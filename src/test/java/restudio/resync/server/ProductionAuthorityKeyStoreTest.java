package restudio.resync.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionAuthorityKeyStoreTest {
    @Test
    void concurrentCreationUsesTheDurableWinner(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("data"));
        ServerIdentityStore identity = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        List<ProductionAuthorityKeyStore> stores = IntStream.range(0, 16)
            .mapToObj(unused -> new ProductionAuthorityKeyStore(identity))
            .toList();
        ExecutorService executor = Executors.newFixedThreadPool(stores.size());
        CyclicBarrier barrier = new CyclicBarrier(stores.size());
        try {
            List<Future<String>> results = stores.stream()
                .map(store -> executor.submit(() -> {
                    barrier.await();
                    return store.signingPublicKey();
                }))
                .toList();
            Set<String> publicKeys = results.stream()
                .map(result -> get(result))
                .collect(Collectors.toSet());
            assertEquals(1, publicKeys.size());
            assertTrue(publicKeys.stream().findFirst().orElseThrow().length() > 20);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void overlappingFailureReachesEveryWaiterAndCorrectedRetrySucceeds(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("data"));
        ServerIdentityStore identity = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        ProductionAuthorityKeyStore.DirectoryForce directoryForce = path ->
            ProductionAuthorityKeyStore.DirectoryForceOutcome.SUPPORTED;
        FailingInitialLoadObserver observer = new FailingInitialLoadObserver();
        ProductionAuthorityKeyStore leader = new ProductionAuthorityKeyStore(identity, directoryForce, observer);
        ProductionAuthorityKeyStore waiter = new ProductionAuthorityKeyStore(identity, directoryForce, observer);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> leaderResult = executor.submit(leader::signingPublicKey);
            assertTrue(observer.leaderStarted.await(10, TimeUnit.SECONDS));
            Future<String> waiterResult = executor.submit(waiter::signingPublicKey);
            assertTrue(observer.followerJoined.await(10, TimeUnit.SECONDS));
            observer.releaseLeader.countDown();

            Throwable leaderFailure = failure(leaderResult);
            Throwable waiterFailure = failure(waiterResult);
            assertSame(leaderFailure, waiterFailure);
            assertEquals("Shared Initial Authority Failure", leaderFailure.getMessage());
            assertFalse(leaderFailure.toString().contains(leader.secretRoot().toString()));
            assertFalse(leaderFailure.toString().contains("private-key="));
            assertTrue(observer.leaderFinished.await(10, TimeUnit.SECONDS));
            assertFalse(Files.exists(leader.secretRoot(), LinkOption.NOFOLLOW_LINKS));

            String corrected = leader.signingPublicKey();
            assertTrue(corrected.length() > 20);
            assertEquals(2, observer.leaderAttempts.get());
        } finally {
            observer.releaseLeader.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void differentIdentityObjectsDoNotShareInitialLoads(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("data"));
        Path identityPath = dataRoot.resolve(ServerIdentityStore.FILE_NAME);
        ServerIdentityStore firstIdentity = ServerIdentityStore.open(identityPath);
        ServerIdentityStore secondIdentity = ServerIdentityStore.open(identityPath);
        assertEquals(firstIdentity.serverId(), secondIdentity.serverId());
        ProductionAuthorityKeyStore.DirectoryForce directoryForce = path ->
            ProductionAuthorityKeyStore.DirectoryForceOutcome.SUPPORTED;
        TwoLeaderObserver observer = new TwoLeaderObserver();
        ProductionAuthorityKeyStore first = new ProductionAuthorityKeyStore(firstIdentity, directoryForce, observer);
        ProductionAuthorityKeyStore second = new ProductionAuthorityKeyStore(secondIdentity, directoryForce, observer);

        List<String> keys = loadConcurrently(first, second);

        assertEquals(keys.get(0), keys.get(1));
        assertEquals(2, observer.leaders.get());
        assertEquals(0, observer.followers.get());
    }

    @Test
    void differentRootsAndServersDoNotShareInitialLoads(@TempDir Path temporary) throws Exception {
        Path firstRoot = Files.createDirectory(temporary.resolve("first"));
        Path secondRoot = Files.createDirectory(temporary.resolve("second"));
        ServerIdentityStore firstIdentity = ServerIdentityStore.open(firstRoot.resolve(ServerIdentityStore.FILE_NAME));
        ServerIdentityStore secondIdentity = ServerIdentityStore.open(secondRoot.resolve(ServerIdentityStore.FILE_NAME));
        assertNotEquals(firstIdentity.serverId(), secondIdentity.serverId());
        ProductionAuthorityKeyStore.DirectoryForce directoryForce = path ->
            ProductionAuthorityKeyStore.DirectoryForceOutcome.SUPPORTED;
        TwoLeaderObserver observer = new TwoLeaderObserver();
        ProductionAuthorityKeyStore first = new ProductionAuthorityKeyStore(firstIdentity, directoryForce, observer);
        ProductionAuthorityKeyStore second = new ProductionAuthorityKeyStore(secondIdentity, directoryForce, observer);

        List<String> keys = loadConcurrently(first, second);

        assertNotEquals(keys.get(0), keys.get(1));
        assertEquals(2, observer.leaders.get());
        assertEquals(0, observer.followers.get());
    }

    @Test
    void differentDirectoryForceCapabilitiesDoNotShareInitialLoads(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("data"));
        ServerIdentityStore identity = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        ProductionAuthorityKeyStore.DirectoryForce firstForce = new SupportedDirectoryForce();
        ProductionAuthorityKeyStore.DirectoryForce secondForce = new SupportedDirectoryForce();
        assertNotSame(firstForce, secondForce);
        TwoLeaderObserver observer = new TwoLeaderObserver();
        ProductionAuthorityKeyStore first = new ProductionAuthorityKeyStore(identity, firstForce, observer);
        ProductionAuthorityKeyStore second = new ProductionAuthorityKeyStore(identity, secondForce, observer);

        List<String> keys = loadConcurrently(first, second);

        assertEquals(keys.get(0), keys.get(1));
        assertEquals(2, observer.leaders.get());
        assertEquals(0, observer.followers.get());
    }

    @Test
    void generatedKeyIsStoredWithRestrictiveProtection(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("data"));
        ServerIdentityStore identity = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        ProductionAuthorityKeyStore store = new ProductionAuthorityKeyStore(identity);
        String publicKey = store.signingPublicKey();
        Path keyPath = store.privateKeyPath();
        Path anchorPath = store.trustAnchorPath();

        assertTrue(Files.isRegularFile(keyPath, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isRegularFile(anchorPath, LinkOption.NOFOLLOW_LINKS));
        String privateContent = Files.readString(keyPath);
        String anchorContent = Files.readString(anchorPath);
        assertTrue(privateContent.contains("private-key="));
        assertFalse(privateContent.contains("public-key="));
        assertFalse(anchorContent.contains("private-key="));
        assertFalse(anchorContent.contains("privateKey"));
        assertTrue(publicKey.length() > 20);
        PosixFileAttributeView posix = Files.getFileAttributeView(keyPath, PosixFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                posix.readAttributes().permissions());
        } else {
            AclFileAttributeView acl = Files.getFileAttributeView(keyPath, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
            assertNotNull(acl);
            assertEquals(1, acl.getAcl().size());
            assertEquals(EnumSet.allOf(AclEntryPermission.class), acl.getAcl().getFirst().permissions());
        }
    }

    @Test
    void keyReadFailuresDoNotExposeThePrivateKey(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("data"));
        ServerIdentityStore identity = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        ProductionAuthorityKeyStore store = new ProductionAuthorityKeyStore(identity);
        store.signingPublicKey();
        Path keyPath = store.privateKeyPath();
        String privateKey = Files.readString(keyPath).lines()
            .filter(line -> line.startsWith("private-key="))
            .map(line -> line.substring("private-key=".length()))
            .findFirst()
            .orElseThrow();
        Files.writeString(keyPath, "format=2\nprivate-key=" + privateKey
            + "\nkey-hash=invalid\n");

        IOException failure = assertThrows(IOException.class,
            () -> new ProductionAuthorityKeyStore(identity).signingPublicKey());
        assertFalse(failure.getMessage() != null && failure.getMessage().contains(privateKey));
    }

    @Test
    void retiredCombinedKeyFailsClosed(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("data"));
        ServerIdentityStore identity = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        ProductionAuthorityKeyStore store = new ProductionAuthorityKeyStore(identity);
        String privateKey = "private-material-must-not-be-reported";
        Files.writeString(store.legacyCombinedKeyPath(), "format=1\npublic-key=public\nprivate-key=" + privateKey + "\n");

        IOException failure = assertThrows(IOException.class, store::signingPublicKey);
        assertFalse(failure.getMessage() != null && failure.getMessage().contains(privateKey));
        assertFalse(Files.exists(store.privateKeyPath(), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void canonicalTemporaryKeyIsPromotedAgainstItsTrustAnchor(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("data"));
        ServerIdentityStore identity = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        ProductionAuthorityKeyStore store = supportedStore(identity);
        String expectedPublicKey = store.signingPublicKey();
        byte[] privateKey = Files.readAllBytes(store.privateKeyPath());
        Files.delete(store.privateKeyPath());
        Path temporaryKey = store.secretRoot().resolve(store.privateKeyPath().getFileName() + "."
            + "00000000-0000-0000-0000-000000000001" + ProductionAuthorityKeyStore.PRIVATE_KEY_TEMP_SUFFIX);
        Files.write(temporaryKey, privateKey);

        String recoveredPublicKey = supportedStore(identity).signingPublicKey();

        assertEquals(expectedPublicKey, recoveredPublicKey);
        assertTrue(Files.isRegularFile(store.privateKeyPath(), LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(temporaryKey, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void canonicalTemporaryKeyWithoutTrustAnchorFailsClosed(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("data"));
        ServerIdentityStore identity = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        ProductionAuthorityKeyStore store = supportedStore(identity);
        store.signingPublicKey();
        byte[] privateKey = Files.readAllBytes(store.privateKeyPath());
        Files.delete(store.privateKeyPath());
        Files.delete(store.trustAnchorPath());
        Path temporaryKey = store.secretRoot().resolve(store.privateKeyPath().getFileName() + "."
            + "00000000-0000-0000-0000-000000000002" + ProductionAuthorityKeyStore.PRIVATE_KEY_TEMP_SUFFIX);
        Files.write(temporaryKey, privateKey);

        assertThrows(IOException.class, () -> supportedStore(identity).signingPublicKey());

        assertTrue(Files.exists(temporaryKey, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(store.privateKeyPath(), LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(store.trustAnchorPath(), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void canonicalTemporaryTrustAnchorIsPromotedAgainstItsSigningKey(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("data"));
        ServerIdentityStore identity = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        ProductionAuthorityKeyStore store = supportedStore(identity);
        String expectedPublicKey = store.signingPublicKey();
        byte[] anchorBytes = Files.readAllBytes(store.trustAnchorPath());
        Files.delete(store.trustAnchorPath());
        Path temporaryAnchor = store.trustAnchorPath().getParent().resolve(
            ProductionAuthorityKeyStore.TRUST_ANCHOR_FILE + "."
                + "00000000-0000-0000-0000-000000000004"
                + ProductionAuthorityKeyStore.TRUST_ANCHOR_TEMP_SUFFIX);
        Files.write(temporaryAnchor, anchorBytes);

        String recoveredPublicKey = supportedStore(identity).signingPublicKey();

        assertEquals(expectedPublicKey, recoveredPublicKey);
        assertTrue(Files.isRegularFile(store.trustAnchorPath(), LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(temporaryAnchor, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void staleTemporaryKeyIsQuarantinedWhenAuthorityExists(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("data"));
        ServerIdentityStore identity = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        ProductionAuthorityKeyStore store = supportedStore(identity);
        store.signingPublicKey();
        Path temporaryKey = store.secretRoot().resolve(store.privateKeyPath().getFileName() + "."
            + "00000000-0000-0000-0000-000000000003" + ProductionAuthorityKeyStore.PRIVATE_KEY_TEMP_SUFFIX);
        Files.write(temporaryKey, Files.readAllBytes(store.privateKeyPath()));

        supportedStore(identity).signingPublicKey();

        assertFalse(Files.exists(temporaryKey, LinkOption.NOFOLLOW_LINKS));
        Path quarantine = store.secretRoot().resolve(ProductionAuthorityKeyStore.QUARANTINE_DIRECTORY)
            .resolve(ProductionAuthorityKeyStore.QUARANTINE_CATEGORY);
        assertTrue(Files.isDirectory(quarantine, LinkOption.NOFOLLOW_LINKS));
        try (var entries = Files.list(quarantine)) {
            assertEquals(1, entries.count());
        }
    }

    @Test
    void unknownSecretArtifactFailsClosed(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("data"));
        ServerIdentityStore identity = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        ProductionAuthorityKeyStore store = new ProductionAuthorityKeyStore(identity);
        Files.createDirectories(store.secretRoot());
        Files.writeString(store.secretRoot().resolve("authority-key-unknown.tmp"), "unknown");

        assertThrows(IOException.class, () -> store.signingPublicKey());
        assertTrue(Files.exists(store.secretRoot().resolve("authority-key-unknown.tmp"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void peerScopedCanonicalKeyRemainsProtectedAndUntouched(@TempDir Path temporary) throws Exception {
        Path firstRoot = Files.createDirectory(temporary.resolve("first"));
        Path secondRoot = Files.createDirectory(temporary.resolve("second"));
        ServerIdentityStore firstIdentity = ServerIdentityStore.open(firstRoot.resolve(ServerIdentityStore.FILE_NAME));
        ServerIdentityStore secondIdentity = ServerIdentityStore.open(secondRoot.resolve(ServerIdentityStore.FILE_NAME));
        ProductionAuthorityKeyStore first = supportedStore(firstIdentity);
        ProductionAuthorityKeyStore second = supportedStore(secondIdentity);
        String firstPublicKey = first.signingPublicKey();
        Path peerKey = first.privateKeyPath();
        byte[] peerBytes = Files.readAllBytes(peerKey);
        FileTime peerModified = Files.getLastModifiedTime(peerKey, LinkOption.NOFOLLOW_LINKS);
        Object peerProtection = protection(peerKey);

        String secondPublicKey = second.signingPublicKey();

        assertEquals(first.secretRoot(), second.secretRoot());
        assertNotEquals(first.privateKeyPath(), second.privateKeyPath());
        assertNotEquals(firstPublicKey, secondPublicKey);
        assertTrue(Files.isRegularFile(second.privateKeyPath(), LinkOption.NOFOLLOW_LINKS));
        assertArrayEquals(peerBytes, Files.readAllBytes(peerKey));
        assertEquals(peerModified, Files.getLastModifiedTime(peerKey, LinkOption.NOFOLLOW_LINKS));
        assertEquals(peerProtection, protection(peerKey));
        assertFalse(Files.exists(first.secretRoot().resolve(ProductionAuthorityKeyStore.QUARANTINE_DIRECTORY),
            LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void peerScopedCanonicalTemporaryKeyRemainsProtectedAndUntouched(@TempDir Path temporary) throws Exception {
        Path firstRoot = Files.createDirectory(temporary.resolve("first"));
        Path secondRoot = Files.createDirectory(temporary.resolve("second"));
        ServerIdentityStore firstIdentity = ServerIdentityStore.open(firstRoot.resolve(ServerIdentityStore.FILE_NAME));
        ServerIdentityStore secondIdentity = ServerIdentityStore.open(secondRoot.resolve(ServerIdentityStore.FILE_NAME));
        ProductionAuthorityKeyStore.DirectoryForce unsupported = path ->
            ProductionAuthorityKeyStore.DirectoryForceOutcome.UNSUPPORTED;
        ProductionAuthorityKeyStore first = new ProductionAuthorityKeyStore(firstIdentity, unsupported);
        String firstPublicKey = first.signingPublicKey();
        Path peerTemporary;
        try (var entries = Files.list(first.secretRoot())) {
            peerTemporary = entries.filter(path -> path.getFileName().toString()
                    .startsWith(first.privateKeyPath().getFileName() + ".")
                    && path.getFileName().toString().endsWith(ProductionAuthorityKeyStore.PRIVATE_KEY_TEMP_SUFFIX))
                .findFirst()
                .orElseThrow();
        }
        byte[] peerBytes = Files.readAllBytes(peerTemporary);
        FileTime peerModified = Files.getLastModifiedTime(peerTemporary, LinkOption.NOFOLLOW_LINKS);
        Object peerProtection = protection(peerTemporary);

        ProductionAuthorityKeyStore second = supportedStore(secondIdentity);
        String secondPublicKey = second.signingPublicKey();

        assertEquals(first.secretRoot(), second.secretRoot());
        assertNotEquals(first.privateKeyPath(), second.privateKeyPath());
        assertNotEquals(firstPublicKey, secondPublicKey);
        assertTrue(Files.isRegularFile(peerTemporary, LinkOption.NOFOLLOW_LINKS));
        assertArrayEquals(peerBytes, Files.readAllBytes(peerTemporary));
        assertEquals(peerModified, Files.getLastModifiedTime(peerTemporary, LinkOption.NOFOLLOW_LINKS));
        assertEquals(peerProtection, protection(peerTemporary));
        assertFalse(Files.exists(first.secretRoot().resolve(ProductionAuthorityKeyStore.QUARANTINE_DIRECTORY),
            LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void cachedKeyRejectsAnExternalReplacement(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("data"));
        ServerIdentityStore identity = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        ProductionAuthorityKeyStore store = new ProductionAuthorityKeyStore(identity);
        store.signingPublicKey();
        Files.writeString(store.privateKeyPath(), "changed");

        assertThrows(IOException.class, () -> store.sign(new byte[] {1, 2, 3}));
    }

    @Test
    void unsupportedDirectoryForceRetainsPublicationEvidence(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("data"));
        ServerIdentityStore identity = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        ProductionAuthorityKeyStore.DirectoryForce unsupported = path ->
            ProductionAuthorityKeyStore.DirectoryForceOutcome.UNSUPPORTED;
        ProductionAuthorityKeyStore store = new ProductionAuthorityKeyStore(identity, unsupported);

        String expectedPublicKey = store.signingPublicKey();
        List<Path> temporaryFiles;
        try (var entries = Files.list(store.secretRoot())) {
            temporaryFiles = entries.filter(path -> path.getFileName().toString()
                    .endsWith(ProductionAuthorityKeyStore.PRIVATE_KEY_TEMP_SUFFIX))
                .toList();
        }
        List<Path> anchorTemporaryFiles;
        try (var entries = Files.list(store.trustAnchorPath().getParent())) {
            anchorTemporaryFiles = entries.filter(path -> path.getFileName().toString()
                    .startsWith(ProductionAuthorityKeyStore.TRUST_ANCHOR_FILE + ".")
                    && path.getFileName().toString().endsWith(ProductionAuthorityKeyStore.TRUST_ANCHOR_TEMP_SUFFIX))
                .toList();
        }

        assertEquals(1, temporaryFiles.size());
        assertEquals(1, anchorTemporaryFiles.size());
        assertEquals(expectedPublicKey, new ProductionAuthorityKeyStore(identity, unsupported).signingPublicKey());
        assertTrue(Files.exists(temporaryFiles.getFirst(), LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.exists(anchorTemporaryFiles.getFirst(), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void supportedDirectoryForceFailurePreservesTheRecoveryPair(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("data"));
        ServerIdentityStore identity = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        ProductionAuthorityKeyStore.DirectoryForce failure = path -> {
            throw new IOException("directory force failure");
        };
        ProductionAuthorityKeyStore store = new ProductionAuthorityKeyStore(identity, failure);

        IOException exception = assertThrows(IOException.class, store::signingPublicKey);

        assertEquals("directory force failure", exception.getMessage());
        assertTrue(Files.exists(store.privateKeyPath(), LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(store.trustAnchorPath(), LinkOption.NOFOLLOW_LINKS));
        try (var entries = Files.list(store.secretRoot())) {
            assertEquals(1, entries.filter(path -> path.getFileName().toString()
                    .endsWith(ProductionAuthorityKeyStore.PRIVATE_KEY_TEMP_SUFFIX)).count());
        }
    }

    @Test
    void postPublicationDirectoryForceFailureRestoresRecoveryEvidence(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("data"));
        ServerIdentityStore identity = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        AtomicInteger forceCount = new AtomicInteger();
        ProductionAuthorityKeyStore.DirectoryForce failure = path -> {
            if (forceCount.getAndIncrement() == 0) {
                return ProductionAuthorityKeyStore.DirectoryForceOutcome.SUPPORTED;
            }
            throw new IOException("post-publication directory force failure");
        };
        ProductionAuthorityKeyStore store = new ProductionAuthorityKeyStore(identity, failure);

        IOException exception = assertThrows(IOException.class, store::signingPublicKey);

        assertEquals("post-publication directory force failure", exception.getMessage());
        assertTrue(Files.exists(store.privateKeyPath(), LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(store.trustAnchorPath(), LinkOption.NOFOLLOW_LINKS));
        try (var entries = Files.list(store.secretRoot())) {
            assertEquals(1, entries.filter(path -> path.getFileName().toString()
                    .endsWith(ProductionAuthorityKeyStore.PRIVATE_KEY_TEMP_SUFFIX)).count());
        }
    }

    @Test
    void trustAnchorDirectoryForceFailureRestoresRecoveryEvidence(@TempDir Path temporary) throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("data"));
        ServerIdentityStore identity = ServerIdentityStore.open(dataRoot.resolve(ServerIdentityStore.FILE_NAME));
        AtomicInteger anchorForceCount = new AtomicInteger();
        ProductionAuthorityKeyStore.DirectoryForce failure = path -> {
            if (ProductionAuthorityKeyStore.TRUST_ANCHOR_DIRECTORY.equals(path.getFileName().toString())
                && anchorForceCount.getAndIncrement() > 0) {
                throw new IOException("trust anchor directory force failure");
            }
            return ProductionAuthorityKeyStore.DirectoryForceOutcome.SUPPORTED;
        };
        ProductionAuthorityKeyStore store = new ProductionAuthorityKeyStore(identity, failure);

        IOException exception = assertThrows(IOException.class, store::signingPublicKey);

        assertEquals("trust anchor directory force failure", exception.getMessage());
        assertTrue(Files.exists(store.privateKeyPath(), LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.exists(store.trustAnchorPath(), LinkOption.NOFOLLOW_LINKS));
        try (var entries = Files.list(store.trustAnchorPath().getParent())) {
            assertEquals(1, entries.filter(path -> path.getFileName().toString()
                    .startsWith(ProductionAuthorityKeyStore.TRUST_ANCHOR_FILE + ".")
                    && path.getFileName().toString().endsWith(ProductionAuthorityKeyStore.TRUST_ANCHOR_TEMP_SUFFIX))
                .count());
        }

        String recovered = supportedStore(identity).signingPublicKey();
        assertEquals(recovered, supportedStore(identity).signingPublicKey());
    }

    private static ProductionAuthorityKeyStore supportedStore(ServerIdentityStore identity) {
        return new ProductionAuthorityKeyStore(identity,
            path -> ProductionAuthorityKeyStore.DirectoryForceOutcome.SUPPORTED);
    }

    private static List<String> loadConcurrently(ProductionAuthorityKeyStore first,
                                                 ProductionAuthorityKeyStore second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> firstResult = executor.submit(first::signingPublicKey);
            Future<String> secondResult = executor.submit(second::signingPublicKey);
            return List.of(get(firstResult), get(secondResult));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static Throwable failure(Future<String> result) {
        ExecutionException exception = assertThrows(ExecutionException.class,
            () -> result.get(10, TimeUnit.SECONDS));
        return exception.getCause();
    }

    private static Object protection(Path path) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            return Set.copyOf(posix.readAttributes().permissions());
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS);
        assertNotNull(acl);
        List<AclEntry> entries = acl.getAcl();
        return List.copyOf(entries);
    }

    private static String get(Future<String> result) {
        try {
            return result.get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class FailingInitialLoadObserver implements ProductionAuthorityKeyStore.InitialLoadObserver {
        private final CountDownLatch leaderStarted = new CountDownLatch(1);
        private final CountDownLatch followerJoined = new CountDownLatch(1);
        private final CountDownLatch releaseLeader = new CountDownLatch(1);
        private final CountDownLatch leaderFinished = new CountDownLatch(1);
        private final AtomicInteger leaderAttempts = new AtomicInteger();

        @Override
        public void leaderStarted() throws IOException {
            int attempt = leaderAttempts.incrementAndGet();
            if (attempt != 1) {
                return;
            }
            this.leaderStarted.countDown();
            try {
                if (!releaseLeader.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("Shared Initial Authority Failure Was Not Released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Shared Initial Authority Failure Was Interrupted", exception);
            }
            throw new IOException("Shared Initial Authority Failure");
        }

        @Override
        public void followerJoined() {
            followerJoined.countDown();
        }

        @Override
        public void finished() {
            leaderFinished.countDown();
        }
    }

    private static final class TwoLeaderObserver implements ProductionAuthorityKeyStore.InitialLoadObserver {
        private final CyclicBarrier barrier = new CyclicBarrier(2);
        private final AtomicInteger leaders = new AtomicInteger();
        private final AtomicInteger followers = new AtomicInteger();

        @Override
        public void leaderStarted() throws IOException {
            leaders.incrementAndGet();
            try {
                barrier.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Independent Initial Authority Load Was Interrupted", exception);
            } catch (Exception exception) {
                throw new IOException("Independent Initial Authority Loads Did Not Start", exception);
            }
        }

        @Override
        public void followerJoined() {
            followers.incrementAndGet();
        }
    }

    private static final class SupportedDirectoryForce implements ProductionAuthorityKeyStore.DirectoryForce {
        @Override
        public ProductionAuthorityKeyStore.DirectoryForceOutcome force(Path path) {
            return ProductionAuthorityKeyStore.DirectoryForceOutcome.SUPPORTED;
        }
    }
}

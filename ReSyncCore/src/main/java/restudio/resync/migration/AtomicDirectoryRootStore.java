package restudio.resync.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public final class AtomicDirectoryRootStore implements MigrationStager, MigrationActivator, MigrationRollback {
    private final Path controlDirectory;
    private final Path pointerPath;
    private final Path scopeRoot;
    private final DirectoryMigrationStager stager = new DirectoryMigrationStager();

    public AtomicDirectoryRootStore(Path controlDirectory) throws IOException {
        this(controlDirectory, "active-root");
    }

    public AtomicDirectoryRootStore(Path controlDirectory, String pointerName) throws IOException {
        this.controlDirectory = MigrationPaths.requirePath(controlDirectory, "controlDirectory");
        String name = MigrationCanonical.requireText(pointerName, "pointerName");
        if (name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("pointerName Must Be A File Name");
        }
        Files.createDirectories(this.controlDirectory);
        if (Files.isSymbolicLink(this.controlDirectory)) {
            throw new MigrationException("controlDirectory Cannot Be A Symbolic Link");
        }
        this.scopeRoot = Objects.requireNonNull(this.controlDirectory.getParent(), "controlDirectory parent").toAbsolutePath().normalize();
        MigrationPaths.requireDirectory(this.scopeRoot, "scopeRoot");
        this.pointerPath = this.controlDirectory.resolve(name).toAbsolutePath().normalize();
    }

    public synchronized Optional<Path> activeRoot() throws IOException {
        if (!Files.exists(pointerPath, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (Files.isSymbolicLink(pointerPath) || !Files.isRegularFile(pointerPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Active Root Pointer Is Not A Regular File");
        }
        Path target = resolvePointer(Files.readString(pointerPath, StandardCharsets.UTF_8));
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Active Root Does Not Exist: " + target);
        }
        MigrationPaths.requireNoSymlinkTree(target);
        return Optional.of(target);
    }

    @Override
    public synchronized StagedMigration stage(Path sourceRoot, Path stagingRoot, MigrationPlan plan) throws IOException {
        Path staging = MigrationPaths.requirePath(stagingRoot, "stagingRoot");
        requireInScope(staging);
        Optional<Path> previous = activeRoot();
        StagedMigration copied = stager.stage(sourceRoot, staging, plan);
        return new StagedMigration(copied.root(), previous, copied.planHash(), copied.contentHash());
    }

    @Override
    public synchronized void activate(StagedMigration staged) throws IOException {
        Objects.requireNonNull(staged, "staged");
        Path root = MigrationPaths.requireDirectory(staged.root(), "stagedRoot");
        requireInScope(root);
        if (!staged.contentHash().equals(TreeDigest.of(root))) {
            throw new MigrationException("Staged Root Content Changed Before Activation");
        }
        AtomicFiles.write(pointerPath, pointerValue(root).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public synchronized void rollback(StagedMigration staged) throws IOException {
        Objects.requireNonNull(staged, "staged");
        Path stagedRoot = MigrationPaths.requirePath(staged.root(), "stagedRoot");
        requireInScope(stagedRoot);
        Optional<Path> previous = staged.previousRoot().map(value -> MigrationPaths.requirePath(value, "previousRoot"));
        if (previous.isPresent()) {
            requireInScope(previous.get());
            if (previous.get().equals(stagedRoot)) {
                throw new MigrationException("Staged And Previous Roots Must Be Distinct");
            }
        }
        Optional<Path> active = activeRoot();
        if (active.equals(Optional.of(stagedRoot))) {
            if (previous.isPresent()) {
                Path previousRoot = MigrationPaths.requireDirectory(previous.get(), "previousRoot");
                AtomicFiles.write(pointerPath, pointerValue(previousRoot).getBytes(StandardCharsets.UTF_8));
            } else {
                Files.deleteIfExists(pointerPath);
            }
            return;
        }
        if (active.equals(previous)) {
            return;
        }
        throw new MigrationException("Cannot Roll Back An Unknown Active Root");
    }

    public Path pointerPath() {
        return pointerPath;
    }

    private String pointerValue(Path root) {
        String relative = scopeRoot.relativize(root.toAbsolutePath().normalize()).toString().replace(java.io.File.separatorChar, '/');
        String encoded = MigrationCanonical.encode(relative);
        return "format=1\nroot=" + encoded + "\nhash=" + MigrationCanonical.sha256(relative) + "\n";
    }

    private Path resolvePointer(String value) throws IOException {
        String[] lines = value.strip().split("\\R", -1);
        if (lines.length != 3 || !lines[0].equals("format=1") || !lines[1].startsWith("root=") || !lines[2].startsWith("hash=")) {
            throw new MigrationException("Active Root Pointer Is Invalid");
        }
        String relative = MigrationCanonical.decode(lines[1].substring("root=".length()));
        String expectedHash = MigrationCanonical.requireDigest(lines[2].substring("hash=".length()), "activeRootHash");
        if (!expectedHash.equals(MigrationCanonical.sha256(relative))) {
            throw new MigrationException("Active Root Pointer Hash Is Invalid");
        }
        Path target;
        try {
            target = MigrationPaths.resolveInside(scopeRoot, relative);
        } catch (IllegalArgumentException exception) {
            throw new MigrationException("Active Root Pointer Escapes Scope", exception);
        }
        requireInScope(target);
        if (target.startsWith(controlDirectory)) {
            throw new MigrationException("Active Root Pointer Cannot Target Control Data");
        }
        return target;
    }

    private void requireInScope(Path root) throws IOException {
        Path normalized = MigrationPaths.requirePath(root, "root");
        if (!normalized.startsWith(scopeRoot) || normalized.startsWith(controlDirectory)) {
            throw new MigrationException("Root Is Outside Activation Scope: " + normalized);
        }
        Path current = normalized;
        while (current != null && current.startsWith(scopeRoot)) {
            if (Files.isSymbolicLink(current)) {
                throw new MigrationException("Activation Root Contains A Symbolic Link: " + current);
            }
            if (current.equals(scopeRoot)) {
                break;
            }
            current = current.getParent();
        }
    }

}

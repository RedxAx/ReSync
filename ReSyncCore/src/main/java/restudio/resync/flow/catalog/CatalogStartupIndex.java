package restudio.resync.flow.catalog;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.ContentHash;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class CatalogStartupIndex {
    public static final String DIRECTORY = "catalog-startup-index";
    private static final ConcurrentHashMap<String, Record> MEMORY = new ConcurrentHashMap<>();

    private CatalogStartupIndex() {
    }

    public static String fingerprint(List<String> sourceIdentities, String bindingManifestHash, String contractVersion,
                                    int definitionCount) {
        List<String> identities = new ArrayList<>(sourceIdentities == null ? List.of() : sourceIdentities);
        identities.sort(Comparator.naturalOrder());
        StringBuilder builder = new StringBuilder("catalog-startup-index-1\n");
        for (String identity : identities) {
            builder.append(identity).append('\n');
        }
        builder.append(Objects.requireNonNull(bindingManifestHash, "bindingManifestHash")).append('\n');
        builder.append(Objects.requireNonNull(contractVersion, "contractVersion")).append('\n');
        builder.append(definitionCount);
        return CanonicalJson.genericCanonicalContentHash(builder.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static List<String> sourceIdentities(Collection<CatalogContribution> contributions) {
        List<String> identities = new ArrayList<>();
        for (CatalogContribution contribution : contributions == null ? List.<CatalogContribution>of() : contributions) {
            if (contribution == null) {
                continue;
            }
            CatalogProvenance provenance = contribution.provenance();
            identities.add(contribution.ownerId().canonicalText() + '\t' + contribution.definitions().size() + '\t'
                + provenance.sourceUri() + '\t' + provenance.sourceHash().canonicalText());
            for (CatalogProvenance.SourceEntry entry : provenance.entries()) {
                identities.add(entry.sourceUri() + '\t' + entry.sourceHash().canonicalText() + '\t' + entry.definitionId());
            }
        }
        return identities;
    }

    public static Optional<CatalogCanonicalizer.DerivedSnapshot> find(Path directory, String fingerprint, long generation) {
        Record record = MEMORY.get(fingerprint);
        if (record == null) {
            record = read(directory, fingerprint);
            if (record != null) {
                MEMORY.put(fingerprint, record);
            }
        }
        if (record == null) {
            return Optional.empty();
        }
        String canonical = record.generation() == generation
            ? record.canonicalContent()
            : CatalogCanonicalizer.rebaseSnapshotGeneration(record.canonicalContent(), generation);
        return Optional.of(CatalogCanonicalizer.derivedSnapshot(record.contentChecksum(), record.bindingManifestHash(), canonical));
    }

    public static void store(Path directory, String fingerprint, long generation, CatalogCanonicalizer.DerivedSnapshot derived) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        CatalogCanonicalizer.DerivedSnapshot snapshot = Objects.requireNonNull(derived, "derived");
        Record record = new Record(fingerprint, generation, snapshot.contentChecksum(), snapshot.bindingManifestHash(),
            snapshot.canonicalContent());
        MEMORY.put(fingerprint, record);
        write(directory, record);
    }

    private static Record read(Path directory, String fingerprint) {
        if (directory == null) {
            return null;
        }
        Path keyFile = directory.resolve("fingerprint.txt");
        Path metaFile = directory.resolve("meta.txt");
        Path snapshotFile = directory.resolve("snapshot.canonical.json");
        if (!Files.isRegularFile(keyFile) || !Files.isRegularFile(metaFile) || !Files.isRegularFile(snapshotFile)) {
            return null;
        }
        try {
            String storedKey = Files.readString(keyFile, StandardCharsets.UTF_8).strip();
            if (!fingerprint.equals(storedKey)) {
                return null;
            }
            List<String> meta = Files.readAllLines(metaFile, StandardCharsets.UTF_8);
            if (meta.size() < 3) {
                return null;
            }
            long generation = Long.parseLong(meta.get(0).strip());
            ContentHash contentChecksum = ContentHash.parseCanonicalText(meta.get(1).strip());
            ContentHash bindingManifestHash = ContentHash.parseCanonicalText(meta.get(2).strip());
            String canonical = Files.readString(snapshotFile, StandardCharsets.UTF_8);
            return new Record(fingerprint, generation, contentChecksum, bindingManifestHash, canonical);
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private static void write(Path directory, Record record) {
        if (directory == null) {
            return;
        }
        try {
            Files.createDirectories(directory);
            Path staging = directory.resolve(".staging");
            Files.createDirectories(staging);
            Path keyFile = staging.resolve("fingerprint.txt");
            Path metaFile = staging.resolve("meta.txt");
            Path snapshotFile = staging.resolve("snapshot.canonical.json");
            Files.writeString(keyFile, record.fingerprint() + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            Files.writeString(metaFile, record.generation() + "\n" + record.contentChecksum().canonicalText() + "\n"
                    + record.bindingManifestHash().canonicalText() + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            Files.writeString(snapshotFile, record.canonicalContent(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            move(keyFile, directory.resolve("fingerprint.txt"));
            move(metaFile, directory.resolve("meta.txt"));
            move(snapshotFile, directory.resolve("snapshot.canonical.json"));
        } catch (IOException exception) {
            return;
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record Record(String fingerprint, long generation, ContentHash contentChecksum, ContentHash bindingManifestHash,
                          String canonicalContent) {
    }
}

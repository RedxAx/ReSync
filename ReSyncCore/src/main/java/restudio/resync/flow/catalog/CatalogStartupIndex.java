package restudio.resync.flow.catalog;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.canonical.CanonicalLimits;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.migration.AtomicFiles;
import restudio.resync.migration.MigrationPaths;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class CatalogStartupIndex {
    public static final String DIRECTORY = "catalog-startup-index";
    static final String FILE_NAME = "snapshot.index";
    private static final String FORMAT = "catalog-startup-index-2";
    private static final int MAX_RECORD_BYTES = CanonicalLimits.catalog().canonicalBytes() + 1_024;
    private static final long MAX_RESIDENT_BYTES = 268_435_456L;
    private static final int MAX_RESIDENTS = 8;
    private static final Map<Key, Resident> MEMORY = new LinkedHashMap<>(8, 0.75f, true);
    private static long residentBytes;
    private static long epoch;

    private CatalogStartupIndex() {
    }

    public static String fingerprint(List<String> sourceIdentities, String bindingManifestHash, String contractVersion,
                                     int definitionCount) {
        if (definitionCount < 0) {
            throw new IllegalArgumentException("Catalog definition count must not be negative");
        }
        List<String> identities = new ArrayList<>(sourceIdentities == null ? List.of() : sourceIdentities);
        identities.sort(Comparator.naturalOrder());
        return CanonicalJson.sha256("catalog-startup-index.v2", Map.of(
            "sources", identities,
            "bindingManifestHash", Objects.requireNonNull(bindingManifestHash, "bindingManifestHash"),
            "contractVersion", Objects.requireNonNull(contractVersion, "contractVersion"),
            "definitionCount", definitionCount));
    }

    public static List<String> sourceIdentities(Collection<CatalogContribution> contributions) {
        List<String> identities = new ArrayList<>();
        for (CatalogContribution contribution : contributions == null ? List.<CatalogContribution>of() : contributions) {
            if (contribution != null) {
                identities.add(contribution.ownerId().canonicalText() + '\t' + contribution.startupContracts());
            }
        }
        return identities;
    }

    public static Optional<CatalogCanonicalizer.DerivedSnapshot> find(Path directory, String fingerprint, long generation) {
        return find(directory, fingerprint, generation, null);
    }

    static Optional<CatalogCanonicalizer.DerivedSnapshot> find(Path directory, String fingerprint, long generation,
                                                              Supplier<CatalogCanonicalizer.DerivedSnapshot> expected) {
        if (generation < 1 || directory == null) {
            return Optional.empty();
        }
        Key key = new Key(directory.toAbsolutePath().normalize(), Objects.requireNonNull(fingerprint, "fingerprint"));
        Resident resident;
        long expectedEpoch;
        synchronized (MEMORY) {
            resident = MEMORY.get(key);
            expectedEpoch = epoch;
        }
        if (resident == null) {
            Record record = read(key, expected);
            if (record == null) {
                return Optional.empty();
            }
            resident = admit(key, record, expectedEpoch, expected != null);
        }
        if (resident == null) {
            return Optional.empty();
        }
        if (!resident.verify(expected)) {
            invalidate(key.directory());
            return Optional.empty();
        }
        CatalogCanonicalizer.DerivedSnapshot derived = resident.snapshot(generation);
        synchronized (MEMORY) {
            return epoch == expectedEpoch ? Optional.of(derived) : Optional.empty();
        }
    }

    public static void store(Path directory, String fingerprint, long generation, CatalogCanonicalizer.DerivedSnapshot derived) {
        store(directory, fingerprint, generation, derived, false);
    }

    static void store(Path directory, String fingerprint, long generation, CatalogCanonicalizer.DerivedSnapshot derived,
                      boolean verified) {
        if (generation < 1) {
            throw new IllegalArgumentException("Catalog generation must be positive");
        }
        if (directory == null) {
            return;
        }
        Key key = new Key(directory.toAbsolutePath().normalize(), Objects.requireNonNull(fingerprint, "fingerprint"));
        CatalogCanonicalizer.DerivedSnapshot snapshot = Objects.requireNonNull(derived, "derived");
        Record record = new Record(fingerprint, generation, snapshot.contentChecksum(), snapshot.bindingManifestHash(),
            snapshot.canonicalContent());
        long expectedEpoch;
        synchronized (MEMORY) {
            expectedEpoch = epoch;
        }
        if (admit(key, record, expectedEpoch, verified) != null) {
            write(key.directory(), record);
        }
    }

    public static void invalidate(Path directory) {
        if (directory == null) {
            return;
        }
        Path root = directory.toAbsolutePath().normalize();
        synchronized (MEMORY) {
            epoch++;
            var entries = MEMORY.entrySet().iterator();
            while (entries.hasNext()) {
                Map.Entry<Key, Resident> entry = entries.next();
                if (entry.getKey().directory().equals(root)) {
                    residentBytes -= entry.getValue().bytes();
                    entries.remove();
                }
            }
        }
    }

    private static Resident admit(Key key, Record record, long expectedEpoch, boolean verified) {
        synchronized (MEMORY) {
            if (epoch != expectedEpoch) {
                return null;
            }
            Resident resident = MEMORY.get(key);
            if (resident != null && !verified) {
                return resident;
            }
            if (resident != null) {
                residentBytes -= resident.bytes();
            }
            resident = new Resident(record, verified);
            MEMORY.put(key, resident);
            residentBytes += resident.bytes();
            var entries = MEMORY.entrySet().iterator();
            while (MEMORY.size() > MAX_RESIDENTS || residentBytes > MAX_RESIDENT_BYTES) {
                residentBytes -= entries.next().getValue().bytes();
                entries.remove();
            }
            return resident;
        }
    }

    private static Record read(Key key, Supplier<CatalogCanonicalizer.DerivedSnapshot> expected) {
        try {
            Path directory = MigrationPaths.requireDirectory(key.directory(), "Catalog startup index directory");
            Path file = directory.resolve(FILE_NAME);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_RECORD_BYTES) {
                return null;
            }
            byte[] bytes;
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(MAX_RECORD_BYTES + 1);
            }
            if (bytes.length > MAX_RECORD_BYTES) {
                return null;
            }
            int bodyStart = 0;
            int lines = 0;
            while (bodyStart < bytes.length && bodyStart < 1_024 && lines < 6) {
                if (bytes[bodyStart++] == '\n') {
                    lines++;
                }
            }
            if (lines != 6) {
                return null;
            }
            String[] fields = new String(bytes, 0, bodyStart - 1, StandardCharsets.UTF_8).split("\n", -1);
            if (fields.length != 6 || !FORMAT.equals(fields[0]) || !key.fingerprint().equals(fields[1])) {
                return null;
            }
            long generation = Long.parseLong(fields[2]);
            ContentHash contentChecksum = ContentHash.parseCanonicalText(fields[3]);
            ContentHash bindingManifestHash = ContentHash.parseCanonicalText(fields[4]);
            if (generation < 1 || !Long.toString(generation).equals(fields[2])) {
                return null;
            }
            if (expected != null) {
                CatalogCanonicalizer.DerivedSnapshot authoritative = Objects.requireNonNull(expected.get(),
                    "Expected Catalog Snapshot Is Required");
                if (!contentChecksum.equals(authoritative.contentChecksum())
                    || !bindingManifestHash.equals(authoritative.bindingManifestHash())) {
                    return null;
                }
                String canonical = CatalogCanonicalizer.rebaseSnapshotGeneration(authoritative.canonicalContent(), generation);
                byte[] canonicalBytes = canonical.getBytes(StandardCharsets.UTF_8);
                if (canonicalBytes.length > CanonicalLimits.catalog().canonicalBytes()
                    || !Arrays.equals(bytes, bodyStart, bytes.length, canonicalBytes, 0, canonicalBytes.length)
                    || !fields[5].equals(CanonicalJson.genericCanonicalContentHash(canonicalBytes))) {
                    return null;
                }
                return new Record(key.fingerprint(), generation, contentChecksum, bindingManifestHash, canonical);
            }
            byte[] canonicalBytes = Arrays.copyOfRange(bytes, bodyStart, bytes.length);
            if (!fields[5].equals(CanonicalJson.genericCanonicalContentHash(canonicalBytes))) {
                return null;
            }
            Object parsed = CanonicalJson.parse(canonicalBytes, CanonicalLimits.catalog());
            String canonical = new String(canonicalBytes, StandardCharsets.UTF_8);
            if (!(parsed instanceof Map<?, ?> snapshot) || !"snapshot".equals(snapshot.get("kind"))
                || !contentChecksum.canonicalText().equals(snapshot.get("contentChecksum"))
                || !bindingManifestHash.canonicalText().equals(snapshot.get("bindingManifestHash"))
                || !(snapshot.get("generation") instanceof BigDecimal value) || value.longValueExact() != generation
                || !canonical.equals(CanonicalJson.canonicalize(parsed, CanonicalLimits.catalog()))
                || !contentChecksum.equals(CatalogCanonicalizer.checksumForParsedContent(parsed))) {
                return null;
            }
            return new Record(key.fingerprint(), generation, contentChecksum, bindingManifestHash, canonical);
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private static void write(Path directory, Record record) {
        try {
            byte[] canonicalBytes = record.canonicalContent().getBytes(StandardCharsets.UTF_8);
            String header = FORMAT + "\n" + record.fingerprint() + "\n" + record.generation() + "\n"
                + record.contentChecksum().canonicalText() + "\n" + record.bindingManifestHash().canonicalText() + "\n"
                + CanonicalJson.genericCanonicalContentHash(canonicalBytes) + "\n";
            byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
            if (canonicalBytes.length > CanonicalLimits.catalog().canonicalBytes()) {
                return;
            }
            byte[] bytes = new byte[headerBytes.length + canonicalBytes.length];
            System.arraycopy(headerBytes, 0, bytes, 0, headerBytes.length);
            System.arraycopy(canonicalBytes, 0, bytes, headerBytes.length, canonicalBytes.length);
            AtomicFiles.write(directory.resolve(FILE_NAME), bytes);
        } catch (IOException | RuntimeException exception) {
            return;
        }
    }

    private record Key(Path directory, String fingerprint) {
    }

    private record Record(String fingerprint, long generation, ContentHash contentChecksum, ContentHash bindingManifestHash,
                          String canonicalContent) {
    }

    private static final class Resident {
        private final Record record;
        private final long bytes;
        private long generation;
        private CatalogCanonicalizer.DerivedSnapshot derived;
        private boolean verified;

        private Resident(Record record, boolean verified) {
            this.record = record;
            this.bytes = 4L * record.canonicalContent().length();
            this.generation = record.generation();
            this.derived = CatalogCanonicalizer.derivedSnapshot(record.contentChecksum(), record.bindingManifestHash(),
                record.canonicalContent());
            this.verified = verified;
        }

        private long bytes() {
            return bytes;
        }

        private synchronized boolean verify(Supplier<CatalogCanonicalizer.DerivedSnapshot> expected) {
            if (expected == null || verified) {
                return true;
            }
            CatalogCanonicalizer.DerivedSnapshot authoritative = Objects.requireNonNull(expected.get(),
                "Expected Catalog Snapshot Is Required");
            verified = authoritative.contentChecksum().equals(record.contentChecksum())
                && authoritative.bindingManifestHash().equals(record.bindingManifestHash())
                && CatalogCanonicalizer.rebaseSnapshotGeneration(authoritative.canonicalContent(), record.generation())
                    .equals(record.canonicalContent());
            return verified;
        }

        private synchronized CatalogCanonicalizer.DerivedSnapshot snapshot(long requestedGeneration) {
            if (generation != requestedGeneration) {
                String canonical = CatalogCanonicalizer.rebaseSnapshotGeneration(record.canonicalContent(), requestedGeneration);
                derived = CatalogCanonicalizer.derivedSnapshot(record.contentChecksum(), record.bindingManifestHash(), canonical);
                generation = requestedGeneration;
            }
            return derived;
        }
    }
}

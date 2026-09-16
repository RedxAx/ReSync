package restudio.resync.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceRootReadinessTest {
    @TempDir
    Path temporary;

    @Test
    void requiredGapsAreSortedAndOptionalGapsRemainVisible() throws Exception {
        Path required = Files.createDirectory(temporary.resolve("required"));
        Path optional = temporary.resolve("optional");
        PersistenceRootReadiness readiness = new PersistenceRootReadiness(List.of(
            PersistenceRootReadiness.Owner.unavailable("optional", optional, false, "Optional owner is not configured"),
            PersistenceRootReadiness.Owner.unavailable("required", required, true, "Required owner is not rebound")));

        assertFalse(readiness.complete());
        assertEquals(List.of("required"), readiness.requiredGaps().stream().map(PersistenceRootReadiness.Owner::owner).toList());
        assertEquals(List.of("optional", "required"), readiness.unavailableOwners().stream()
            .map(PersistenceRootReadiness.Owner::owner).toList());
        assertEquals(required, readiness.owner("required").root());
    }

    @Test
    void externalAffectedWritersRemainVisibleWithoutBlockingLocalCompleteness() throws Exception {
        Path localRoot = Files.createDirectory(temporary.resolve("local"));
        Path externalRoot = Files.createDirectories(temporary.resolve("world").resolve("playerdata"));
        PersistenceRootReadiness.UncoveredWriter external = PersistenceRootReadiness.UncoveredWriter.externalAffected(
            "resync.paper.playerdata", externalRoot, "Paper owns world player data outside the ReSync root",
            "Bukkit/Paper world persistence authority");
        PersistenceRootReadiness readiness = new PersistenceRootReadiness(
            List.of(PersistenceRootReadiness.Owner.registered("local", localRoot, true)), List.of(external));

        assertTrue(readiness.complete());
        assertTrue(readiness.writerInventoryComplete());
        assertTrue(readiness.localUncoveredWriterIds().isEmpty());
        assertEquals(List.of("resync.paper.playerdata"), readiness.externalAffectedWriters().stream()
            .map(PersistenceRootReadiness.UncoveredWriter::id).toList());
        assertEquals(List.of("EXTERNAL_AFFECTED"), ((List<?>) readiness.payload().get("uncoveredWriters")).stream()
            .map(entry -> ((Map<?, ?>) entry).get("scope").toString()).toList());
        assertTrue(readiness.canonicalReport().contains("EXTERNAL_AFFECTED"));
    }

    @Test
    void emptyReadinessCannotClaimCompleteCoverage() {
        assertFalse(PersistenceRootReadiness.empty().complete());
        assertThrows(MigrationException.class, () -> PersistenceRootReadiness.empty().requireComplete());
    }

    @Test
    void duplicateOwnersAreRejected() throws Exception {
        Path first = Files.createDirectory(temporary.resolve("first"));
        Path second = Files.createDirectory(temporary.resolve("second"));

        assertThrows(IllegalArgumentException.class, () -> new PersistenceRootReadiness(List.of(
            PersistenceRootReadiness.Owner.registered("duplicate", first, true),
            PersistenceRootReadiness.Owner.registered("duplicate", second, true))));
    }

    @Test
    void unavailableOwnerMustExplainItsGap() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));

        assertThrows(IllegalArgumentException.class, () -> PersistenceRootReadiness.Owner.unavailable("missing", root, true, ""));
    }

    @Test
    void preservesTheGenericDerivedCacheClassificationInReports() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("derived"));
        PersistenceRootReadiness.Owner owner = PersistenceRootReadiness.Owner.unavailable(
            "generated", root, false, PersistenceParticipantClassification.DERIVED_CACHE, "Rebuild required");
        PersistenceRootReadiness readiness = new PersistenceRootReadiness(List.of(owner));

        assertEquals(PersistenceParticipantClassification.DERIVED_CACHE, readiness.owner("generated").classification());
        assertTrue(readiness.canonicalReport().contains("DERIVED_CACHE"));
        List<?> owners = (List<?>) readiness.payload().get("owners");
        assertEquals("DERIVED_CACHE", ((Map<?, ?>) owners.getFirst()).get("classification"));
    }

    @Test
    void derivedResumeFailureIsPropagatedToKeepRestoreUnavailable() throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("resume-scope"));
        Path root = Files.createDirectory(scope.resolve("generated"));
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(scope);
        registry.register(new PersistenceParticipant() {
            @Override
            public String owner() {
                return "derived";
            }

            @Override
            public Path root() {
                return root;
            }

            @Override
            public PersistenceParticipantClassification classification() {
                return PersistenceParticipantClassification.DERIVED_CACHE;
            }

            @Override
            public void resume() throws IOException {
                throw new IOException("rebuild unavailable");
            }
        });

        assertThrows(MigrationException.class, registry::resumeAll);
    }

    @Test
    void authoritativeResumeFailureKeepsDependentDerivedParticipantsQuiesced() throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("resume-order-scope"));
        Path authoritativeRoot = Files.createDirectory(scope.resolve("authoritative"));
        Path derivedRoot = Files.createDirectory(scope.resolve("derived"));
        AtomicInteger derivedResumes = new AtomicInteger();
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(scope);
        registry.register(new PersistenceParticipant() {
            @Override
            public String owner() {
                return "authoritative";
            }

            @Override
            public Path root() {
                return authoritativeRoot;
            }

            @Override
            public void resume() throws IOException {
                throw new IOException("shared gate remains quiesced");
            }
        });
        registry.register(new PersistenceParticipant() {
            @Override
            public String owner() {
                return "derived";
            }

            @Override
            public Path root() {
                return derivedRoot;
            }

            @Override
            public PersistenceParticipantClassification classification() {
                return PersistenceParticipantClassification.DERIVED_CACHE;
            }

            @Override
            public void resume() {
                derivedResumes.incrementAndGet();
            }
        });

        MigrationException failure = assertThrows(MigrationException.class, registry::resumeAll);

        assertTrue(failure.getMessage().contains("shared gate remains quiesced"));
        assertEquals(0, derivedResumes.get());
    }

    @Test
    void readinessCheckIsSeparateFromQuiescedRestoreHealthChecks() throws Exception {
        Path scope = Files.createDirectory(temporary.resolve("readiness-scope"));
        Path root = Files.createDirectory(scope.resolve("generated"));
        PersistenceParticipantRegistry registry = new PersistenceParticipantRegistry(scope);
        registry.register(new PersistenceParticipant() {
            @Override
            public String owner() {
                return "derived";
            }

            @Override
            public Path root() {
                return root;
            }

            @Override
            public PersistenceParticipantClassification classification() {
                return PersistenceParticipantClassification.DERIVED_CACHE;
            }

            @Override
            public void readinessCheck() throws IOException {
                throw new IOException("rebuild required");
            }
        });

        assertThrows(MigrationException.class, registry::readinessCheckAll);
        registry.healthCheckAll();
    }
}

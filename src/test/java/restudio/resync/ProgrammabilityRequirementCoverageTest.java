package restudio.resync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgrammabilityRequirementCoverageTest {
    private static final Pattern REQUIREMENT = Pattern.compile("\\*\\*([A-Z]+-[0-9]+)\\*\\*");
    private static final Set<String> ACCEPTANCE_STATUSES = Set.of("automated", "live-pending");

    @Test
    void everySpecificationRequirementHasExactMachineReadableCoverage() throws IOException {
        Path root = projectRoot();
        Path specification = root.resolve("RESYNC_PROGRAMMABILITY_DEVELOPMENT_SPEC.md");
        Path coverageFile = root.resolve("docs/programmability-requirement-coverage.json");
        Set<String> specificationRequirements = specificationRequirements(specification);
        JsonObject coverage = JsonParser.parseString(Files.readString(coverageFile)).getAsJsonObject();

        assertEquals(specification.getFileName().toString(), coverage.get("specification").getAsString());
        assertEquals("repository-verified", coverage.get("implementationStatus").getAsString());

        Set<String> domainIds = new HashSet<>();
        Set<String> coveredRequirements = new LinkedHashSet<>();
        int livePendingDomains = 0;
        for (JsonElement domainElement : coverage.getAsJsonArray("domains")) {
            JsonObject domain = domainElement.getAsJsonObject();
            String domainId = domain.get("id").getAsString();
            String acceptanceStatus = domain.get("acceptanceStatus").getAsString();
            assertTrue(domainIds.add(domainId), "Duplicate coverage domain: " + domainId);
            assertTrue(ACCEPTANCE_STATUSES.contains(acceptanceStatus), "Invalid acceptance status for " + domainId + ": " + acceptanceStatus);
            if ("live-pending".equals(acceptanceStatus)) {
                livePendingDomains++;
            }
            JsonArray requirements = domain.getAsJsonArray("requirements");
            assertFalse(requirements.isEmpty(), "Coverage domain has no requirements: " + domainId);
            for (JsonElement requirement : requirements) {
                String requirementId = requirement.getAsString();
                assertTrue(coveredRequirements.add(requirementId), "Requirement covered more than once: " + requirementId);
            }
            JsonArray evidence = domain.getAsJsonArray("evidence");
            assertFalse(evidence.isEmpty(), "Coverage domain has no evidence: " + domainId);
            for (JsonElement evidenceElement : evidence) {
                Path evidencePath = root.resolve(evidenceElement.getAsString()).normalize();
                assertTrue(Files.exists(evidencePath), "Missing evidence for " + domainId + ": " + evidencePath);
            }
        }

        assertTrue(livePendingDomains > 0, "Operational acceptance work must remain explicit until production evidence is captured");
        assertEquals(specificationRequirements, coveredRequirements);
    }

    private Set<String> specificationRequirements(Path specification) throws IOException {
        Matcher matcher = REQUIREMENT.matcher(Files.readString(specification));
        Set<String> requirements = new LinkedHashSet<>();
        while (matcher.find()) {
            assertTrue(requirements.add(matcher.group(1)), "Duplicate requirement in specification: " + matcher.group(1));
        }
        assertFalse(requirements.isEmpty());
        return requirements;
    }

    private Path projectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve("RESYNC_PROGRAMMABILITY_DEVELOPMENT_SPEC.md"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("ReSync project root is unavailable");
    }
}

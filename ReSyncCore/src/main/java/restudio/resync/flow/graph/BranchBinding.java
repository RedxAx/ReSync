package restudio.resync.flow.graph;

import restudio.resync.flow.identity.BranchId;
import restudio.resync.flow.identity.CaseId;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class BranchBinding {
    private final BranchId branchId;
    private final CaseId selectedCaseId;
    private final List<BranchCase> cases;
    private final OpaqueData unknown;

    public BranchBinding(BranchId branchId, CaseId selectedCaseId, List<BranchCase> cases, OpaqueData unknown) {
        this.branchId = Objects.requireNonNull(branchId, "branchId");
        this.selectedCaseId = Objects.requireNonNull(selectedCaseId, "selectedCaseId");
        this.cases = List.copyOf(cases != null ? cases : List.of());
        Set<CaseId> ids = new HashSet<>();
        this.cases.forEach(branchCase -> {
            BranchCase value = Objects.requireNonNull(branchCase, "branchCase");
            if (!ids.add(value.caseId())) {
                throw new IllegalArgumentException("Duplicate branch case ID: " + value.caseId());
            }
        });
        if (!ids.contains(this.selectedCaseId)) {
            throw new IllegalArgumentException("Selected branch case does not exist: " + selectedCaseId);
        }
        this.unknown = unknown != null ? unknown : OpaqueData.empty();
        this.unknown.rejectKnownFields("branchId", "selectedCaseId", "cases");
    }

    public BranchBinding(BranchId branchId, CaseId selectedCaseId, List<BranchCase> cases) {
        this(branchId, selectedCaseId, cases, OpaqueData.empty());
    }

    public BranchId branchId() {
        return branchId;
    }

    public CaseId selectedCaseId() {
        return selectedCaseId;
    }

    public List<BranchCase> cases() {
        return cases;
    }

    public OpaqueData unknown() {
        return unknown;
    }

    Map<String, Object> canonicalValue() {
        return OpaqueData.mergeKnownFields(unknown, Map.of("branchId", branchId.canonicalText(), "selectedCaseId", selectedCaseId.canonicalText(), "cases", cases.stream().sorted(Comparator.comparing(BranchCase::caseId)).map(BranchCase::canonicalValue).toList()));
    }
}

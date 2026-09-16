package restudio.resync.flow.protocol;

import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record CapabilityNegotiationResult(CatalogVersion selectedVersion, Set<ContractRef<CapabilityId>> granted,
                                          Set<ContractRef<CapabilityId>> additive, Set<ContractRef<CapabilityId>> missing,
                                          CapabilityOutcome outcome, ProtocolEditability editability,
                                          ContentHash catalogChecksum, ContentHash bindingManifestHash, String fallbackReason,
                                          List<Diagnostic> diagnostics) {
    public CapabilityNegotiationResult {
        selectedVersion = Objects.requireNonNull(selectedVersion, "selectedVersion");
        granted = ProtocolValues.set(granted, "granted");
        additive = ProtocolValues.set(additive, "additive");
        missing = ProtocolValues.set(missing, "missing");
        if (!java.util.Collections.disjoint(granted, missing) || !java.util.Collections.disjoint(granted, additive)) {
            throw new IllegalArgumentException("Capability outcome sets must be disjoint");
        }
        outcome = Objects.requireNonNull(outcome, "outcome");
        editability = Objects.requireNonNull(editability, "editability");
        catalogChecksum = Objects.requireNonNull(catalogChecksum, "catalogChecksum");
        bindingManifestHash = Objects.requireNonNull(bindingManifestHash, "bindingManifestHash");
        fallbackReason = outcome == CapabilityOutcome.ADDITIVE
            ? ProtocolValues.optionalText(fallbackReason, "fallbackReason", 512)
            : ProtocolValues.requiredText(fallbackReason, "fallbackReason", 512);
        if (outcome == CapabilityOutcome.ADDITIVE && editability != ProtocolEditability.EDITABLE) {
            throw new IllegalArgumentException("Additive negotiation must remain editable");
        }
        if (outcome == CapabilityOutcome.READ_ONLY && editability == ProtocolEditability.EDITABLE) {
            throw new IllegalArgumentException("Read-only negotiation needs a read-only editability state");
        }
        if (outcome == CapabilityOutcome.UNSUPPORTED && editability != ProtocolEditability.REJECTED) {
            throw new IllegalArgumentException("Unsupported negotiation must be rejected");
        }
        diagnostics = ProtocolValues.list(diagnostics, "diagnostics");
    }

    public static CapabilityNegotiationResult additive(CatalogVersion selectedVersion, Set<ContractRef<CapabilityId>> granted,
                                                       Set<ContractRef<CapabilityId>> additive, ContentHash catalogChecksum,
                                                       ContentHash bindingManifestHash, List<Diagnostic> diagnostics) {
        return new CapabilityNegotiationResult(selectedVersion, granted, additive, Set.of(), CapabilityOutcome.ADDITIVE,
            ProtocolEditability.EDITABLE, catalogChecksum, bindingManifestHash, null, diagnostics);
    }

    public static CapabilityNegotiationResult readOnly(CatalogVersion selectedVersion, Set<ContractRef<CapabilityId>> granted,
                                                       Set<ContractRef<CapabilityId>> missing, ContentHash catalogChecksum,
                                                       ContentHash bindingManifestHash, String reason, List<Diagnostic> diagnostics) {
        return new CapabilityNegotiationResult(selectedVersion, granted, Set.of(), missing, CapabilityOutcome.READ_ONLY,
            ProtocolEditability.READ_ONLY_GRAPH, catalogChecksum, bindingManifestHash, reason, diagnostics);
    }

    public static CapabilityNegotiationResult unsupported(CatalogVersion selectedVersion, Set<ContractRef<CapabilityId>> missing,
                                                          ContentHash catalogChecksum, ContentHash bindingManifestHash,
                                                          String reason, List<Diagnostic> diagnostics) {
        return new CapabilityNegotiationResult(selectedVersion, Set.of(), Set.of(), missing, CapabilityOutcome.UNSUPPORTED,
            ProtocolEditability.REJECTED, catalogChecksum, bindingManifestHash, reason, diagnostics);
    }
}

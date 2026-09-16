package restudio.resync.flow.protocol;

import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;

import java.util.List;
import java.util.Objects;

public record OptionPage(ContractRef<InspectorFieldId> sourceRef, ContractRef<CapabilityId> query, long revision, String invalidationKey,
                         List<OptionItem> items, String nextCursor, boolean complete, List<Diagnostic> diagnostics) {
    public OptionPage {
        sourceRef = Objects.requireNonNull(sourceRef, "sourceRef");
        query = Objects.requireNonNull(query, "query");
        revision = ProtocolValues.revision(revision, "revision");
        invalidationKey = ProtocolValues.requiredText(invalidationKey, "invalidationKey", 256);
        items = ProtocolValues.list(items, "items");
        if (items.size() > 500) {
            throw new IllegalArgumentException("An option page cannot contain more than 500 items");
        }
        nextCursor = ProtocolValues.optionalText(nextCursor, "nextCursor", 2048);
        if (complete && nextCursor != null) {
            throw new IllegalArgumentException("Complete pages cannot expose a next cursor");
        }
        if (!complete && nextCursor == null) {
            throw new IllegalArgumentException("Incomplete pages require a next cursor");
        }
        diagnostics = ProtocolValues.list(diagnostics, "diagnostics");
    }
}

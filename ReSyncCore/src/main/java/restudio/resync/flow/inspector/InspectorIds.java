package restudio.resync.flow.inspector;

import restudio.resync.contract.canonical.CanonicalDigests;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.OwnerId;

import java.nio.charset.StandardCharsets;

public final class InspectorIds {
    private InspectorIds() {
    }

    public static InspectorFieldId stableField(OwnerId owner, String logicalPath) {
        String value = InspectorDescription.required(logicalPath, "logical field path");
        return InspectorFieldId.of("field-" + digest(owner.canonicalText() + '\u0000' + value).substring(0, 32));
    }

    public static FunctionParameterId stableParameter(OwnerId owner, String logicalPath) {
        String value = InspectorDescription.required(logicalPath, "logical parameter path");
        return FunctionParameterId.deterministic("inspector\u0000" + owner.canonicalText() + '\u0000' + value);
    }

    public static ContractRef<CapabilityId> capability(OwnerId owner, String id) {
        return ContractRef.of(owner, CapabilityId.of(id));
    }

    private static String digest(String value) {
        return CanonicalDigests.hex(CanonicalDigests.sha256(value.getBytes(StandardCharsets.UTF_8)));
    }
}

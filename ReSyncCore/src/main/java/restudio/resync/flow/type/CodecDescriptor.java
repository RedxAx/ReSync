package restudio.resync.flow.type;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.IdentitySupport;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record CodecDescriptor(TypeReference id, int version, boolean deterministic, boolean preservesOpaque,
                              Map<String, Object> unknown) {
    public CodecDescriptor {
        Objects.requireNonNull(id, "id");
        if (version < 1) {
            throw new IllegalArgumentException("Codec version must be positive");
        }
        unknown = IdentitySupport.unknown(unknown, "codec descriptor unknown data");
    }

    public CodecDescriptor(TypeReference id, int version, boolean deterministic, boolean preservesOpaque) {
        this(id, version, deterministic, preservesOpaque, Map.of());
    }

    public Map<String, Object> canonicalValue() {
        var known = new LinkedHashMap<String, Object>();
        known.put("deterministic", deterministic);
        known.put("id", id.canonicalValue());
        known.put("preservesOpaque", preservesOpaque);
        known.put("version", version);
        return IdentitySupport.merge(unknown, known);
    }

    public String canonicalJson() {
        return CanonicalJson.canonicalize(canonicalValue());
    }
}

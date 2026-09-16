package restudio.resync.server;

import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.protocol.ProtocolRejectionCode;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class MappedOptionQueryAuthority implements OptionQueryAuthority {
    private final Map<Key, Source> sources;

    public MappedOptionQueryAuthority(Collection<Source> sources) {
        Objects.requireNonNull(sources, "Option query sources are required");
        Map<Key, Source> indexed = new LinkedHashMap<>();
        for (Source source : sources) {
            Source checked = Objects.requireNonNull(source, "Option query source is required");
            Key key = new Key(checked.sourceRef(), checked.query());
            Source previous = indexed.putIfAbsent(key, checked);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate option query source and capability: " + key);
            }
        }
        this.sources = Map.copyOf(indexed);
    }

    public MappedOptionQueryAuthority(Map<Key, Source> sources) {
        Objects.requireNonNull(sources, "Option query sources are required");
        Map<Key, Source> indexed = new LinkedHashMap<>();
        sources.forEach((key, source) -> {
            Key checkedKey = Objects.requireNonNull(key, "Option query key is required");
            Source checkedSource = Objects.requireNonNull(source, "Option query source is required");
            if (!checkedKey.sourceRef().equals(checkedSource.sourceRef()) || !checkedKey.query().equals(checkedSource.query())) {
                throw new IllegalArgumentException("Option query map key does not match its advertised capability");
            }
            indexed.put(checkedKey, checkedSource);
        });
        this.sources = Map.copyOf(indexed);
    }

    @Override
    public Source require(ContractRef<InspectorFieldId> sourceRef, ContractRef<CapabilityId> query) {
        Source source = sourceRef == null || query == null ? null : sources.get(new Key(sourceRef, query));
        if (source == null) {
            throw new Rejected(ProtocolRejectionCode.RESOURCE_TYPE_UNAVAILABLE,
                "Option query capability is unavailable");
        }
        return source;
    }

    public record Key(ContractRef<InspectorFieldId> sourceRef, ContractRef<CapabilityId> query) {
        public Key {
            sourceRef = Objects.requireNonNull(sourceRef, "Option source reference is required");
            query = Objects.requireNonNull(query, "Option query capability is required");
        }
    }
}

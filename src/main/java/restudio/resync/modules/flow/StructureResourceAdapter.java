package restudio.resync.modules.flow;

import com.google.gson.Gson;
import restudio.resync.resources.ReSyncManagedResource;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.structure.ReSyncStructure;
import restudio.resync.structure.StructureLibrary;
import restudio.resync.structure.StructureSummary;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

public final class StructureResourceAdapter implements FlowResourceAdapter<ReSyncStructure> {
    private static final Gson GSON = new Gson();
    private final Supplier<StructureLibrary> library;

    public StructureResourceAdapter(StructureLibrary library) {
        this(() -> Objects.requireNonNull(library, "Structure library is required"));
    }

    StructureResourceAdapter(Supplier<StructureLibrary> library) {
        this.library = Objects.requireNonNull(library, "Structure library supplier is required");
    }

    @Override
    public ReSyncManagedResource descriptor() {
        return ReSyncResourceCatalog.byType(ReSyncResourceCatalog.STRUCTURE);
    }

    @Override
    public ReSyncStructure get(String id) {
        return library().load(id).orElse(null);
    }

    @Override
    public List<String> listIds() {
        return library().list().stream().map(StructureSummary::id).toList();
    }

    @Override
    public ReSyncStructure deserialize(String json) {
        ReSyncStructure structure = GSON.fromJson(json, ReSyncStructure.class);
        validate(structure);
        return structure;
    }

    @Override
    public String id(ReSyncStructure value) {
        return value == null ? "" : library().canonicalId(value.getId());
    }

    @Override
    public void save(ReSyncStructure value) {
        library().save(value);
    }

    @Override
    public void delete(String id) {
        library().delete(id);
    }

    @Override
    public void validate(ReSyncStructure value) {
        if (value == null) {
            throw new IllegalArgumentException("Structure is required");
        }
        library().canonicalId(value.getId());
    }

    @Override
    public Set<String> supportedOperations() {
        return Set.of("discover", "query", "get", "validate", "save", "update", "delete");
    }

    @Override
    public String identityRules() {
        return "server_restudio.resync:structure_id";
    }

    @Override
    public String authoritativeService() {
        return "StructureLibrary";
    }

    @Override
    public boolean changeEvents() {
        return true;
    }

    private StructureLibrary library() {
        StructureLibrary current = library.get();
        if (current == null) {
            throw new IllegalStateException("Structure library is unavailable");
        }
        return current;
    }
}

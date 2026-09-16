package restudio.resync.upgrade.lifecycle;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.ReplacementActivationRecord;
import restudio.resync.upgrade.TypedLifecycleMigrationAdapter;

public final class ReplacementActivationRecordMigrationAdapter implements TypedLifecycleMigrationAdapter {
    public static final String ID = "resync.lifecycle.replacement-activation-record";
    public static final String OWNER = ProductionPersistenceOwners.FLOW_ASSETS;
    public static final String RECORD_PATH = ReplacementActivationRecord.RECORD_FILE;

    @Override
    public String adapterId() {
        return ID;
    }

    @Override
    public Adaptation adapt(Input input) throws IOException {
        Objects.requireNonNull(input, "input");
        SourceFile source = input.file(RECORD_PATH).orElse(null);
        if (source == null) {
            return Adaptation.claimed(List.of());
        }
        input.read(source);
        ReplacementActivationRecord.read(input.root());
        return Adaptation.claimed(List.of(new Claim(RECORD_PATH, OWNER)));
    }
}

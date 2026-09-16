package restudio.resync.flow.function;

import restudio.resync.flow.identity.ContentHash;

import java.util.Optional;

@FunctionalInterface
public interface FunctionSourceStore {
    Optional<FunctionSourceRecord> load(FunctionLocator function, FunctionRevision revision);

    default Optional<FunctionSourceRecord> load(FunctionSourceLocator locator) {
        if (locator == null) {
            return Optional.empty();
        }
        Optional<FunctionSourceRecord> result = load(locator.function(), locator.revision());
        if (result == null || result.isEmpty()) {
            return Optional.empty();
        }
        FunctionSourceRecord record = result.get();
        return record != null && locator.contentHash().equals(record.contentHash())
            ? Optional.of(record) : Optional.empty();
    }

    default Optional<FunctionSourceRecord> load(FunctionLocator function, FunctionRevision revision, ContentHash contentHash) {
        if (function == null || revision == null || contentHash == null) {
            return Optional.empty();
        }
        return load(new FunctionSourceLocator(function, revision, contentHash));
    }
}

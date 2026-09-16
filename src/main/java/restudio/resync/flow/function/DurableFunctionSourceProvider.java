package restudio.resync.flow.function;

import restudio.resync.flow.identity.ContentHash;

import java.util.Objects;
import java.util.Optional;

public final class DurableFunctionSourceProvider implements TypedFunctionSourceProvider {
    private final FunctionSourceStore store;
    private final FunctionSourceMaterializer materializer;

    public DurableFunctionSourceProvider(FunctionSourceStore store) {
        this(store, new FunctionSourceMaterializer());
    }

    public DurableFunctionSourceProvider(FunctionSourceStore store, FunctionSourceMaterializer materializer) {
        this.store = Objects.requireNonNull(store, "Function Source Store Is Required");
        this.materializer = Objects.requireNonNull(materializer, "Function Source Materializer Is Required");
    }

    @Override
    public Optional<FunctionSourceDocument> resolve(FunctionLocator function, FunctionRevision revision) {
        if (function == null || revision == null) {
            return Optional.empty();
        }
        return resolveRecord(function, revision, null);
    }

    public Optional<FunctionSourceDocument> resolve(FunctionSourceLocator locator) {
        if (locator == null) {
            return Optional.empty();
        }
        return resolveRecord(locator, locator.function(), locator.revision(), locator.contentHash());
    }

    public Optional<FunctionSourceDocument> resolve(FunctionLocator function, FunctionRevision revision, ContentHash contentHash) {
        if (function == null || revision == null || contentHash == null) {
            return Optional.empty();
        }
        return resolveRecord(function, revision, contentHash);
    }

    public FunctionSourceStore store() {
        return store;
    }

    private Optional<FunctionSourceDocument> resolveRecord(FunctionLocator function, FunctionRevision revision, ContentHash expectedHash) {
        return resolveRecord(null, function, revision, expectedHash);
    }

    private Optional<FunctionSourceDocument> resolveRecord(FunctionSourceLocator requestedLocator,
                                                           FunctionLocator function,
                                                           FunctionRevision revision,
                                                           ContentHash expectedHash) {
        Optional<FunctionSourceRecord> loaded;
        try {
            loaded = requestedLocator == null ? store.load(function, revision) : store.load(requestedLocator);
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
        if (loaded == null || loaded.isEmpty()) {
            return Optional.empty();
        }
        FunctionSourceRecord record = loaded.get();
        if (record == null || !function.equals(record.function()) || !revision.equals(record.revision())
            || record.contentHash() == null || (expectedHash != null && !expectedHash.equals(record.contentHash()))) {
            return Optional.empty();
        }
        FunctionSourceDocument source = record.document();
        if (!function.equals(source.signature().function()) || !revision.equals(source.signature().revision())) {
            return Optional.empty();
        }
        ContentHash computedHash;
        try {
            computedHash = source.checksum();
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
        if (!record.contentHash().equals(computedHash) || (expectedHash != null && !expectedHash.equals(computedHash))) {
            return Optional.empty();
        }
        try {
            FunctionSourceMaterializer.Result validation = materializer.validate(source);
            return validation.materialized() && validation.diagnostics().isEmpty()
                ? Optional.of(source) : Optional.empty();
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }
}

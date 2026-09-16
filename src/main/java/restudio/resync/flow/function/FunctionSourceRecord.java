package restudio.resync.flow.function;

import restudio.resync.flow.identity.ContentHash;

import java.util.Objects;

public record FunctionSourceRecord(
    FunctionLocator function,
    FunctionRevision revision,
    ContentHash contentHash,
    FunctionSourceDocument document
) {
    public FunctionSourceRecord {
        function = Objects.requireNonNull(function, "Function Source Function Locator Is Required");
        revision = Objects.requireNonNull(revision, "Function Source Revision Is Required");
        document = Objects.requireNonNull(document, "Function Source Document Is Required");
    }

    public FunctionSourceRecord(FunctionSourceDocument document) {
        this(document.signature().function(), document.signature().revision(), document.checksum(), document);
    }

    public FunctionSourceLocator locator() {
        return contentHash == null ? null : new FunctionSourceLocator(function, revision, contentHash);
    }

    public ContentHash computedContentHash() {
        return document.checksum();
    }
}

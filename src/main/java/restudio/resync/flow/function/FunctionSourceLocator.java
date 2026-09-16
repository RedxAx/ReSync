package restudio.resync.flow.function;

import restudio.resync.flow.identity.ContentHash;

import java.util.Objects;

public record FunctionSourceLocator(
    FunctionLocator function,
    FunctionRevision revision,
    ContentHash contentHash
) implements Comparable<FunctionSourceLocator> {
    public FunctionSourceLocator {
        function = Objects.requireNonNull(function, "Function Source Function Locator Is Required");
        revision = Objects.requireNonNull(revision, "Function Source Revision Is Required");
        contentHash = Objects.requireNonNull(contentHash, "Function Source Content Hash Is Required");
    }

    public static FunctionSourceLocator of(FunctionLocator function, FunctionRevision revision, ContentHash contentHash) {
        return new FunctionSourceLocator(function, revision, contentHash);
    }

    public String canonicalText() {
        return function.canonicalText() + "\u0000" + revision.canonicalText() + "\u0000" + contentHash.canonicalText();
    }

    @Override
    public int compareTo(FunctionSourceLocator other) {
        Objects.requireNonNull(other, "Function Source Locator Is Required");
        int functionOrder = function.compareTo(other.function);
        if (functionOrder != 0) {
            return functionOrder;
        }
        int revisionOrder = revision.compareTo(other.revision);
        if (revisionOrder != 0) {
            return revisionOrder;
        }
        return contentHash.compareTo(other.contentHash);
    }
}

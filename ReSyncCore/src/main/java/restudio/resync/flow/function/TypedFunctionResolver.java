package restudio.resync.flow.function;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.graph.FunctionParameter;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.GraphVariable;
import restudio.resync.flow.graph.InspectorState;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.identity.FunctionParameterId;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypedValue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class TypedFunctionResolver implements CompiledFunctionResolver {
    private static final int MAX_CACHE_ENTRIES = 64;
    private static final long MAX_CACHE_BYTES = 32L * 1024 * 1024;
    private final TypedFunctionCompiler compiler;
    private final TypedFunctionSourceProvider sourceProvider;
    private final TypedFunctionCapabilityProvider capabilityProvider;
    private final Map<CacheKey, CachedResolution> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<IdentityKey, HashEntry> hashes = new LinkedHashMap<>(16, 0.75f, true);
    private long cacheBytes;
    private long hashBytes;

    public TypedFunctionResolver(TypedFunctionCompiler compiler,
                                 TypedFunctionSourceProvider sourceProvider,
                                 TypedFunctionCapabilityProvider capabilityProvider) {
        this.compiler = Objects.requireNonNull(compiler, "Typed Function Compiler Is Required");
        this.sourceProvider = Objects.requireNonNull(sourceProvider, "Typed Function Source Provider Is Required");
        this.capabilityProvider = Objects.requireNonNull(capabilityProvider, "Typed Function Capability Provider Is Required");
    }

    public TypedFunctionResolver(Collection<Registration> registrations) {
        this(new TypedFunctionCompiler(), registrations);
    }

    public TypedFunctionResolver(TypedFunctionCompiler compiler, Collection<Registration> registrations) {
        this(compiler, sourceProvider(registrations), capabilityProvider(registrations));
    }

    @Override
    public Optional<CompiledFunction> resolve(FunctionLocator function, FunctionRevision revision) {
        return resolveResult(function, revision).functionOptional();
    }

    public Resolution resolveResult(FunctionLocator function, FunctionRevision revision) {
        return resolveResult(function, revision, null, null, null);
    }

    public Resolution resolveResult(FunctionLocator function, FunctionRevision revision, ContentHash expectedSourceChecksum,
                                     CatalogBinding expectedBinding, ContentHash expectedCapabilityFingerprint) {
        Objects.requireNonNull(function, "Typed Function Locator Is Required");
        Objects.requireNonNull(revision, "Typed Function Revision Is Required");
        Optional<FunctionSourceDocument> source;
        try {
            source = sourceProvider.resolve(function, revision);
            if (source == null) {
                return Resolution.rejected(diagnostic("FUNCTION.PROVIDER_FAILURE", function, revision,
                    "The typed Function source provider returned no resolution result."));
            }
        } catch (RuntimeException | Error failure) {
            return Resolution.rejected(diagnostic("FUNCTION.PROVIDER_FAILURE", function, revision,
                "The typed Function source provider failed."));
        }
        if (source.isEmpty()) {
            return Resolution.rejected(diagnostic("FUNCTION.NOT_FOUND", function, revision,
                "The requested typed Function source revision is not available."));
        }
        FunctionSourceDocument value = source.get();
        if (!value.signature().function().equals(function) || !value.signature().revision().equals(revision)) {
            return Resolution.rejected(diagnostic("FUNCTION.RESOLVER_SOURCE_MISMATCH", function, revision,
                "The typed Function source provider returned a different locator or revision."));
        }
        Optional<TypedFunctionCapabilitySet> capabilities;
        try {
            capabilities = capabilityProvider.resolve(value);
            if (capabilities == null) {
                return Resolution.rejected(diagnostic("FUNCTION.PROVIDER_FAILURE", function, revision,
                    "The typed Function capability provider returned no resolution result."));
            }
        } catch (RuntimeException | Error failure) {
            return Resolution.rejected(diagnostic("FUNCTION.PROVIDER_FAILURE", function, revision,
                "The typed Function capability provider failed."));
        }
        if (capabilities.isEmpty()) {
            return Resolution.rejected(diagnostic("FUNCTION.CAPABILITY_INPUT_MISSING", function, revision,
                "The requested typed Function has no capability input set."));
        }
        return resolveResult(value, capabilities.get(), expectedSourceChecksum, expectedBinding, expectedCapabilityFingerprint);
    }

    public Resolution resolveResult(FunctionSourceDocument value, TypedFunctionCapabilitySet capabilitySet,
                                    ContentHash expectedSourceChecksum, CatalogBinding expectedBinding,
                                    ContentHash expectedCapabilityFingerprint) {
        Objects.requireNonNull(value, "Typed Function Source Is Required");
        Objects.requireNonNull(capabilitySet, "Typed Function Capabilities Are Required");
        FunctionLocator function = value.signature().function();
        FunctionRevision revision = value.signature().revision();
        if (expectedBinding != null && (!expectedBinding.equals(value.graph().catalogBinding())
            || !expectedBinding.equals(capabilitySet.catalogBinding()))) {
            return Resolution.rejected(diagnostic("FUNCTION.BINDING_MISMATCH", function, revision,
                "The typed Function source and capabilities do not match the requested catalog binding."));
        }
        HashEntry sourceHash;
        HashEntry capabilityHash;
        try {
            sourceHash = sourceStamp(value);
            capabilityHash = capabilityStamp(capabilitySet);
        } catch (RuntimeException | Error failure) {
            return Resolution.rejected(diagnostic("FUNCTION.FINGERPRINT_MISSING", function, revision,
                "The typed Function source or capability fingerprint is unavailable."));
        }
        if (expectedSourceChecksum != null && !expectedSourceChecksum.equals(sourceHash.hash())) {
            return Resolution.rejected(diagnostic("FUNCTION.RESOLVER_SOURCE_MISMATCH", function, revision,
                "The typed Function source does not match the authoritative Function checksum."));
        }
        if (expectedCapabilityFingerprint != null && !expectedCapabilityFingerprint.equals(capabilityHash.hash())) {
            return Resolution.rejected(diagnostic("FUNCTION.FINGERPRINT_MISMATCH", function, revision,
                "The typed Function capabilities do not match the requested fingerprint."));
        }
        CacheKey cacheKey = new CacheKey(function, revision, sourceHash.hash(), capabilityHash.hash(),
            capabilityHash.compilers());
        synchronized (cache) {
            CachedResolution cached = cache.get(cacheKey);
            if (cached != null) {
                return cached.resolution();
            }
            TypedFunctionCompiler.Result result = compiler.compile(sourceHash.snapshot() == null ? value : sourceHash.snapshot(), capabilitySet);
            if (result.compiled() && !sourceHash.hash().canonicalText().equals(result.function().body().metadata().get("sourceChecksum"))) {
                return Resolution.rejected(diagnostic("FUNCTION.RESOLVER_SOURCE_MISMATCH", function, revision,
                    "The typed Function source changed while its executable body was admitted."));
            }
            if (result.compiled() && !capabilityHash.hash().canonicalText().equals(result.function().body().metadata().get("capabilityFingerprint"))) {
                return Resolution.rejected(diagnostic("FUNCTION.FINGERPRINT_MISMATCH", function, revision,
                    "The typed Function capabilities changed while their executable body was admitted."));
            }
            Resolution resolved = result.compiled() ? Resolution.accepted(result.function()) : Resolution.rejected(result.diagnostics());
            long weight = sourceHash.bytes() + capabilityHash.bytes() + 1024L * capabilitySet.nodes().size();
            if (resolved.resolved() && weight <= MAX_CACHE_BYTES) {
                cache.put(cacheKey, new CachedResolution(resolved, weight));
                cacheBytes += weight;
                while (cache.size() > MAX_CACHE_ENTRIES || cacheBytes > MAX_CACHE_BYTES) {
                    CacheKey oldest = cache.keySet().iterator().next();
                    cacheBytes -= cache.remove(oldest).bytes();
                }
            }
            return resolved;
        }
    }

    public int cachedResolutionCount() {
        synchronized (cache) {
            return cache.size();
        }
    }

    public void clearCache() {
        synchronized (cache) {
            cache.clear();
            cacheBytes = 0;
        }
        synchronized (hashes) {
            hashes.clear();
            hashBytes = 0;
        }
    }

    public ContentHash sourceChecksum(FunctionSourceDocument source) {
        return sourceStamp(Objects.requireNonNull(source, "Function Source Is Required")).hash();
    }

    public ContentHash capabilityFingerprint(TypedFunctionCapabilitySet capabilities) {
        return capabilityStamp(Objects.requireNonNull(capabilities, "Typed Function Capabilities Are Required")).hash();
    }

    private HashEntry sourceStamp(FunctionSourceDocument source) {
        return stamp(source, "function-source", source::canonicalJson, () -> source.stable() || immutableSource(source), List::of);
    }

    private HashEntry capabilityStamp(TypedFunctionCapabilitySet capabilities) {
        return stamp(capabilities, "typed-function-capabilities", () -> CanonicalJson.canonicalize(capabilities.canonicalValue()),
            () -> immutable(capabilities.canonicalValue()), () -> capabilities.nodes().stream().sorted((left, right) -> left.nodeId().compareTo(right.nodeId()))
                .map(node -> new IdentityKey(node.compiler())).toList());
    }

    private HashEntry stamp(Object identity, String domain, Supplier<String> canonical,
                            Supplier<Boolean> immutable, Supplier<List<IdentityKey>> compilers) {
        IdentityKey key = new IdentityKey(identity);
        synchronized (hashes) {
            HashEntry cached = hashes.get(key);
            if (cached != null) {
                return cached;
            }
            byte[] bytes = canonical.get().getBytes(StandardCharsets.UTF_8);
            boolean memoizable = immutable.get();
            FunctionSourceDocument snapshot = !memoizable && identity instanceof FunctionSourceDocument
                ? FunctionSourceDocumentCodec.INSTANCE.decodeBytes(bytes) : null;
            HashEntry admitted = new HashEntry(ContentHash.of(CanonicalJson.sha256Canonical(domain, bytes)),
                4L * bytes.length, compilers.get(), snapshot);
            if (memoizable && identity instanceof FunctionSourceDocument source) {
                source.retainChecksum(admitted.hash());
            }
            if (admitted.bytes() <= MAX_CACHE_BYTES && memoizable) {
                hashes.put(key, admitted);
                hashBytes += admitted.bytes();
                while (hashes.size() > MAX_CACHE_ENTRIES || hashBytes > MAX_CACHE_BYTES) {
                    IdentityKey oldest = hashes.keySet().iterator().next();
                    hashBytes -= hashes.remove(oldest).bytes();
                }
            }
            return admitted;
        }
    }

    private static boolean immutableSource(FunctionSourceDocument source) {
        if (!immutable(source.signature().canonicalValue()) || !immutable(source.unknown().fields())
            || !immutable(source.graph().resource().canonicalValue()) || !immutable(source.graph().unknown().fields())
            || source.graph().variables().stream().anyMatch(value -> value.getClass() != GraphVariable.class
                || !immutable(value.type()) || !immutable(value.value()) || !immutable(value.unknown().fields()))
            || source.graph().functions().stream().anyMatch(value -> !immutable(value.function().canonicalValue())
                || !immutable(value.unknown().fields()) || value.inputs().stream().anyMatch(parameter -> !immutableParameter(parameter))
                || value.outputs().stream().anyMatch(parameter -> !immutableParameter(parameter)))
            || source.graph().requiredCapabilities().stream()
                .anyMatch(value -> !immutable(value.canonicalValue()))) {
            return false;
        }
        for (GraphNode node : source.graph().nodes()) {
            if (!immutable(node.definition().canonicalValue()) || !immutable(node.unknown().fields())
                || !immutablePins(node.values()) || !immutablePins(node.inspector())
                || !node.inspectorFields().values().stream().allMatch(TypedFunctionResolver::immutable)
                || !immutableInspector(node.inspectorState())) {
                return false;
            }
            for (var branch : node.branches()) {
                if (!immutable(branch.unknown().fields()) || branch.cases().stream().anyMatch(value ->
                    !immutable(value.unknown().fields()) || !immutablePins(value.values()) || !immutableInspector(value.inspectorState()))) {
                    return false;
                }
            }
            for (var repeatable : node.repeatables()) {
                if (!immutable(repeatable.unknown().fields()) || repeatable.elements().stream().anyMatch(value ->
                    !immutable(value.unknown().fields()) || !immutablePins(value.values()))) {
                    return false;
                }
            }
        }
        return source.graph().connections().stream().allMatch(value -> immutable(value.unknown().fields())
            && immutable(value.source().unknown().fields()) && immutable(value.target().unknown().fields()))
            && source.graph().passthroughs().stream().allMatch(value -> immutable(value.unknown().fields()));
    }

    private static boolean immutableParameter(FunctionParameter parameter) {
        return immutable(parameter.type()) && immutable(parameter.defaultValue()) && immutable(parameter.unknown().fields());
    }

    private static boolean immutablePins(Map<?, PinValue> pins) {
        return pins.values().stream().allMatch(value -> immutable(value.value()) && immutable(value.unknown().fields()));
    }

    private static boolean immutableInspector(InspectorState inspector) {
        return immutable(inspector.unknown().fields()) && immutablePins(inspector.legacyFields())
            && inspector.fields().values().stream().allMatch(TypedFunctionResolver::immutable);
    }

    private static boolean immutable(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Character || value instanceof UUID
            || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
            || value instanceof Float || value instanceof Double || value instanceof FunctionParameterId
            || value instanceof FunctionRevision) {
            return true;
        }
        if (value instanceof BigInteger || value instanceof BigDecimal) {
            return value.getClass() == BigInteger.class || value.getClass() == BigDecimal.class;
        }
        if (value instanceof TypedValue typed) {
            return immutable(typed.canonicalValue());
        }
        if (value instanceof TypeExpr type) {
            return immutable(type.canonicalValue());
        }
        if (value instanceof FunctionLocator locator) {
            return immutable(locator.canonicalValue());
        }
        if (value instanceof FunctionCancellation cancellation) {
            return immutable(cancellation.canonicalValue());
        }
        if (value instanceof Map<?, ?> values) {
            return values.entrySet().stream().allMatch(entry -> entry.getKey() instanceof String && immutable(entry.getValue()));
        }
        if (value instanceof List<?> values) {
            return values.stream().allMatch(TypedFunctionResolver::immutable);
        }
        return false;
    }

    private static TypedFunctionSourceProvider sourceProvider(Collection<Registration> registrations) {
        Map<Key, Registration> values = registrations(registrations);
        return (function, revision) -> {
            Registration registration = values.get(new Key(function, revision));
            return registration == null ? Optional.empty() : Optional.of(registration.source());
        };
    }

    private static TypedFunctionCapabilityProvider capabilityProvider(Collection<Registration> registrations) {
        Map<Key, Registration> values = registrations(registrations);
        return source -> {
            Registration registration = values.get(new Key(source.signature().function(), source.signature().revision()));
            return registration == null ? Optional.empty() : Optional.of(registration.capabilities());
        };
    }

    private static Map<Key, Registration> registrations(Collection<Registration> values) {
        Objects.requireNonNull(values, "Typed Function Registrations Are Required");
        LinkedHashMap<Key, Registration> registrations = new LinkedHashMap<>();
        values.forEach(value -> {
            Registration registration = Objects.requireNonNull(value, "Typed Function Registration Cannot Be Null");
            Key key = new Key(registration.source().signature().function(), registration.source().signature().revision());
            if (registrations.putIfAbsent(key, registration) != null) {
                throw new IllegalArgumentException("Typed Function Resolver Contains A Duplicate Locator And Revision");
            }
        });
        return Map.copyOf(registrations);
    }

    private static FunctionDiagnostic diagnostic(String code, FunctionLocator function, FunctionRevision revision, String message) {
        return new FunctionDiagnostic(code, FunctionDiagnostic.Severity.ERROR, FunctionDiagnostic.Phase.CAPABILITY,
            "function-resolution", message,
            "Provide the exact typed Function source and capability revision before execution.", function, revision,
            null, null, null, Map.of(), Map.of("boundary", TypedFunctionCompiler.BOUNDARY), true);
    }

    private record Key(FunctionLocator function, FunctionRevision revision) {
    }

    private record CacheKey(FunctionLocator function, FunctionRevision revision,
                            ContentHash sourceChecksum, ContentHash capabilityFingerprint, List<IdentityKey> compilers) {
    }

    private record HashEntry(ContentHash hash, long bytes, List<IdentityKey> compilers, FunctionSourceDocument snapshot) {}

    private record CachedResolution(Resolution resolution, long bytes) {}

    private record IdentityKey(Object value) {
        @Override
        public boolean equals(Object other) {
            return other instanceof IdentityKey key && key.value == value;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(value);
        }
    }

    public record Registration(FunctionSourceDocument source, TypedFunctionCapabilitySet capabilities) {
        public Registration {
            source = Objects.requireNonNull(source, "Typed Function Registration Source Is Required");
            capabilities = Objects.requireNonNull(capabilities, "Typed Function Registration Capabilities Are Required");
        }
    }

    public record Resolution(CompiledFunction function, List<FunctionDiagnostic> diagnostics) {
        public Resolution {
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "Typed Function Resolution Diagnostics Are Required"));
            if (function != null && !diagnostics.isEmpty()) {
                throw new IllegalArgumentException("An Accepted Typed Function Resolution Cannot Contain Diagnostics");
            }
            if (function == null && diagnostics.isEmpty()) {
                throw new IllegalArgumentException("A Rejected Typed Function Resolution Requires Diagnostics");
            }
        }

        public static Resolution accepted(CompiledFunction function) {
            return new Resolution(Objects.requireNonNull(function, "Resolved Compiled Function Is Required"), List.of());
        }

        public static Resolution rejected(FunctionDiagnostic diagnostic) {
            return new Resolution(null, List.of(Objects.requireNonNull(diagnostic, "Typed Function Resolution Diagnostic Is Required")));
        }

        public static Resolution rejected(List<FunctionDiagnostic> diagnostics) {
            return new Resolution(null, diagnostics);
        }

        public boolean resolved() {
            return function != null;
        }

        public Optional<CompiledFunction> functionOptional() {
            return Optional.ofNullable(function);
        }
    }
}

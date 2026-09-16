package restudio.resync.flow.function;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class TypedFunctionResolver implements CompiledFunctionResolver {
    private final TypedFunctionCompiler compiler;
    private final TypedFunctionSourceProvider sourceProvider;
    private final TypedFunctionCapabilityProvider capabilityProvider;
    private final Map<CacheKey, Resolution> cache = new ConcurrentHashMap<>();

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
        Objects.requireNonNull(function, "Typed Function Locator Is Required");
        Objects.requireNonNull(revision, "Typed Function Revision Is Required");
        Optional<FunctionSourceDocument> source;
        try {
            source = sourceProvider.resolve(function, revision);
            if (source == null) {
                return Resolution.rejected(diagnostic("FUNCTION.RESOLVER_SOURCE_NULL", function, revision,
                    "The typed Function source provider returned no resolution result."));
            }
        } catch (RuntimeException failure) {
            return Resolution.rejected(diagnostic("FUNCTION.RESOLVER_SOURCE_FAILURE", function, revision,
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
                return Resolution.rejected(diagnostic("FUNCTION.RESOLVER_CAPABILITY_NULL", function, revision,
                    "The typed Function capability provider returned no resolution result."));
            }
        } catch (RuntimeException failure) {
            return Resolution.rejected(diagnostic("FUNCTION.RESOLVER_CAPABILITY_FAILURE", function, revision,
                "The typed Function capability provider failed."));
        }
        if (capabilities.isEmpty()) {
            return Resolution.rejected(diagnostic("FUNCTION.CAPABILITY_INPUT_MISSING", function, revision,
                "The requested typed Function has no capability input set."));
        }
        TypedFunctionCapabilitySet capabilitySet = capabilities.get();
        CacheKey cacheKey = new CacheKey(function, revision, value.checksum(), capabilitySet.fingerprint());
        Resolution cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        TypedFunctionCompiler.Result result = compiler.compile(value, capabilitySet);
        Resolution resolved = result.compiled() ? Resolution.accepted(result.function()) : Resolution.rejected(result.diagnostics());
        cache.putIfAbsent(cacheKey, resolved);
        return resolved;
    }

    public int cachedResolutionCount() {
        return cache.size();
    }

    public void clearCache() {
        cache.clear();
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
                            restudio.resync.flow.identity.ContentHash sourceChecksum,
                            restudio.resync.flow.identity.ContentHash capabilityFingerprint) {
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

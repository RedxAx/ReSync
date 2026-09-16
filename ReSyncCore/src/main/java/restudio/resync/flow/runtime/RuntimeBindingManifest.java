package restudio.resync.flow.runtime;

import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.diagnostic.Diagnostic;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class RuntimeBindingManifest {
    public static final int VERSION = 1;
    private final List<RuntimeProviderDescriptor> providers;
    private final List<RuntimeBindingDescriptor> bindings;
    private final Map<RuntimeBindingKey, ContentHash> executionFingerprints;
    private final Map<RuntimeBindingKey, Map<String, Object>> invalidationInputs;
    private final List<Diagnostic> diagnostics;
    private final List<Map<String, Object>> diagnosticCanonicalValues;
    private final Map<String, Object> unknown;
    private final ContentHash bindingManifestHash;
    private final String canonicalForm;

    private RuntimeBindingManifest(
        Collection<RuntimeProviderDescriptor> providers,
        Collection<RuntimeBindingDescriptor> bindings,
        Map<RuntimeBindingKey, ContentHash> executionFingerprints,
        Collection<Diagnostic> diagnostics,
        Map<String, ?> unknown,
        Map<RuntimeBindingKey, ? extends Map<String, ?>> invalidationInputs
    ) {
        this(providers, bindings, executionFingerprints, diagnostics, unknown, invalidationInputs, null);
    }

    private RuntimeBindingManifest(
        Collection<RuntimeProviderDescriptor> providers,
        Collection<RuntimeBindingDescriptor> bindings,
        Map<RuntimeBindingKey, ContentHash> executionFingerprints,
        Collection<Diagnostic> diagnostics,
        Map<String, ?> unknown,
        Map<RuntimeBindingKey, ? extends Map<String, ?>> invalidationInputs,
        Collection<? extends Map<String, ?>> diagnosticCanonicalValues
    ) {
        this.providers = sortedProviders(providers);
        this.bindings = sortedBindings(bindings);
        this.executionFingerprints = sortedFingerprints(executionFingerprints);
        this.invalidationInputs = sortedInvalidationInputs(invalidationInputs, this.bindings);
        SortedDiagnostics sortedDiagnostics = sortedDiagnostics(diagnostics, diagnosticCanonicalValues);
        this.diagnostics = sortedDiagnostics.diagnostics();
        this.diagnosticCanonicalValues = sortedDiagnostics.canonicalValues();
        this.unknown = RuntimeCanonicalSupport.unknown(unknown, "Manifest Unknown Data");
        RuntimeCanonicalSupport.rejectCollisions(this.unknown, "Manifest Unknown Data", Set.of(
            "kind", "version", "providers", "bindings", "executionFingerprints", "invalidationInputs", "diagnostics", "bindingManifestHash"));
        var providerIds = this.providers.stream().map(RuntimeProviderDescriptor::provider).collect(Collectors.toSet());
        if (this.bindings.stream().map(RuntimeBindingDescriptor::provider).anyMatch(provider -> !providerIds.contains(provider))) {
            throw new IllegalArgumentException("Manifest Binding References An Unknown Provider");
        }
        if (!this.executionFingerprints.keySet().equals(this.bindings.stream()
            .map(RuntimeBindingDescriptor::key)
            .collect(Collectors.toSet()))) {
            throw new IllegalArgumentException("Execution Fingerprints Must Match Manifest Bindings");
        }
        this.bindings.forEach(binding -> {
            ContentHash fingerprint = this.executionFingerprints.get(binding.key());
            if (!binding.executionFingerprint(this.invalidationInputs.get(binding.key())).equals(fingerprint)) {
                throw new IllegalArgumentException("Execution Fingerprint Does Not Match Binding: " + binding.key().canonical());
            }
        });
        this.bindingManifestHash = ContentHash.of(CanonicalJson.sha256("runtime-manifest", canonicalContentValue()));
        this.canonicalForm = buildCanonicalForm();
    }

    public static RuntimeBindingManifest create(
        Collection<RuntimeProviderDescriptor> providers,
        Collection<RuntimeBindingDescriptor> bindings,
        Map<RuntimeBindingKey, ContentHash> executionFingerprints,
        Collection<Diagnostic> diagnostics
    ) {
        return new RuntimeBindingManifest(providers, bindings, executionFingerprints, diagnostics, Map.of(), Map.of());
    }

    public static RuntimeBindingManifest create(
        Collection<RuntimeProviderDescriptor> providers,
        Collection<RuntimeBindingDescriptor> bindings,
        Map<RuntimeBindingKey, ContentHash> executionFingerprints,
        Collection<Diagnostic> diagnostics,
        Map<String, ?> unknown
    ) {
        return new RuntimeBindingManifest(providers, bindings, executionFingerprints, diagnostics, unknown, Map.of());
    }

    public static RuntimeBindingManifest create(
        Collection<RuntimeProviderDescriptor> providers,
        Collection<RuntimeBindingDescriptor> bindings,
        Map<RuntimeBindingKey, ContentHash> executionFingerprints,
        Collection<Diagnostic> diagnostics,
        Map<String, ?> unknown,
        Map<RuntimeBindingKey, ? extends Map<String, ?>> invalidationInputs
    ) {
        return new RuntimeBindingManifest(providers, bindings, executionFingerprints, diagnostics, unknown, invalidationInputs);
    }

    static RuntimeBindingManifest createDecoded(
        Collection<RuntimeProviderDescriptor> providers,
        Collection<RuntimeBindingDescriptor> bindings,
        Map<RuntimeBindingKey, ContentHash> executionFingerprints,
        Collection<Diagnostic> diagnostics,
        Collection<? extends Map<String, ?>> diagnosticCanonicalValues,
        Map<String, ?> unknown,
        Map<RuntimeBindingKey, ? extends Map<String, ?>> invalidationInputs
    ) {
        return new RuntimeBindingManifest(
            providers,
            bindings,
            executionFingerprints,
            diagnostics,
            unknown,
            invalidationInputs,
            diagnosticCanonicalValues);
    }

    private static List<RuntimeProviderDescriptor> sortedProviders(Collection<RuntimeProviderDescriptor> values) {
        Objects.requireNonNull(values, "Providers Are Required");
        ArrayList<RuntimeProviderDescriptor> sorted = new ArrayList<>(values.stream()
            .map(value -> Objects.requireNonNull(value, "Providers Cannot Contain Null")).toList());
        sorted.sort((left, right) -> left.provider().compareTo(right.provider()));
        if (sorted.stream().map(RuntimeProviderDescriptor::provider).distinct().count() != sorted.size()) {
            throw new IllegalArgumentException("Manifest Contains Duplicate Providers");
        }
        return List.copyOf(sorted);
    }

    private static List<RuntimeBindingDescriptor> sortedBindings(Collection<RuntimeBindingDescriptor> values) {
        Objects.requireNonNull(values, "Bindings Are Required");
        ArrayList<RuntimeBindingDescriptor> sorted = new ArrayList<>(values.stream()
            .map(value -> Objects.requireNonNull(value, "Bindings Cannot Contain Null")).toList());
        sorted.sort((left, right) -> left.key().compareTo(right.key()));
        if (sorted.stream().map(RuntimeBindingDescriptor::key).distinct().count() != sorted.size()) {
            throw new IllegalArgumentException("Manifest Contains Duplicate Bindings");
        }
        return List.copyOf(sorted);
    }

    private static Map<RuntimeBindingKey, ContentHash> sortedFingerprints(Map<RuntimeBindingKey, ContentHash> values) {
        Objects.requireNonNull(values, "Execution Fingerprints Are Required");
        LinkedHashMap<RuntimeBindingKey, ContentHash> sorted = new LinkedHashMap<>();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            RuntimeBindingKey key = Objects.requireNonNull(entry.getKey(), "Fingerprint Key Cannot Be Null");
            ContentHash fingerprint = Objects.requireNonNull(entry.getValue(), "Fingerprint Cannot Be Null");
            sorted.put(key, fingerprint);
        });
        return Collections.unmodifiableMap(sorted);
    }

    private static Map<RuntimeBindingKey, Map<String, Object>> sortedInvalidationInputs(
        Map<RuntimeBindingKey, ? extends Map<String, ?>> values,
        List<RuntimeBindingDescriptor> bindings
    ) {
        Objects.requireNonNull(values, "Execution Invalidation Inputs Are Required");
        Set<RuntimeBindingKey> bindingKeys = bindings.stream().map(RuntimeBindingDescriptor::key).collect(Collectors.toSet());
        LinkedHashMap<RuntimeBindingKey, Map<String, Object>> sorted = new LinkedHashMap<>();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            RuntimeBindingKey key = Objects.requireNonNull(entry.getKey(), "Invalidation Input Key Cannot Be Null");
            if (!bindingKeys.contains(key)) {
                throw new IllegalArgumentException("Invalidation Inputs Reference An Unknown Binding: " + key.canonical());
            }
            sorted.put(key, RuntimeCanonicalSupport.canonicalMap(entry.getValue(), "Execution Invalidation Inputs"));
        });
        for (RuntimeBindingKey key : bindingKeys) {
            sorted.putIfAbsent(key, Map.of());
        }
        LinkedHashMap<RuntimeBindingKey, Map<String, Object>> ordered = new LinkedHashMap<>();
        sorted.keySet().stream().sorted().forEach(key -> ordered.put(key, sorted.get(key)));
        return Collections.unmodifiableMap(ordered);
    }

    private static SortedDiagnostics sortedDiagnostics(
        Collection<Diagnostic> values,
        Collection<? extends Map<String, ?>> canonicalValues
    ) {
        Objects.requireNonNull(values, "Diagnostics Are Required");
        List<Diagnostic> diagnostics = values.stream()
            .map(value -> Objects.requireNonNull(value, "Diagnostics Cannot Contain Null")).toList();
        List<Map<String, Object>> normalizedValues;
        if (canonicalValues == null) {
            normalizedValues = diagnostics.stream().map(Diagnostic::toMap).toList();
        } else {
            if (canonicalValues.size() != diagnostics.size()) {
                throw new IllegalArgumentException("Diagnostic Canonical Values Must Match Diagnostics");
            }
            normalizedValues = canonicalValues.stream()
                .map(value -> RuntimeCanonicalSupport.canonicalMap(value, "Diagnostic Canonical Data"))
                .toList();
        }
        ArrayList<DiagnosticEntry> entries = new ArrayList<>();
        for (int index = 0; index < diagnostics.size(); index++) {
            entries.add(new DiagnosticEntry(diagnostics.get(index), normalizedValues.get(index)));
        }
        entries.sort(Comparator.comparing(entry -> CanonicalJson.canonicalize(entry.canonicalValue())));
        return new SortedDiagnostics(
            entries.stream().map(DiagnosticEntry::diagnostic).toList(),
            entries.stream().map(DiagnosticEntry::canonicalValue).toList());
    }

    private String buildCanonicalForm() {
        return CanonicalJson.canonicalize(canonicalContentValue());
    }

    private Map<String, Object> canonicalContentValue() {
        Map<String, Object> fingerprints = new LinkedHashMap<>();
        executionFingerprints.forEach((key, value) -> fingerprints.put(key.canonical(), value.canonicalText()));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("kind", "runtime-binding-manifest");
        values.put("version", VERSION);
        values.put("providers", providers.stream().map(RuntimeProviderDescriptor::canonicalValue).toList());
        values.put("bindings", bindings.stream().map(binding -> {
            Map<String, Object> bindingValue = new LinkedHashMap<>(binding.canonicalValueWithoutFingerprint());
            bindingValue.put("fingerprint", executionFingerprints.get(binding.key()).canonicalText());
            return bindingValue;
        }).toList());
        values.put("executionFingerprints", fingerprints);
        Map<String, Object> invalidation = new LinkedHashMap<>();
        this.invalidationInputs.forEach((key, input) -> invalidation.put(key.canonical(), input));
        values.put("invalidationInputs", invalidation);
        values.put("diagnostics", diagnosticCanonicalValues);
        return RuntimeCanonicalSupport.merge(unknown, values);
    }

    public Map<String, Object> canonicalValue() {
        Map<String, Object> values = new LinkedHashMap<>(canonicalContentValue());
        values.put("bindingManifestHash", bindingManifestHash.canonicalText());
        return Collections.unmodifiableMap(values);
    }

    public String wireCanonicalForm() {
        return CanonicalJson.canonicalize(canonicalValue());
    }

    public static RuntimeBindingManifest fromCanonical(String value) {
        return RuntimeCanonicalDecoder.parse(value, RuntimeCanonicalDecoder::manifest);
    }

    public static RuntimeBindingManifest fromCanonical(Map<String, ?> value) {
        return RuntimeCanonicalDecoder.manifest(RuntimeCanonicalDecoder.object(value, "manifest"));
    }

    public List<RuntimeProviderDescriptor> providers() {
        return providers;
    }

    public List<RuntimeBindingDescriptor> bindings() {
        return bindings;
    }

    public Optional<RuntimeBindingDescriptor> binding(RuntimeBindingKey key) {
        Objects.requireNonNull(key, "Binding Key Is Required");
        return bindings.stream().filter(value -> value.key().equals(key)).findFirst();
    }

    public Map<RuntimeBindingKey, ContentHash> executionFingerprints() {
        return executionFingerprints;
    }

    public Map<RuntimeBindingKey, Map<String, Object>> invalidationInputs() {
        return invalidationInputs;
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    public List<Map<String, Object>> diagnosticCanonicalValues() {
        return diagnosticCanonicalValues;
    }

    public Map<String, Object> unknown() {
        return unknown;
    }

    public ContentHash bindingManifestHash() {
        return bindingManifestHash;
    }

    public int version() {
        return VERSION;
    }

    public String canonicalForm() {
        return canonicalForm;
    }

    public boolean matches(ContentHash expectedBindingManifestHash) {
        return bindingManifestHash.equals(Objects.requireNonNull(expectedBindingManifestHash, "Expected Binding Manifest Hash Is Required"));
    }

    public boolean matchesBindingManifest(ContentHash expectedBindingManifestHash) {
        return matches(expectedBindingManifestHash);
    }

    public boolean matches(CatalogBinding catalogBinding) {
        Objects.requireNonNull(catalogBinding, "Catalog Binding Is Required");
        return matches(catalogBinding.bindingManifestHash());
    }

    public Optional<ContentHash> executionFingerprint(RuntimeBindingKey key) {
        Objects.requireNonNull(key, "Binding Key Is Required");
        return Optional.ofNullable(executionFingerprints.get(key));
    }

    public Optional<Map<String, Object>> invalidationInputs(RuntimeBindingKey key) {
        Objects.requireNonNull(key, "Binding Key Is Required");
        return Optional.ofNullable(invalidationInputs.get(key));
    }

    private record DiagnosticEntry(Diagnostic diagnostic, Map<String, Object> canonicalValue) {
    }

    private record SortedDiagnostics(List<Diagnostic> diagnostics, List<Map<String, Object>> canonicalValues) {
    }
}

package restudio.resync.flow.validation;

import restudio.flow.data.FlowGraph;
import restudio.resync.api.ExtensionRegistryActivation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class FlowGraphValidationRegistry {
    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();
    private volatile ExtensionRegistryActivation activation;

    public void bindActivation(ExtensionRegistryActivation activation) {
        this.activation = activation;
    }

    public void register(String owner, String validatorId, FlowGraphValidationRule rule) {
        String normalizedOwner = requireId(owner, "Validator owner");
        String normalizedId = requireId(validatorId, "Validator ID");
        if (!normalizedId.startsWith(normalizedOwner + ":")) {
            throw new IllegalArgumentException("Validator ID must use the " + normalizedOwner + " namespace: " + normalizedId);
        }
        if (rule == null) {
            throw new IllegalArgumentException("Validation rule is required: " + normalizedId);
        }
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            current.update(FlowGraphValidationRegistry.class, target -> {
                target.registerLocal(normalizedOwner, normalizedId, rule);
                return null;
            });
            return;
        }
        registerLocal(normalizedOwner, normalizedId, rule);
    }

    private void registerLocal(String normalizedOwner, String normalizedId, FlowGraphValidationRule rule) {
        Registration existing = registrations.putIfAbsent(normalizedId, new Registration(normalizedOwner, normalizedId, rule));
        if (existing != null) {
            throw new IllegalArgumentException("Duplicate Flow validator ID: " + normalizedId);
        }
    }

    public void unregister(String owner, String validatorId) {
        String normalizedOwner = requireId(owner, "Validator owner");
        String normalizedId = requireId(validatorId, "Validator ID");
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            current.update(FlowGraphValidationRegistry.class, target -> {
                target.unregisterLocal(normalizedOwner, normalizedId);
                return null;
            });
            return;
        }
        unregisterLocal(normalizedOwner, normalizedId);
    }

    private void unregisterLocal(String normalizedOwner, String normalizedId) {
        registrations.computeIfPresent(normalizedId, (ignored, registration) -> registration.owner().equals(normalizedOwner) ? null : registration);
    }

    public void unregisterOwner(String owner) {
        String normalizedOwner = requireId(owner, "Validator owner");
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            current.update(FlowGraphValidationRegistry.class, target -> {
                target.unregisterOwnerLocal(normalizedOwner);
                return null;
            });
            return;
        }
        unregisterOwnerLocal(normalizedOwner);
    }

    private void unregisterOwnerLocal(String normalizedOwner) {
        registrations.entrySet().removeIf(entry -> entry.getValue().owner().equals(normalizedOwner));
    }

    public List<FlowGraphDiagnostic> validate(FlowGraph graph) {
        FlowGraphValidationRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.validate(graph);
        }
        List<FlowGraphDiagnostic> diagnostics = new ArrayList<>();
        for (Registration registration : orderedRegistrations()) {
            try {
                List<FlowGraphDiagnostic> contributed = registration.rule().validate(graph);
                if (contributed != null) {
                    for (FlowGraphDiagnostic diagnostic : contributed) {
                        if (diagnostic == null || diagnostic.code().isBlank() || diagnostic.message().isBlank() || diagnostic.remediation().isBlank()) {
                            diagnostics.add(registrationFailure(registration, graph, "returned an invalid diagnostic"));
                        } else {
                            diagnostics.add(diagnostic);
                        }
                    }
                }
            } catch (RuntimeException exception) {
                diagnostics.add(registrationFailure(registration, graph, "failed: " + message(exception)));
            }
        }
        return List.copyOf(diagnostics);
    }

    public List<Map<String, Object>> inventory() {
        FlowGraphValidationRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.inventory();
        }
        return orderedRegistrations().stream().map(registration -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", registration.id());
            item.put("owner", registration.owner());
            item.put("disposition", "supported");
            item.put("requirements", List.of("EXT-001", "EXT-002", "EXEC-001", "EXEC-004"));
            return Map.copyOf(item);
        }).toList();
    }

    public List<String> incarnationFingerprint() {
        FlowGraphValidationRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.incarnationFingerprint();
        }
        return orderedRegistrations().stream()
            .map(registration -> registration.id() + ":" + Integer.toHexString(System.identityHashCode(registration.rule())))
            .toList();
    }

    public boolean contains(String validatorId) {
        FlowGraphValidationRegistry activeRegistry = activeRegistry();
        if (activeRegistry != this) {
            return activeRegistry.contains(validatorId);
        }
        return validatorId != null && registrations.containsKey(validatorId.trim().toLowerCase(Locale.ROOT));
    }

    public synchronized FlowGraphValidationRegistry copy() {
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            return current.snapshot().validators();
        }
        FlowGraphValidationRegistry copy = new FlowGraphValidationRegistry();
        copy.registrations.putAll(registrations);
        return copy;
    }

    public synchronized void replaceFrom(FlowGraphValidationRegistry staged) {
        ExtensionRegistryActivation current = activation;
        if (current != null) {
            current.update(FlowGraphValidationRegistry.class, target -> {
                target.replaceFromLocal(staged);
                return null;
            });
            return;
        }
        replaceFromLocal(staged);
    }

    private synchronized void replaceFromLocal(FlowGraphValidationRegistry staged) {
        if (staged == null) {
            throw new IllegalArgumentException("A staged Flow validation registry is required");
        }
        registrations.clear();
        registrations.putAll(staged.registrations);
    }

    private List<Registration> orderedRegistrations() {
        return registrations.values().stream().sorted(Comparator.comparing(Registration::id, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    private String requireId(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String message(RuntimeException exception) {
        return exception.getMessage() != null && !exception.getMessage().isBlank() ? exception.getMessage() : exception.getClass().getSimpleName();
    }

    private FlowGraphDiagnostic registrationFailure(Registration registration, FlowGraph graph, String reason) {
        return new FlowGraphDiagnostic(FlowGraphDiagnostic.Severity.ERROR, "EXTENSION_VALIDATOR_FAILED", graph != null ? graph.getId() : "", "", "",
            "Validator " + registration.id() + " " + reason, "Update or remove extension " + registration.owner());
    }

    private record Registration(String owner, String id, FlowGraphValidationRule rule) {
    }

    private FlowGraphValidationRegistry activeRegistry() {
        ExtensionRegistryActivation current = activation;
        return current != null ? current.snapshot().validators() : this;
    }
}

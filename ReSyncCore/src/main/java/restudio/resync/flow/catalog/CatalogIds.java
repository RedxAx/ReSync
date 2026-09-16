package restudio.resync.flow.catalog;

import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.LocalId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.type.TypeReference;

import java.util.Locale;
import java.util.Objects;

final class CatalogIds {
    private CatalogIds() {
    }

    static String local(String value, String field) {
        return CapabilityId.of(value).value();
    }

    static <T extends LocalId> ContractRef<T> reference(OwnerId owner, T id) {
        return ContractRef.of(owner, id);
    }

    static ContractRef<CapabilityId> reference(TypeReference id) {
        Objects.requireNonNull(id, "type reference");
        return reference(OwnerId.of(id.ownerId()), CapabilityId.of(id.localId()));
    }

    static String text(String value, String field, int maximum) {
        String normalized = required(value, field);
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " exceeds " + maximum + " characters");
        }
        return normalized;
    }

    static String description(String value, String field, int maximum, int minimum) {
        String normalized = text(value, field, maximum);
        if (normalized.length() < minimum) {
            throw new IllegalArgumentException(field + " must contain at least " + minimum + " characters");
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.matches("^(todo|tbd|placeholder|lorem ipsum|n/?a|none|unknown|fill in|coming soon)([ .,:;-].*)?$")) {
            throw new IllegalArgumentException(field + " must contain authored product text");
        }
        return normalized;
    }

    static String optionalText(String value, String field, int maximum) {
        return value == null ? null : text(value, field, maximum);
    }

    static String required(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

}

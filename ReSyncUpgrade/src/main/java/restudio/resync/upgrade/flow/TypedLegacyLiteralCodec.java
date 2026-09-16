package restudio.resync.upgrade.flow;

import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.contract.canonical.JsonValue.JsonBoolean;
import restudio.resync.contract.canonical.JsonValue.JsonNumber;
import restudio.resync.contract.canonical.JsonValue.JsonString;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypedValue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class TypedLegacyLiteralCodec {
    private TypedLegacyLiteralCodec() {
    }

    public static Optional<TypedValue> decode(TypeExpr type, JsonValue source) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
        if (!(type instanceof TypeExpr.Named named) || !named.arguments().isEmpty()
            || !"builtin".equals(named.reference().ownerId())
            || !(source instanceof JsonString || source instanceof JsonBoolean || source instanceof JsonNumber)) {
            return Optional.empty();
        }
        try {
            return switch (named.reference().localId()) {
                case "string" -> source instanceof JsonString value
                    ? Optional.of(TypedValue.value(type, value.value())) : Optional.empty();
                case "boolean" -> source instanceof JsonBoolean value
                    ? Optional.of(TypedValue.value(type, value.value())) : Optional.empty();
                case "integer" -> source instanceof JsonNumber value
                    ? Optional.of(TypedValue.value(type, integer(value.value()))) : Optional.empty();
                case "number" -> source instanceof JsonNumber value
                    ? Optional.of(TypedValue.value(type, decimal(value.value()))) : Optional.empty();
                case "uuid" -> source instanceof JsonString value
                    ? Optional.of(TypedValue.value(type, uuid(value.value()))) : Optional.empty();
                default -> Optional.empty();
            };
        } catch (ArithmeticException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static BigInteger integer(BigDecimal value) {
        return value.toBigIntegerExact();
    }

    private static BigDecimal decimal(BigDecimal value) {
        BigDecimal result = value;
        return result.signum() == 0 ? BigDecimal.ZERO : result.stripTrailingZeros();
    }

    private static UUID uuid(String value) {
        UUID result = UUID.fromString(value);
        if (!result.toString().equals(value)) {
            throw new IllegalArgumentException("UUID must be canonical");
        }
        return result;
    }
}

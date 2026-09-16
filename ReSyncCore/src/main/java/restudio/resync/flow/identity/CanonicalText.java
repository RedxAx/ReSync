package restudio.resync.flow.identity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public final class CanonicalText {
    public static final Comparator<String> ORDER = CanonicalText::compareCodePoints;

    private CanonicalText() {
    }

    public static int compare(String left, String right) {
        return ORDER.compare(Objects.requireNonNull(left, "Left text is required"), Objects.requireNonNull(right, "Right text is required"));
    }

    public static <T> Comparator<T> orderBy(Function<T, String> text) {
        Objects.requireNonNull(text, "Text function is required");
        return (left, right) -> compare(text.apply(left), text.apply(right));
    }

    public static <T> List<T> sorted(Collection<? extends T> values, Function<T, String> text) {
        Objects.requireNonNull(values, "Values are required");
        ArrayList<T> ordered = new ArrayList<>(values);
        ordered.sort(orderBy(text));
        return List.copyOf(ordered);
    }

    private static int compareCodePoints(String left, String right) {
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            int leftPoint = left.codePointAt(leftIndex);
            int rightPoint = right.codePointAt(rightIndex);
            if (leftPoint != rightPoint) {
                return Integer.compare(leftPoint, rightPoint);
            }
            leftIndex += Character.charCount(leftPoint);
            rightIndex += Character.charCount(rightPoint);
        }
        return Integer.compare(left.length(), right.length());
    }
}

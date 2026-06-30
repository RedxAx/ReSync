package restudio.resync.customcontent;

import com.google.gson.Gson;

import java.util.List;
import java.util.Map;

public class ItemAttributeValidationException extends IllegalArgumentException {
    private static final Gson GSON = new Gson();

    private final List<Map<String, Object>> errors;

    public ItemAttributeValidationException(List<Map<String, Object>> errors) {
        super("ATTRIBUTE_VALIDATION:" + GSON.toJson(errors != null ? errors : List.of()));
        this.errors = errors != null ? List.copyOf(errors) : List.of();
    }

    public List<Map<String, Object>> getErrors() {
        return errors;
    }
}

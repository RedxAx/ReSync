package restudio.resync.flow.automation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import restudio.resync.Log;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

final class AutomationTaskStore {
    private static final Gson GSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private static final Type STATE_LIST = new TypeToken<List<AutomationTaskService.PersistentTask>>() { }.getType();
    private final Path file;

    AutomationTaskStore(Path file) {
        this.file = file;
    }

    synchronized List<AutomationTaskService.PersistentTask> load() {
        if (file == null || !Files.exists(file)) {
            return List.of();
        }
        try {
            List<AutomationTaskService.PersistentTask> states = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), STATE_LIST);
            return states != null ? List.copyOf(states) : List.of();
        } catch (RuntimeException | IOException failure) {
            Log.warn("Failed to load persistent automation tasks: " + failure.getMessage());
            return List.of();
        }
    }

    synchronized void save(List<AutomationTaskService.PersistentTask> states) {
        if (file == null) {
            return;
        }
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(new ArrayList<>(states), STATE_LIST), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            Log.warn("Failed to save persistent automation tasks: " + failure.getMessage());
        }
    }
}

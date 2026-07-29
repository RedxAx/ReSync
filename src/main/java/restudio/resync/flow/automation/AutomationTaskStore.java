package restudio.resync.flow.automation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import restudio.resync.Log;
import restudio.resync.storage.RecoverableJsonStore;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class AutomationTaskStore {
    private static final Gson GSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private static final Type STATE_LIST = new TypeToken<List<AutomationTaskService.PersistentTask>>() { }.getType();
    private final Path file;
    private final RecoverableJsonStore store;

    AutomationTaskStore(Path file) {
        this.file = file;
        this.store = file != null ? new RecoverableJsonStore(file, GSON) : null;
    }

    synchronized List<AutomationTaskService.PersistentTask> load() {
        if (file == null || !Files.exists(file)) {
            return List.of();
        }
        try {
            List<AutomationTaskService.PersistentTask> states = GSON.fromJson(store.load(), STATE_LIST);
            return states != null ? List.copyOf(states) : List.of();
        } catch (RuntimeException | IOException failure) {
            Log.warn("Failed to load persistent automation tasks: " + failure.getMessage());
            return List.of();
        }
    }

    synchronized void save(List<AutomationTaskService.PersistentTask> states) throws IOException {
        if (file == null) {
            return;
        }
        store.save(GSON.toJsonTree(new ArrayList<>(states), STATE_LIST));
    }
}

package restudio.resync.advancement;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

public final class PaperUnsafe {
    private static volatile boolean resolved;
    private static Method loadAdvancement;
    private static Method loadAdvancementPersist;
    private static Method loadAdvancements;
    private static Method removeAdvancement;
    private static Method serializeItemAsJson;
    private static Method deserializeItemFromJson;

    private PaperUnsafe() {
    }

    static boolean loadAdvancementSupported() {
        resolve();
        return loadAdvancement != null && removeAdvancement != null;
    }

    public static boolean serializeItemAsJsonSupported() {
        resolve();
        return serializeItemAsJson != null;
    }

    public static boolean itemJsonRoundTripSupported() {
        resolve();
        return serializeItemAsJson != null && deserializeItemFromJson != null;
    }

    static void loadAdvancement(NamespacedKey key, String advancementJson, boolean persist) {
        resolve();
        require(loadAdvancement, "loadAdvancement");
        try {
            if (loadAdvancementPersist != null) {
                loadAdvancementPersist.invoke(Bukkit.getUnsafe(), key, advancementJson, persist);
            } else {
                loadAdvancement.invoke(Bukkit.getUnsafe(), key, advancementJson);
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Failed to load advancement " + key, unwrap(failure));
        }
    }

    static void loadAdvancements(Map<NamespacedKey, String> advancements, boolean persist) {
        resolve();
        if (loadAdvancements != null) {
            try {
                loadAdvancements.invoke(Bukkit.getUnsafe(), advancements, persist);
                return;
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Failed to bulk-load advancements", unwrap(failure));
            }
        }
        for (Map.Entry<NamespacedKey, String> entry : advancements.entrySet()) {
            loadAdvancement(entry.getKey(), entry.getValue(), persist);
        }
    }

    static boolean removeAdvancement(NamespacedKey key) {
        resolve();
        require(removeAdvancement, "removeAdvancement");
        try {
            Object result = removeAdvancement.invoke(Bukkit.getUnsafe(), key);
            return result instanceof Boolean bool && bool;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Failed to remove advancement " + key, unwrap(failure));
        }
    }

    public static JsonObject serializeItemAsJson(ItemStack item) {
        resolve();
        require(serializeItemAsJson, "serializeItemAsJson");
        try {
            return (JsonObject) serializeItemAsJson.invoke(Bukkit.getUnsafe(), item);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Failed to serialize item as JSON", unwrap(failure));
        }
    }

    public static ItemStack deserializeItemFromJson(JsonObject item) {
        resolve();
        require(deserializeItemFromJson, "deserializeItemFromJson");
        try {
            return (ItemStack) deserializeItemFromJson.invoke(Bukkit.getUnsafe(), item);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Failed to deserialize item from JSON", unwrap(failure));
        }
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        synchronized (PaperUnsafe.class) {
            if (resolved) {
                return;
            }
            try {
                Object unsafe = Bukkit.getUnsafe();
                if (unsafe == null) {
                    return;
                }
                loadAdvancement = method(unsafe, "loadAdvancement", NamespacedKey.class, String.class);
                loadAdvancementPersist = method(unsafe, "loadAdvancement", NamespacedKey.class, String.class, boolean.class);
                loadAdvancements = method(unsafe, "loadAdvancements", Map.class, boolean.class);
                removeAdvancement = method(unsafe, "removeAdvancement", NamespacedKey.class);
                serializeItemAsJson = method(unsafe, "serializeItemAsJson", ItemStack.class);
                deserializeItemFromJson = method(unsafe, "deserializeItemFromJson", JsonObject.class);
            } catch (Throwable ignored) {
                loadAdvancement = null;
                loadAdvancementPersist = null;
                loadAdvancements = null;
                removeAdvancement = null;
                serializeItemAsJson = null;
                deserializeItemFromJson = null;
                if (Bukkit.getServer() == null) {
                    return;
                }
            }
            resolved = true;
        }
    }

    private static Method method(Object target, String name, Class<?>... parameterTypes) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static void require(Method method, String name) {
        if (method == null) {
            throw new IllegalStateException("Paper UnsafeValues." + name + " is not available on this server");
        }
    }

    private static Throwable unwrap(ReflectiveOperationException failure) {
        return failure instanceof InvocationTargetException invocation && invocation.getCause() != null
            ? invocation.getCause()
            : failure;
    }
}

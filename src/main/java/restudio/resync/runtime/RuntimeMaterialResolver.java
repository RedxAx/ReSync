package restudio.resync.runtime;

import org.bukkit.Bukkit;
import org.bukkit.Material;

import java.util.Locale;

final class RuntimeMaterialResolver {
    private RuntimeMaterialResolver() {
    }

    static Material itemMaterial(String reference) {
        Material material = material(reference);
        return isUsableItem(material) ? material : null;
    }

    private static Material material(String reference) {
        if (reference == null || reference.isBlank()) {
            return null;
        }
        String normalized = reference.trim();
        int namespace = normalized.indexOf(':');
        if (namespace >= 0) {
            if (!"minecraft".equalsIgnoreCase(normalized.substring(0, namespace)) || namespace + 1 >= normalized.length()) {
                return null;
            }
            normalized = normalized.substring(namespace + 1);
        }
        try {
            return Material.valueOf(normalized.replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean isUsableItem(Material material) {
        if (material == null || "AIR".equals(material.name())) {
            return false;
        }
        if (Bukkit.getServer() == null) {
            return true;
        }
        try {
            return material.isItem() && !material.isAir();
        } catch (RuntimeException | LinkageError ignored) {
            return true;
        }
    }
}

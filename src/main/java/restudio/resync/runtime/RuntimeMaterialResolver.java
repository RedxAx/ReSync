package restudio.resync.runtime;

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
        String normalized = normalize(reference);
        try {
            return Material.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String normalize(String reference) {
        String value = reference.trim();
        int namespace = value.indexOf(':');
        if (namespace >= 0 && namespace + 1 < value.length()) {
            value = value.substring(namespace + 1);
        }
        return value.replace('-', '_').toUpperCase(Locale.ROOT);
    }

    private static boolean isUsableItem(Material material) {
        if (material == null || "AIR".equals(material.name())) {
            return false;
        }
        try {
            return material.isItem() && !material.isAir();
        } catch (IllegalStateException ignored) {
            return true;
        }
    }
}

package restudio.resync.customcontent;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Projectile;
import restudio.flow.data.CustomAbilityBinding;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.CustomTriggerRule;
import restudio.resync.storage.StorageSafety;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class CustomContentValidator {
    private static final Set<String> TYPES = Set.of("item", "block", "armor", "projectile");
    private static final Set<String> ARMOR_SLOTS = Set.of("head", "chest", "legs", "feet");
    private static final Set<String> PROJECTILE_SOURCES = Set.of("automatic", "bow ammo", "item use", "both");
    private static final Set<String> PROJECTILE_PICKUP = Set.of("allowed", "disallowed", "creative only");
    private static final Set<String> PROJECTILE_FLAGS = Set.of("gravity", "glowing", "consume_item", "remove_on_hit");

    public List<String> validate(CustomContentDefinition definition) {
        List<String> errors = new ArrayList<>();
        if (definition == null) {
            errors.add("Definition is required");
            return errors;
        }
        validateId(definition.getId(), "id", errors);
        if (definition.getFlowId() != null && !definition.getFlowId().isBlank()) {
            validateId(definition.getFlowId(), "flowId", errors);
        }
        String type = lower(definition.getType());
        if (!TYPES.contains(type)) {
            errors.add("Type must be item, block, armor, or projectile");
        }
        String provider = lower(definition.getProvider());
        if (!provider.isBlank()) {
            validateId(provider, "provider", errors);
        }
        if (Material.matchMaterial(definition.getMaterial() != null ? definition.getMaterial() : "") == null) {
            errors.add("Material does not exist: " + definition.getMaterial());
        }
        if ("armor".equals(type) && !ARMOR_SLOTS.contains(lower(definition.getArmorSlot()))) {
            errors.add("Armor slot must be head, chest, legs, or feet");
        }
        if ("projectile".equals(type)) {
            validateProjectile(definition, errors);
        }
        if (definition.getVersion() < 0) {
            errors.add("Version must be >= 0");
        }
        if (definition.getComponents() != null) {
            for (String componentId : definition.getComponents().keySet()) {
                if (componentId == null || !componentId.trim().toLowerCase(Locale.ROOT).matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                    errors.add("Component id must be namespaced: " + componentId);
                }
            }
        }
        Set<String> abilityIds = new HashSet<>();
        for (CustomAbilityBinding ability : definition.getAbilities()) {
            if (ability == null) {
                errors.add("Ability is required");
                continue;
            }
            if (ability.getId() == null || ability.getId().isBlank()) {
                errors.add("Ability id is required");
            } else if (!abilityIds.add(ability.getId())) {
                errors.add("Duplicate ability id: " + ability.getId());
            }
            if (ability.isEnabled() && (ability.getFlowId() == null || ability.getFlowId().isBlank())) {
                errors.add("Ability flow id is required: " + ability.getId());
            } else if (ability.getFlowId() != null && !ability.getFlowId().isBlank()) {
                validateId(ability.getFlowId(), "ability flowId", errors);
            }
            CustomTriggerRule rule = ability.getRule();
            if (rule != null) {
                if (rule.getChancePercent() < 0.0 || rule.getChancePercent() > 100.0) {
                    errors.add("Ability chance must be between 0 and 100: " + ability.getId());
                }
                if (rule.getCooldownTicks() < 0) {
                    errors.add("Ability cooldown must be >= 0: " + ability.getId());
                }
                if (rule.getMaxActivationsPerTick() < 0) {
                    errors.add("Ability activation limit must be >= 0: " + ability.getId());
                }
            }
        }
        return errors;
    }

    private void validateProjectile(CustomContentDefinition definition, List<String> errors) {
        Map<String, Object> properties = definition.getGraph() != null && definition.getGraph().getContentProperties() != null
            ? definition.getGraph().getContentProperties()
            : Map.of();
        String entityName = text(properties, "projectile.entity_type", "ARROW").replace(' ', '_').toUpperCase(Locale.ROOT);
        if ("ENDERPEARL".equals(entityName)) entityName = "ENDER_PEARL";
        try {
            EntityType entityType = EntityType.valueOf(entityName);
            Class<?> entityClass = entityType.getEntityClass();
            if (entityClass == null || !Projectile.class.isAssignableFrom(entityClass)) {
                errors.add("Projectile entity type must create a projectile: " + entityName);
            }
        } catch (IllegalArgumentException exception) {
            errors.add("Projectile entity type does not exist: " + entityName);
        }

        String source = lower(text(properties, "projectile.launch_source", "Automatic"));
        if (!PROJECTILE_SOURCES.contains(source)) {
            errors.add("Projectile launch source must be Automatic, Bow Ammo, Item Use, or Both");
        }
        String pickup = lower(text(properties, "projectile.pickup", "Allowed"));
        if (!PROJECTILE_PICKUP.contains(pickup)) {
            errors.add("Projectile pickup must be Allowed, Disallowed, or Creative Only");
        }
        validateProjectileNumber(properties, "projectile.speed", "Projectile speed", 0.05, Double.MAX_VALUE, errors);
        validateProjectileNumber(properties, "projectile.damage", "Projectile damage", 0.0, Double.MAX_VALUE, errors);
        validateProjectileNumber(properties, "projectile.sound_volume", "Projectile sound volume", 0.0, 16.0, errors);
        validateProjectileNumber(properties, "projectile.sound_pitch", "Projectile sound pitch", 0.5, 2.0, errors);
        for (String flag : PROJECTILE_FLAGS) {
            Object value = properties.get("projectile." + flag);
            if (value != null && !(value instanceof Boolean) && !Set.of("true", "false").contains(lower(value.toString()))) {
                errors.add("Projectile " + flag.replace('_', ' ') + " must be enabled or disabled");
            }
        }
        validateSound(properties, "projectile.fire_sound", "Projectile fire sound", errors);
        validateSound(properties, "projectile.hit_sound", "Projectile hit sound", errors);
    }

    private void validateProjectileNumber(Map<String, Object> properties, String key, String label, double minimum, double maximum, List<String> errors) {
        Object value = properties.get(key);
        if (value == null) return;
        double number;
        try {
            number = value instanceof Number numeric ? numeric.doubleValue() : Double.parseDouble(value.toString().trim());
        } catch (NumberFormatException exception) {
            errors.add(label + " must be a number");
            return;
        }
        if (!Double.isFinite(number) || number < minimum || number > maximum) {
            errors.add(maximum == Double.MAX_VALUE ? label + " must be at least " + minimum : label + " must be between " + minimum + " and " + maximum);
        }
    }

    private void validateSound(Map<String, Object> properties, String key, String label, List<String> errors) {
        String value = text(properties, key, "").toLowerCase(Locale.ROOT);
        if (value.isBlank()) return;
        NamespacedKey soundKey = NamespacedKey.fromString(value.contains(":") ? value : "minecraft:" + value);
        if (soundKey == null) errors.add(label + " must be a valid sound id");
    }

    private String text(Map<String, Object> properties, String key, String fallback) {
        Object value = properties.get(key);
        if (value == null) return fallback;
        String text = value.toString().trim();
        return text.isBlank() ? fallback : text;
    }

    private void validateId(String id, String field, List<String> errors) {
        try {
            StorageSafety.validateId(id);
        } catch (IllegalArgumentException e) {
            errors.add("Invalid " + field + ": " + id);
        }
    }

    private String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}

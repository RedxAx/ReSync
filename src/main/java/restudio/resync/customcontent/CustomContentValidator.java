package restudio.resync.customcontent;

import org.bukkit.Material;
import restudio.flow.data.CustomAbilityBinding;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.CustomTriggerRule;
import restudio.resync.storage.StorageSafety;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class CustomContentValidator {
    private static final Set<String> TYPES = Set.of("item", "block", "armor");
    private static final Set<String> ARMOR_SLOTS = Set.of("head", "chest", "legs", "feet");

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
            errors.add("Type must be item, block, or armor");
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
        if (definition.getVersion() < 0) {
            errors.add("Version must be >= 0");
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

package restudio.resync.customcontent;

import org.junit.jupiter.api.Test;
import restudio.flow.data.CustomContentDefinition;
import restudio.flow.data.CustomContentGraphAdapter;
import restudio.flow.data.FlowSerializer;
import restudio.flow.data.FlowGraph;
import restudio.resync.api.OptionCatalogItem;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomContentCompatibilityTest {
    private static final List<String> SPECIALIZED_COMPONENT_IDS = List.of(
        "minecraft:axolotl/variant",
        "minecraft:cat/variant",
        "minecraft:cat/sound_variant",
        "minecraft:cat/collar",
        "minecraft:chicken/variant",
        "minecraft:chicken/sound_variant",
        "minecraft:cow/variant",
        "minecraft:cow/sound_variant",
        "minecraft:fox/variant",
        "minecraft:frog/variant",
        "minecraft:horse/variant",
        "minecraft:llama/variant",
        "minecraft:map_color",
        "minecraft:map_decorations",
        "minecraft:map_id",
        "minecraft:map_post_processing",
        "minecraft:mooshroom/variant",
        "minecraft:painting/variant",
        "minecraft:parrot/variant",
        "minecraft:pig/variant",
        "minecraft:pig/sound_variant",
        "minecraft:rabbit/variant",
        "minecraft:salmon/size",
        "minecraft:sheep/color",
        "minecraft:shulker/color",
        "minecraft:tropical_fish/base_color",
        "minecraft:tropical_fish/pattern",
        "minecraft:tropical_fish/pattern_color",
        "minecraft:villager/variant",
        "minecraft:wolf/variant",
        "minecraft:wolf/sound_variant",
        "minecraft:wolf/collar",
        "minecraft:zombie_nautilus/variant"
    );
    private static final List<String> VANILLA_26_2_COMPONENT_IDS = List.of(
        "minecraft:additional_trade_cost",
        "minecraft:attack_range",
        "minecraft:attribute_modifiers",
        "minecraft:axolotl/variant",
        "minecraft:banner_patterns",
        "minecraft:base_color",
        "minecraft:bees",
        "minecraft:blocks_attacks",
        "minecraft:block_entity_data",
        "minecraft:block_state",
        "minecraft:break_sound",
        "minecraft:bucket_entity_data",
        "minecraft:bundle_contents",
        "minecraft:can_break",
        "minecraft:can_place_on",
        "minecraft:cat/collar",
        "minecraft:cat/sound_variant",
        "minecraft:cat/variant",
        "minecraft:charged_projectiles",
        "minecraft:chicken/variant",
        "minecraft:chicken/sound_variant",
        "minecraft:consumable",
        "minecraft:container",
        "minecraft:container_loot",
        "minecraft:cow/sound_variant",
        "minecraft:cow/variant",
        "minecraft:creative_slot_lock",
        "minecraft:custom_data",
        "minecraft:custom_model_data",
        "minecraft:custom_name",
        "minecraft:damage",
        "minecraft:damage_resistant",
        "minecraft:damage_type",
        "minecraft:death_protection",
        "minecraft:debug_stick_state",
        "minecraft:dye",
        "minecraft:dyed_color",
        "minecraft:enchantable",
        "minecraft:enchantments",
        "minecraft:enchantment_glint_override",
        "minecraft:entity_data",
        "minecraft:equippable",
        "minecraft:fireworks",
        "minecraft:firework_explosion",
        "minecraft:food",
        "minecraft:fox/variant",
        "minecraft:frog/variant",
        "minecraft:glider",
        "minecraft:horse/variant",
        "minecraft:instrument",
        "minecraft:intangible_projectile",
        "minecraft:item_model",
        "minecraft:item_name",
        "minecraft:jukebox_playable",
        "minecraft:kinetic_weapon",
        "minecraft:llama/variant",
        "minecraft:lock",
        "minecraft:lodestone_tracker",
        "minecraft:lore",
        "minecraft:map_color",
        "minecraft:map_decorations",
        "minecraft:map_id",
        "minecraft:map_post_processing",
        "minecraft:max_damage",
        "minecraft:max_stack_size",
        "minecraft:minimum_attack_charge",
        "minecraft:mooshroom/variant",
        "minecraft:note_block_sound",
        "minecraft:ominous_bottle_amplifier",
        "minecraft:painting/variant",
        "minecraft:parrot/variant",
        "minecraft:piercing_weapon",
        "minecraft:pig/sound_variant",
        "minecraft:pig/variant",
        "minecraft:potion_contents",
        "minecraft:potion_duration_scale",
        "minecraft:pot_decorations",
        "minecraft:profile",
        "minecraft:provides_banner_patterns",
        "minecraft:provides_trim_material",
        "minecraft:rabbit/variant",
        "minecraft:rarity",
        "minecraft:recipes",
        "minecraft:repairable",
        "minecraft:repair_cost",
        "minecraft:salmon/size",
        "minecraft:sheep/color",
        "minecraft:shulker/color",
        "minecraft:stored_enchantments",
        "minecraft:sulfur_cube_content",
        "minecraft:suspicious_stew_effects",
        "minecraft:swing_animation",
        "minecraft:tool",
        "minecraft:tooltip_display",
        "minecraft:tooltip_style",
        "minecraft:trim",
        "minecraft:tropical_fish/base_color",
        "minecraft:tropical_fish/pattern",
        "minecraft:tropical_fish/pattern_color",
        "minecraft:unbreakable",
        "minecraft:use_cooldown",
        "minecraft:use_effects",
        "minecraft:use_remainder",
        "minecraft:villager/variant",
        "minecraft:weapon",
        "minecraft:wolf/collar",
        "minecraft:wolf/sound_variant",
        "minecraft:wolf/variant",
        "minecraft:writable_book_content",
        "minecraft:written_book_content",
        "minecraft:zombie_nautilus/variant"
    );

    @Test
    void validatorAcceptsGraphDerivedDefaultContent() {
        CustomContentValidator validator = new CustomContentValidator();

        for (String type : new String[]{"item", "block", "armor", "projectile"}) {
            FlowGraph graph = CustomContentGraphAdapter.createContentGraph("flow_default_" + type, type, "Default " + type);
            CustomContentDefinition definition = CustomContentGraphAdapter.toDefinition(graph);

            assertTrue(validator.validate(definition).isEmpty(), () -> type + " should be valid");
        }
    }

    @Test
    void validatorRejectsUnsafeProjectileConfiguration() {
        FlowGraph graph = CustomContentGraphAdapter.createContentGraph("unsafe_projectile", "projectile", "Unsafe Projectile");
        graph.getContentProperties().put("projectile.entity_type", "ZOMBIE");
        graph.getContentProperties().put("projectile.launch_source", "Unknown");
        graph.getContentProperties().put("projectile.speed", "NaN");
        graph.getContentProperties().put("projectile.sound_pitch", 4.0);
        graph.getContentProperties().put("projectile.fire_sound", "invalid sound");
        CustomContentDefinition definition = CustomContentGraphAdapter.toDefinition(graph);

        List<String> errors = new CustomContentValidator().validate(definition);

        assertTrue(errors.stream().anyMatch(error -> error.contains("must create a projectile")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("launch source")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("speed")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("sound pitch")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("fire sound")));
    }

    @Test
    void graphAdapterRoundTripsStudioFields() {
        FlowGraph graph = CustomContentGraphAdapter.createContentGraph("studio_item", "item", "Studio Item");
        CustomContentGraphAdapter.setContentProperty(graph, "custom_model_data", 42);
        CustomContentGraphAdapter.setContentProperty(graph, "components", Map.of(
            "minecraft:custom_model_data", Map.of("floats", List.of(8.0)),
            "minecraft:tooltip_display", Map.of("hidden_components", List.of("minecraft:attribute_modifiers"))
        ));
        CustomContentGraphAdapter.setContentProperty(graph, "lore", "Line One, Line Two");
        CustomContentGraphAdapter.setContentProperty(graph, "tags", "rare, quest");
        CustomContentGraphAdapter.setContentProperty(graph, "enabled", false);
        CustomContentGraphAdapter.setContentProperty(graph, "priority", 7);
        CustomContentGraphAdapter.setEnabledTriggerBranches(graph, List.of("use", "hit_entity", "while_holding"));

        CustomContentDefinition definition = CustomContentGraphAdapter.toDefinition(graph);

        assertEquals(42, definition.getCustomModelData());
        assertEquals(List.of(8.0), ((Map<?, ?>) definition.getComponents().get("minecraft:custom_model_data")).get("floats"));
        assertEquals(List.of("minecraft:attribute_modifiers"), ((Map<?, ?>) definition.getComponents().get("minecraft:tooltip_display")).get("hidden_components"));
        assertEquals(List.of("Line One", "Line Two"), definition.getLore());
        assertEquals(List.of("rare", "quest"), definition.getTags());
        assertEquals(3, definition.getAbilities().size());
        assertTrue(definition.getAbilities().stream().allMatch(binding -> !binding.isEnabled()));
        assertTrue(definition.getAbilities().stream().allMatch(binding -> binding.getRule().getPriority() == 7));
    }

    @Test
    void customContentSerializationPreservesUnknownNestedComponents() {
        CustomContentDefinition definition = new CustomContentDefinition();
        definition.setId("nested_components");
        definition.setType("item");
        definition.setDisplayName("Nested Components");
        definition.setMaterial("STICK");
        Map<String, Object> food = new LinkedHashMap<>();
        food.put("nutrition", 4);
        food.put("effects", List.of(Map.of("effect", Map.of("type", "minecraft:speed", "duration", 120))));
        definition.setComponents(Map.of("minecraft:food", food));

        CustomContentDefinition roundTrip = FlowSerializer.deserializeCustomContent(FlowSerializer.serializeCustomContent(definition));

        Map<?, ?> roundTripFood = (Map<?, ?>) roundTrip.getComponents().get("minecraft:food");
        assertEquals(4.0, ((Number) roundTripFood.get("nutrition")).doubleValue());
        List<?> effects = (List<?>) roundTripFood.get("effects");
        Map<?, ?> firstEffect = (Map<?, ?>) effects.getFirst();
        Map<?, ?> effect = (Map<?, ?>) firstEffect.get("effect");
        assertEquals("minecraft:speed", effect.get("type"));
        assertEquals(120.0, ((Number) effect.get("duration")).doubleValue());
    }

    @Test
    void validatorRejectsUnnamespacedComponentIds() {
        CustomContentDefinition definition = CustomContentGraphAdapter.toDefinition(CustomContentGraphAdapter.createContentGraph("bad_component", "item", "Bad Component"));
        definition.setComponents(Map.of("custom_model_data", 1));

        List<String> errors = new CustomContentValidator().validate(definition);

        assertTrue(errors.stream().anyMatch(error -> error.contains("Component id must be namespaced")));
    }

    @Test
    void componentsPreserveLegacyOverlapAsAdvancedSourceOfTruth() {
        CustomContentDefinition definition = CustomContentGraphAdapter.toDefinition(CustomContentGraphAdapter.createContentGraph("component_precedence", "item", "Component Precedence"));
        definition.setCustomModelData(7);
        definition.setLore(List.of("Legacy Lore"));
        definition.setComponents(Map.of(
            "minecraft:custom_model_data", Map.of("floats", List.of(99.0)),
            "minecraft:lore", List.of("Component Lore")
        ));

        assertEquals(7, definition.getCustomModelData());
        assertEquals(List.of("Legacy Lore"), definition.getLore());
        assertEquals(List.of(99.0), ((Map<?, ?>) definition.getComponents().get("minecraft:custom_model_data")).get("floats"));
        assertEquals(List.of("Component Lore"), definition.getComponents().get("minecraft:lore"));
    }

    @Test
    void vanillaProviderAppliesLegacyMetadataBeforeComponents() throws Exception {
        String source = Files.readString(Path.of("src/main/java/restudio/resync/customcontent/VanillaContentProvider.java"));

        int setMetaIndex = source.indexOf("item.setItemMeta(meta);");
        int applyComponentsIndex = source.indexOf("item = applyComponents(item, definition);");
        int stampIndex = source.indexOf("return stampItem(item, definition);");

        assertTrue(setMetaIndex >= 0);
        assertTrue(applyComponentsIndex > setMetaIndex);
        assertTrue(stampIndex > applyComponentsIndex);
    }

    @Test
    void schemaServiceUsesInjectedDiscoveryAndExampleFallback() {
        ItemAttributeSchemaService service = new ItemAttributeSchemaService(
            List.of("minecraft:food", "minecraft:tooltip_display"),
            Map.of("minecraft:food", Map.of("nutrition", 4.0)),
            true
        );

        List<String> values = service.values("STICK");

        assertTrue(values.contains("minecraft:food"));
        assertTrue(values.contains("minecraft:tooltip_display"));
    }

    @Test
    void schemaServiceKeepsComponentsThatDoNotApplyToMaterial() {
        ItemAttributeSchemaService service = new ItemAttributeSchemaService(
            List.of("minecraft:food", "minecraft:axolotl/variant"),
            Map.of(
                "minecraft:food", Map.of("nutrition", 4.0),
                "minecraft:axolotl/variant", "lucy"
            ),
            true,
            Map.of(
                "minecraft:food", true,
                "minecraft:axolotl/variant", false
            )
        );

        List<OptionCatalogItem> items = service.catalog("STICK");
        List<String> values = items.stream().map(OptionCatalogItem::value).toList();

        assertTrue(values.contains("minecraft:food"));
        assertTrue(values.contains("minecraft:axolotl/variant"));
        OptionCatalogItem variant = items.stream()
            .filter(item -> "minecraft:axolotl/variant".equals(item.value()))
            .findFirst()
            .orElseThrow();
        assertEquals("Entity Variants", variant.group());
        assertFalse(Boolean.TRUE.equals(variant.metadata().get("applicable")));
    }

    @Test
    void schemaServiceProductionFilterUsesVanillaOriginEvidence() throws Exception {
        String source = Files.readString(Path.of("src/main/java/restudio/resync/customcontent/ItemAttributeSchemaService.java"));
        String storageSource = Files.readString(Path.of("src/main/java/restudio/resync/customcontent/CustomContentStorage.java"));
        String flowModuleSource = Files.readString(Path.of("src/main/java/restudio/resync/modules/FlowModule.java"));
        String resourceRouterSource = Files.readString(Path.of("src/main/java/restudio/resync/modules/flow/FlowResourcePacketRouter.java"));
        String catalogSource = Files.readString(Path.of("src/main/java/restudio/resync/modules/flow/BuiltinOptionCatalogService.java"));
        String uiSchemaSource = Files.readString(Path.of("src/main/resources/resync/item_attribute_ui_schema.json"));

        assertTrue(source.contains("exampleMaterials()"));
        assertTrue(source.contains("origins.contains(material.name())"));
        assertTrue(source.contains("origins.size() < 2"));
        assertTrue(source.contains("candidateExamples(material)"));
        assertTrue(source.contains("candidateExampleValue(material"));
        assertTrue(source.contains("componentValueCanApply(material, id, candidate)"));
        assertTrue(source.contains("boolean writable = injectedComponentIds != null || componentValueCanApply(material, id, value)"));
        assertTrue(source.contains("metadata.put(\"writable\", writable)"));
        assertFalse(source.contains("if (!writable) {\n                continue;\n            }"));
        assertTrue(source.contains("customComponentsFromStack"));
        assertTrue(source.contains("customComponentsForMaterial"));
        assertTrue(source.contains("componentsFromStack(new ItemStack(material))"));
        assertTrue(source.contains("jsonEquivalent"));
        assertTrue(flowModuleSource.contains("quickEditAttributeService.customComponentsFromStack(item)"));
        assertTrue(storageSource.contains("definition.setComponents(attributeSchemaService.customComponentsForMaterial(definition.getMaterial(), definition.getComponents()))"));
        assertTrue(resourceRouterSource.contains("value.setComponents(attributeSchemaService.customComponentsForMaterial(value.getMaterial(), value.getComponents()))"));
        assertTrue(source.contains("SPECIALIZED_ITEM_COMPONENTS"));
        assertFalse(source.contains("if (SPECIALIZED_ITEM_COMPONENTS.contains(id))"));
        assertTrue(source.contains("case \"minecraft:food\" -> \"Nutrition And Saturation\""));
        assertTrue(source.contains("case \"minecraft:consumable\" -> \"Use Time And Animation\""));
        assertTrue(source.contains("case \"minecraft:weapon\" -> \"Weapon Durability And Shield Disable\""));
        assertFalse(source.contains("\"Eating Behavior\""));
        assertTrue(source.contains("loadUiProfiles()"));
        assertTrue(source.contains("item_attribute_ui_schema.json"));
        assertFalse(source.contains("public List<OptionCatalogItem> catalog(String materialName) {\n        if (!itemJsonRoundTripSupported())"));
        assertTrue(uiSchemaSource.contains("\"schemaVersion\": 1"));
        assertTrue(uiSchemaSource.contains("\"minecraft:consumable\""));
        assertTrue(uiSchemaSource.contains("\"category\": \"Use\""));
        assertTrue(uiSchemaSource.contains("\"priority\": 305"));
        assertTrue(source.contains("metadata.put(\"applicable\", applicable)"));
        assertTrue(source.contains("metadata.put(\"category\", group)"));
        assertTrue(source.contains("metadata.put(\"priority\", priority)"));
        assertTrue(source.contains("Class.forName(\"io.papermc.paper.registry.keys.DataComponentTypeKeys\")"));
        assertTrue(source.contains("if (runtimeIds.isEmpty())"));
        assertTrue(source.contains("ids.addAll(UI_PROFILES.keySet())"));
        assertTrue(source.contains("metadata.put(\"runtime\", runtimeKnown)"));
        assertTrue(source.contains("metadata.put(\"ui\", uiMetadata"));
        assertTrue(source.contains("private record AttributeUiProfile"));
        assertTrue(source.contains("if (!componentIsSupported(runtimeKnown, defaultValue, exampleValue))"));
        assertTrue(source.contains("if (generatedExample) {\n            return profileAppliesToMaterial(material, profile);\n        }"));
        assertTrue(source.contains("Comparator.comparingInt((OptionCatalogItem item) -> groupRank(item.group()))"));
        assertTrue(source.contains("case \"Data\" -> 11"));
        assertTrue(source.contains("booleanMetadata(item.metadata(), \"applicable\", false)"));
        assertTrue(source.contains("booleanMetadata(item.metadata(), \"recommended\", false)"));
        assertTrue(source.contains("candidates.put(\"minecraft:jukebox_playable\", List.of(\"minecraft:13\"))"));
        assertTrue(source.contains("candidates.put(\"minecraft:dyed_color\", List.of(16777215))"));
        assertTrue(source.contains("candidates.put(\"minecraft:attribute_modifiers\", List.of(List.of(Map.of("));
        assertFalse(source.contains("\"modifiers\", List.of(Map.of("));
        assertTrue(source.contains("candidates.put(\"minecraft:can_break\", List.of(List.of(Map.of(\"blocks\", \"minecraft:stone\"))))"));
        assertTrue(source.contains("candidates.put(\"minecraft:can_place_on\", List.of(List.of(Map.of(\"blocks\", \"minecraft:stone\"))))"));
        assertTrue(source.contains("candidates.put(\"minecraft:trim\", List.of(Map.of(\n            \"material\", \"minecraft:iron\",\n            \"pattern\", \"minecraft:sentry\"\n        )))"));
        assertFalse(source.contains("\"show_in_tooltip\", true"));
        assertFalse(source.contains("Map.of(\"rgb\", 16777215, \"show_in_tooltip\", true)"));
        for (String id : SPECIALIZED_COMPONENT_IDS) {
            assertTrue(source.contains(id), id);
            assertTrue(uiSchemaSource.contains("\"" + id + "\""), id);
        }
        assertTrue(uiSchemaSource.contains("\"minecraft:use_effects\""));
        assertTrue(uiSchemaSource.contains("\"minecraft:zombie_nautilus/variant\""));
        assertTrue(source.contains("candidates.put(\"minecraft:attack_range\""));
        assertTrue(source.contains("candidates.put(\"minecraft:use_effects\""));
        assertTrue(source.contains("candidates.put(\"minecraft:zombie_nautilus/variant\""));
        assertTrue(source.contains("candidates.put(\"minecraft:tooltip_style\""));
        for (String id : VANILLA_26_2_COMPONENT_IDS) {
            assertTrue(uiSchemaSource.contains("\"" + id + "\""), id);
        }
        List<String> schemaIds = new ArrayList<>();
        Matcher schemaMatcher = Pattern.compile("^\\s+\"(minecraft:[^\"]+)\"\\s*:", Pattern.MULTILINE).matcher(uiSchemaSource);
        while (schemaMatcher.find()) {
            schemaIds.add(schemaMatcher.group(1));
        }
        assertEquals(VANILLA_26_2_COMPONENT_IDS.size(), schemaIds.size());
        assertTrue(schemaIds.containsAll(VANILLA_26_2_COMPONENT_IDS));
        assertTrue(VANILLA_26_2_COMPONENT_IDS.containsAll(schemaIds));
        assertTrue(source.contains("candidates.put(\"minecraft:dyed_color\""));
        assertTrue(source.contains("case \"minecraft:dyed_color\" -> \"Item Color\""));
        assertTrue(source.contains("candidates.put(\"minecraft:can_break\""));
        assertTrue(source.contains("candidates.put(\"minecraft:can_place_on\""));
        assertTrue(source.contains("case \"minecraft:can_break\" -> \"Adventure Break Rules\""));
        assertTrue(source.contains("case \"minecraft:can_place_on\" -> \"Adventure Placement Rules\""));
        assertTrue(source.contains("candidates.put(\"minecraft:attribute_modifiers\""));
        assertTrue(source.contains("case \"minecraft:attribute_modifiers\" -> \"Stats And Equipment Slots\""));
        assertTrue(source.contains("candidates.put(\"minecraft:trim\""));
        assertTrue(source.contains("case \"minecraft:trim\" -> \"Armor Trim Material And Pattern\""));
        assertTrue(source.contains("candidates.put(\"minecraft:firework_explosion\""));
        assertTrue(source.contains("candidates.put(\"minecraft:fireworks\""));
        assertTrue(source.contains("case \"minecraft:firework_explosion\" -> \"Firework Shape And Colors\""));
        assertTrue(source.contains("case \"minecraft:fireworks\" -> \"Rocket Flight And Explosions\""));
        assertTrue(source.contains("candidates.put(\"minecraft:banner_patterns\""));
        assertTrue(source.contains("case \"minecraft:banner_patterns\" -> \"Banner Patterns\""));
        assertTrue(source.contains("candidates.put(\"minecraft:charged_projectiles\""));
        assertTrue(source.contains("candidates.put(\"minecraft:bundle_contents\""));
        assertTrue(source.contains("candidates.put(\"minecraft:container\""));
        assertTrue(source.contains("case \"minecraft:charged_projectiles\" -> \"Loaded Projectiles\""));
        assertTrue(source.contains("case \"minecraft:bundle_contents\" -> \"Bundle Items\""));
        assertTrue(source.contains("case \"minecraft:container\" -> \"Stored Items\""));
        assertTrue(source.contains("candidates.put(\"minecraft:enchantments\""));
        assertTrue(source.contains("candidates.put(\"minecraft:stored_enchantments\""));
        assertTrue(source.contains("case \"minecraft:enchantments\", \"minecraft:stored_enchantments\" -> \"Enchantments\""));
        assertTrue(source.contains("Map.of(\"value\", 10)"));
        assertTrue(source.contains("\"minecraft:unbreaking\", 1"));
        assertFalse(source.contains("\"levels\", Map.of(\"minecraft:unbreaking\", 1)"));
        assertTrue(source.contains("candidates.put(\"minecraft:potion_contents\""));
        assertTrue(source.contains("case \"minecraft:potion_contents\" -> \"Potion Contents\""));
        assertTrue(source.contains("candidates.put(\"minecraft:use_cooldown\""));
        assertTrue(source.contains("case \"minecraft:use_cooldown\" -> \"Reusable Item Delay\""));
        assertTrue(source.contains("candidates.put(\"minecraft:use_remainder\""));
        assertTrue(source.contains("case \"minecraft:use_remainder\" -> \"Item Left After Use\""));
        assertTrue(source.contains("candidates.put(\"minecraft:damage_resistant\""));
        assertTrue(source.contains("case \"minecraft:damage_resistant\" -> \"Ignored Damage Types\""));
        assertTrue(source.contains("candidates.put(\"minecraft:weapon\""));
        assertTrue(source.contains("case \"minecraft:weapon\" -> \"Weapon Durability And Shield Disable\""));
        assertTrue(source.contains("case \"minecraft:blocks_attacks\" -> \"Blocks Attacks\""));
        assertTrue(source.contains("case \"minecraft:custom_data\" -> \"Custom Data\""));
        assertTrue(source.contains("case \"minecraft:writable_book_content\" -> \"Writable Book Content\""));
        assertTrue(source.contains("case \"minecraft:blocks_attacks\" -> \"Attack Blocking\""));
        assertTrue(source.contains("case \"minecraft:custom_data\" -> \"Custom Data\""));
        assertTrue(source.contains("case \"minecraft:writable_book_content\", \"minecraft:written_book_content\" -> \"Book Pages\""));
        assertFalse(source.contains("candidates.put(\"minecraft:hide_tooltip\""));
        assertFalse(source.contains("candidates.put(\"minecraft:hide_additional_tooltip\""));
        assertFalse(source.contains("candidates.put(\"minecraft:fire_resistant\""));
        assertTrue(source.contains("candidates.put(\"minecraft:enchantable\""));
        assertTrue(source.contains("candidates.put(\"minecraft:instrument\""));
        assertTrue(source.contains("candidates.put(\"minecraft:jukebox_playable\""));
        assertTrue(source.contains("case \"minecraft:jukebox_playable\" -> \"Jukebox Song\""));
        assertTrue(catalogSource.contains("case \"potion\" -> potionTypes()"));
        assertTrue(catalogSource.contains("case \"instrument\" -> registryKeysByField(\"INSTRUMENT\")"));
        assertTrue(catalogSource.contains("case \"jukebox_song\" -> registryKeysByField(\"JUKEBOX_SONG\")"));
        assertTrue(catalogSource.contains("case \"trim_material\" -> registryKeysByField(\"TRIM_MATERIAL\")"));
        assertTrue(catalogSource.contains("case \"trim_pattern\" -> registryKeysByField(\"TRIM_PATTERN\")"));
        assertTrue(catalogSource.contains("case \"attribute\" -> registryKeysByField(\"ATTRIBUTE\")"));
        assertTrue(catalogSource.contains("case \"banner_pattern\" -> registryKeysByField(\"BANNER_PATTERN\")"));
        assertTrue(catalogSource.contains("case \"dye_color\" -> enumNames(DyeColor.values())"));
        assertTrue(catalogSource.contains("case \"damage_type\" -> registryKeysByField(\"DAMAGE_TYPE\")"));
        assertTrue(catalogSource.contains("case \"cat_sound_variant\" -> registryKeysByField(\"CAT_SOUND_VARIANT\")"));
        assertTrue(catalogSource.contains("case \"chicken_sound_variant\" -> registryKeysByField(\"CHICKEN_SOUND_VARIANT\")"));
        assertTrue(catalogSource.contains("case \"cow_sound_variant\" -> registryKeysByField(\"COW_SOUND_VARIANT\")"));
        assertTrue(catalogSource.contains("case \"pig_sound_variant\" -> registryKeysByField(\"PIG_SOUND_VARIANT\")"));
        assertTrue(catalogSource.contains("case \"wolf_sound_variant\" -> registryKeysByField(\"WOLF_SOUND_VARIANT\")"));
        assertTrue(catalogSource.contains("case \"zombie_nautilus_variant\" -> registryKeysByField(\"ZOMBIE_NAUTILUS_VARIANT\")"));
        assertFalse(catalogSource.contains("valuesOrFallback"));
        assertTrue(catalogSource.contains("private List<String> registryKeysByField(String fieldName)"));
        assertTrue(catalogSource.contains("private List<String> potionTypes()"));
        assertTrue(source.contains("discoverNonValuedComponentIds()"));
        assertTrue(source.contains("metadata.put(\"advanced\", advanced)"));
        assertTrue(source.contains("metadata.put(\"editableJson\", false)"));
        assertTrue(source.contains("default -> \"Raw Component Data\""));
        assertFalse(source.contains("Advanced Component"));
        assertTrue(source.contains("if (exampleValue == null)"));
        assertTrue(source.contains("return profileAppliesToMaterial(material, profile);"));
        assertTrue(source.contains("private boolean profileAppliesToMaterial(Material material, AttributeUiProfile profile)"));
        assertTrue(source.contains("private boolean materialMatchesTarget(Material material, String name, String target)"));
        int matcherStart = source.indexOf("private boolean materialMatchesTarget(Material material, String name, String target)");
        int matcherEnd = source.indexOf("\n    private boolean materialContains", matcherStart);
        assertTrue(matcherStart >= 0);
        assertTrue(matcherEnd > matcherStart);
        String materialMatcherSource = source.substring(matcherStart, matcherEnd);
        Matcher appliesToMatcher = Pattern.compile("\"appliesTo\"\\s*:\\s*\\[(.*?)]").matcher(uiSchemaSource);
        boolean foundAppliesToTarget = false;
        while (appliesToMatcher.find()) {
            Matcher targetMatcher = Pattern.compile("\"([a-z0-9_]+)\"").matcher(appliesToMatcher.group(1));
            while (targetMatcher.find()) {
                foundAppliesToTarget = true;
                String target = targetMatcher.group(1);
                assertTrue(materialMatcherSource.contains("\"" + target + "\""), target);
            }
        }
        assertTrue(foundAppliesToTarget);
        assertFalse(source.contains("if (!itemJsonRoundTripSupported()) {\n            return false;\n        }"));
        assertFalse(source.contains("return componentIds().contains(id) && itemJsonRoundTripSupported()"));
        assertTrue(storageSource.contains("ItemAttributeSchemaService"));
        assertTrue(storageSource.contains("attributeSchemaService.validate(definition.getMaterial(), definition.getComponents())"));
        assertTrue(storageSource.contains("throw new ItemAttributeValidationException(componentErrors)"));
    }

    @Test
    void schemaServiceUsesRuntimeDiscoveryWithoutSchemaBackfill() {
        ItemAttributeSchemaService service = new ItemAttributeSchemaService(
            List.of("minecraft:food", "minecraft:consumable"),
            Map.of(),
            false
        );

        List<String> values = service.values("STICK");

        assertTrue(values.contains("minecraft:food"));
        assertTrue(values.contains("minecraft:consumable"));
        assertEquals(2, values.size());
    }

    @Test
    void schemaServiceDoesNotExposeSchemaOnlyComponentsAsCompatible() {
        ItemAttributeSchemaService service = new ItemAttributeSchemaService(
            List.of(),
            Map.of(),
            false
        );

        assertTrue(service.values("STICK").isEmpty());
    }

    @Test
    void schemaServiceReportsUnsupportedRoundTripValidation() {
        ItemAttributeSchemaService service = new ItemAttributeSchemaService(
            List.of("minecraft:food"),
            Map.of(),
            false
        );

        List<Map<String, Object>> errors = service.validate("STICK", Map.of("minecraft:food", Map.of("nutrition", 4.0)));

        assertTrue(errors.stream().anyMatch(error -> String.valueOf(error.get("message")).contains("Item component JSON is not available")));
    }
}

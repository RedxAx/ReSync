package restudio.resync.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import restudio.resync.resources.ReSyncResourceCatalog;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonRuntimeResourceValidatorTest {
    private final JsonRuntimeResourceValidator validator = new JsonRuntimeResourceValidator(null);

    @Test
    void validTradeProfilePassesAuthoritativeValidation() {
        assertDoesNotThrow(() -> validator.validate(ReSyncResourceCatalog.TRADE_PROFILE, profile("minecraft:emerald", "minecraft:book")));
    }

    @Test
    void invalidTradeReferencesAndAmountsAreRejectedBeforePersistence() {
        JsonObject unavailable = profile("minecraft:not_a_real_item", "minecraft:book");
        JsonObject invalidAmount = profile("minecraft:emerald", "minecraft:book");
        invalidAmount.getAsJsonArray("offers").get(0).getAsJsonObject().addProperty("resultAmount", 0);

        assertThrows(IllegalArgumentException.class, () -> validator.validate(ReSyncResourceCatalog.TRADE_PROFILE, unavailable));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(ReSyncResourceCatalog.TRADE_PROFILE, invalidAmount));
    }

    @Test
    void invalidTradeIdentityAndRuntimeFieldsAreRejected() {
        JsonObject invalidId = profile("minecraft:emerald", "minecraft:book");
        invalidId.addProperty("id", "../outside");
        JsonObject invalidLevel = profile("minecraft:emerald", "minecraft:book");
        invalidLevel.addProperty("level", 6);
        JsonObject invalidProfession = profile("minecraft:emerald", "minecraft:book");
        invalidProfession.addProperty("profession", "unknown");

        assertThrows(IllegalArgumentException.class, () -> validator.validate(ReSyncResourceCatalog.TRADE_PROFILE, invalidId));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(ReSyncResourceCatalog.TRADE_PROFILE, invalidLevel));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(ReSyncResourceCatalog.TRADE_PROFILE, invalidProfession));
    }

    @Test
    void lootRecipeNpcDialogAndAdvancementDefinitionsUseDomainValidation() {
        assertDoesNotThrow(() -> validator.validate(ReSyncResourceCatalog.LOOT_TABLE, lootTable()));
        assertDoesNotThrow(() -> validator.validate(ReSyncResourceCatalog.RECIPE_DEFINITION, recipe()));
        assertDoesNotThrow(() -> validator.validate(ReSyncResourceCatalog.NPC_DEFINITION, npc()));
        assertDoesNotThrow(() -> validator.validate(ReSyncResourceCatalog.DIALOG, dialog()));
        assertDoesNotThrow(() -> validator.validate(ReSyncResourceCatalog.ADVANCEMENT_TREE, advancementTree()));

        JsonObject invalidLoot = lootTable();
        invalidLoot.getAsJsonArray("pools").get(0).getAsJsonObject().getAsJsonArray("entries").get(0).getAsJsonObject().addProperty("chance", 101);
        JsonObject invalidRecipe = recipe();
        invalidRecipe.remove("output");
        JsonObject invalidNpc = npc();
        invalidNpc.addProperty("entityType", "minecraft:not_real");
        JsonObject invalidDialog = dialog();
        invalidDialog.addProperty("actions", "not-an-array");
        JsonObject invalidTree = advancementTree();
        invalidTree.getAsJsonObject("nodes").getAsJsonObject("root").getAsJsonObject("display").addProperty("frame", "invalid");

        assertThrows(IllegalArgumentException.class, () -> validator.validate(ReSyncResourceCatalog.LOOT_TABLE, invalidLoot));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(ReSyncResourceCatalog.RECIPE_DEFINITION, invalidRecipe));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(ReSyncResourceCatalog.NPC_DEFINITION, invalidNpc));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(ReSyncResourceCatalog.DIALOG, invalidDialog));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(ReSyncResourceCatalog.ADVANCEMENT_TREE, invalidTree));
    }

    @Test
    void npcHookAndEntityContractsAreValidatedBeforePersistence() {
        JsonObject valid = npc();
        valid.getAsJsonObject("hooks").addProperty("rightClickAction", "npc-click");
        JsonObject invalidHook = npc();
        invalidHook.getAsJsonObject("hooks").addProperty("damageAction", true);
        JsonObject damageablePlayer = npc();
        damageablePlayer.addProperty("entityType", "player");
        damageablePlayer.addProperty("invulnerable", false);
        JsonObject aiPlayer = npc();
        aiPlayer.addProperty("entityType", "player");
        aiPlayer.addProperty("ai", true);
        JsonObject invalidSkin = npc();
        invalidSkin.addProperty("entityType", "player");
        JsonObject skin = new JsonObject();
        skin.addProperty("username", "invalid player name");
        invalidSkin.add("skin", skin);
        JsonObject ambiguousSkin = npc();
        ambiguousSkin.addProperty("entityType", "player");
        JsonObject ambiguousSkinSources = new JsonObject();
        ambiguousSkinSources.addProperty("username", "Notch");
        ambiguousSkinSources.addProperty("uuid", "069a79f444e94726a5befca90e38aaf5");
        ambiguousSkin.add("skin", ambiguousSkinSources);
        JsonObject orphanSkinSignature = npc();
        orphanSkinSignature.addProperty("entityType", "player");
        JsonObject signatureOnly = new JsonObject();
        signatureOnly.addProperty("signature", "signature");
        orphanSkinSignature.add("skin", signatureOnly);
        JsonObject ambiguousInteraction = npc();
        ambiguousInteraction.addProperty("dialog", "welcome");
        ambiguousInteraction.addProperty("tradeProfile", "merchant");

        assertDoesNotThrow(() -> validator.validate(ReSyncResourceCatalog.NPC_DEFINITION, valid));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(ReSyncResourceCatalog.NPC_DEFINITION, invalidHook));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(ReSyncResourceCatalog.NPC_DEFINITION, damageablePlayer));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(ReSyncResourceCatalog.NPC_DEFINITION, aiPlayer));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(ReSyncResourceCatalog.NPC_DEFINITION, invalidSkin));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(ReSyncResourceCatalog.NPC_DEFINITION, ambiguousSkin));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(ReSyncResourceCatalog.NPC_DEFINITION, orphanSkinSignature));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(ReSyncResourceCatalog.NPC_DEFINITION, ambiguousInteraction));
    }

    @Test
    void npcLegacyHooksAndSkinFieldsMigrateBeforeSave() {
        JsonObject definition = npc();
        definition.addProperty("entityType", "player");
        definition.addProperty("spawnMode", "startup");
        definition.add("location", new JsonObject());
        definition.addProperty("skinUsername", "Notch");
        definition.addProperty("skinTexture", "legacy-texture");
        definition.getAsJsonObject("hooks").addProperty("interactFlow", "legacy-interact");

        validator.beforeSave(ReSyncResourceCatalog.NPC_DEFINITION, definition);

        assertEquals("Notch", definition.getAsJsonObject("skin").get("username").getAsString());
        assertFalse(definition.getAsJsonObject("skin").has("texture"));
        assertEquals("legacy-interact", definition.getAsJsonObject("hooks").get("interactAction").getAsString());
        assertFalse(definition.has("skinUsername"));
        assertFalse(definition.has("skinTexture"));
        assertFalse(definition.has("spawnMode"));
        assertFalse(definition.has("location"));
        assertFalse(definition.getAsJsonObject("hooks").has("interactFlow"));
    }

    private JsonObject profile(String cost, String result) {
        JsonObject profile = new JsonObject();
        profile.addProperty("id", "librarian");
        profile.addProperty("profession", "librarian");
        profile.addProperty("villagerType", "plains");
        profile.addProperty("level", 2);
        JsonObject offer = new JsonObject();
        offer.addProperty("cost", cost);
        offer.addProperty("costAmount", 2);
        offer.addProperty("result", result);
        offer.addProperty("resultAmount", 1);
        JsonArray offers = new JsonArray();
        offers.add(offer);
        profile.add("offers", offers);
        return profile;
    }

    private JsonObject lootTable() {
        JsonObject entry = new JsonObject();
        entry.addProperty("item", "minecraft:stone");
        entry.addProperty("minAmount", 1);
        entry.addProperty("maxAmount", 2);
        entry.addProperty("weight", 1);
        entry.addProperty("chance", 100);
        JsonArray entries = new JsonArray();
        entries.add(entry);
        JsonObject pool = new JsonObject();
        pool.addProperty("rolls", 1);
        pool.add("entries", entries);
        JsonArray pools = new JsonArray();
        pools.add(pool);
        JsonObject table = new JsonObject();
        table.addProperty("id", "starter");
        table.add("pools", pools);
        return table;
    }

    private JsonObject recipe() {
        JsonObject output = new JsonObject();
        output.addProperty("material", "minecraft:stone");
        output.addProperty("amount", 1);
        JsonObject ingredient = new JsonObject();
        ingredient.addProperty("material", "minecraft:cobblestone");
        JsonArray ingredients = new JsonArray();
        ingredients.add(ingredient);
        JsonObject recipe = new JsonObject();
        recipe.addProperty("id", "stone");
        recipe.addProperty("type", "shapeless");
        recipe.add("output", output);
        recipe.add("ingredients", ingredients);
        return recipe;
    }

    private JsonObject npc() {
        JsonObject npc = new JsonObject();
        npc.addProperty("id", "guide");
        npc.addProperty("entityType", "minecraft:villager");
        npc.addProperty("followRange", 12);
        npc.add("hooks", new JsonObject());
        return npc;
    }

    private JsonObject dialog() {
        JsonObject dialog = new JsonObject();
        dialog.addProperty("id", "welcome");
        dialog.addProperty("type", "minecraft:notice");
        dialog.add("body", new JsonArray());
        dialog.add("inputs", new JsonArray());
        dialog.add("actions", new JsonArray());
        return dialog;
    }

    private JsonObject advancementTree() {
        JsonObject criterion = new JsonObject();
        criterion.addProperty("trigger", "impossible");
        JsonObject criteria = new JsonObject();
        criteria.add("requirement", criterion);
        JsonArray group = new JsonArray();
        group.add("requirement");
        JsonArray requirements = new JsonArray();
        requirements.add(group);
        JsonObject display = new JsonObject();
        display.addProperty("icon", "minecraft:stone");
        display.addProperty("frame", "task");
        JsonObject root = new JsonObject();
        root.addProperty("enabled", true);
        root.add("display", display);
        root.add("criteria", criteria);
        root.add("requirements", requirements);
        JsonObject nodes = new JsonObject();
        nodes.add("root", root);
        JsonObject tree = new JsonObject();
        tree.addProperty("id", "story");
        tree.add("nodes", nodes);
        return tree;
    }
}

package restudio.resync.flow.handler.family;

import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import restudio.flow.data.FlowGraph;
import restudio.flow.data.FlowNode;
import restudio.flow.data.FlowTypeRef;
import restudio.resync.flow.CompiledRuntimeValueCodec;
import restudio.resync.flow.FlowContext;
import restudio.resync.flow.FlowRuntime;
import restudio.resync.flow.ItemStackPropertySelector;
import restudio.resync.flow.TypeAdapterRegistry;
import restudio.resync.flow.handler.HandlerRegistry;
import restudio.resync.flow.handler.NodeHandler;
import restudio.resync.flow.handler.property.PropertyRegistry;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.registry.NodeDefinition;
import restudio.resync.flow.registry.NodeDefinitionDiagnostic;
import restudio.resync.flow.registry.NodeDefinitionLoader;
import restudio.resync.flow.registry.NodeDefinitionRegistry;
import restudio.resync.flow.runtime.CompiledRuntimeContext;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonFamilyHandlerTest {
    @Test
    void playerFamilyReadsExplicitStateProperties() throws Exception {
        PlayerInventory inventory = proxy(PlayerInventory.class, (proxy, method, args) -> switch (method.getName()) {
            case "getSize" -> 36;
            case "getMaxStackSize" -> 64;
            default -> fallback(method.getReturnType());
        });
        UUID uuid = UUID.randomUUID();
        Player player = proxy(Player.class, (proxy, method, args) -> switch (method.getName()) {
            case "getName" -> "RedxAx";
            case "getUniqueId" -> uuid;
            case "getHealth" -> 18.0;
            case "getLevel" -> 12;
            case "getTotalExperience" -> 420;
            case "getExpToLevel" -> 33;
            case "isOp" -> true;
            case "getInventory" -> inventory;
            default -> fallback(method.getReturnType());
        });

        NodeHandler handler = family("player");

        assertEquals("RedxAx", read(handler, player, "name"));
        assertEquals(uuid, read(handler, player, "uuid"));
        assertEquals(18.0, read(handler, player, "health"));
        assertEquals(true, read(handler, player, "is_op"));
        assertEquals(12, read(handler, player, "xp_level"));
        assertEquals(420, read(handler, player, "total_exp"));
        assertEquals(33, read(handler, player, "exp_to_level"));
        assertSame(inventory, read(handler, player, "inventory"));
    }

    @Test
    void entityFamilyReadsExplicitStateProperties() throws Exception {
        AttributeInstance maxHealth = proxy(AttributeInstance.class, (proxy, method, args) -> switch (method.getName()) {
            case "getValue" -> 20.0;
            default -> fallback(method.getReturnType());
        });
        LivingEntity entity = proxy(LivingEntity.class, (proxy, method, args) -> switch (method.getName()) {
            case "getType" -> EntityType.ZOMBIE;
            case "getUniqueId" -> UUID.fromString("00000000-0000-0000-0000-000000000001");
            case "getHealth" -> 7.0;
            case "getAttribute" -> maxHealth;
            case "getVelocity" -> null;
            case "isValid" -> true;
            case "isDead" -> false;
            default -> fallback(method.getReturnType());
        });

        NodeHandler handler = family("entity");

        assertEquals("ZOMBIE", read(handler, entity, "type"));
        assertEquals(7.0, read(handler, entity, "health"));
        assertEquals(20.0, read(handler, entity, "max_health"));
        assertEquals(true, read(handler, entity, "is_alive"));
    }

    @Test
    void playerFamilyUsesMaximumAirAccessorForMaxAir() throws Exception {
        int[] maximumAir = {300};
        Player player = proxy(Player.class, (proxy, method, args) -> switch (method.getName()) {
            case "getMaximumAir" -> maximumAir[0];
            case "setMaximumAir" -> {
                maximumAir[0] = ((Number) args[0]).intValue();
                yield null;
            }
            default -> fallback(method.getReturnType());
        });

        NodeHandler handler = family("player");

        assertEquals(300, read(handler, player, "max_air"));
        write(handler, player, "max_air", 420);
        assertEquals(420, maximumAir[0]);
    }

    @Test
    void entityFamilyUsesMaximumAirAccessorForMaxAir() throws Exception {
        int[] maximumAir = {400};
        LivingEntity entity = proxy(LivingEntity.class, (proxy, method, args) -> switch (method.getName()) {
            case "getMaximumAir" -> maximumAir[0];
            case "setMaximumAir" -> {
                maximumAir[0] = ((Number) args[0]).intValue();
                yield null;
            }
            default -> fallback(method.getReturnType());
        });

        NodeHandler handler = family("entity");

        assertEquals(400, read(handler, entity, "max_air"));
        write(handler, entity, "max_air", 500);
        assertEquals(500, maximumAir[0]);
    }

    @Test
    void itemStackFamilyReadsPopulatedRepairAndLocalizedMetadata() throws Exception {
        Repairable meta = proxy(Repairable.class, (proxy, method, args) -> switch (method.getName()) {
            case "hasRepairCost" -> true;
            case "getRepairCost" -> 7;
            case "hasLocalizedName" -> true;
            case "getLocalizedName" -> "item.custom_name";
            default -> fallback(method.getReturnType());
        });
        ItemStack item = new TestItemStack(meta);

        NodeHandler handler = family("itemstack");

        assertEquals(7, read(handler, item, "repair_cost"));
        assertEquals("item.custom_name", read(handler, item, "localized_name"));
    }

    @Test
    void itemStackFamilyUsesDefaultsWhenRepairAndLocalizedMetadataIsAbsent() throws Exception {
        Repairable meta = proxy(Repairable.class, (proxy, method, args) -> switch (method.getName()) {
            case "hasRepairCost" -> false;
            case "getRepairCost" -> 99;
            case "hasLocalizedName" -> false;
            case "getLocalizedName" -> "ignored.name";
            default -> fallback(method.getReturnType());
        });
        ItemStack item = new TestItemStack(meta);

        NodeHandler handler = family("itemstack");

        assertEquals(0, read(handler, item, "repair_cost"));
        assertEquals("", read(handler, item, "localized_name"));
    }

    @Test
    void worldBlockAndInventoryFamiliesReadExplicitProperties() throws Exception {
        World world = proxy(World.class, (proxy, method, args) -> switch (method.getName()) {
            case "getTime" -> 6000L;
            case "getFullTime" -> 24000L;
            case "hasStorm" -> true;
            case "isThundering" -> false;
            case "getDifficulty" -> Difficulty.HARD;
            case "getPVP" -> true;
            default -> fallback(method.getReturnType());
        });
        BlockData blockData = proxy(BlockData.class, (proxy, method, args) -> switch (method.getName()) {
            case "getAsString" -> "minecraft:stone";
            default -> fallback(method.getReturnType());
        });
        BlockState state = proxy(BlockState.class, (proxy, method, args) -> switch (method.getName()) {
            case "getBlockData" -> blockData;
            default -> fallback(method.getReturnType());
        });
        Block block = proxy(Block.class, (proxy, method, args) -> switch (method.getName()) {
            case "getType" -> Material.STONE;
            case "getBlockData" -> blockData;
            case "getState" -> state;
            case "isSolid" -> true;
            default -> fallback(method.getReturnType());
        });
        ItemStack[] items = new ItemStack[] {null};
        Inventory inventory = proxy(Inventory.class, (proxy, method, args) -> switch (method.getName()) {
            case "getSize" -> 54;
            case "getContents", "getStorageContents" -> items;
            case "firstEmpty" -> 4;
            case "getMaxStackSize" -> 64;
            default -> fallback(method.getReturnType());
        });

        assertEquals(6000L, read(family("world"), world, "time"));
        assertEquals(24000L, read(family("world"), world, "full_time"));
        assertEquals("rain", read(family("world"), world, "weather_type"));
        assertEquals(true, read(family("world"), world, "has_storm"));
        assertEquals("HARD", read(family("world"), world, "difficulty"));
        assertEquals(true, read(family("world"), world, "pvp"));
        assertEquals("STONE", read(family("block"), block, "type"));
        assertEquals("minecraft:stone", read(family("block"), block, "data"));
        assertEquals(true, read(family("block"), block, "is_solid"));
        assertEquals(54, read(family("inventory"), inventory, "size"));
        assertSame(items, read(family("inventory"), inventory, "items"));
        assertEquals(4, read(family("inventory"), inventory, "first_empty"));
        assertEquals(64, read(family("inventory"), inventory, "max_stack_size"));
    }

    @Nested
    class RuntimeContracts {
        private static final ServerId SERVER = ServerId.parseCanonicalText("11111111-1111-5111-8111-111111111111");
        private final NodeDefinitionRegistry definitions = new NodeDefinitionRegistry(false);
        private final PropertyRegistry properties = new PropertyRegistry();
        private final HandlerRegistry handlers = new HandlerRegistry();
        private World world;
        private Player player;

        @BeforeEach
        void setUp() throws Exception {
            MockBukkit.mock();
            world = MockBukkit.getMock().addSimpleWorld("property-world");
            player = MockBukkit.getMock().addPlayer("PropertyPlayer");
            player.teleport(new Location(world, 2, 65, 3));
            List<NodeDefinition> loaded = new ArrayList<>();
            NodeDefinitionLoader loader = new NodeDefinitionLoader();
            for (String family : List.of("player", "entity", "block", "itemstack")) {
                String resource = "nodes/" + family + ".json";
                byte[] source = Files.readAllBytes(Path.of("src/main/resources", resource));
                try (InputStream bundled = getClass().getClassLoader().getResourceAsStream(resource)) {
                    assertNotNull(bundled, resource);
                    assertArrayEquals(source, bundled.readAllBytes(), resource);
                }
                loaded.addAll(loader.parseReplacement(new ByteArrayInputStream(source), resource));
            }
            assertTrue(loader.getDiagnostics().stream().noneMatch(diagnostic -> diagnostic.severity() == NodeDefinitionDiagnostic.Severity.ERROR),
                loader.getDiagnostics().toString());
            definitions.registerAll("restudio.resync", loaded);
            properties.loadNodeDefinitions(loaded);
            JsonFamilyHandler.registerFamilies(handlers, properties);
        }

        @AfterEach
        void tearDown() {
            MockBukkit.unmock();
        }

        @Test
        void playerSelectorRoundTripsNameHealthUuidAndLocation() {
            player.setHealth(17.5);
            Object target = roundTrip(TypeExpr.named(TypeReference.of("builtin", "player")), player);
            assertSame(player, target);
            assertEquals(player.getName(), query("player.properties", "name", target).roundTrip("name", "string"));
            assertEquals(17.5, ((Number) query("player.properties", "health", target).roundTrip("health", "number")).doubleValue());
            assertEquals(player.getUniqueId(), assertInstanceOf(UUID.class, query("player.properties", "uuid", target).roundTrip("uuid", "uuid")));
            assertEquals(player.getLocation(), query("player.properties", "location", target).roundTrip("location", "location"));
        }

        @Test
        void entitySelectorRoundTripsNameHealthUuidAndLocation() {
            LivingEntity entity = (LivingEntity) world.spawnEntity(new Location(world, 5, 65, 5), EntityType.ARMOR_STAND);
            entity.setHealth(9.0);
            Object target = roundTrip(TypeExpr.named(TypeReference.of("builtin", "entity")), entity);
            assertSame(entity, target);
            assertEquals(entity.getName(), query("entity.properties", "name", target).roundTrip("name", "string"));
            assertEquals(9.0, ((Number) query("entity.properties", "health", target).roundTrip("health", "number")).doubleValue());
            assertEquals(entity.getUniqueId(), query("entity.properties", "uuid", target).roundTrip("uuid", "uuid"));
            assertEquals(entity.getLocation(), query("entity.properties", "location", target).roundTrip("location", "location"));
            assertEquals(entity.getAttribute(Attribute.MAX_HEALTH).getValue(),
                ((Number) query("entity.properties", "max_health", target).roundTrip("max_health", "number")).doubleValue());
        }

        @Test
        void standaloneUuidQueryPreservesItsDeclaredTextOutput() {
            Query query = query("entity.uuid", "uuid", player);
            assertEquals(player.getUniqueId().toString(), query.roundTrip("value", "string"));
            assertEquals(player.getUniqueId(), query.context().getOutput(query.node(), "uuid"));
        }

        @Test
        void lastDamageReadsNumericDamageWithoutFabricatingAnEvent() {
            DamagePlayer damaged = new DamagePlayer(MockBukkit.getMock());
            MockBukkit.getMock().addPlayer(damaged);
            damaged.teleport(new Location(world, 2, 65, 3));
            Object target = roundTrip(TypeExpr.named(TypeReference.of("builtin", "player")), damaged);
            assertEquals(6.25, ((Number) query("player.properties", "last_damage", target).roundTrip("last_damage", "number")).doubleValue());
        }

        @Test
        void enchantmentsRoundTripTheirExactNamespacedKeysAndLevels() {
            ItemStack item = new ItemStack(Material.DIAMOND_SWORD);
            item.addUnsafeEnchantment(Enchantment.SHARPNESS, 3);
            item.addUnsafeEnchantment(Enchantment.UNBREAKING, 2);
            Object target = roundTrip(TypeExpr.named(TypeReference.of("builtin", "itemstack")), item);
            Object output = query("itemstack.properties", "enchantments", target).roundTrip("enchantments", "map<string,number>");
            assertEquals(Map.of("minecraft:sharpness", 3, "minecraft:unbreaking", 2), output);
        }

        @Test
        void itemSelectorPublishesEveryDeclaredPropertyWithItsDeclaredType() {
            ItemStack item = new ItemStack(Material.DIAMOND_SWORD, 3);
            ItemMeta meta = item.getItemMeta();
            assertNotNull(meta);
            meta.setDisplayName("Blade");
            meta.setLore(List.of("first", "second"));
            meta.setCustomModelData(42);
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);

            Map<String, String> properties = new LinkedHashMap<>();
            properties.put("type", "string");
            properties.put("amount", "number");
            properties.put("display_name", "string");
            properties.put("lore", "list<string>");
            properties.put("durability", "number");
            properties.put("max_durability", "number");
            properties.put("enchantments", "map<string,number>");
            properties.put("custom_model_data", "number");
            properties.put("unbreakable", "boolean");
            properties.put("repair_cost", "number");
            properties.put("item_flags", "list<string>");
            properties.put("localized_name", "string");

            Object target = roundTrip(TypeExpr.named(TypeReference.of("builtin", "itemstack")), item);
            for (Map.Entry<String, String> property : properties.entrySet()) {
                Object output = query("itemstack.properties", property.getKey(), target)
                    .roundTrip(property.getKey(), property.getValue());
                assertNotNull(output, property.getKey());
            }
            Query flags = query("itemstack.properties", "item_flags", target);
            assertEquals(List.of("HIDE_ATTRIBUTES"), flags.context().getOutput(flags.node(), "item_flags"));
        }

        @Test
        void itemPropertiesKeepsOneFamilyWithMatchingGetAndSetTypes() {
            NodeDefinition definition = definitions.get("itemstack.properties");
            assertNotNull(definition);
            assertEquals("Item Properties", definition.getDisplayName());
            assertEquals(NodeDefinition.NodeKind.FAMILY, definition.getKind());
            assertEquals(List.of("get", "has", "set"),
                FlowRuntime.resolveInputPin(definition, "action").getOptions());

            Map<String, FlowTypeRef> getters = new LinkedHashMap<>();
            definition.getOutputs().stream()
                .filter(pin -> "get".equals(pin.getVisibleWhen().get("action")))
                .forEach(pin -> getters.put(pin.getName(), pin.getTypeRef()));
            assertFalse(getters.isEmpty());
            getters.forEach((property, type) -> {
                NodeDefinition.PinDefinition setter = FlowRuntime.resolveInputPin(definition, "set_" + property);
                assertNotNull(setter, property);
                assertEquals(type, setter.getTypeRef(), property);
                assertEquals("set", setter.getVisibleWhen().get("action"), property);
                assertEquals(property, setter.getVisibleWhen().get("property"), property);
            });

            NodeDefinition bulkComponents = definitions.get("itemstack.item_apply_components");
            assertNotNull(bulkComponents);
            assertTrue(bulkComponents.isHidden());
            assertEquals("Apply Item Components", bulkComponents.getDisplayName());
        }

        @Test
        void itemPropertiesSetWritesEveryAdvertisedProperty() {
            ItemStack item = new ItemStack(Material.DIAMOND_SWORD);

            set("amount", item, 1);
            set("display_name", item, "Blade");
            set("lore", item, List.of("first", "second"));
            set("max_durability", item, 2000);
            set("durability", item, 17);
            set("enchantments", item, Map.of("minecraft:sharpness", 4));
            set("custom_model_data", item, 42);
            set("unbreakable", item, true);
            set("repair_cost", item, 7);
            set("item_flags", item, List.of("HIDE_ATTRIBUTES", "HIDE_ENCHANTS"));
            set("localized_name", item, "item.fixture.blade");

            assertEquals(1, item.getAmount());
            assertEquals("Blade", read(item, "display_name"));
            assertEquals(List.of("first", "second"), read(item, "lore"));
            assertEquals(2000, read(item, "max_durability"));
            assertEquals(17, read(item, "durability"));
            assertEquals(Map.of("minecraft:sharpness", 4), read(item, "enchantments"));
            assertEquals(42, read(item, "custom_model_data"));
            assertEquals(true, read(item, "unbreakable"));
            assertEquals(7, read(item, "repair_cost"));
            assertEquals(List.of("HIDE_ATTRIBUTES", "HIDE_ENCHANTS"), read(item, "item_flags"));

            ItemStack changedType = new ItemStack(Material.STICK);
            set("type", changedType, "minecraft:diamond");
            assertEquals(Material.DIAMOND, changedType.getType());
        }

        @Test
        void itemPropertiesSetCommitsDetachedHeldItemBackToThePlayer() {
            player.getInventory().setItemInMainHand(new ItemStack(Material.STICK, 1));
            Object detached = roundTrip(TypeExpr.named(TypeReference.of("builtin", "itemstack")),
                player.getInventory().getItemInMainHand());
            Query query = prepare("itemstack.properties", "amount", detached, "set");
            query.node().getInputValues().put("set_amount", 11.0);

            query.execute();

            assertEquals(true, query.context().getOutput(query.node(), "success"));
            assertEquals(11, player.getInventory().getItemInMainHand().getAmount());
        }

        @Test
        void itemSelectorUsesAuthoredPropertyBeforeHandlerConfiguration() {
            ItemStack item = new ItemStack(Material.DIAMOND_SWORD);
            ItemMeta meta = item.getItemMeta();
            assertNotNull(meta);
            meta.setDisplayName("Blade");
            item.setItemMeta(meta);

            Query query = prepare("itemstack.properties", "display_name", item, "get");
            query.node().setHandlerConfig(Map.of("operation", "get", "action", "get", "property", "type"));
            query.execute();

            assertEquals("Blade", query.roundTrip("display_name", "string"));
            assertFalse(query.context().getRuntime().hasNodeOutput("node", "type"));
        }

        @Test
        void itemSelectorBridgesAStoredConfiguredPropertyIntoCompiledInputs() {
            assertTrue(ItemStackPropertySelector.applies("restudio.resync/itemstack.properties"));
            Map<String, Object> effective = ItemStackPropertySelector.effectiveInputs(
                "itemstack.properties", Map.of("target", "item"), Map.of("property", "display_name"));
            assertEquals("display_name", effective.get("property"));
            assertEquals(Map.of("operation", "get"), ItemStackPropertySelector.canonicalHandlerConfig(
                "itemstack.properties", Map.of("operation", "get", "property", "display_name")));
        }

        @Test
        void blockSelectorRoundTripsLocationAndWorldFromTheRealBlock() {
            world.getChunkAt(0, 0);
            Block block = world.getBlockAt(3, 64, 2);
            block.setType(Material.STONE);
            Object target = roundTrip(TypeExpr.named(TypeReference.of("builtin", "block")), block);
            assertEquals("STONE", query("block_properties", "type", target).roundTrip("type", "material"));
            assertEquals(block.getLocation(), query("block_properties", "location", target).roundTrip("location", "location"));
            assertSame(world, query("block_properties", "world", target).roundTrip("world", "world"));
        }

        @Test
        void velocityRoundTripsAsAVectorFromSelectorAndDedicatedPropertyNode() {
            Vector velocity = new Vector(1.0, 0.5, -2.0);
            player.setVelocity(velocity);
            for (String id : List.of("entity.properties", "entity.velocity")) {
                Query query = prepare(id, "velocity", player, "get");
                query.execute();
                String output = "entity.properties".equals(id) ? "velocity" : "value";
                assertEquals(velocity, query.roundTrip(output, "vector"));
            }
        }

        @Test
        void unsupportedHealthDoesNotPublishZeroForANonLivingEntity() {
            Entity entity = world.spawnEntity(new Location(world, 5, 65, 5), EntityType.ITEM_FRAME);
            for (String property : List.of("health", "max_health", "absorption")) {
                Query query = prepare("entity.properties", property, entity, "get");
                IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, query::execute);
                assertTrue(failure.getMessage().contains("requires a living entity"));
                assertFalse(query.context().getRuntime().hasNodeOutput("node", property));
                assertFalse(query.context().getRuntime().hasNodeOutput("node", "value"));
            }
        }

        @Test
        void unavailablePropertyAndActionRemainRegistryRejected() {
            Query unknown = prepare("player.properties", "class", player, "get");
            assertThrows(IllegalArgumentException.class, unknown::execute);
            assertFalse(unknown.context().getRuntime().hasNodeOutput("node", "value"));
            Query action = prepare("player.properties", "name", player, "destroy");
            assertThrows(IllegalArgumentException.class, action::execute);
            assertFalse(action.context().getRuntime().hasNodeOutput("node", "value"));
        }

        @Test
        void wrongTargetIsRejectedBeforeAnyOutputIsPublished() {
            Query query = prepare("player.properties", "name", world.getBlockAt(0, 64, 0), "get");
            assertThrows(IllegalArgumentException.class, query::execute);
            assertFalse(query.context().getRuntime().hasNodeOutput("node", "name"));
        }

        @Test
        void missingTargetUsesTheInvocationPlayer() {
            Query query = prepare("player.properties", "sneak", null, "get");
            assertSame(player, query.context().getPlayer());
            query.execute();
            assertTrue(query.context().getRuntime().hasNodeOutput("node", "sneak"));
            assertEquals(player.isSneaking(), query.context().getRuntime().getNodeOutput("node", "sneak"));
        }

        @Test
        void registeredTargetConversionSelectsOnlyTheRequestedPlayer() {
            Player selected = MockBukkit.getMock().addPlayer("SelectedPlayer");
            Query query = prepare("player.properties", "name", selected.getName(), "get");
            query.context().getTypeAdapter().registerStringParser(Player.class, MockBukkit.getMock()::getPlayerExact);
            query.execute();
            assertEquals(selected.getName(), query.roundTrip("name", "string"));
            Query missing = prepare("player.properties", "name", "MissingPlayer", "get");
            missing.context().getTypeAdapter().registerStringParser(Player.class, MockBukkit.getMock()::getPlayerExact);
            assertThrows(IllegalArgumentException.class, missing::execute);
            assertFalse(missing.context().getRuntime().hasNodeOutput("node", "name"));
        }

        private Query query(String id, String property, Object target) {
            Query query = prepare(id, property, target, "get");
            query.execute();
            return query;
        }

        private Query prepare(String id, String property, Object target, String action) {
            NodeDefinition definition = definitions.get(id);
            assertNotNull(definition, id);
            Map<String, Object> inputs = new LinkedHashMap<>();
            inputs.put(FlowRuntime.resolveInputPin(definition, "target").getName(), target);
            NodeDefinition.PinDefinition propertyPin = FlowRuntime.resolveInputPin(definition, "property");
            if (propertyPin != null) inputs.put(propertyPin.getName(), property);
            NodeDefinition.PinDefinition actionPin = FlowRuntime.resolveInputPin(definition, "action");
            if (actionPin != null) inputs.put(actionPin.getName(), action);
            FlowNode node = new FlowNode(id, 0, 0, inputs);
            node.setHandlerConfig(definition.getHandlerConfig());
            FlowGraph graph = new FlowGraph("property-test", Map.of("node", node), List.of(), List.of());
            FlowRuntime runtime = new FlowRuntime(graph, new TypeAdapterRegistry(), Map.of(), Map.of(), definitions);
            assertSame(definition, runtime.getDefinition(node));
            return new Query(node, new FlowContext(runtime, player, null), handlers.getHandler(definition.getHandler()));
        }

        private void set(String property, ItemStack item, Object value) {
            Query query = prepare("itemstack.properties", property, item, "set");
            query.node().getInputValues().put("set_" + property, value);
            query.execute();
            assertSame(item, query.context().getOutput(query.node(), "item"));
            assertEquals(true, query.context().getOutput(query.node(), "success"));
        }

        private Object read(ItemStack item, String property) {
            Query query = query("itemstack.properties", property, item);
            return query.context().getOutput(query.node(), property);
        }

        private static Object roundTrip(TypeExpr type, Object value) {
            TypedValue encoded = CompiledRuntimeValueCodec.encode(SERVER, type, value);
            assertTrue(new CompiledRuntimeContext(null, null, Map.of("value", encoded)).supportedByCompiledCore());
            return CompiledRuntimeValueCodec.decode(SERVER, encoded);
        }

        private static TypeExpr type(FlowTypeRef type) {
            return switch (type.getTypeId()) {
                case "map" -> new TypeExpr.MapType(type(type.getArguments().get(0)), type(type.getArguments().get(1)));
                case "list" -> new TypeExpr.ListType(type(type.getArguments().getFirst()));
                default -> TypeExpr.named(TypeReference.of("builtin", type.getTypeId()));
            };
        }

        private record Query(FlowNode node, FlowContext context, NodeHandler handler) {
            private void execute() {
                handler.execute(context, node);
            }

            private Object roundTrip(String output, String expectedType) {
                NodeDefinition.PinDefinition pin = context.getRuntime().resolveOutputPin(node, output);
                assertNotNull(pin, output);
                assertEquals(FlowTypeRef.parse(expectedType), pin.getTypeRef());
                return RuntimeContracts.roundTrip(type(pin.getTypeRef()), context.getOutput(node, output));
            }
        }

        private static final class DamagePlayer extends PlayerMock {
            private DamagePlayer(ServerMock server) {
                super(server, "DamagedPlayer");
            }

            @Override
            public double getLastDamage() {
                return 6.25;
            }

            @Override
            public EntityDamageEvent getLastDamageCause() {
                throw new AssertionError("The numeric damage property must not read the damage event");
            }
        }
    }

    private NodeHandler family(String id) {
        HandlerRegistry registry = new HandlerRegistry();
        JsonFamilyHandler.registerFamilies(registry, new PropertyRegistry());
        return registry.getHandler(id);
    }

    private Object read(NodeHandler handler, Object target, String property) throws Exception {
        Method method = handler.getClass().getDeclaredMethod("readValue", Object.class, String.class);
        method.setAccessible(true);
        return method.invoke(handler, target, property);
    }

    private void write(NodeHandler handler, Object target, String property, Object value) throws Exception {
        FlowNode node = new FlowNode("test", 0.0, 0.0, Map.of("value", value));
        FlowGraph graph = new FlowGraph("test", Map.of("node", node), List.of(), List.of());
        FlowContext context = new FlowContext(new FlowRuntime(graph, new TypeAdapterRegistry(), Map.of()), null, null);
        Method method = handler.getClass().getDeclaredMethod("setValue", FlowContext.class, FlowNode.class,
            Object.class, String.class);
        method.setAccessible(true);
        method.invoke(handler, context, node, target, property);
    }

    private <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private Object fallback(Class<?> returnType) {
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == double.class) {
            return 0.0;
        }
        if (returnType == float.class) {
            return 0.0f;
        }
        return null;
    }

    private static final class TestItemStack extends ItemStack {
        private final ItemMeta meta;

        private TestItemStack(ItemMeta meta) {
            super();
            this.meta = meta;
        }

        @Override
        public ItemMeta getItemMeta() {
            return meta;
        }
    }
}

# C2 Current Trace

## Startup To Execution

1. `FlowRuntimeModule.start` (`src/main/java/restudio/resync/modules/FlowRuntimeModule.java`, initialization around lines 230-340) creates `FlowStorage`, option/resource services, `HandlerRegistry`, `PropertyRegistry`, `NodeDefinitionRegistry`, validator, `FlowExecutor`, and `FlowGraphValidator`.
2. `registerNodeHandlers` (around lines 890-960) registers `ParticleHandler`, `PlayerActionHandler`, `EntityActionHandler`, `WorldActionHandler`, `BlockActionHandler`, `InventoryActionHandler`, then `JsonFamilyHandler.registerFamilies` for player/entity/world/block/inventory/itemstack.
3. `NodeDefinitionLoader.loadFromClasspath("nodes")` recursively discovers bundled nodes; `loadFromDirectory` adds data-folder nodes. `applyCompatibilityTransforms` and `normalizeLegacyPin` mutate parsed legacy JSON before `parseSingle`. `parseSingle` supplies default descriptions/tags/examples/safety metadata when raw JSON omits them. `validateAndRegister` registers definitions one by one.
4. `PropertyRegistry.loadNodeDefinitions(nodeDefinitionRegistry.getAllDefinitions().values())` derives family property descriptors from current definitions. `CustomFunctionNodeDefinitions.rebuild`, `FlowGraphMigrator.migrateStoredFlows`, and `TypedAutomationGraphMigrator.migrateStoredFlows` then run during normal startup.
5. `FlowExecutor.executeValidated` creates `FlowRuntime`, traverses mutable `FlowGraph` nodes/connections by string IDs/pin names, resolves the live handler/definition/defaults, executes the handler, and triggers named outputs. `resolveThreadPolicy` reads `NodeHandler.getThreadPolicy`, whose default is MAIN; scheduling is in `executeWithThreadPolicy`.

## Family Dispatch

`JsonFamilyHandler.execute` reads mutable `handlerConfig.property`/`action` or named inputs; validates through `PropertyRegistry`; resolves a Bukkit Player/Entity/World/Block/Inventory/ItemStack target; then performs named get/set/has/do/execute behavior. Read/write fallback uses Java reflection and dynamic coercion. It registers only `get`, `set`, `has`, `do`, `execute` as supported operation values.

`PropertyRegistry` stores handlers and descriptors in concurrent maps. `register` derives a descriptor from a runtime `PropertyHandler`; `loadNodeDefinitions` clears only `builtin` descriptors then re-derives properties from definition handler config and named pins; `registerDescriptor` merges existing actions/type data. This means JSON and runtime metadata are co-authoritative current behavior.

Dedicated generic handlers expose mutable operation maps: `PlayerActionHandler` (player state, messaging, command, movement, potion, advancement, cooldown, player item/inventory operations), `EntityActionHandler` (state/data/spawn/query/lifecycle), `WorldActionHandler` (world property plus management/portal operations), `BlockActionHandler` (block and region mutation/query), `InventoryActionHandler` (inventory and item construction/meta/component mutation), and `ParticleHandler` (canonical apply plus legacy shapes). Each reads `node.getHandlerConfig().getString("operation")` and throws on an unknown operation.

## Source Locations And Compatibility Relations

| Source location | Current relation |
|---|---|
| `resources/nodes/migrated/player.json` | Visible `player.properties`/`player.actions`; 53 hidden property aliases canonicalized to `player.properties`. |
| `player_action.json`, `player_query_restored.json` | Dedicated PlayerActionHandler operations; five hidden nodes canonicalize to player families; restored queries overlap property/action behavior. |
| `entity.json`, `entity_restored.json` | Visible entity property/action families, hidden property aliases, EntityActionHandler operations, and restored standalone operations coexist. |
| `world.json`, `world_action.json` | Hidden property/action aliases coexist with `WorldActionHandler`, including world-management and portal operations. |
| `block.json`, `block_action.json` | Visible block property/action family plus hidden aliases and BlockActionHandler operations. |
| `inventory.json`, `itemstack.json` | JsonFamilyHandler property surfaces overlap InventoryActionHandler inventory/item operations and PlayerActionHandler player-inventory/item operations. |
| `particle.json` | Canonical `particle.apply` and 16 hidden canonicalized legacy shape nodes; ParticleHandler translates legacy nodes at runtime by creating a temporary node. |
| `flow/handler/family/JsonFamilyHandler.java` | Name-driven family behavior, reflection, coercion, and output routing. |
| `flow/handler/property/PropertyRegistry.java` | Mixed runtime/JSON property descriptor ownership and merge behavior. |
| `flow/handler/generic/*ActionHandler.java`, `ParticleHandler.java` | Mutable operation maps and runtime-specific behavior/failures. |
| `flow/registry/NodeDefinitionLoader.java`, `NodeDefinitionRegistry.java` | Recursive migrated-tree discovery, parser compatibility/default inference, mutable per-definition registration/overwrite. |

## Structural Acceptance Data Evidence

Read-only local data is at `ReSync/run/plugins/ReSync`. It structurally contains assets, diagnostics, extensions, player-dossiers, structures, and world-management paths; `extensions` is empty. No local payload content, metadata content, diagnostic content, identity, or secret is copied or reported.

## Matrix Evidence Revision

The C2 matrix fixture and verifier are in the authorized test-only paths. `family-semantic-matrix.json` contains 497 source-derived definition rows and 2,833 source-derived physical pin rows. It marks runtime semantics not declared or directly bound by current source as individually `quarantine-required` rather than asserting behavioral parity. The first serial verifier mismatch was a singleton selector-options array flattened to a scalar; the fixture now preserves every raw options array shape exactly and structural validation found zero selector-options shape mismatches. The reviewer requested no further Gradle execution pending the combined serial rerun.

# C2 Pre-Implementation Findings — Runtime Domain Families

Status: Gate 0A evidence only. No production source, catalog, fixtures, or tests changed.

## Scope And Inventory

Owned review slice: Player, Entity, World, Block, Inventory, Item, and Particle nodes, including property/action aliases, modes, pins, runtime semantics, typed selection, effects, failures, and execution thread needs.

The current active classpath scan includes every file below `src/main/resources/nodes`, therefore it includes `nodes/migrated/**`. The assigned source files contain 497 definitions and 2,833 declared pins. All 497 lack an authored node description. The following source evidence is machine-counted from the JSON files: `player.json` 55/415 (53 hidden, 53 canonical); `player_action.json` 53/317 (5 hidden, 5 canonical); `player_query_restored.json` 19/44; `entity.json` 95/621 (56 hidden, 47 canonical); `entity_restored.json` 22/75; `world.json` 24/175 (24 hidden, 22 canonical); `world_action.json` 56/343 (43 hidden, 7 canonical); `block.json` 17/120 (15 hidden, 15 canonical); `block_action.json` 37/165 (7 hidden, 6 canonical); `inventory.json` 65/306 (21 hidden, 21 canonical); `itemstack.json` 37/218 (14 hidden, 12 canonical); and `particle.json` 17/34 (16 hidden, 16 canonical).

The raw catalog uses mutable names for all node IDs, pin identities, mode values, property names, handler and operation values. It declares `visibleWhen`, options and `optionsSource`, but not immutable pin/mode IDs, domain/lifecycle/inspector intent, explicit effects, thread policy, authorization, failure contract, or descriptions for nearly every node and many pins.

## Reproducible Raw Pin Description Coverage

Method: parse each named JSON array with PowerShell `ConvertFrom-Json`; total pins are `inputs + outputs`; an explicit description is a nonblank raw `description` field. Loader-generated descriptions are deliberately excluded.

| File | Definitions | Pins | Explicit pin descriptions | Missing explicit descriptions |
|---|---:|---:|---:|---:|
| `player.json` | 55 | 415 | 358 | 57 |
| `player_action.json` | 53 | 317 | 3 | 314 |
| `player_query_restored.json` | 19 | 44 | 0 | 44 |
| `entity.json` | 95 | 621 | 270 | 351 |
| `entity_restored.json` | 22 | 75 | 0 | 75 |
| `world.json` | 24 | 175 | 149 | 26 |
| `world_action.json` | 56 | 343 | 3 | 340 |
| `block.json` | 17 | 120 | 99 | 21 |
| `block_action.json` | 37 | 165 | 0 | 165 |
| `inventory.json` | 65 | 306 | 49 | 257 |
| `itemstack.json` | 37 | 218 | 82 | 136 |
| `particle.json` | 17 | 34 | 0 | 34 |
| **Total** | **497** | **2,833** | **1,013** | **1,820** |

## Problems Addressed

- `NSR-003`, `NSR-004`, `NSR-005`, `NSR-006`, `NSR-009`, `NSR-011`, `NSR-012`, `NSR-013`, `NSR-016`, and `NSR-018` apply directly to this slice.
- `C-P06` — Property-family JSON, `PropertyRegistry`, reflective `JsonFamilyHandler`, and dedicated action handlers independently express the same family surface. Examples: `player.properties`/`player.actions` coexist with `PlayerActionHandler`; `entity.properties`/`entity.actions` coexist with `EntityActionHandler`; `world.properties`/`world.actions` coexist with `WorldActionHandler`; item/inventory behavior is divided between `JsonFamilyHandler`, `InventoryActionHandler`, and Player item actions. The current source makes ownership and canonical semantics ambiguous.
- `C-P07` — Current family selectors and structural state are string/name based: `property`, `action`, `operation`, `handler`, `handlerConfig`, pin names, `canonicalId`, and `visibleWhen` keys. `JsonFamilyHandler` resolves values by these names, and `FlowExecutor` traverses pin names. Rename/reorder/mode migration safety is not intrinsic.
- `C-P08` — Handler safety semantics are runtime implementation details. The assigned handlers default to `NodeHandler.ThreadPolicy.MAIN`, while catalog definitions do not declare thread, cancellation, idempotency, authorization, effects, failure branches, or audit behavior. Block/world/entity/player/item mutations can be destructive or stateful without descriptor-level proof.
- `C-P09` — `JsonFamilyHandler` validates a property against `PropertyRegistry` then reflects Bukkit methods and coerces values dynamically. Advertised property/action/type data can disagree with an actual reader/writer, with failure delayed to execution (`No runtime reader/writer/action exists`). Its registry descriptors can originate from runtime registrations and from parsed legacy node definitions, then merge mutable action/type metadata.
- `C-P10` — Particle nodes have two overlapping representations: a hidden canonical `particle.apply` family plus sixteen hidden legacy shape operations. `ParticleHandler` remaps legacy shapes by constructing/mutating a temporary `FlowNode` with an `operation` handler config. This is a live compatibility behavior, not an offline-only conversion.
- `C-P11` — World management nodes mix ordinary world properties/actions with durable management operations (create/import/clone/load/unload/delete worlds, portal and player-state management). They have materially different persistence, destructive effects, authorization, and failure modes yet share `WorldActionHandler` and ordinary JSON shape.

## Current Behavior And Defects

`FlowRuntimeModule.start` creates mutable handler, definition, and property registries; registers generic handlers and family handlers; loads classpath and optional data-folder nodes; validates/registers one definition at a time; derives property descriptors from all registered definitions; preloads storage; then runs `FlowGraphMigrator` and `TypedAutomationGraphMigrator` during normal startup. `FlowExecutor` validates then interprets the mutable graph, resolves a handler from its live registry and defaults from the current definition, schedules according to handler policy, and dispatches named output pins.

Each property family uses `JsonFamilyHandler`: it reads `handlerConfig.property` or `property` input, reads `action`, checks `PropertyRegistry`, resolves a Bukkit target by family, then does `get`, `set`, `has`, `do`, or `execute` using explicit cases and reflective fallbacks. It emits dynamically named property outputs for gets and triggers `flow` for mutating action names. The implementation can expose values whose declared JSON types differ from actual Bukkit values (for example UUID strings and Bukkit arrays/objects), and it has no descriptor-declared unavailable/error output.

Dedicated handlers use mutable `ConcurrentHashMap<String, BiConsumer<FlowContext, FlowNode>>` operation maps. They read `handlerConfig.operation`; unknown operations throw `IllegalArgumentException`. Player delegates particle send by constructing a temporary particle node. Item construction, item mutation, and inventory mutation reside together in `InventoryActionHandler`; player-specific inventory/item operations also remain in `PlayerActionHandler`. Block action code has a 65,536 block action budget and ray-trace distance validation, but this boundary is runtime-only. World management delegates to `WorldManagementService`; its IDs and portal IDs are still scalar strings in node paths. All assigned handlers use Bukkit/Paper state and therefore need main-thread execution unless a contract-specific safe operation is proven; none overrides the default MAIN policy.

## Compact Domain Semantic Matrix

"Observed" is source-traced behavior. "Inferred Bukkit constraint" means it is a cautious operational inference from the handler's Bukkit/Paper API use, not a catalog declaration or verified API contract.

| Domain | Handler surface and target/selector representation | Effect and thread | Success/branch and failure | Canonical/alias gap |
|---|---|---|---|---|
| Player | Observed: `JsonFamilyHandler` (`player`, 55 definitions), `PlayerActionHandler` (53), `RestoredNodeHandler` (19). Target is runtime `Player` object from `target` or context player; property/action, hand, material and operation are strings/options. No typed persisted resource locator. | Observed reads, player-state mutation, inventory mutation, messaging/command/UI actions. Default handler policy MAIN. Inferred Bukkit constraint: all require main thread. | Observed family get/has writes outputs; set/do/execute emits `success` or `flow`; dedicated handlers usually trigger `flow`, throw `IllegalArgumentException`/`IllegalStateException`. | 53 hidden property aliases and five PlayerAction aliases; restored query nodes overlap both property and inventory surfaces. |
| Entity | Observed: `JsonFamilyHandler` (`entity`, 42), `EntityActionHandler` (53), `RestoredNodeHandler` (22). Target is runtime `Entity`; types/effects/tags use raw strings/options or runtime objects. | Observed query, entity state/data mutation, spawn/despawn/lifecycle. MAIN default. Inferred Bukkit constraint: main thread. | Observed named data/flow outputs; operation/validation failures throw. | 47 canonical hidden aliases within 56 hidden nodes; restored entity actions duplicate action/state operations. |
| World | Observed: `JsonFamilyHandler` (`world`, 24) plus `WorldActionHandler` (56). World is runtime `World`; management world/portal inputs and returned IDs include scalar string paths. | Observed world state mutation/query plus create/import/clone/load/unload/delete, portal and player-state management. MAIN default. Inferred Bukkit constraint: main thread and management is durable/destructive. | Observed handler writes outputs/flow; service and validation errors propagate as exceptions. | 22 canonical property aliases plus seven WorldAction aliases; ordinary state and management lifecycle share one handler surface. |
| Block | Observed: `JsonFamilyHandler` (`block`, 17) plus `BlockActionHandler` (37). Target is runtime `Block`/location; material/state options are strings. | Observed reads and point/region mutation, drops, sound, explode, physics. MAIN default. Inferred Bukkit constraint: main thread. | Observed mutating paths trigger flow; runtime validates 65,536 region budget and ray trace 0–1024, then throws on failure. | 15 property aliases and six action aliases; property surface overlaps block action queries. |
| Inventory | Observed: `JsonFamilyHandler` (`inventory`, 9) and `InventoryActionHandler` (56); PlayerActionHandler also mutates player inventory. Target is runtime `Inventory`, player, item stack, slot number or material string. | Observed query and mutable contents/slots; MAIN default. Inferred Bukkit constraint: main thread. | Observed operation map sets named outputs or flow; invalid/unknown operations throw. | 21 canonical hidden aliases; inventory behavior has three handler surfaces including Player actions. |
| Item | Observed: `JsonFamilyHandler` (`itemstack`, 14) and `InventoryActionHandler` (23). Target is runtime `ItemStack`; material/component/attribute IDs and structure fields are strings/maps/lists. | Observed construction and mutable item meta/component/content effects; MAIN default. Inferred Bukkit constraint: main thread for Bukkit item/meta interaction. | Observed outputs changed item/value or flow; component/attribute validation and unknown operation failures throw. | 12 canonical hidden aliases; item operations live under inventory handler and overlap Player item actions. |
| Particle | Observed: `ParticleHandler` (17), `particle.apply` plus sixteen legacy shapes; input selectors use runtime locations/players and raw particle/material/item option strings. | Observed visual external side effect; MAIN default. Inferred Bukkit constraint: main thread. | Observed `execute` invokes operation then flow; invalid operation/shape inputs throw. | All sixteen legacy shapes are hidden/canonicalized, but still execute through live temporary-node remapping. |

## Handler And Operation Inventory Closure

Every one of the 497 definitions has an observed dispatch route, but the inventory is only syntactically closed, not semantically closed:

- 161 definitions route to `JsonFamilyHandler`: Player 55, Entity 42, World 24, Block 17, Inventory 9, Item 14. Their property/action route is dynamic and requires `PropertyRegistry`, explicit special cases, reflection, coercion, and Bukkit method availability.
- 295 definitions route to registered dedicated handlers via the JSON handler ID and `handlerConfig.operation`: PlayerAction 53, EntityAction 53, WorldAction 56, BlockAction 37, InventoryAction 79, Particle 17. Their handlers register under the exact class-name IDs and maintain operation maps.
- 41 definitions route directly to the `RestoredNodeHandler` ID set: 19 player query nodes and 22 entity restored nodes.

The blocking gap is semantic parity for the 161 family definitions: there is no generated artifact proving, for every concrete definition/property/action/type combination, that `PropertyRegistry` accepts it and `JsonFamilyHandler` has a compatible explicit or reflective Bukkit runtime reader/writer/action with the declared pins. The required Gate 0A artifact is a deterministic handler-to-catalog matrix containing definition ID, handler ID, operation/property ID, target type, declared pin IDs/types, actual binding path, thread policy, effects, success outputs, failure code/form, canonical target, and unmapped/mismatched result. It must also cover all 295 dedicated operations and 41 restored IDs so syntactic route closure becomes reproducible rather than hand-counted.

## Proposed Classification And Replacement Goal

This is a classification proposal for Gate 0B, not an implementation design.

| Family | Classification | Replacement goal |
|---|---|---|
| Player | Family + Conditional; typed reference | One Player capability family with stable property/operation/mode IDs; retain only genuinely distinct inspector configuration. |
| Entity | Family + Conditional; typed reference | One Entity capability family, separating entity lifecycle/query/state concerns when their result/effect model differs. |
| World | Split + Family + Inspector; typed reference | Keep ordinary world state separate from destructive World Management and portal/state operations; use typed World/portal locators. |
| Block | Family + Conditional | Keep compact point operations; inspector-classify region, fill, replace, state, and container operations. |
| Inventory | Family + Conditional | Consolidate inventory target/slot/content operations under one coherent inventory capability surface. |
| Item | Family + Inspector | Consolidate item construction and component/meta mutations; structural component/object/list configuration needs generic inspector support. |
| Particle | Family + Inspector + Migration-only | One canonical particle configuration with shape/mode IDs; legacy shapes become one-time migration input only. |
| Hidden aliases/property-action duplicates | Delete / Migration-only | Convert only when behavior and wire mapping are proven; otherwise quarantine with a diagnostic. |

Replacement goal: preserve documented observable semantics only where they can be tied to a canonical capability and stable IDs; remove runtime aliases, loader transforms, temporary-node remapping, name-driven mode/pin routing, and duplicate property/action paths after controlled migration.

## Invariants, Neighbors, Contracts, And Impacts

Invariants: complete server/type/ID locators for selectable durable resources; immutable node/pin/mode/field IDs; explicit typed literal/default; catalog snapshot-bound execution; lossless opaque unavailable nodes; server-authoritative mutation; declared main-thread versus safe asynchronous behavior; no runtime catalog/default mutation; explicit failure/cancellation/authorization/effect contracts; no client node-ID branches for routine modes/selectors.

Affected neighbors: Flow graph documents/connections and Function calls; `FlowExecutor`/`FlowRuntime`; `FlowGraphValidator`; `NodeDefinitionLoader`/registry; `PropertyRegistry`; option catalog providers; world-management persistence and `WorldManagementService`; custom content/item component services; Player tracking and network state; extension handler lifecycle; Remotely generic graph, selector, inspector, cache, and protocol projections.

Shared contracts consumed (all pending Gate 0B): typed locator/resource key; descriptor/pin/mode/inspector/provenance; graph structural IDs and unknown data; type expression/value/codec/conversion; runtime capability/execution-plan; diagnostics; catalog contribution/snapshot; generic resource/option/collaboration protocol; migration/report/journal; snapshot/restore.

Client impact: substantial but must be descriptor-capability-driven. The current client may observe `visibleWhen`, widgets, options, and named pins; replacement must render family modes, typed selectors, branches, repeatables, component structures, descriptions, unavailable data and validation through generic capabilities. No ordinary node or extension in this slice should require a Remotely source change.

Persistence impact: world-management resources and local acceptance data are separate persistence participants from ordinary graph data. `run/plugins/ReSync` structurally contains assets, diagnostics, extensions, player-dossiers, structures, and world-management paths; the extensions path is empty. No local payload content is copied or reported in this evidence. Migration/snapshot scope must include these participants and metadata without letting metadata recreate resources.

Migration and compatibility impact: every `canonicalId`, hidden alias, legacy operation node, old pin name, mode value, handler/operation config, and particle temporary-node behavior needs a versioned offline mapping with explicit wire handling. `nodes/migrated/**` must be excluded from the replacement runtime. Existing clients/graphs may carry name-keyed state; compatibility must be an upgrader/bridge path, not a live handler, loader, or startup fallback.

Repository history does not establish supported family-format bounds: ReSync has no tags, and Remotely has only the `BETA` tag. The available `node-system-v2` remotes are not evidence of a supported release range. This slice therefore declares no source/target compatibility range; product-owner and fixture evidence are required before Gate 0B.

## Fixtures Required

- Family parity graphs for each `get`, `set`, `has`, `do`, and `execute` mode, including conditional-pin visibility and absent target/property failures.
- Canonical/alias conversion fixtures for every assigned JSON `canonicalId`, property/action duplicate, and all legacy Particle shapes.
- Typed selector fixtures for Player, Entity, World, material, particle/effect, item component, portal, and world-management targets, including same-ID/cross-type cases.
- Main-thread policy, cancellation, authorization/audit, destructive world/block bounds, and runtime-failure diagnostic fixtures.
- Item component/list/object inspector and opaque unknown-field round-trip fixtures.
- World-management resource, portal, player-state, metadata, snapshot/restore, migration interruption, and deletion/quarantine fixtures.
- Missing extension/unload/reinstall fixture exercising a node in this domain with no client code change.

## Exact Files Owned

Only `docs/node-replacement/reviewer-c/worker-2/findings.md` and `docs/node-replacement/reviewer-c/worker-2/trace.md`. No production files are owned or changed at Gate 0A.

## Unresolved Risks

- Current source has no proof that every JSON handler/operation is registered or every registered operation is represented; this needs a generated handler-to-catalog parity inventory.
- The retained runtime semantics of all aliases are not yet individually compared to their canonical target, especially type/pin/flow differences.
- Property descriptors merged from JSON/runtime sources can mask unsupported methods; no complete property/action/type matrix exists.
- World management requires its own persistence and authorization evidence before it can be accepted as a node family.
- Thread safety is inferred from Bukkit API use, not encoded per operation; any ASYNC classification needs direct implementation proof.

## Gate 0A Matrix Revision

The durable exhaustive C2 fixture is `src/test/resources/fixtures/node-replacement/runtime/c2/family-semantic-matrix.json`; the smaller `family-matrix.json` remains its count/route manifest. The focused verifier is `src/test/java/restudio/resync/replacement/evidence/runtime/c2/C2FamilySemanticMatrixTest.java`. The exhaustive fixture contains exactly 497 definition entries and 2,833 physical pin entries. Each definition records source file, domain, node ID, handler, operation/property/action, canonical ID, classification, status/reason, and effect/thread/branch/failure evidence. Each pin records direction, name, type, default, required/optional state, visibility, selector metadata, description, canonical mapping, status, and concrete reason.

The raw-source reconciliation is now fixed at 497 definitions, 2,833 physical pins, 1,013 authored pin descriptions, and 1,820 missing authored pin descriptions. The prior plan-wide 7,278 number remains a logical compatibility-pin baseline; it is not the physical raw-pin total for this C2 slice.

The matrix accounts for 161 family definitions, 295 dedicated operation definitions, and 41 restored definitions. This proves source dispatch coverage, but semantic rows lacking source-declared effects, thread policy, cancellation, authorization, success/failure branches, immutable selector/pin/mode identity, or canonical pin mapping remain individually `quarantine-required` with their own reason. No parity is inferred from a matching handler name.

The first focused test attempt under ADR-009 returned after reporting `Waiting For Another ReStudio Build To Finish`; no C2 XML test result was produced in `build/test-results/test`. This is not a passing test result. A later reviewer-run serial compilation exposed the first strict-matrix mismatch: `player.json:player.name:input:action:options` was serialized as scalar `"get"` although the raw source is `[` `"get"` `]`. The durable matrix has been corrected with `System.Text.Json.Nodes` so every present raw options field remains a JSON array, including singleton and empty arrays, and an absent raw options field remains JSON null. Read-only structural validation after correction reports 497 definitions, 2,833 pins, and zero option-shape mismatches. The reviewer has instructed that no Gradle task be run; the corrected verifier awaits the combined serial rerun.

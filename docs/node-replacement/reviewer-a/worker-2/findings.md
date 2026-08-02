# Worker 2 — Catalog, Loader, Local, Generated, And Extension Inventory

Status: Gate 0A read-only evidence. Inspected 2026-08-02. No production source, test, Gradle, build, or live-data mutation was performed. The only written files are this report and its two machine-readable inventories in this directory.

## Scope And Evidence Boundary

This slice covers bundled JSON, data-folder JSON discovery, generated Function definitions, extension discovery/contribution/unload, registry merge and validation, raw node/pin metrics, redirects, aliases, and provenance. It does not claim an execution, client hydration, revision-conflict, migration, reconnect, or full snapshot trace beyond the neighboring entry points cited below.

Commands used, all read-only:

- `rg --files C:\\Users\\redxa\\ReProjects\\ReSync\\docs\\node-replacement`
- `Get-Content -Raw` for the complete governing files, plan, and every Gate 0A evidence file existing at task start.
- `rg` and `Get-Content` against the cited Java sources.
- PowerShell `Get-ChildItem` plus `ConvertFrom-Json` raw traversal of `C:\\Users\\redxa\\ReProjects\\ReSync\\src\\main\\resources\\nodes`, excluding files whose basename begins with `_` exactly as the loader does.
- PowerShell structural count of `C:\\Users\\redxa\\ReProjects\\ReSync\\run\\plugins\\ReSync`; no values, contents, or names from live data were copied.

## Current Architecture Traced

### Bundled And Local Catalog

`restudio.resync.modules.FlowRuntimeModule.initialize` at `C:\\Users\\redxa\\ReProjects\\ReSync\\src\\main\\java\\restudio\\resync\\modules\\FlowRuntimeModule.java:229` creates handlers and `NodeDefinitionRegistry`, registers built-in handlers, then calls `NodeDefinitionLoader.loadFromClasspath("nodes")` and `validateAndRegister(..., "json-classpath")` at lines 265–269. `NodeDefinitionLoader.scanPath`/`scanJar` at `...\\flow\\registry\\NodeDefinitionLoader.java:173`/`:191` recursively include every JSON beneath `nodes`, excluding only basenames starting `_`. Therefore `nodes/migrated/**` is active, while `_id_migration_map.json` is skipped.

The same initializer checks `<plugin data folder>/nodes` at lines 270–274 and uses `loadFromDirectory`, which follows the same recursive/exclude-underscore rule at `NodeDefinitionLoader.java:217`. The reload path repeats clear → handler registration → bundled load → local load at `FlowRuntimeModule.java:392`–`:421`; it does not retain a last known valid registry.

Parsing is not raw-preserving. `NodeDefinitionLoader.parseSingle` at line 359 calls `applyCompatibilityTransforms` at lines 291–357, which renames legacy pin `id` to `name`, reinterprets `type`, and infers a handler property from the node ID. It also supplies a default category/display name/availability/hidden reason/description/tags/examples/sensitivity/destructiveness/audit/confirmation metadata (lines 359–681), infers option sources (lines 683–807), and maps `server:resync:*` selectors to specialized ID data types (lines 739–762). These are loader-inferred runtime values, not raw catalog declarations.

`NodeDefinitionLoader.validateAndRegister` at lines 809–839 detects duplicate IDs only against definitions already present and then registers each valid definition individually. It can leave preceding definitions active after a later definition fails. `NodeDefinitionRegistry.register` at `...\\flow\\registry\\NodeDefinitionRegistry.java:33` overwrites `definitions` by node ID and moves ownership bookkeeping; `registerAll` loops item by item (line 52). It has no synchronization, immutable snapshot, contribution-level validation, provenance object, generation, or atomic swap.

### Generated Function Definitions

`CustomFunctionNodeDefinitions.rebuild` at `...\\flow\\CustomFunctionNodeDefinitions.java:24` first calls `definitionRegistry.unregisterPlugin("custom_functions")`, reads every stored `function` graph by raw ID, builds definitions, then `registerAll`s them. `FlowRuntimeModule.initialize` calls it before and after both runtime graph migrators (lines 295–298); `FlowModule.refreshCustomFunctionDefinitions` calls it and broadcasts the full registry snapshot at `...\\modules\\FlowModule.java:362`–`:365`.

The generated definition ID is `custom_function:<flowId>`, while the handler config keeps `functionId` and owner/schema version come from the graph. `buildDefinition` sorts Function parameters by mutable `FunctionParameter.getName()` and creates pin names from those names at `CustomFunctionNodeDefinitions.java:48`–`:91` and `:142`–`:158`. This makes signature/pin identity name-derived and rebuild begins by making the complete Function contribution unavailable.

### Extension Discovery, Merge, And Unload

`ReSyncServer` constructs `ReSyncExtensionManager` with `<plugin data folder>/extensions` and calls `loadInitialExtensions` at `...\\server\\ReSyncServer.java:157`–`:159`. `ReSyncExtensionManager.scanDirectory` at `...\\api\\ReSyncExtensionManager.java:315` scans only top-level `.jar` files every five seconds after `tick` (lines 77–85), and `loadJar` at lines 347–371 uses `ServiceLoader<ReSyncExtension>`.

`registerExtension` inserts `ExtensionState` into `extensions` before `initializeExtension`, which invokes extension `initialize` and `start` at lines 160–193. An extension registers individual node definitions through `ExtensionFlowRegistration.registerNode` at lines 582–590: namespace check → mutate node owner → immediate `NodeDefinitionRegistry.register` → record ID. `registerNodes` at lines 592–600 parses extension resources then repeats that direct registration. Neither route calls `NodeDefinitionLoader.validateAndRegister`; node/handler/type/catalog/resource/validator/module contributions register independently.

On extension initialization failure, `registerExtension` removes the state and invokes `cleanupState` (lines 160–179). This rolls back tracked registrations but cannot undo arbitrary side effects from extension `initialize`/`start`; handlers registered before nodes, node conflicts, and cross-registry failure sequencing are not compiled as one contribution. `HandlerRegistry.register` at `...\\flow\\handler\\HandlerRegistry.java:13` replaces an existing handler and immediately shuts the previous one down.

Unload is triggered by missing/modified jars (`scanDirectory` lines 330–343), disabled Bukkit owners (`unloadDisabledOwners` lines 307–313), explicit handle close, or shutdown. `unloadJar` calls `unregister` for each plugin, then closes the classloader (lines 373–382). `cleanupState` at lines 225–305 removes node definitions, runtime handlers, properties, option catalogs, runtime data adapters, types/codecs, conversions, resources, validators, content/world-map providers, listeners, and modules sequentially. `unregister` then calls `refreshNodeRegistry`, which only rebuilds generated Functions and broadcasts the snapshot (lines 203–223). There is no catalog-wide validate-then-swap, no execution-plan ownership/drain, no guaranteed opaque unavailable-node preservation, and no proof that in-flight work retains its provider.

### Runtime And Persistence Neighbors

`FlowExecutor.executeValidated` creates `FlowRuntime` with the live mutable `NodeDefinitionRegistry` at `...\\flow\\FlowExecutor.java:230`–`:243`. `FlowRuntime.resolveDefinitionDefault` at `...\\flow\\FlowRuntime.java:192`–`:235` resolves the current node definition through `IdCompatibilityLayer.mapToNew`, then applies current defaults by pin name. This couples a saved graph to the active registry and compatibility aliases.

`FlowStorage` calls `migrateLegacyAssets` from construction and its implementation calls legacy flow/resource/custom-content migration at `...\\flow\\FlowStorage.java:102`, `:1539`–`:1552`, `:1839`, `:1864`, and `:1872`. `FlowRuntimeModule.initialize` additionally invokes `FlowGraphMigrator.migrateStoredFlows` and `TypedAutomationGraphMigrator.migrateStoredFlows` at lines 296–297. These paths neighbor catalog load because Function advertising is rebuilt around them and migrated JSON remains active.

## Raw Catalog Metrics And Coverage

The raw traversal result is in [raw-catalog-inventory.json](C:\\Users\\redxa\\ReProjects\\ReSync\\docs\\node-replacement\\reviewer-a\\worker-2\\raw-catalog-inventory.json). Its eligible source-tree hash is `c83f2e2828ee599c18563beaab2a131a160acceeb5b7ae8220064124188ff0e8`: SHA-256 over UTF-8 records sorted by slash-normalized path, where each record is `<lowercase file SHA-256><two spaces><relative path>`, newline-delimited with one final newline. The JSON inventory records the complete reproducible counting algorithm.

The physical raw JSON inventory is 61 eligible files, 1,428 definitions, 3,584 declared inputs, 3,689 declared outputs, and therefore 7,273 declared pin entries; 6,215 physical pin entries lack a description. 60 files and 1,420 definitions are below `nodes/migrated`; `automation.json` is the sole non-migrated source with 8 definitions. Other reproduced values: 999 visible, 429 hidden, 43 deprecated, 226 `canonicalId` redirects, 6 `replacementFor` definitions, 723 defaulted pins, 157 optional pins, 281 conditional inputs, 1,237 missing node descriptions, zero explicit domain/lifecycle/inspector-intent declarations, and 142 explicit schema versions.

The five-pin delta is reconciled. `custom_content.current`, `event.custom_content`, `logic.logic_true`, `logic.logic_false`, and `map.create` omit the `inputs` property altogether. The loader has `dto.inputs == null` and adds no input for them (`NodeDefinitionLoader.parseSingle` lines 442–450); `NodeDefinition.Builder` also has no implicit-input behavior. The published 7,278/6,220 baseline is instead a logical audit count that adds one implicit, undocumented input for every omitted `inputs` property: 7,273 + 5 = 7,278 and 6,215 + 5 = 6,220. Both metrics are retained: the physical count is the raw declaration truth; the logical count matches the existing baseline and exposes the five legacy missing-input-shape defects. It is not a loader-inferred runtime count.

The reproducible source hash command is:

```powershell
$root='C:\\Users\\redxa\\ReProjects\\ReSync\\src\\main\\resources\\nodes'; $files=Get-ChildItem -LiteralPath $root -Recurse -File -Filter *.json | Where-Object { -not $_.Name.StartsWith('_') }; $records=$files | Sort-Object FullName | ForEach-Object { $relative=$_.FullName.Substring($root.Length+1).Replace('\\','/'); $hash=(Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant(); "$hash  $relative" }; [Convert]::ToHexString([System.Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes(($records -join "`n") + "`n"))).ToLowerInvariant()
```

For counts, parse each eligible file with `ConvertFrom-Json`; treat an object root as one definition and an array root as its elements; add `inputs.Count` and `outputs.Count` only when that property exists and its value is non-null. The logical baseline additionally increments pin and missing-description totals for each definition whose `inputs` property is absent.

There are 150 raw input names ending in `id`/`ids`, including 132 typed as raw `string`; 123 of the latter lack an option source. This is a conservative raw-name indicator, not the plan’s authoritative `155 raw identity inputs`, because the existing plan does not specify the predicate. Selector semantics are also loader-modified: a `server:resync:*` source may become a specialized ID type at runtime. Raw JSON has neither a server scope nor a typed server/type/ID locator field.

Raw data is intentionally separated from inferred runtime data. The loader manufactures/normalizes the fields listed above; its post-load description/tag/safety/availability/selector values must not be counted as explicit catalog coverage.

## Local Live Inventory

[live-source-inventory.json](C:\\Users\\redxa\\ReProjects\\ReSync\\docs\\node-replacement\\reviewer-a\\worker-2\\live-source-inventory.json) records only structural facts. The supplied live root has 10 entries; no data-folder `nodes` directory or local node JSON exists, and its `extensions` directory exists but has zero extension jars. Thus this instance currently adds neither local JSON definitions nor jar-provided catalog contributions. It still requires a sanitized pre-migration catalog/extension inventory because both sources are live discovery paths. Do not copy live resource, graph, player, or credential values; capture hashes, versions, presence/counts, and approved sanitized fixtures instead.

## Existing Problems Addressed And Proposed New IDs

This evidence strengthens existing NSR-003 (loader inference/transforms), NSR-004 (active migrated tree), NSR-005 (mutable/per-definition registry), NSR-006 (live default resolution), NSR-011 (raw documentation deficit), NSR-012 (redirect/compatibility catalog), NSR-013 (Function name-derived pins), and NSR-016 (extension partial activation/unload).

Proposed ledger additions; do not add them to the shared ledger until Reviewer A accepts the evidence:

| Proposed ID | Evidence | Impact / violated invariant | Target outcome / retirement proof |
|---|---|---|---|
| A2-P01 | `NodeDefinitionRegistry.register` overwrites an existing node across owners; `registerAll` and `validateAndRegister` are incremental. | Collision result depends on discovery/order and a failed contribution can leave partial live state. | Contribution-level collision detection and validate-then-swap; rejected contribution leaves generation/checksum unchanged. |
| A2-P02 | `CustomFunctionNodeDefinitions.rebuild` unregisters all generated nodes then rebuilds pins from Function parameter names. | Rebuild has a temporary empty contribution and rename/reorder changes persisted pin identity. | Generated Functions contribute atomically with immutable parameter IDs; rebuild never creates a partial active catalog. |
| A2-P03 | Extension `registerNode`/`registerNodes` bypass the loader validator and `cleanupState` unloads mutable registries sequentially. | Invalid extensions can expose definitions before complete validation; unload can race handlers/execution and no lossless opaque guarantee is evidenced. | Atomic namespaced contribution and declared provider drain/cancel/unavailability behavior; unload/reinstall fixture preserves bytes/wires. |
| A2-P04 | Five definitions omit `inputs`; plan metrics count each as one logical undocumented input, while raw physical entries do not. | Without separate physical and logical predicates, future audits can reproduce the same apparent delta. | Keep both source-hashed metrics and specify which gate uses each. |

## Required Pre-Implementation Fields

| Field | Gate 0A result |
|---|---|
| Current behavior and defects | Bundled/local/extension/generated definitions merge into one mutable ID map. Loader fills missing semantics. Extensions register components individually and unload them sequentially. Function rebuilding unregisters then re-adds generated nodes. |
| Proposed classification | Bundled migrated JSON: Migration-only then Delete; bundled non-migrated JSON: Replace/Reorganize; loader and mutable registry: Replace/Delete; generated Function source: Adapt into one catalog source; local definitions and extensions: Adapt as validated namespaced contributions; aliases/redirects: Migration-only then Delete. |
| Replacement goal | One deterministic, provenance-aware catalog snapshot over bundled, local, generated, and extension sources; invalid/colliding source changes nothing active. This is a goal trace, not an implementation design. |
| Invariants | One active catalog; stable namespaced owner/node/pin/Function parameter identities; raw explicit documentation; no registration-order collision result; atomic activation/unload; missing provider data remains opaque/lossless; runtime uses snapshot-bound definitions/defaults; normal startup never loads migration-only tree. |
| Affected neighbors | `FlowRuntimeModule`, `FlowModule`, `FlowNodeRegistryPacketHandler`, `FlowExecutor`, `FlowRuntime`, `FlowStorage`, `FlowGraphMigrator`, `TypedAutomationGraphMigrator`, handler/property/type/option/resource/validator registries, `ReSyncServer`, protocol snapshot DTOs. |
| Shared contracts consumed | Current product-local `NodeDefinition`, `NodeDefinition.PinDefinition`, `NodePluginPayload`, `NodeRegistrySnapshot`, `FlowTypeRef`, `FlowDataType`, `FlowGraph.FunctionParameter`, `ReSyncExtension`, `ReSyncExtensionContext`, and `ReSyncResourceKey`-adjacent storage contracts. Gate 0B ownership is unfrozen; no contract was changed. |
| Client impact | `FlowNodeRegistryPacketHandler.buildSnapshot` at `...\\modules\\flow\\FlowNodeRegistryPacketHandler.java:109` packages registry by plugin and removes missing plugin payloads. Full snapshots are broadcast after Function refresh/unload. No evidence proves client opaque preservation, capability fallback, or stable catalog generation semantics. |
| Persistence impact | Local `<data>/nodes` is discovered if present; generated Functions derive from persisted function graphs; live storage migrations run during startup. No source values were inspected. Replacement migration must inventory/sanitize local node JSON, extension jars/versions, Function graphs, aliases, and references before conversion. |
| Migration impact | `nodes/migrated` currently stays active; loader transforms and `IdCompatibilityLayer` remain live. Every canonical ID/replacement/alias and generated Function pin-name mapping needs an explicit migration/quarantine decision. |
| Compatibility impact | Current snapshots use product-local contract versions/checksums and extension plugin payloads. Ordinary extension descriptors presently rely on client parsing mirrored DTOs; no proof establishes data-only compatibility or read-only fallback. |
| Fixtures required | FX-004 raw bundled inventory plus source hash; FX-005 extension atomic load/unload/reinstall; FX-006 generated Functions with parameter rename/reorder; FX-008 invalid/colliding extension; FX-012 unknown node/type/widget preservation; sanitized live-source inventory fixture with no values. |
| Exact future files implicated, not owned now | `...\\flow\\registry\\NodeDefinitionLoader.java`; `NodeDefinitionRegistry.java`; `CustomFunctionNodeDefinitions.java`; `FlowRuntimeModule.java`; `FlowModule.java`; `FlowNodeRegistryPacketHandler.java`; `ReSyncExtensionManager.java`; `ReSyncExtensionContext.java`; `FlowRuntime.java`; `FlowExecutor.java`; `FlowStorage.java`; `FlowGraphMigrator.java`; `TypedAutomationGraphMigrator.java`; `IdCompatibilityLayer.java`; `src/main/resources/nodes/**`; data-folder `nodes/**`; and `<plugin-data>/extensions/**`. |
| Unresolved risks | Loader behavior may differ in packaged jar/classloader ordering; no running-server registry dump was taken; no extension jar is currently installed; the raw/logical count predicate is now reconciled but must be adopted consistently by the shared baseline; no full opaque-node, in-flight unload, or client cache trace is proven in this slice. |

## Gate 0A Decision

Do not begin replacement implementation from this slice. The bundled raw/logical metric predicates and source-tree hash are resolved. Gate 0A still requires sanitized fixture capture, complete client/protocol and durable storage traces, and reviewer approval/allocation of the proposed ledger items.

## FX-013 Sanitized Full-Folder Fixture

ADR-007 authorizes `C:\\Users\\redxa\\ReProjects\\ReSync\\run\\plugins\\ReSync` as a read-only real-folder source. The resulting fixture is [full-folder](C:\\Users\\redxa\\ReProjects\\ReSync\\src\\test\\resources\\fixtures\\node-replacement\\full-folder). The source was never modified.

`sanitized-real/` is the deterministic ten-file structural copy. It records every source relative path, size, SHA-256, corresponding sanitized relative path, and retained root/top-level JSON shape or configuration key count in [fixture-manifest.json](C:\\Users\\redxa\\ReProjects\\ReSync\\src\\test\\resources\\fixtures\\node-replacement\\full-folder\\fixture-manifest.json); it replaces all live values and dynamic names with fixed fixture values. The manifest distinguishes the source’s empty extension/local-catalog state from the deterministic supplement and is pinned by [manifest.sha256](C:\\Users\\redxa\\ReProjects\\ReSync\\src\\test\\resources\\fixtures\\node-replacement\\full-folder\\manifest.sha256). No source value is reproduced in this report or fixture manifest.

`populated/` supplements the sparse source with stable coverage for config, graph/function/command assets, project metadata, revisions, mutation IDs, tombstones, custom-content resource state, triggers, extension manifest/state, a local node definition, WorldGen, world management, network state, automation state, migration journal, quarantine, and structured diagnostics. Each participant row has a stable owner/path/size/tree hash/format/current-head build/catalog/extension state/expected result. Tree hashes use the manifest-declared canonical record algorithm and are suitable for B/C to cite independently of their client/runtime fixture fields.

Current-head-only compatibility is explicit: ReSync `b2941e66d762ffb76391715fbf4487c068fe12ea`, Remotely `76c4eb8989aaf856a4f002fa7fe6e56a67c373df`, Java 21, and catalog source hash `c83f2e2828ee599c18563beaab2a131a160acceeb5b7ae8220064124188ff0e8`.

Scoped proof source: [FullFolderFixtureSanitizationTest.java](C:\\Users\\redxa\\ReProjects\\ReSync\\src\\test\\java\\restudio\\resync\\replacement\\evidence\\fixture\\sanitize\\FullFolderFixtureSanitizationTest.java). It verifies `manifest.sha256`, source file count/total bytes/source hashes, sanitized-file count/path/size/hash, retained shape fields, every participant path/size/tree hash, absence of any shared JSON string value of four or more characters, and common credential/URL markers across the fixture. It was intentionally not executed in this worker because Reviewer A directed serial focused execution after all workers finish, avoiding the shared Gradle lock; no source or external state was mutated.

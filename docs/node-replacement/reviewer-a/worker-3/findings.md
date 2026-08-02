# Worker 3 — Migration, Persistence, Snapshot And Restore Trace

Status: Gate 0A evidence only. Inspected read-only; no production source, tests, Gradle tasks, or persisted values were changed/read for disclosure.

## Scope And Existing Ledger IDs

This trace owns live and startup migration, legacy/alias/compatibility transforms, graph/resource persistence roots, triggers, tombstones, network/generated assets, backup/snapshot/restore, and direct-upgrade history. It substantiates existing problems **NSR-004**, **NSR-007**, **NSR-009**, **NSR-012**, **NSR-013**, **NSR-015**, **NSR-016**, and **NSR-018**. It proposes provisional **A3-P01** through **A3-P05** below; the shared ledger was deliberately not edited and must assign stable IDs after cross-review reconciliation.

## Current Architecture Traced

### Startup, legacy discovery, and repair

`FlowRuntimeModule` performs `storage.preloadAll()`, rebuilds generated Function definitions, then invokes `new FlowGraphMigrator(...).migrateStoredFlows()` and `new TypedAutomationGraphMigrator(...).migrateStoredFlows()` at [FlowRuntimeModule.java](C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/modules/FlowRuntimeModule.java:301) through line 305. This makes both graph migrations normal startup behavior.

Earlier, the `FlowStorage(File)` constructor establishes `flows`, `guis`, `scoreboards`, `tabs`, `project-metadata`, `assets`, and `config.properties`, initializes `AssetTransactionManager`, reads legacy default configuration, calls `cleanupBelowNameData()`, and calls `migrateLegacyAssets()` at [FlowStorage.java](C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/flow/FlowStorage.java:80) through line 103. This is a second, independent startup repair/migration path.

`migrateLegacyAssets()` reads asset or legacy project metadata, derives command graph IDs from `triggers.json` and graph contents, adds default folders, reconciles active asset files, reclassifies commands, imports legacy flow/gui/scoreboard/tab/custom-content/worldgen roots, syncs copies into `assets`, prunes metadata for absent assets, then removes legacy roots ([FlowStorage.java](C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/flow/FlowStorage.java:1539)). Command classification depends on `triggers.json` bindings and Command Start-node inspection ([FlowStorage.java](C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/flow/FlowStorage.java:1948)). Asset reconciliation infers functions from `FlowGraph.isFunction()`, commands from detected start nodes, rewrites/moves files, and quarantines duplicate copies ([FlowStorage.java](C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/flow/FlowStorage.java:1573)).

The loader recursively registers the non-underscore JSON definitions in `src/main/resources/nodes/migrated/**` as active nodes. It excludes underscore-prefixed files, so `_id_migration_map.json` is not a node-definition source. `IdCompatibilityLayer.loadMigrationMap()` independently reads that excluded resource and supplies bidirectional old/new node aliases ([IdCompatibilityLayer.java](C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/flow/migration/IdCompatibilityLayer.java:18)). Both live surfaces agree with NSR-004/007/012 rather than a controlled offline-only boundary.

### Graph migration and per-save repair

`FlowGraphMigrator.migrateStoredFlows()` locks only `assets/.durability/migration.lock`, iterates stored flows, creates an item ledger record, copies one graph backup, mutates/saves that graph, and commits/fails its one-resource ledger entry ([FlowGraphMigrator.java](C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/flow/migration/FlowGraphMigrator.java:72)). It maps node IDs, inserts current handler configuration defaults, transforms node values, sets versions, and rewires pin connections ([FlowGraphMigrator.java](C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/flow/migration/FlowGraphMigrator.java:145)). The save packet handler also calls `migrateGraph(graph)` before validation and persistence, at [FlowBlueprintPacketHandler.java](C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/modules/flow/FlowBlueprintPacketHandler.java:132): live save-time compatibility conversion.

`TypedAutomationGraphMigrator.migrateStoredFlows()` is separately called at startup. It converts legacy variable and schedule/cancel-task graph nodes, derives/creates typed JSON resources, rewires pins, then saves resources and graphs. Its resource rollback only removes resources it created in that invocation ([TypedAutomationGraphMigrator.java](C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/flow/migration/TypedAutomationGraphMigrator.java:50), [TypedAutomationGraphMigrator.java](C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/flow/migration/TypedAutomationGraphMigrator.java:298), [TypedAutomationGraphMigrator.java](C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/flow/migration/TypedAutomationGraphMigrator.java:368)). It is not a cross-participant transaction or whole-system plan.

`MigrationLedger` records only `PREPARED`, `COMMITTED`, or `FAILED` per migration/resource/source hash in `assets/.durability/migrations.json`; its fence is a single file lock below the asset root ([MigrationLedger.java](C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/storage/MigrationLedger.java:25), [MigrationLedger.java](C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/storage/MigrationLedger.java:73)). It has no participant registration, drain, deterministic dry-run output, staged activation, quarantine acceptance, or rollback state machine.

### Active graph/resource persistence

`FlowStorage.saveGraph` validates, preserves/advances graph revision and mutation ID, writes the graph plus `assets/project.json` in one `AssetTransactionManager` call, clears that graph tombstone, deletes duplicate graph copies, and emits an ID-only change callback ([FlowStorage.java](C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/flow/FlowStorage.java:220)). It guards cross-type collisions before finding a write path, but cache and notification use ID-only forms (`Map<String, FlowGraph>` and `Consumer<String>`), despite storage files carrying type/revision/mutation fields. `deleteGraphFiles` writes `assets/.tombstones/{type}/{id}.json`, deletes resource files/metadata, and emits the same ID-only callback ([FlowStorage.java](C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/flow/FlowStorage.java:413)). Tombstones block an older asset revision when read ([FlowStorage.java](C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/flow/FlowStorage.java:1464)).

The asset transaction system is a useful local durability primitive: `commit` stages changed asset files in `assets/.transactions/{id}`, snapshots overwritten targets under `assets/.snapshots/{id}`, applies each entry, and marks a transaction committed; recovery re-applies prepared journals ([AssetTransactionManager.java](C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/storage/AssetTransactionManager.java:40), [AssetTransactionManager.java](C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/storage/AssetTransactionManager.java:110)). Its `restore` recreates only the targets listed by that one asset transaction ([AssetTransactionManager.java](C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/storage/AssetTransactionManager.java:149)); `FlowStorage.restoreAssetSnapshot` merely invokes it and clears storage caches ([FlowStorage.java](C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/flow/FlowStorage.java:157)). This is rollback, not a discoverable complete ReSync-folder snapshot/restore facility.

### Current persistence roots and participant inventory

The live data-root inventory was enumerated without reading file values. Current `run/plugins/ReSync` contains `assets/project.json`; `config.properties`; `config.yml`; `diagnostics/*.json`; empty `extensions/`; empty `player-dossiers/`; empty `structures/`; and `world-management/{worlds,portals,inventory-groups,sign-portals,player-states}.json`.

| Root / participant candidate | Current owner(s) | Snapshot/restore implication |
|---|---|---|
| `assets/**`, `assets/project.json`, `.transactions`, `.snapshots`, `.quarantine`, `.durability`, `.tombstones`, `migration-backups` | `FlowStorage`; `AssetTransactionManager`; `AssetIntegrityService`; `MigrationLedger` | Only asset-local transactions and target-level rollback exist. Must be included with journals/tombstones/quarantine reports, but no complete manifest currently exists. |
| legacy data-root `flows`, `guis`, `scoreboards`, `tabs`, `project-metadata`, `custom-content`, `worldgen-projects`, resource-family JSON folders, `triggers.json`, legacy config files | `FlowStorage.migrateLegacyAssets`, `syncAssetsFromProjectMetadata`, `loadCommandFlowIds`, default config methods | Active migration inputs and cleanup targets. A direct upgrader needs an explicit source-layout inventory and must snapshot them before conversion; current startup can mutate/delete them. |
| `world-management/*.json` | `WorldStateStorage` constructor assigns all five exact paths at [WorldStateStorage.java](C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/world/WorldStateStorage.java:41) | Persisted world, portal, inventory group, sign portal, and player state data are outside `assets`; no observed registration in a coordinated snapshot service. |
| `structures/*.resync-structure` | `StructureLibrary` resolves this data-root path and loads/writes it ([StructureLibrary.java](C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/structure/StructureLibrary.java:28), [StructureLibrary.java](C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/structure/StructureLibrary.java:92)) | Required generated/recovery asset participant, absent from asset-only restore. |
| `extensions/` and extension data/manifests | `ReSyncServer` creates `ReSyncExtensionManager` with this root ([ReSyncServer.java](C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/server/ReSyncServer.java:157)) | Empty now, but installed artifacts, versions, and extension-owned data are migration/snapshot participants by plan. No coordinated manifest evidence found. |
| `diagnostics/*.json` | `ReSyncCommand` exports programmability acceptance snapshots here ([ReSyncCommand.java](C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/commands/ReSyncCommand.java:1371)) | Durable diagnostic evidence is present but no snapshot inclusion/retention policy was traced. |
| `config.properties`, `config.yml`, `player-dossiers/` | Data-root files/dir observed; `FlowStorage` reads/writes only `config.properties` ([FlowStorage.java](C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/flow/FlowStorage.java:1057)) | `config.yml` and player dossiers have no complete participant owner/restore trace in this audit; treat as evidence gaps, not non-persisted. |
| Network synchronization state | `NetworkResourceManifestStore`, `NetworkResourceSynchronizer`, `NetworkSnapshot*` contracts in ReSyncCore | Network snapshot contracts exist, but no evidence of registration in a complete ReSync-folder snapshot service; must not be mistaken for restore coverage. |

## Current Behavior And Defects

- Startup writes/deletes/reclassifies persistence state through constructor migration, two startup migrators, and cache/index repair. This violates the required offline, versioned, once-only conversion boundary (NSR-007, NSR-012).
- Legacy aliases, migrated JSON, loader transforms, migration maps, and save-time migration can all alter interpretation of a persisted graph, while legacy-shaped IDs not present in `_id_migration_map.json` remain unchanged (the `loop_while` fixture is one proven example). Pin repair is selective: the fixture proves `completed` becomes `done`, while the unrelated `next` source pin remains unchanged. This violates explicit version-edge and immutable persisted-meaning requirements (NSR-003/004/006/012/018).
- The fence and backups cover only asset resources, not writer draining or the complete data root. One graph can commit while unrelated participants remain mutable (NSR-015).
- Local asset rollback has journals and hashes but no system manifest, build/catalog compatibility preflight, current-state snapshot before restore, staging of all participants, atomic folder swap, or restore journal state model (NSR-015).
- Metadata reconciliation derives type from trigger bindings, folder/filename, `isFunction`, and Command Start nodes; it can rewrite, move, or quarantine live assets. This conflicts with typed-authority rules (NSR-007/009/012).
- Flow cache/change API identity is ID-only after a type-aware file write, leaving collision/convergence risk (NSR-009).
- No repository evidence identifies supported direct-upgrade release bounds, a published bridge/offline upgrader, or restore-compatible build ranges. The reachable history records incremental persistence migrations (notably `d763b83`, `2d74979`, `9fd793b`, `0b196b9`, `2a5d7ac`) but does not establish the required supported source-release matrix.

## Proposed Classification And Replacement Goal

Classification only, not an implementation design:

| Surface | Classification | Replacement Goal |
|---|---|---|
| `FlowGraphMigrator`, `TypedAutomationGraphMigrator`, `IdCompatibilityLayer`, loader ID transforms, `nodes/migrated/**`, startup asset migration and save-time migration | Migration-only, then Delete from runtime | One explicit administrative/offline conversion path with a supported direct-upgrade record. |
| Asset transaction, integrity, typed files, typed graph tombstones | Retain/Adapt | Use as a proven low-level participant primitive only under a coordinated snapshot/migration authority. |
| `FlowStorage` typed graph persistence | Retain/Adapt | Preserve typed/revision/tombstone durability while removing legacy inference/reconciliation authority. |
| Per-graph backup and asset transaction restore | Replace | Complete participant snapshot/manifest and atomic whole-folder restore. |
| World/network/structure/extension/config/diagnostic roots | Review, then participant or explicit exclusion | Every retained persisted root must have an owner, flush rule, manifest status, and restore semantics. |

## Required Invariants

- Every migrated, stored, cached, tombstoned, networked, and restored resource is identified by type plus ID (and server scope where applicable), never inferred by folder, node shape, ID-only map, or trigger lookup.
- A normal runtime start/load/save/execution path has no migration, legacy discovery, compatibility alias, or repair side effect.
- A successful migration or restore is a verified, recoverable, whole-participant activation; interruption never exposes mixed generations.
- The source snapshot precedes all migration writes; manifests cover relative paths, hashes, size, owner, format/build/catalog/extension state and verification result.
- Ambiguous/unresolved input is reported and quarantined; it is not silently normalized, deleted, or recreated from metadata.
- Restore first protects current state, validates compatibility before mutation, fences all writers, and leaves current state active after failure.

## Affected Neighbors And Shared Contracts Consumed

Neighbors: `FlowRuntimeModule`, `FlowBlueprintPacketHandler`, `FlowStorage`, `ReSyncJsonResourceStorage`, `CustomFunctionNodeDefinitions`, triggers/command binding, `ReSyncResourceCatalog`, `AssetFileFormat`, `AssetTransactionManager`, `MigrationLedger`, world-management storage, network manifest/synchronizer, extension manager, StructureLibrary, ReSyncCore network snapshot records, and Remotely flow/resource packet/cache paths.

No Gate 0B shared contract is frozen. Later work consumes the ledger's pending `identity/**`, `graph/**`, `migration/**`, `snapshot/**`, `protocol/**`, `catalog/**`, and `diagnostic/**` contracts. This audit does not authorize a contract change.

## Client, Persistence, Migration, And Compatibility Impact

Client impact: present graph-save packets invoke migration before server validation; client graph/cache behavior therefore cannot assume a save is semantically neutral. The future migration boundary must return durable, typed, versioned outcomes and diagnostics rather than mutate accepted client data by alias.

Persistence impact: all listed roots, asset internals, graph metadata, trigger bindings, typed resources, tombstones, revisions/mutation IDs, generated Functions, network state, extension artifacts/data, WorldGen/structures, and runtime automation state require an explicit participant decision. Current evidence proves only partial asset transaction recovery.

Migration and compatibility impact: direct source release bounds, old layouts, retained bridge/upgrader ownership, manifest contract/build checks, and compatibility rules are open. No runtime compatibility path may remain after its controlled conversion window.

## Fixtures Required

Existing: FX-001 recipe schema, FX-002 command graph/trigger, FX-003 cross-resource manifest fixtures, FX-005 request extension, and current migration tests/resources. Required before implementation: FX-006 generated Function graphs; FX-007 typed same-ID collision; FX-008 broken/colliding extension; FX-010 every migration/restore interruption state; FX-011 sanitized complete ReSync-folder snapshot; FX-012 unknown/older-client preservation; plus sanitized snapshots that include the observed world-management five-file set, structure root, configs, diagnostics policy, empty/non-empty extension root, tombstones, transaction journals/snapshots, and network state.

## Exact Future Files Implicated (Not Owned Now)

- `C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/flow/FlowStorage.java`
- `C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/flow/migration/FlowGraphMigrator.java`
- `C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/flow/migration/TypedAutomationGraphMigrator.java`
- `C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/flow/migration/IdCompatibilityLayer.java`
- `C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/storage/AssetTransactionManager.java`
- `C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/storage/MigrationLedger.java`
- `C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/modules/FlowRuntimeModule.java`
- `C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/modules/flow/FlowBlueprintPacketHandler.java`
- `C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/world/WorldStateStorage.java`
- `C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/network/paper/NetworkResourceManifestStore.java`
- `C:/Users/redxa/ReProjects/ReSync/src/main/java/restudio/resync/network/paper/NetworkResourceSynchronizer.java`
- `C:/Users/redxa/ReProjects/ReSync/ReSyncCore/src/main/java/restudio/resync/network/NetworkSnapshotRestore.java`
- `C:/Users/redxa/ReProjects/ReSync/src/main/resources/nodes/migrated/_id_migration_map.json` and `nodes/migrated/**`

## Provisional Problem Labels For Reviewer Reconciliation

| ID | Evidence | Impact / Target Outcome |
|---|---|---|
| A3-P01 | `FlowStorage` constructor calls `migrateLegacyAssets`; `FlowRuntimeModule` runs two migrators; `FlowBlueprintPacketHandler` migrates a submitted graph | Migration happens at startup and save. Retire all live conversion entry points after a fenced standalone conversion. |
| A3-P02 | `AssetTransactionManager.restore` restores only journal entries below `assets`; observed world-management/config/structure/extension/diagnostic roots are outside it | Restore is partial by construction. Replace with verified whole-participant snapshot and staged atomic restoration. |
| A3-P03 | `migrateLegacyAssets` derives graph type from `triggers.json`, start nodes, `isFunction`, filenames/folders and mutates metadata/files | Inference may reclassify/reconstruct persisted identity. Use declared typed source identity or quarantine ambiguity. |
| A3-P04 | `FlowStorage` graph cache and change listener are keyed/called by raw ID despite typed files/tombstones | Cross-type collision and event ambiguity remain in the storage projection. Use typed keys/events throughout. |
| A3-P05 | Git history shows incremental migration commits but no published source release matrix, offline upgrader, or recoverable build/contract restore policy | Direct-upgrade/restore support cannot be proven. Publish and test explicit supported release and compatibility bounds. |

## Unresolved Risks And Evidence Gaps

- No complete source trace yet proves every `config.yml`, `player-dossiers`, diagnostics, WorldGen runtime asset, external extension data, or network database/location participant and its flush semantics. Their observed presence is sufficient to block a claim of complete snapshot coverage.
- `NetworkSnapshot*` is a network-state model, not proof of folder-snapshot creation or restore activation. Its concrete persistence root and transaction integration remain untraced.
- The migration tests show component coverage but cannot demonstrate an actual full-folder snapshot/restore rehearsal; no tests were run for this Gate 0A audit.
- Exact legacy release support, historical file layouts, client protocol fallback during a bridge release, and production sanitized backup availability are not evidenced. FX-011 remains missing per the fixture manifest.
- The report does not trace client reconnect/revision conflict/extension unload end-to-end; those are reviewer-level required traces and adjacent-worker ownership, not evidence of absence in this slice.

## Reproducible Read-Only Commands Used

```powershell
Get-ChildItem -LiteralPath 'C:\Users\redxa\ReProjects\ReSync\docs\node-replacement' -Recurse -File
Get-Content -LiteralPath 'C:\Users\redxa\ReProjects\AGENTS.md','C:\Users\redxa\ReProjects\RESYNC_RULES.md','C:\Users\redxa\ReProjects\ReSync\plan.md' -Raw
rg -l -i --glob '*.java' --glob '*.kt' --glob '*.json' 'migrateLegacy|Migration|Compatibility|snapshot|restore|backup|tombstone|triggers\.json|FlowStorage' C:\Users\redxa\ReProjects\ReSync
rg -n -C 4 'migrateStoredFlows\(|new FlowGraphMigrator|new TypedAutomationGraphMigrator|restoreAssetSnapshot|previewAssetRestore' C:\Users\redxa\ReProjects\ReSync\src\main\java
Get-ChildItem -LiteralPath 'C:\Users\redxa\ReProjects\ReSync\run\plugins\ReSync' -Force -Recurse
git -C 'C:\Users\redxa\ReProjects\ReSync' log --all --oneline --decorate -80 -- src/main/java/restudio/resync/flow/migration src/main/java/restudio/resync/flow/FlowStorage.java src/main/java/restudio/resync/storage
```

Initial Gate 0A audit: no production files, tests, Gradle/build commands, or shared evidence files were modified. The ADR-007 through ADR-009 continuation below adds only owned current-behavior evidence fixtures and one scoped test; production implementation and shared evidence files remain untouched.

## ADR-007 Through ADR-009 Continuation

ADR-007 establishes the local data root as a read-only fixture source, ADR-008 fixes the supported legacy heads, and ADR-009 authorizes focused non-destructive verification. The current-root participant matrix is now complete to the evidence boundary in [persistence-participant-matrix.md](C:/Users/redxa/ReProjects/ReSync/docs/node-replacement/reviewer-a/worker-3/persistence-participant-matrix.md). It records an explicit owner/root/flush/current snapshot/current restore/manifest/atomicity row for every observed or plan-required ReSync root, with missing methods marked absent.

Current component golden fixtures now reside in `src/test/resources/fixtures/node-replacement/migration/`: `legacy-graph-alias-pins.json`, `typed-automation.json`, `persistence-participants.json`, and `current-journal-states.json`. `persistence-participants.json` has exactly the matrix’s sixteen ordered `A3-PP-01` through `A3-PP-16` boundaries, each with root, owner, flush, current snapshot, current restore, manifest, and whole-folder atomicity fields. Together they cover live aliases/pins, typed automation, project/triggers/tombstones participant expectations, asset transaction prepared/committed/recovery/restore, and all plan journal states as present current states or absence expectations. [PersistenceMigrationEvidenceTest.java](C:/Users/redxa/ReProjects/ReSync/src/test/java/restudio/resync/replacement/evidence/fixture/persistence/PersistenceMigrationEvidenceTest.java) exercises the existing component behavior using only temporary test roots; it does not introduce a replacement service.

Verification attempt: `gradlew.bat test --tests restudio.resync.replacement.evidence.fixture.persistence.PersistenceMigrationEvidenceTest` was attempted twice before the reviewer directed no broad retry. Both invocations ended after reporting `Waiting For Another ReStudio Build To Finish`; no test XML was produced. This is a shared transient Gradle lock, not a Gate blocker; Reviewer A will run the focused class serially.

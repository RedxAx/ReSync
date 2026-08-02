# Reviewer A Gate 0A Synthesis

Domain: contracts, catalog, and migration.

Decision: **Approve Gate 0A evidence for this domain.** The current system is traced with absences recorded as stable problems, the ADR-007 through ADR-009 evidence requirements are resolved, and the focused A+C evidence suite passes. This approval does not freeze contracts, approve Gate 0B, or authorize production replacement work. Production source and the accepted real-folder source remained read-only; only scoped test verification and test-owned output were executed.

## Gate 0A Closure Evidence

- FX-013 is the accepted, source-pinned fixture under `src/test/resources/fixtures/node-replacement/full-folder`. Its manifest records the ten real-source paths, hashes, sizes, retained structural shapes, sanitized counterparts, current-head compatibility, and fourteen deterministic populated participant groups. `manifest.sha256` pins the authoritative manifest; the proof test verifies every declared source, sanitized-file, and participant size/hash without mutating `run/plugins/ReSync`.
- The current persistence boundary is machine-readable in `src/test/resources/fixtures/node-replacement/migration/persistence-participants.json`: sixteen ordered participants A3-PP-01 through A3-PP-16 record owner, flush, snapshot, restore, manifest, and whole-folder atomicity. Component migration, tombstone, transaction recovery, ledger-state, and absence expectations are exercised against temporary roots.
- Compatibility is limited to ReSync `b2941e66d762ffb76391715fbf4487c068fe12ea` and Remotely `76c4eb8989aaf856a4f002fa7fe6e56a67c373df` under ADR-008. No broader upgrade or client window is inferred.
- The versioned catalog and protocol scanners pin 61 source files, 1,428 definitions, 7,273 physical pins, 7,278 logical compatibility pins, 6,215 physical missing pin descriptions, 6,220 logical missing pin descriptions, 1,237 missing node descriptions, five definitions without `inputs`, 21 seven-operation packet families, eight mirror pairs, catalog hash `c83f2e2828ee599c18563beaab2a131a160acceeb5b7ae8220064124188ff0e8`, and protocol hash `8fcc0f84538c4c9907c58e67a38670f51d4db3b992734e479e5ec50afc4ae527`.
- The current performance harness records five warmups and ten raw samples. On the accepted verification environment it measured catalog parse/load, graph deserialize/validate, fixture-copy snapshot/restore proxies, a deterministic registry projection proxy, byte counts, heap proxy, source hashes, and environment. Live Remotely hydration/editing, immutable compilation, live execution overhead, and coordinated migration/restore remain explicitly unavailable current-system measures with later acceptance methods; none is represented as zero.
- The consolidated root `:test` verification covered nine exact A+C classes. XML evidence under `build/test-results/test` records 29 tests, 29 passed, zero failures, zero errors, and zero skipped. The root-qualified rerun completed `BUILD SUCCESSFUL`; the performance report hash is `f71bdfe2d0a8cc48e3dca4105f3e64657803998e60f6959f4e78a33ff5521df0`.

## Reviewed Evidence

- `worker-1/findings.md`: dependency direction, mirrored contracts, serializers, schemas, protocol generators, packet routes, resource DTOs, and client hydration boundary.
- `worker-2/findings.md`, `raw-catalog-inventory.json`, and `live-source-inventory.json`: bundled/local/generated/extension discovery, loader and registry behavior, extension unload, raw catalog metrics, and live source presence.
- `worker-3/findings.md`: startup/save-time migration, aliases, persistence roots, local rollback, snapshot/restore gaps, and direct-upgrade gaps.
- `worker-4/findings.md`, `fixture-inventory.md`, and `acceptance-evidence-plan.md`: fixtures, local candidate hashes and sanitization, history compatibility, and performance evidence requirements.
- Reviewer B cross-domain evidence: recursive unknown-field preservation is incomplete; structural and Function identities remain name/position based; revision conflicts do not return authoritative recovery state.
- Reviewer C cross-domain confirmation: extension unload is sequential without execution-plan drain ownership; execution mutates graph nodes from live definitions and reads live defaults.

## Current Architecture Findings

### Contract and dependency direction

`ReSync/build.gradle.kts` reads `../Remotely/contracts/resync-protocol.json` and generates a server `ReSyncProtocolContract`; Remotely independently generates its own package-local class from the same Remotely-owned JSON. ReSyncCore is already a dependency-free included build and contains useful shared records such as `ReSyncResourceKey`, but it does not own the complete descriptor, graph, type/value, catalog, diagnostics, or protocol model. ReSync and Remotely retain mirrored `NodeDefinition`, registry snapshot/payload, graph/node/connection, type/reference, and serializer implementations.

The current resource protocol allocates seven CRUD bytes per resource type. Twenty-one resource families are present in the generated table. ReSync routes these through `FlowModule`, `FlowBlueprintPacketHandler`, `FlowResourcePacketRouter`, `FlowResourcePacketHandler`, `FlowPacketSender`, and `ReSyncResourceCatalog`; Remotely reverses the packet mapping through `ReSyncResourceType` and `ReSyncFlowClient`. This directly substantiates NSR-001, NSR-002, and NSR-008.

### Catalog sources and activation

`FlowRuntimeModule.initialize` loads bundled `nodes/**`, then data-folder `nodes/**`, into a mutable `NodeDefinitionRegistry`. Recursive discovery includes active JSON below `nodes/migrated/**`; underscore-prefixed `_id_migration_map.json` is excluded from definition registration and is consumed independently by `IdCompatibilityLayer`.

`NodeDefinitionLoader` transforms legacy pins and infers descriptions, tags, availability, safety, option sources, and specialized ID types. `validateAndRegister` and `NodeDefinitionRegistry.registerAll` mutate one definition at a time. Cross-owner collision overwrites the prior definition, and later unloading the winning owner does not restore the displaced definition.

Generated Functions are a separate live source. `CustomFunctionNodeDefinitions.rebuild` unregisters the entire `custom_functions` owner, scans persisted Function graphs, sorts parameters by display name, derives pins from those names, and registers the replacement batch incrementally.

Extensions are scanned as top-level jars below the data-folder `extensions` directory. `ReSyncExtensionManager` invokes extension initialization/start and lets the extension independently register definitions, handlers, types/codecs, conversions, option catalogs, resources, validators, modules, listeners, and providers. Cleanup reverses those registries sequentially. `HandlerRegistry.register` replaces and shuts down the previous handler immediately. There is no contribution-wide validation, immutable generation swap, or in-flight execution ownership/drain. This substantiates NSR-003 through NSR-005, NSR-011 through NSR-013, and NSR-016.

### Raw catalog baseline

The source-hashed raw inventory covers 61 eligible JSON files and 1,428 definitions. The eligible tree hash is `c83f2e2828ee599c18563beaab2a131a160acceeb5b7ae8220064124188ff0e8` using the algorithm recorded in `worker-2/raw-catalog-inventory.json`.

Two pin metrics must remain distinct:

| Metric | Value | Meaning |
|---|---:|---|
| Physical declared pin entries | 7,273 | 3,584 actual input objects plus 3,689 actual output objects in raw JSON. |
| Missing descriptions on physical entries | 6,215 | Raw physical pin objects with no explicit description. |
| Published logical baseline pins | 7,278 | Physical entries plus one logical undocumented input for each of five definitions that omit `inputs`. |
| Published logical missing descriptions | 6,220 | Physical missing descriptions plus those five logical inputs. |

The five definitions are `custom_content.current`, `event.custom_content`, `logic.logic_true`, `logic.logic_false`, and `map.create`. `NodeDefinitionLoader` creates no runtime input for them. The arithmetic difference is therefore fully localized, but the original plan does not preserve the scanner that chose the logical convention. All later source audits must use the physical count as raw declaration truth and report the logical compatibility count separately.

Other reproduced values match the evidence pack: 999 visible, 429 hidden, 43 deprecated, 226 canonical redirects, 723 defaulted pins, 157 optional pins, 281 conditional inputs, 1,237 missing node descriptions, zero explicit domains/lifecycle states/inspector intents, and 142 explicit schema versions. Sixty files and 1,420 definitions are below `nodes/migrated`; only `automation.json` supplies eight non-migrated bundled definitions.

### Graph persistence, execution, and conflicts

`FlowStorage.saveGraph` provides useful typed resource/revision/mutation metadata, a graph-plus-project-metadata asset transaction, typed tombstones, and collision checks. However, caches and change callbacks still include ID-only paths. The graph and client models remain mirrored.

`FlowExecutor.executeValidated` traverses a mutable `FlowGraph`. `resolveHandler` maps legacy IDs, then mutates `FlowNode.type` and `handlerConfig` from the live definition. `FlowRuntime.resolveDefinitionDefault` resolves current definition defaults by pin name during execution. This proves NSR-006 and reinforces NSR-012/013.

For Flow saves, `FlowBlueprintPacketHandler.handleSave` deserializes the client graph, performs live migration before validation, then calls `FlowStorage.saveGraph`. A `ResourceRevisionConflictException` contains expected/current revision internally, but the handler returns only a job failure telling the user to reload. Generic Function/Command resource handling emits an `EditorError`, but also omits authoritative current revision and payload. The complete recoverable conflict contract is absent.

### Migration and restore

Legacy conversion is reachable from normal runtime in several places:

- `FlowStorage` constructor calls `migrateLegacyAssets`.
- `FlowRuntimeModule.initialize` runs `FlowGraphMigrator.migrateStoredFlows` and `TypedAutomationGraphMigrator.migrateStoredFlows`.
- `FlowBlueprintPacketHandler.handleSave` calls `FlowGraphMigrator.migrateGraph` on submitted graphs.
- `IdCompatibilityLayer` maps old/new IDs at runtime.
- Active definitions remain below `nodes/migrated/**`.

`MigrationLedger`, graph backups, `AssetTransactionManager`, integrity checks, typed files, and tombstones are useful component primitives. They do not form the plan-required system-wide fence, registered participant flush, deterministic dry run, content-addressed plan, complete manifest, staged whole-folder activation, full journal state machine, or atomic whole-folder restore.

Observed/current participant candidates include `assets/**` and its transactions/snapshots/quarantine/durability/tombstones; legacy resource roots; `triggers.json`; world-management files; structures; extension artifacts/data; configs; diagnostics; player dossiers; WorldGen; runtime automation state; and network state. No complete participant registry or restore path covers them. The existing `NetworkSnapshot*` models are network-domain state, not proof of complete ReSync-folder snapshot/restore. This substantiates NSR-007, NSR-009, NSR-012, NSR-015, and NSR-018.

## Local Evidence and Compatibility

`run/plugins/ReSync` contains 10 files totaling 1,982,595 bytes. Worker 4 recorded exact hashes and shape-only observations for `assets/project.json`, two large programmability diagnostics, configs, and world-management files. The extension directory contains zero jars and the live root contributes no local `nodes` directory. This candidate may contain server, path, host, credential, player, world, or operational identity and was not copied. It is not FX-011 and cannot be treated as a complete sanitized snapshot.

ReSync history has release-message commits through 1.3.0 but no tags. Remotely has only the `BETA` tag. `origin/node-system-v2` and `upstream/node-system-v2` are April 28 ancestors of the current branches, not published upgrade sources. No evidence establishes supported legacy release bounds, offline upgrader/bridge ownership, old-client fallback bounds, or restore-compatible build/contract ranges.

All catalog compilation, payload/hydration, graph validation/compilation, execution overhead, migration, coordinated snapshot/restore, memory, and interactive editing baselines remain unmeasured. Unknown measurements are not zero.

## Problem Evidence

Reviewer A directly strengthens existing NSR-001 through NSR-018. The most consequential unresolved items are:

- NSR-001/002/008/018: client-owned generation, mirrored models, per-resource protocol routes, and no formal single schema/canonicalizer/defaulting owner.
- NSR-003/004/005/011/012: inferred raw behavior, live migrated definitions, mutable/order-dependent registration, and incomplete raw documentation.
- NSR-006/013: execution and generated Functions depend on mutable live definitions, names, and positions.
- NSR-007/015: live migration and partial asset rollback exist without a complete fenced migration/snapshot/restore transaction.
- NSR-009/014/017: identity, diagnostic, conflict recovery, ordering, capability, and recursive unknown-data behavior are incomplete across client/server boundaries.
- NSR-016: extension activation/unload is sequential and has no execution drain or proven opaque round trip.

Worker-local proposals are evidence labels only; stable allocation belongs to the orchestrator. Consolidated provisional findings are:

| ID | Finding | Existing overlap |
|---|---|---|
| A-P01 | Physical and logical catalog pin predicates differ; both are now source-hashed and explicit. | A2-P04; baseline evidence quality. |
| A-P02 | Generated Function rebuild is non-atomic and parameter identity is name-derived. | A2-P02, A4-P01; NSR-005/013. |
| A-P03 | Client-facing revision conflicts omit authoritative current revision and payload. | NSR-014/017 and resource mutation rules. |
| A-P04 | Complete snapshot participant ownership and full restore are absent. | A3-P02; NSR-015. |
| A-P05 | Supported direct-upgrade and restore compatibility bounds are unproven. | A3-P05, A4-P03/A4-P05; compatibility matrix gap. |
| A-P06 | Required performance baselines are unmeasured. | A4-P04; baseline metric gap. |
| A-P07 | Recursive unknown-field preservation is incomplete beyond graph-root data. | Reviewer B; NSR-017/018. |

## Approval Boundary

ADR-007 accepts FX-013 as the available real-folder evidence plus a deterministic populated supplement; it does not claim that the sparse local folder is a production-complete migration rehearsal. The sixteen-participant matrix closes current owner/flush/snapshot/restore evidence by recording concrete behavior or absence. ADR-008 fixes the only supported legacy heads. Reviewer B's approved evidence supplies recursive unknown-data, client registry, same-ID, missing-provider, reconnect, and export baselines; Reviewer C's passing matrices supply definition/pin semantics, runtime mutation, lifecycle, cancellation, automation, network, and performance evidence.

The remaining NSR findings are target requirements and later-gate acceptance obligations, not missing Gate 0A current-system evidence. Gate 0B contracts remain Draft and unapproved. Production replacement implementation must not begin until the orchestrator separately approves Gate 0B.

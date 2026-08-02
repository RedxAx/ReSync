# Reviewer A Current-System Trace Signature

Status: **Trace coverage signed with recorded absences; Gate 0A domain evidence approved. Gate 0B unapproved.**

Date: 2026-08-02.

This signature means the current paths below can be followed to concrete classes/methods, and missing complete paths are recorded as problems. It does not approve target contracts or implementation.

## 1. Add an extension node

`ReSyncServer` constructs `ReSyncExtensionManager` with the data-folder `extensions` root and calls `loadInitialExtensions`. `ReSyncExtensionManager.scanDirectory` finds top-level jars; `loadJar` uses `ServiceLoader<ReSyncExtension>`; `registerExtension` inserts `ExtensionState` and invokes `initializeExtension`. `ExtensionFlowRegistration.registerNode` namespace-checks the definition, assigns plugin ownership, calls `NodeDefinitionRegistry.register`, and records the ID. `registerHandler`, type/codec, conversion, option, resource, validator, module, listener, and provider routes mutate their own registries separately. `refreshNodeRegistry` rebuilds generated Function definitions and broadcasts the registry.

Evidence: `ReSyncServer.java:157-159`; `ReSyncExtensionManager.java:315-371,160-193,582-758`; `NodeDefinitionRegistry.java:33-57`; `FlowModule.java:362-365`.

Known problem: no contribution-wide validation or atomic activation; collision may displace an existing owner before later failure (NSR-005/016).

## 2. Unload an extension node

Missing/modified jars call `unloadJar`; disabled Bukkit owners and explicit handles call `unregister`. `unregister` stops the extension, then `cleanupState` sequentially removes definitions, runtime nodes, handlers, properties, option catalogs, runtime data, types/codecs, conversions, resources, validators, providers, listeners, and modules before refreshing the registry.

Evidence: `ReSyncExtensionManager.java:203-305,307-382`; `NodeDefinitionRegistry.unregisterPlugin`; `HandlerRegistry.unregister`.

Known problem: no immutable generation, provider ownership in compiled plans, drain/cancel policy, rollback of arbitrary initialization side effects, or proven opaque missing-node round trip (NSR-005/016/017).

## 3. Open and save a graph

Remotely `FlowManager.openFlowEditor` first checks the server/type-aware draft cache; if known but unloaded it calls `ReSyncFlowClient.requestFlow`, otherwise it creates a local draft. `ReSyncFlowClient.requestResource/sendResourceRequest` sends the resource-specific request byte. ReSync `FlowModule` routes Flow requests to `FlowBlueprintPacketHandler.handleRequest`, which calls `FlowStorage.getGraph` and `FlowPacketSender.sendFlowData`. Function/Command requests use `FlowResourcePacketRouter` and `FlowResourcePacketHandler`. Client data handling deserializes the mirrored graph model and updates the client store before opening the pending editor.

Saving serializes the client graph and `ReSyncFlowClient.sendResourceSave` sends a route-specific mutation. Flow uses `FlowBlueprintPacketHandler.handleSave`: deserialize, set mutation ID, run live graph migration, validate, call `FlowStorage.saveGraph`, update bindings, notify change, and send revision/hash acknowledgement. Function/Command use `FlowResourcePacketHandler.handleSave` and the graph adapter. `FlowStorage.saveGraph` validates, detects type/revision conflicts, writes graph plus `assets/project.json` through `AssetTransactionManager`, advances revision/mutation ID, clears tombstone, updates caches, and emits change notification. Remotely `handleResourceSaveAck` marks the draft saved with revision/hash.

Evidence: Remotely `FlowManager.java:470-510`; `ReSyncFlowClient.java:2640-2690,2960-3030,2170-2280`; ReSync `FlowModule.java:292-354`; `FlowBlueprintPacketHandler.java:75-193`; `FlowResourcePacketRouter.java:95-139,145-273`; `FlowResourcePacketHandler.java:26-130`; `FlowStorage.java:104-330`.

Known problems: mirrored serializers, resource-specific packets, live save-time migration, ID-only projections, and no uniform generic mutation envelope (NSR-001/002/007/008/009/017).

## 4. Execute a node

`FlowExecutor.execute` validates the mutable graph and chooses a start node. `executeValidated` creates `FlowRuntime` with the current `NodeDefinitionRegistry`. Recursive execution resolves the current node, thread policy, authorization, handler, operation, inputs, and outgoing pin-name connections. `resolveHandler` applies `IdCompatibilityLayer.mapToNew`, mutates node type/handler config from the live definition, and fetches a handler from `HandlerRegistry`. `FlowRuntime.resolveInputRaw` follows name-keyed connections/literals and falls back to `resolveDefinitionDefault` against the current live definition.

Evidence: `FlowExecutor.java:197-370,422-585,2128-2190`; `FlowRuntime.java:153-235`.

Known problem: no immutable snapshot-owned execution plan; execution mutates saved node semantics and reads live defaults/aliases by mutable names (NSR-006/012/013/016).

## 5. Handle a revision conflict

`FlowStorage.saveGraph` compares submitted `resourceRevision` with the file revision and throws `ResourceRevisionConflictException`, which holds resource ID, expected revision, and current revision. Flow save handling catches it and returns only a job failure telling the user to reload. Generic resource handling creates `RESOURCE_REVISION_CONFLICT` editor diagnostics but omits the expected/current revision and authoritative payload. Remotely parses the error and displays it through `GraphEditorScreen`; normal request methods can reload the resource manually.

Evidence: `FlowStorage.java:250-280`; `ResourceRevisionConflictException.java`; `FlowBlueprintPacketHandler.java:180-188`; `FlowResourcePacketHandler.java:111-127`; Remotely `ReSyncFlowClient.java:2160-2180`; `ReSyncEditorDiagnostics.java:17-67`; `GraphEditorScreen.java:955-981,1341-1351`.

Recorded absence: no current end-to-end response returns authoritative revision, normalized payload/hash, and mutation context while preserving the failed draft. Recoverable convergence is therefore not signed (NSR-014/017; A-P03).

## 6. Migrate a legacy graph

`FlowStorage` constructor runs `migrateLegacyAssets`, which inventories/reconciles metadata, derives graph types from legacy metadata/triggers/start nodes/function flags, imports legacy roots, quarantines duplicates, and cleans legacy directories. `FlowRuntimeModule.initialize` later calls `FlowGraphMigrator.migrateStoredFlows` and `TypedAutomationGraphMigrator.migrateStoredFlows`. `FlowGraphMigrator` acquires an asset-local migration lock, scans graphs, creates per-graph backups/ledger entries, maps IDs through `IdCompatibilityLayer`, transforms values/configuration/connections, saves, and records a report. Typed automation migration creates resource definitions and rewires graph nodes with component rollback. Flow save also calls `migrateGraph` before validation.

Evidence: `FlowStorage.java:80-103,1506-2170`; `FlowRuntimeModule.java:301-305`; `FlowGraphMigrator.java:72-203`; `TypedAutomationGraphMigrator.java:50-138,298-395`; `MigrationLedger.java`; `IdCompatibilityLayer.java`; `FlowBlueprintPacketHandler.java:95-193`.

Known problems: migration is live at startup/save, inference can reclassify authority, locking/backups cover only asset-local participants, and no standalone direct-upgrade range is published (NSR-004/007/012/015/018).

## 7. Disconnect and reconnect a client

On WebSocket close/error, Remotely `ReSyncFlowClient` clears authentication, disconnects collaboration/workspaces, marks node registry unsynchronized, clears option requests, marks option catalogs stale, stops heartbeat, and schedules reconnect. After a successful handshake it subscribes startup/plugin channels, reconnects collaboration/workspaces, requests the node registry and job snapshots, flushes queued sends, and calls the connection listener. Registry deltas are accepted only against the current checksum; mismatch requests a full snapshot. Workspace connection/resync occurs through `ReSyncWorkspaceClient` and document reconnect behavior.

Evidence: Remotely `ReSyncFlowClient.java:480-730,850-925,2310-2485,3280-3490`; `ReSyncWorkspaceClient.java`; `GraphEditorScreen.java:3239-3264`.

Known problems: queued resource-specific saves, cache refresh, registry checksum, collaboration revisions, tombstones, mutation IDs, and recursive unknown data do not share one proven ordered typed envelope. Complete authoritative convergence after reconnect is not signed (NSR-008/009/014/017).

## 8. Create and restore a complete snapshot

No complete current path exists.

The closest local path is `AssetTransactionManager`: a commit stages one transaction below `assets/.transactions`, snapshots overwritten targets below `assets/.snapshots`, applies entries, and recovers prepared journals. `FlowStorage.previewAssetRestore/restoreAssetSnapshot` restores only targets recorded by that asset transaction and clears storage caches. Network `NetworkSnapshot*` contracts and restore operations cover network-domain state. Neither path fences every ReSync writer, registers/flushed all persistence participants, creates a complete ReSync-folder manifest, snapshots current state before restore, validates build/catalog/extension compatibility, stages all restored participants, or atomically activates the whole folder.

Evidence: `AssetTransactionManager.java:40-190,262-317`; `FlowStorage.java:148-161`; ReSyncCore `network/NetworkSnapshot*.java`; live participant inventory in `worker-3/findings.md` and `worker-4/fixture-inventory.md`.

Recorded absence: complete snapshot creation and restore cannot be traced and is a blocking NSR-015 finding. This trace is signed only as proof of absence.

## Signature Decision

I can trace extension add/unload, graph open/save, node execution, conflict detection/display, legacy migration, and disconnect/reconnect to concrete current methods. I can prove that complete snapshot/restore and authoritative conflict recovery do not currently exist, and those absences remain target problems rather than implied current capabilities.

ADR-007 through ADR-009 close the earlier evidence blockers: FX-013 pins and sanitizes the available ten-file real source while adding deterministic populated participant coverage; sixteen persistence participants record concrete current behavior or absence; ADR-008 fixes the current-head-only compatibility window; and reproducible current/proxy performance samples are recorded with unavailable live measures identified honestly. Reviewer B and C evidence closes the adjacent unknown-data, client, semantic-family, runtime, lifecycle, and reconnect traces.

The consolidated A+C verification produced nine XML suites with 29 tests, 29 passed, zero failures, zero errors, and zero skipped. Reviewer A therefore signs and approves Gate 0A current-system evidence. This signature does not approve a target contract, Gate 0B, production implementation, migration execution, deployment, publishing, or packaging.

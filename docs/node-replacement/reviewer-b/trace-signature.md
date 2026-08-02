# Reviewer B Current-System Trace Signature

Gate: 0A. Domain: graph interaction and client independence. This signature attests that the current paths or their explicit absence can be followed in source. It does not approve replacement contracts or implementation.

## 1. Add An Extension Node

1. `ReSyncExtensionManager.registerExtension` creates and publishes mutable `ExtensionState`, records plugin metadata, then calls `initializeExtension`.
2. `ExtensionFlowRegistration.registerNode` validates namespace, assigns the plugin owner, and immediately calls `NodeDefinitionRegistry.register(pluginId, definition)`; handlers, option sources, types, conversions, resources, and validators use separate registration calls.
3. `NodeDefinitionRegistry.register` inserts by raw node ID. A collision removes the prior plugin's list entry and overwrites `definitions`/`nodeToPlugin`; there is no contribution-wide collision rejection.
4. `ReSyncExtensionManager.refreshNodeRegistry` calls `FlowModule.refreshCustomFunctionDefinitions`, which rebuilds generated Function definitions and broadcasts a full registry snapshot.
5. `FlowNodeRegistryPacketHandler.buildFullSnapshot` emits mirrored `NodePluginPayload` data through `FlowPacketSender`.
6. Remotely `ReSyncFlowClient.handleNodeRegistrySnapshot` applies it through client `NodeRegistry.applySnapshot`, persists `NodeRegistryCache`, and refreshes an open graph designer.

Trace result: add is concrete and mutable. Atomic contribution activation, deterministic collision rejection, one shared descriptor model, and unchanged client behavior for arbitrary supported capabilities are absent. Problems: NSR-002, NSR-005, NSR-016; B-P008, B-P012.

## 2. Unload An Extension Node

1. Jar removal or `ExtensionHandle.close` reaches `ReSyncExtensionManager.unregister`.
2. The extension's `stop` runs, then `cleanupState` sequentially removes plugin data, node definitions, runtime nodes, handlers, properties, option catalogs, runtime adapters, types/codecs, conversions, resources, validators, providers, listeners, and modules.
3. `NodeDefinitionRegistry.unregisterPlugin` removes the currently owned node IDs. It cannot restore a definition displaced earlier by collision.
4. Registry refresh broadcasts the new mutable state.
5. Remotely `NodeRegistry.applySnapshot` moves removed plugin payloads into `serverUnresolvedPlugins`; `NodeRegistryTombstoneCache.replace` persists descriptor payloads by server/plugin ID.

Trace result: unload is concrete, but catalog generation swap, in-flight handler ownership/drain/cancellation, and recursive opaque graph/configuration/wire round trip are absent. Problems: NSR-005, NSR-016, NSR-017; B-P011, B-P012.

## 3. Open And Save A Graph

Open:

1. Remotely requests a graph through `ReSyncFlowClient.requestResource(type,id,...)`, using `ReSyncResourceType` packet mapping.
2. Server `FlowResourcePacketRouter.handle` dispatches to a registered `FlowResourcePacketHandler`; its graph adapter calls `FlowStorage.getGraph(type,id)`/reload and serializes the server-owned graph DTO.
3. Client `ReSyncFlowClient.handleResourceData` deserializes into its mirrored `FlowGraph`; `FlowManager.cacheFlow` places it in `TypedGraphCache`.
4. `GraphEditorScreen` builds widgets from client `NodeRegistry` definitions and joins a workspace by raw `(type,resourceId)`.

Save:

1. `GraphEditorScreen.saveGraph` branches for WorldGen, Command/custom-content cases, or calls `FlowManager.saveGraph`.
2. `FlowManager` stores an optimistic draft and `ReSyncFlowClient.sendGraphSave`/`sendResourceSave` sends a resource-specific packet byte, request ID, and serialized mirrored graph.
3. Server `FlowResourcePacketHandler.handleSave` or the legacy `FlowBlueprintPacketHandler.handleSave` deserializes, validates, persists through `FlowResourceRegistry`/`FlowStorage`, publishes resource/workspace notifications, and sends an ACK.
4. `ReSyncFlowClient.handleResourceSaveAck` updates the draft/cached graph's revision/hash and marks it saved.

Trace result: open/save is concrete. It crosses mirrored DTOs, resource packet families, optimistic client state, separate workspace semantics, and live save migration. Unknown preservation is only graph-root-level. Problems: NSR-002, NSR-007 through NSR-010, NSR-013, NSR-017; B-P004, B-P006, B-P008, B-P011.

## 4. Execute A Node

1. `FlowExecutor.execute` validates/constructs a mutable `FlowRuntime` and traverses `FlowGraph` by node IDs and pin-name connections.
2. `executePreparedNodeWithInputs` resolves the live `NodeDefinition`, authorization, loop/Function special cases, and a handler from current registries.
3. `FlowRuntime.resolveInputRaw` resolves incoming connections and literals by pin name, then `resolveDefinitionDefault` reads the current live definition's default by pin name.
4. `FlowExecutor.resolveHandler` maps legacy type through `IdCompatibilityLayer`, then mutates the graph node with `node.setType(mappedType)` and `node.setHandlerConfig(definition.getHandlerConfig())` before handler execution.
5. Functions are generated/bound by mutable parameter names; loops/branches use string pin conventions.

Trace result: execution is concrete and directly mutates/reinterprets persisted graph state from live catalog data. No immutable execution plan, fixed catalog generation, stable pin/parameter identity, or provider unload lease exists. Problems: NSR-006, NSR-013, NSR-016; B-P006, B-P012.

## 5. Handle A Revision Conflict

1. Loaded graphs carry `resourceRevision`; client saves serialize it in the graph payload.
2. `FlowStorage.saveGraph` compares a positive submitted revision to current revision and throws `ResourceRevisionConflictException` on mismatch.
3. The exception contains resource ID, expected revision, and current revision.
4. `FlowResourcePacketHandler.handleSave` converts the conflict into an `EditorError`; `FlowBlueprintPacketHandler.handleSave` fails the job with a reload message.
5. Neither conflict response returns the exception revisions or authoritative normalized payload. Remotely has success ACK handling but no uniform conflict merge/refresh response; specialized resource routes often send no expected revision.

Trace result: detection exists for graphs, authoritative recovery does not. Problems: NSR-009, NSR-017; B-P001, B-P002, B-P010.

## 6. Migrate A Legacy Graph

1. `FlowStorage` construction calls `migrateLegacyAssets`, which performs metadata reconciliation, graph/resource discovery, type inference/reclassification, legacy directory moves, duplicate cleanup, and quarantine during normal startup.
2. `FlowGraphMigrator.migrateStoredFlows` scans stored graphs, creates per-graph backups/reports, and rewrites supported legacy IDs/pins/values/connections.
3. `TypedAutomationGraphMigrator.migrateStoredFlows` creates typed automation resources, rewires graphs, and uses per-graph backups/rollback.
4. `FlowBlueprintPacketHandler.handleSave` also calls `FlowGraphMigrator.migrateGraph` on every ordinary graph save.
5. `IdCompatibilityLayer` and `nodes/migrated/**` remain reachable runtime compatibility surfaces.

Trace result: migration is concrete but live, distributed, and partial. There is no standalone complete-folder fenced upgrade transaction. Problems: NSR-003, NSR-004, NSR-007, NSR-012, NSR-015, NSR-018.

## 7. Disconnect And Reconnect A Client

1. WebSocket close/error clears authentication, disconnects collaboration/workspaces, marks option catalog requests stale, marks the registry unsynced, stops heartbeat, and schedules reconnect.
2. Registry cache and unresolved plugin payload cache can be used before live hydration.
3. After handshake, the client subscribes channels, reconnects collaboration/workspaces, requests the node registry and jobs, flushes queued resource-list requests and queued send closures, then invokes the connection listener.
4. Registry deltas have checksum-baseline logic. Workspace snapshots reject older workspace sequences.
5. Resource events have no revision/mutation/hash/deletion ordering fields, and queued saves have no demonstrated revision rebase after hydration.

Trace result: transport retry, registry checksum refresh, and workspace sequence handling exist, but global cache/resource/workspace/mutation convergence is unproven. Problems: NSR-009, NSR-017; B-P001, B-P003, B-P004, B-P011.

## 8. Create And Restore A Complete Snapshot

The required current path does not exist.

- `AssetTransactionManager` creates/restores snapshots only for files touched by an asset transaction.
- `FlowStorage.backupGraphForMigration` creates per-graph migration backups.
- `FlowBlueprintPacketHandler.restoreFlowSave` compensates one failed save.
- ReSyncCore network snapshot contracts and Remotely `NetworkSnapshotScreen` concern network/player state, not a complete ReSync folder.
- No participant registry, system-wide writer fence, full-folder manifest, file ownership/hash verification, disk preflight, build/contract compatibility check, staged whole-folder activation, or restore journal was found.
- No generic graph export/import operation was found in the traced Flow/client protocol surfaces.

Trace result: explicit absence recorded under NSR-015 and B-P014. The local run folder has independently located assets, diagnostics, world-management, configuration, and extension roots, which demonstrates why asset-only restore is insufficient.

## Resumed Evidence Closure

- B1 now regenerates an exact source inventory over Remotely, RemotelyMod, and the protocol-owner JSON and compares every sorted record plus a hard SHA-256 fixture. This converts the earlier representative client-branch trace into a reproducible declared-scope inventory.
- B2 now pins an exact recursive graph deserialize/serialize golden. Root and generic-map material survive, generic numeric values normalize to doubles, and unknown fields on typed nodes, connections, Function parameters/type references, and passthroughs are discarded.
- B3 now pins the canonical 1,024-node registry wire payload and the separate cache-restored projection. Cache reconstruction preserves plugin content by plugin ID, changes plugin ordering, and materializes `propertyActions` and `propertyOutputTypes` as empty maps.
- B4 now pins the specialized designer/save/conflict/reconnect boundary and six current-head synthetic evidence fixtures, including same-ID typed resources, missing-provider nodes with inbound/outbound wires, nested opaque material, and explicit generic export/import absence.
- ADR-007 supplies the separately owned sanitized real-folder evidence; ADR-008 limits compatibility evidence to ReSync `b2941e66d762ffb76391715fbf4487c068fe12ea` and Remotely `76c4eb8989aaf856a4f002fa7fe6e56a67c373df`.

## Signature Decision

Reviewer B can trace all required current-system flows either to concrete methods or to a reproducibly proved missing path. The resumed fixtures, generated inventory, compatibility declaration, and deterministic registry baseline close this domain's Gate 0A evidence gaps. Reviewer B approves Gate 0A for graph interaction and client independence. This approval does not freeze Gate 0B contracts or authorize production replacement work by itself.

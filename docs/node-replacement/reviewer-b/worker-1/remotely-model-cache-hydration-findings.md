# Reviewer B Worker 1 — Remotely Mirrored Models, Hydration, Cache, And Server Scope

Status: Gate 0A evidence only. Inspected 2026-08-02; no production source, tests, or build commands were run.

## Scope And Current Architecture Trace

Remotely maintains its own flow contract hierarchy under `Remotely/src/main/java/redxax/oxy/remotely/flow/**`: `NodeDefinition`, `NodeRegistry`, `NodeRegistrySnapshot`, `NodePluginPayload`, `FlowGraph`, `FlowNode`, `FlowConnection`, `FlowTypeRef`, serializers, metadata, and protocol contract. `Remotely/src/main/java/redxax/oxy/remotely/data/flow/ReSyncResourceType.java` adds client resource-type-specific serializers and packet byte routing; `ReSyncFrameCodec.java` separately owns frame encoding. This duplicates the server graph DTOs and serializer at `ReSync/src/main/java/restudio/flow/data/{FlowGraph,FlowNode,FlowConnection,FlowSerializer}.java` and the conceptual server hierarchy under `ReSync/src/main/java/restudio/resync/flow/**`, matching NSR-001 and NSR-002.

The client connection is `ReSyncFlowClient`. On connection it can restore `NodeRegistryCache` plus `NodeRegistryTombstoneCache` in `loadCachedRegistry()` (lines 2595-2611), then requests a registry snapshot. `handleNodeRegistrySnapshot()` (2322-2367) checks contract/checksum compatibility, applies the client-owned DTO snapshot to `NodeRegistry`, persists a materialized client snapshot, and refreshes the graph screen. `NodeRegistry.applySnapshot()` (114-201) mutates numerous per-server maps, then rebuilds definitions. It accepts a full/delta checksum baseline but has no catalog generation, provenance atom, or whole-contribution validation boundary.

Graph resources use client-local `FlowGraph` (including `resourceType`, `resourceRevision`, `resourceHash`, `resourceMutationId`) and `FlowSerializer`. `FlowManager` owns a `TypedGraphCache`, one `SyncedResourceCache` per graph type, and more resource-specific caches. `GraphEditorScreen` opens a graph through `FlowManager.getGraph(serverId, type, id)` and sends saves through `ReSyncFlowClient.sendGraphSave`. The latter serializes client graph data and routes a resource-type-specific packet in `sendResourceSave()` (2991-3024). Server receipt is `FlowResourcePacketRouter.handle()` -> `FlowResourcePacketHandler.handleSave()` -> `FlowResourceRegistry.saveFromSession()` and per-type adapter, not a generic protocol envelope.

On a successful graph save, `handleResourceSaveAck()` (2198-2257) accepts only ID/request ID/revision/hash, mutates the currently cached/draft graph revision/hash in `FlowManager.markFlowSaved()`, and marks it saved. On a resource-change event, `handleResourceEvent()` (1679-1710) accepts `{type,resourceId,payload,authorSessionId,author,changedAt}`, immediately deserializes and caches the payload; a delete immediately removes the resource and asks for a type-specific list. No revision, mutation ID, payload hash, or server locator is present in the event record.

`SyncedResourceCache` keys all state using a concatenated `serverId + ":" + resourceId` string. `TypedGraphCache` splits Flow/Function/Command stores but still exposes ID-only overloads (`get`, `getFromDraft`, `uniqueStore`, `getForServer`) that return null on a discovered collision rather than requiring a typed key. `FlowGraph.FunctionParameter` has only mutable `name` and list position; `EditorPassthrough` has only node ID/input-pin strings. `FlowNode.inputValues` and `FlowConnection.sourcePin`/`targetPin` are string keyed.

`FlowManager.hydrateProjectMetadata()` (3442-3531) repairs project metadata from caches: it deletes metadata for absent loaded graphs, infers graph type from `resourceType` or `isFunction`, adds graph/resources/worlds absent from metadata, and calls `saveProjectMetadata()` if it removed entries. This contradicts the required presentation-only role for metadata.

Live read-only run evidence: `ReSync/run/plugins/ReSync/assets/project.json` has an empty `resources` array, while `assets/Blueprints`, `Content`, `Customization`, `WorldGen`, and `Worlds` folders exist. `diagnostics` contains two large programmability snapshots; `extensions` is empty; world-management has persisted JSON. No complete coordinated snapshot/restore manifest, journal, or extension fixture exists at this location. No unsanitized live data was copied.

## Required Flow Traces

| Required trace | Evidence | Current conclusion |
|---|---|---|
| Add extension node | Server `NodeDefinitionRegistry.register(pluginId, definition)` and client `NodeRegistry.applySnapshot()` consume `NodePluginPayload`; `rebuildServerDefinitions()` inserts definitions by ID. | Separate mutable registry paths, no validated atomic contribution found. |
| Unload extension | Server `NodeDefinitionRegistry.unregisterPlugin`; client snapshot `removedPlugins` moves payload into `serverUnresolvedPlugins` and persists it via `NodeRegistryTombstoneCache`. | Client preserves descriptor payload by server/plugin ID only; no trace of provider drain/cancel or lossless graph node/wire preservation. |
| Open/save graph | `GraphEditorScreen` -> `FlowManager.getGraph`; `ReSyncFlowClient.sendGraphSave` -> server router/handler/registry -> ack handling. | Client model and resource-specific protocol remain authoritative in parts of editing. |
| Node execution touchpoint | Client graph serializes `FlowNode.type` and string input map; server `FlowExecutor`/`FlowRuntime` trace is documented in dossier and is outside client runtime. | Client carries mutable node type/pin-name data; no shared immutable execution-plan contract. |
| Revision conflict | Server `FlowResourcePacketHandler.handleSave()` catches `ResourceRevisionConflictException` and sends only editor error; client `handleResourceSaveAck()` is success only. | No authoritative revision/payload refresh path found in client conflict handling. |
| Legacy migration | Client `FlowSerializer` preserves unknown top-level graph fields only; server-side legacy migrators are listed in dossier. | No client migration boundary/version report trace; raw graph identity remains legacy-shaped. |
| Reconnect | `connect()` increments generation; cache is loaded, then a snapshot requested; `scheduleReconnect()` retries. | Registry delta checksum resync exists, but resource cache reconciliation has no event revision ordering. |
| Complete snapshot/restore | Read-only source and run-folder search found network/debug/diagnostic snapshots and local graph rollback only. | No complete fenced ReSync-folder snapshot/restore client trace; record as absent. |

## Findings

### NSR-002 / NSR-008 / NSR-010 — Mirrored mutable client catalog and resource-specific protocol

**Current behavior/defect:** Remotely reconstructs descriptors, type metadata, conversions, options, plugin payloads, and graph types into client-owned DTOs. `NodeRegistry` applies a snapshot by mutating separate maps; `rebuildServerDefinitions()` silently overwrites same-ID definitions by map insertion. `ReSyncFlowClient` continues to select request/save/delete packet bytes through `ReSyncResourceType` and `ReSyncProtocolContract`; server `FlowResourcePacketRouter` owns a growing adapter set. Registry fallback categories/types (`fallbackCategoryMetadata`, `resolveType`) are client inference.

**Classification and replacement goal:** Replace. Consume exact ReSyncCore descriptors/canonical codecs and a generic catalog/resource envelope; project-specific adapters may render but not redefine contract semantics. Catalog/extension activation must validate and swap a complete snapshot.

**Invariants:** one shared model; server/type/ID locator; immutable catalog generation/checksum; no registration-order collision; capability fallback is declared and lossless; ordinary node/extension changes require no Remotely source edit.

**Affected neighbors/contracts:** ReSyncCore catalog/type/protocol/identity contracts (all draft in ledger); `ReSyncProtocolContract`; `NodeRegistrySnapshot`; `NodePluginPayload`; `ReSyncResourceType`; `ReSyncFrameCodec`; `FlowResourceRegistry`; `GraphEditorScreen`; option selectors; ReSync/Remotely protocol generators; mirrored server `restudio.flow.data` DTOs.

**Client/persistence/migration/compatibility impact:** Client removes DTO authority and special routes. Persisted graphs must be read through the shared graph model. Offline migration must map legacy client graph data to the shared schema. Existing clients require explicit negotiated read-only/lossless fallback; no bounds are approved in the compatibility matrix.

**Fixtures required:** FX-005 (extension add/unload/reinstall with a collision and a missing capability), FX-008 (failed contribution leaves snapshot byte-identical), FX-012 (unknown fields/capabilities). Exact owned evidence file only: this report. **Risk:** the exact server extension registration/broadcast call chain still needs a dedicated server lifecycle trace.

### NSR-009 / NSR-013 — Concatenated and ID-only client keys; mutable structural identities

**Current behavior/defect:** `SyncedResourceCache.key()` is an ambiguous string concatenation; its maps are scoped server+ID but omit resource type. `TypedGraphCache` has type-specific stores yet exposes ID-only methods and collision-null behavior. `FlowManager.getGraphType(serverId,id)` scans types. `FlowGraph` persists function parameters by name/list order; `FlowNode` and `FlowConnection` persist pin strings; `EditorPassthrough` persists a string pin. Node definitions and node instances similarly use mutable string IDs/type names without owner key.

**Classification and replacement goal:** Replace with shared typed server locator/server-local typed key and immutable structural IDs for every definition, pin, function parameter, mode, branch, repeatable element, and inspector field.

**Invariants:** no ID-only mixed-resource lookup; cross-type collision is deliberately rejected/reclassified; display names/order never identify wires or parameters; cache ordering applies authoritative revisions.

**Affected neighbors/contracts:** `ReSyncResourceKey`; `ReSyncResourceType`; `TypedGraphCache`; `SyncedResourceCache`; `FlowGraph`, `FlowNode`, `FlowConnection`, `FlowSerializer`; graph editor and Function builders; server `FlowStorage`/validator/executor.

**Client/persistence/migration/compatibility impact:** Cache keys and serialized graph fields change together. Migration must preserve every connection and function binding through rename/reorder. Older clients must retain unknown stable IDs opaquely. **Fixtures required:** FX-006, FX-007, FX-012 plus rename/reorder and cross-server/type collision cases. Exact owned evidence file only. **Risk:** strings such as `serverId + ":" + resourceId` can collide under unrestricted colon-containing inputs; validation proof was not found.

### NSR-009 / NSR-016 / NSR-017 / NSR-B1-001 — Hydration, tombstones, and event ordering are not authoritative revision reconciliation

**Current behavior/defect:** Registry deltas have a checksum baseline but resource events omit revision, mutation ID, payload hash, and server identity. `handleResourceEvent()` therefore caches any received payload and deletes immediately. `SyncedResourceCache.cache/replaceFromServer/applyServerList` have no revision comparison or tombstone record. `NodeRegistryTombstoneCache` is not a resource tombstone cache: it stores removed plugin payloads only. Cache restore can make cached definitions usable before server hydration. Snapshot state lacks a catalog generation and opaque node data semantics.

**Classification and replacement goal:** Replace with acknowledged projections keyed by typed locator, carrying revision/mutation ID/hash/deletion state; reconcile deterministically and retain newer tombstones across reconnect/restart.

**Invariants:** server wins; older event cannot overwrite newer acknowledged state; deletion remains after reconnect; retry is idempotent; unknown data survives client round trip.

**Affected neighbors/contracts:** generic protocol/resource event/collaboration envelopes; `ReSyncFlowClient`; `ReSyncCollaborationClient`; `FlowManager`; caches; `FlowResourceRegistry.notifySaved/notifyDeleted`; network synchronization.

**Client/persistence/migration/compatibility impact:** client cache becomes non-persistent authority only. Existing cached snapshots require discard/re-hydrate or explicit migration; compatibility needs a revision-aware envelope fallback. **Fixtures required:** revised FX-007 (same ID/type/server), tombstone-before-payload, payload-before-tombstone, duplicate mutation ID, reconnect/restart, two-client stale save, and missing extension round trip. Exact owned evidence file only. **Risk:** generic resource event server publisher and its ordering guarantees need cross-reviewer verification.

### NSR-007 / NSR-B1-002 — Project metadata hydration reconstitutes resources from cache and writes repaired metadata

**Current behavior/defect:** `FlowManager.hydrateProjectMetadata()` treats cache/list state, `isFunction`, resource type fallbacks, trigger bindings, and cached resource stores as evidence to remove/add metadata entries; it can call `saveProjectMetadata()`. The actual run folder demonstrates presentation metadata can be empty while asset folders exist. This violates the governing rule that project metadata owns presentation only and must not recreate, reclassify, or resurrect a resource.

**Classification and replacement goal:** Delete live repair/reclassification behavior after one versioned offline migration. Hydration reconciles acknowledged typed payloads with presentation metadata without creating persisted resources or converting type.

**Invariants:** payload existence/type governs; metadata cannot resurrect or reclassify; normal startup/hydration performs no legacy repair; migration is versioned, idempotent, backed up, and reports quarantines.

**Affected neighbors/contracts:** `FlowManager`; `ReSyncProjectMetadata`; trigger bindings; `TypedGraphCache`; `ReSyncResourceType`; server `FlowStorage` migrations and project metadata service; migration/snapshot contracts.

**Client/persistence/migration/compatibility impact:** client must stop persisting repair based on cache. Offline upgrader owns legitimate metadata normalization; supported legacy metadata requires an explicit migration report. **Fixtures required:** empty metadata + valid payloads, metadata points to missing typed payload, flow/function/command same ID, stale cached graph after deletion, command binding conflict, second migration run zero changes. Exact owned evidence file only. **Risk:** save timing can create a client-originated write during hydration; production trace must establish whether this occurs automatically on every connection.

### NSR-015 / NSR-B1-003 — No complete client-visible snapshot/restore trace

**Current behavior/defect:** The live folder contains local diagnostics and domain files only. Source traces reveal per-resource rollback (`FlowBlueprintPacketHandler.restoreFlowSave`) and network/debug snapshots, not a whole-ReSync-folder fence, participant flush, manifest, journal, staged restore, compatibility preflight, or client restore reconciliation.

**Classification and replacement goal:** Add the single coordinated server snapshot/restore service governed by plan; client consumes status through shared diagnostics/protocol and rehydrates authoritative snapshots only after activation.

**Invariants:** no partial restore exposure, hash-verified manifest, writer fence, deterministic recovery, compatibility rejection before mutation, catalog/cache rehydration after activation.

**Affected neighbors/contracts:** snapshot/migration/protocol/diagnostic contracts; every storage participant including network/extension/world-management; `ReSyncFlowClient` reconnect/cache path.

**Client/persistence/migration/compatibility impact:** current local caches cannot be treated as restore evidence and must rehydrate. **Fixtures required:** FX-010 and FX-011 plus restored-newer-tombstone/reconnect. Exact owned evidence file only. **Risk:** absent trace, not an assertion that no server-only implementation exists outside searched paths.

## Gate 0A Summary

Problem IDs addressed: NSR-002, NSR-007, NSR-008, NSR-009, NSR-010, NSR-013, NSR-015, NSR-016, NSR-017; proposed local IDs NSR-B1-001 through NSR-B1-003. No shared ledger was edited. All shared contracts remain draft and frozen from worker modification. This report owns only `docs/node-replacement/reviewer-b/worker-1/remotely-model-cache-hydration-findings.md`.

## Resumed Inventory Evidence

The deterministic exhaustive lexical inventory is `client-independence-inventory.md`; its source test is `Remotely/src/test/java/redxax/oxy/remotely/replacement/evidence/b1/B1ClientIndependenceInventoryTest.java` and its checksum fixture is `Remotely/src/test/resources/fixtures/node-replacement/b1/client-independence-inventory.sha256`. It covers every Java file below the declared Remotely source root and adds controls for direct `node.getType()` equality/switch branches and literal input-map keys. The fixed canonical result is 3,193 categorized records and SHA-256 `58808c5880de42b7697e6221d2bf4d4f669bd4fefa39006be955fbddbc705d99`.

Exact attempted focused verification command: `Remotely\\gradlew.bat test --tests redxax.oxy.remotely.replacement.evidence.b1.B1ClientIndependenceInventoryTest --no-daemon`. It did not reach test execution because the shared Gradle workspace reported `Waiting For Another ReStudio Build To Finish`; Reviewer B will run accepted focused tests serially. A read-only equivalent PowerShell canonicalization using the same declared rules produced the recorded 3,193 records and checksum.

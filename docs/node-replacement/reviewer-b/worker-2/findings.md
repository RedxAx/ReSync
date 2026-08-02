# Reviewer B2 — Graph Canvas, Structural Editing, And Graph Persistence Trace

Gate: 0A evidence only. Scope: graph canvas/editor/widget/serialization flow, node-ID and resource-ID branches, dynamic pins, Functions, branches/repeatables, and immutable identity. No production files, tests, or Gradle tasks were changed or run.

## Current Architecture Traced

### Graph Shape And Serialization

- The complete mirrored server graph-data surface is `ReSync/src/main/java/restudio/flow/data/**`, including `FlowGraph`, `FlowNode`, `FlowConnection`, `FlowSerializer`, `FlowDataType`, `FlowTypeRef`, `FlowResourceReference`, and graph-adjacent custom-content adapters. Remotely owns independent equivalents under `Remotely/src/main/java/redxax/oxy/remotely/flow/data/**`; neither is a shared ReSyncCore contract. This concretely expands NSR-002 beyond the three primary graph classes.
- Server and client retain separate `FlowGraph`, `FlowConnection`, and `FlowSerializer` models. The client serializer preserves only unknown **root graph** properties; it does not establish a recursive unknown-data contract for nodes, connections, parameters, inspector data, or structural elements. Evidence: `ReSync/src/main/java/restudio/flow/data/FlowGraph.java:21-42`, `Remotely/src/main/java/redxax/oxy/remotely/flow/data/FlowSerializer.java:13-50`.
- `FlowGraph` stores nodes in `Map<String, FlowNode>` and wires in `List<FlowConnection>`. A connection is exactly source node ID, source pin string, target node ID, target pin string, plus editor source node/pin strings. There is no immutable connection ID, branch ID, repeatable-element ID, or pin-instance ID. Evidence: `ReSync/src/main/java/restudio/flow/data/FlowGraph.java:23-47`; `Remotely/src/main/java/redxax/oxy/remotely/flow/data/FlowConnection.java:5-69`.
- Node positions remain fields on mutable graph nodes and are copied/saved by the editor. The canvas selects, wires, deletes, copies, and restores directly against this mutable model. Evidence: `Remotely/src/main/java/redxax/oxy/remotely/flow/ui/GraphEditorScreen.java:5386-5409, 5436-5480, 5750-5908, 6849-7006`.
- The client collaboration diff specializes only `/connections`: it matches whole connection JSON values by equality and emits array add/remove patches. This cannot address one wire independently when two equal connection objects exist, and structural arrays other than connections fall back to positional `set` patches. Evidence: `Remotely/src/main/java/redxax/oxy/remotely/flow/data/FlowWorkspaceDocument.java:82-107, 152-206`.

### Open, Edit, Save, Conflict, And Reconnect

1. `FlowManager` owns resource-family-specific client caches, hydration, opening, saving, drafts, activation, and project metadata. It maintains a `TypedGraphCache` plus dedicated GUI/scoreboard/tab/content/JSON stores and dispatches through `ReSyncResourceType`; it has ID-only graph-type lookup paths. Evidence: `Remotely/src/main/java/redxax/oxy/remotely/data/flow/FlowManager.java:95-116, 356-400, 1214-1258, 2445-2464, 2257-2287`.
2. `ReSyncResourceType` is a closed client enum with resource-specific request/packet bytes, so ordinary resource-family additions require matching client enumeration/protocol work. Evidence: `Remotely/src/main/java/redxax/oxy/remotely/data/flow/ReSyncResourceType.java:16-220`.
3. `GraphEditorScreen` opens a graph through the local `FlowManager`/resource route and joins the workspace using a `(type, resourceId)` `WorkspaceTarget`; it creates UI widgets from the client `NodeRegistry` projection. Evidence: `GraphEditorScreen.java:619-631, 6148-6227`; `ReSyncWorkspaceClient.java:46-93, 182-184`.
4. `ReSyncFlowClient` encodes workspace join/leave/operation/awareness targets as `type` plus raw `resourceId`, with patch operation ID and base sequence. It uses the Remotely-generated `ReSyncProtocolContract` packet constants and `ReSyncFrameCodec` framing. Evidence: `Remotely/src/main/java/redxax/oxy/remotely/data/flow/ReSyncFlowClient.java:361-402, 738-754`; `Remotely/src/main/java/redxax/oxy/remotely/data/flow/ReSyncFrameCodec.java`.
5. Workspace snapshots/operations carry only resource `type`, raw `resourceId`, sequence, operation ID, author session, and patches. They do not carry a server locator, authoritative revision, mutation ID, normalized payload hash, deletion state, or catalog checksum. Evidence: `ReSyncWorkspaceClient.java:99-131, 204-215`; `ReSyncCore/src/main/java/restudio/resync/flow/workspace/WorkspaceTarget.java`.
6. Server `FlowWorkspaceService` applies patches to JSON and persists graphs through `FlowStorage`/the resource adapter. It does preserve server-managed roots and can resync a workspace, but this is a document-patch route rather than a complete typed mutation envelope. Evidence: `ReSync/src/main/java/restudio/resync/modules/flow/FlowWorkspaceService.java:32-45, 107-162, 385-468, 646-674`.
7. `FlowStorage.getGraph(type, id)` accepts a raw type and ID, uses type+ID only after the caller chooses a type, and caches by a concatenated string key. Its no-type overload performs legacy flow lookup. Evidence: `FlowStorage.java:105-143, 109-123`; `FlowStorage.java:1234-1238`.
8. `FlowStorage.saveGraph` performs resource revision/hash/mutation handling and validates the graph, but graph open/save authority is split with metadata, asset indexing, and workspace paths. Existing per-asset restore only resets asset transaction state and clears caches; it is not complete ReSync-folder restore. Evidence: `FlowStorage.java:153-162, 199-341, 1506-2155`.
9. `ReSyncWorkspaceClient` reconnect/disconnect delegates to `LiveDocumentChannel`; it routes snapshot, operation, awareness, and resync by raw `(type, resourceId)` target. Evidence: `ReSyncWorkspaceClient.java:38-44, 99-136`. This trace finds no graph protocol proof that reconnect preserves unknown node/parameter/structural bytes or resolves authoritative resource revision conflicts.

### Node Definitions, Dynamic Pins, Families, And Canvas Branches

- The server descriptor model names pins with `String name`; `visibleWhen` is `Map<String,String>` keyed by another pin name; `RepeatablePin` contains only descriptor group ID/min/max/label. It does not define a pin machine ID separate from the name or an element-instance identity. Evidence: `NodeDefinition.java:437-589, 917-998`.
- `NodeDefinitionLoader.toPinDefinition` builds the descriptor using `pin.name`, copies the name-keyed visibility map, and transfers repeatable metadata. It infers selector data types from options-source strings and inferred option sources. Evidence: `NodeDefinitionLoader.java:683-806`.
- The canvas makes family/conditional decisions from names: it recognizes selector pins only where input name is `mode` or `action`, reads `visibleWhen` values, and returns `pin.getName()` as the wire/add-node target. Evidence: `GraphEditorScreen.java:6025-6050, 6104-6126, 6299-6445`.
- The canvas has its own resource-drag-to-type switch for every known `ReSyncResourceDragPayload` resource family. Evidence: `GraphEditorScreen.java:777-798`. This is a direct client resource-ID/type branch rather than catalog capability rendering.
- Optional-pin removal and passthrough are driven by raw pin names; a passthrough record stores node ID + input pin string, and its wire editor source uses pin strings. Evidence: `FlowGraph.java:134-163`; `GraphEditorScreen.java:5198-5211, 6701-6767`.
- `NodeWidget` is a second major client special-case surface: it contains hard-coded Function start/end/call legacy and canonical IDs, migrates those IDs in the widget, mutates signatures/wires by parameter name, synthesizes repeatable pins by numeric index, and persists branches under private input-value keys. Evidence: `Remotely/src/main/java/redxax/oxy/remotely/flow/ui/NodeWidget.java:111-137, 267-308, 967-1030, 1225-1305, 1429-1714, 1933-1999, 3386-3591`.
- Canvas “branch” is visual fan-out and branch-pin focus, not a persisted structural branch/case model: focus selects by source node ID and source pin string, while fan-out geometry is recomputed from connections. Evidence: `GraphEditorScreen.java:1025-1040, 4716-4753`.

### Functions And Generated Nodes

- A Function parameter persists only mutable name, type/typeRef, widget, option source, and default string. There is no parameter ID. Evidence: `FlowGraph.java:49-132` and mirrored Remotely `FlowGraph.java`.
- `CustomFunctionNodeDefinitions.rebuild` unregisters all `custom_functions`, enumerates stored Functions, then creates the callable node ID from `custom_function:` + raw Function graph ID. It validates uniqueness and sorts signature pins by parameter **name**; generated pin identities are therefore names. Evidence: `CustomFunctionNodeDefinitions.java:25-50, 53-103, 142-155`.
- Server Function invocation binds defaults, context values, and configured values in maps keyed by `parameter.getName()`. Evidence: `FunctionCallSupport.java:130-151`; client widget type resolution also matches pin name to parameter name. Evidence: `FlowNodeWidget.java:50-72`.
- The client function context menu adds/removes parameters by names, and the editor copies parameter lists retaining only the same mutable name-centric fields. Evidence: `GraphEditorScreen.java:2737-2750, 2882-2907, 5520-5529, 7016-7029`.

### Extension Add And Unload

- `ReSyncExtensionManager.registerExtension` places an extension state in `extensions` before `initializeExtension`, then individual registrations occur through the extension context; failure removes and cleans up the state. `refreshNodeRegistry` only refreshes generated Function definitions. Evidence: `ReSyncExtensionManager.java:160-193, 218-223`.
- Cleanup unregisters nodes, runtime handlers, properties, option catalogs, codecs/types, adapters, resource types, validators, content/world-map providers, listeners, and modules sequentially; no active immutable catalog generation or execution-plan drain boundary appears in this path. Evidence: `ReSyncExtensionManager.java:203-305`.
- Jar scanning loads each `ServiceLoader` extension individually and continues after individual failures. Removing a jar invokes sequential `unregister` for each plugin. Evidence: `ReSyncExtensionManager.java:315-382`. Thus the concrete current path proves no all-contribution atomic activation/unload guarantee.
- Client `NodeRegistry.applySnapshot` keeps removed plugin payloads as unresolved payloads and rebuilds definitions, but the trace cannot prove lossless graph-node round trip when a definition is unavailable because graph serialization is independent and has no node-level opaque-field model. Evidence: `NodeRegistry.java:114-200, 373-424`; `FlowSerializer.java:25-50`.

### Legacy Migration And Complete Snapshot/Restore

- `FlowStorage` constructor invokes `migrateLegacyAssets` on normal startup. That method reconciles metadata, type inference/reclassification, legacy directories, duplicate cleanup/quarantine, and asset paths. Evidence: `FlowStorage.java:76-103, 1506-2155`.
- Existing graph migration backups are per graph (`backupGraphForMigration`); asset recovery/restore operates through `AssetTransactionManager`, not all ReSync persistence participants. Evidence: `FlowStorage.java:764-823, 153-162`.
- No current class/method was found that fences every persistence participant, flushes extensions/network/runtime state, creates a manifest for the whole ReSync folder, stages a complete restore, validates all hashes, and atomically activates it. `NetworkSnapshotRestore` exists under the network contract but is network-domain state only. This is explicit absence evidence, not a target-design inference.

## Problem IDs Addressed

Existing: NSR-002, NSR-006, NSR-007, NSR-009, NSR-010, NSR-013, NSR-015, NSR-016, NSR-017, NSR-018.

New local findings (do not add to the shared ledger until Reviewer B accepts them):

| ID | Concrete defect | Violated invariant / impact |
|---|---|---|
| NSR-B2-001 | Connections, passthroughs, conditional pins, and repeatable pin metadata use strings/name-keyed maps; there are no durable connection, branch, repeatable-element, or pin-instance IDs. | Renames/reordering/duplicate equal values cannot reliably preserve structural identity or collaboration edits. |
| NSR-B2-002 | Function signatures and callable generated-node pins are keyed, sorted, and bound by mutable parameter names. | Renaming or reordering a parameter changes external call wiring and default/value binding. |
| NSR-B2-003 | `GraphEditorScreen` contains resource-payload and family-selector/name branches. | Ordinary resource/selector/family changes require client source edits; node capability is not fully descriptor-driven. |
| NSR-B2-004 | Workspace patch routing and client graph serialization lack a complete typed mutation/unknown-data/revision envelope. | Reconnect, conflict recovery, unavailable definitions, and opaque structural data are not proven lossless or authoritative. |
| NSR-B2-005 | Current `FlowSerializer` preserves unknown data only at the root graph object and inside generic value maps; Gson discards unknown fields on typed node, connection, Function-parameter, type-reference, and passthrough objects. | Unknown extension/type/widget/editor/structural data has a path-dependent and undocumented loss boundary. |

## Recursive Unknown-Data Golden Baseline

Owned fixtures and focused test:

- `Remotely/src/test/resources/fixtures/node-replacement/b2/current-round-trip-source.json`
- `Remotely/src/test/resources/fixtures/node-replacement/b2/current-round-trip-expected.json`
- `Remotely/src/test/resources/fixtures/node-replacement/b2/current-round-trip-baseline.json`
- `Remotely/src/test/java/redxax/oxy/remotely/replacement/evidence/b2/CurrentGraphRoundTripBaselineTest.java`

The golden source intentionally includes known and unknown data at every currently relevant nesting boundary: root opaque state; node data; connection data; Function input/output and `FlowTypeRef` generic arguments; passthrough fields; private branch/repeatable/call-signature state inside `inputValues`; and generic `contentProperties` editor state.

The canonical expected output removes typed-object unknown fields while retaining root opaque data and generic-map structure. Generic Gson map numbers are not byte-exact: `__repeatable_inputs:values` changes from `2` to `2.0`, and the unknown widget state's `[1, 2]` changes to `[1.0, 2.0]`. Those paths are explicitly classified as normalized rather than preserved; the test asserts source/output canonical inequality and expected-double output. The fixture-derived canonical SHA-256 is `35887bee10bd87b48a54b6ba4515439e7c4ec25224db222440b5c7005daa25d4`. The test asserts both exact canonical JSON and this SHA, plus the explicit preserved/normalized/discarded path manifest; it does not imply the loss is acceptable replacement behavior.

Result status: a first focused Gradle test was started before the shared workspace lock was reported, but no B2 result XML was produced. Per Reviewer B coordination, B2 did not wait for or retry the shared lock. Reviewer B will run the accepted focused Remotely evidence tests serially. Planned command:

`./gradlew.bat test --tests redxax.oxy.remotely.replacement.evidence.b2.CurrentGraphRoundTripBaselineTest`

## Required Pre-Implementation Classification

| Field | Evidence-based result |
|---|---|
| Current behavior / defects | Mutable mirrored graph models; string/name/position structural addressing; client-specific family/resource branches; partial workspace patching; live startup migration; per-asset only restore. |
| Proposed classification | Replace graph document/serializer, inspector/structural editor, generic client projection and workspace mutation path. Adapt canvas rendering and specialized designers to shared capabilities. Delete node/resource-ID branches and live migration paths after approved offline conversion. |
| Replacement goal | One shared graph document with immutable IDs for nodes, pins, connections, modes, branches, repeatable elements, parameters, and inspector fields; lossless opaque data; generic descriptor/capability-driven canvas and inspector; server-authoritative typed mutation/revision flow. |
| Invariants | Typed server/type/ID resource identity; display labels never persist as identity; wire/parameter/element identity survives rename/reorder; unknown data round trips byte-for-byte/canonically; failed extension activation/unload never partially changes active catalog; execution uses a fixed validated snapshot. |
| Affected neighbors | Reviewer A: shared graph/identity/serializer/protocol/migration/snapshot contracts. Reviewer C: Function execution, handler registry, compiled plans, extension drain/cancellation. Reviewer B: `NodeRegistry`, `GraphEditorScreen`, `NodeWidget`, workspace client/cache, rich designers. |
| Shared contracts consumed | Existing `WorkspaceTarget`, `WorkspacePatch`, `WorkspaceRevision`, resource revision/tombstone and asset transaction APIs, node snapshot/plugin payload DTOs. They are not yet the frozen replacement contracts. |
| Client impact | High: replace `GraphEditorScreen` string/node/resource switches with descriptor capabilities; make unknown nodes/data inspectable/read-only where needed; use one shared graph contract and typed resource locator. |
| Persistence impact | High: replace name/string-based wire/signature/structural persistence with immutable IDs; preserve legacy source verbatim for controlled conversion/quarantine; metadata remains presentation-only. |
| Migration impact | High: migrate Function parameter names to stable IDs and rewire every call; assign durable IDs to connections/branches/repeatable elements and map conditional/mode names deliberately; preserve unresolved graphs as quarantined/lossless opaque documents. |
| Compatibility impact | Supported clients need capability negotiation and read-only fallback. Existing client resource switches cannot be claimed compatible with arbitrary new resources. Existing `FlowGraph` versions and workspace patches require versioned bridge/offline upgrader fixtures before retirement. |
| Fixtures required | FX-005 extension add/unload/reinstall with an open graph; FX-006 Function rename/reorder/call wire fixture; a duplicate-equal-connection collaboration fixture; repeatable/conditional/family mode rename/reorder fixture; FX-012 unknown node/pin/inspector data through open-save-reconnect; revision conflict and stale workspace operation fixture; missing extension reinstall fixture; full-folder snapshot/restore and interruption fixtures from FX-010/FX-011. |
| Exact files owned | `ReSync/docs/node-replacement/reviewer-b/worker-2/findings.md`; `Remotely/src/test/java/redxax/oxy/remotely/replacement/evidence/b2/CurrentGraphRoundTripBaselineTest.java`; `Remotely/src/test/resources/fixtures/node-replacement/b2/**`. No production ownership is authorized at Gate 0A. |
| Unresolved risks | Exact persisted shapes of all `FlowNode` input maps and repeatable families need catalog-wide inventory; graph node opaque preservation has not been proven; extension handler use during unload needs runtime trace; no complete snapshot participant registry was found; workspace sequence semantics need concurrency fixture evidence. |

## Gate Trace Coverage

| Required trace | Evidence / absence |
|---|---|
| Extension add/unload | Concrete sequential registration/cleanup and jar scanning traced above. Atomic generation and execution drain are absent. |
| Graph open/save | `FlowStorage.getGraph` → serializer → cache; `FlowWorkspaceService.persistDocumentDurable` → resource/storage save traced above. |
| Execution touchpoint | Function execution names are bound in `FunctionCallSupport`; full generic executor ownership is NSR-006/Reviewer C territory. Current trace proves persisted Function identity reaches execution by name. |
| Revision conflict | Storage and workspace have separate revision/sequence concepts. A uniform graph mutation conflict envelope is absent from traced workspace messages. |
| Legacy migration | Normal `FlowStorage` construction calls `migrateLegacyAssets`; per-graph backup route traced. |
| Reconnect | Client channel reconnect delegates to `LiveDocumentChannel` and replays snapshots/operations by raw workspace target; lossless unknown/revision convergence is not proven. |
| Complete snapshot/restore | Explicitly absent: only asset transaction restoration and network-domain snapshot records were found, not coordinated complete-folder fencing/staging/activation. |

## Reviewer Questions

1. Should the shared target graph contract use one immutable `elementId` abstraction for connection, branch/case, repeatable item, Function parameter, and inspector field instances, with type-specific wrappers only where semantics require them?
2. The legacy client currently preserves unresolved plugin *descriptors*, but not demonstrated opaque graph-node structure. Should migration treat any graph containing unavailable/unknown node shape as opaque/quarantined until the lossless shared graph codec is proven?

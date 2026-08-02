# Current Architecture Dossier

Status: Gate 0A approved current-system baseline. Gate 0B target architecture remains unfrozen.

## Dependency And Contract Direction

ReSync includes `ReSyncCore` and `ReSyncVelocity`. Remotely source-composes ReSync and substitutes `restudio.resync:ReSyncCore`, so ReSyncCore can be the dependency-free shared owner. The current node protocol violates that direction: both ReSync and Remotely generate separate `ReSyncProtocolContract` classes from `Remotely/contracts/resync-protocol.json`, and ReSync's Gradle build reaches into the Remotely repository. Both products also hold mirrored node definitions, sync DTOs, graph DTOs, type models, and serializers.

## Startup And Shutdown

`ReSync.onEnable` constructs the plugin services, registers listeners and integrations, starts the ReSync server and WebSocket surface, and owns shutdown. Node/runtime initialization is distributed through server construction and Flow services rather than activated as one validated immutable generation. ReSync currently has no replacement-catalog preflight that can reject an invalid contribution while retaining a complete last-known generation.

## Catalog Discovery And Registry Merge

Bundled active definitions are recursively discovered below `src/main/resources/nodes`, including the `nodes/migrated` tree. `NodeDefinitionLoader` scans directories and jars, applies `applyCompatibilityTransforms`, normalizes legacy pins, supplies generated descriptions/tags/examples/safety/availability/options metadata, and validates/registers definitions one by one. `NodeDefinitionRegistry` is a mutable registry. Extension/plugin payloads and generated Function nodes reach related live registries through separate paths. Registration order and per-definition mutation are not a transactional contribution compiler.

The raw bundled baseline is 1,428 unique definitions in 61 JSON files with 7,278 pins. The active sources do not explicitly supply 1,237 node descriptions or 6,220 pin descriptions. Domain, lifecycle, inspector intent, and most schema versions are absent and inferred after load.

## Handler Binding And Execution

`HandlerRegistry` maps handler IDs to executable `NodeHandler` instances. Runtime behavior is spread across generic/family/property/event handlers. `FlowExecutor` resolves live definitions and handlers while traversing a mutable `FlowGraph`; `FlowRuntime.resolveDefinitionDefault` reads current definition defaults at execution time. Runtime APIs address pins by visible/string names and Function inputs by names. Execution is interpreted directly rather than compiled once against an immutable catalog snapshot into an owned execution plan.

## Graph Loading, Saving, And Resource Authority

`FlowStorage` owns many resource families, caches, legacy directories, asset indexing, project metadata reconciliation, graph type inference, typed tombstones, backups, direct migration, cleanup, and resource-specific list/load/save/delete paths in one large service. It performs startup reconciliation and migration through `migrateLegacyAssets`, `migrateLegacyFlows`, `migrateLegacyResources`, command reclassification, duplicate deletion/quarantine, and metadata-driven discovery. Some recent typed-resource protections exist, including `ReSyncResourceKey`, revisions, tombstones, collision-safe writes, and atomic asset support, but normal startup still contains legacy interpretation and repair.

Project metadata and stored graph payloads can both influence resource identity. Several caches and APIs accept ID alone. Graph/function/command separation has been improved but remains surrounded by inference and compatibility paths.

## Validation And Diagnostics

Node definitions use `NodeDefinitionValidator` and build validation, while graph validation uses `FlowGraphValidator` and rule registries. Editor diagnostics exist in ReSyncCore, execution traces exist in ReSync, and network/migration diagnostics have separate shapes. There is no one stable diagnostic contract spanning catalog, graph, execution, extension, protocol, migration, snapshot, restore, and reconciliation.

## Protocol And Synchronization

The protocol currently contains resource-specific packet assignments generated from a Remotely-owned JSON contract. ReSync and Remotely mirror `NodeRegistrySnapshot`, `NodeRegistryRequest`, `NodePluginPayload`, option/resource/property/type/conversion metadata DTOs. Resource families use dedicated packet routes. The WebSocket and RemotelyMod bridge surfaces carry additional envelopes. The current surface does not provide one capability-negotiated generic typed resource operation model for list/query/load/create/save/rename/move/duplicate/delete/subscribe/collaboration.

## Remotely Cache, Hydration, And Editing

Remotely has its own `NodeDefinition`, `NodeRegistry`, graph/node/connection/type/reference/serializer models, registry cache, and tombstone cache. `NodeRegistry` parses server snapshots into client-owned node models and maintains client-side fallback/discovery behavior. Flow editing is split across `GraphEditorScreen`, `FlowEditorScreen`, node widgets, option selectors, structural helpers, and specialized designers. The UI contains node/resource-specific routes and does not yet have one schema-driven inspector contract with generic sections, fields, structural identities, previews, fallbacks, and lossless opaque handling.

## Collaboration And Revision Conflicts

Workspace revisions and patches exist in ReSyncCore and Studio collaboration transactions exist in Remotely. Server mutations expose revision-conflict handling in portions of storage. The complete node/resource protocol does not yet guarantee that every event carries server/type/ID, revision, mutation ID, author, deletion state, and authoritative normalized payload hash. Cache ordering and conflict recovery therefore remain split by resource-specific implementation.

## Extension Lifecycle

Extensions can contribute node definitions and handlers, but contributions do not compile as one isolated `CatalogContribution` with dependency/collision/capability/migration validation followed by atomic generation swap. Unload and collision behavior must be traced per registry and handler path. Missing-extension graph data is not yet guaranteed to remain opaque and lossless through every client and server round trip.

## Migration, Snapshot, And Restore

`FlowGraphMigrator`, `TypedAutomationGraphMigrator`, `IdCompatibilityLayer`, `_id_migration_map.json`, loader transforms, startup asset migrations, save/load repair, and migrated JSON definitions form live compatibility paths. Flow migration can create per-graph backups and reports, but the system does not yet expose the plan-required coordinated all-participant snapshot, deterministic content-addressed dry run, fenced activation, journal recovery across every state, atomic whole-folder restore, or separately versioned offline upgrader unreachable from startup.

Network snapshot models exist in ReSyncCore for network state; they are not evidence of a complete ReSync-folder snapshot participant service.

## Required Reviewer Traces

Each reviewer must independently trace and cite concrete classes/methods for:

1. Adding and unloading an extension node.
2. Opening and saving a graph.
3. Executing a node and resolving defaults/conversions.
4. Handling a revision conflict and authoritative refresh.
5. Migrating a legacy graph.
6. Disconnecting/reconnecting a client and ordering cache updates.
7. Creating and restoring a complete snapshot.

Absence of a complete current path is recorded as a problem, not silently filled in with target design.

# Reviewer C Required Trace Signature

Status: **Trace coverage signed with recorded absences; Gate 0A domain evidence approved. Gate 0B unapproved.**

Date: 2026-08-02.

This signature accepts a trace when it reaches concrete current code or reproducibly proves that the complete current path is absent. It does not convert an absence into a capability or approve target behavior.

## 1. Add An Extension Node

`ReSyncExtensionManager.registerExtension` creates extension state, invokes initialization/start, exposes `ExtensionContext.flow()`, registers definitions and handlers through `NodeDefinitionRegistry` and `HandlerRegistry`, then refreshes the registry.

The C4 lifecycle sequence proves current add/reload primitives. Registration remains sequential and collision replacement is not one validated atomic contribution.

## 2. Unload An Extension Node

`ReSyncExtensionManager.unregister` removes state, stops the extension, and sequentially cleans definitions, runtime nodes, handlers, properties, catalogs, adapters, types/codecs, conversions, resources, validators, providers, listeners, and modules.

C4 proves immediate handler shutdown occurs before an already-entered invocation is released. No new-execution fence, provider lease, universal drain/cancel policy, or typed opaque-node preservation contract exists.

## 3. Open And Save A Graph

Open runs from Remotely resource request through server packet routing, `FlowStorage.getGraph`, integrity/deserialize, response, client cache, and editor application. Save runs through client serialization, server deserialize and live migration, validation, `FlowStorage.saveGraph`, revision check, asset transaction, cache/index/binding refresh, event/acknowledgement, and client saved-state update.

The path is traceable. It remains a composite route with live migration and compensating behavior, not one complete typed mutation.

## 4. Execute A Node

`FlowExecutor.execute` validates the supplied graph and `FlowRuntime` interprets nodes through dependency evaluation, thread policy, authorization, live definition and handler lookup, special loop/Function behavior, handler execution, pending operations, and string-keyed connection traversal.

C1 proves `resolveHandler` mutates node type/configuration from current definitions and defaults are resolved live. The current path is fully evidenced and does not satisfy an immutable-plan/provider-lease target.

## 5. Handle A Revision Conflict

`FlowStorage.saveGraph` throws `ResourceRevisionConflictException` for a stale positive revision. The graph handler reduces it to a reload instruction; generic handling emits an editor error. The authoritative current revision and payload are not uniformly returned, and no single recovery path proves draft preservation, refresh, merge, or convergence.

This is a signed current-path absence and remains a Gate 0B mutation/conflict requirement.

## 6. Migrate A Legacy Graph

Startup invokes `FlowGraphMigrator.migrateStoredFlows` and `TypedAutomationGraphMigrator.migrateStoredFlows`; ordinary graph saves also invoke live migration. Storage separately migrates/reconciles legacy assets. Per-graph backups and ledger entries exist.

The path proves live migration debt. It is not a deterministic complete-folder offline upgrader with coordinated snapshot, journaled activation, quarantine, and second-run proof.

## 7. Disconnect And Reconnect A Client

Remotely loads cached registry projections, resets synchronization on connect/disconnect, requests a checksum/delta registry snapshot, falls back to full data on base mismatch, applies and persists the projection, and refreshes editor state.

The registry path is traceable. Typed resource events, tombstones, open drafts, queued saves, mutation ordering, and unknown extension material do not share one proven ordered convergence envelope. C3 and adjacent Reviewer B fixtures preserve these current boundaries.

## 8. Create And Restore A Complete Snapshot

Current partial paths include asset transaction snapshots/restores, per-graph migration backups, and network-domain snapshots. The sixteen-participant matrix records owner, flush, snapshot, restore, and absence evidence across the wider persistence surface. ADR-007 supplies a source-pinned sanitized real-folder fixture plus deterministic populated participants.

No current service fences every writer, flushes a closed participant registry, manifests and hashes the complete folder, validates build/catalog/extension compatibility, stages all restored participants, snapshots current state first, and atomically activates a journaled restore. This reproducibly proved absence is NSR-015 evidence, not a Gate 0A evidence gap.

## Signature Decision

All eight required flows terminate in concrete current methods or a reproducibly recorded absence. C1 and C2 pin the runtime definition/family scopes; C3 pins automation/resource lifecycle identity; C4 pins extension concurrency, cancellation, opaque-data, provenance, and timing behavior. Reviewer A and B independently approve their adjacent evidence, ADR-007 through ADR-009 close fixture, compatibility, and execution-policy blockers, and the final A+C XML records 29 of 29 passing tests with no failures, errors, or skips.

Reviewer C therefore signs and approves Gate 0A current-system evidence for Runtime Domains And Semantic Parity. Gate 0B remains unapproved and unfrozen; this signature authorizes no production implementation, migration, package, publish, deploy, or target contract.

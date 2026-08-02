# Reviewer B Gate 0A Synthesis

Status: current-system evidence synthesized and Gate 0A approved for graph interaction and client independence. No production code was changed. Focused evidence tests were added and run under ADR-009; shared contracts remain draft and no Gate 0B design is frozen.

## Evidence Reviewed

- `worker-1/remotely-model-cache-hydration-findings.md`: mirrored client models, registry/cache hydration, tombstones, resource ordering, server scope, and metadata reconciliation.
- `worker-2/findings.md`: graph document/canvas/serialization, dynamic pins, Functions, branches, repeatables, collaboration structure, and immutable-identity defects.
- `worker-3/gate-0a-selector-designer-evidence.md`: option catalogs, raw resource selectors, descriptions/hints, and the specialized editor/preview/save inventory. Its first submission was rejected because several designer routes were untraced; the revised submission traces them.
- `worker-4/gate-0a-save-collaboration-compatibility-trace.md`: mutation/conflict, collaboration, disconnect/reconnect, opaque fallback, export/import absence, complete restore absence, and repository-history compatibility evidence.
- `worker-1/client-independence-inventory.md`: exact generated Remotely, RemotelyMod, and protocol branch/model/cache inventory.
- `worker-2/findings.md`: recursive unknown-data preservation, normalization, and loss golden.
- `worker-3/gate-0a-selector-designer-evidence.md`: deterministic registry wire/cache hydration baseline and proposed comparison budgets.
- `worker-4/specialized-designer-mutation-matrix.md`: bounded specialized designer/save/conflict/reconnect matrix and six-fixture current-head manifest.
- Reviewer-owned source traces in ReSync, ReSyncCore, and Remotely, plus read-only structural inspection of `ReSync/run/plugins/ReSync`.

## Current Architecture Synthesis

### Contract And Protocol Boundary

ReSync and Remotely independently own graph, node, connection, descriptor, registry snapshot, plugin payload, type, reference, option, and serializer models. ReSync also generates protocol constants from `Remotely/contracts/resync-protocol.json`, while Remotely generates its own copy. The client closed enum `ReSyncResourceType` maps 21 resource types to dedicated packet families; the current generated surface allocates seven CRUD-related bytes per resource type. Ordinary resource additions therefore touch client routing and mirrored protocol tables.

The client graph codec preserves unknown properties only at the graph root. No recursive preservation contract was found for unknown node fields, connections, Function parameters, structural elements, inspector state, types, widgets, or extension-owned data. The B2 golden makes this boundary executable: unknown root and generic-map values survive, generic numbers normalize to doubles, while unknown fields on typed nodes, connections, Function parameters/type references, and passthroughs are discarded. Its canonical current output SHA-256 is `35887bee10bd87b48a54b6ba4515439e7c4ec25224db222440b5c7005daa25d4`.

### Registry, Cache, And Hydration

`ReSyncFlowClient.handleNodeRegistrySnapshot` validates a mirrored `NodeRegistrySnapshot`, then `NodeRegistry.applySnapshot` mutates per-server maps and rebuilds client definitions. `NodeRegistryCache` persists the reconstructed snapshot. `NodeRegistryTombstoneCache` preserves removed plugin descriptor payloads by server and plugin ID; it is not a resource tombstone/revision cache.

Resource events contain type, ID, payload, author, and timestamp, but omit server locator, resource revision, mutation ID, normalized hash, and deletion revision. `ReSyncFlowClient.handleResourceEvent` immediately caches a payload or deletes the local projection, so there is no evidence that a delayed event cannot overwrite a newer acknowledged state. `SyncedResourceCache` concatenates server ID and resource ID into a string key; `TypedGraphCache` partitions graph types but retains ID-only/unique lookup surfaces.

`FlowManager.hydrateProjectMetadata` removes missing graph entries, infers graph type from payload flags/current bindings, and adds cached resources into presentation metadata. It conditionally persists repaired metadata. This makes client hydration an identity/reclassification influence instead of a projection of acknowledged typed server state.

The deterministic B3 fixture contains 1,024 nodes across 8 plugins, 24 types, and 32 option sources. Its canonical wire payload is 838,733 bytes (`89c9ad56e0b04a59dc614b456af4b96ceae1ecf14e751de680aff779077801ad`); the cache-restored projection is 838,779 bytes (`4e9308b326a6a2778ec5ac505df595fc36da2455e4875362764ee50171ca5abf`), and the disk cache is 738,563 bytes. Current-head hydration measured 478,700 ns median / 727,700 ns P95; cache projection measured 35,800 ns median / 48,600 ns P95. Memory delta was zero in this run and remains advisory, not a stable assertion.

### Graph Document And Interaction

Connections persist source/target node IDs and pin names. Passthroughs persist node/pin strings. Conditional visibility is keyed by pin names. Repeatable pins synthesize numeric suffixes and private count keys. Canvas branches persist output names. Function parameters contain mutable name/type/widget/source/default fields but no parameter ID; generated Function call pins are sorted and bound by parameter name.

`NodeWidget` contains direct node-ID and option-source behavior for Function aliases/calls, Variable/Timer/Schedule nodes, scheduled-task/event aliases, and `flow.switch_case`. It persists private keys such as `__flow_branches`, `__repeatable_count:*`, `__removed_optional_inputs`, `__call_parameters`, and `__function_signature`. `GraphEditorScreen` contains resource-drag type switches and treats selector pins named `mode` or `action` as family behavior. These paths prove that ordinary node, structural, resource, and selector changes are not client-independent.

The B1 Java scanner pins 2,621 exact lexical records across the declared Remotely, RemotelyMod, and protocol-owner scope with SHA-256 `f3ef80122fb10b5a6a9bbe51cad279899f6e4976cc5df83d7a5031ac4a2bbc49`. The inventory includes explicit controls for direct node-type, pin-name, and closed resource-enum paths and documents its AST/runtime blind spots.

Workspace collaboration gives operations a base sequence and operation ID. `/connections` is diffed by whole JSON-value equality; other structural arrays use positional patches. Workspace sequence/idempotence is separate from durable resource revision, mutation ID, and resource-event ordering.

### Selectors, Descriptions, Inspectors, And Designers

Server option catalogs can return rich items and source-level revision/sequence information, but `OptionCatalogItem.value` is a string. Client selectors write that string into `FlowNode.inputValues` keyed by pin name; server validation compares string values. Selectable ReSync resources therefore lose server/type/revision identity even when the option item contains richer presentation metadata.

Raw catalog evidence remains 1,237 missing node descriptions and 6,220 missing pin descriptions. `NodeDefinitionLoader` and the descriptor builder generate fallback descriptions and infer selector semantics. The node selector can show a node-level hint, but `NodeWidget` does not present pin descriptions, required/default state, or conditional explanations through one shared hover surface.

The current editor inventory has several persistence planes:

- GUI, scoreboard, and tab designers have specialized draft/preview/save paths.
- Advancement, dialog, and focused JSON designers save through JSON-resource routes.
- Trade, NPC, loot, recipe, chat, message-rule, MOTD, text-template, Variable, Timer, and Schedule screens share `FocusedJsonResourceDesignerScreen`, yet remain selected by client resource switches and lack a uniform expected-revision/draft-conflict path.
- `WorldDesignerScreen` emits several independent world-management mutations, so one visible Save is not one atomic resource mutation.
- `ContentDesignerScreen` saves through graph/custom-content or quick-edit paths.
- `ItemIconPreview` is preview-only and has no persistence path.

These are useful existing visual capabilities, but they do not share one generic inspector draft, validation, conflict, mutation, description, fallback, and opaque-data boundary.

### Save, Conflict, Collaboration, And Reconnect

Client resource saves serialize a resource-specific payload plus request ID. Graphs may carry revision/hash fields in the payload. Server graph storage can reject a stale positive revision with `ResourceRevisionConflictException`, and successful ACKs return revision/hash. Conflict responses discard the exception's expected/current revision fields and do not return the authoritative payload; the client receives a reload instruction.

On disconnect, option requests are cleared/staled and collaboration/workspaces disconnect. On successful handshake, subscriptions, collaboration, and workspaces reconnect; the client requests the registry/jobs, then flushes queued save closures. No global ordering is established between registry hydration, resource lists, resource events, workspace snapshots, and queued mutations, and no queued-save rebase against newly authoritative revision is proven.

### Extension Lifecycle

`ReSyncExtensionManager.registerExtension` exposes independent node, handler, property, option, type, conversion, resource, validator, provider, listener, and module registrations. `NodeDefinitionRegistry.register` can displace a definition owned by another plugin by registration order. Unloading sequentially unregisters subsystems and does not restore a displaced prior definition. No catalog-generation swap, in-flight handler lease/drain, or complete opaque graph round trip is present.

The client does preserve an unresolved plugin descriptor payload after removal, but that does not prove recursive graph node/configuration/wire preservation through load, edit, save, export, reconnect, and reinstall.

### Migration, Export/Import, Snapshot/Restore, And Compatibility

`FlowBlueprintPacketHandler.handleSave` invokes `FlowGraphMigrator.migrateGraph` during an ordinary save. `FlowStorage` performs startup legacy discovery, metadata reconciliation, type inference/reclassification, and directory migration. Existing backups and `AssetTransactionManager` snapshots are per graph or per asset transaction. Network snapshot classes concern network/player state. No generic graph export/import operation or complete fenced ReSync-folder snapshot participant service, verified manifest, compatibility preflight, staged atomic activation, or whole-folder restore journal was found.

ReSync has no release tags. Remotely's only `BETA` tag predates the current flow/resource/workspace system and is not a protocol release. Repository history cannot establish a supported-client window. Exact supported Remotely builds and contract generations remain product-owner evidence.

The local `run/plugins/ReSync` instance has an empty extension root and `assets/project.json` contains no resources. It proves multiple persistence roots exist, but cannot serve as a graph, extension, missing-provider, migration, or restore fixture without orchestrator allocation and sanitization.

## Consolidated Problem Set

Existing ledger coverage: NSR-001 through NSR-003, NSR-005 through NSR-011, and NSR-013 through NSR-018. NSR-004 and NSR-012 are adjacent catalog/family retirement problems owned primarily by Reviewers A and C.

The following are Reviewer B local identifiers only. The main orchestrator owns stable shared NSR allocation.

| Local ID | Current problem proof | Existing ledger relationship |
|---|---|---|
| B-P001 | Resource events omit ordering/tombstone fields and immediately mutate cache projections. | NSR-009, NSR-017 |
| B-P002 | Conflict responses omit authoritative revision/payload even though the server exception holds revisions. | NSR-009, NSR-017 |
| B-P003 | Disconnected save closures flush after handshake without a proven hydration/rebase boundary. | NSR-009, NSR-017 |
| B-P004 | Workspace sequence/idempotence is separate from durable mutation revision and event ordering. | NSR-008, NSR-017 |
| B-P005 | Client metadata hydration removes/adds/reclassifies presentation entries from cache and bindings. | NSR-007, NSR-009 |
| B-P006 | Connections, pins, modes, branches, repeatables, passthroughs, and Function parameters use mutable names/positions. | NSR-013 |
| B-P007 | Selectable resources persist raw option strings instead of complete typed locators. | NSR-009 |
| B-P008 | NodeWidget/GraphEditor contain node-ID, pin-name, option-source, and resource-type special cases. | NSR-010 |
| B-P009 | Generated/fallback documentation hides raw description gaps; shared pin hints are absent. | NSR-003, NSR-011 |
| B-P010 | Specialized editors use several save/validation/conflict routes; World Save is multi-operation. | NSR-010, NSR-017 |
| B-P011 | Unknown preservation is graph-root-only; supported read-only opaque node/editor fallback is unproven. | NSR-017, NSR-018 |
| B-P012 | Cross-plugin overwrite and sequential extension cleanup are not atomic and lack execution ownership. | NSR-005, NSR-016 |
| B-P013 | Concatenated and ID-only client cache lookup surfaces remain. | NSR-009 |
| B-P014 | Generic graph export/import and complete coordinated snapshot/restore are absent. | NSR-015 |
| B-P015 | Exact supported-client/release bounds cannot be established from repository tags/history. | NSR-017, NSR-018 |

## Current-To-Target Classification

This records plan-governed outcomes, not a frozen Gate 0B design:

- Replace mirrored descriptor/graph/type/option/protocol authority with the sole ReSync-owned shared contract boundary.
- Replace ID-only/raw-string selectable identity, name/position structural identity, unordered cache/event updates, and resource-family packet routing.
- Delete node/resource-ID-specific client behavior where descriptors/capabilities can express it.
- Adapt the useful canvas, focused editor, and rich preview behavior only through the future shared draft/mutation/validation/description/fallback boundary.
- Delete live save/startup migration paths after the controlled standalone conversion.
- Retain/adapt proven revision, typed-key, tombstone, atomic-asset, workspace-sequence, and local preview behavior only where it conforms to the frozen replacement contracts.

## Required Fixtures And Evidence

The B-owned generated inventory, recursive round-trip golden, registry payload/cache fixture, six B4 graph/mutation fixtures, and designer matrix now provide the reproducible current-head baselines for this domain. ADR-007 assigns the real persisted-folder source and sanitized full-folder evidence to the shared fixture owner; B references that authoritative evidence rather than duplicating it. The B4 export/import fixture records a proved absence boundary and deliberately does not pretend an unavailable feature can be exercised.

FX-005 through FX-008 and FX-010 through FX-012 remain required replacement acceptance cases for Function rename/reorder, equal duplicate connections, repeatable/branch/mode reordering, typed same-ID resources, stale mutation streams, conflict/reconnect, unknown provider material, extension reinstall, and specialized editors. They are Gate 0B/implementation obligations, not missing current-system Gate 0A evidence.

## Gate 0A Domain Decision

Reviewer B **approves Gate 0A for this domain** at the ADR-008 heads. The resumed work closed the former evidence gaps with an exact generated client inventory, recursive loss/normalization golden, deterministic registry payload/cache hydration baseline, bounded designer matrix, current-head fixtures, explicit export/import and complete-restore absence evidence, ADR-007 real-folder ownership, and the product-approved compatibility window.

The approval is evidence-only. Gate 0B must still freeze immutable graph identity, opaque-data, typed locator, mutation/conflict/event ordering, extension generation/drain, generic editor capability, snapshot/restore, migration, and compatibility contracts before production replacement begins. Proposed baseline comparison budgets are payload and median latency no more than 1.25x current, P95 latency no more than 1.5x current, using the same deterministic fixture and Java 21 environment.

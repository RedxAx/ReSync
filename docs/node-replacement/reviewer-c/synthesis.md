# Reviewer C Gate 0A Synthesis

Status: **Runtime-domains current-system evidence approved for Gate 0A. Gate 0B remains unapproved and unfrozen.**

## Scope And Evidence Reviewed

Reviewer C independently read the complete governing plan and evidence pack, traced the current runtime seams, and reviewed four disjoint worker reports:

- C1: `worker-1/findings.md` and `worker-1/trace.md` — execution, validation, handler binding, threading, defaults, conversions, Functions, branches, and mutable execution.
- C2: `worker-2/findings.md` and `worker-2/trace.md` — Player, Entity, World, Block, Inventory, Item, and Particle families.
- C3: `worker-3/findings.md` and `worker-3/trace.md` — resources, content, automation, events, schedules/jobs, network, permissions/economy/integrations, persistence, and triggers.
- C4: `worker-4/findings.md` and `worker-4/trace.md` — extension lifecycle, unload/concurrency/cancellation, authorization, diagnostics, observability, and representative performance evidence.

All production source and the real `run/plugins/ReSync` source remained read-only. Scoped evidence tests and test-owned outputs were permitted by ADR-009. No server command, package, publish, deploy, or production edit was performed.

## Final Evidence Closure

- C1 pins 1,428 definitions, 3,584 physical inputs, 3,689 physical outputs, 723 defaults, and five logical omitted inputs. Its normalized source hash is `140445208ee691fdf5e20fdc259765f335807e468bc2695fa438547e18e1cd73`; the durable matrix file hash is `eba63a1c9470af9be4a736b66208b0c4ddc0b00b7c2fc199feb534cb3bbfd8b1`. Mutable execution evidence proves graph mutation and live definition/default binding.
- C2 covers exactly 497 assigned definitions and 2,833 physical pins. Every unproved semantic or canonical pin mapping is explicitly `quarantine-required`; raw option arrays and default JSON types/values are preserved exactly. The durable matrix file hash is `9b3451bb3f9c516342479c61e4f7d7209e406a3d1cba14740a3b48a2af367fb5`.
- C3 pins trigger IDs, persistent automation definition/owner identity, revision/catalog/plan absences, typed local keys, and network mutation type/revision/deleted/payload fields. It also records the absent server locator and mutation ID. Trigger order is intentionally treated as an ID set because the current `ConcurrentHashMap` value order is nondeterministic.
- C4 proves collision overwrite, immediate handler shutdown, shutdown-before-in-flight-release, sequential add/unload/reload, incomplete cancellation ownership, graph-root opaque preservation with node-level extension loss, and missing trace provenance. Its accepted root-run nanosecond samples are: registry median `14800`, P95 `32700`; serializer median `60300`, P95 `105400`; trace median `6200`, P95 `14500`, each over 250 samples. These are current-environment baselines, not Gate 0B budgets.
- The adjacent recursive serialization golden records the exact current boundary: graph-root and generic-map material survive, generic numeric values normalize, and unknown typed node/connection/Function/type-reference/passthrough fields are lost. Its canonical SHA-256 is `35887bee10bd87b48a54b6ba4515439e7c4ec25224db222440b5c7005daa25d4`.
- ADR-007 evidence pins the sanitized real-folder fixture and deterministic populated supplement. The authoritative full-folder manifest SHA-256 is `a448615bf5aa3a2cdd05379485150b0362f15eae13da842dfcddc4fa0f998bb1`. ADR-008 limits compatibility to ReSync `b2941e66d762ffb76391715fbf4487c068fe12ea` and Remotely `76c4eb8989aaf856a4f002fa7fe6e56a67c373df`.
- The root-qualified selected verification produced nine XML suites with 29 tests, 29 passed, zero failures, zero errors, and zero skipped. The accepted C4 measurements above come from that same final output.

## Current Runtime Architecture

### Construction And Registration

`FlowRuntimeModule` constructs mutable `NodeDefinitionRegistry`, `HandlerRegistry`, `PropertyRegistry`, option/type/resource registries, `FlowGraphValidator`, and `FlowExecutor`. `NodeDefinitionLoader` recursively loads the active `nodes` tree, including `nodes/migrated`, applies compatibility transforms and inferred metadata, and registers definitions individually. Generated Function descriptors are rebuilt separately from persisted Function graphs.

`NodeDefinitionRegistry.register(pluginId, definition)` replaces a colliding definition and transfers its owner instead of rejecting the contribution. `HandlerRegistry.register` shuts down a replaced handler before installing the next instance. There is no immutable catalog generation spanning definitions, handlers, types, conversions, validators, resources, and extensions.

### Execution

`FlowExecutor.execute` validates the supplied mutable `FlowGraph`, constructs `FlowRuntime` around the same graph reference, and interprets one node at a time. It resolves current definitions, handlers, handler operations, thread policy, authorization, defaults, conversions, structural routes, and Function behavior during traversal.

`FlowExecutor.resolveHandler` maps a legacy ID and then calls `FlowNode.setType` and `FlowNode.setHandlerConfig` from the current definition. Execution therefore mutates the caller's graph and makes saved semantics depend on current registry state. `FlowRuntime.resolveDefinitionDefault` looks up current descriptor defaults by string pin name. Connections, output cache entries, branches, loop pins, repeatable pins, Function inputs/outputs, and generated Function signatures use mutable names or positional conventions.

Handler thread policy is `MAIN`, `ASYNC`, or `CURRENT`. Scheduler transitions and some pending tasks are tracked, but there is no execution-plan/provider lease or universal cancellation token. Duration is checked after node completion. The event-mutation window closes after the initial future is created; the observable risk across later thread transitions remains a fixture requirement rather than a proven regression.

### Domain Families

C2 accounts for 497 assigned definitions and 2,833 pins across twelve JSON sources. All 497 nodes lack authored descriptions. Of the 2,833 pins, 1,013 have raw authored descriptions and 1,820 do not. Dispatch is syntactically accounted for as 161 dynamic property-family definitions, 295 dedicated-handler definitions, and 41 restored-handler definitions.

The family surface is not semantically closed. Player, Entity, World, Block, Inventory, and Item behavior is divided among reflective `JsonFamilyHandler`, `PropertyRegistry`, dedicated action handlers, and restored handlers. Particle has one canonical apply family plus sixteen live legacy shape routes. World state and destructive/durable world-management operations share one handler surface despite different authorization, persistence, and failure semantics.

The blocking C2 inventory gap is a deterministic definition-to-runtime parity matrix proving every definition/property/action/type against its real handler operation, target type, declared pins, binding path, effects, thread policy, branches, failure form, and canonical replacement.

### Resources, Automation, Triggers, And Integrations

`FlowStorage` has useful graph revision, mutation ID, typed tombstone, integrity, and atomic graph-plus-project-metadata behavior. It also performs startup legacy discovery, repair, reclassification, duplicate cleanup, and migration. JSON resources, custom content, triggers, automation task state, network manifests, world management, and extension data use separate lifecycle and persistence routes with uneven revision/mutation/tombstone metadata.

`TriggerRegistry` persists raw flow IDs, types, and contexts in `triggers.json`; `TriggerDispatcher` separately maps raw flow IDs to start-node IDs and probes visible pin names for automation matching. `AutomationTaskService` persists task state against raw definition IDs without a definition revision, catalog generation, or compiled-plan identity. Permission, economy, network, and custom-content handlers use provider-specific availability, authorization, output, failure, cancellation, and durability conventions.

`FlowBlueprintPacketHandler.handleSave` deserializes and live-migrates a graph, persists it, then separately updates content/trigger bindings and uses compensating restore on later failure. This is not one complete typed mutation. Resource-specific protocol and acknowledgement behavior remains alongside `FlowResourceRegistry`/`FlowResourcePacketRouter` abstractions.

### Extension Lifecycle

`ReSyncExtensionManager.registerExtension` inserts extension state and lets initialization register definitions, handlers, types, conversions, resources, validators, option catalogs, listeners, modules, and providers one at a time. Cleanup unregisters those surfaces sequentially. Observers can see partial state; a collision can displace another definition; unload immediately shuts down handlers without fencing new executions or draining in-flight work.

Graph-level unknown fields have some serializer preservation, but no trace proves that an unavailable extension node's owner/version, opaque node fields, inspector state, literals, and wires survive server load/save, client hydration, reconnect, export, and reinstall.

### Diagnostics And Performance

Runtime traces, audits, editor diagnostics, migration reports, network diagnostics, and storage health use separate contracts. Existing trace metrics are bounded and node-type oriented; they do not carry a catalog generation, handler owner/capability, typed resource locator, cross-phase correlation, or durable extension lifecycle report.

Reproducible current/proxy samples now cover catalog parsing, deterministic registry projection and hydration, graph deserialize/validation, fixture-copy snapshot/restore, registry operations, serialization, and trace recording. Immutable-plan overhead, live extension activation/unload, provider drain/cancellation, coordinated migration/restore, stable memory allocation, and interactive behavior remain unavailable. Gate 0B performance budgets therefore cannot be frozen from these Gate 0A evidence coordinates.

## Problem Reconciliation

Existing shared ledger findings directly evidenced by Reviewer C are NSR-002 through NSR-018, with strongest runtime proof for NSR-003, NSR-005, NSR-006, NSR-007, NSR-009, NSR-012, NSR-013, NSR-014, NSR-015, NSR-016, NSR-017, and NSR-018.

The central ledger now carries the consolidated runtime findings through NSR-032. The original C-P labels below are retained only as worker-evidence provenance; they do not create a second problem namespace:

| Local ID | Finding |
|---|---|
| C-P01 | Execution mutates `FlowNode.type` and `handlerConfig` from the live definition. |
| C-P02 | Descriptor defaults and handler bindings are resolved live, so catalog changes can alter saved semantics. |
| C-P03 | Function parameters, connections, branches, repeatables, outputs, and templates use names/positions as structural identity. |
| C-P04 | Definition/handler reload lacks immutable plan ownership and provider drain. |
| C-P05 | Event-mutation availability across thread transitions is a code-path risk requiring a fixture. |
| C-P06 | Property registries, reflective family handling, dedicated handlers, and restored handlers overlap without canonical semantic ownership. |
| C-P07 | Family modes, selectors, operations, pins, visibility, and canonical redirects are string/name driven. |
| C-P08 | Effects, thread policy, cancellation, idempotency, authorization, failure, and audit semantics are absent from descriptors. |
| C-P09 | Reflective property dispatch can disagree with advertised type/action metadata and fail only at runtime. |
| C-P10 | Particle legacy shapes remain executable through live temporary-node remapping. |
| C-P11 | Ordinary World behavior and destructive/durable World Management share one handler surface. |
| C-P12 | Trigger persistence and dispatcher binding use independent raw identities and bulk replacement semantics. |
| C-P13 | Persistent automation tasks bind raw definition IDs without definition revision or compiled-plan identity. |
| C-P14 | Resource lifecycle metadata is uneven across graph, JSON/content, and network persistence routes. |
| C-P15 | Event matching depends on mutable graph node IDs, aliases, and visible pin names. |
| C-P16 | Integration availability, effects, authorization, cancellation, and failures are handler-specific. |
| C-P17 | Blueprint save is a composite compensating sequence, not one typed resource transaction. |
| C-P18 | Extension registration/reload/removal is a sequence of independent live mutations. |
| C-P19 | Handler replacement/unload has no owner lease and can race in-flight execution. |
| C-P20 | Cancellation covers selected tasks, not every execution/provider-owned asynchronous operation. |
| C-P21 | Runtime observability lacks shared durable provenance/correlation and representative baselines. |
| C-P22 | Missing-extension opaque node and wire round-trip is not demonstrated end to end. |

C-P04/C-P19 and portions of C-P06/C-P18 overlap conceptually and are reconciled in the central NSR ledger rather than duplicated.

## Required Cross-Domain Decisions For Gate 0B

1. Freeze one atomic catalog-contribution and runtime-binding ownership model. It must reject collisions before activation, pin in-flight plans to provider leases, and define drain/cancel/security-revocation behavior.
2. Freeze graph structural identity and migration rules for pins, branches, repeatables, Function parameters, modes, and handler operations before any domain conversion.
3. Freeze typed trigger/automation identities and decide whether runtime task restoration binds a resource revision, compiled-plan checksum, or another immutable execution identity.
4. Freeze one complete typed mutation boundary spanning payload, metadata, revision, mutation ID, derived runtime bindings, tombstones, network records, diagnostics, and acknowledgement.
5. Freeze lossless unavailable-extension behavior across server and Remotely serialization before declaring extension compatibility.
6. Freeze the diagnostic/provenance contract and representative performance workloads before setting Gate 0B budgets.

## Recorded Current-System Absences And Gate 0B Inputs

- The exhaustive C2 matrix deliberately quarantines all 497 definition semantics and 2,833 pin mappings that current artifacts cannot prove. This is a closed Gate 0A evidence result, not semantic approval.
- Revision conflicts do not uniformly return authoritative revision and payload, and reconnect/resource event paths do not prove complete ordered convergence.
- The current extension lifecycle has no atomic contribution, provider lease, execution drain, or lossless typed-node opaque round trip. The C4 lifecycle and adjacent missing-provider fixtures preserve that limitation.
- No complete coordinated ReSync-folder snapshot/restore service exists. The sixteen-participant matrix and deterministic populated fixture prove the boundary and the absence without claiming current capability.
- Cancellation, diagnostics, provenance, stable structural identity, immutable execution plans, and typed mutation ownership remain unresolved target requirements.
- The recorded timing samples are evidence coordinates only. Representative target workloads and budgets require an explicit Gate 0B decision.

## Reviewer C Decision

Reviewer C **approves Gate 0A current-system evidence for Runtime Domains And Semantic Parity**. The evidence exhaustively records the declared definition/pin scopes, current mutable execution and lifecycle behavior, identity and persistence boundaries, measurable baselines, and reproducibly absent paths. Approval means the replacement problems are proved; it does not claim the current runtime satisfies the target invariants.

Gate 0B remains unapproved and unfrozen. No target contract, intentional behavior, compatibility expansion, production implementation, migration, packaging, publishing, or deployment is authorized by this signature.

# C3 Gate 0A Findings: Resources, Content, Automation, Events, Network, Integrations

Status: pre-implementation evidence only. No production files changed and no test, Gradle, or server command was run.

## Scope And Current Architecture Traced

The domain has several independent durable and runtime routes rather than one resource lifecycle.

- Graph resources (`flow`, `function`, `command`) are loaded, saved, reclassified, deleted, revision-checked, and tombstoned by `src/main/java/restudio/resync/flow/FlowStorage.java`: `getGraph` (109), `saveGraph` (220), `deleteGraphFiles` (413), and `writeGraphTombstone` (1443). `saveGraph` commits the graph and edited `assets/project.json` through `AssetTransactionManager`, increments the resource revision, and assigns a mutation ID. It also infers or changes graph type from current payload/state. The caches and some helpers are keyed by strings, including a typed `type + '\0' + id` asset index key but an ID-only graph-change callback and graph methods with unspecified type.
- `FlowStorage` performs normal-startup structural repair: its constructor calls `migrateLegacyAssets` (80--102). That path reconciles project metadata with files, reclassifies command graphs using `triggers.json` and graph starts, imports legacy flows/resources/content, prunes missing metadata, and can delete known legacy directories (1539--2171). This is a live migration path, not a fenced snapshot transaction.
- JSON-defined resources use a separate `ReSyncJsonResourceStorage` over one `JsonAssetStore` per catalog type. `get`, `save`, `delete`, and `reload` accept `(type, id)` and notify listeners as `(type, id, value, deleted)` without revision, mutation ID, server scope, payload hash, or durable tombstone. `migrateLegacyAssets` also normalizes recipe files during normal operation. `AutomationDefinitionRegistry` obtains variables, timers, and schedules directly from this storage by raw ID.
- `FlowResourcePacketRouter` constructs resource-specific adapters for graphs, GUIs, scoreboards, tabs, custom content, project metadata, JSON resources, WorldGen, and worlds. `FlowResourceRegistry` has nominal common operations, but routes mutable adapters and string `(type,id)` references. The catalog has dedicated flow packet assignments (`ReSyncResourceCatalog.BY_FLOW_PACKET`), preserving resource-family protocol coupling.
- Automation definitions live in JSON resources, while active task state is separately persisted as `runtime/automation-tasks.json`. `AutomationTaskService` indexes active tasks by `AutomationInstanceKey` and random task ID, persists only selected persistent entries, restores on startup, and publishes Bukkit `TimerEvent`/`ScheduledTaskEvent`. It restores definitions by ID and logs/restores around missing or invalid definitions rather than producing durable resource diagnostics/quarantine.
- `TriggerRegistry` is a third store: `triggers.json` is a complete JSON array keyed in memory only by mutable binding `id`. Each `TriggerBinding` stores raw `flowId`, enum type, and raw context. Whole-list replacement (`setBindings`) overwrites all types; preservation methods try to retain selected enum types. `TriggerDispatcher` separately keeps runtime maps `event type -> (flowId -> startNodeId)`; registration, dispatch, and automation matching use raw strings and aliases.
- Server graph DTOs and serializer are independently defined in `src/main/java/restudio/flow/data/FlowGraph.java`, `FlowNode.java`, `FlowResourceReference.java`, and `FlowSerializer.java`. `FlowBlueprintPacketHandler` deserializes that server model, runs `FlowGraphMigrator` during save, writes through `FlowStorage`, and then separately updates graph bindings. This is an additional protocol/persistence seam beside `FlowResourcePacketRouter`; it is evidence for NSR-002/006/007 rather than an approved replacement contract.
- The protocol transport has further dedicated routes: `FlowBlueprintPacketHandler` handles flow save/delete/trigger update, `FlowNodeRegistryPacketHandler` owns registry/option payload behavior, `FlowPacketSender` contains distinct resource list/save-ack senders and ID-only list payloads, and `ReSyncProtocolInventory` reflects the generated protocol constants. `ReSyncServer` owns the WebSocket handshake/channel layer. These packet seams make resource identity and acknowledgement behavior route-specific.
- Custom content has another durable path (`CustomContentStorage` plus adapters) and runtime side effects through `CustomContentService`/`CustomContentListener`; the packet router explicitly refreshes its catalog after mutation. The generic `CustomContentHandler` selects content values from graph inputs and invokes that runtime path. This means content payload durability and item/player reconciliation are independent of graph/resource revision transactions.
- Permission and economy nodes are handler operation maps (`PermissionHandler`, `EconomyHandler`), backed by externally installed providers and strings such as permission/group/account/currency values. They mutate/query provider state at execution time and expose operation-specific `success`/`error` pins; there is no persisted provider capability identity or uniform authorization/audit transaction.
- Network resource synchronization is another complete state machine. `NetworkResourceSynchronizer` observes `FlowResourceRegistry` mutations, keeps a local manifest, serializes each adapter, publishes `NetworkResourceMutation(type, resourceId, expectedRevision, payload, deleted)`, reconciles on connect, and applies remote saves/deletes through the registry. Its per-key queue and revision retry are useful current behavior, but identity is still `type/id` without server locator or mutation ID, while the manifest governs tombstone knowledge separately from FlowStorage tombstones.

## Problem IDs Addressed

Existing ledger items evidenced by this slice: NSR-006, NSR-007, NSR-008, NSR-009, NSR-012, NSR-014, NSR-015, NSR-016, NSR-017, NSR-018.

Local C3 evidence IDs (reviewer reconciliation required; do not append directly to the central ledger):

- C-P12 — Trigger bindings are independent raw-ID persistence. `TriggerRegistry` overwrites its entire map in `setBindings`, and `TriggerBinding` carries only `flowId`/type/context. The dispatcher maps `flowId -> startNodeId` outside that store. Saving or reclassifying a command/flow is therefore not one atomic typed-resource-and-trigger mutation; aliases and missing start nodes are resolved at dispatch time.
- C-P13 — Automation definitions and automation task runtime state are split by raw definition ID. `AutomationDefinitionRegistry` reads `ReSyncJsonResourceStorage` by `(type,id)`, while `AutomationTaskService.PersistentTask` preserves only `definitionId`, scope/owner strings, arguments and signature version. Restart restoration can fail after definition mutation/deletion without a revision-bound plan, durable quarantine record, or typed locator.
- C-P14 — Resource lifecycle metadata is uneven across storage families. Flow assets have revision/mutation ID/tombstones, JSON/custom content resources expose listener events without those fields, and network manifests have separate revision/deleted/hash state. A cross-family mutation cannot presently be proved as one durable operation or subscribed to through one ordered event shape.
- C-P15 — Event binding semantics depend on mutable graph node IDs and raw visible pin names. `TriggerDispatcher.matchesAutomationBinding` probes `variable`, `timer`, and `schedule`, recognizes `event.schedule` by node type, and resolves legacy aliases. Rename/reorder/schema conversion and absent-node behavior are not guarded by immutable descriptor identities.
- C-P16 — Integration side effects are handler-specific and capability availability is implicit. Permission/economy/network/custom-content operations decide failures through individual maps and output conventions, with no common provider lifecycle, authorization/audit declaration, cancellation policy, or stable diagnostic contract.
- C-P17 — Flow blueprint save is a composite, compensating sequence rather than a single typed transaction: `FlowBlueprintPacketHandler.handleSave` deserializes/migrates a server DTO, saves the graph, then updates bindings/content and attempts rollback on failure. The resource-specific acknowledgement contains ID/request/revision/hash only on some routes; lifecycle event and trigger state are not one shared mutation envelope.

## Current Behavior And Defects

| Family | Observable semantics and effects | Failure/lifecycle defect | Proposed classification |
|---|---|---|---|
| Flow/function/command resources | Save validates first; existing revision mismatch throws; successful graph write plus metadata update is committed together; delete writes a typed graph tombstone before file removal. Command save requires exactly one command start. | Startup then uses triggers and graph shape to reclassify/repair data; unspecified type and ID-only helpers still permit inference surfaces. | Retain durable transaction portions; Replace live discovery/repair; Typed reference |
| Project metadata | `assets/project.json` provides folders/order/display/resource locations and is rewritten during graph save and startup reconciliation. | Metadata reconstruction/pruning can influence persisted resource discovery and resurrection rather than remaining presentation only. | Adapt as presentation-only metadata |
| JSON resources and content | Per-type stores save/delete JSON; content adapters then refresh runtime catalogs and content reconciliation reacts to mutations. Recipe saves normalize effective schema. | No uniform revision, mutation ID, tombstone, server scope, or event hash; content runtime effects are outside a complete resource mutation boundary. | Typed reference; Inspector/Preview for structured content; Review lifecycle |
| Trigger/event binding | `triggers.json` loads on registry construction. Dispatcher resolves aliases, registers Bukkit events, gets flow by `flow` type, checks configured automation ID/type, and invokes the executor with event variables. | Bindings retain raw flow ID/start-node ID/pin names; registry bulk replacement can replace unrelated binding types; permissive missing graph/node/input returns `true`, allowing a dispatch without a validated binding. | Event family; Migration-only command bindings; Typed reference |
| Timers/schedules/jobs | A timer/schedule is keyed by definition ID + scope + owner. Starts persist selected task state before scheduling; pause/resume/cancel persist; timer and task events are emitted; restore reconstructs from persisted state. Flow jobs are memory-only, UUID-keyed records with terminal retention. | Definition revision, owner typed identity, graph plan, mutation correlation, and catalog generation are missing. Restore logs failures and drops semantic evidence; in-flight task/job snapshot participation is unproven. | Automation family; Typed reference; Review persistence |
| Permissions/economy | Operation maps use current provider integrations, inputs, and per-operation outputs. Provider absence/input errors become handler-specific messages and branches. | Values/provider capability and authorization semantics are not declaratively persisted or uniformly diagnosed; external mutation has no durable transaction/tombstone relation. | Integration family; Conditional modes; Review authorization |
| Network resources/events | Synchronizer queues each type/id, uses expected revisions and payload hashes, retains a manifest, retries local-wins conflicts, applies deletes/saves through registry, and reconciles after connect. Network flow handler makes async node/provider calls and returns operation-specific values. | Mutation IDs/server typed locators and complete atomic payload+metadata+tombstone semantics are absent; snapshot contract is network-specific, and policy can choose winner without a complete mutation event. | Network family; Typed reference; Review reconciliation |

## Replacement Goal And Invariants

Goal: preserve the listed observable runtime effects while making each durable resource, binding, task, integration operation, and network change traceable through the approved shared typed-resource, catalog, diagnostic, migration, and snapshot contracts. This report does not prescribe an implementation.

Invariants required for this slice:

1. A resource/binding reference is always server + declared type + immutable ID; display names, folder paths, raw graph IDs, node labels, and `context` cannot identify it.
2. A successful mutation durably changes payload, presentation metadata, revision, mutation ID, runtime-derived binding/task state where applicable, tombstone/change record, and network representation as one logical operation.
3. Command paths belong to the command resource. `triggers.json` may own only event/system bindings; legacy command bindings are migration input and a command save cannot remove unrelated bindings.
4. Event, timer, schedule, and task dispatch binds immutable descriptor/pin identities and a validated graph revision/plan, not aliases or remembered names. Missing/deleted dependencies are explicit non-executable diagnostics, never permissive dispatch.
5. Timers, schedules, jobs, custom content reconciliation, network reconciliation, and external integration operations declare cancellation, restart/restore, authorization, side-effect, failure, and idempotency behavior.
6. Remote/local delete remains a durable typed tombstone through reconnect and restart. Reconciliation is deterministic and idempotent.
7. Content/resource and runtime automation persistence participate in one coordinated snapshot/restore fence; diagnostics/acceptance data are sanitized before becoming fixtures.

## Affected Neighbors And Shared Contracts Consumed

Neighbors: graph persistence/execution and generated Functions; catalog descriptors/handler capabilities; generic resource protocol/client caches/collaboration; migration/snapshot ownership; ReSyncCore network resource contracts; WorldGen/world management adapter lifecycle; Remotely resource designers/selectors; extension contributions and provider unload.

Consumed (currently draft, frozen ownership remains with the orchestrator): typed server/resource locator and local key; descriptor/pin/mode and runtime capability contracts; graph/typed-value/unknown-data model; diagnostics; generic resource/option/collaboration protocol; migration journal/report; snapshot participant/manifest/restore contract.

## Client, Persistence, Migration, And Compatibility Impact

- Client impact: current resource adapters and packet assignments are type-specific. Clients presently receive resource-specific payloads and may remember raw IDs. The target must support typed selectors, capability-negotiated generic resource operations, opaque unavailable content/extension data, and ordered revision/mutation events without ordinary node-specific client code.
- Persistence impact: roots include `assets/**` (typed graph assets, `project.json`, `.tombstones`, transaction internals), legacy graph/content folders read by FlowStorage, resource JSON asset folders managed by `ReSyncJsonResourceStorage`, `triggers.json`, `runtime/automation-tasks.json`, custom-content files, and network manifest/state directories. Network state and task state must be snapshot participants, not incidental files.
- Migration impact: current startup migration and trigger-derived command classification must become explicit one-time migration inputs. Legacy command bindings, raw automation references, task states, project metadata links, JSON resource records, content definitions, and network manifest/tombstone state require versioned conversion or quarantine. No normal-startup repair path may remain.
- Compatibility impact: a supported legacy client can require a read-only/lossless fallback for unsupported resource/inspector capabilities. Current ReSyncCore `ReSyncResourceKey(type,id)` is server-local only; network `FlowResourceReference` uses string scope values. Compatibility fixtures must prove no cross-server/type collision and no unknown-field loss on save/reconnect.

## Required Fixtures

- C3-FX-01: same ID across flow/function/command/JSON/content/network resource types; prove type-safe mutations, selection, cache, manifest and tombstone isolation.
- C3-FX-02: event/system bindings plus legacy command binding input; save/rename/delete one command and one flow while preserving unrelated event/system bindings.
- C3-FX-03: missing event start node, renamed pin/mode, unknown extension event node, and alias input; require explicit diagnostic/quarantine with no execution.
- C3-FX-04: persistent timer/schedule at active, paused, expired, missing-definition, changed-definition, and in-flight states across restart/restore.
- C3-FX-05: content definition plus generated runtime state; mutation failure and restart preserve resource bytes and report derived-runtime outcome.
- C3-FX-06: provider unavailable/available permission and economy actions; verify declared authorization/failure/cancellation and no silent success.
- C3-FX-07: reconnect races for save/delete/conflict; validate revision/mutation/tombstone convergence, including network-only state.
- C3-FX-08: complete sanitized folder snapshot containing all structural roots above, plus interrupted migration/restore states. Do not use or copy current diagnostics until identity/secret review is complete.

## Exact Files Owned

Only these evidence files are owned by C3 at Gate 0A:

- `docs/node-replacement/reviewer-c/worker-3/findings.md`
- `docs/node-replacement/reviewer-c/worker-3/trace.md`

## Unresolved Risks

- Exact current transaction ordering between `FlowResourceRegistry` commit listeners, adapter runtime refresh, packet acknowledgements, and network synchronization needs an end-to-end trace before claiming mutation atomicity.
- TriggerRegistry callers must be inventoried to prove which editor routes use whole-list versus preservation calls.
- Extension-defined resource adapters, external content providers, and provider unload sequencing require live-code-path inventory; local `extensions` is empty.
- Automation task owner semantics and schedule invocation restoration may retain unsanitized runtime inputs; fixture capture must not expose them.
- The local acceptance folder proves only structural roots, not populated resource/extension/graph migration parity.
- Compatibility source bounds remain unsupported: read-only repository refs show no ReSync tags and only Remotely tag `BETA`; neither establishes an upgrade range. Do not infer bounds from branch heads or this tag.

## ADR-007..009 Lifecycle Evidence Update

`participant-matrix.md` records C3's runtime-domain participant/identity evidence only: triggers, automation definitions and task state, graph/resource registry seams, and network resource reconciliation. Reviewer A owns the authoritative full-folder FX-013 sanitized manifest/source hashes and the cross-system participant/flush/snapshot/restore absence matrix; C3 does not duplicate those artifacts.

Created deterministic synthetic fixtures `src/test/resources/fixtures/node-replacement/runtime/c3/trigger-bindings.json`, `automation-persistent-task.json`, and `network-resource-mutations.json`, plus focused `src/test/java/restudio/resync/replacement/evidence/runtime/c3/C3LifecycleEvidenceTest.java`. The test asserts current behavior only: raw trigger flow identity after reload; raw automation-ID reduction; `PersistentTask` raw definition/owner identity plus absent definition-revision/catalog/plan fields; same-ID type isolation in `ReSyncResourceKey`; and cross-type `NetworkResourceMutation` save/delete revision, payload, deletion, and absent server/mutation fields.

Command attempted earlier: `./gradlew.bat test --tests restudio.resync.replacement.evidence.runtime.c3.C3LifecycleEvidenceTest` from `ReSync`. Result: Gradle reported `Waiting For Another ReStudio Build To Finish` and no C3 XML test report was produced. No retry was run; subsequent C3 test additions remain pending Reviewer C's shared-lock direction. This is not evidence of a test pass or failure.

After the shared C-suite serial run, C3's first trigger assertion failed because it treated registry iteration as fixture order: `TriggerRegistry` stores bindings in `ConcurrentHashMap` and returns `values()`, which produced `system-primary` before `event-primary`. The evidence test now asserts the complete binding-ID set while preserving the raw shared flow-ID assertion. This documents current ordering nondeterminism; it does not weaken identity evidence.

Irreducible current gaps remain: no existing complete participant fence/flush/manifest/restore boundary; no current task-state binding to definition revision or execution-plan identity; no proven remote tombstone restart fixture across the network manifest and local storage; and no unified trigger/resource/network event shape with type, ID, revision, mutation ID, author, and deletion state.

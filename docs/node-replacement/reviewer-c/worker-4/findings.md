# C4 Extension Lifecycle, Runtime Ownership, And Execution Evidence

Gate: 0A. Scope is current-state evidence only; no replacement implementation is proposed.

## Required Pre-Implementation Report

### Current Architecture Traced

`FlowRuntimeModule.enable` constructs `HandlerRegistry`, mutable `NodeDefinitionRegistry`, validator, trace/debug services, and `FlowExecutor`; it loads classpath and data-folder nodes, then runs both `FlowGraphMigrator.migrateStoredFlows` and `TypedAutomationGraphMigrator.migrateStoredFlows` during normal startup. `ReSyncExtensionManager.registerExtension` puts an `ExtensionState` into a concurrent map before `initialize` and `start`; its `ExtensionContext.FlowRegistration` registers node definitions, handlers, types, conversions, resources, validators, option catalogs, listeners, and modules one at a time. `refreshNodeRegistry` only rebuilds generated Function definitions.

`FlowExecutor.execute` validates each supplied mutable graph and constructs `FlowRuntime`. Each node resolves its live definition and handler immediately before execution. `FlowRuntime.resolveDefinitionDefault` reads default values from the mutable registry, keyed by visible pin names. `FlowExecutor.resolveHandler` maps legacy IDs then mutates the supplied node type and handler configuration from the current descriptor before resolving the current handler instance.

### Problem IDs Addressed

Existing: NSR-005, NSR-006, NSR-014, NSR-016, NSR-017.

New evidence IDs in this report:

- C-P18: Extension registration, reload, and removal are a sequence of independent live mutations, so they have no all-or-nothing catalog/runtime generation.
- C-P19: Handler ownership and execution leases are absent; handler replacement or unload can call `shutdown` while an in-flight graph still invokes that handler.
- C-P20: Execution cancellation is task-oriented, not execution/provider-oriented; asynchronous handler futures and active execution chains have no common cancellation token/deadline propagation.
- C-P21: Runtime diagnostics and metrics are local and partially structured, without catalog generation, extension provenance, typed resource identity, or durable lifecycle reports.
- C-P22: Missing-extension persistence is only partly protected: graph-level unknown fields round-trip, but node-level opaque data/wires and unavailable-provider state have no demonstrated end-to-end contract.

### Current Behavior And Defects

- `NodeDefinitionRegistry.register` silently overwrites an existing node ID and removes the previous plugin's ownership entry. It uses unsynchronized `HashMap`s; concurrent execute/register/unload has undefined map safety and view consistency.
- `HandlerRegistry.register` shuts down a previous handler before replacing it, and `unregister` immediately shuts down the removed handler. Neither registry records extension owner, reference count, drain state, operation thread policy, authorization, cancellation contract, nor plan lease.
- `ReSyncExtensionManager.cleanupState` deregisters each contribution category independently, then refreshes generated functions. It does not fence new executions, wait for in-flight work, validate a complete replacement contribution, or swap a catalog and bindings together. A `start`, registration, or reload failure can be cleaned up, but observers can have seen partial state.
- `FlowExecutor` has graph-level `executionAuthority` and per-node `FlowNodeAuthorizationPolicy`; its default authorization permits only trusted server flow. It does not bind authorization to an extension owner/capability lease or re-evaluate a security revocation during in-flight work.
- The executor enforces a step budget and checks elapsed time only after a node completes. `cancelPendingTask(s)` cancels tracked Bukkit/wall-clock scheduled tasks and their completion futures, but ordinary handler-created `CompletableFuture`s, currently running sync work, and full graph executions are not registered as cancellable executions.
- `FlowTraceService` maintains an in-memory ring buffer (configured as 500 records in `FlowRuntimeModule`) plus node-type aggregate metrics. `FlowTraceRecord` has graph/node/execution data, but no catalog generation/checksum, handler capability/owner, typed resource locator, correlation across extension lifecycle, or durable export. Node audit records are similarly bounded in memory.
- `FlowSerializer` preserves unknown top-level `FlowGraph` fields in `opaqueProperties`; this is not proof that unknown node fields, missing extension ownership/version, inspector values, or wires survive server load/save, protocol hydration, export, reconnect, and reinstall. On execution, a missing handler produces `HANDLER_UNAVAILABLE`; this is executable failure behavior, not lossless unavailable-node handling.

### Proposed Classification

Replace the extension lifecycle and interpreted execution ownership seam. Adapt existing handler implementations only behind an approved capability binding. Retain/expand focused task scheduling evidence, but do not treat it as provider or graph-execution cancellation.

### Replacement Goal

One namespaced extension contribution and its capability bindings must validate off-line, then activate as one immutable catalog generation. Plans own leases to the resolved bindings; unload rejects new work and drains or cancels existing leased work under declared policy. Unavailable extension nodes remain opaque, non-executable, inspectable, and byte-preserving.

### Invariants

- A collision or invalid extension contribution leaves the prior active generation unchanged.
- Registration order never selects a node or handler owner.
- A handler cannot shut down until all execution-plan leases release, except declared security cancellation.
- New executions against an unavailable/revoked capability fail with a stable, provenance-rich diagnostic.
- Cancellation has a declared scope, deadline, result, and propagation to every owned asynchronous operation.
- Executing a plan cannot mutate persisted nodes or replace saved configuration/defaults from live descriptors.
- Missing extension data and wires round-trip without selecting, executing, or silently coercing unavailable content.

### Affected Neighbors

`FlowRuntimeModule`, `NodeDefinitionLoader`/validator, `FlowGraphValidator`, generated Function definitions, `FlowRegistry`, `PropertyRegistry`, option/type/conversion/resource registries, `FlowModule` protocol snapshots, `FlowStorage`, migration paths, Remotely registry/cache hydration, and extension JAR scanner/reloader.

### Shared Contracts Consumed

Pending Gate 0B contracts: catalog contribution/snapshot/provenance; runtime capability/binding/operation/thread/cancellation model; immutable graph/execution-plan model; typed graph/resource locator; structured diagnostics/correlation; extension contract range/capability manifest; unknown-data policy and migration edges.

### Client Impact

Ordinary extension nodes must be descriptor-driven with no Remotely source change. The client requires a snapshot generation/checksum, capability fallback/read-only state, provider provenance, and lossless opaque graph representation. Current server-side availability/handler errors cannot serve as a generic unavailable-node UX contract.

### Persistence Impact

Current graph serialization preserves only graph-level unknown properties. No persisted extension contribution manifest, provider version, catalog generation binding, opaque node payload contract, or whole-folder lifecycle report was found in this slice. Existing live startup migrations make persistence behavior additionally mutable.

### Migration Impact

Any supported extension node must carry owner, version, stable definition/pin identities, and migration edges. A missing provider or missing migration edge must quarantine/block affected documents rather than runtime transform them. Current `FlowGraphMigrator` executes at startup and mutates saved graphs after per-graph backup; it is not the replacement boundary.

### Compatibility Impact

Current extensions use mutable plugin IDs and classloader/JAR scanning without contract range/capability negotiation. Supported older clients need generic fallback/read-only behavior, while current protocol/cache paths must be proven to preserve unknown content before compatibility can be declared. Repository-history evidence supplied to this review reports no ReSync tags and only Remotely `BETA`; exact supported source/replacement bounds therefore remain unresolved and must not be inferred.

### Fixtures Required

- FX-005 extension that registers nodes, handler, type, option source, resource, validator, and listener; activation/reload/unload/reinstall observation.
- FX-008 broken, duplicate-node, duplicate-handler, dependency-missing, and handler-operation-invalid contributions, asserting unchanged active generation.
- FX-012 unavailable extension node with unknown node and graph fields plus wires, round-tripped through load/save/export/reconnect/reinstall.
- New C4 fixture: an in-flight main-thread handler, an in-flight asynchronous handler future, and scheduled work during provider unload, including declared drain/cancel/security-revocation outcomes.
- FX-009 fixed-seed representative catalog/graph and extension reload workload for activation, validation, compile, execute, drain, and cancellation measurements.

### Exact Files Owned

Only this evidence directory: `docs/node-replacement/reviewer-c/worker-4/findings.md` and `trace.md`.

### Unresolved Risks

The inspected local acceptance data has an empty extensions directory, so missing-extension claims remain code-path gaps until sanitized fixtures exist. The exact external plugin JAR discovery/start sequence and every protocol/client serializer path need cross-review evidence. Contract freeze is pending; this report must not be used to implement a local lifecycle contract.

## ADR-007 Through ADR-009 Baseline Addendum

Created deterministic fixtures `src/test/resources/fixtures/node-replacement/runtime/c4/missing-provider-node.json` and `registry-lifecycle-sequence.json`, plus focused test `src/test/java/restudio/resync/replacement/evidence/runtime/c4/CurrentRuntimeLifecycleBaselineTest.java`. The test asserts the present registry-level baseline: `NodeDefinitionRegistry` transfers a duplicate node ID to the later owner; the explicit add/unload/reload sequence exposes node removal before handler removal; and `HandlerRegistry` shuts down the prior implementation at replacement and the active implementation at unregister.

The test also creates an in-flight handler invocation behind entered/release latches, calls `HandlerRegistry.unregister` after the invocation enters, observes `shutdown` while the invocation remains blocked, then releases and joins the thread. This proves immediate registry-level shutdown without a lease/drain boundary; it does not claim a live Bukkit `ReSyncExtensionManager` race. A platform-free cancellation check establishes that `FlowExecutor` tracks `pendingTasks` and `wallClockTasks`, has no active-execution registry, and does not cancel an arbitrary unregistered `CompletableFuture`; Bukkit scheduler cancellation remains separately unproven. The serializer fixture proves graph-root opaque data survives while its unknown node-level extension payload is omitted. The trace check proves no provider, catalog generation/checksum, or typed resource fields exist.

`printsRepresentativeCurrentRuntimeMeasurements` uses 100 warmup cycles and 250 samples each for registry add/lookup/unload, missing-provider serializer round-trip, and trace/diagnostic record/aggregate/snapshot. It prints one parseable `C4_BASELINE` line per execution with `n`, `min`, `median`, `p95`, and `max` nanoseconds for each path. It intentionally asserts no time threshold; output is a current-environment distribution, not a budget.

The scoped command `./gradlew.bat test --tests restudio.resync.replacement.evidence.runtime.c4.CurrentRuntimeLifecycleBaselineTest` was initially attempted twice from `ReSync`. Each attempt returned after `Waiting For Another ReStudio Build To Finish`; no C4 XML result was created under `build/test-results/test`.

On 2026-08-02, Reviewer C subsequently ran the C suite serially. `CurrentRuntimeLifecycleBaselineTest` completed with 8 tests and 0 failures/errors in 0.126 seconds. Its parseable current-environment output was `C4_BASELINE registry=n=250,min=18000,median=30100,p95=55200,max=357800 serializer=n=250,min=64900,median=115500,p95=209200,max=5939000 trace=n=250,min=6000,median=9900,p95=31000,max=132400`; all values are nanoseconds. The overall Gradle invocation failed only because four other Reviewer C tests failed; that does not change the passing C4-class result. These are one machine/date-specific baseline samples, not portable performance limits or proposed budgets.

Live Bukkit owner disable, extension JAR scanner/reload, `ReSyncExtensionManager` rollback visibility, handler invocation through a real executor during unload, and Bukkit scheduler cancellation are not proven by these fixtures. They require a controlled MockBukkit/module-context fixture or an isolated server/JAR acceptance run. The current tests prove only the independently reachable registry/serializer/trace baseline.

## Performance And Measurement Inventory

No test body, benchmark, or server was run: the focused Gradle test command was blocked before execution by an existing shared build lock. `baseline-metrics.md` records no unified current measurement. Existing observable hooks are `FlowTraceService.metricsSnapshot` (node-type count/failure/total/max/average duration, bounded at 4,096 metric keys), a 500-record runtime trace buffer, and scheduled-task snapshots. They do not measure deterministic catalog compilation, registry hydration, extension activation/reload, plan compilation, handler drain, cancellation latency, memory, or opaque-node round-trip. Baselines and budgets for those behaviors remain required before Gate 0B.

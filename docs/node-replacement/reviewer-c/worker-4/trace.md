# C4 Concrete Current-System Trace

## Startup, Extension Registration, And Unload

1. `FlowRuntimeModule.enable` (`modules/FlowRuntimeModule.java`, around lines 260-344) creates mutable registries, loads classpath/data-folder node JSON, validates/registers it, builds `FlowExecutor`, preloads storage, rebuilds Function definitions, and invokes both graph migrators during normal startup.
2. `ReSyncExtensionManager.loadInitialExtensions` calls `scanDirectory`; `tick` unloads disabled Bukkit owners and rescans every five seconds (`api/ReSyncExtensionManager.java`, lines 72-91 and JAR-scanner section).
3. `registerExtension` normalizes the plugin ID, inserts `ExtensionState` into `ConcurrentHashMap`, writes metadata, invokes `initialize`/`start`, then calls `refreshNodeRegistry`. On failure it removes the state and invokes `cleanupState` (`ReSyncExtensionManager.java`, lines 160-178).
4. Extension callbacks call `ExtensionFlowRegistration.registerNode` and `registerHandler` independently (`ReSyncExtensionManager.java`, lines 582-607). `registerNodes` loads then loops each node. Node, handler, type, conversion, resource, validator, option, listener, module, and custom provider registration use separate mutable registries.
5. `unregister` removes state, calls extension `stop`, calls `cleanupState`, then refreshes generated Functions (`ReSyncExtensionManager.java`, lines 203-305). Cleanup removes node definitions, runtime nodes, handlers, properties, option catalogs, runtime data, types/codecs, conversions, resources, validators, content/world providers, listeners, and modules in order; there is no cross-registry transaction or execution drain.

## Registry And Collision Evidence

- `NodeDefinitionRegistry.register` (`flow/registry/NodeDefinitionRegistry.java`, lines 31-54) assigns `definitions.put(nodeId, definition)` and transfers `nodeToPlugin` ownership from another plugin. There is no duplicate rejection, namespace identity key, synchronization, catalog checksum, or generation.
- `HandlerRegistry.register` (`flow/handler/HandlerRegistry.java`, lines 13-19) finds a prior handler, calls its `shutdown`, then writes the replacement. `unregister` removes then shuts down immediately (lines 21-25). `ConcurrentHashMap` only protects the two maps, not a handler invocation/ownership lease.

## Execution, Authorization, And Mutation Evidence

1. `FlowExecutor.execute` validates the supplied graph each call, then creates `FlowRuntime` (`flow/FlowExecutor.java`, lines 201-240). It does not compile a plan or snapshot registry/bindings.
2. `executePreparedNodeWithInputs` resolves definition, invokes `FlowNodeAuthorizationPolicy`, records a bounded audit record, resolves handler, checks operation availability, then invokes `handler.execute` (`FlowExecutor.java`, lines 480-575).
3. `resolveHandler` maps compatibility IDs, fetches the mutable registry definition, calls `node.setType(mappedType)` and `node.setHandlerConfig(definition.getHandlerConfig())`, then gets the live handler (`FlowExecutor.java`, near lines 2080-2105). This both makes semantics registry-time dependent and mutates the executing graph node.
4. `FlowRuntime.resolveInputRaw` falls through to `resolveDefinitionDefault`; that finds a pin by its name (including numeric repeatable suffixes) and adapts its live descriptor default (`flow/FlowRuntime.java`, lines 161-228). Pin/default behavior is not plan-owned or immutable.
5. `FlowNodeAuthorizationPolicy` is an interface receiving context/node/definition. Its default deny is generic `AUTHORIZATION_DENIED` with a policy and node string (`flow/FlowNodeAuthorizationPolicy.java`). It does not identify provider, catalog generation, resource locator, or an authorization revision.

## Concurrency And Cancellation Evidence

- `FlowExecutor` dispatches node work through Bukkit synchronous/asynchronous schedulers according to handler thread policy and awaits context futures. Its runtime guards re-entry by graph ID plus node ID and has step/duration budgets, but duration is checked after node completion.
- Pending Bukkit and wall-clock tasks are tracked by task ID in concurrent maps. `cancelPendingTaskWithStatus`, `cancelPendingTasks`, and graph-ID cancellation cancel the scheduler task and associated completion future; `shutdown` cancels those maps and stops the wall-clock executor (`FlowExecutor.java`, lines 1777-2067).
- These task maps do not represent all execution chains or provider ownership. A handler executing already, or an arbitrary future registered in `FlowContext`, has no universal cancellation token or unload drain relationship.

## Diagnostics And Persistence Evidence

- `FlowTraceService` stores an optional in-memory trace ring and aggregate node-type duration/failure metrics (`flow/diagnostics/FlowTraceService.java`). `FlowTraceRecord` includes graph/node/execution information but lacks catalog/provider/resource/revision lifecycle context.
- `FlowSerializer` (`flow/data/FlowSerializer.java`) retains unknown top-level graph JSON in `FlowGraph.opaqueProperties`; its declared graph property allowlist and Gson node serialization provide no demonstrated opaque-node/provider-version preservation guarantee.
- `FlowGraphMigrator.migrateStoredFlows` acquires an asset fence but iterates stored flow IDs, clones each graph via serializer, performs migration, backs up and saves it, and commits per graph (`flow/migration/FlowGraphMigrator.java`, lines 69-140). This is normal startup work, not a coordinated extension or whole-folder transaction.

## Local Acceptance Data (Structural, Read-Only)

`run/plugins/ReSync` contains project metadata, configuration, diagnostics, and world-management paths. The extensions directory is structurally empty; no plugin JARs were observed under `run/plugins`. Diagnostic contents were not copied or disclosed. This structural data cannot prove persisted extension-node or missing-extension round trips.

## Focused Fixture And Verification Attempt

Fixtures: `src/test/resources/fixtures/node-replacement/runtime/c4/missing-provider-node.json` contains a missing-provider node with unknown node-level and graph-level payloads. `registry-lifecycle-sequence.json` fixes the add/unload/reload operation order used by the registry-level test.

Test: `src/test/java/restudio/resync/replacement/evidence/runtime/c4/CurrentRuntimeLifecycleBaselineTest.java`. It isolates current `NodeDefinitionRegistry`, `HandlerRegistry`, `FlowExecutor` cancellation surface, `FlowSerializer`, and `FlowTraceRecord` behavior without a Bukkit server or extension JAR. Its in-flight handler test blocks direct handler invocation on a latch, unregisters it, verifies shutdown before release, then releases and joins. Its measurement test performs 100 warmups and 250 samples for registry add/lookup/unload, serializer round-trip, and trace/diagnostic aggregate/snapshot, emitting a parseable `C4_BASELINE` summary without thresholds.

Command initially attempted twice: `./gradlew.bat test --tests restudio.resync.replacement.evidence.runtime.c4.CurrentRuntimeLifecycleBaselineTest` from `ReSync`.

Initial result: each invocation reported `Waiting For Another ReStudio Build To Finish`; no test XML result was produced.

Reviewer C then executed the combined C suite serially on 2026-08-02. C4 itself passed: 8 tests, 0 failures/errors, suite time 0.126 seconds. Output was `C4_BASELINE registry=n=250,min=18000,median=30100,p95=55200,max=357800 serializer=n=250,min=64900,median=115500,p95=209200,max=5939000 trace=n=250,min=6000,median=9900,p95=31000,max=132400`, with every statistic in nanoseconds. The overall Gradle invocation failed only due to four other Reviewer C test failures; C4 did not fail. This is a machine/date-specific current baseline, not a performance threshold, comparison target, or Gate 0B budget.

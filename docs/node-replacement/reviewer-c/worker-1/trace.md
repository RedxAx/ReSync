# C1 Runtime Trace

Status: Gate 0A current-state evidence only. Inspected read-only on 2026-08-02; no production files or fixtures were changed and no tests/builds ran.

## Runtime construction and live dependencies

`ReSync/src/main/java/restudio/resync/modules/FlowRuntimeModule.java:260-305` constructs a mutable `HandlerRegistry`, `NodeDefinitionRegistry`, option/type/resource registries, loads JSON definitions one at a time, constructs `FlowGraphValidator`, then injects that same mutable registry and `TypeAdapterRegistry` into `FlowExecutor`. It preloads storage, rebuilds generated Function definitions, runs both `FlowGraphMigrator.migrateStoredFlows()` and `TypedAutomationGraphMigrator.migrateStoredFlows()` during normal initialization, and rebuilds Functions again. `FlowRuntimeModule.java:392-404` cancels executor work, clears the definitions/handlers/property registry, and repopulates them during definition reload; the live executor retains registry references.

Binding trace:

1. `FlowRuntimeModule` calls `registerNodeHandlers` (`:898+`) and `NodeDefinitionLoader.validateAndRegister` (`:275-279`).
2. `NodeDefinitionRegistry.register/registerAll` (`flow/registry/NodeDefinitionRegistry.java:25-55`) mutates `HashMap` state immediately; a later registration silently replaces an existing node ID and moves its plugin association.
3. `HandlerRegistry.register` (`flow/handler/HandlerRegistry.java:12-20`) shuts down an existing different handler before replacing it and derives operation metadata, including a reflective fallback over a field named `operations` (`:62-98`). `unregister/clear` call handler shutdown without a plan ownership/drain relationship (`:22-55`).
4. `FlowExecutor.resolveDefinition/resolveHandler` (`flow/FlowExecutor.java:2102-2161`) looks up the current registry on every evaluation. Thus a reload/contribution change can alter the definition/handler used by a graph already saved or being executed.

## Execution trace

Public `FlowExecutor.execute` overloads (`:197-241`) first check `executionAuthority`, validate the supplied mutable `FlowGraph`, find a start node when needed, construct a `FlowRuntime` holding the graph reference, and interpret traversal. `executeValidated` (`:230-241`) opens an event mutation window only around the call that creates the initial future, then registers `cleanupThreadLocals` at future completion.

Per-node execution is `execute` (`:422-462`) -> `executePreparedNode` (`:464-478`) -> input dependency execution -> `executeWithThreadPolicy` (`:729-759`) -> `executePreparedNodeWithInputs` (`:480-574`). The executor:

- tracks a per-runtime operation count and elapsed duration, rejects re-entry with `EXECUTION_REENTRANT_NODE`, and traces/audits nodes;
- checks authorization dynamically from the current descriptor;
- intercepts loop types and Function calls through string/handler-config rules before generic handler dispatch;
- invokes `NodeHandler.execute(FlowContext, FlowNode)` directly, then interprets triggered/deferred outputs and queued futures;
- finds downstream nodes from the current graph connections by source node ID and source pin string (`:788-822`, `:1722-1747`). Outputs with multiple wires run sequentially in connection-list order.

Data dependencies are demand-evaluated: `ensureInputNodesReady` (`:1617-1634`) follows all non-`flow` incoming connections; `executeDataNode` (`:1636-1689`) uses runtime node-output presence and an in-memory evaluating-node set to avoid recursion. It resolves the current handler and runs it under its policy. A cycle encountered while evaluating returns completed without producing a value rather than surfacing a diagnostic at this layer.

## Defaults, conversions, and templates

`FlowRuntime.resolveInputRaw` (`flow/FlowRuntime.java:164-190`) prioritizes the first incoming connection returned by `FlowGraph.getConnectionsToTarget`, otherwise uses `FlowNode.inputValues`, otherwise calls `resolveDefinitionDefault`. `resolveDefinitionDefault` (`:192-210`) performs a live `NodeDefinitionRegistry` lookup, reads the current descriptor default by string pin name, and calls `TypeAdapterRegistry.adapt`; it does not materialize the result into a plan or graph.

String literals are template-expanded at runtime (`FlowRuntime.renderStringTemplate`, `:239-287`) by resolving `{name}` through other string-named inputs. Node output cache keys are concatenated strings, `nodeId + ':' + pinName` (`:366-405`); graph connections and passthrough metadata also store raw node/pin strings.

`TypeAdapterRegistry` is a mutable `HashMap` registry (`flow/TypeAdapterRegistry.java:42-43`) of class pairs and string parsers. Its adaptation search includes assignability iteration (`:300-312`), so the adapter path is not recorded in persisted graph state or execution input. Validator compatibility separately relies on type references plus `TypeAdapterRegistry` (`FlowGraphValidator.java:814-985`), not a common persisted conversion edge.

## Threading, pending work, and cancellation

`NodeHandler.ThreadPolicy` is only `MAIN`, `ASYNC`, or `CURRENT` (`flow/handler/NodeHandler.java:8-27`). `FlowExecutor.executeWithThreadPolicy` dispatches with Bukkit `runTask` or `runTaskAsynchronously` when the current thread does not match (`:729-759`). Handler policy is read from the current handler each node. Deferred output dispatch returns to the Bukkit primary thread (`:685-722`).

Async handler work is accumulated by `FlowContext`; executor waits recursively for both ordinary and before-continuation futures (`FlowExecutor.java:651-683`). `FlowExecutor` tracks Bukkit tasks and an independent daemon wall-clock scheduler (`:69-146`, `:1777-2068`). Cancellation is by task ID or graph ID and calls Bukkit/future cancellation, but there is no immutable execution-plan owner, provider drain contract, or catalog-generation pin. `shutdown` cancels tracked work and `shutdownNow`s the wall-clock executor (`:2065-2068`).

The elapsed-time check happens only at node completion (`:576-613`); it does not itself cancel an already-running handler/future. Code-path evidence shows that `executeValidated` closes the event mutation window before an asynchronous chain necessarily completes. This is an inferred observable risk—event mutation availability may be timing-dependent after a MAIN/ASYNC transition—but no current fixture in this C1 audit proves the observed behavior.

## Function trace

`FlowGraph.FunctionParameter` is persisted as mutable fields keyed by `name`, with type/widget/options/default metadata but no immutable parameter ID (`flow/data/FlowGraph.java:49-146`). Graph Function inputs/outputs are lists (`:302-314`).

Generated Function catalog nodes are rebuilt from every persisted Function by `CustomFunctionNodeDefinitions.rebuild` (`flow/CustomFunctionNodeDefinitions.java:20-49`): it unregisters the whole `custom_functions` plugin, loads each `function` resource, creates a descriptor ID `custom_function:<flowId>`, and registers all definitions. `buildDefinition` (`:51-107`) uses Function parameter names as pins and sorts them case-insensitively. Its description can be generated from the display name.

The ordinary Function-call path is determined from raw node type and handler operation (`FlowExecutor.resolveFunctionCallId`, `:1591-1615`), then `executeFunctionCallNode` resolves a Function and bindings by string names (`:835-1064`). The direct public Function path deep-copies the graph by serialize/deserialize (`:313-368`), starts at the first matching string type, and returns output map entries keyed by Function parameter name. `FlowRuntime.callFunction/returnFromFunction` (`FlowRuntime.java:506-558`) swaps the active graph and local/output maps on a stack frame; call inputs and outputs are `Map<String,Object>` keyed by names.

`FunctionHandler` implements `function_start`, `function_end`, `function_input`, and `function_output` by iterating signatures and accessing names (`flow/handler/generic/FunctionHandler.java:68-119`). The validator synthesizes Function boundary pins from those names and synthesizes call pins from `__call_parameters`, then validates matching names/types (`FlowGraphValidator.java:199-344`). Rename/reorder is therefore an identity-affecting operation even though names are presentation according to the replacement mission.

## Validation trace

`FlowGraphValidator.validate` (`:88-110`) performs one synchronous pass over graph schema/version, Function signature, live definitions, connections, data cycles, execution structure, then extension validators. It builds a local resolved-definition map but does not emit a compiled plan.

`validateNodes` (`:158-198`) maps each persisted node type through `IdCompatibilityLayer`, resolves it from the live registry, derives Function-specific descriptors, validates handlers, required/default/literal/schedule inputs. It does not rewrite the node at validation time. Dynamic/conditional visibility, repeatable pins, template/editor metadata, and resource references are resolved by current descriptor conventions (`:419-813`, `:1141-1287`).

Connections identify source/target node IDs and pins with mutable strings (`flow/data/FlowConnection.java:3-67`). Validation checks current pin definitions and inferred bindings (`FlowGraphValidator.java:814-985`); execution branch/loop recognition also contains hard-coded type, operation, and pin strings (`FlowExecutor.java:1093-1574`, `:2109-2122`).

## Persisted/current-state mutation inventory

| Surface | Current mutation/read behavior | Evidence |
|---|---|---|
| Persisted `FlowNode` during execution | `resolveHandler` maps legacy type then directly executes `node.setType(nodeType)` and replaces its handler configuration with the current descriptor configuration. A caller executing a stored graph can therefore mutate the in-memory graph passed in. | `FlowExecutor.java:2136-2161` |
| Descriptor defaults | Missing literal values read default from the current registry at each input resolution; catalog updates change unpinned execution semantics. | `FlowRuntime.java:192-232` |
| Graph execution state | Runtime retains a direct mutable graph reference, traverses mutable maps/lists, and swaps graph references for nested Function calls. | `FlowRuntime.java:76-105`, `:498-558`; `FlowGraph.java:149-205` |
| Node/pin schema interpretation | Both executor and validator construct `IdCompatibilityLayer`, reading active `/nodes/migrated/_id_migration_map.json`; ID aliases participate in normal execution/validation. | `FlowExecutor.java:169`, `:2102-2108`; `FlowGraphValidator.java:61`, `:158-198`; `flow/migration/IdCompatibilityLayer.java:17-46` |
| Handler binding | Mutable registry replacement shuts down the former handler; no execution lease pins an in-flight plan/provider. | `HandlerRegistry.java:12-29`; `FlowExecutor.java:2136-2161` |
| Function definition catalog | Every refresh unregisters/re-registers generated definitions from current persisted Function graphs. | `CustomFunctionNodeDefinitions.java:20-49`; `FlowModule.java:362-365` |
| Runtime ephemeral state | Node outputs, local/global/event variables, call stack, loop controls, evaluation/execution sets and task maps mutate during execution; output keys are colon-concatenated strings. | `FlowRuntime.java:36-45`, `:325-405`, `:506-682`; `FlowExecutor.java:69-75`, `:1777-2068` |
| User-visible runtime effects | Handlers may change Bukkit/world/resource state; examples include block/inventory writes and resource writes. These are runtime effects, not graph-document changes. | `flow/handler/generic/BlockActionHandler.java`, `InventoryActionHandler.java`, `ReSyncRuntimeResourceHandler.java` |

## Local plugin structural evidence (sanitized)

Read-only inspection of `ReSync/run/plugins/ReSync` found separate persistence roots: `assets/`, `diagnostics/`, `extensions/`, `world-management/`, plus other roots. `assets/` contains `project.json` and domain directories `Blueprints/{Commands,Flows,Functions}`, `Content/{Advancements,Armor,Blocks,Dialogs,Items}`, `Customization/{Scoreboards,Tabs}`, `Groups`, `GUIs`, `WorldGen`, and `Worlds`. `diagnostics/` contains large programmability acceptance reports. `world-management/` contains separate JSON state files. `extensions/` was structurally empty at inspection. No contents, identifiers, player data, or diagnostic payloads were copied or disclosed.

## Graph serialization and protocol seam inventory

The active server graph DTO and serializer package is `ReSync/src/main/java/restudio/flow/data/**`, including `FlowGraph`, `FlowNode`, `FlowConnection`, `FlowTypeRef`, `FlowResourceReference`, and `FlowSerializer`; it is not in ReSyncCore. This is the graph model directly consumed by executor, validation, storage, and server packet serialization.

The runtime protocol path remains resource/packet-specialized. `modules/flow/FlowBlueprintPacketHandler` and `FlowResourcePacketRouter` parse, validate, save, duplicate, reload, and send graph/resource payloads; the latter has a `FlowGraph` adapter at `FlowResourcePacketRouter.java:176-243` and routes packet IDs through a handler list (`:447-457`). `FlowPacketSender` has resource-specific send/list/save-ack methods alongside generic-looking helpers; examples include `sendFlowData` (`:74-76`), `sendFlowList` (`:226-228`), and many named resource methods. `FlowNodeRegistryPacketHandler` constructs `NodeRegistrySnapshot` from the mutable registry and stamps a local contract/checksum (`:94-214`). `FlowModule` wires these handlers and broadcasts a rebuilt Function registry (`modules/FlowModule.java:362-365`).

Neighbor seams requiring the protocol/contract audit are `protocol/ReSyncProtocolInventory`, `resources/ReSyncResourceCatalog`, and `server/ReSyncServer`; the server dispatches authenticated transport `DATA` messages to channel handlers (`server/ReSyncServer.java:335-347`, `:608-630`). This inventory establishes paths only; packet-level revision/conflict/reconnect behavior is outside C1's execution scope.

## ADR-007..009 baseline evidence

The source declaration scan is complete for all 1,428 current JSON definitions and is source-hashed in `findings.md`. It records declaration-level handler/operation/default/branch fields only. It deliberately does not infer runtime thread, effect, authorization, cancellation, conversion, or failure semantics: those values are absent from most raw descriptors and handler behavior is distributed across module registration, family/property routes, and executable code. The complete current semantic matrix therefore remains a Gate 0A gap rather than a fabricated classification.

`MutableExecutionBaselineTest` is the focused reproducible baseline under the approved C1 test root. It isolates two directly observed semantics: `FlowExecutor.resolveHandler` writes the live definition handler configuration into a supplied `FlowNode`, and `FlowRuntime.resolveInput` changes an unresolved literal when the active registry default changes. The directly relevant Gradle test was attempted but did not execute because another ReStudio Gradle build held the shared lock.

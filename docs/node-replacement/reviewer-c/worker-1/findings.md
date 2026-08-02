# C1 Gate 0A Runtime Findings

Status: evidence report for Reviewer C. Scope: FlowExecutor, FlowRuntime, graph validation, handler binding, threading/defaults/conversions, Function behavior, branches, and mutable execution. This report proposes no implementation design.

## Required pre-implementation fields

### Current architecture traced

The concrete call paths, lifecycle, validation, Function handling, thread/cancellation behavior, compatibility reads, and persisted/current mutation inventory are in [trace.md](trace.md). Core path: `FlowRuntimeModule` assembles mutable registries -> `FlowExecutor.execute` validates a caller-supplied graph -> `FlowRuntime` interprets the same graph -> live definitions/defaults/handlers are resolved per node -> handler futures and string-pin connections determine continuation. Function resources generate a separate live node batch and execute through name-keyed maps and a graph-swapping call stack.

### Problem IDs addressed

Existing ledger IDs evidenced by this slice: **NSR-003** (inference/current definitions), **NSR-005** (mutable registry), **NSR-006** (live definition/default execution), **NSR-012** (aliases/special paths), **NSR-013** (string/name structural identity), and **NSR-016** (handler/contribution lifecycle). Reviewer C local problem identifiers follow; they are not shared-ledger additions:

| Proposed ID | Evidence | Defect / violated invariant |
|---|---|---|
| C-P01 | `FlowExecutor.resolveHandler` directly calls `FlowNode.setType` and `setHandlerConfig` while executing. | Execution mutates the supplied graph's persisted node type/configuration, violating immutable execution semantics. |
| C-P02 | `FlowRuntime.resolveDefinitionDefault` reads current descriptor defaults by string pin name; handler/definition lookup remains live per node. | Catalog changes can alter saved graph execution without document migration or plan recompilation. |
| C-P03 | Function parameters, generated Function pins, bindings, and returned output maps use parameter names; connections/passthrough/output keys use raw pin strings. | Rename/reorder/presentation values are structural identities and cache keys rather than immutable IDs. |
| C-P04 | Handler replacement invokes `shutdown`; reload clears handlers/definitions while executor keeps references; no provider execution ownership is present. | Contribution/reload/unload can race in-flight execution; declared drain/cancellation policy is absent. |
| C-P05 | Code-path evidence: the event mutation window closes after dispatch setup while future work can continue on async/scheduled threads; duration check is completion-only. | Inferred observable risk only until a fixture proves it: mutation/thread/cancellation timing may vary with scheduling and is not represented as a graph/plan contract. |

### Current behavior and defects

- Runtime is an interpreter, not a prevalidated immutable plan. It performs dynamic descriptor, default, handler, operation, authorization, pin, loop, and Function decisions during traversal.
- The executor directly mutates a `FlowNode` (`setType`, `setHandlerConfig`) when a definition is found. This is observable to callers holding the graph reference and is independent of a storage save transaction.
- Compatibility aliases remain in both validation and execution through `IdCompatibilityLayer` loading `/nodes/migrated/_id_migration_map.json`.
- Defaults are descriptor values at the moment of evaluation. A descriptor reload changes unresolved literal behavior in an existing graph.
- Handler operations are validated from mutable registry metadata. Its reflection fallback means operation declaration is not entirely an explicit runtime capability contract.
- Connections, branches, loops, Function parameters, repeatable representations, output cache keys, and templates depend on strings and positional/list conventions. The execution path recognizes hard-coded node IDs/operations/pin labels.
- Multiple outgoing branches are sequential in stored connection-list order; input resolution picks the first incoming connection encountered. Those ordering semantics must be explicitly classified before conversion.
- Async work is tracked/cancelled by generated task IDs and graph ID. Executor cancellation does not itself establish a provider unload drain; duration overrun is detected after node completion.
- Function execution deep-copies only the direct public Function target. Nested calls use mutable runtime graph swapping and stack frames; parameter/output identity is name based. Generated Function descriptors are rebuilt from current storage and registration order.

### Proposed classification

| Surface | Classification |
|---|---|
| `FlowExecutor` interpreted traversal and string-based structural dispatch | Replace |
| `FlowRuntime` ephemeral variable/output/call-frame state | Adapt (runtime-state-only boundary) |
| Direct `FlowNode` mutation in handler resolution | Delete |
| Live default lookup and catalog alias resolution in execution | Delete |
| `HandlerRegistry` handler implementations | Adapt; registry lifecycle/binding surface Replace |
| `TypeAdapterRegistry` ad-hoc conversion resolution | Replace/adapt into the frozen shared conversion contract |
| `FlowGraphValidator` current semantic checks | Adapt; retain rules only after stable-ID/shared-contract refit |
| Generated Function definition refresh path | Replace |
| Legacy `IdCompatibilityLayer` runtime use and `nodes/migrated` dependency | Migration-only then Delete |
| Existing task tracking and trace/audit intent | Adapt after explicit plan/provider/thread ownership is available |

### Replacement goal

Per plan, execution must consume a graph validated against one immutable catalog generation and use resolved handler bindings, typed values/conversions, stable structural identities, declared effects/thread/cancellation/authorization semantics, and an immutable plan. Running a graph must not modify persisted graph nodes or source defaults/configuration. This is a target constraint, not an implementation proposal.

### Invariants to preserve or declare intentionally changed

- Server validation and authorization remain authoritative before execution.
- A node's declared output/branch semantics, handler failure information, audit/trace correlation, and user-visible Bukkit/resource effects require parity evidence or an approved behavior-change entry.
- Data dependencies must resolve deterministically; present first-connection/list ordering behavior needs either explicit preservation or an approved replacement rule.
- Loop recursion, Function depth/operation budgets, task cancellation, and handler thread requirements must not weaken safety.
- A Function call must preserve argument/result association across rename/reorder using stable identity; name-driven legacy bindings require a migration/quarantine decision.
- A catalog/extension reload must not silently change an in-flight plan, mutate a stored document, or shut down a provider still owned by work in progress.
- No normal validation/execution path may retain migration aliases or migrated catalog dependency after the controlled upgrade.

### Affected neighbors

`FlowRuntimeModule`, `FlowModule`, `FlowStorage`, `NodeDefinitionLoader`, `NodeDefinitionRegistry`, `NodeDefinitionValidator`, `FlowGraphMigrator`, `TypedAutomationGraphMigrator`, `IdCompatibilityLayer`, all `NodeHandler` implementations, `FlowContext`, the server DTO/serializer root `ReSync/src/main/java/restudio/flow/data/**`, trigger dispatchers, scheduler/automation handlers, Resource/Content handlers, `modules/flow/FlowBlueprintPacketHandler`, `FlowNodeRegistryPacketHandler`, `FlowPacketSender`, `FlowResourcePacketRouter`, `protocol/ReSyncProtocolInventory`, `resources/ReSyncResourceCatalog`, `server/ReSyncServer`, ReSyncCore diagnostic/protocol consumers, and Remotely graph/editor/cache surfaces that serialize node/pin/Function names.

### Shared contracts consumed

Current code consumes non-frozen server-local models in `ReSync/src/main/java/restudio/flow/data/**` (`FlowGraph`, `FlowNode`, `FlowConnection`, `FlowTypeRef`, `FlowResourceReference`, serializers) plus registry definition/handler/configuration types and ReSyncCore diagnostic/protocol types. `FlowResourcePacketRouter`, `FlowPacketSender`, and `FlowNodeRegistryPacketHandler` serialize these server-local models across resource-specific packet paths. Gate 0B's contract-freeze ledger says graph, runtime capability/plan, catalog, type/value/conversion, diagnostics, typed locator, and protocol contracts remain Draft. No shared contract change is authorized by this report.

### Client impact

Current persisted node/pin names and Function names are also client-facing structural data. Replacing runtime identity/default/Function semantics requires Remotely to consume the frozen shared descriptor/graph model rather than local interpretation. Ordinary catalog changes must stay descriptor-driven; this C1 report identifies current node-ID/pin-name special behavior but proposes no client implementation.

### Persistence impact

Existing graph documents contain mutable node type/version/input/handler configuration, map-keyed node IDs, list connections with string pin labels, and Function parameters keyed by names. The local runtime structure has distinct asset/project, world-management, diagnostics, and extension roots; complete snapshot/migration work must include the registered participant boundary rather than assume graph JSON is all durable state. No unsanitized local payload was copied.

### Migration impact

Migration must handle ID aliases, handler-config replacement behavior, string pin/connection identities, Function parameter names, generated Function descriptor IDs, and current defaults that were not persisted. Legacy graphs depending on aliases or renamed Function arguments require deterministic conversion or quarantine. Runtime startup currently runs legacy graph migrators; their normal-runtime reachability is evidence for later retirement, not authorization to change it now.

### Compatibility impact

Java 21 and current ReSyncCore/Remotely bounds are listed in `docs/node-replacement/compatibility-matrix.md`, but exact supported client and direct-upgrade ranges are not yet evidenced. Repository history does not establish them: ReSync has no tags and remote `origin/node-system-v2`; Remotely has only `BETA` and remote `upstream/node-system-v2`. Record the bounds as unsupported/unresolved rather than inferring them. Any replacement must preserve declared handler effects/thread safety or make an approved behavioral change and must protect older clients' opaque graph data. Gate 0B compatibility and capability contracts are pending.

### Fixtures required

- Function signature fixture with rename/reorder, duplicate name, stable old-to-new binding, generated descriptor refresh, and nested call output assertions (extends FX-006).
- Graph fixture with unresolved descriptor defaults, catalog generation change, and proof that execution does not change serialized graph bytes (new; supports C-P01/C-P02).
- Handler reload/unload fixture exercising active async/main/wall-clock execution, cancellation, and provider ownership (extends FX-005/008).
- Event-mutation transition fixture that explicitly verifies mutation availability and result before and after a MAIN-to-ASYNC and ASYNC-to-MAIN transition; it must determine whether C-P05 is observable behavior or only a code-path risk.
- Branch/input ordering and dynamic-pin fixture covering multiple outgoing/incoming connections, loop boundary, templates, repeatables, and cycle diagnostic behavior (new).
- Conversion fixture covering adapter ambiguity/assignability and explicit failure behavior (supports contract conversion fixtures).
- Legacy alias/migrated-node fixture proving offline conversion or quarantine with no normal-runtime lookup (supports FX-002).
- Sanitized complete folder snapshot including structurally observed asset, world-management, diagnostics, and extension participant cases (FX-011; do not use local raw data).

### Exact files owned

Only these evidence files are owned/modified:

- `C:/Users/redxa/ReProjects/ReSync/docs/node-replacement/reviewer-c/worker-1/trace.md`
- `C:/Users/redxa/ReProjects/ReSync/docs/node-replacement/reviewer-c/worker-1/findings.md`

### Unresolved risks

- Full handler-by-handler effects, ThreadPolicy declarations, cancellation and resource durability semantics were not exhaustively enumerated in C1 and need bounded Runtime-domain slices.
- `FlowRuntimeModule.reloadNodeDefinitions` and extension registration/unload ownership need a complete extension lifecycle trace before an atomicity claim.
- The exact persistence participants and restore paths behind `FlowStorage`, world management, diagnostics, network state, and extensions remain unproven; local inspection established only separate roots.
- Current ordering behavior may depend on Gson/map/list load order, and intent is undocumented.
- C-P05 is code-path evidence/inferred observable risk only. A transition fixture must reproduce or disprove it before it is classified as a user-visible regression.
- The shared contracts are not frozen, so no implementation decision should be derived from these findings alone.

## ADR-007..009 C1 Baseline Extension

### Generated raw definition matrix

On 2026-08-02, the current 61 eligible `src/main/resources/nodes/**` JSON sources were scanned deterministically by sorted relative path. C1's verifier-authoritative path/content digest is `140445208ee691fdf5e20fdc259765f335807e468bc2695fa438547e18e1cd73`: the Java verifier hashes each normalized relative path plus newline followed by its raw bytes. It is deliberately distinct from shared raw-catalog hash `c83f2e2828ee599c18563beaab2a131a160acceeb5b7ae8220064124188ff0e8`, which has its own documented scanner algorithm; the two must not be treated as competing values for the same metric. The PowerShell generator now rejects any digest that differs from the verifier value instead of emitting a misleading matrix. The durable matrix has exactly 1,428 unique definition IDs, 3,584 physical input entries, 3,689 physical output entries, 723 inputs with raw defaults, and 357 outputs whose names match current branch-like naming (`true`, `false`, `branch_*`, `case*`, `success`, `failure`, or `cancel`). The separate five logical compatibility inputs remain a plan-baseline reporting rule only; they are omitted from the physical matrix because their raw definitions omit `inputs`.

The raw definition-to-route inventory is complete at JSON declaration level: 1,323 definitions name a handler and 105 name none. The no-handler set is predominantly trigger definitions, including four active automation entries and legacy event definitions under `nodes/migrated/**`; their runtime route is `FlowExecutor.resolveTriggerDefinition`/trigger dispatch rather than a `NodeHandler`. The remaining handler routes include 55 `player`, 42 `entity`, 24 `world`, 17 `block`, 14 `itemstack`, and 9 `inventory` reflective family identifiers, plus dedicated handler identifiers. Raw handlers/operations do not declare effect class, authorization, cancellation, failure policy, or a descriptor thread policy. Those semantics are therefore explicitly **unverifiable from the definition matrix** and require runtime handler execution evidence.

The durable per-definition artifact is `src/test/resources/fixtures/node-replacement/runtime/c1/definition-runtime-matrix.json`, generated by `worker-1/generate-runtime-matrix.ps1` and verified by `DefinitionRuntimeMatrixTest`. Every row includes source path, definition ID, handler declaration, binding evidence or unresolved reason, handler operation, thread/effect evidence, defaults, conversion evidence, branch outputs/semantics evidence, trigger route, and an explicit status. Runtime binding, thread, effect, conversion, and branch semantics are marked `unverifiable` unless directly declared/proven; this prevents declaration inventory from being misrepresented as semantic parity.

### Mutable-execution baseline

Added `src/test/java/restudio/resync/replacement/evidence/runtime/c1/MutableExecutionBaselineTest.java` and fixture `src/test/resources/fixtures/node-replacement/runtime/c1/mutable-execution-baseline.json`. It proves the current mutable behavior without replacement design: resolver-installed handler configuration overwrites the caller node and an absent literal resolves to the current registry default after registry replacement.

### Measurement and command record

The fixed raw matrix scan completed in 7.1 seconds wall time on this workspace. This is an evidence collection measurement only, not a budget. Focused verification command was `./gradlew.bat test --tests restudio.resync.replacement.evidence.runtime.c1.MutableExecutionBaselineTest` from `ReSync`; it did not reach test execution because Gradle reported `Waiting For Another ReStudio Build To Finish` on two attempts. No build/check/package/server task was run. The test result is therefore pending the existing shared build lock, not reported as passed.

### Irreducible C1 gaps

- The raw catalog does not provide an exhaustive mapping from definition to actual handler instance, supported operation, thread policy, effect, failure, authorization, conversion implementation, or branch behavior.
- Trigger definitions without handlers need separate dispatcher binding evidence; they cannot be labelled missing handlers from JSON alone.
- Current production handler registration requires module construction and platform collaborators, so a complete live binding matrix cannot be safely produced by a unit fixture without broader test-server/module evidence.
- Fixed-fixture graph validation/execution timing remains pending the shared Gradle lock; no speculative runtime performance claim is made.

# Worker 4 Gate 0A Findings

Status: Gate 0A evidence only. Production source and shared-ledger files remain untouched. A focused current-baseline test was added only within the explicitly assigned evidence-test boundary; its serial execution is pending because concurrent ReStudio builds hold the shared Gradle coordination lock.

## Scope And Current Architecture Traced

This slice covers fixture inventory, history/version compatibility, generated Functions, existing fixture builders, baseline instrumentation, and a reproducible acceptance evidence plan.

| Current path | Evidence | Current behavior / defect |
|---|---|---|
| Bundled catalog | `src/main/resources/nodes/**`; `flow/registry/NodeDefinitionLoader.java:291,360,401-404,837`; `flow/registry/NodeDefinitionRegistry.java:29-61` | Loader transforms source and creates fallback descriptions before registering each definition into a mutable registry. This proves NSR-003, NSR-004, and NSR-005 in this slice. |
| Generated Functions | `flow/CustomFunctionNodeDefinitions.java:25-49,51-151`; callers `modules/FlowRuntimeModule.java:302,305` and `modules/FlowModule.java:363` | Every rebuild removes `custom_functions`, reads stored `function` graphs, derives definition/pin IDs from function and parameter names, then registers the batch. Name/order dependency violates the target immutable Function-parameter identity requirement (NSR-013) and separate live discovery violates one catalog source path (NSR-005/016). |
| Live migration | `modules/FlowRuntimeModule.java:303-304`; `flow/migration/FlowGraphMigrator.java:72-127`; `TypedAutomationGraphMigrator.java:50-128`; `FlowStorage.java:1539-1872` | Startup calls both migrators after Function rebuilding. The graph migrator uses a per-graph fence/ledger, backup, direct save, and report; storage also performs legacy asset migration. This is useful partial durability evidence, but not a complete fenced-folder snapshot/dry-run/atomic activation path (NSR-007/015). |
| Direct execution/default resolution | `flow/FlowExecutor.java:197-236,422-541,2152-2175`; `flow/FlowRuntime.java:189-192` | Runtime executes a mutable graph and resolves handlers and definition defaults during traversal; this is not a snapshot-owned execution plan (NSR-006). |
| Extension mutation / unload | `api/ReSyncExtensionManager.java:160-215,232,588,603-758`; `flow/registry/NodeDefinitionRegistry.java:33-61` | Extension registration/unregistration independently touches definitions, handlers, types, resources, validators, conversions, and option sources. No all-or-nothing catalog generation is evidenced (NSR-005/016). |
| Typed graph persistence | `flow/FlowStorage.java:220-281,416,764-819,1443-1483`; `src/test/java/restudio/resync/flow/FlowStorageTypedIdentityTest.java` | Typed graph path writes revision/mutation metadata and tombstones, and current tests cover type separation and stale-copy blocking. It remains a usable legacy fixture source, not replacement acceptance proof. |
| Existing acceptance export | `flow/diagnostics/ProgrammabilityAcceptanceSnapshot.java:18,52-56`; `src/test/java/restudio/resync/flow/diagnostics/ProgrammabilityAcceptanceSnapshotTest.java` | Atomic diagnostic snapshots already inventory registry/handler/resource state. They do not measure catalog compilation, client hydration, graph compilation, migration, snapshot/restore, memory, or UI latency. |

## Required Pre-Implementation Fields

| Field | Gate 0A evidence for this slice |
|---|---|
| Problem IDs addressed | Existing: NSR-003, NSR-004, NSR-005, NSR-006, NSR-007, NSR-013, NSR-015, NSR-016, NSR-018. Proposed: A4-P01 through A4-P05 below. These are worker-local provisional IDs; stable problem-ID allocation is orchestrator-owned. |
| Proposed classification | Current bundled and generated definitions: **Review** until per-definition inventory; `nodes/migrated/**`: **Migration-only** then delete from runtime; generated Function definitions: **Replace** as provenance-bearing catalog contributions; test fixtures: **Retain/Expand**; local runtime folder: **Quarantine as source, sanitize before fixture use**; acceptance diagnostics: **Retain as inventory evidence, not performance evidence**. |
| Replacement goal | A complete, sanitized, hash-pinned fixture corpus and accepted source-version range for one offline replacement upgrade, with deterministic expected diagnostics and measurable baseline evidence. This report does not design the replacement. |
| Invariants | No unsanitized local data leaves `run/plugins/ReSync`; fixture bytes and pre-sanitization hashes are retained under controlled access; source identity/version/contract are recorded; generated Functions preserve complete signatures and wires; failure fixtures preserve opaque bytes; measurements never treat unknown as zero. |
| Affected neighbors | Reviewer A catalog/compiler/migration/snapshot work; Reviewer B generic client hydration/capability fixtures; Reviewer C generated Function runtime and performance paths; ReSyncCore contract/diagnostic/snapshot ownership; Remotely consumer compatibility. |
| Shared contracts consumed | Current `FlowGraph`, `FlowNode`, `FlowConnection`, `FlowResourceReference`, `FlowDataType`, `NodeDefinition`, `NodeDefinitionRegistry`, resource revisions/tombstones, migration ledger, and ReSync protocol inventory. All are current contracts, not approved Gate 0B contracts. |
| Client impact | Existing diagnostics report client connectivity and registry parity but cannot prove hydration timing, opaque unknown preservation, or supported-version fallback. Generated Function descriptors flow through the live registry and can change client-visible definitions without a generation/checksum contract. |
| Persistence impact | Current fixtures must include graph assets, project metadata, tombstones, migration ledgers/backups, resources, triggers, extensions, network state, and generated Function inputs. The inspected local folder has only a small subset, so it is not FX-011. |
| Migration impact | Current per-graph backups and `MigrationLedger` establish useful input cases, but no fixture currently proves full-folder coordinated snapshot, all journal interruption states, quarantine acceptance, or second-run zero change. |
| Compatibility impact | ReSync history contains release commits for 1.0.0/1.0.1/1.0.2/1.1.0/1.2.0/1.3.0 and current head `b2941e66d762ffb76391715fbf4487c068fe12ea`; it has no tags. Remotely history contains release commits through 2.6.0 pre-release and only the `BETA` tag. The remote `node-system-v2` refs are April 28 ancestors of both current branches, not a replacement-upgrade branch. No Git ref or history proves a supported direct-upgrade min/max policy. Therefore no release is yet approved as a supported source bound. Current heads are evidence only, not compatibility promises. |
| Fixtures required | See [fixture-inventory.md](fixture-inventory.md) and [acceptance-evidence-plan.md](acceptance-evidence-plan.md). |
| Exact future files implicated (not owned now) | `ReSync/src/main/java/restudio/resync/flow/registry/NodeDefinitionLoader.java`; `NodeDefinitionRegistry.java`; `CustomFunctionNodeDefinitions.java`; `FlowStorage.java`; `FlowGraphMigrator.java`; `TypedAutomationGraphMigrator.java`; `IdCompatibilityLayer.java`; `FlowRuntimeModule.java`; `ReSyncExtensionManager.java`; `FlowExecutor.java`; `FlowRuntime.java`; `ReSyncCore/src/main/java/restudio/resync/contract/**` (future owner only); `ReSync/src/test/resources/fixtures/**`; current `docs/node-replacement/fixture-manifest.md`, `baseline-metrics.md`, and `compatibility-matrix.md` (shared files, not edited). |
| Unresolved risks | The sanitized-real-backup requirement is unmet; current local folder may contain identity/configuration/operational data; archive completeness is not established; fixture hashes, expected diagnostics, and quarantine decisions are absent; actual client version bounds and performance timings are unknown; no complete snapshot/restore implementation exists to exercise. |

## Proposed Problems (Provisional Worker IDs)

Stable ledger-ID allocation is orchestrator-owned. Do not add these provisional IDs to the shared ledger.

| Proposed ID | Evidence | Impact / target outcome |
|---|---|---|
| A4-P01 | `CustomFunctionNodeDefinitions.buildDefinition` uses `FunctionParameter.getName()` as pin ID, sorts by name, and rebuilds a live registry plugin. | Function signature rename/reorder can alter persisted identity; generated Functions need stable parameter IDs and provenance within the unified snapshot. |
| A4-P02 | `run/plugins/ReSync` is only 10 files (~1.98 MB) and has no graph, trigger, tombstone, extension artifact, network-state, or migration journal proof. | It cannot satisfy FX-011 or direct-upgrade coverage; capture a complete sanitized folder under an approved procedure. |
| A4-P03 | Existing 1.0.0–1.3.0 release commits have no tags and the compatibility matrix leaves all supported source bounds pending. | Direct-upgrade and restore compatibility cannot be asserted; publish explicit commit/build/format bounds before Gate 0B. |
| A4-P04 | `ProgrammabilityAcceptanceSnapshot` inventories registry state but baseline metrics remain unmeasured. | Gate 0B cannot freeze performance budgets without fixed-fixture measurement scripts, environments, repetitions, and result hashes. |
| A4-P05 | `origin/node-system-v2` (`13fd92dc`, 2026-04-28) is an ancestor of ReSync `master`; `upstream/node-system-v2` (`722488b9`, 2026-04-28) is an ancestor of Remotely `ReOS`. The refs predate all current release/durability work and contain no declared migration bounds. | The branch names cannot be used as a compatibility or direct-upgrade proof. Publish an explicit source-build/format support matrix. |

## History Evidence

`git -C ReSync log --all --grep="Release: ReSync"` identifies historic release commits: 1.0.0 (`24e79d16`), 1.0.1 (`17f4fc93`; earlier duplicate commit messages also exist), 1.0.2 (`863c5420`), 1.1.0 (`fe4dc38e`), 1.2.0 (`e55adcc7`), and 1.3.0 (`f9abd2f4`). `git -C ReSync tag -l` returned no tags. `origin/node-system-v2` is `13fd92dc` (2026-04-28) and is the merge base/ancestor of `master`, not a forward migration candidate. Remotely `upstream/node-system-v2` is `722488b9` (2026-04-28) and is likewise an ancestor of `ReOS`; `git -C Remotely tag -l` returns only `BETA`. Relevant ReSync lifecycle history includes typed registry/workflow work (`734d1302`, `922cec5f`, `9fd793ba`), resource durability/contracts (`0b196b96`, `3c91111e`), and the current head. This shows candidate source eras, but no replacement-format migration edges or minimum/maximum support statement.

## Reproducibility

Read-only commands used (PowerShell, from `C:\Users\redxa\ReProjects`):

```powershell
rg --files ReSync\docs\node-replacement
Get-Content -Raw AGENTS.md; Get-Content -Raw RESYNC_RULES.md; Get-Content -Raw ReSync\plan.md
Get-ChildItem ReSync\run\plugins\ReSync -Recurse -File | Get-FileHash -Algorithm SHA256
Get-Content -Raw <candidate-json> | ConvertFrom-Json -Depth 100
rg -n "..." ReSync\src\main\java ReSync\src\test\java
git -C ReSync log --all --format="%H%x09%ad%x09%s" --date=short
git -C ReSync tag -l
```

The earlier inventory phase used no test, Gradle, server, archive, or source-write command. The later authorized baseline phase adds [baseline-measurement.md](baseline-measurement.md) and `src/test/java/restudio/resync/replacement/evidence/performance/CurrentBaselineEvidenceTest.java`; no sample report exists yet because the reviewer will execute the focused test serially after concurrent workers finish.

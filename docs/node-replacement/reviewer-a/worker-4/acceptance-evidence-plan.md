# Worker 4 Reproducible Gate 0A Acceptance Evidence Plan

This is a future evidence plan, not permission to run builds, tests, migrations, or modify source data during Gate 0A.

## Evidence Matrix

| Evidence | Fixture / method | Required recorded result | Current gap |
|---|---|---|---|
| Raw catalog inventory | Read JSON sources directly, fixed deterministic scanner | file/definition/pin counts; raw descriptions, domain/lifecycle/inspector/schema coverage; IDs, redirects, defaults, optional/conditional/raw-reference counts; per-file SHA-256 | Baseline aggregate exists only; reproducible scanner/output hash absent. |
| Generated Function inventory | Fixed Function graph corpus from existing builders | contribution owner/version/provenance; stable Function/parameter IDs; signatures, wires, output hash; rename/reorder behavior | Current generator uses parameter names and live registry rebuild. |
| Extension atomicity | Valid, malformed, collision, missing dependency/capability, missing extension, unload/reinstall fixtures | before/after generation/checksum; activation diagnostic; active catalog unchanged on rejection; opaque node byte/wire preservation | No generation model or fixture set. |
| Direct-upgrade compatibility | Sanitized complete snapshots from every explicitly supported source build | source commit/build/format bounds; dry-run plan hash; expected diagnostics/quarantines; committed output hashes; second run zero changes | No support bounds, complete snapshot, or exact direct-upgrade map. `node-system-v2` remote refs are April 28 ancestors, so cannot supply those bounds. |
| Malformed / large | Hand-authored malformed catalog/graph/manifest data; fixed-seed production-scale corpus | stable code, source location, remediation, quarantine decision; seed/config hash; resource/time/memory measures | Absent. |
| Interruption / restore | Journal-state fixtures for prepared, transforming, validating, staged, activated, failed, rolled-back, committed | restart action and exact active-state hash at each state; no partial active state | Current graph ledger is per graph, not complete-folder journal. |
| Missing extension/client capability | Opaque-node graph with absent provider and older supported capability sets | bytes/wires retained across load/save/export/reconnect; explicit read-only reason | No contract/capability fixture. |
| Performance baseline | Fixed hardware/JDK/OS record; warmup/repetition protocol; existing diagnostic export plus dedicated future harness | median/p95 compile, registry bytes, hydration, graph validation/compile, execution overhead, migration, snapshot/restore, heap, interaction metrics; raw samples and harness commit hash | `baseline-metrics.md` declares all measures unknown. |

## Measurement Protocol

1. Freeze fixture bytes and hashes before measuring; record JDK 21 vendor/version, OS, CPU, memory, commit/build, command line, and capability/extension set.
2. Run a dedicated approved test/harness only after Gate authorization. Use five warmups, then at least ten independent measured iterations per deterministic fixture; report median, p95, min/max, and raw samples in machine-readable form.
3. Separately measure catalog source scanning/validation/canonicalization, payload byte size, client hydration, graph validation and compilation, execution, migration dry run/commit, snapshot, restore, memory, and interactive edit latency. Do not substitute existing registry snapshot export for any unavailable metric.
4. Pin output, plan, manifest, and diagnostic hashes. A mismatch must retain its structured diagnostic and fail evidence comparison; it must not be normalized away.
5. Establish Gate 0A baseline first. Gate 0B may set budgets only from these results or an explicit product-owner decision that names the unavailable metric and risk.

## Read-Only Commands Used For This Report

```powershell
Get-ChildItem ReSync\run\plugins\ReSync -Recurse -File | Get-FileHash -Algorithm SHA256
Get-Content -Raw ReSync\run\plugins\ReSync\assets\project.json | ConvertFrom-Json -Depth 100
Get-Content -Raw ReSync\run\plugins\ReSync\diagnostics\programmability-acceptance-20260717-135605.json | ConvertFrom-Json -Depth 100
rg -n "migrateStoredFlows\(" ReSync\src\main\java
git -C ReSync log --all --grep="Release: ReSync"
git -C ReSync tag -l
```

No performance measurement was run. The commands only enumerated names, byte sizes, hashes, and structural keys; they did not copy fixture data or execute ReSync.

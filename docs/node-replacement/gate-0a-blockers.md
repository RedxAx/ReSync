# Gate 0A Blocker Record

Date: 2026-08-02. Gate: 0A — Current Architecture And Problem Proof.

## Fleet Result

The requested fleet was created exactly:

- Main orchestrator: one Sol owner.
- Reviewers A, B, C: three Sol reviewers at medium reasoning.
- Each reviewer created exactly four Terra workers at medium reasoning.

All workers used disjoint evidence paths and production source remained read-only. Reviewers performed revisions and wrote `synthesis.md`, `trace-signature.md`, and `review.md` under their owned directories.

Reviewer A and B sign the current-system trace with explicit absent paths but withhold Gate 0A approval. Reviewer C withholds the trace signature and Gate approval because semantic parity, recovery, extension losslessness, and complete restore cannot be demonstrated. No reviewer approves Gate 0A.

## Completed Evidence

- Governing files and plan read completely.
- ReSync, Remotely, Rebase, and ReScreen dirty state captured; only user-owned `ReSync/plan.md` was dirty before evidence work.
- Startup, catalog discovery, registry merge, handler binding, graph open/save, execution, conflicts, collaboration, reconnect, extension add/unload, live migration, storage, protocol, client cache/editor, and the absence of complete snapshot/restore traced.
- Raw catalog source hashed and inventoried: 61 files, 1,428 definitions, 7,273 physical pins, 7,278 logical compatibility pins.
- Local data structurally inventoried without copying unsanitized values. It has no persisted project resources and no installed extension jars.
- Mirrored contracts, 21 resource-specific packet families, live compatibility paths, incomplete descriptions, raw identity, mutable execution, and client special cases recorded.
- Stable problem ledger expanded through NSR-032.

## Hard Blockers

### B0A-01 — Complete Sanitized Real Fixture Missing

Required evidence: FX-011, a complete populated ReSync-folder snapshot with source/sanitized hashes, sanitizer identity, participant ownership, build/catalog/extension versions, expected diagnostics, and quarantine expectations.

Observed evidence: `C:\Users\redxa\ReProjects\ReSync\run\plugins\ReSync` is an acceptance run with zero project resources and zero extension jars. Searches of common user folders found no other ReSync data root. Existing repository fixtures are component cases, not a complete populated folder.

Attempted alternatives: existing tester and programmability fixtures were inventoried; the local run folder was inspected structurally; generated fixtures were identified for Gate 3A. A synthetic fixture cannot satisfy the plan's separate real-folder migration requirement or prove unknown installed extensions/local definitions.

Smallest decision: provide the absolute path to a complete ReSync plugin-data folder that may be copied and sanitized, or explicitly declare that no real installation is available and authorize a generated full-folder fixture for Gate 0A/3A while accepting that Gate 3B remains blocked until real data is supplied.

### B0A-02 — Supported Compatibility Window Undeclared

Required evidence: exact supported legacy ReSync sources, Remotely clients/builds, extension contract generations, direct-upgrade/bridge ownership, and restore-compatible build/contract bounds.

Observed evidence: repository tags/history do not define support. ReSync has no tags; Remotely's `BETA` tag predates the relevant systems; `node-system-v2` refs are ancestors.

Attempted alternatives: branch/tag/history and release-message commits were traced. Treating all historical commits as supported is unbounded and unsafe; treating only the current heads as supported is a product policy decision not encoded in source.

Smallest decision: approve the conservative window of the current pre-replacement heads only (`ReSync` `b2941e66d762ffb76391715fbf4487c068fe12ea`, `Remotely` `76c4eb8989aaf856a4f002fa7fe6e56a67c373df`) or provide the additional release/build identifiers that must be supported.

### B0A-03 — Required Non-Test Measurements And Acceptance Runs Not Authorized

Required evidence: registry payload/hydration, interactive editing, live migration/snapshot/restore, supported-client acceptance, and final runtime/server behavior. Some fixed-fixture logic can be measured in JUnit, but the complete plan requires non-test application/server/verification runs.

Repository restriction: only tests may run without explicit permission. Build, check, validation JavaExec, application, server, packaging, publishing, and other Gradle tasks are not authorized by the implementation request alone.

Attempted alternatives: existing diagnostic files and tests were inventoried; they contain no complete current performance baseline and cannot demonstrate live application/server or whole-folder behavior. Compilation alone is explicitly insufficient.

Smallest decision: explicitly permit scoped non-destructive Gradle verification, application/client acceptance, and local ReSync test-server tasks for this replacement, while keeping publishing, deployment, packaging, and external mutations forbidden.

## Affected Paths

- Evidence and approvals: `C:\Users\redxa\ReProjects\ReSync\docs\node-replacement\**`.
- Missing source fixture: a user-provided ReSync plugin-data root outside or inside the workspace.
- Compatibility policy: `compatibility-matrix.md`, future shared contract versions, standalone upgrader, and retained legacy fixtures.
- Measurement commands: ReSync and Remotely Gradle verification/application/server tasks after explicit permission.

## Autonomous Resolution

Resolved by the product owner on 2026-08-02 with explicit direction for the orchestrator to decide from product intent and implementation efficiency:

- B0A-01: `run/plugins/ReSync` is the real-folder source. Preserve its source hashes and sanitize a copy; supplement it with deterministic populated and production-scale generated fixtures for missing graphs, resources, extensions, journals, and network state. Controlled migration always operates on a verified staging copy, never the source folder.
- B0A-02: the supported legacy window is the current pre-replacement heads only: ReSync `b2941e66d762ffb76391715fbf4487c068fe12ea` and Remotely `76c4eb8989aaf856a4f002fa7fe6e56a67c373df`. The standalone upgrader owns that legacy format. Older history is unsupported unless represented by an explicitly retained regression fixture.
- B0A-03: non-destructive local Gradle verification, application/client acceptance, and ReSync test-server tasks are authorized for this replacement. Publishing, deployment, packaging, credential changes, and external mutations remain forbidden.

## Gate Decision

The policy blockers and evidence obligations are resolved. Reviewer A approved after the root-qualified ReSync evidence run completed 29 of 29 tests; Reviewer B approved after all four Remotely evidence suites passed; Reviewer C independently audited the final XML and fixture hashes and approved. The main orchestrator accepts Gate 0A on 2026-08-02. Gate 0B contracts remain unfrozen until their separate design, golden-fixture, and review gate completes.

# Reviewer A Ruthless Review Record

Gate: 0A. Domain: contracts, catalog, and migration.

Final decision: **Approved for Gate 0A current-system evidence. Gate 0B remains unapproved.**

## Final Verification

The accepted verification used one root-qualified ReSync `:test` invocation with exact selectors for the four Reviewer A and five Reviewer C evidence classes. The generated XML under `build/test-results/test` records nine suites, 29 tests, 29 passed, zero failures, zero errors, and zero skipped. The command completed `BUILD SUCCESSFUL`.

Failures found during review were not waived. They drove owner corrections to scanner sort semantics and missing-description assertions, exact legacy migration expectations, non-reflective registry projection, C1 matrix hashing, C2 option/default JSON typing, trigger identity, and test compilation. The final XML is the acceptance result.

## Worker Review

### Worker 1 — Contracts and protocol

Accepted after governance revision. The report maps both protocol generators, the Remotely-owned source dependency, mirrored DTO/serializer families, all 21 resource packet families, server/client routing seams, resource-key limitations, and required pre-implementation fields. Its proposed problem IDs were rejected because they collided with sibling reviewers; they were relabeled A1-P01 through A1-P03.

No factual claim from this report authorizes target contract design. Field-by-field serializer equivalence, recursive unknown preservation, and complete ReSyncVelocity participation remain open.

### Worker 2 — Catalog and extension inventory

Initial output was rejected because it left the five-pin baseline discrepancy unresolved. Revision identified the exact five definitions lacking `inputs`, separated 7,273 physical entries from the 7,278 logical compatibility convention, recorded input/output counts, captured a deterministic source-tree hash and algorithm, and renamed proposals A2-P01 through A2-P04.

Accepted with one reviewer qualification: the arithmetic reconciliation is complete, but no historical scanner proves why the plan originally labeled the logical number “declared pins.” Reviewer A therefore treats 7,273 as physical raw declaration truth and preserves 7,278 only as a separately named compatibility baseline.

### Worker 3 — Migration and persistence

Initial output was rejected for wording that could imply `_id_migration_map.json` was registered as a node definition. Revision now states correctly that underscore-prefixed files are excluded by `NodeDefinitionLoader`, while `IdCompatibilityLayer` consumes the map independently and other `nodes/migrated/*.json` definitions remain active. Proposals were relabeled A3-P01 through A3-P05.

Accepted. The report does not overclaim asset transaction restore or network snapshot records as complete snapshot/restore.

### Worker 4 — Fixtures, compatibility, and performance

Accepted after governance revision relabeled proposals A4-P01 through A4-P05. It records repository fixtures/builders, exact local-candidate hashes and sanitization risks, history refs, the absence of supported upgrade bounds, and a reproducible future acceptance/measurement plan.

The local folder was not accepted as FX-011. No performance figure was accepted because no approved measurement exists.

## Ruthless Gate Checks

| Rejection rule | Current evidence | Result |
|---|---|---|
| No mirrored contract/DTO pair | Protocol, registry, graph, type, and serializer models are mirrored. | Fail: NSR-001/002/018. |
| No duplicate/live legacy catalog | 1,420 definitions remain below active `nodes/migrated`; aliases and loader transforms remain reachable. | Fail: NSR-003/004/012. |
| Contribution collision must be atomic | Registry overwrites by order; extension components activate/unload sequentially. | Fail: NSR-005/016. |
| Execution cannot mutate persisted semantics | `resolveHandler` mutates type/config and runtime reads live defaults. | Fail: NSR-006. |
| New resources must not require client packet routes | 21 types each have seven dedicated CRUD bytes and client reverse maps. | Fail: NSR-008. |
| Typed identity everywhere | Type/id key exists, but server scope and ID-only cache/event/raw selector paths remain. | Fail: NSR-009. |
| Explicit descriptions | 1,237 nodes and 6,215 physical pins lack raw descriptions. | Fail: NSR-011. |
| Stable structural/Function identity | Function pins and several structural paths remain name/position based. | Fail: NSR-013. |
| Structured recoverable diagnostics | Conflict response drops authoritative current revision/payload; diagnostics are split. | Fail: NSR-014/017. |
| Complete fenced snapshot/restore | Only asset-local rollback and network-domain snapshots exist. | Fail: NSR-015. |
| Evidence against current behavior | Required traces exist, with missing paths recorded explicitly. | Pass for current-system trace only. |
| Reproducible inventory | Catalog source tree is hashed and both pin predicates are explicit. | Pass for bundled raw catalog; live/local/extension production inventory remains instance-limited. |
| Fixtures and compatibility complete | ADR-007 accepts FX-013 plus deterministic populated coverage; ADR-008 pins current heads; A/B/C goldens and current/proxy baselines are present, with unavailable live measures explicit. | Pass for Gate 0A evidence. |

## Intentional Behavior Decisions

None are approved in Gate 0A. Classification terms in worker reports record current-to-target disposition only. Shared contracts are still Draft in `contract-freeze-ledger.md`; no worker may implement against them.

## Compatibility and Risk

- Current heads and historical release-message commits are evidence coordinates, not support promises.
- The `node-system-v2` remote refs are ancestors, not an upgrade path.
- The live folder is unsanitized and incomplete; hashes may be retained as evidence, bytes may not be copied without an approved sanitizer and owner decision.
- Missing performance measurements remain unknown, not zero.
- Revision conflicts and reconnects do not yet prove authoritative multi-client convergence.
- Extension unload does not prove in-flight handler safety or lossless unavailable-node round trips.

## Required Reviewer Outcome

Reviewer A signs and approves the current-system Gate 0A evidence in `trace-signature.md`. The failures in the ruthless checks above remain accepted NSR replacement requirements: Gate 0A approval records them; it does not assert that the legacy implementation already satisfies the target invariants.

The orchestrator must keep Gate 0B contracts unfrozen and production replacement work stopped until Gate 0B receives its own explicit approval. No intentional target behavior or contract ownership decision is approved by this review.

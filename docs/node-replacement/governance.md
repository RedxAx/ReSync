# Governance And Ownership

## Repository State Before Replacement

Captured 2026-08-02 before replacement edits.

| Repository | Branch | Upstream State | Dirty State | Replacement Scope |
|---|---|---|---|---|
| `ReSync` | `master` | ahead of `origin/master` by 16 | untracked user-owned `plan.md` only | ReSyncCore contracts, server catalog/runtime/persistence/protocol/migration, fixtures, evidence |
| `Remotely` | `ReOS` | ahead of `upstream/ReOS` by 21 | clean | shared contract consumption, generic protocol/cache/inspector/graph client, legacy client-path retirement |
| `Rebase` | `master` | ahead of `origin/master` by 15 | clean | read-only dependency unless a proven generic dependency defect blocks the replacement |
| `ReScreen` | `master` | ahead of `origin/master` by 12 | clean | read-only UI dependency unless a genuinely reusable visual capability is proven missing |

The untracked `ReSync/plan.md` is user-owned and immutable. Existing ahead commits are user history and must not be rewritten, rebased, reset, or squashed.

## Shared-File Ownership

The main orchestrator is the sole active owner until Gate 0B freeze of:

- `ReSync/ReSyncCore/src/main/java/restudio/resync/contract/**`
- `ReSync/ReSyncCore/src/main/resources/resync/contract/**`
- `ReSync/ReSyncCore/src/test/resources/restudio/resync/contract/**`
- `ReSync/settings.gradle.kts`, `ReSync/ReSyncCore/build.gradle.kts`, and dependency wiring in `Remotely/settings.gradle.kts` or `Remotely/build.gradle.kts`
- canonical catalog schema, serializer, canonicalizer, diagnostic codes, protocol envelopes, migration boundary, snapshot manifest, graph model, type/value system, and resource locator
- `ReSync/docs/node-replacement/contract-freeze-ledger.md` and `decision-log.md`

Only one reviewer slice may own a catalog compiler/registry, serializer, migration map, protocol router, cache foundation, inspector foundation, graph compiler, or execution-plan foundation at a time. Domain JSON files are parallel only when each file has one owner and no worker changes schemas or loaders.

## Reviewer Ownership

| Reviewer | Domain | Gate 0A Writable Evidence | Later Implementation Boundary |
|---|---|---|---|
| A | Contracts, catalog, migration | `docs/node-replacement/reviewer-a/**` | ReSyncCore contracts plus orchestrator-approved server catalog/migration/snapshot seams |
| B | Graph interaction, client independence | `docs/node-replacement/reviewer-b/**` | Remotely generic graph/inspector/cache/protocol projection seams after shared contracts freeze |
| C | Runtime domains, semantic parity | `docs/node-replacement/reviewer-c/**` | ReSync runtime capability bindings, execution plans, and disjoint domain catalogs/handlers after foundation freeze |

Reviewers create four Terra workers each. During Gate 0A their workers are read-only outside the reviewer's evidence directory. Implementation write boundaries are assigned only after Gate 0B and may be narrower than the reviewer boundary.

## Isolation And Merge Order

Gate 0A evidence is produced in the existing ReSync worktree with path-exclusive reviewer directories. No production source is writable during Gate 0A.

After Gate 0B, reviewer implementation uses dedicated worktrees and branches rooted at the captured heads:

- ReSync branches: `codex/resync-contracts`, `codex/resync-runtime`; worktrees under `C:\Users\redxa\ReProjects\.codex\worktrees\resync-node-replacement\`.
- Remotely branch: `codex/resync-client`; worktree under the same replacement root.
- Terra workers share only their reviewer's worktree and have disjoint path ownership. They do not commit or merge independently; the reviewer owns review and branch integration.
- Cross-repository merge order is ReSyncCore contracts, ReSync server foundation, Remotely shared-contract consumption and client foundation, ReSync runtime/domain conversion, Remotely domain-independent UI verification, standalone upgrader and retirement, then release evidence.
- A shared-contract change after freeze pauses every dependent slice. The orchestrator records the decision, updates golden fixtures and compatibility impact, obtains all reviewer approvals, and then redistributes the new contract.

No worker may run non-test Gradle tasks. Tests are scoped to regression-prone behavior. Build, check, shadow, publishing, packaging, and server run tasks remain intentionally unavailable without explicit user permission.

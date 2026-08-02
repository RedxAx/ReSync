# Reviewer B Ruthless Review

Gate: 0A. No production implementation was reviewed or authorized.

## Worker Verdicts

| Worker | Scope | Verdict | Review action |
|---|---|---|---|
| B1 | Mirrored models, cache/hydration, tombstone/revision ordering, server scope | Accepted with reviewer supplements | The report correctly separates plugin descriptor tombstones from resource tombstones and proves missing resource-event ordering. Reviewer source trace supplied the full extension collision/unload and execution mutation paths it marked adjacent. |
| B2 | Canvas/editor/serialization, dynamic pins, Functions, branches/repeatables, identity | Accepted for Gate 0A evidence | Concrete paths prove name/position identity and client special cases. Its proposed single `elementId` abstraction question is rejected as premature Gate 0B design; only the invariant and evidence are retained. |
| B3 | Selectors, descriptions/hints, inspectors/designers/previews | Accepted after revision | First submission was rejected because Trade/NPC/Loot/Automation/World/Recipe/Content/Chat/Message Rule/MOTD/Text Template save/validation routes were incomplete. The revision traces focused-JSON, World multi-operation, Content graph/quick-edit, and preview-only paths and does not allocate a shared fixture ID. |
| B4 | Save/mutation/collaboration/conflict/reconnect/export/import/opaque fallback/compatibility | Accepted for Gate 0A evidence | Conflict, queued reconnect, extension overwrite, compatibility history, export/import absence, and full restore absence are evidence-backed. Its workspace-collapse question is escalated to the orchestrator and not answered as local design. |

## Resumed Worker Verdicts

| Workstream | Verdict | Ruthless review result |
|---|---|---|
| B1 generated inventory | Accepted after two rejections and execution correction | Rejected the opaque checksum-only submission, then rejected incomplete source scope. The final test compares every canonical record across Remotely, RemotelyMod, and protocol ownership and guards known high-risk node, pin, and resource controls. |
| B2 recursive round trip | Accepted after execution correction | Rejected source-only/stdout evidence. The final hard golden distinguishes preserved generic material, discarded typed-object unknown fields, and integer-to-double normalization discovered by the reviewer run. |
| B3 registry/cache baseline | Accepted after three execution corrections | Rejected the false wire/cache equality, the false plugin-order-only claim, and the false cache-file-size relationship. The final fixture pins separate wire/projection hashes and asserts plugin reordering plus the two materialized map defaults. |
| B4 designer/mutation fixtures | Accepted | The matrix is bounded to supported heads, synthetic fixtures are explicitly non-executable evidence, hashes and source anchors are hard checked, and graph/resource export-import absence uses a reproducible zero-result rule. |

## Ruthless Gate Findings

The reports would be rejected for implementation if they preserved any of the following current behavior:

- mirrored ReSync/Remotely graph, descriptor, type, option, registry, serializer, or protocol authority;
- `ReSyncResourceType`/packet-family additions for ordinary new resources;
- selectable raw resource strings or cache/event identities missing server/type/revision scope;
- pin, Function parameter, branch, repeatable, connection, mode, or inspector state keyed by mutable names/positions;
- node-ID, option-source, resource-type, or pin-name client behavior expressible as descriptor capability;
- generated descriptions or missing shared pin/default/conditional hints;
- specialized designer persistence/validation/conflict routes independent from the future shared boundary;
- conflict responses that omit authoritative state or resource events without ordering/tombstone fields;
- queued disconnected saves flushed without defined hydration/rebase behavior;
- extension registration-order collision, partial activation, sequential unload without execution ownership, or lossy missing-provider behavior;
- execution-time mutation of node type/handler configuration or live lookup of defaults;
- startup/save migration, asset-only snapshot claims, or unsupported-client compatibility inferred from repository history.

## Evidence Accepted

The following current behavior is real and may be considered for adaptation only after Gate 0B freezes the governing contracts:

- typed graph revisions/hashes/mutation IDs and typed tombstone primitives in server storage;
- atomic multi-file asset transactions and recoverable per-transaction journals;
- registry checksum/delta-baseline checks;
- workspace sequence/operation-id deduplication and resync;
- option source revision/sequence and contextual query support;
- useful focused editor and rich preview behavior;
- unresolved plugin descriptor caching;
- graph-root unknown-property preservation.

Acceptance here means these seams are evidenced, not that their current contracts are sufficient.

## Cross-Domain Communication

- Reviewer A received evidence for cross-plugin overwrite/unregister loss, per-save migration, conflict revision fields being discarded, absence of complete snapshots, mirrored graph/unknown-field contracts, and name-bound Function migration.
- Reviewer C received evidence for extension collision/unload, missing execution leases, `FlowExecutor` node mutation, and live default resolution.
- The main orchestrator received the undecided Gate 0B surfaces: generic conflict/event ordering, reconnect/queued-save ordering, workspace-versus-durable mutation ownership, recursive opaque fallback, and supported-client release bounds. No contract was frozen locally.

## Closed Evidence Gaps

1. ADR-007 assigns the real persisted-folder source and sanitized complete-folder fixture to the shared fixture owner; B adds generated missing-provider, unknown-material, reconnect/conflict, same-ID, and export-absence fixtures without duplicating that authority.
2. B1 provides the generated exact-record client/Mod/protocol inventory under a declared lexical scope and records its semantic/AST blind spots honestly.
3. B4 proves current generic graph/resource export-import and complete coordinated restore absence; absence is a current-system baseline, while implementation interruption cases remain later acceptance obligations.
4. ADR-008 declares the only supported pre-replacement heads.
5. B3 pins deterministic wire/cache payloads and hydration/cache timing. Interactive behavior remains covered by current-path/source evidence and later UI acceptance work rather than unstable wall-clock assertions.
6. B2 pins recursive unknown preservation, normalization, and loss; B4 supplies unavailable-provider topology and opaque-material fixtures.

## Approval

Reviewer B signs the current-system trace as evidence-backed, including explicit missing paths, and **approves Gate 0A for graph interaction and client independence** at the ADR-008 heads. This is not Gate 0B contract approval. Production replacement remains blocked until the orchestrator freezes the cross-domain contracts and advances the gate.

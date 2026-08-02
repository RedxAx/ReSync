# C3 Persistence Participant And Identity Matrix

Status: current-system evidence. Rows state current support or absence; they do not define a replacement.

| Participant | Owner and root | Identity / mutation | Flush, snapshot, restore | Current lifecycle gap |
|---|---|---|---|---|
| Graph assets | `FlowStorage`; `assets/**`, `assets/project.json`, `.tombstones/**` | graph type + id, revision, mutation ID, hash; cache/callback has ID-only seams | `AssetTransactionManager.commit`; asset restore preview/restore only | constructor invokes live migration/reclassification; no complete-folder participant registration |
| Project metadata | `FlowStorage`; `assets/project.json` | presentation metadata contains resource paths; updated with selected graph saves | committed with selected graph write | startup reconciliation can recreate/reclassify metadata/resources |
| Trigger bindings | `TriggerRegistry`; `triggers.json` | binding map key is raw binding ID; payload contains raw flow ID/type/context | atomic single-file write; no flush registration/snapshot participant | dispatcher keeps separate raw flow/start-node map; whole-list/bulk replacement semantics; `ConcurrentHashMap.values()` does not preserve fixture/list order |
| Automation definitions | `ReSyncJsonResourceStorage`; resource assets/legacy folders | raw `(type,id)`; `FlowResourceReference(kind,id,"server",...)` | store-level writes; no coordinated participant evidence | no uniform revision/mutation/tombstone event and definition lookup is raw ID |
| Automation runtime tasks | `AutomationTaskService`/`AutomationTaskStore`; `runtime/automation-tasks.json` | task UUID plus raw definition ID/scope/owner; persisted arguments/signature version | writes before scheduling and at state changes; restores task records on startup | no definition revision, typed locator, plan identity, fence, snapshot participant, or durable quarantine report |
| JSON/custom content | `ReSyncJsonResourceStorage`, custom-content storage/services | `(type,id)` listeners; derived runtime refresh separate | per-store writes; no complete participant proof | revision/mutation/tombstone/event shape differs from graph route |
| Network resources | `NetworkResourceSynchronizer` + manifest store/network hub state | `type/id`, expected revision, payload hash, deleted flag; per-key queue | reconciles on connect; manifest maintained separately | no common mutation ID/typed server locator or proven restart+remote-tombstone fixture |
| Jobs | `FlowJobRegistry` | random UUID and optional owner string | in memory only; shutdown cancels active records | not durable and not snapshot-participant evidence |
| Cross-system closure | Reviewer A owns the full-folder manifest and cross-system participant/flush/snapshot/restore absence matrix | not duplicated by C3 | reference Reviewer A's final owned evidence and hashes when published | C3 contributes only the trigger, automation, resource-registry, and network rows above; no claim of complete-folder closure |

## Focused Fixture/Test Evidence

- `trigger-bindings.json` is a deterministic, synthetic trigger fixture. It contains no user data.
- `automation-persistent-task.json` is a deterministic synthetic persisted-task record. `C3LifecycleEvidenceTest` reloads and reserializes it, confirms that current task identity is raw definition ID plus owner ID, and records the absence of `definitionRevision`, catalog generation, and plan checksum record fields.
- `network-resource-mutations.json` is a deterministic synthetic same-ID cross-type save/delete representation. The focused test structurally parses both rows and compares type, shared resource ID, expected revision, payload representation, and deleted flag with constructed current `NetworkResourceMutation` values; it also records the absent `mutationId` and `serverId` fields. Every fixture stream is asserted non-null.
- The first serial C3 test run failed because it assumed fixture order (`event-primary` first) while `TriggerRegistry` returns `ConcurrentHashMap.values()` order (`system-primary` observed first). The test now asserts the complete ID set and retains the raw shared flow-identity assertion. No C3 test claims a complete snapshot/restore, task-definition revision binding, remote tombstone restart, or network manifest recovery: those implementations are absent or their required participant boundary is not present in the current seams.

## ADR-007 Local Source Handling

No ADR-007 local source data was opened, copied, staged, or used by C3. The fixture above is synthetic; no unsanitized source data or diagnostics is disclosed.

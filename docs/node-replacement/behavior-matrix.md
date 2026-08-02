# Current Behavior Matrix

This matrix records current observable behavior to preserve or intentionally change. Reviewer domain appendices expand every node family and structural system.

| Area | Current Inputs | Current Observable Behavior | Failure / Known Defect | Target Classification |
|---|---|---|---|---|
| Bundled catalog startup | Recursive `nodes` classpath tree | Loader discovers JSON, transforms legacy shape, fills metadata, registers mutable definitions | Invalid/duplicate contribution can be handled per definition; inferred behavior masks raw defects | Replace |
| Extension contribution | Plugin/classloader definitions and runtime handlers | Definitions and handlers become live through separate mutable registries | Partial activation, collision/order, unload ownership not globally atomic | Replace |
| Graph open | Resource type/ID or legacy ID/folder plus cached client data | Server/client serializers hydrate separate graph models and registry projections | Type inference, fallback, stale cache, unknown-field loss need per-path proof | Replace |
| Graph save | Client graph payload, expected revision in supported paths | Server persists graph/resource and responds through resource-specific protocol | Authority, mutation ID, hash, metadata, runtime state, collaboration event not uniformly one transaction | Replace |
| Revision conflict | Expected and authoritative revision | Some storage mutations reject stale writes | Recovery and client convergence vary by resource route | Adapt into generic transaction |
| Node execution | Mutable graph node, live registry definition, handler name, runtime inputs | Interpreter resolves handler/defaults/connections during execution and mutates runtime state | Live catalog/default changes can alter saved graph semantics; no immutable plan | Replace |
| Function call | Function graph, parameter names/order, generated definitions | Runtime locates start/return nodes and binds maps by names | Rename/reorder identity and generated catalog lifecycle are fragile | Replace |
| Typed selection | Option source plus current string value | Client selector queries or remembers IDs | Many selectable resources remain raw strings or lack server/type scope | Replace |
| Conditional/dynamic pins | Descriptor hints and node-specific client logic | Canvas changes visible pins and special structures | Identity and behavior are partly label/position/node-ID driven | Replace |
| Collaboration/reconnect | Workspace revision/patches and registry/resource events | Client caches and open designers reconcile through several paths | Ordering, tombstones, unknown data, mutation IDs not uniform | Replace |
| Legacy graph migration | Loader transforms, ID map, runtime migrators, startup storage sweeps | Old IDs/pins/files are repaired while normal runtime operates | Permanent compatibility surface, nondeterministic broad startup mutation | Delete after offline upgrade |
| Snapshot/restore | Network-specific snapshots and local backups | Partial/domain-specific backup facilities exist | No complete fenced ReSync-folder transaction or atomic full restore | Add single coordinated service |
| Missing extension | Stored unknown node plus absent handler/descriptor | Behavior depends on deserialization and client fallback path | Lossless round trip and unavailable-node experience are not guaranteed | Replace |
| Catalog change | Mutable registry changes/cache refresh | Clients receive snapshots or cache data | No immutable generation/checksum invalidation of compiled plans | Replace |

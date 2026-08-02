# Worker 1 — Contracts, Protocol, Resource And DTO Trace

Status: Gate 0A evidence only. No replacement design, production edit, test, Gradle, or build command was performed.

## Scope And Evidence Boundary

This report traces the current dependency direction, duplicated contracts, resource packet inventory, serializers/schemas, and DTO ownership across ReSync, ReSyncCore, ReSyncVelocity, and Remotely. It does not claim complete traces for execution, UI, extension unload, or snapshots; those are outside this slice except where they directly affect the traced boundaries.

All paths below are absolute. The only modified path is this report.

## Current Architecture Traced

### Dependency And Generated Protocol Direction

- `C:\Users\redxa\ReProjects\ReSync\build.gradle.kts:53-59` declares `protocolContractFile` as `../Remotely/contracts/resync-protocol.json`, adds generated source to `main`, and generates `restudio.resync.contracts.ReSyncProtocolContract`. The generator embeds packet constants and resource descriptors.
- `C:\Users\redxa\ReProjects\Remotely\build.gradle.kts:310+` independently reads its local `contracts/resync-protocol.json` and generates `redxax.oxy.remotely.data.flow.ReSyncProtocolContract` with the same process.
- The common source is `C:\Users\redxa\ReProjects\Remotely\contracts\resync-protocol.json`. Its `packageNames` explicitly produce separate ReSync and Remotely package names. This makes ReSync’s server build depend on client-repository source and makes protocol source authority client-owned.
- `C:\Users\redxa\ReProjects\ReSync\ReSyncCore` is an included shared dependency (`ReSync/build.gradle.kts:20`) and already contains `restudio.resync.resource.ReSyncResourceKey`; however it does not own the node, graph, catalog snapshot, option, protocol-envelope, or diagnostic contract family. No ReSyncVelocity contract-owner evidence was found in the files searched.

### Catalog Discovery, Registry, And Extension Boundary

- `C:\Users\redxa\ReProjects\ReSync\src\main\java\restudio\resync\modules\FlowRuntimeModule.java:267,405` constructs `NodeDefinitionLoader` and loads definitions. Loader directory/classpath scans recurse all JSON under the selected root (`...\flow\registry\NodeDefinitionLoader.java:104-122, 204-239`), excluding only filenames beginning `_`; thus the active `src/main/resources/nodes/migrated/**` tree is loadable.
- `NodeDefinitionLoader.applyCompatibilityTransforms` (`:291-345`) rewrites legacy pins, including `id` to `name`, and infers handler property data from node IDs. `parseSingle` (`:358+`) fills category, display name, availability, hidden reason and description when source omits them. Its type inference/defaulting continues further in that same class.
- `C:\Users\redxa\ReProjects\ReSync\src\main\java\restudio\resync\flow\registry\NodeDefinitionRegistry.java:8-105` is singleton mutable state keyed only by `String nodeId`; `register` overwrites a prior owner and removes it from the previous plugin list (`:29-48`); `registerAll` mutates one definition at a time (`:50-57`); `unregisterPlugin` removes its definitions directly (`:59-75`). There is no validated aggregate contribution, generation, snapshot swap, checksum, or transaction.
- `C:\Users\redxa\ReProjects\ReSync\src\main\java\restudio\resync\api\ReSyncExtensionManager.java:595` creates a loader for extension definitions. This supports the dossier conclusion that extension definition discovery and handler registration are independent mutable paths. Exact unload/drain ordering remains an evidence gap for this worker.

### Typed Resource Contract And Persistence Boundary

- `C:\Users\redxa\ReProjects\ReSync\ReSyncCore\src\main\java\restudio\resync\resource\ReSyncResourceKey.java:6-25` is a two-part record `(type,id)`, lowercases type and provides a NUL-separated token. It deliberately distinguishes resource types but contains no server identity, revision, mutation ID, payload hash, deletion/tombstone state, or provenance.
- `C:\Users\redxa\ReProjects\ReSync\src\main\java\restudio\resync\flow\FlowStorage.java:61-66` retains `Map<String,...>` graph, GUI, scoreboard, tab, project-metadata, and asset-file indexes; its `saveGraph` overloads start at `:199,220`. These are server-local and not uniformly typed keys. The live migration entry occurs in its startup path at `:102`; `migrateLegacyAssets` at `:1539-1552` calls legacy flow/resource/custom-content migration and command classification; additional legacy command discovery is at `:1839-1999`.
- `ReSyncResourceCatalog` (`C:\Users\redxa\ReProjects\ReSync\src\main\java\restudio\resync\resources\ReSyncResourceCatalog.java:9-99`) materializes generated `ResourceContract[]` into mutable static `BY_TYPE` and packet-to-resource maps. Its lookup/default APIs accept type strings and `byFlowPacket(byte)` maps each resource-specific route.
- `ReSyncManagedResource.FlowPackets` (`...\resources\ReSyncManagedResource.java:46-56`) explicitly models a distinct request/list/data/list/save/delete/ack family per resource type.

### Server/Client DTO And Serializer Mirror Map

| Concept | ReSync current authority/copy | Remotely current copy | Finding |
|---|---|---|---|
| Node descriptor | `...\resync\flow\registry\NodeDefinition.java` | `...\remotely\flow\registry\NodeDefinition.java` | Mirrored mutable models. |
| Registry snapshot | `...\resync\flow\sync\NodeRegistrySnapshot.java` | `...\remotely\flow\sync\NodeRegistrySnapshot.java` | Byte-for-byte conceptual mirror, including local `FlowDataType`, option-source type resolution/defaulting, contract constants, and mutable lists. |
| Snapshot request/plugin payload/resource/option/conversion metadata | `...\resync\flow\sync\{NodeRegistryRequest,NodePluginPayload,FlowResourceMetadata,FlowOptionSourceMetadata,FlowConversionRule}.java` | Same filenames under `...\remotely\flow\sync\` | Parallel DTO implementations, not ReSyncCore contracts. |
| Graph/node/connection/type/ref/serializer | `C:\Users\redxa\ReProjects\ReSync\src\main\java\restudio\flow\data\{FlowGraph,FlowNode,FlowConnection,FlowDataType,FlowDataTypeAdapter,FlowTypeRef,FlowSerializer}.java` | `C:\Users\redxa\ReProjects\Remotely\src\main\java\redxax\oxy\remotely\flow\data\` with equivalent names | Mirrored graph/value interpretation and serializer surface. |
| Protocol constants/resource descriptor | Generated `restudio.resync.contracts.ReSyncProtocolContract` | Generated `redxax.oxy.remotely.data.flow.ReSyncProtocolContract` | Same JSON generated twice into different packages. |
| Typed resource key | `ReSyncCore/.../ReSyncResourceKey.java` | consumed by `Remotely/.../ReSyncFlowClient.java` | This is one useful shared contract, but lacks server scope and does not eliminate parallel resource/type models. |

`NodeRegistrySnapshot` on both sides applies a local fallback: if an option source says `string` and exactly one type metadata item names that catalog source, it changes the source value type to that inferred type (`...\NodeRegistrySnapshot.java:181-196` in each product). This is duplicated interpretation and silent runtime defaulting.

### Protocol Routes And Client Hydration

- `C:\Users\redxa\ReProjects\ReSync\src\main\java\restudio\resync\modules\FlowModule.java:292-354` dispatches by packet byte: collaboration packets first, then `FlowResourcePacketRouter`, then a switch for special routes. `FlowNodeRegistryPacketHandler` builds mutable snapshots and computes registry checksum (`:106-214`).
- `C:\Users\redxa\ReProjects\ReSync\src\main\java\restudio\resync\modules\flow\FlowResourcePacketRouter.java` and `FlowResourcePacketHandler.java` are the resource-specific server route seam. `FlowPacketSender` contains resource response/ack writers, including an ID-only acknowledgement writer (`:523+`).
- `C:\Users\redxa\ReProjects\Remotely\src\main\java\redxax\oxy\remotely\data\flow\ReSyncFlowClient.java:1245-1337` first maps incoming bytes through `ReSyncResourceType.byDataResponse/byListResponse/bySaveAck`, then uses a switch for other routes. Snapshot parsing is at `:2322+`; it parses JSON to the client `NodeRegistrySnapshot` and checks only an integer contract range (`:2370+`).
- `C:\Users\redxa\ReProjects\Remotely\src\main\java\redxax\oxy\remotely\data\flow\ReSyncResourceType.java:239-256` uses packet-byte reverse lookups. `ReSyncResourceDragPayload` is an unshared raw record `(type,id,displayName,path)` at `...\remotely\flow\data\ReSyncResourceDragPayload.java:3`.
- Client pending deletes use a `Map<String, ReSyncResourceKey>` indexed by request ID (`ReSyncFlowClient.java:178,3066`) and so acquire typed local resource identity only after a route/type-specific delete is chosen. Server identity is held separately, not embedded in this key.

### Concrete Server Protocol Seams (Follow-up Inventory)

- `C:\Users\redxa\ReProjects\ReSync\src\main\java\restudio\resync\server\ReSyncServer.java:299-351` decodes WebSocket frames, then `handlePayload` dispatches to modules; bridge frames use the separate `onBridgeMessage`/`handleBridgePayload` route (`:269,351-383`). `completeHandshake` (`:411+`) accepts client identity/capabilities, but current Flow snapshot capability validation remains independently implemented in the node registry/client DTO path.
- `...\modules\FlowModule.java:292-354` is the Flow-channel byte dispatcher. It directly special-cases collaboration byte IDs, calls `resourceRouter.handle(session, packetId, buffer)` at `:323`, and then switches special flow packets. This is a concrete routing dependency for resource and feature additions.
- `...\modules\flow\FlowBlueprintPacketHandler.java:75-292` owns legacy Flow request/save/delete/list/trigger routes; `handleSave` (`:95`) and `handleDelete` (`:194`) use `FlowMutationPayloadReader`, but `migrateLegacyCommandBindings` (`:327-436`) runs in the handler construction/startup path. Saved/deleted graph binding notifications are resource-type/string based (`:310-320,437-456`), and command/event binding reconstruction is in `:458-552`.
- `...\modules\flow\FlowNodeRegistryPacketHandler.java:94-165` parses `NodeRegistryRequest`, checks contract conditions, and returns a mutable `NodeRegistrySnapshot`; `buildPluginPayloads` (`:201`) and `computeRegistryChecksum` (`:187-214,249-358`) derive payload/checksum from current registry state. `populateServerMetadata` (`:451-719`) derives type, editor, catalog-source, category, option-source and conversion metadata in server code rather than from a contract-owned catalog snapshot.
- `...\modules\flow\FlowResourcePacketRouter.java:121-139` registers adapters/lifecycle; its graph adapter (`:145-273`) implements get/list/deserialize/serialize/id/save/delete/validate/duplicate/reload and route-specific data/list/ack senders. It consequently remains a generic adapter mechanism behind a resource-specific packet table, not a generic protocol envelope. It also has separate lifecycle registration (`:121`) and operation-set helpers (`:1210+`).
- `...\modules\flow\FlowPacketSender.java:74-177,226-250` exposes dedicated send/save-ack/list methods for Flow, GUI, scoreboard, tab, custom content, project metadata and JSON resources. `sendIdAck` (`:484-542`) serializes ID/request/revision/hash variants; `sendStringList` (`:542`) serializes raw ID lists. `sendNodeRegistrySnapshot` (`:315`) and option catalog methods (`:328-362`) are separate payload forms. Resource change/deletion events remain arbitrary JSON strings (`:448-452`).
- `C:\Users\redxa\ReProjects\ReSync\src\main\java\restudio\resync\protocol\ReSyncProtocolInventory.java:16-35` reflects over the generated server `ReSyncProtocolContract` and labels every public primitive/string constant as `owner = shared-contract`. That label is inaccurate as ownership evidence: the generated class is sourced from Remotely JSON per `ReSync/build.gradle.kts:53+` and is not a ReSyncCore-owned contract.
- Server graph DTOs are directly present in `C:\Users\redxa\ReProjects\ReSync\src\main\java\restudio\flow\data\**`, including `FlowGraph`, `FlowNode`, `FlowConnection`, `FlowDataType`, `FlowDataTypeAdapter`, `FlowTypeRef`, and `FlowSerializer`; the matching Remotely files are an additional concrete mirror, not generated consumption of a shared model.

## Resource Packet Inventory

The following current resource types are generated from the Remotely-owned JSON. Columns are request/list-request/data/list/save/delete/save-ack byte values.

| Type | Packet bytes |
|---|---|
| flow | 1 / 9 / 2 / 10 / 3 / 8 / 7 |
| function | 231 / 232 / 233 / 234 / 235 / 236 / 237 |
| command | 238 / 239 / 240 / 241 / 242 / 243 / 244 |
| gui | 17 / 20 / 18 / 21 / 19 / 22 / 23 |
| scoreboard | 24 / 26 / 28 / 29 / 25 / 27 / 30 |
| tab | 32 / 34 / 36 / 37 / 33 / 35 / 38 |
| custom_content | 48 / 54 / 50 / 49 / 51 / 52 / 53 |
| project_metadata | 80 / 81 / 82 / 83 / 84 / 85 / 86 |
| chat | 103 / 104 / 105 / 106 / 107 / 108 / 109 |
| motd_profile | 145 / 146 / 147 / 148 / 149 / 150 / 151 |
| message_rule | 152 / 153 / 154 / 155 / 156 / 157 / 158 |
| recipe_definition | 159 / 160 / 161 / 162 / 163 / 164 / 165 |
| text_template | 166 / 167 / 168 / 169 / 170 / 171 / 172 |
| advancement_tree | 173 / 174 / 175 / 176 / 177 / 178 / 179 |
| dialog | 180 / 181 / 182 / 183 / 184 / 185 / 186 |
| trade_profile | 189 / 190 / 191 / 192 / 193 / 194 / 195 |
| npc_definition | 196 / 197 / 198 / 199 / 200 / 201 / 202 |
| loot_table | 203 / 204 / 205 / 206 / 207 / 208 / 209 |
| variable_definition | 210 / 211 / 212 / 213 / 214 / 215 / 216 |
| timer_definition | 217 / 218 / 219 / 220 / 221 / 222 / 223 |
| schedule_definition | 224 / 225 / 226 / 227 / 228 / 229 / 230 |
| worldgen, world | no Flow resource packet family |

Additional Flow protocol bytes include registry 11/12/13, option catalog 55/56, resource changed/deleted 93/94, workspace 110-116, activation 117/118, and several editor/debug/preview routes. These coexist with the per-resource matrix, rather than forming one typed operation envelope.

## Problem IDs Addressed And Proposed

- NSR-001: confirmed with both build generators and `Remotely/contracts/resync-protocol.json` ownership.
- NSR-002: confirmed DTO and graph/type/serializer mirror inventory above.
- NSR-003: confirmed compatibility transforms and generated description/default behavior in `NodeDefinitionLoader`.
- NSR-004: confirmed recursive active loader behavior reaches the migrated resource tree.
- NSR-005: confirmed per-definition mutable registration, owner replacement, and direct plugin removal.
- NSR-007: confirmed startup legacy migration and ID-only caches in `FlowStorage`.
- NSR-008: confirmed all 21 resource-specific CRUD packet families plus resource packet routers on both ends.
- NSR-009: confirmed a shared type/id key without server scope, plus several `Map<String,...>` cache/index and raw drag paths.
- NSR-014: confirmed registry diagnostics are `Map<String,Object>` and independent snapshot/contract DTO surface; complete diagnostics inventory remains outside scope.
- NSR-017: confirmed version gating is integer-range based and snapshot local inference can rewrite option value type.
- NSR-018: confirmed generation-time models and post-load defaulting/inference are dispersed rather than a single schema/canonicalizer/defaulting authority.

Provisional worker-local findings (do not add to shared ledger here; stable problem-ID allocation is orchestrator-owned):

- A1-P01 — `NodeRegistrySnapshot` is mirrored and mutates option-source types from a heuristic on both products; unknown/default behavior can drift and is not lossless/contract-owned.
- A1-P02 — Resource identity is type/id only even on cross-server client surfaces; independently keyed raw string caches and payloads remain. This violates the required server/type/id identity.
- A1-P03 — The resource packet contract allocates seven byte routes per resource type (and separate client/server reverse maps), making each new type a protocol and Remotely routing change.

## Proposed Classification And Replacement Goal

This is classification evidence, not an implementation design.

| Current surface | Classification | Goal required by plan |
|---|---|---|
| Remotely-owned JSON and both protocol generators | Delete/Replace | One ReSyncCore-owned shared model/codec/schema with no ReSync source reach into Remotely. |
| Mirrored descriptor, snapshot, graph/type/value and serializer DTOs | Replace/Delete | Exact ReSyncCore contract consumption; only explicitly non-authoritative UI/runtime adapters may remain. |
| `ReSyncResourceKey` | Adapt | Shared server/type/id locator plus revision/mutation/tombstone/hash semantics at applicable boundaries. |
| `NodeDefinitionLoader` transforms/defaults | Replace/Delete | Strict explicit source descriptor parsing and one contract-owned defaulting/validation service. |
| Mutable registry and extension registration | Replace | Atomic deterministic catalog contribution compiler/snapshot. |
| Resource-specific CRUD packets/routes | Delete | Versioned generic typed-resource and option-query envelopes with capability negotiation. |
| `FlowStorage` legacy startup migration | Migration-only then Delete | Offline/versioned fenced upgrader; normal startup stops reinterpretation. |
| Existing typed asset transaction/tombstone facilities | Retain/Adapt | Reuse under generic typed durable transaction, preserving proven safety behavior. |

## Invariants, Neighbors, And Impact

Invariants required for any later change: ReSyncCore/dedicated ReSync dependency-free contract ownership; server/type/id identity; server authority/revision/mutation/tombstone ordering; unknown field preservation; deterministic canonical serialization; atomic catalog and extension activation; no resource-specific client route for a normal new type; no runtime migration/default inference.

Affected neighbors: `ReSyncCore` resource/workspace contracts; ReSync Flow module, resource catalog/storage/asset transactions, runtime module, extension manager, packet sender/router/handlers, migration classes; Remotely `ReSyncFlowClient`, `FlowManager`, node registry/cache, drag payloads and studio resource UI; ReSyncVelocity/network participants, which need a specific snapshot/protocol participation trace before any contract freeze.

Shared contracts currently consumed: `ReSyncResourceKey` is the only clearly shared typed resource record found. ReSync uses generated `restudio.resync.contracts.ReSyncProtocolContract`; Remotely uses a separately generated package-local counterpart. Flow category/type metadata imported by both snapshots is not sufficient evidence of one complete shared contract.

Client impact: breaking current byte-routing and mirrored Gson DTO hydration requires a negotiated envelope/capability transition. Current integer contract check admits only v2. Unknown data preservation is unproven.

Persistence/migration impact: persistence currently combines typed and raw string maps and runs startup legacy migrations. Packet/DTO replacement must not reclassify graphs from project metadata or lose type/id state. Existing legacy migration code is reachable from normal storage startup and must later become offline-only, after fixture-backed conversion and restore evidence.

Compatibility impact: exact supported ReSync/Remotely release bounds and old-client fallback behavior are unrecorded in `compatibility-matrix.md`; no compatibility promise can be frozen from this evidence.

## Fixtures Required

- FX-007 cross-server and cross-type same-ID resources through cache, protocol, storage, collaboration, reconnect, and tombstones.
- FX-008 colliding/invalid extension contributions proving last valid catalog remains active and unload/reinstall preserves opaque node data.
- FX-012 unknown descriptor/protocol fields, unknown type/editor/capability, additive client capability, and an old v2 client round trip with no data loss.
- A packet-route inventory fixture generated from the contract JSON, asserting every current packet/type mapping before migration.
- A serializer golden corpus with the same node registry, graph, resource mutation, and option catalog values decoded by both existing products, including defaults inferred today.
- FX-006 generated Function nodes/graphs to expose graph/type/serializer mirror and parameter-name identity behavior.
- FX-011 is still absent; complete sanitized folder snapshot is mandatory before any migration/restore approval.

## Exact Future Files Implicated (Not Owned Now)

`C:\Users\redxa\ReProjects\ReSync\build.gradle.kts`; `C:\Users\redxa\ReProjects\Remotely\build.gradle.kts`; `C:\Users\redxa\ReProjects\Remotely\contracts\resync-protocol.json`; ReSyncCore contract paths named in `docs/node-replacement/contract-freeze-ledger.md`; `ReSync/.../flow/registry/{NodeDefinitionLoader,NodeDefinitionRegistry,NodeDefinition}.java`; `ReSync/.../flow/sync/**`; `ReSync/.../resources/{ReSyncResourceCatalog,ReSyncManagedResource}.java`; `ReSync/.../flow/FlowStorage.java`; `ReSync/.../modules/FlowModule.java`; `ReSync/.../modules/flow/{FlowBlueprintPacketHandler,FlowPacketSender,FlowResourcePacketRouter,FlowResourcePacketHandler,FlowNodeRegistryPacketHandler}.java`; `ReSync/.../protocol/ReSyncProtocolInventory.java`; `ReSync/.../server/ReSyncServer.java`; `ReSync/.../flow/data/**`; `ReSync/.../flow/migration/{FlowGraphMigrator,TypedAutomationGraphMigrator,IdCompatibilityLayer}.java`; `Remotely/.../data/flow/{ReSyncFlowClient,ReSyncResourceType,FlowGraph,FlowNode,FlowConnection,FlowDataType,FlowDataTypeAdapter,FlowTypeRef,FlowSerializer,ReSyncResourceDragPayload}.java`; `Remotely/.../flow/{registry,sync,cache}/**`; and ReSyncVelocity/network synchronization implementation paths after a dedicated trace.

## Unresolved Risks And Evidence Gaps

- I did not establish every ReSyncVelocity packet/network persistence participant, full WebSocket/bridge envelope schema, or a complete snapshot/restore path; Gate 0A cannot sign those as complete from this report.
- No raw catalog count was recomputed. The plan baseline is accepted as provided; recursive loader reachability, not every individual JSON definition, was inspected here.
- Exact data-loss behavior for unknown Gson fields, old client reconnect, collaboration mutation ordering, and resource revision/tombstone events requires dedicated lifecycle traces/fixtures.
- Flow graph DTOs are clearly mirrored by class/path/name, but field-by-field equivalence needs generated serializer fixtures rather than assumption.
- Contract JSON itself also embeds resource-specific defaults and dialog helper code in the generated output. That mixes protocol definition and resource behavior; full impact needs a contract-owner review.

## Reproducible Read-Only Commands Used

```powershell
Get-Content -Raw AGENTS.md
Get-Content -Raw RESYNC_RULES.md
Get-Content ReSync\plan.md
Get-ChildItem -Recurse -File ReSync\docs\node-replacement
Get-Content <each Gate 0A document>
rg -l -g '*.java' -g '*.kt' -g '*.json' '<contract/storage/protocol symbols>' ReSync ReSyncCore ReSyncVelocity Remotely
rg -n -g '*.gradle.kts' -g '*.java' -g '*.json' '<generation/packet/migration symbols>' ReSync Remotely
$c = Get-Content -Raw Remotely\contracts\resync-protocol.json | ConvertFrom-Json; $c.resources
Get-Content <the concrete source files cited above>
```

No test, Gradle, build, server, or source-mutating command was run.

## ADR-007 Through ADR-009 Evidence Scanner Increment

Status: Gate 0A evidence code only. The scanner makes the current source inventory reproducible and deliberately fails on pinned-source drift. It does not introduce or approve a replacement contract.

### Written Scanner And Machine-Inventory Paths

- `C:\Users\redxa\ReProjects\ReSync\src\test\java\restudio\resync\replacement\evidence\catalog\CatalogEvidenceScanner.java` recursively scans the active bundled JSON tree using the current loader's underscore-file exclusion rule. It returns physical raw counts separately from the five-input logical compatibility convention and calculates the canonical source-tree SHA-256 from sorted `file-hash + two spaces + slash-relative-path + newline` records.
- `C:\Users\redxa\ReProjects\ReSync\src\test\java\restudio\resync\replacement\evidence\contracts\ContractEvidenceScanner.java` parses the Remotely-owned protocol JSON directly, inventories all resource CRUD packet families, and hashes eight concrete server/client DTO/serializer mirror pairs.
- `C:\Users\redxa\ReProjects\ReSync\src\test\java\restudio\resync\replacement\evidence\EvidenceScannerTest.java` scans each source twice and compares complete records for deterministic output; it additionally compares results to pinned machine fixtures, so catalog, protocol, and tracked DTO source drift fails deterministically.
- `C:\Users\redxa\ReProjects\ReSync\src\test\resources\fixtures\node-replacement\catalog\catalog-inventory.json` pins physical/logical catalog metrics and the current bundled source hash.
- `C:\Users\redxa\ReProjects\ReSync\src\test\resources\fixtures\node-replacement\catalog\contract-protocol-inventory.json` pins the protocol hash, all 21 × 7 resource routes, and all 16 individual files in eight mirrored DTO/serializer pairs.

### Pinned Current Metrics And Hashes

- Bundled eligible catalog tree: 61 files, 1,428 definitions, 3,584 physical inputs, 3,689 physical outputs, 7,273 physical pins, 7,278 logical compatibility pins, five definitions without `inputs`, 6,215 missing physical pin descriptions, and 6,220 logical missing descriptions. Source tree SHA-256: `c83f2e2828ee599c18563beaab2a131a160acceeb5b7ae8220064124188ff0e8`.
- Protocol source SHA-256: `8fcc0f84538c4c9907c58e67a38670f51d4db3b992734e479e5ec50afc4ae527` for `C:\Users\redxa\ReProjects\Remotely\contracts\resync-protocol.json`.
- The machine protocol fixture is the complete 21-family × seven-operation table. `worldgen` and `world` are recorded by the scanner source as resource entries without a Flow CRUD family and are intentionally excluded from the 21×7 count.
- Mirror evidence is hash-pinned for `NodeDefinition`, `NodeRegistrySnapshot`, `FlowGraph`, `FlowNode`, `FlowConnection`, `FlowDataType`, `FlowTypeRef`, and `FlowSerializer` in their current ReSync and Remotely locations. Separate hashes demonstrate separate physical source ownership even where semantics are intentionally parallel.

### Verification Result And Commands

The authorized focused command was issued twice, but both invocations stopped after 30 seconds at the shared workspace coordination message `Waiting For Another ReStudio Build To Finish`; neither reached task execution or emitted a test result. Per Reviewer A direction, no further retry was made while another shared build is active. This is not a code/test failure and is not a Gate blocker; Reviewer A will run the focused test serially.

```powershell
cd C:\Users\redxa\ReProjects\ReSync
.\gradlew.bat test --tests restudio.resync.replacement.evidence.EvidenceScannerTest --console=plain
```

Expected verification behavior: two same-source scans compare equal; each pinned source hash, full packet row, and mirror-file hash must match its fixture. Any changed source byte or packet mapping fails with an exact JUnit assertion. Updating expected hashes requires an intentional evidence-fixture review, so ordinary drift cannot be silently normalized.

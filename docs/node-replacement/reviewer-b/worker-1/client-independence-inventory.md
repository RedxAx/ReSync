# B1 Deterministic Remotely Client-Independence Inventory

Status: Gate 0A evidence. Generated from every Java source under `Remotely/src/main/java/redxax/oxy/remotely`, every Java source under `Remotely/RemotelyMod/src/main/java`, and `Remotely/contracts/resync-protocol.json` by `B1ClientIndependenceInventoryTest`.

## Reproduction

The test reads every scoped source in lexical path order, emits one canonical record per matching rule as `category|repo-relative-file|line|trimmed-source`, sorts all records lexically, UTF-8 encodes `record + LF`, and SHA-256 hashes that byte stream. Every canonical record and the expected checksum are fixtures at `Remotely/src/test/resources/fixtures/node-replacement/b1/client-independence-inventory.{tsv,sha256}`; the test compares exact records before the hash.

Current canonical checksum: `f3ef80122fb10b5a6a9bbe51cad279899f6e4976cc5df83d7a5031ac4a2bbc49`.

| Category | Records | Rule |
|---|---:|---|
| `NODE_ID_BRANCH` | 168 | Node registry ID APIs; direct string/constant equality against `node.getType()`; type switches. |
| `RESOURCE_TYPE_BRANCH` | 956 | Closed `ReSyncResourceType` references and resource/type conditional/switch lines. |
| `OPTION_SOURCE_BRANCH` | 135 | Option-source fields, lookup, and query calls. |
| `PIN_NAME_BRANCH` | 27 | Literal key reads/writes of input values and literal source/target pins. |
| `STRUCTURAL_KEY_BRANCH` | 812 | Input maps, pins, Function parameter lists, passthrough/branch/repeatable structures. |
| `MIRRORED_MODEL_ROUTE` | 992 | Client graph/registry/cache/frame DTO/model route references. |
| `PACKET_ROUTE` | 103 | Closed resource packet fields, protocol constants, frame send/dispatch routes. |
| Total | 2,621 | All matching records; records may intentionally appear in multiple categories. |

## Auditable Controls

The test fails if any of these exact canonical records is missing:

- `NODE_ID_BRANCH|Remotely/flow/ui/NodeWidget.java|3387|if (!"flow.switch_case".equals(node.getType()) || node.getInputValues() == null) {`
- `NODE_ID_BRANCH|Remotely/flow/ui/NodeWidget.java|1080|boolean scheduleNode = AUTOMATION_SCHEDULE_ID.equals(node.getType());`
- `PIN_NAME_BRANCH|Remotely/data/flow/FlowManager.java|2886|Object previousCommand = start != null && start.getInputValues() != null ? start.getInputValues().get("command") : null;`
- `RESOURCE_TYPE_BRANCH|Remotely/data/flow/ReSyncResourceType.java|16|public enum ReSyncResourceType {`

## Closed Routes And Mirrored Surfaces Captured

The lexical inventory covers both client source roots and the protocol-owner JSON. Key closed or mirrored surfaces are `data/flow/ReSyncResourceType` (closed resource enum and per-resource packet bytes), `data/flow/ReSyncFlowClient` (frame dispatch, packet send, cache hydration), `data/flow/ReSyncFrameCodec`, `data/flow/FlowManager`, `data/flow/{SyncedResourceCache,TypedGraphCache}`, `flow/{data,registry,cache,sync}/**`, `RemotelyMod` bridge code, and `contracts/resync-protocol.json`. Server mirrors remain `ReSync/src/main/java/restudio/flow/data/{FlowGraph,FlowNode,FlowConnection,FlowSerializer}.java`; their relationship is documented in the earlier B1 findings.

## Blind Spots

This is lexical, deliberately not semantic/AST analysis. It records source lines matching declared current-system branch/model/route terms and therefore does not prove runtime reachability, multi-line literals, reflection, generated protocol source, resources, Kotlin/Groovy, or branches whose identifiers lack all declared terms. The fixed scope excludes client tests and server sources only; those are separately inventoried by their owners. The controls specifically protect known high-risk direct type and pin-key branches that simple registry-only searches miss.

## Generator Correction

The rejected 3,312-record fixture was produced by a PowerShell regular-expression reproduction, not the Java `Pattern` matcher used by the JUnit inventory. PowerShell's regex engine selected different matches and therefore emitted records the test never regenerates. The exact Java test method was compiled without Gradle into a temporary diagnostic output and invoked reflectively from the Remotely working directory; it regenerated the canonical list. The TSV and SHA fixture were replaced from that exact Java list. The resource-type rule now explicitly records the closed `ReSyncResourceType` enum declaration, yielding 2,621 records. This is a generator-semantics correction, not a hash-only update.

## Pre-Implementation Fields

Current architecture traced: Remotely owns node/type/resource/graph/cache/protocol routes listed above. Problem IDs addressed: existing NSR-001, NSR-002, NSR-008, NSR-009, NSR-010, NSR-013, NSR-016, NSR-017 plus B1-local evidence labels only. Current defect: ordinary node/resource/selector/structure changes are encoded in client source branches and closed packet/type routes. Classification: replace/delete client-specific behavior once generic shared contracts are frozen. Replacement goal: descriptor/capability-driven client projection using shared typed identities. Invariants: server/type/ID keys, immutable structural IDs, lossless unknown data, no ordinary-node source edit. Neighbors/contracts: all draft ReSyncCore catalog/graph/type/protocol/identity contracts; `GraphEditorScreen`, selectors, caches, server DTO mirrors. Client impact: broad generic-client conversion; persistence/migration: existing raw strings need offline mapped IDs; compatibility: negotiated fallback only. Fixtures: B1 inventory fixture plus FX-005/006/007/008/012. Exact owned files: this report, B1 test, B1 SHA fixture. Unresolved risk: lexical inventory cannot replace AST/runtime trace; complete snapshot/restore remains absent.

# Current-To-Target Map

| Current Surface | Decision | Replacement / Evidence Required |
|---|---|---|
| ReSyncCore existing resource/workspace contracts | Adapt | Consolidate into sole dependency-free node replacement contracts; retain proven typed-resource semantics |
| Remotely-owned `contracts/resync-protocol.json` and both generators | Delete | ReSyncCore shared protocol records/codecs and schema fixtures |
| ReSync and Remotely mirrored node/sync/graph/type DTOs | Replace/Delete | Shared ReSyncCore contracts; product-specific runtime/view adapters only |
| `NodeDefinitionLoader` legacy transforms and inference | Replace/Delete | Strict catalog source parser plus canonical defaulting/validation service |
| Mutable `NodeDefinitionRegistry` | Replace | Immutable `CatalogSnapshot` activated by deterministic `CatalogCompiler` |
| `HandlerRegistry` and handler implementations | Adapt | Capability/operation bindings validated against catalog; explicit lifecycle and semantics |
| `FlowExecutor` interpreted traversal | Replace | Graph compiler and immutable execution plan executor |
| `FlowRuntime` live default/definition lookup | Replace | Plan-owned typed bindings; runtime state only |
| `FlowStorage` typed transaction/tombstone portions | Retain/Adapt | Generic typed resource transaction and participant boundaries |
| `FlowStorage` legacy discovery/migration/type inference | Delete | Standalone upgrader with explicit version edges and journal |
| `nodes/migrated/**` | Migration-only then Delete from runtime | Domain catalog plus offline fixture/conversion inputs |
| Active domain JSON | Replace/Reorganize | Explicit descriptor schema under domain/family directories |
| Per-resource packet families | Delete | Generic resource operation envelopes and capability negotiation |
| Remotely registry/cache projections | Replace | Shared snapshots keyed by server/checksum and typed locator/revision |
| Remotely node-specific canvas/editor behavior | Delete/Adapt | Generic compact canvas plus one inspector capability renderer |
| Specialized rich designers | Adapt | Compose shared inspector/draft/mutation/description capabilities where appropriate |
| `FlowGraphMigrator`, `TypedAutomationGraphMigrator`, `IdCompatibilityLayer` | Migration-only then Delete from runtime | Separately versioned standalone upgrader |
| Existing migration regression fixtures | Retain/Expand | Sanitized direct-upgrade, interruption, quarantine, and idempotence suite |
| Network snapshot contracts | Retain as network domain | Register network persistence participant in complete snapshot service |

The generated Gate 4 deletion inventory must map every concrete file/class/method and every catalog definition to one accepted row or an explicit quarantine outcome.

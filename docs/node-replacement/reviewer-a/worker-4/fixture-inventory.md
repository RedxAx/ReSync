# Worker 4 Fixture Inventory

All paths are relative to `ReSync`. This is an inventory only; no candidate fixture was copied.

## Existing Repository Fixtures And Builders

| ID / source | Evidence and useful coverage | Gap before acceptance |
|---|---|---|
| FX-001 | `src/test/resources/fixtures/regressions/tester-resync-backup/recipe-mixed-schema.json`; `RecipeSchemaNormalizerTest` | Retain recipe normalization input. Add source hash, version, expected diagnostics/output hash. |
| FX-002 | `src/test/resources/fixtures/programmability/legacy-command-flow.json`; `CommandResourceMigrationTest`, `TriggerRegistryCompatibilityTest` | Retain command/trigger migration input. Need complete command resource, metadata, tombstone, and trigger transaction expectations. |
| FX-003 | `fixtures/programmability/{manifest,guide-npc-definition,librarian-trade-profile,starter-loot-table}.json` | Cross-resource preservation seed. Add locator/type collision cases and canonical expected output. |
| FX-005 | Existing request extension example (location still requires explicit inventory) plus `ReSyncExtensionManager` registration APIs | Must produce valid, missing, malformed, conflicting, unsupported-contract, and unload/reinstall cases; preserve opaque graph bytes/wires. |
| FX-006 | `CustomFunctionNodeDefinitionsTest`, `FlowFunctionTestHarnessTest`, `FunctionCatalogHandlerTest`, `FlowFunctionReferenceTest`, `FunctionCallSupportTest` | Builders can generate deterministic Function graphs. Need fixed identifiers independent of display names/order, source and output hashes, signature rename/reorder fixtures, and generated contribution provenance. |
| Typed durable graphs | `FlowStorageTypedIdentityTest`, `FlowStorageDurabilityTest`, `FlowStorageAssetMigrationTest` | Useful builders for type separation, revisions, tombstones, stale copies, and direct asset migration. Need a serialized fixture manifest instead of only test-local setup. |
| Legacy migration | `FlowGraphMigratorSchemaVersionTest`, `TypedAutomationGraphMigratorTest`, `ProgrammabilityCompatibilityFixtureTest` | Useful conversions and rejection cases. Need whole-folder snapshots, quarantine outcomes, all interruption journal states, and second-run assertions. |
| Diagnostics inventory | `ProgrammabilityAcceptanceSnapshotTest` and `flow/diagnostics/ProgrammabilityAcceptanceSnapshot.java` | Produces stable-schema operational audit snapshots; not a migration/snapshot fixture and not a performance benchmark. |

## Local Candidate Source: `run/plugins/ReSync`

Inspected read-only on 2026-08-02. It contains 10 files totaling 1,982,595 bytes, empty `extensions`, and empty/sparse asset subdirectories. It is a candidate source, not a sanitized fixture and not a complete snapshot.

| Relative path | Bytes | SHA-256 | Safe shape-only observation | Sanitization / usefulness |
|---|---:|---|---|---|
| `config.properties` | 170 | `2C514F5AB1C4F29A8CA337BC71D76BE40FEC9CB6BBCC5CE19C759759E082B4AF` | non-JSON | May contain credentials/endpoints; exclude or redact keys and replace values deterministically. |
| `config.yml` | 97 | `743930C190C82E5CA94423E3279D37F222DBB408C4A0EF06458731B468950E3A` | non-JSON | Same secret/host risk; not safe to copy unchanged. |
| `assets/project.json` | 1,683 | `9B2EB14F3580F63497E6C520D3614F67DF66F7359A8670BDF1A1ECADAAED9D28` | `serverId`, folders, resources | Server identifier and names/paths require deterministic replacement; useful for metadata/resource presentation only. |
| `diagnostics/programmability-acceptance-20260717-135605.json` | 1,001,882 | `4FC159D59C73D2CBACA46A465FE324D5276AB13A01419CF8807930B2DE33678A` | acceptance schema with registry/handler/type/resource inventories, readiness, versions | Operational diagnostic data may expose host, capability, extension, owner, node, and server details. Preserve only a reviewed redacted schema/count fixture; useful for catalog inventory baseline. |
| `diagnostics/programmability-acceptance-20260717-135954.json` | 970,373 | `282C3E9993372ACC3CB64097E622517ED3FFFF2B8878232730886FA2698D1231` | same schema, distinct capture | Same risks; use for drift comparison only after deterministic redaction. |
| `world-management/inventory-groups.json` | 2 | `4F53CDA18C2BAA0C0354BB5F9A3ECBE5ED12AB4D8E11BA873C2F11161202B945` | empty JSON object | Empty-state fixture candidate. |
| `world-management/player-states.json` | 2 | `44136FA355B3678A1146AD16F7E8649E94FB4FC21FE77E8310C060F61CAAFF8A` | empty JSON object | Empty-state fixture candidate. |
| `world-management/portals.json` | 2 | `4F53CDA18C2BAA0C0354BB5F9A3ECBE5ED12AB4D8E11BA873C2F11161202B945` | empty JSON object | Empty-state fixture candidate. |
| `world-management/sign-portals.json` | 2 | `4F53CDA18C2BAA0C0354BB5F9A3ECBE5ED12AB4D8E11BA873C2F11161202B945` | empty JSON object | Empty-state fixture candidate. |
| `world-management/worlds.json` | 8,382 | `5C061614C318A053AD1430984E6542F747C24DD38C35574961005264AD2E0280` | array of 3 world records; world/configuration/game-rule/profile fields | World names, locations, economy/configuration, and timestamps must be replaced or normalized; useful as a world-management structure fixture after approval. |

`assets/Blueprints/{Commands,Flows,Functions}`, other asset roots, `extensions`, `player-dossiers`, and `structures` were present as directories but contain no captured file evidence. This explicitly fails FX-011 completeness.

## Required Capture Metadata For Every Fixture

Record source path, source SHA-256, sanitized SHA-256, sanitizer version/config hash, source build/commit, catalog checksum, format/schema versions, extension versions, expected status, structured diagnostic codes, expected output/plan hashes, expected quarantine decision, and owner approval. Never publish source values that identify players, servers, hosts, tokens, paths, or private content.

# Fixture Manifest

| ID | Source | Sanitization / Ownership | Expected Use |
|---|---|---|---|
| FX-001 | `src/test/resources/fixtures/regressions/tester-resync-backup/recipe-mixed-schema.json` | Existing retained regression fixture | Preserve effective ingredients during versioned recipe normalization |
| FX-002 | `src/test/resources/fixtures/programmability/legacy-command-flow.json` | Existing retained fixture | Command graph/path migration and trigger separation |
| FX-003 | `src/test/resources/fixtures/programmability/manifest.json` and neighboring content/NPC/trade/loot fixtures | Existing retained fixtures | Typed resource identity and cross-resource reference preservation |
| FX-004 | Bundled 61-file/1,428-definition catalog | Public repository source | Raw inventory, classification, description completion, deterministic compilation |
| FX-005 | Request extension example | Existing example; expand without client changes | Atomic contribution, capability negotiation, unload/reinstall, opaque round trip |
| FX-006 | Generated Function catalog and graphs | To capture deterministically from test builders | Stable parameter identity, signature migration, call execution |
| FX-007 | Cross-type same-ID resources | To generate | Typed cache/storage/protocol identity and deliberate collision handling |
| FX-008 | Missing/broken/colliding extension contributions | To generate | Atomic rejection and unchanged active generation |
| FX-009 | Large production-scale catalog and graph corpus | To generate with fixed seed | Performance budgets, deterministic hashes, migration throughput |
| FX-010 | Interrupted migration/restore journal states | To generate for every state | Deterministic resume/rollback with no partial activation |
| FX-011 | Complete sanitized real ReSync-folder snapshot | Required before Gate 3B; not currently present in repository evidence | Controlled migration, network convergence, restore proof |
| FX-012 | Unknown contract fields/types/widgets and older-client capabilities | To generate as golden fixtures | Lossless preservation and clear read-only fallback |
| FX-013 | `run/plugins/ReSync` local acceptance data, 10 files and 1,982,595 bytes captured 2026-08-02 | Read-only source; project contains no resources, extensions directory is empty, world data is acceptance-named; diagnostics require sanitization review before copying | Current persistence-participant inventory and fixture-derived complete-folder cases |

Fixture hashes, format/build/catalog versions, expected diagnostics, and quarantine decisions are added when each fixture is captured. Secrets and player identities must be removed or deterministically replaced.

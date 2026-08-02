# Baseline Metrics

## Raw Catalog Baseline

Plan-verified baseline before replacement:

| Metric | Value |
|---|---:|
| JSON files | 61 |
| Unique definitions | 1,428 |
| Physical declared pin entries | 7,273 |
| Logical compatibility pins | 7,278 |
| Visible nodes | 999 |
| Hidden compatibility-style nodes | 429 |
| Deprecated nodes | 43 |
| Canonical redirects | 226 |
| Raw identity inputs | 155 |
| Defaulted pins | 723 |
| Optional pins | 157 |
| Conditional input definitions | 281 |
| Missing explicit node descriptions | 1,237 |
| Missing explicit descriptions on physical pins | 6,215 |
| Missing logical compatibility pin descriptions | 6,220 |
| Explicit domains | 0 |
| Explicit lifecycle states | 0 |
| Explicit inspector intents | 0 |
| Explicit schema versions | 142 |

Catalog source hash: `c83f2e2828ee599c18563beaab2a131a160acceeb5b7ae8220064124188ff0e8`.

The logical count adds one undocumented logical input for `custom_content.current`, `event.custom_content`, `logic.logic_true`, `logic.logic_false`, and `map.create`, whose raw definitions omit `inputs`. The current loader creates no runtime input for them. The physical count is raw declaration truth; the logical count is retained to reproduce the plan baseline. The exact hash and counting algorithm are recorded in `reviewer-a/worker-2/raw-catalog-inventory.json`.

## Performance Baseline Status

The accepted ReSync fixed-fixture baseline used five warmups and ten measured samples on Windows 11, Java 21.0.7, 16 available processors, and a 512 MiB maximum heap. Its complete machine-readable report is `build/reports/node-replacement-performance/current-baseline.json`, SHA-256 `f71bdfe2d0a8cc48e3dca4105f3e64657803998e60f6959f4e78a33ff5521df0`.

| Operation | Median | P95 |
|---|---:|---:|
| Catalog JSON parse | 14.575 ms | 16.1603 ms |
| Current catalog load proxy | 53.1389 ms | 65.3026 ms |
| Graph deserialize | 0.142 ms | 0.285 ms |
| Current graph validation | 60.2555 ms | 70.6628 ms |
| Fixture snapshot-copy proxy | 7.6244 ms | 9.2166 ms |
| Fixture restore-copy proxy | 7.7506 ms | 8.5255 ms |

The ReSync registry projection proxy is 1,132,707 JSON bytes and the recorded heap-used proxy after catalog load is 68,190,416 bytes. The Remotely 1,024-node client fixture records an 838,733-byte wire payload, an 838,779-byte cache-restored projection, and a 738,563-byte disk cache. Current-head client hydration is 478,700 ns median / 727,700 ns P95; cache projection is 35,800 ns median / 48,600 ns P95. The runtime micro-fixture records registry operations at 14,800 ns median / 32,700 ns P95, serialization at 60,300 ns median / 105,400 ns P95, and trace recording at 6,200 ns median / 14,500 ns P95.

Immutable graph compilation, coordinated migration and whole-folder snapshot/restore, live execution overhead, and stable interactive-edit measurements do not exist in the current architecture. The accepted baseline records explicit proxies or unavailability rather than zero. Gate 0B must assign budgets and fixture methodology for every replacement measurement, including those unavailable in the current system.

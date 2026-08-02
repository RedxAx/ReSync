# Current Performance Baseline Measurement

Status: ready for serial execution; no current timings are claimed in this artifact.

## Scoped Test And Output

Run only this test from `ReSync` after concurrent Gradle activity is clear:

```powershell
.\gradlew.bat test --tests restudio.resync.replacement.evidence.performance.CurrentBaselineEvidenceTest
Get-Content -Raw build\reports\node-replacement-performance\current-baseline.json
```

The test writes `build/reports/node-replacement-performance/current-baseline.json`. It uses five warmups and ten recorded samples per operation, with raw samples, minimum, median, p95, maximum, source hashes, environment, byte counts, and heap proxy in the report. Timings are evidence only and have no wall-clock assertion.

## Measurements Produced

| Result | Current operation / proxy | Limitation |
|---|---|---|
| Catalog parse | JSON parse of every eligible bundled source | Measures parsing, not future compilation. |
| Catalog load | `NodeDefinitionLoader.loadFromDirectory` over the bundled source tree | Measures current inference/loader behavior, not immutable compilation. |
| Registry payload proxy | Gson bytes of a deterministic explicit projection of current node/cache fields: node ID, owner, display/category/hidden/handler/schema/redirect fields and pin name/type/direction/data-type/options/default/optional/visibility fields | Not a network protocol payload or client hydration measure. The explicit projection avoids reflective serialization of runtime `Class` fields. |
| Graph deserialize | `FlowSerializer.deserialize` on retained `legacy-command-flow.json` | One representative legacy command graph. |
| Graph validation | Current `FlowGraphValidator` against loader-produced definitions with empty handler/catalog registries | Diagnostic outcome is not a production-ready validation acceptance result; timing measures current traversal. |
| Snapshot / restore proxy | Copy the retained `programmability` fixture tree into JUnit temporary snapshot/restore directories | Filesystem-copy proxy only; current coordinated full-folder snapshot/restore does not exist. |
| Memory proxy | JVM used heap after catalog loading | Process-level proxy, not retained memory or a leak measurement. |

## Explicitly Unavailable Measurements Under ADR-009

- Remotely client hydration and interactive editing: no live supported-client measurement is run by this test. Gate 5 method: run the supported legacy client head from `compatibility-matrix.md` against a fixed catalog/graph fixture, record registry bytes, hydration-ready event latency, edit-to-ack latency, client heap proxy, and raw samples.
- Catalog compilation and graph compilation: current architecture has no immutable compiler/plan path. Loader and validator proxies are intentionally not labelled compilation.
- Execution overhead: the legacy graph requires live handlers/server state; this test does not execute it. Gate 5 method: a controlled test-server fixture with declared handler set records validated graph execution from request through completion.
- Migration and complete snapshot/restore: no current coordinated participant service exists. The copy proxy must never be represented as migration or recovery throughput.

## Attempted Execution

On 2026-08-02 the focused Gradle test command was attempted twice and once with `--no-daemon`. Each attempt stopped at `Waiting For Another ReStudio Build To Finish`; no test report was created. Per reviewer coordination, no broad retry was performed. This is not a Gate blocker and not a measurement result; the reviewer will run the exact focused command serially.

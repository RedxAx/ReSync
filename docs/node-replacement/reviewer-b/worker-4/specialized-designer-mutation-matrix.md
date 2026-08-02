# B4 Specialized Designer, Mutation And Fixture Matrix

Status: Gate 0A current-head evidence, 2026-08-02. Scope is limited by ADR-008 to ReSync `b2941e66d762ffb76391715fbf4487c068fe12ea` and Remotely `76c4eb8989aaf856a4f002fa7fe6e56a67c373df`. Fixtures are deterministic evidence documents, not valid/executable current graphs.

## Current Mutation Boundary

`ReSyncFlowClient.sendResourceSave` sends resource-specific packet byte + request ID + serialized JSON (`Remotely/src/main/java/redxax/oxy/remotely/data/flow/ReSyncFlowClient.java:2987-3027`). It queues disconnected save closures at lines 2997-3001 and flushes pending sends after reconnect handshake at line 908. Graph revision/hash are acknowledged in `handleResourceSaveAck` (2198-2263); events are applied immediately from a resource-specific payload in `handleResourceEvent` (1679-1709). The event has no revision/mutation/hash field.

`FlowStorage.saveGraph` checks submitted positive revision (258-259) and commits graph plus project metadata with `AssetTransactionManager` (280-289) at `ReSync/src/main/java/restudio/resync/flow/FlowStorage.java`. Generic resource paths use `FlowResourceRegistry.saveFromSession` through `FlowResourcePacketHandler.handleSave` (`modules/flow/FlowResourcePacketHandler.java:78-128`). On conflict, the client gets a reload instruction/editor error; it does not receive authoritative payload/current revision as recovery data.

| Editor / capability | Class and concrete save path | Preview-only? | Current validation, conflict, draft, reconnect evidence |
|---|---|---:|---|
| Graph canvas / Flow / Function / Command | `GraphEditorScreen.saveGraph` → `FlowManager.saveGraph` → `ReSyncFlowClient.sendGraphSave` | No | Graph serializer carries revision. Server detects stale revision. Current conflict path lacks authoritative payload; disconnected sends queue un-revalidated closure. |
| GUI | `GuiDesignerScreen.saveGui` → `FlowManager.saveGui` | No | Specialized draft/screen; resource ACK path. No uniform cross-designer conflict refresh proved. |
| Scoreboard | `ScoreboardDesignerScreen.saveScoreboard` → `FlowManager.saveScoreboard` | No | Specialized draft/screen; ACK route. No shown expected-revision conflict recovery. |
| Tab | `TabDesignerScreen.saveTab` → `FlowManager.saveTab` | No | Specialized draft/screen; ACK route. No shown expected-revision conflict recovery. |
| Advancement tree | `AdvancementDesignerScreen.save` → `FlowManager.saveJsonResource(ADVANCEMENT_TREE)` | No | JSON resource draft; generic JSON validation. No client expected-revision precondition evidenced. |
| Dialog | `DialogDesignerScreen.save` → `saveJsonResource(DIALOG)` | No | JSON resource draft; generic validation. No uniform conflict refresh evidenced. |
| Focused JSON base | `FocusedJsonResourceDesignerScreen.save` → `saveJsonResource` | No | Mutable JSON draft and collaboration document. Base history defaults off; generic resource save packet has no expected revision. |
| Recipe, MOTD, message rule, text template, chat, trade, NPC, loot | Subclasses of `FocusedJsonResourceDesignerScreen`; `StudioScreen.focusedResourceView` routes them | No | Common focused JSON route; local preview/structural controls differ. Recipe and message-rule previews can mutate draft. Conflict/reconnect behavior is common-path absence. |
| Variable, timer, schedule | `AutomationDefinitionDesignerScreen`, focused JSON route | No | Type-specific client switch/default writer plus generic JSON validation/save; no generic revision recovery evidenced. |
| World | `WorldDesignerScreen.saveCurrentWorld` / `saveWorld` → world management operations | No | Independent multi-operation route; not the generic Flow resource save route. Conflict/draft/reconnect ordering absent from the inspected matrix. |
| Custom content | `ContentDesignerScreen`, graph/custom-content and quick-edit paths | No | Separate graph/custom-content conversion and quick-edit routing; conflict behavior differs from ordinary JSON resource path. |
| WorldGen | `WorldGenEditorScreen` / `FlowManager` worldgen methods | No | Dedicated worldgen packet operations, not generic Flow resource save. |
| GUI, scoreboard, tab preview views | `GuiStudioPreviewView`, `ScoreboardStudioPreviewView`, `TabStudioPreviewView`, `MinecraftUiPreviewRenderer` | No, composed with editors | Render projections of specialized editors; their persistence belongs to parent editor route. |
| Item preview | `ItemIconPreview` | Yes | No draft, validation, packet, mutation, reconnect, or persistence path. |

The matrix establishes current divergence; it does not assign replacement implementation. The shared defects are local evidence IDs NSR-B4-001 through NSR-B4-009 in `gate-0a-save-collaboration-compatibility-trace.md`.

## Deterministic Fixture Manifest

Location: `Remotely/src/test/resources/fixtures/node-replacement/b4/manifest.json`.

| File | SHA-256 | Boundary represented |
|---|---|---|
| `disconnected-draft-remote-mutation.json` | `121902cb78fd0cd04985b4f7d85d1945d36cc632e132af90621aa20709f37b8b` | Offline draft followed by remote revision. |
| `export-import-absence-boundary.json` | `170cc564d42433bed934d68de8b7fc4d2fb5b3a0839cadb85a868ca89dd5f7a2` | Required command round-trip fields and no current graph/resource export-import method. |
| `known-extension-graph.json` | `8535d1af2ba9d49958daa3d3eff979ef34f3749d541a5b91c42d5a823f842fd7` | Known synthetic extension node. |
| `missing-provider-graph.json` | `b713fff233ee7162eb2c0f4ba720417dbf99a7e9299e3231b421f249f7e4823e` | Missing provider with inbound/outbound wires and nested opaque material. |
| `same-id-resources.json` | `8c79ce5bb5712ba962ca0a280c51d6bf0584de244f1f61938d58c15954596117` | Flow/function/command collision under one server. |
| `unknown-nested-material-graph.json` | `34f68ca522bdb99a3fc2ed148687e70fcc0e517849764342c2e15dc5df299bf7` | Unknown graph/node/value/wire nested material. |
| `manifest.json` | `13de00bca2a24b7b3fb236534def44433c056a0955014347126ab8ecb98f13e1` | Current supported heads, hashes, and source anchors. |

`Remotely/src/test/java/redxax/oxy/remotely/replacement/evidence/b4/B4FixtureIntegrityTest.java` parses all fixtures, hard-checks every non-manifest SHA, both supported head IDs, five source anchors, missing-provider wires/opaque node field, and the three distinct same-ID resource types. It intentionally does not deserialize the synthetic graphs into current runtime models or claim execution validity.

## Reproducible Export/Import Absence Rule

Run from `C:\Users\redxa\ReProjects` at the declared heads:

```powershell
$scope=@('ReSync/src/main/java/restudio/resync/flow','ReSync/src/main/java/restudio/resync/modules/flow','ReSync/src/main/java/restudio/resync/storage','ReSync/ReSyncCore/src/main/java/restudio/resync/flow','Remotely/src/main/java/redxax/oxy/remotely/data/flow','Remotely/src/main/java/redxax/oxy/remotely/flow')
rg -n -i --glob '*.java' "\b(export|import)(Graph|Flow|Resource|Workspace|Blueprint)\s*\(" $scope
```

Result on 2026-08-02: exit code 1, `ZERO_GRAPH_RESOURCE_EXPORT_IMPORT_METHODS`. This rule deliberately excludes language imports and general “export” wording. It does not claim no unrelated exports exist: `ReSyncCommand` has a registry snapshot export user message around lines 1373-1375, and network snapshot/transfer classes exist under `ReSync/src/main/java/restudio/resync/network/**`. Neither is a Flow/graph/resource/workspace import/export method or round-trip route.

## Commands And Results

1. Fixture hashes: `Get-ChildItem Remotely\src\test\resources\fixtures\node-replacement\b4\*.json | Get-FileHash -Algorithm SHA256` — recorded above.
2. Focused test attempted twice: `Remotely\gradlew.bat test --tests redxax.oxy.remotely.replacement.evidence.b4.B4FixtureIntegrityTest` — neither execution started; both returned only `Waiting For Another ReStudio Build To Finish` after 30 seconds. Per reviewer coordination, no further lock attempts were made. Reviewer B will run accepted tests serially.

## Review Questions

1. Does the explicit current absence boundary correctly separate registry/network exports from the missing graph/resource/workspace import-export contract?
2. Are these evidence-only synthetic fixtures sufficiently scoped for the current-head compatibility window, without implying executable behavior?

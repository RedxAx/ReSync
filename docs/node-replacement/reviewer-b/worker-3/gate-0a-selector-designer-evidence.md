# Reviewer B Worker 3 — Gate 0A Evidence

Status: evidence only. No production files, tests, Gradle tasks, or live acceptance data were changed.

## Scope, Inputs, And Ownership

This report covers option metadata/querying/selection/persistence, raw resource identity selectors, descriptions and hints, specialized designers, and generic editor/preview gaps. Governing inputs read in full: `C:\Users\redxa\ReProjects\AGENTS.md`, `C:\Users\redxa\ReProjects\RESYNC_RULES.md`, `C:\Users\redxa\ReProjects\ReSync\plan.md`, and every existing file recursively under `C:\Users\redxa\ReProjects\ReSync\docs\node-replacement\` at investigation start.

Exact files owned: only this report and future evidence files beneath `C:\Users\redxa\ReProjects\ReSync\docs\node-replacement\reviewer-b\worker-3\`. No production ownership is requested at Gate 0A.

Existing ledger problems addressed by this evidence: NSR-002, NSR-003, NSR-008, NSR-009, NSR-010, NSR-011, NSR-013, NSR-016, and NSR-017.

New local problem IDs (not added to the shared ledger):

| ID | Evidence | Defect / violated invariant | Proposed classification |
|---|---|---|---|
| NSR-B3-001 | `NodeWidget` keys `inputWidgets`, `searchableSelectorValues`, `inputValues`, `visibleWhen`, option context, and update calls by `PinDefinition.getName()`; `GraphEditorScreen` creates variants with `Map.of(variant.selectorPin().getName(), variant.option())`; `FlowGraph.FunctionParameter` copies are constructed with name/type/widget/source/default. | Pin, mode, and Function parameter persistence follows mutable display/string names rather than immutable structural IDs. Rename/reorder safety is not proved. | Replace with contract-owned pin/mode/parameter/field IDs and ID-keyed literals/connections. |
| NSR-B3-002 | `NodeWidget.buildSearchableSelector` stores an `OptionCatalogItem.value()` string in `FlowNode.inputValues`; `OptionCatalogItem` is `String value`; `FlowGraphValidator.validateOptionCatalog` accepts only a `String literal`; server handlers retrieve strings and do ID-only lookups. | A server catalog query is richer than its persisted selection, but the selected ReSync resource loses server/type/revision identity. | Replace selectable ReSync resources with typed locators and resource-reference values. |
| NSR-B3-003 | `NodeDefinitionLoader` supplies missing node descriptions and infers option sources; `NodeDefinition.Builder.build` supplies a generic description and example; `NodeDefinitionValidator` only warns for missing node descriptions; `NodeWidget` has no consumption of node/pin descriptions in its literal input surface. | Raw documentation coverage is hidden by generated text, and pin requirements/default/conditional behavior are not rendered as shared hoverable documentation. | Replace with strict raw descriptor requirements plus one shared description/hint renderer. |
| NSR-B3-004 | `FlowManager` selects designer classes by `ReSyncResourceType`; each designer owns edit state and calls resource-specific `save*`; `GraphEditorScreen.saveGraph` also branches for WorldGen and Command. | Rich configuration has multiple persistence and validation paths; new ordinary resource/layout work needs client routing/classes. | Adapt rich previews/designers as generic-inspector compositions; replace direct type switches and persistence routes. |

## Option Catalog Current Architecture

### Server metadata, query, and validation

- `ReSync/src/main/java/restudio/resync/api/OptionCatalogItem.java:5` defines an option as `value`, `label`, `description`, `icon`, `group`, and free-form metadata. Its selection identity is only the `String value`.
- `ReSync/src/main/java/restudio/resync/modules/flow/BuiltinOptionCatalogService.java:135-719` registers built-in providers. `FlowOptionCatalogPacketHandler.handle` at `.../FlowOptionCatalogPacketHandler.java:44-113` reads a source ID plus JSON context, adds session/client/capability values, queries the provider, then sends separate string values and rich items. It has source-scoped sequence/revision/status, but no pagination/search request, complete typed locator, or per-item revision.
- `ReSync/src/main/java/restudio/resync/modules/flow/FlowNodeRegistryPacketHandler.java:709-710` projects provider metadata into `FlowOptionSourceMetadata`; its client mirror is `Remotely/.../flow/sync/FlowOptionSourceMetadata.java`. This is a second shared-concept DTO surface (NSR-002).
- `ReSync/src/main/java/restudio/resync/flow/validation/FlowGraphValidator.java:672-739` validates an option-backed literal as a `String`, queries the provider, and compares it against `List<String> values`. The managed-resource check does not make persistence typed.
- The active source tree names 61 option sources. The ReSync managed-resource set includes advancement tree, chat, command, custom content, dialog, flow, function, GUI, locale, loot table, message rule, MOTD profile, NPC definition, recipe definition, schedule definition, scoreboard, tab, text template, timer definition, trade profile, variable definition, world generator, worldgen, and world. Minecraft/LuckPerms/runtime catalogs are also string-valued. This is concrete evidence for the plan baseline's raw identity risk; the plan records 155 raw identity inputs.

### Client querying, caching, and selection

- `Remotely/src/main/java/redxax/oxy/remotely/data/flow/ReSyncFlowClient.java:2410-2551` parses catalog packets and issues requests keyed by source ID plus a stringified context key. Disconnect paths at lines 548-583 and 684-695 clear in-flight requests and mark the cache stale; reconnect is scheduled at 3321-3324.
- `Remotely/src/main/java/redxax/oxy/remotely/data/flow/OptionCatalogCache.java:212-217` invalidates by server/source, not by a typed resource locator or item revision.
- `Remotely/src/main/java/redxax/oxy/remotely/flow/registry/NodeRegistry.java:38, 191-192, 262-271, 363-449` stores source metadata in `Map<String, List<FlowOptionSourceMetadata>>`, keyed by normalized server ID. The registry retains unresolved plugin payloads separately, but this does not constitute atomic catalog generation or lossless opaque graph-node handling.
- `Remotely/src/main/java/redxax/oxy/remotely/flow/ui/NodeWidget.java:351-364` preloads each pin's `optionsSource`; `852-923` resolves static string options or requests cached catalog values and composes context from all `node.inputValues`, `$nodeType`, and `$pin`.
- `NodeWidget.buildSearchableSelector` at lines 537-674 accepts `Consumer<String>`, writes `node.getInputValues().put(input.getName(), option)`, and uses the string for label lookup. `resolveWidgetType` at 925-953 infers the client widget from mutable option metadata and falls back to searchable list/text. If the catalog has not arrived, a dropdown becomes a text field and a searchable selector falls back to the current raw value.
- The selector adds client-specific Variable/Timer/Schedule create, edit, and delete actions in `managedResourceType`, `createManagedResource`, and `openManagedResource` at 621-674. The client therefore knows particular option source IDs and resource types.
- `GraphEditorScreen.showRichNodeInputSelector` at 3167-3196 likewise returns `item.getValue()`, while mode-variant creation at 6261-6332 materializes a `Map<String, Object>` keyed by selector pin name and treats comma-separated display strings in `visibleWhen` as behavior.

### Current selector behavior and defects

The selector is responsive and has rich labels, descriptions, grouping, aliases, cache stale handling, and contextual queries. Its durable result is nevertheless a raw string. A same-ID resource of another type/server cannot be distinguished in the persisted literal, validation, handler lookup, client cache, selection action, collaboration path, or reconnect behavior. Option query metadata is independently modelled on server and client, and normal data changes require client source conditionals for managed resources.

Replacement goal: one contract-owned contextual option query with capability IDs, pagination/search/dependency keys/invalidation, typed resource locator result where applicable, revision and diagnostic data, and generic create/open actions driven by declared resource capability. A selector must persist a typed value/locator, never an option label, source-specific string, or remembered raw ID.

Invariants: server authority; all selected ReSync resources use server/type/ID; a label/display-name change cannot alter selection; stale/disconnected cache cannot produce durable authority; unsupported selector capability is read-only and lossless; option queries cannot silently coerce strings to references.

Affected neighbors: shared catalog/pin/type/value/protocol contracts; `FlowGraph`, graph serializer, validation, resource adapters/handlers, resource registry, ReSyncFlowClient, OptionCatalogCache, NodeRegistry, NodeWidget, GraphEditorScreen, Studio resource actions, collaboration, and reconnect reconciliation.

Shared contracts consumed today: mirrored `NodeDefinition`, `FlowOptionSourceMetadata`, `OptionCatalogItem`, `OptionCatalogSnapshot`, flow type/reference models, and Remotely-owned packet generation. Gate 0B must replace these with ReSyncCore-owned locator, typed-value, descriptor, catalog query, diagnostics, and protocol contracts.

Persistence impact: every option-backed graph literal and Function parameter must migrate from a name-keyed raw string to pin-ID-keyed typed value. Resource-reference conversion needs type-aware resolution, ambiguity quarantine, and a report; unknown selector/value fields remain opaque. Client impact: generic selector renders descriptor capability/fallback and returns the shared typed value without ordinary source changes. Compatibility impact: existing client may display only a fallback/read-only opaque selector; no client is allowed to save and erase unknown locator fields.

Fixtures required: FX-007 cross-type same-ID resources; FX-012 unknown selector/typed-value/capability round trip; a context-dependent option query fixture; server reconnect/out-of-order option revision fixture; missing option-provider/extension fixture; rename/reorder pin and Function parameter fixture; raw legacy IDs with an ambiguous and an unambiguous conversion.

## Description And Hint Current Architecture

- Raw baseline evidence in `docs/node-replacement/baseline-metrics.md` records 1,237/1,428 node descriptions and 6,220/7,278 pin descriptions absent from JSON.
- `ReSync/src/main/java/restudio/resync/flow/registry/NodeDefinitionLoader.java:291-360` applies compatibility transforms. At 401-404 it uses JSON description when supplied, otherwise `defaultDescription`; at 685-707 it infers semantic catalog type/options source. This violates explicit raw-source documentation.
- `ReSync/src/main/java/restudio/resync/flow/registry/NodeDefinition.java:855-874` inserts `displayName + " Flow capability."` and a default usage hint during builder construction. `NodeDefinitionValidator.java:69` only emits a warning for a missing node description; it does not reject missing pin descriptions or verify default/conditional wording.
- `GraphEditorScreen.selectorHint` at `.../GraphEditorScreen.java:6466-6498` shows node description plus owner/deprecation/destructive/clock annotations in the add-node selector. Variant hints at 6412-6418 are generated from the mode option and this selector hint.
- `NodeWidget` carries pin descriptions when recreating pins (1155-1182, 1461-1500), but its visible input construction (`351-953`) does not render `input.getDescription()`, required/default/optional state, or conditional exposure explanation as a shared hoverable hint. The only direct node-widget hints evidenced are fixed controls such as Delete Node, Open Function, and Arguments at 218-239. Catalog selection item descriptions are shown, but they document an option rather than the pin's role.

Replacement goal: strict schema validation requires authored node/pin descriptions and declared required/default/conditional state. One shared animated hint surface consumes descriptor text in canvas, node selector, generic inspector titled rows, and specialized preview compositions. Conditional pins must state the mode/selector that exposes them; resource pins describe the resource role, not the ID.

Migration/compatibility: legacy missing descriptions are catalog conversion data, not runtime-generated text. An old client that cannot render a capability must provide a clear read-only reason while preserving descriptor/node data. No persisted user graph migration is required for presentation-only authored text, but any pin-ID migration must map descriptions with their IDs.

## Specialized Designer, Editor, And Preview Inventory

### Graph and structural editing

- `GraphEditorScreen` is the broad graph editor. `saveGraph` at 6576-6654 branches for WorldGen, Command, custom content, and otherwise calls `FlowManager.saveGraph`; it also owns optional pin removal and Function parameter copies keyed by names (6709-6743; 7025).
- `NodeWidget` owns inline pin widgets, conditional visibility, option lookup, mode branches, function/open buttons, managed resource actions, and literal state. `FlowGraphDesignerScreen`/`FlowEditorScreen` are graph wrappers. This is not a generic inspector; complex data stays distributed through canvas widgets and node/resource-specific paths.

### Resource-specific screens and persistence routes

`FlowManager` is the route switch and opens specialized screens by type at lines 538-667 and 3957-4093. The evidence inventory is:

| Resource/design capability | Client screen or renderer | Save route evidenced |
|---|---|---|
| GUI with visual preview | `GuiDesignerScreen`, `studio/GuiStudioPreviewView`, `MinecraftUiPreviewRenderer` | `GuiDesignerScreen.saveGui` -> `FlowManager.saveGui` (2005) |
| Scoreboard with preview | `ScoreboardDesignerScreen`, `studio/ScoreboardStudioPreviewView` | `saveScoreboard` -> `FlowManager.saveScoreboard` (679) |
| Tab list with preview | `TabDesignerScreen`, `studio/TabStudioPreviewView` | `saveTab` -> `FlowManager.saveTab` (653) |
| Advancement tree | `AdvancementDesignerScreen` | direct `FlowManager.saveJsonResource(...ADVANCEMENT_TREE...)` (2492) |
| Dialog | `DialogDesignerScreen` | direct `saveJsonResource(...DIALOG...)` (1048) |
| Focused JSON resources | `FocusedJsonResourceDesignerScreen` | `saveJsonResource` (425) |
| Focused JSON editors | Trade, NPC, Loot, Recipe, Chat, Message Rule, MOTD, Text Template, Variable, Timer, and Schedule designers | All inherit the shared focused-JSON save seam detailed below; this is a shared class path, but still a JSON-resource-specific persistence route |
| World editor | `WorldDesignerScreen` | independent multi-operation world-management save path detailed below |
| Custom-content editor | `ContentDesignerScreen` | graph/custom-content or quick-edit route detailed below |
| `ItemIconPreview` | static item/catalog preview utility | preview-only; it has no draft, validation, save, packet, or persistence route |

### Complete focused-JSON editor trace

`StudioScreen.focusedResourceView` at `Remotely/src/main/java/redxax/oxy/remotely/flow/ui/studio/StudioScreen.java:1073-1087` is the concrete entry switch for Recipe, MOTD, Message Rule, Text Template, Chat, Trade, NPC, Loot, Variable, Timer, and Schedule. `ReSyncFlowClient` caches generic JSON resource data at `.../data/flow/ReSyncFlowClient.java:2014-2023`; the Studio view receives a detached JSON resource. These editors are not preview-only: every one derives from `FocusedJsonResourceDesignerScreen` and modifies the in-memory `JsonObject` through its field/binding widgets.

`FocusedJsonResourceDesignerScreen` supplies their common draft/load/save/validation/collaboration route:

- Draft: constructor retains a mutable JSON object and wires Save (`131-139`); `collaborationDocument` copies it and `applyCollaborationDocument` replaces/reloads it (`152-173`); field/binding inputs call `putJsonText`/path writers (`605-915`, `2108-2591`). Its history is opt-in and defaults to `false` (`142-145`), so the base guarantees no independent undo/rebase draft preservation.
- Save: `save` (`414-425`) calls `FlowManager.saveJsonResource`; that method (`.../FlowManager.java:2205-2229`) stores an optimistic draft then calls `ReSyncFlowClient.sendResourceSave`; client serialization/queueing is at `.../ReSyncFlowClient.java:2988-3042`.
- Server validation/persistence: `FlowResourcePacketRouter` generic JSON adapter calls `jsonResourceValidator.validate(type,value)` then `storage.save(type,value)` (`ReSync/src/main/java/restudio/resync/modules/flow/FlowResourcePacketRouter.java:1123-1129`); runtime provider resolution is still hard-coded at 1191-1205. `FlowResourceRegistry.save` validates before adapter save (`.../FlowResourceRegistry.java:267-299`).
- Conflict: `FlowResourceRegistry` has generic conflict catches (`397-445`), but this client packet does not include an expected revision: `sendResourceSave` serializes type, request ID, and JSON only. Unlike graph save (`ReSyncFlowClient:3127-3130`), there is no focused-JSON revision precondition or shown client draft rollback/authoritative conflict refresh. The required cross-editor conflict route is therefore absent, rather than untraced.

The individual visual/editor capabilities and their extra behavior are:

| Capability | Concrete class / entry | Draft and preview behavior | Save / validation / conflict result |
|---|---|---|---|
| Trade profile | `TradeDesignerScreen`, `StudioScreen:1081`, or `FlowManager:4089` | Editable focused JSON; `renderTradeRealPreview` (37-205) is a visual projection; `reloadFields` is invoked after actions (440). | Common focused-JSON route; server JSON validator; no per-editor validation or revision-conflict recovery found. |
| NPC definition | `NpcDesignerScreen`, `StudioScreen:1082`, or `FlowManager:4090` | Editable focused JSON with catalog preload (306), equipment selectors, and NPC preview widget (330-543). `ItemIconPreview.resolve` is used only to draw equipment. | Common focused-JSON route; server JSON validator; no dedicated revision/conflict path. |
| Loot table | `LootTableDesignerScreen`, `StudioScreen:1083`, or `FlowManager:4091` | Editable focused JSON; grid preview is rendered at 75-274, with field reloads at 334/441. | Common focused-JSON route; server JSON validator; no dedicated revision/conflict path. |
| Automation definitions | `AutomationDefinitionDesignerScreen`, `StudioScreen:1084-1086`, or `FlowManager:4093` | Editable focused JSON. Its type switch selects Variable/Timer/Schedule field groups (21-25) and writes defaults for that type (146-159). | Common focused-JSON route; server JSON validator; client type switch is an ordinary-resource coupling. |
| Recipe definition | `RecipeDesignerScreen`, `StudioScreen:1076` or `FlowManager:1880` | Editable focused JSON with recipe binding drafts (361-433), structural reloads (173/561/577), and `renderRecipeRealPreview` (91-433). | Common focused-JSON route; server JSON validator. Recipe-schema-specific behavior is additionally mutated client-side by focused base methods (2446-2565), so validation and shape normalization are not solely declarative. |
| Chat | `ChatDesignerScreen`, `StudioScreen:1080` or `FlowManager:1837` | Editable focused JSON plus local sample sender/receiver/message and preview mode state (135-362); preview renders from resource plus sample values (437-642). | Common focused-JSON route; server JSON validator; sample/preview state is local and should never persist as resource data. |
| Message rule | `MessageRuleDesignerScreen`, `StudioScreen:1078` or `FlowManager:1873` | Editable focused JSON; interactive preview selection writes `contains` back to JSON (430-453), so this preview can mutate the draft. | Common focused-JSON route; server JSON validator; preview mutation bypasses a generic inspector mutation contract. |
| MOTD profile | `MotdDesignerScreen`, `StudioScreen:1077` or `FlowManager:1867` | Editable focused JSON with MOTD preview (55-150). Icon upload mutates JSON, reloads fields, then immediately calls `save` (188-240). | Common focused-JSON route, but upload is a specialized immediate persistence trigger; server JSON validator; no explicit draft/conflict reconciliation. |
| Text template | `TextTemplateDesignerScreen`, `StudioScreen:1079` or `FlowManager:1896` | Editable focused JSON with multiline preview (`224-301`) and a custom render surface (45-47). | Common focused-JSON route; server JSON validator; no dedicated conflict route. |
| World | `StudioScreen.openStudioWorldDocument` (`1010-1011`) creates `WorldDesignerScreen`; it reads through `FlowManager.getWorld` in `collaborationDocument` (`WorldDesignerScreen:99-148`). | Independent editable document plus `History<JsonObject>` and collaboration apply/rebase (`99-257`). Local numeric/range validation occurs in `saveWorld` (`912-1016`). | `saveWorld` begins a notification and emits separate FlowManager operations—difficulty, profile, isolated state, time lock, weather lock—at `1017-1052`. It is not a JSON-resource save and is not atomic across those operations. Requests carry request IDs, but no expected-revision conflict precondition is evidenced. |
| Custom content | `StudioScreen`/content browser open `ContentDesignerScreen` (`StudioScreen:348,430,858`; browser:1506); it loads a FlowGraph through `ContentDesignerScreen.loadGraph` (`163-171`). | It extends `GraphEditorScreen`; its attribute designer holds local component drafts and writes them into graph properties (`2094-2108`). | `onSave` applies quick edit or delegates to graph save (`461-481`), which derives/sends custom content via `FlowManager.saveGraph` (`1214-1249`) or `applyQuickEdit`. Server custom-content validation is specific at `FlowResourcePacketRouter:892-906`. This is a separate graph/quick-edit route, not a focused-JSON route; revision behavior remains split. |
| Item icon helper | `ItemIconPreview` | `resolve`/`previewFromCatalog` (`52-79,194-219`) converts a value/catalog item into a render item. It has no screen lifecycle, mutable resource, field writer, save call, validation call, revision, or protocol send. | Preview-only utility; it must be a generic read-only preview capability, never considered an independent persistence surface. |

The prior table is superseded by this complete trace. The rich editors are not merely previews: all except `ItemIconPreview` own a mutable draft and/or specialized editing interaction. Their common save call reduces some duplication but remains outside a shared contract-driven inspector mutation/validation/conflict model. Existing decision ADR-005 permits rich designers only as compositions of shared draft/mutation/validation/description capabilities; the current routes do not meet that criterion.

Replacement goal: a single inspector draft and mutation model with declarative sections/fields/conditions/repeatables/branches and pluggable read-only previews. Existing rich visual screens may survive only as preview/editor capability implementations using the same typed mutation, server validation, conflict, description, accessibility, and lossless unknown-data paths. A new ordinary resource or layout must not require a `FlowManager` switch or new Remotely screen.

Fixtures required: each row's load/edit/save/validation/conflict/reconnect path; preview emits no persistence unless it sends the same inspector mutation; unsupported preview capability read-only round trip; duplicate browser/designer sessions converging on one revision; specialized resource rename/move/delete and typed selector creation.

## Required Trace Evidence

| Required trace | Evidence / current result |
|---|---|
| Add extension node | `ReSyncExtensionManager.ExtensionContext.registerNode` at `ReSync/src/main/java/restudio/resync/api/ReSyncExtensionManager.java:582-598` assigns owner then directly registers in `NodeDefinitionRegistry`; options at 699-744 and handlers at 603 onward use separate registrations. This is non-atomic contribution activation. |
| Unload extension | `ReSyncExtensionManager.unregister` at 203-285 removes extension data, registry nodes, types/resources/validators independently; JAR unload calls it at 373-379. No catalog generation swap, in-flight execution drain/cancel proof, or opaque graph preservation is evidenced. |
| Open graph | Client opens graph/studio via `FlowManager` and `GraphEditorScreen`; node definitions arrive through `NodeRegistry` snapshots and inline `NodeWidget` materializes values. Server `FlowStorage.reloadGraph(type,id)` at 164-186 is the durable load seam. Multiple mirrored graph/descriptor models remain. |
| Save graph | `GraphEditorScreen.saveGraph` at 6576-6654 -> `FlowManager.saveGraph`; server `FlowBlueprintPacketHandler` invokes `FlowStorage.saveGraph` (line 168) and `FlowStorage.saveGraph` at 220-281 performs revision/mutation/asset operations. Resource-specific client routes remain. |
| Execute node | `FlowExecutor.execute` at `ReSync/src/main/java/restudio/resync/flow/FlowExecutor.java:197-541` resolves a live definition then handler; `FlowRuntime.resolveDefinitionDefault` at 189-192 reads current definition defaults. No immutable compiled plan/snapshot boundary. |
| Revision conflict | `FlowStorage.saveGraph` checks `resourceRevision` and throws `ResourceRevisionConflictException` at 252-259; `FlowBlueprintPacketHandler` catches conflicts at 184 and 253. This confirms a partial server conflict seam; this slice has not found uniform selector/designer draft rollback and authoritative refresh across all resource routes. |
| Legacy migration | `FlowStorage` constructor calls `migrateLegacyAssets` (102); lines 1539-2010 perform startup migration/reconciliation. `FlowGraphMigrator.migrateStoredFlows` obtains a flow-assets fence at 72-84 and uses per-graph backup/report mechanics. This is live migration, not the required standalone coordinated upgrade. |
| Reconnect | `ReSyncFlowClient` disconnects collaboration, clears option requests, marks catalog stale (548-583, 684-695), and schedules reconnect (3321-3324). It does not prove typed locator/revision convergence or lossless unsupported selector/designer drafts. |
| Complete snapshot/restore | Explicit absence: `FlowStorage.previewAssetRestore`/`restoreAssetSnapshot` at 153-158 delegate only to asset transactions; migration backup helpers at 764-798 copy individual graphs. Search found network/job/world/workspace snapshots, not a complete ReSync-folder participant registry, fence, manifest, staging, whole-folder atomic restore, or restore journal. Local acceptance data confirms separate assets, diagnostics, world-management, and empty extensions roots. |

## Local Acceptance Data (Read Only)

`ReSync/run/plugins/ReSync` was inspected without copying it. `assets/project.json` declares folders and `resources: []`; `extensions/` is empty; `diagnostics/` contains two large acceptance reports; `world-management/worlds.json` is an array with three acceptance-world entries, while `inventory-groups.json`, `player-states.json`, `portals.json`, and `sign-portals.json` are empty JSON objects. This proves the current persistence surface has independently located participants outside graph assets, while this local instance has no installed extension/resource fixture. It is only a proposed local fixture candidate requiring orchestrator allocation and sanitization review; this report does not allocate, rename, or canonize it as FX-013.

## Gate 0B Decisions Needed / Unresolved Risks

1. Freeze a ReSyncCore-owned typed locator and typed literal contract, including server-scope rules, persistence JSON form, unknown preservation, and selector option result shape. This worker must not invent it.
2. Decide whether generic inspector configuration is stored inside graph node literals or a separate ID-keyed field map; both must be immutable-ID keyed and atomically mutated with graph revision.
3. Freeze generic inspector and preview capability IDs/fallback semantics before adapting any designer. Define which existing visual designers are capability providers rather than resource-specific applications.
4. Define the migration rule for every currently string-valued managed resource. Ambiguous same-ID or absent-type values require quarantine, not lookup-order repair.
5. Obtain an orchestrator-allocated, sanitized complete-folder fixture: current local data contains no resources/extensions, so it cannot prove resource/extension migration, unload, or restore.

## Pre-Implementation Report

Current architecture traced: server provider registry and packet handler; client catalog cache, selector, graph save, specialized designer routing; raw JSON loader/validator; server graph storage/conflict/execution; extension add/unload; startup migration; local persistence roots.

Current behavior and defects: selectors offer contextual rich labels but persist raw strings; descriptions are inferred and only selector-level node hints visibly consume them; structural values are name keyed; specialized designers have multiple save routes; client source contains option/resource/type branches; extension registration/unload is piecemeal; snapshots/restores are partial.

Proposed classification: selector and identity semantics Replace; description/hint renderer Replace; rich previews/designers Adapt behind generic inspector capabilities; current rich screens' independent save routing Delete/Replace; migration-only raw-string conversion once; unresolved complicated resources Review until exact behavior parity is traced.

Replacement goal and invariants: as stated in each section above: shared authoritative descriptors/typed locators/typed values/protocol, ID-keyed structural state, one generic inspector/mutation/draft/validation route, client-independent ordinary capabilities, authoritative revision reconciliation, lossless opaque fallback, and explicit quarantine for non-resolvable legacy IDs.

Affected neighbors and consumed contracts: listed in the option and designer sections. The required shared contracts are all Draft in `contract-freeze-ledger.md`; none may be changed by this worker.

Client impact: Remotely becomes catalog/capability-driven for selectors and inspector fields. Existing clients require negotiated fallback/read-only behavior; no ordinary catalog, option, resource, or layout addition should need source edits.

Persistence impact: name-keyed strings and UI state require one versioned migration to typed, ID-keyed values; selection, graph save, collaboration, cache, export, reconnect, and restore must retain unknown fields.

Migration impact: offline/standalone conversion with complete snapshot, deterministic typed resolution, report, and quarantine; eliminate current startup migration/loader inference after conversion.

Compatibility impact: preserve existing descriptor/node/resource data losslessly; unsupported inspector/selector capability is explicit read-only; direct-upgrade exact releases remain unresolved in the compatibility matrix.

Fixtures required: FX-005 through FX-008 and FX-010 through FX-012, plus the designer-route/raw-identity fixtures named above and an orchestrator-allocated sanitized local complete-folder candidate. No fixtures were added in Gate 0A.

Unresolved risks: Gate 0B contract decisions; focused-JSON and world routes lack shown uniform expected-revision/draft-conflict recovery; absent real resource/extension fixture; mirrored DTO/protocol generation; client-side pending save/reconnect ordering; catalog query has no typed per-item identity/revision; live migration and asset-only restore paths remain reachable.

## Registry Payload And Hydration Baseline

ADR-009 authorizes narrow non-destructive verification. The B3 current-client baseline is implemented at `Remotely/src/test/java/redxax/oxy/remotely/replacement/evidence/b3/CurrentRegistryBaselineTest.java` with fixed parameters in `Remotely/src/test/resources/fixtures/node-replacement/b3/registry-baseline.json`.

The fixture uses seed `20260802`, eight plugins, 128 nodes per plugin (1,024 nodes), 24 type records, and 32 option-source records. It creates a faithful current `NodeRegistrySnapshot`, serializes it through the current Gson DTO surface, canonicalizes JSON object keys only for baseline measurement, applies it through `NodeRegistry.applySnapshot`, and persists/restores it through the current `NodeRegistryCache`. Wire payload and cache-restored projection are separate baseline artifacts: they deliberately do not compare equal. `NodeRegistryCache` stores plugins in a `HashMap` and materializes its `values()` order rather than preserving wire plugin-array order. It also changes the two null/omitted snapshot maps, `propertyActions` and `propertyOutputTypes`, into empty JSON objects because cache state initializes both maps before reconstruction. The test asserts that every plugin payload keyed by `pluginId` is preserved; that the only changed top-level fields are those two cache defaults; and that their cache projection is exactly `{}`. Its manifest records the wire-to-cache plugin-order transform and these two defaulted fields explicitly. Separate byte-count and SHA-256 fields are hard-pinned for both artifacts, so a future change cannot hide this boundary behind normalization or equality.

Cache materialization timing starts immediately before `NodeRegistryCache.getSnapshot("baseline-server")` and ends after that returned projection is available; it measures the actual cache retrieval route, not an in-memory DTO copy. The test also records warm-cache hydration and cache-materialization median/P95 after twelve warmups and forty measurements. Timing has no pass/fail assertion. The direct heap-used delta is recorded only as advisory: it has no forced GC, fork, or fixed heap and must never become a budget gate.

The test source is intentionally located in the approved B3 directory but declares package `redxax.oxy.remotely.flow.cache`; this is solely to reach the current package-private `NodeRegistryCache(Path)` constructor with a JUnit temporary path. It does not alter, expose, or duplicate a production package. A future shared benchmark seam should make safe cache construction explicit rather than retaining this test-only package placement.

The initial two attempted invocations stopped at `Waiting For Another ReStudio Build To Finish` before compilation or test execution. Under ADR-009, Reviewer B then ran the accepted focused test serially using `./gradlew.bat test --tests redxax.oxy.remotely.flow.cache.CurrentRegistryBaselineTest --console=plain` from `C:\Users\redxa\ReProjects\Remotely`; the final combined run passed. The test emits all observations to test output for durable capture rather than relying on interactive stdout.

The controlled serial run pinned independent golden artifacts in the fixture: wire payload `838733` bytes / SHA-256 `89c9ad56e0b04a59dc614b456af4b96ceae1ecf14e751de680aff779077801ad`; cache-restored projection `838779` bytes / SHA-256 `4e9308b326a6a2778ec5ac505df595fc36da2455e4875362764ee50171ca5abf`. The test hard-asserts all four values. This turns wire DTO drift and cache projection drift into deterministic failures, while retaining the current cache ordering/default-field transformation as an explicit preservation manifest rather than masking it with array sorting. Cache-file size is measured and emitted but deliberately has only a positive-existence assertion: the current cache uses its own compact Gson adapters, so it is not required to exceed the wire payload.

Controlled result: `738563`-byte disk cache; `1024` nodes, `8` plugins, `24` types, and `32` option sources; warm full-snapshot hydration median `478700 ns`, P95 `727700 ns`; actual `NodeRegistryCache.getSnapshot` materialization median `35800 ns`, P95 `48600 ns`; advisory direct heap-used delta `0` bytes. This ran on the current Remotely head `76c4eb8989aaf856a4f002fa7fe6e56a67c373df` with OpenJDK `21.0.7` (JBR `21.0.7+6-1038.58-nomod`) on Windows. The time and heap figures are a controlled comparison baseline for this environment, not a universal performance claim.

Proposed Gate 0B comparison budgets, deliberately not current timing assertions and subject to the measured environment: canonical registry payload at most 1.25x the current B3 fixture bytes; warm full-snapshot hydration median at most 1.25x current and P95 at most 1.5x current; cache materialization median at most 1.25x current and P95 at most 1.5x current. Any measurement comparison must use Java 21, the same fixture, warmup/iteration counts, and a recorded machine/heap/OS environment. Memory is reported only as an informational allocation signal until a forked/stabilized method is approved.

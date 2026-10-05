# Runtime QA

ReSync exposes one QA service through `ReSyncApi.qa()` and `/resync qa`. Both interfaces use the same operation descriptions, permission checks, admission limits, and receipts. The caller needs `resync.qa`, including console callers. Java submission and polling run on Minecraft's server thread.

Start with:

```text
resync qa list
resync qa describe resource.create
resync qa run resource.discover {}
resync qa poll <runId>
```

`run` accepts one operation name and a JSON object. It returns a JSON receipt immediately. Poll its `runId` until the status is `completed` or `failed`. A completed QA request can contain a rejected resource response. Inspect the result's status and errors as well. Commands print one JSON object per invocation and never send unsolicited asynchronous output.

Numbers use plain decimal notation. Invalid JSON, duplicate object keys, malformed text, nonfinite values, and oversized requests are rejected.

## Request Identity And Results

An optional full UUID `requestId` makes repeated submissions idempotent while their receipt is retained. The same actor, operation, and input return the existing receipt. Reusing that ID for changed input returns `QA_REQUEST_CONFLICT`. Other actors cannot read that receipt. Omit the ID to create a new invocation.

QA receipts belong to this server process and retain up to 256 requests within a 64 MB budget. Admission allows eight active requests. Restarting the server clears QA receipts. Resource mutations have their separate durable `mutationId` and authoritative resource revision. Their existing persistence and recovery rules continue to apply across restart.

An execution result settles after its tracked physical work ends. A timeout or cancellation does not imply that active work has already stopped. Results contain the committed resource identity and available node outputs. A completed receipt is immutable and can be reused by callers without another resource read or execution.

## Resources

`resource.discover` lists registered types and their available operations. `resource.list` accepts `type` and optional `cursor`, `limit`, and `search`. `resource.load` accepts `type` and `id`.

For create or save, first use `payload.canonicalize` with a `payload` object. It returns the normalized payload and the real typed protocol checksum. Pass those values to `resource.create` or `resource.save` together with a fresh `mutationId` UUID. Save, delete, duplicate, and activation require the current `expectedRevision`. Create also accepts `presentation` with `displayName`, `path`, and `sortOrder` when the type supports it.

Use `describe` for each operation's exact required and optional fields. Resource changes use the same typed resource protocol, durable mutation authority, catalog identity, live refresh, and recovery as normal clients. Unsupported operations are reported honestly. Durable resource rename is currently unavailable.

## Catalog Inspection

`catalog.inspect` reads the current admitted node catalog. Optional `owner`, `nodeId` and `functionId` filters match exact identities. `functionId` selects the generated node for an authored Function. The response includes the catalog identity, qualified node ID, schema version, display text, handler contract, metadata and pins with their types, defaults and requirements. Use those returned identities when authoring graphs.

Pages default to 50 nodes and allow at most 200. Pass the returned `nextCursor` as `cursor` to continue. Supply the returned `serverId`, `catalogGeneration`, `catalogChecksum`, `bindingManifestHash` and `authorityEpoch` on later requests to reject pages from a changed catalog. The operation rejects stale admission or a catalog change during capture.

## Execution And Events

`execution.discover` lists stored Flow, Function, and Command graph identities and event bindings. Use the returned `resourceType`, `resourceId`, `revision`, and `checksum` for execution. `flow.run` also requires `startNodeId` and accepts `variables`, an optional online `playerId`, and `timeoutMillis`. `function.run` accepts `inputs` keyed by declared parameter UUID or name and optional variables. Inputs must match their declared types. Missing optional inputs use declared defaults. Unknown and duplicate parameter identities are rejected. Flow, Function and Command execution use committed typed sources and the existing compiled owners, including nested Function calls. A stale revision or checksum is rejected before execution.

`event.inject` currently supports `eventType: "block_break"`. Supply a loaded `worldId`, an online `playerId` in that world, and integer `x`, `y`, and `z` inside an already loaded chunk. Optional `cancelled`, `dropItems`, and `experience` initialize the fixture. ReSync calls the actual Bukkit event system, records matching flows and their results, and reports the final event and block state.

Event injection dispatches listeners. It does not perform Minecraft's vanilla block destruction, item drops, or experience spawning. Listener and flow effects are real. Use a disposable test world when testing mutations.

## Gameplay

`game.inspect` lists online players and active NPCs. Supply `playerId` to inspect that player's inventory and nearby entities. `custom.inspect` accepts `contentId` and reports its stored definition and provider availability.

`custom.item.give`, `custom.item.inspect`, and `custom.item.remove` inspect and change actual player inventory slots. Give requires an empty slot. Remove requires the matching custom identity. `custom.block.inspect`, `custom.block.place`, and `custom.block.remove` use loaded world coordinates. Placement requires an empty position and a supported Vanilla content provider. These fixture calls use the existing content services. A real client placement or item use also exercises Minecraft's event path.

`npc.inspect`, `npc.spawn`, `npc.remove`, and `npc.interact` use stored NPC definitions and the existing NPC service. The interaction fixture requires an online player within six blocks. It dispatches a right click. Entity NPC and Player NPC clicks from a real client exercise their separate Bukkit and packet paths. The fixture receipt confirms the service call and snapshot. Inspect the resulting effects separately when a hook runs asynchronously.

## Advancements

`advancement.inspect` returns committed trees, native availability, and each tree's revision and checksum. `advancement.progress` reads an online player's native progress. `advancement.evaluate`, `advancement.grant`, and `advancement.revoke` require `treeId`, `nodeId`, `criterion`, `playerId`, `revision`, and `checksum`.

Evaluate checks built-in conditions without awarding progress. It does not execute an external Function predicate. Grant awards the native criterion directly and bypasses its conditions. Native rewards can apply. ReSync completion actions run through the owning event or quest path, so a direct grant does not establish that those actions ran. A parent organizes the displayed tree. Use an explicit condition when progress must depend on a parent.

Create and edit trees through the typed resource operations. QA rejects native progress access while a committed tree's runtime projection needs recovery. Paper owns player progress files. Verify saved progress after a reconnect or restart when durability matters.

## Java Adapter

```java
QaService qa = ReSyncApi.qa();
Map<String, Object> receipt = qa.submit(sender, "resource.discover", Map.of());
UUID runId = UUID.fromString((String) receipt.get("runId"));
Map<String, Object> result = qa.poll(sender, runId);
```

The public service interface supports discovery, strict JSON parsing, plain decimal JSON rendering, submission, and polling. A different transport can adapt these methods without introducing another executor, mutation authority, or receipt store.

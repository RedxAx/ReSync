# ReQuest

ReQuest adds a quests system to ReSync.

## Additions:
- Extension id: `request`
- Module/channel: `request:quests`
- Quest option catalog: `request:quest_ids`
- Event option catalog: `request:event_ids`
- Flow type metadata: `request:quest`
- Nodes:
  - `Quest Info`
  - `Create Quest`
  - `Start Quest`
  - `Add Quest Progress`
  - `Complete Quest`
  - `Quit Quest`
  - `Quest Event Listen`
- Quest state:
  - Quests are definitions only until a player starts them
  - Per-player active/completed/quit state
  - Numeric progress against each quest target
  - Quest XP and ReQuest levels
  - Restrictions: minimum level, permission, world, required quest, active quest limit, and cooldown

## Build

Build ReSync first so `../../build/libs/ReSync-*.jar` exists, then run:
```
gradlew jar
```

Copy the jar into:
```
plugins/ReSync/extensions
```

ReSync scans that folder while running.

## Examples:
There are 4 premade quests (inactive by default): gather_logs, light_caves, map_spawn, and feed_team.
Create a flow with `Player Join` -> `Start Quest` -> `Send Message`.

Use `gather_logs` in the quest selector. The selector is backed by the extension option catalog.

Then create another flow with `Player Block Break` -> `Add Quest Progress` -> `Send Message`. Use the same quest id and amount `1`.

Complete `gather_logs` once to gain XP. `light_caves` requires ReQuest level `2`, so it becomes a natural restriction test after the first quest completion.

Use `Quest Event Listen` with `request:progress` to react to progress changes. The event data includes `progress_added`, `previous_progress`, `progress`, `target`, `progress_percent`, `level`, `xp`, and `xp_to_next_level`.

Use `Quest Event Listen` with `request:completed` to continue a flow after any quest is completed. Event data is exposed on `event_data`.
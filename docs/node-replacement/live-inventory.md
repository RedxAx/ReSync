# Gate 0A Live Inventory

## Local ReSync Data

The only ReSync plugin data root found inside the ReProjects workspace is `C:\Users\redxa\ReProjects\ReSync\run\plugins\ReSync`.

Captured read-only on 2026-08-02:

- 10 files, 1,982,595 total bytes.
- `assets/project.json` contains the default folder hierarchy and zero resources.
- `extensions` is empty and `run/plugins` contains no plugin jars.
- `world-management/worlds.json` contains three `resync-acceptance-world` dimensions; the remaining world-management collections are empty arrays.
- Two large `diagnostics/programmability-acceptance-*.json` files account for nearly all bytes and require explicit secret/player-identity inspection before fixture copying.
- No complete real populated ReSync-folder snapshot is present in the workspace. Gate 3B therefore cannot treat this acceptance run directory as a populated production snapshot.

## Installed Remotely Extensions

`C:\Users\redxa\ReProjects\Remotely\plugins` contains no files. No installed local extension contributes nodes in the inspected workspace state.

## Generated Functions And Server Resources

The local project metadata has zero declared resources, so generated Function nodes and persisted graphs cannot be inventoried from this run data. Their code paths and test builders remain part of Gate 0A, and deterministic generated fixtures are required before Gate 0B.

# Compatibility Matrix

Status: supported replacement window resolved by product-owner autonomous direction.

| Dimension | Gate 0A Current | Replacement Requirement |
|---|---|---|
| Java | 21 | 21 |
| ReSync | legacy source head `b2941e66d762ffb76391715fbf4487c068fe12ea` plus immutable plan | supported direct-upgrade source; replacement generation follows the frozen Gate 0B contract |
| ReSyncCore | `1.3.0`, included build | sole shared contract owner, dependency-free from Bukkit/Paper/Minecraft/Remotely/ReScreen |
| Remotely | supported legacy client head `76c4eb8989aaf856a4f002fa7fe6e56a67c373df` | negotiated generic client and read-only fallback evidence |
| Paper | compile/test API 1.21.10 | declared tested range, no contract dependency |
| Velocity | included `ReSyncVelocity` | shared contracts remain platform independent; network participant included in snapshots |
| Extensions | current plugin contributions | semantic contract ranges, namespaced ownership, atomic activation, generic/read-only client fallback |
| Persisted format | the format readable/writable at ReSync `b2941e66d762ffb76391715fbf4487c068fe12ea` plus explicitly retained regression fixtures | one replacement version; standalone upgrader supports this declared window |

Gate 0B must freeze exact contract generations, client capability fallback behavior, and restore build compatibility rules within this declared window.

Repository history does not expand the window: ReSync has no tags; Remotely's only `BETA` tag predates the current Flow/resource/workspace implementation; the `node-system-v2` refs are ancestors rather than published support boundaries.

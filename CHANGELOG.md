## Added
- Added packet-based ReSync connections for Remotely and RemotelyMod, including plugin message bridging, chunked envelopes, session tracking, and frame sending.
- Added packet-based GUI and scoreboard subscriptions so Remotely can receive live ReSync UI state more reliably.
- Added the beta ReSync Extension API with extension registration, extension storage, option catalog providers, node definitions, extension data, and extension manager support.
- Added the ReQuest example extension as a working API example with quest profiles, quest state, commands, handlers, modules, and custom node definitions.
- Added player facet metadata/state tracking and player tracking services for richer live project context.
- Added option catalog and node registry packet handling so Remotely can load extension and built-in node data dynamically.
- Added custom content metadata and compatibility support for ReSync custom content projects.
- Added GUI item command actions.
- Added an improved Leap To Location node.
- Added built-in generator registration support through the ReSync world generation path.
- 
## Changed
- Restructured ReSync file and content management across flow storage, custom content storage, world generation storage, metadata, and packet handlers.
- Improved Flow runtime reliability through stronger mutations, execution context handling, type adaptation, JSON family behavior, and migrated node definitions.
- Improved custom content graph compatibility and listener behavior.
- Improved connection management around sessions, channel registration, chunk modules, world modules, player tracking, and ReSync server startup.
- Improved Flow registry and plugin loading so extensions can contribute nodes and option catalogs through the new API.
- Improved Remotely integration for GUI, scoreboard, player, node registry, option catalog, and custom content synchronization.

## Fixed
- Fixed broken ReSync file storage paths for flows, custom content, and world generation projects.
- Fixed flow reliability issues in custom content, ability effects, entity actions, player actions, JSON handling, and migrated node catalogs.
- Fixed invisible player messages when multiple text formatting operations are used.
- Fixed double log prefixing.
- Fixed numeric logic comparisons so `0`, `"0"`, and `0.0` are handled consistently.
- Fixed ReSync connection issues in the packet-based Remotely bridge.
- Fixed GUI and scoreboard subscription behavior over packet-based connections.
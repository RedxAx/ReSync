## Added
- Added the Chat module with channels, formats, rules, private messages, mentions, ignore lists, channel membership, cooldowns, chat spy support, and new chat flow nodes and events.
- Added the MOTD module with profile-based server list MOTDs, icon support, and live MOTD rendering through ReText.
- Added the Recipe module for custom shaped, shapeless, furnace, blasting, smoking, campfire, stonecutter, and smithing recipes using custom content items and vanilla/provider items.
- Added the Message Rewrite module for join, quit, death, kick, and ProtocolLib-backed message customization with predicates and flow hooks.
- Added ReTextService for MiniMessage, legacy color codes, placeholders, animations, and text template rendering.
- Added unified JSON resource storage and packet routing through FlowResourcePacketRouter, JsonAssetStore, and ReSyncResourceCatalog for chat, MOTD, recipe, message, and text template resources.
- Expanded chat and permission migrated node catalogs and added chat and permission flow handlers.

## Changed
- Consolidated Remotely packet handlers into unified FlowResourcePacketHandler and FlowResourcePacketRouter instead of separate GUI, scoreboard, tab, custom content, and metadata handlers.
- Improved custom content storage, flow storage, and world generation storage to work with the new managed resource system.
- Expanded ReSync commands and resource diagnostics for the new customization and content resource types.

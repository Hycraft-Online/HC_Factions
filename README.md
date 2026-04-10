# HC_Factions

Hybrid faction-guild system combining admin-defined major factions with player-created guilds. Players choose a major faction on first join and can then create or join guilds within their faction. PvP is faction-based (same-faction players are protected), while land protection is guild-based (only guild members can build on claimed chunks).

## Features

- Admin-defined major factions loaded from database with configurable colors, spawn points, and capitals
- Player-created guilds within factions with roles, permissions, and activity logging
- Faction selection UI shown to new players on first join
- Chunk-based land claiming system with guild ownership and role-based access control
- Claim protection for block placement, block breaking, interactions, crop harvesting, and item pickup
- Claim decay for inactive guilds
- Claim bypass API for external plugins to override protection checks
- Faction-colored player nameplates with level display (via HC_Leveling)
- Territory HUD showing current chunk ownership as players move between claimed areas
- World map integration with claim visualization and faction capital markers
- Faction-based PvP damage rules (same-faction friendly fire prevention)
- Combat tagging system with ECS component tracking
- Faction-based respawn system with bed respawn support
- Spawn suppression in claimed chunks
- Faction-colored chat formatting via HeroChat integration
- Guild browser, management, invitation, and permission matrix UIs
- Admin commands for faction and claim management
- Random teleport fallback for faction selection via HC_RTP
- PostgreSQL-backed persistence for factions, guilds, claims, player data, and chunk access
- Configurable via HC_Core settings API

## Dependencies

- **EntityModule** (required) -- Hytale entity system
- **HC_Core** (required) -- shared database pool and settings API
- HC_RTP (optional) -- random teleport on faction selection
- HC_Leveling (optional) -- player level in nameplates
- HeroChat (optional) -- faction-colored chat formatting

## Building

```bash
./gradlew build
```

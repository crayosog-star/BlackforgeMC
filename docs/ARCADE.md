# BlackForge Arcade

The Arcade server (`arcade`, Paper 26.3, port 30005 behind the Velocity proxy) runs 17 minigames, each with its own pool of maps. All of it lives in the existing **AlloyCore** plugin (`plugins-src/AlloyCore`, package `net.alloymc.core.arcade`). It uses the network's shared money and Scrap for rewards.

## How it works

```
Arcade -> pick a game -> pick a mode -> queue -> match found -> random map loads -> teleport -> match starts
```

| Piece | Class | Job |
|---|---|---|
| Lobby | `arcade/ArcadeModule` | Spawn platform, quick-join plates, hotbar, sidebar, top-winners hologram |
| Service root | `core/Arcade` | Wires everything up; holds the game registry, settings and map validity |
| Game registry | `arcade/Games` + `core/ArcadeGame` | One `ArcadeGame` per mode: variants, map requirements, built-in maps, default settings |
| Map registry | `core/MapStore`, `core/MapDef` | One YAML file per map, in a **separate pool per game** |
| Map choice | `core/MapSelector` | Weighted random over eligible maps in that game's pool only; avoids the game's recent maps |
| Worlds | `core/Worlds` | `arcade_templates` (immutable templates) and `arcade_matches` (instances) |
| Instances | `core/SlotAllocator` | Each match reserves its own slot, 1024 blocks apart, and gets a fresh copy of its map |
| Lifecycle | `core/MatchManager`, `core/Match`, `core/MatchState` | Queues, countdown, map loading with retry, state machine, ending and reset |
| Events | `core/ArcadeListener` | Routes each event to the one match it belongs to; denies everything else in the match world |
| Menus | `core/ArcadeMenu` | Categories, paging, per-game variant and status screen, map list, stats |
| Commands | `core/ArcadeCommand` | `/arcade` for players and staff |
| Stats | `core/StatsService` | `arcade/stats/<uuid>.yml`; cached and written off the main thread |
| Rewards | `core/RewardsService`, `core/RewardLedger` | Money and Scrap through NetEconomy; every payout happens at most once |
| Ratings | `core/Ratings` | Elo for ranked variants |

**Lifecycle** (checked transitions; an illegal one throws):

```
COUNTDOWN (queue) -> MAP_LOADING -> TELEPORTING_PLAYERS -> STARTING -> IN_PROGRESS -> ENDING -> RESETTING -> AVAILABLE
```

**Random map selection.** When a queue's countdown ends:
1. The match reserves a free instance slot. If all slots are busy, players stay first in line and it retries in 5 seconds.
2. A map is picked at random, weighted by `weight`, from **that game's pool only**. Eligible maps are enabled, pass validation, have a built template, support the chosen variant, and fit the player count and team bases.
3. The game's last `avoid-recent-maps` maps are skipped when another eligible map exists. A pool with a single map can still play it.
4. The map is copied fresh into the slot. If loading fails, that map is excluded and the next eligible one is tried, at most once per map, so there is no infinite loop. If nothing loads, the slot is released and players stay queued with a clear message.
5. Players are told `Map selected: <name>`, teleported to that map's own spawns, and the game starts.

Players never pick or vote on maps. Staff can force one map for the next match with `/arcade forcemap`.

**Isolation.**
- Every match has a unique ID (e.g. `BEDWARS-3K2F`), its own slot, players, teams, placed blocks and tasks.
- Two matches on the same map each get their own copy.
- Liquids, explosions and falling blocks can't leave a match's bounds.
- Resetting only clears the match's own slot.
- After a crash, the match world's entities are removed on startup, and every slot is wiped before it is pasted again.

## Game modes and maps

| Game (id) | Variants | Maps (all generated, original) |
|---|---|---|
| BedWars (`bedwars`) | Solo, Doubles, 3v3v3v3, 4v4v4v4 | Forgeworks (4 teams), Verdant Isles (8), Frostspire (4) |
| SkyWars (`skywars`) | Solo, Doubles, Ranked Solo | Skyreach Ruins (8), Gilded Atoll (12), Cinderfall (8) |
| Duels (`duels`) | Classic, NoDebuff, Sumo, Boxing, BuildUHC, Combo (1v1); Classic, BuildUHC, Sumo (2v2); Ranked Classic, NoDebuff, Sumo | Coliseum, Ironyard, Grove (open-arena kits); Dojo, Stone Ring (Sumo); Red Canyon, Highlands (BuildUHC) |
| Spleef (`spleef`) | Solo | Frostbowl, Glacier Grid (2 rounds), Snowflake |
| TNT Run (`tntrun`) | Solo | Triple Tier, Checkerfall, Molten Crater |
| Build Battle (`buildbattle`) | Solo | Classic Plots (16), Sky Plots (12) |
| Murder Mystery (`murder`) | Classic | Old Manor, Archive Library, Frost Lodge |
| Hide and Seek (`hideseek`) | Classic | Village Market, Workshop |
| Parkour (`parkour`) | Solo Run, Race | Meadow Run (easy), Foundry Climb (medium), Ember Spire (hard) |
| TNT Tag (`tnttag`) | Solo | Rooftops, Courtyard |
| FFA Arena (`kitpvp`) | Free for All (join any time) | Ruined Colosseum (lava pit), Ironclad Yard (fire vents), Windswept Cliffs |
| Block Party (`blockparty`) | Solo | Disco Floor, Neon Ring, Garden Grid |
| Dropper (`dropper`) | Solo Run, Race | Forge Shafts (3 levels), Emerald Depths (4 levels) |
| The Bridge (`bridge`) | 1v1, 2v2, 4v4 | Classic Span, Gilded Gorge, Iron Crossing |
| UHC Meetup (`uhc`) | Solo, Teams of 2 | Highlands, Taiga Basin, Badlands |
| Speed Builders (`speedbuilders`) | Solo | Island Ring, Grid Plaza |
| Infected (`infected`) | Classic | Old Town, Deep Mine |

That's 47 maps in total. Every map has its own spawns, bounds, void level, spectator spawn and game structures (beds, generators, chests, goals, plots, checkpoints, gold spots and so on). Run `gradle arcadePreview` (optionally with `-Pgame=<id>`) to render isometric PNGs of every built-in map into `plugins-src/AlloyCore/previews/arcade/`.

## Files

```
arcade/plugins/AlloyCore/arcade/
  config.yml               limits, map rotation, rewards
  games/<game>.yml         settings for one mode (any key can be overridden per map)
  maps/<game>/<map>.yml    one file per map
  stats/<uuid>.yml         per-player stats
  templates/               markers recording which built-in templates have been generated
```

`config.yml`:
```yaml
max-active-matches: 12     # instance slots; more queued matches wait their turn
max-queue-size: 64
avoid-recent-maps: 2       # per game; never blocks a pool from playing at all
blocks-per-tick: 20000     # paste/clear speed
rewards:
  default: {win-money: 150, win-scrap: 15, play-money: 25, play-scrap: 2, kill-money: 10, kill-scrap: 1, min-seconds: 45}
  bedwars: {win-money: 300, win-scrap: 30}      # per-game override
```

Example map file (`maps/bedwars/forgeworks.yml`, trimmed):
```yaml
display-name: Forgeworks
description: Copper foundries floating over the void.
enabled: true
weight: 1                  # random-selection weight
min-players: 2
max-players: 16
teams: 4
bounds: -48, -40, -48 ; 48, 40, 48     # relative to the map origin
void-y: -22
spectator-spawn: 0.5, 30, 0.5, 0, 60
points:
  team: {'0': {bed: '0.5, 1, -31.5, 180', generator: ..., shop: ..., upgrades: ..., spawn: ...}}
lists:
  diamond_generators: [...]
  emerald_generators: [...]
settings:
  diamond-interval-seconds: 25         # overrides games/bedwars.yml for this map only
template: {x: 26400, y: 100, z: 0, builder: 'bedwars:forgeworks', builder-version: 1}   # where the template lives
reset-strategy: fresh-instance
```

Game settings examples (from `games/*.yml`):
- **BedWars:** generator intervals, generator cap, upgrade prices, respawn time, bed-destruction time.
- **SkyWars:** `loot.island` and `loot.center` (lines like `"IRON_SWORD 1 8 sharpness:1"` = item, amount, weight, enchant), refill times, border timing, kits.
- **Duels:** `kit.<name>` item lists, `rounds.<kit>` (best-of), `boxing-hits`, `ranked-enabled`.
- **Build Battle:** themes, build and vote time, plot size, blocked items.
- **Parkour and Dropper:** race grace time. Checkpoints, finish and level goals live in the map files.

## Adding a map to any game

1. `/arcade create-map <game> <map-id>` makes a disabled map in its own area of the template world and teleports you there in creative.
2. Build the arena.
3. Stand on each spot and set the positions it needs. `/arcade validate-map <game> <map>` lists exactly what's missing.
   - `/arcade setspawn <g> <m> [n|add]`
   - `/arcade setteamspawn <g> <m> <team#> [key]` (e.g. `bed`, `generator`, `shop`)
   - `/arcade setspectator <g> <m>`, `/arcade setlobby <g> <m>`, `/arcade setvoid <g> <m>`
   - `/arcade setboundary <g> <m> pos1|pos2`
   - `/arcade setpoint <g> <m> <key>`, `/arcade addpoint <g> <m> <list>`, `/arcade setregion <g> <m> <key> pos1|pos2`
   - `/arcade set <g> <m> <field> <value>` (name, weight, player limits, teams, or any game setting)
4. `/arcade validate-map <game> <map>` reports errors and warnings, such as overlapping spawns.
5. `/arcade enable <game> <map>`. This is refused until the map validates. It then joins that game's random rotation.
6. To try it straight away, `/arcade forcemap <game> <map>` makes the next match use it.

After editing a template, `/arcade refresh-template <g> <m>` makes new matches use the changes. `/arcade reload` re-reads every config and map file safely, and running matches keep the map they started with.

## Adding a game mode

1. Create `arcade/games/<mode>/<Mode>.java` extending `ArcadeGame`. Declare its variants, `requirements()` (the points, lists and regions every map must have), `defaultSettings()`, and `builtInMaps()` (use `MapKit` and `Rooms` to generate blocks, then fill a `MapDef`).
2. Write a `Match` subclass that implements `setup()` (spawns and kit) and `objective()`. Override only the hooks you need, such as:
   - `onStart`, `secondTick`, `fastTick`
   - `allowBreak` / `allowPlace`, `onDamage`, `onDeath`, `onVoid`, `onInteract`
   - `checkWinners`, `onTimeUp`, `sidebar`

   The base class already handles the lifecycle, teams, spectators, kill and assist credit, stats, rewards and cleanup.
3. Register it in `arcade/Games.java`. The menu, queues, map pool, `/arcade` commands, stats and leaderboards pick it up automatically.
4. `gradle test` checks that every built-in map of every game validates.

## Commands and permissions

| Command | Permission |
|---|---|
| `/arcade` (menu), `join <game> [mode]`, `leave`, `games`, `maps <game>`, `top <game> [stat]`, `stats [player]`, `spectate <player>`, `rematch` | `arcade.play` (everyone) |
| `create-map` | `arcade.map.create` |
| `edit`, `mapinfo`, `validate-map`, `enable`/`disable`, `setspawn`/`setteamspawn`/`setlobby`/`setspectator`/`setboundary`/`setpoint`/`addpoint`/`clearpoints`/`setregion`/`setvoid`/`set`, `refresh-template` | `arcade.map.edit` |
| `reload` | `arcade.map.reload` |
| `status`, `forcestart <game> [mode]`, `forcemap <game> <map\|off>`, `stop <match\|all>` | `arcade.match.manage` |
| everything | `arcade.admin` (op by default) |

## Build, test, deploy

```
cd plugins-src/AlloyCore
gradle test           # unit tests (23)
gradle jar            # build/libs/AlloyCore.jar
gradle arcadePreview  # render every built-in map
python ../../tools/netctl.py restart arcade   # deploy safely (never copy a jar into a running server)
```

On first start, built-in maps are written to `maps/` and their templates generated in `arcade_templates`. This takes a few seconds, and each map becomes playable once its template is ready.

## Test results (2026-10-26)

**Unit tests (`gradle test`): 23 passed.** They cover:
- Map validation, including errors and warnings.
- Pools staying separate per game, and selection never picking a disabled or invalid map.
- Recent-map avoidance per game, single-map pools, failed-map exclusion and empty pools.
- Fair random spread, weights, and variant, player-count and team-capacity checks.
- Simultaneous matches getting separate slots even on the same map, slot regions never overlapping, and slot release ownership.
- Team balancing, lifecycle transitions, one-time rewards, Elo, map-file round trips and coordinate parsing.
- Every built-in map of all 17 games validating, and different maps using different positions.

**Live bot play-tests (mineflayer, on the real server):**
- **Core:** queue and leave, countdown cancel, random map per game, the second match avoiding the previous map, two Spleef matches plus TNT Run in parallel slots, eliminations, wins, rewards paid once, stats, instance reset and slot release.
- **Duels:** best-of-3 Sumo, and rematches.
- **BedWars:** bed break, final kill, team elimination and win.
- **SkyWars:** loot in chests and the win.
- **Parkour and Dropper:** fall recovery.
- **Murder Mystery:** roles, stab kill, and the detective's bow dropping.
- **TNT Tag:** the fuse eliminates its holder and the next round starts.
- **The Bridge:** goal scoring.
- **Block Party:** rounds and eliminations.
- **Speed Builders:** judging and elimination.
- **Hide and Seek:** hiders turn solid and a seeker finds one.
- **FFA Arena:** late join, spawn protection, kill credit and respawn.
- **UHC:** a win.
- **Build Battle:** a full vote with self-votes blocked.
- **Staff:** `/arcade stop all`.
- Four different games ran at once in separate slots, and there were no server errors during any test.

**Not covered by bots** (need real players; checklist below): fine movement skill (actual parkour jumps, dropper aiming, block-party running), the BedWars shop clicked through villagers (the 1.20.4 test bot's villager clicks don't arrive; the robot copper golems needed the other click type too), BedWars upgrades and fireballs, SkyWars kit menu, Duels NoDebuff potions and Combo hit speed, Murder Mystery knife throw and gold-to-bow, Hide and Seek moving disguises, and how everything looks and sounds.

## Manual test checklist (2+ real players)

- [ ] `/arcade` menu: categories, paging, status (Available / Starting soon / In progress / Full), and leaving the queue from the menu.
- [ ] Join each game. The countdown starts at the minimum and stops if someone leaves.
- [ ] "Map selected: X" names a map from that game. Play a few rounds and the map changes.
- [ ] Two groups in the same game at once both play, even on the same map.
- [ ] **BedWars:** the shop buys wool, swords, armour and fireballs. Upgrades apply to the whole team. Breaking a bed stops respawns. Your own bed can't be broken. Resources go to the killer.
- [ ] **SkyWars:** the kit menu works in the cage. Chests have loot and refill at 3:00. The border hurts you outside it.
- [ ] **Duels:** each kit's inventory is right. Sumo knock-off scores a round. Boxing ends at 100 hits. Rematch works. Ranked changes the rating.
- [ ] **Murder Mystery:** name tags are hidden. 10 gold gives a bow. Shooting an innocent kills the shooter. The knife throw works.
- [ ] **Hide and Seek:** the disguise follows you while moving. Standing 3s makes you solid. A seeker hitting the block finds you.
- [ ] **Build Battle:** you can only build on your own plot. Spawn eggs and blocked items are refused. Voting and results work.
- [ ] **Parkour and Dropper:** checkpoints save, falls return you, finish times and personal bests are recorded, and `/arcade top parkour best_time` shows them.
- [ ] Disconnect mid-match (BedWars or Bridge) and reconnect within 2 minutes: you're put back in.
- [ ] Staff: `create-map` -> set points -> `validate-map` -> `enable` -> `forcemap`, then play it.

## Known limitations and remaining work

- Maps are generated in code. Staff can rebuild them by hand in the template world, but signs and container contents in a template aren't copied: blocks only (chests are refilled by the games).
- There's no party system yet (`/party`). Teams are filled round-robin in queue order, so friends who queue together aren't guaranteed the same team.
- Ranked play exists for Duels (Classic, NoDebuff, Sumo) and SkyWars Solo only.
- Map voting is deliberately off: every match gets a random map, as requested.
- The lobby has quick-join plates for Spleef, TNT Run and Infected. Every other game is reached through the menu.
- Hide and Seek moving disguises use a block display that follows the player, so very fast movement can look slightly delayed.
- UHC maps are large (about 290k blocks each), so loading takes about 2 seconds.

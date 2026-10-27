BlackForgeMC
===============

Join (on this PC):   localhost:25577        (Minecraft Java 26.3; older versions down to 1.13 also work via ViaVersion)

Start:  double-click start-alloymc.bat      (runs everything in the background, no windows)
Stop:   double-click stop-alloymc.bat       (saves every world first - always use this, never kill java)
Status / console commands:
        python tools\netctl.py status
        python tools\netctl.py cmd hub op <name>        (any server: hub, skyblock, survival, factions, arcade)

Layout
  proxy\      Velocity 4.3 on port 25577 (the only public port). Online mode, modern forwarding, ViaVersion+ViaBackwards.
  hub\        Paper 26.3 :30001 - floating forge island hub, compass menu, walk-in portals, double jump
  skyblock\   Paper 26.3 :30002 - BentoBox + BSkyBlock, Level, Challenges, Warps (/island)
                OneBlock (AOneBlock 1.28): walk through the purple OneBlock Gate at Sky Harbor or /ob. /istop = top OneBlock
                islands by blocks broken (+ phase, level); also a TOP ONEBLOCK board in the Hall of Fame.
                OneBlock progress tag floats over every OneBlock (phase, blocks, % to the next biome). AOneBlock rolls biome chests
                per phase. Spawner Fragments: ~1/300 OneBlock breaks, ~1/12 OneBlock chests (skyblock config oneblock:); 9 craft an
                Empty Spawner -> right-click, buy a mob with Mob Essence (OneBlock: mobs of every biome phase reached).
                Spawner Tier I-V rises with kills of its own mobs (64/256/768/2000) and caps the Essence upgrades (Spawn Time,
                Mob Count, Stack, Range, Essence Yield) and which mob tiers it can hold. Upgrades/tier/kills survive Silk Touch.
                Robots (/robots workshop, $7,500): copper golems that work while fuelled - Farmer, Miner (mines the block in
                front, incl. the OneBlock when the owner is online), Grinder (kills mobs, keeps loot/XP/Essence), Fisherman.
                Tiers I-V; Fuel Cells I-V (30m/2h/6h/12h/24h) power them and build Charge (x1..x3) needed to tier up
                (+ Essence + money). Limit 2 robots, ranks 3-8. Data: skyblock\plugins\AlloyCore
obots.yml.
                Give: cmd skyblock robots give <player> <robot|fuel|essence> [tier/amount] [job]
  survival\   Paper 26.3 :30003 - AliACraft-style survival (all in AlloyCore + EssentialsX economy/homes):
                floating spawn town (crates pavilion, rules board, richest-players board, market hall, Wild + KOTH gates)
                /claim chunk claims (/claim map, /trust), /warp crates (Cinder/Molten/Forgeheart/Conqueror/Spark keys - edit plugins\AlloyCore\crates.yml; ids stay iron/gold/netherite/koth/vote, either name works in commands),
                KOTH every 2h (settings in plugins\AlloyCore\config.yml), /ah auction house, /rtp anywhere in the 10k world border,
                /pvp = The Scrapyard: 200x200 walled PvP wasteland, ~15 supply crates every 5h (1-2 random items: upgraded
                iron/diamond gear, gapples, strength/speed potions, buckets, very rare keys). Force a drop: cmd survival alloy pvp drop
                /tags name colours (items + shop), /kits god kits (store hook: cmd survival alloy kit give <player> <kit>),
                /enchants: Lifesteal, Thunderstrike, Bleed, Overload, Springs, Autosmelt, Excavator - books from crates/supply drops,
                drag a book onto gear. Give: cmd survival alloy ce give <player> <enchant> [level]
                /vote: Votifier (AzuVotifier 3.3.6) on survival, port 8192 (127.0.0.1 for now). Each vote = Spark Key + $250 + 20 Scrap,
                offline votes queue, vote party every 50 votes. Add vote-site URLs in survival config.yml votes.sites.
                Votifier public key for v1 sites: survival\plugins\Votifier\rsa\public.key, v2 token in Votifier\config.yml.
                /scrap: Scrap Shop (earn: kills +25, monsters +1, 10 min active play +10, KOTH +200, supply crate +5, vote +20).
                Spawners from the shop keep their mob type; a Silk Touch pickaxe picks spawners up with upgrades/tier (SilkSpawners: survival, skyblock, factions). Give: cmd survival alloy scrap give <p> <n>
                Ranks Copper/Iron/Gold/Netherite (LuckPerms groups, tools\setup_ranks.py): prefixes, /hat /craft /ec /feed /anvil
                /repair, /fly in own claims (Gold+), more homes + claims, Scrap boost, /monthly keys. Ranks come from vouchers:
                cmd survival alloy voucher give <player> <copper|iron|gold|netherite>. KOTH crate has a 1% Copper voucher.
                Drop Zone (west of spawn): jump in a shaft to fall into the Scrapyard or KOTH. Teleporting OUT of either zone
                (spawn/home/rtp/tpa/back/warp/hub) takes a 10 s warmup that moving or damage cancels.
                Loot test: cmd survival alloy pvp sample 40 (prints to the log). Timing in config.yml under pvp: Give keys: python tools\netctl.py cmd survival alloy crate give <player> <cinder|molten|forgeheart|conqueror|spark> [n]
                Force KOTH: ... cmd survival alloy koth start
  factions\   Paper 26.3 :30004 - PvPIndex-Factions (/f) + AlloyCore factions mode: floating war citadel spawn (y 190) with two
                giant netherite warrior statues; spawn is a safe zone and unclaimable; walk through any gate (or /rtp) to drop into
                the wild; /spawn has a 5 s warmup. SEASONS: the map resets every 3 months (config season.months). /season shows
                the countdown; at the end the top factions (/f top) are saved, everyone goes to the hub and tools\factions_reset.py
                archives the old world + factions data + homes into factions\seasons\season-N (nothing deleted) and starts a new map.
                Staff: /alloy season info | standings | set-end <days> | end confirm, /alloy buildfactions
  arcade\     Paper 26.3 :30005 - BlackForge Arcade: 17 minigames (BedWars, SkyWars, Duels, Spleef, TNT Run, Build Battle, Murder
                Mystery, Hide and Seek, Parkour, TNT Tag, FFA Arena, Block Party, Dropper, The Bridge, UHC Meetup, Speed Builders,
                Infected), 47 maps, random map per match in private instances. /arcade menu; staff guide: docs\ARCADE.md
  plugins-src\AlloyCore   our custom plugin (hub, arcade games, server switching). Build: gradle jar, then python tools\deploy.py
  tools\      JDK 25, Gradle, netctl.py (start/stop/rcon), deploy.py, setup_network.py

Shared across every server:
  Money + Scrap: AlloyCore NetEconomy, one file per player in shared\economy\ (Vault provider, Essentials' own
                 /bal /pay /baltop /eco are disabled). /bal /pay /baltop work everywhere; /eco give|take|set for admins.
  Ranks: Copper/Iron/Gold/Diamond/Netherite live in one LuckPerms YAML store, shared\luckperms (each server's
         plugins\LuckPerms\yaml-storage is a junction to it). No LuckPerms on the proxy (it overwrote rank data).
         Re-create groups: python tools\setup_ranks.py <server>
  Rank perks (Copper/Iron/Gold/Diamond/Netherite): rank name tag + crate keys via /kit every 10 days (Copper 1 Cinder key,
         Iron 2 Cinder, Gold 1 Molten, Diamond 2 Molten, Netherite 1 Forgeheart), homes 4/6/9/13/18, bonus claim chunks
         10/20/35/55/80, /fly in your claims or on your island (Iron+), /repairhand every 2 hours (Gold+).

Moderation (every server):
  AutoMod - slur filter (catches l33t, spacing, repeats) with escalating network-wide auto-mutes 30m/6h/3d/30d,
            spam + flood + advertising filters (3 warnings in an hour = 15m mute), all-caps toned down.
            Word lists: <server>\plugins\AlloyCore\chatfilter.yml  (/automod reloads them)
  Staff (ops, or permission alloy.staff): /mute <player> <10m|2h|3d|perm> [reason], /unmute, /mutes, /alerts
  Shared files: shared\moderation.yml (mutes + offence counts), shared\moderation.log, sharednticheat.log
Anti-cheat: GrimAC 2.3.75 on every backend (alerts staff; kicks at 400 movement / 15 reach / 15 hitbox violations,
  see <server>\plugins\GrimAC\punishments.yml) + AlloyCore Guardian (x-ray mining ratios, auto-clicker CPS + timing).

Backends listen on 127.0.0.1 only, so players can only get in through the proxy.
Update AlloyCore on a server:  python tools\netctl.py restart <server>   (never copy jars into a running server)
Rebuild the survival spawn:     python tools\netctl.py cmd survival alloy buildsurvival
Rebuild the hub from scratch:   python tools\netctl.py cmd hub alloy buildhub
Rebuild the arcade arenas:      python tools\netctl.py cmd arcade alloy buildarcade

2026-10-27
  Hub: dark volcanic restyle under the blackforge:inferno biome (datapackslackforge -> hub\world\datapacks, red sky,
       ash/embers, midnight). All lava is under glass or behind rails. Rebuild: delete hub\plugins\AlloyCore\hub-built.
  Safety: gradle walkcheck (plugins-src\AlloyCore) walks every spawn build for traps and reachable lava - run it after
       editing a builder. At runtime SpawnSafety returns anyone who touches lava/fire or is stuck in a block at a spawn;
       /stuck works in the hub and arcade lobby.
  Drop Zone shafts only land players on open ground (no roofs). Fountain basins at Survival/Skyblock spawn have exits.
  Crates renamed: Cinder (iron), Molten (gold), Forgeheart (netherite), Conqueror (koth), Spark (vote).
  Bot test: tools	estbot\safety.js (proxy offline).

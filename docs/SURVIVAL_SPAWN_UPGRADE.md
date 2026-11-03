# Survival spawn: inventory and the BlackForge volcanic-kingdom upgrade

## 1. Inventory of the live spawn (checked 2026-11-02 against the world files)

The spawn is one floating island (radius 84, surface y=150) at 0,0 in the Survival world. The live world matches
the generated build block for block (457,612 blocks); the only differences are natural (snow, oxidised copper
lanterns, grass/dirt). Nothing has been built on it by hand. Everything below is **kept exactly as it is**.

| Feature | Where | What it does (must keep working) |
|---|---|---|
| Spawn pad + rules board / leaderboard | (0, 151, 32.5) facing north; boards at x=-17 and x=17, z=33 | Where players arrive; holograms (respawned by the plugin) |
| Fountain plaza (courtyard) | centre, r≈13 | Hub of the four paths |
| Crates pavilion | z≈-28, crates at (-9,-28) (-3,-30) (3,-30) (9,-28) (0,-34), vaults at y=152 | Right-click vaults with keys (Cinder, Molten, Forgeheart, KOTH, Spark) |
| Trading Hall | x -15..15, z -79..-59 | 10 named villagers with fixed trades (Gold rank+), workstations; doors at z≈-55 |
| Hall of Fame | arc x 21..39, z -12..-19 | 5 leaderboard pedestals (holograms) |
| Wild gate | x=42, z -3..4, y 151..160 | Walk in → random teleport into the wild |
| KOTH gate / Drop Zone | x=-42 gate; shafts at x=-40, z=-5 (Scrapyard) and z=5 (KOTH) | Walk in / jump down a shaft → KOTH arena or Scrapyard |
| Market hall + auction bell | z≈46, bell at (0, 152, 50) | Ring the bell → auction house |
| Four corner towers | ≈(±27, ±24) | Decorative |
| Farm Ring | r 61..77, 8 sectors | Decorative crops, protected |
| KOTH hologram, crate labels, Hall of Fame boards | runtime | Text displays the plugin respawns |
| Spawn protection, no fall damage | r ≤ 92 | SpawnSafety / SurvivalModule |

Outside spawn: the KOTH arena (z=-420) and the Scrapyard (z=520) float on their own; the AFK zone is at
(1500, 200, 1500). None of them are part of this upgrade.

## 2. Rules for the upgrade

* **Additive only.** Blocks are only ever placed where the world has **air**; no existing block is replaced.
* Never inside a feature's working space: gate openings, shafts, the Trading Hall, crate pavilion, market,
  paths, the spawn pad, or within reach of a hologram.
* Every placed block is logged (plugins/BlackForgeCore/spawn-upgrade-*.log) so each phase can be undone exactly
  (`/blackforge spawnupgrade undo <phase>`).
* A full backup is taken right before each phase; each phase is previewed in a render of the live world first.
* Anything that would need moving or rebuilding an existing feature: **ask first**.

## 3. Phases

**Phase 1: the kingdom around the spawn** (nothing on walking surfaces)
* A great **volcano** rising behind the Trading Hall (north), with a glowing crater, lava streams and molten
  falls pouring into the void, ash and basalt slopes, and **BLACKFORGE** in burning letters on its face,
  visible from the spawn pad.
* A **fortress rampart** ringing the island just past its edge (on a new rock collar, r≈86-92): blackstone and
  deepslate walls, eight towers with glowing orange windows, braziers, banners and lava spilling from
  gargoyle spouts into the void. The spawn becomes the courtyard inside the fortress.
* **The Legendary Forge**: a giant anvil and hammer floating above the fountain, chained, with embers.
* Braziers along the lawns (never on paths).

**Phase 2: destinations** (new structures, joined by gates in the rampart and bridges)
* Survival world: a monumental gate dressing for the existing Wild gate (banners, fire, blackstone arch
  around, not in, its opening).
* Resource world: an ancient mine entrance with shafts, ore displays and lava-lit tunnels (walk in → /resource).
* Nether/adventure gate: a demonic blackstone and crimson fortress entrance.
* Market district and the Blacksmith hall: new buildings in unused space; the existing market and crates
  get forge-themed dressing around them (the treasure-vault look) without touching their blocks.

**Phase 3:** decoration around existing builds (Trading Hall, crates, Hall of Fame) once phases 1-2 are approved.

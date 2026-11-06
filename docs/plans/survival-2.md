# Survival 2

**Status:** Server (PR #210) and wave 1 experiments built. Nothing played in game yet.
**Next task:** set up `survival2` on the server PC, then walk every experiment in `/lab` and vote.

## Concept
**Pitch:** a staff-only copy of Survival where new ideas are tried before they go live. Every idea is a switch in `/lab`; staff vote good/bad and leave notes, and a finished idea moves to live Survival by adding its id to `lab.graduated` in that server's config.

**Decided by Steven:** staff only; the server machine has room for it; build the server first, then every wave 1 idea except the 3D web map (BlueMap).

## How it's kept apart from live
- `lab.enabled: true` (set by `deploy.py` for `survival2`), still `mode: survival`.
- Own wallet `survival2`. Hub rewards never flush into it.
- Progress files under `shared/lab/survival2` (quests, pass, stats, cosmetics, market, events, journey). Bans, nicks, titles, friends, Discord and news stay network-wide.
- Non-staff (no `blackforge.staff`, `blackforge.admin` or `blackforge.lab`) are sent to the hub.

## Wave 1 experiments (ids for `/lab` and `lab.graduated`)
| id | What | Commands |
|---|---|---|
| `ash-urns` | Death (not PvP) puts items + XP in an urn, owner-locked 10 min, crumbles after 60 | - |
| `hearth-contracts` | Buy orders with held money and a 1% fee; fill from inventory, buyer collects | `/contracts` |
| `kindle-draw` | Lottery, 10% house cut, draw every 6 h | `/draw`, staff `/draw now` |
| `living-prices` | Grove Trader prices fall with server-wide sales, recover with a 6 h half-life | `/prices` |
| `ember-bonds` | Lock 1k-50k for 3 d (+3%) or 7 d (+8%), paid offline | `/bonds` |
| `ashfall-nights` | Every 5th night: ash, tougher mobs outside claims, Ashmark drops and a shop | `/ashfall`, `/ashmarks`, staff `/ashfall start\|stop` |
| `forge-rifts` | Rift opens 300-1500 blocks out every 90 min; 3 waves, then a loot burst | `/rift`, staff `/rift here\|stop` |
| `vein-timber` | Sneak-break a vein (Miner 20) or a natural tree (Lumberjack 20) | - |
| `quick-harvest` | Right-click a grown crop to harvest and replant | - |
| `sit` | Right-click a bottom stair or slab with an empty hand | - |
| `forge-post` | Parcels and money to anyone, even offline | `/post` |

Renamed from the pitch: the lottery is **Kindle Draw** and the Ashfall currency is **Ashmarks**, because "Cinder Key" is already the iron crate key.

**Trial plugins:** `setup_network.py` copies `CoreProtect.jar` and `voicechat-bukkit.jar` into `survival2` if they're in `_downloads/plugins`. Simple Voice Chat also needs its UDP port opened and a client mod.

## Risks to watch
- Contracts: the fee and the 5-order cap stop spam; only plain items trade.
- Kindle Draw and Ember Bonds add or remove money; check `/baltop` drift on Survival 2.
- Living Prices may feel punishing; tune `half-price-at` and `floor`.
- Sit: seat height is a guess (`lab-experiments.sit.height`).
- Ashmarks are gunpowder underneath, and crafting with them is blocked. They have no texture yet.

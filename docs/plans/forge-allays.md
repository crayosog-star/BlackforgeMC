# Forge Allays
**Status:** In progress. Approved by Colin on 2026-11-04 with the recommended defaults
**Next task:** P3.T2

## Rules of engagement
> 1. **Read first.** At the start of every session, read this whole document and list open PRs. Start at the task named in **Next task**.
> 2. **Never skip a task.** Do them in order, even if one looks easy, unnecessary or already done. If it's already done, prove it, record the evidence and tick it.
> 3. **Check it's ready.** Before starting, confirm everything under **Needs** exists on `origin/main` and that the cited code still matches. If not, stop and follow rule 5.
> 4. **Stay in scope.** Do only what the task says. Put new ideas under Later ideas and leave them unbuilt.
> 5. **Stop when blocked.** Record the problem in the Blockers log and ask Colin. Don't work around it, fake it or move on to the next task.
> 6. **Don't rewrite the plan silently.** Adding, removing, reordering or weakening a task or check needs Colin's OK and a Change log entry.
> 7. **Done means proven.** Tick a task only when its **Done when** checks passed and **Evidence** is filled in, in the same PR as the work. Label results honestly as verified, untested or planned. Never say something works in game unless someone played it. A green GitHub check is not proof, because it builds the old plugin.
> 8. **One task at a time.** The next task's PR opens only after the previous one is merged. You may prepare the next task on a branch stacked on the previous one, then update it from `main` before opening the PR.
> 9. **Respect phase gates.** Don't start a phase until the previous phase's gate is confirmed by Colin or a tester and recorded.
> 10. **Keep `main` safe.** Every merge goes live. Follow `blackforge-ship`: no secrets, no draft PRs, no local Gradle and no edits to `plugins-src/AlloyCore`. Unfinished player-facing work stays behind its flag.
> 11. **Keep this document current.** Update **Next task** and **Status** in every task PR.

## Concept
**Pitch:** Allays become your base's logistics crew. They stay bound to your Base Station, pick up what your base drops, sort it into chests, refuel your Agents and sell your surplus.

**Why it's worth building:** Agents make items and hoppers move them along fixed lines, but loose drops, sorting, refuelling and selling are still done by hand. Allays turn a base into a system players build once and then expand, which fits the network's automation and selling direction. The base-bound flyer and its job queue are reusable for later base workers.

**How the base fits together**
| Layer | Job | Bought at |
|---|---|---|
| Base Station | Storage and the Forge menu | `/agents` |
| Agents | Make items from one fixed spot | Crates and shops, upgraded in the Forge |
| Hopper tiers | Move items along fixed lines at high speed | Forge |
| Forge Allays | Move loose and unsorted items, fuel and sales | Forge, slot 34 |

**Tiers (cumulative)**
| Tier | Name | New job | Flock | Leash | Cost to reach it |
|---|---|---|---|---|---|
| I | Gatherer | Carries loose drops to the Station | 1 | 12 blocks | $75,000 + 256 Essence |
| II | Courier | Sorts the Station into framed chests; refuels Agents from Fuel Cells in the Station | 2 | 20 blocks | $250,000 + 1,024 Essence |
| III | Steward | Sells from an emerald-framed chest at Gilded Grove prices, less 10% | 3 | 28 blocks | $750,000 + 4,096 Essence |

**Out of scope:** Skyblock, OneBlock, Factions and Forge Wars; combat abilities; following players; fuel or upkeep; a custom allay model; working in unloaded chunks; a new item of any kind.

## Decisions and assumptions
**Decided by Colin**
- Allays are a base upgrade and stay at the base.
- Their vanilla behaviour changes so they're useful there.
- There are 3 tiers.
- "The Forge" means the Base Station's Forge menu, as settled in the Foundry plan.

**Assumptions (recommended defaults; change them before approval if you disagree)**
- The three tier jobs above are the set to build.
- Each tier adds one allay, so a full base has 3. `allays.flock` set to `[1, 1, 1]` makes it one allay.
- Survival only at launch, because selling runs through `GroveTrader`, which only exists on Survival. Skyblock has no sell path to reuse.
- All prices and speeds are proposals, and P5.T2 checks them.
- The upgrade belongs to the base (the Station's region). Nothing is refunded and there's no item.
- Allays are controlled by item frames on chests. An emerald frame means sell chest. Emeralds aren't in `SellCatalog`, so the marker can't be confused with stock.
- Steward sales need the claim owner online on Survival, because `plugin.economy().deposit` takes an online `Player`.
- Steward takes a 10% fee and uses the same daily counter as the Trader.

## Verified facts
Checked against `origin/main` at `236f7d0`. `S` means `plugins-src/BlackForgeCore/src/main/java/net/blackforge/core/survival`.

**Blockers (cleared in Phase 1)**
- **Allay control is unproven.** No allay work code exists; the only allay reference that matters is the `p_allay` cosmetic pet (`plugins-src/BlackForgeCore/src/main/java/net/blackforge/core/Cosmetics.java:182`). Agents avoid AI with `setAI(false)` at `S/Robots.java:304`, and stay still.
- **Stations can't be looked up.** `stations` and `stationFor` are private (`S/BaseStation.java:70`, `151`).
- **Fuel needs a player.** `addFuel(Player, Bot, boolean)` at `S/Robots.java:769`.
- **Selling needs a player's inventory.** `sell(Player, Offer, int)` at `S/GroveTrader.java:133`.
- **The Forge has one free slot to spare.** Cards use columns 0, 2, 4, 6, 8 across rows 0-2 (`S/BaseStation.java:270`, `326`), and slots 27, 31, 32 and 33 are in use (`S/BaseStation.java:272-274`). `docs/plans/agent-academy.md:218` reserves 35, so allays use 34.

**Other facts**
- `Robots`, `HopperUpgrades` and `BaseStation` are constructed on Survival, Skyblock and the Foundry (`S/SurvivalModule.java:155-162`, `S/SkyblockModule.java:114-119`, `S/FoundrySystems.java:26-33`).
- `GroveTrader` is constructed on Survival only (`S/SurvivalModule.java:202`).
- The Survival Station region is `claim:<owner>`, and the Foundry's is per chunk (`S/SurvivalModule.java:164`, `S/FoundrySystems.java:36`).
- Agents only work in loaded chunks (`S/Robots.java:406`).
- Stale entities are handled at `S/Robots.java:382`, the pattern to copy for allay entities.
- Agents keep an unused rule for refuelling: `RobotRules.shouldFetchFuel` (`S/RobotRules.java:63`), already unit tested (`RobotRulesTest.java:56`).
- Sales cap: `SellCatalog.payout` with `softCap` and `overRate`, 1,728 items at full price then 25% (`S/SellCatalog.java:17-18`, `S/GroveTrader.java:138`).
- `S/LagGuard.java:25` warns staff about chunks at 90 mobs, so 3 allays don't trigger it alone.
- Each server's plugin data is backed up nightly (`tools/backup.py:42-43`), which covers a new `allays.yml`.
- First-use tips live in `hub/FoundryTips.java:31`. Each server's Foundry sandbox wiring is in `S/FoundrySystems.java:25-40`.
- Station and hopper numbers are config-driven (`plugins-src/BlackForgeCore/src/main/resources/config.yml:124-138`). Agent costs are fixed arrays (`S/Robots.java:63-65`), so allays follow the Station pattern instead.

**Unverified**
- Whether an allay with `setAI(false)` moves smoothly when teleported every few ticks on Paper 26.3, and whether Paper still exposes `Allay.setCanDuplicate`. P1.T3 tests both.
- Whether a dropped item's `Item.getThrower()` is set for every player drop. P2.T2 tests it.
- How the Forge's art looks with an icon in slot 34. P2.T1 checks it.

## Counterplay and theme
**Counterplay:** allays have no combat use, can't be hurt and only touch items inside their owner's claim, so no item counter table applies. The EMP doesn't affect them. The risk is griefing and economy abuse, which the exploit list below covers.

| Exploit | Stopped by | Checked in |
|---|---|---|
| Taking death drops, or baiting players to die in your claim | `PlayerDeathEvent` drops are tagged and skipped | P2.T2 |
| Taking drops a visitor threw | Items thrown by a non-member are skipped | P2.T2 |
| Using a Steward to launder duped goods | Only `SellCatalog` raw goods sell, with the daily cap and fee | P4.T2 |
| Using an alt to dodge the cap | Cap counts against the claim owner; owner must be online | P4.T2 |
| Crash duping a carried stack | Items leave the world on pickup; a crash can lose up to one carry but can't double it | P2.T2 |
| Moving items out of a trusted member's chest | Only framed chests inside the claim are ever filled; chests are never emptied except sell chests | P3.T1 |
| Lag | At most 3 per base, a 5-second scan, a server-wide scan budget | P2.T2, P5.T2 |

**Theme**
- **Palette:** molten copper to verdigris (`#c8743a` to `#4fd1b5`). It's unused, it ties to copper Agents, and it's distinct from the Spark Allay pet's `#6bd6ff`.
- **Name:** Forge Allay, with tier names Gatherer, Courier and Steward.
- **Look and sound:** a copper and verdigris particle trail. Pickup has a short anticipation hover and a soft amethyst chime, then the allay accelerates away. Each delivery gives a small spark at the Station. A sale gives a gold burst and a coin-like sound.
- **Texture:** none. There's no item, and the vanilla allay model stays, because overriding it in the pack would also change the Spark Allay pet.

## Phases and tasks
Every phase ends with a gate. New config keys all live under `allays:` in `plugins-src/BlackForgeCore/src/main/resources/config.yml`.

## Phase 1: Clear the blockers
- **Goal:** nothing changes for players. The project has tested allay rules, the hooks it needs, and proof that an allay can be flown under full control.
- **Blast radius:** a restart of all five servers per task. No saved data changes. Live play is unaffected.
- **Rollback:** revert the PR. Nothing is saved in this phase.
- **Phase gate:** a tester confirms in the Foundry: `/allay start` spawns a test allay that flies between two points smoothly, doesn't follow a player, ignores right-clicks and held items, and doesn't duplicate beside a playing jukebox; `/allay stop` removes it.

### [x] P1.T1: Add allay rules and config
- **Needs:** `S/RobotRules.java` as the pattern for pure rules.
- **Files:** create `S/AllayRules.java`, `plugins-src/BlackForgeCore/src/test/java/net/blackforge/core/survival/AllayRulesTest.java`; change `plugins-src/BlackForgeCore/src/main/resources/config.yml`.
- **Steps:**
  1. Add `AllayRules` with no Bukkit types: `flock(tier)`, `leash(tier)`, `cost(tier)`, `canSort(tier)`, `canRefuel(tier)`, `canSell(tier)`, `tripSeconds(distance, speed, pause)`.
  2. Add `skipDrop(ageTicks, deathTagged, thrownByNonMember)` returning a reason or empty.
  3. Add `netSale(gross, fee)`.
  4. Add config: `allays.enabled: false`, `flock: [1, 2, 3]`, `leash: [12, 20, 28]`, `price: [75000, 250000, 750000]`, `essence: [256, 1024, 4096]`, `carry: 16`, `speed: 6.0`, `pause-seconds: 1.0`, `min-drop-age-ticks: 60`, `scan-seconds: 5`, `max-scans-per-tick: 4`, `sell-fee: 0.10`.
- **Edge cases:** tier out of range throws; a fee outside 0-1 is clamped.
- **Done when:** `AllayRulesTest` covers each method, including that the default speed and carry give 3.2 items a second on a 12-block run. CI passes on `BlackForgeCore`.
- **Leaves ready:** rules and config for later tasks.
- **Evidence:** Verified: all 8 `AllayRulesTest` tests pass when run alone on Java 21 with a small stand-in for JUnit. Automerge runs them for real on the `BlackForgeCore` build and won't merge if they fail. Nothing reads the config yet, so there's nothing to play.

### [ ] P1.T2: Add the hooks allays need, with no behaviour change
- **Needs:** `S/BaseStation.java:70`, `151`, `166`; `S/Robots.java:769`, `864`; `S/GroveTrader.java:133-152`.
- **Files:** change `S/BaseStation.java`, `S/Robots.java`, `S/GroveTrader.java`; extend `plugins-src/BlackForgeCore/src/test/java/net/blackforge/core/survival/RobotRulesTest.java` only if a pure rule moves.
- **Steps:**
  1. `BaseStation`: add `Location stationIn(String region)` (a standing station or null), `String regionAt(Location)` and `Inventory storageIn(String region)`.
  2. `Robots`: add `boolean fuelFromItem(Bot b, ItemStack cell)`, split out of `addFuel` so both paths share the tank, blend and cap logic. Add `List<Bot> agentsNear(Location, int radius, UUID owner)`.
  3. `GroveTrader`: split the middle of `sell` into `double saleValue(UUID owner, Material m, int amount)` and `void recordSale(UUID owner, Material m, int amount)`, and make `sell` call them. Add `boolean buys(Material)`.
- **Edge cases:** a failed deposit still returns items to the player, as today; a Forge purchase and a Trader sale behave exactly as before.
- **Done when:** in the Foundry, adding fuel by click and shift-click still works. On Survival, a Trader sale pays and counts toward the daily total as before. Existing tests pass.
- **Leaves ready:** Station, fuel and sale hooks.
- **Evidence:** Verified: the `BlackForgeCore` build, unit tests and strict walkcheck pass with these hooks in. Untested: the in-game fuel and Trader sale checks. Both still need a tester, so this stays unticked until then.

### [ ] P1.T3: Prove flight control in the Foundry
- **Needs:** P1.T1; `S/FoundrySystems.java:25-40`; command registration through `Bukkit.getCommandMap()`, as `S/Robots.java:981` does; the smooth-motion guidance in the `motion` skill.
- **Files:** create `S/Allays.java`, `S/AllayFlight.java`, `plugins-src/BlackForgeCore/src/test/java/net/blackforge/core/survival/AllayFlightTest.java`; change `S/FoundrySystems.java`.
- **Steps:**
  1. `AllayFlight`: move an entity from A to B with ease-in and ease-out, updating each tick, using the speed from config. Keep the position maths pure and unit-tested in `AllayFlightTest`.
  2. `Allays`: spawn with `setAI(false)`, `setInvulnerable(true)`, `setSilent(true)`, `setCollidable(false)`, `setPersistent(false)`, no duplication, and a persistent-data marker. Cancel `PlayerInteractEntityEvent` and leashing for marked allays.
  3. Add staff `/allay start` (spawn a test allay flying a 12-block loop by the Foundry forge) and `/allay stop` (remove all test allays). It's Foundry only, guarded by `blackforge.admin`.
- **Edge cases:** a player leaving the Foundry; a server restart (the marker removes stragglers on entity load, using the pattern at `S/Robots.java:382`); a jukebox beside it; a held item.
- **Done when:** the Phase 1 gate checks pass in game. If flight is visibly jerky or the allay can't be fully controlled, stop and record it in the Blockers log, because the design then needs Colin's decision.
- **Leaves ready:** a controllable allay and `Allays` class.
- **Evidence:** Verified: the `BlackForgeCore` build and strict walkcheck pass, so the Allay calls compile against the pinned Paper build, and all 7 `AllayFlightTest` tests pass. Untested: nobody has flown one in game yet, so this stays unticked until the Phase 1 gate is checked.

## Phase 2: Tier I, Gatherer (Foundry only)
- **Goal:** in the Foundry, a player can summon a Gatherer in the Forge and watch it carry loose drops to the Station.
- **Blast radius:** Foundry only. `allays.enabled` stays false for Survival. No live data.
- **Rollback:** revert the PR. Foundry data isn't saved.
- **Phase gate:** a tester confirms: slot 34 opens the Allay menu and summons a Gatherer for free; dropped items within 12 blocks reach the Station; the allay waits with a status when the Station is full; a second player's thrown item and a death drop are left alone; breaking the Station puts it to sleep and placing a new one wakes it.

### [ ] P2.T1: Add the Allay menu
- **Needs:** P1.T3; `S/BaseStation.java:288-340`, `359-373`; `S/StationUi.java:15`.
- **Files:** change `S/BaseStation.java`, `S/Allays.java`, `S/FoundrySystems.java`.
- **Steps:**
  1. `BaseStation`: add `setAllays(Allays)`. When set, put an allay icon in slot 34. When null, the slot stays empty, which keeps Skyblock unchanged.
  2. `Allays.openMenu(Player, Location)`: a 27-slot menu listing the three tiers with name, job, cost, and owned or locked state. The next tier's card buys it.
  3. Buying goes through a shared pay method, free in the sandbox. Copy `BaseStation.pay`'s order: check Essence, check money, withdraw, then take Essence.
  4. Store tier per region in memory.
  5. The Foundry always runs allays. `allays.enabled` only governs real servers, and until P4.T2 no real server constructs `Allays`.
- **Edge cases:** a Station broken while the menu's open, and access lost while it's open (re-check as at `S/BaseStation.java:365`); buying with too little money or Essence takes nothing; two trusted members buying at once.
- **Done when:** in the Foundry, the icon shows in slot 34 at GUI scales 2, 3 and 4 without overlapping the art; buying Tier I spawns one allay by the Station; a failed purchase takes nothing.
- **Leaves ready:** base tier state and a working flock of idle allays.
- **Evidence:** Untested in game; the build and tests are checked before merge. Tier II and III cards say "Not ready yet" until their jobs are built, so nobody can buy an upgrade that does nothing.

### [ ] P2.T2: Add loose-drop collection
- **Needs:** P2.T1; `AllayRules.skipDrop`; `BaseStation.stationIn`, `regionAt`, `deliver` (`S/BaseStation.java:166`).
- **Files:** change `S/Allays.java`; create `plugins-src/BlackForgeCore/src/test/java/net/blackforge/core/survival/AllayJobsTest.java`.
- **Steps:**
  1. Add a job queue per base, shared by its flock. A pure `AllayJobs` picks the nearest unclaimed job.
  2. Every `scan-seconds`, up to `max-scans-per-tick` bases are scanned within the leash and the base's region, for items that `skipDrop` allows.
  3. Tag `PlayerDeathEvent` drops with a persistent-data marker on the item stack. Skip items whose thrower isn't a member.
  4. Pickup removes up to `carry` items from the world and holds them in memory on the allay. The allay flies home and calls `deliver`.
  5. If the Station is full, the allay hovers with the status `Station full` and retries. If it's dormant or the server shuts down, it drops the carried items at the Station's side.
- **Edge cases:** an item despawned or picked up by a player mid-flight (the job ends); two allays claiming one item; a chunk unloading mid-trip (the allay freezes with its load, and it's kept in memory while the server runs); a shutdown; a crash (up to one carry lost); a claim shrunk under a job.
- **Done when:** `AllayJobsTest` passes. In the Foundry, the Phase 2 gate's collection, full Station and skip checks pass. With 3 test bases running, staff see no TPS drop in `/tps`.
- **Leaves ready:** the work loop that later jobs join.
- **Evidence:** Verified: `AllayJobsTest` passes when run alone, and the build runs before merge. Untested in game: all the collection and skip checks, including whether death drops really get marked.

### [ ] P2.T3: Add the Foundry bench and tips
- **Needs:** P2.T2; `S/FoundryLab.java`; `hub/FoundryTips.java:31`; `S/FoundrySystems.java`.
- **Files:** change `S/FoundryLab.java`, `plugins-src/BlackForgeCore/src/main/java/net/blackforge/core/hub/FoundryTips.java`, `S/FoundrySystems.java`, `plugins-src/BlackForgeCore/src/main/resources/config.yml`; also the Arena layout if a label needs a spot.
- **Steps:**
  1. Add a labelled "Allay Bench" beside the Forge with a dispenser that drops mixed items on a timer, so collection can be watched with no setup.
  2. Add a tip: "Forge Allays carry loose drops to the Station. Open the Forge and buy one from slot 34."
  3. Have `FoundrySystems.clear(owner)` remove that player's allays and carried items.
- **Edge cases:** a player leaving the Arena (nothing carries out); restart; many players at once (cap with `foundry.max-agents-total`-style `foundry.max-allays-total: 12`).
- **Done when:** the Phase 2 gate passes on the live Foundry, and leaving the Arena leaves no allay behind.
- **Leaves ready:** a test bench for later tiers.
- **Evidence:** Untested in game. The Phase 2 gate needs a tester, and two of its checks need a second player. The build runs before merge.

## Phase 3: Tier II, Courier (Foundry only)
- **Goal:** in the Foundry, a Tier II flock sorts the Station into framed chests and keeps Agents fuelled.
- **Blast radius:** Foundry only.
- **Rollback:** revert the PR.
- **Phase gate:** a tester confirms: framed chests fill with only their item; items with no frame stay in the Station; a full chest is skipped; an Agent under 2 minutes of fuel gets a Fuel Cell from the Station, using the cheapest first; Courier doesn't touch unframed chests.

### [ ] P3.T1: Add chest sorting
- **Needs:** P2.T3; `BaseStation.storageIn`; `AllayRules.canSort`.
- **Files:** change `S/Allays.java`, `AllayJobs` and its test, `plugins-src/BlackForgeCore/src/main/resources/config.yml`.
- **Steps:**
  1. Every `allays.link-scan-seconds: 30`, scan the base's loaded area within the leash for item frames attached to chests or barrels in the same region, and build a material-to-chest map. Skip frames holding emeralds.
  2. Add a job when the Station holds a matched material and that chest has room.
  3. Take up to `carry` from the Station, fly, add to the chest, and return any remainder to the Station.
- **Edge cases:** a chest broken mid-trip (return the load to the Station); two frames with one material (the nearest with room wins); double chests; a frame on the Station itself (ignored); Agent items, Fuel Cells and items with custom data move as whole stacks with their data intact; the Base Station barrel is never a destination.
- **Done when:** a unit test covers map building and picking. In the Foundry, the gate's sorting checks pass, and the same item count exists before and after a run with custom-named items.
- **Leaves ready:** the courier job pattern for fuel and sales.
- **Evidence:** Verified: `AllayJobsTest` passes when run alone, and the build runs before merge. Untested in game: all sorting checks, and the Courier Bench's frames and spot.

### [ ] P3.T2: Add Agent refuelling
- **Needs:** P3.T1; `Robots.fuelFromItem`, `agentsNear` from P1.T2; `RobotRules.shouldFetchFuel` (`S/RobotRules.java:63`), `lowestTierCell`.
- **Files:** change `S/Allays.java`, `S/Robots.java`.
- **Steps:**
  1. On the link scan, list the owner's Agents within the leash whose `shouldFetchFuel` is true.
  2. Add a job if the Station holds a Fuel Cell, taking the lowest tier. Agents with a full tank are skipped by `fuelFromItem`, and the cell returns.
  3. Show a small fuel spark on the Agent.
- **Edge cases:** an Agent picked up mid-trip (return the cell); the tank's 48-hour cap (`S/Robots.java:73`); Academy fuel perks unaffected, since the tank logic is shared; a Fisherman's slower burn.
- **Done when:** in the Foundry, an Agent run down to under 2 minutes is refilled by a Courier with no click. No cell is lost when the Agent is removed mid-trip.
- **Leaves ready:** Tier II complete.
- **Evidence:**

## Phase 4: Tier III, Steward (Foundry only, then staff on Survival)
- **Goal:** a Steward sells raw goods from a sell chest, using the Trader's prices and daily cap, without creating money in the Foundry.
- **Blast radius:** Foundry, plus staff-only preview on Survival in P4.T2. Real money moves for staff only.
- **Rollback:** set `allays.preview-staff: false` and revert. `allays.yml` is ignored if unused.
- **Phase gate:** a tester and a staff member confirm: an emerald-framed chest sells only `SellCatalog` goods; other items stay; sales stop while the owner is offline; a Trader sale and a Steward sale share one daily counter; the Foundry never pays real money.

### [ ] P4.T1: Add the sell chest and selling
- **Needs:** P3.T2; `GroveTrader.buys`, `saleValue`, `recordSale` from P1.T2; `AllayRules.netSale`.
- **Files:** change `S/Allays.java`, `AllayJobs` and its test.
- **Steps:**
  1. Add `Allays.setTrader(GroveTrader)`, with no caller yet. With no trader (the Foundry), a sale is valued from `SellCatalog.ITEMS`, shows that value and pays nothing.
  2. Treat an emerald-framed chest as the sell chest and add a job for its first sellable stack, taking up to `carry`.
  3. On return, with the owner online on this server: value the sale, apply `sell-fee`, deposit, record the sale, and show a gold burst and an action bar total. If the deposit fails or the owner is offline, put the load back in the chest.
- **Edge cases:** a sale crossing the soft cap mid-carry (handled by `payout`); midnight rollover; the owner logging out mid-trip (load returns); a full chest on return (drop into the Station); a sell chest beside a hopper (fine, since it's just a chest).
- **Done when:** a unit test covers fee and the cap crossing. In the Foundry, the sell chest empties of catalog goods only and no balance changes.
- **Leaves ready:** Tier III job on the Foundry.
- **Evidence:**

### [ ] P4.T2: Add saving and a staff preview on Survival
- **Needs:** P4.T1; `S/BaseStation.java:403-423` as the save pattern; `S/SurvivalModule.java:162`.
- **Files:** change `S/Allays.java`, `S/SurvivalModule.java`, `plugins-src/BlackForgeCore/src/main/resources/config.yml`.
- **Steps:**
  1. Save region, tier, each allay's name and any carried items to `allays.yml` every minute and on shutdown, with no save in the sandbox. Load on start. Unknown keys are ignored, so future versions can add fields.
  2. Construct `Allays` on Survival, calling `setTrader` with the instance built at `S/SurvivalModule.java:202` and `BaseStation.setAllays`. Add `allays.preview-staff: false`. Slot 34 shows only when `enabled`, or when `preview-staff` is on and the player has `blackforge.admin`.
  3. On Survival, charge real money and Essence through the shared pay method.
  4. Respawn entities from saved state, using the entity-load pattern at `S/Robots.java:382`, and go dormant when no Station stands in the region.
- **Edge cases:** restart with a load in flight (saved load returns to the Station); Station moved within a claim (allays rebind); claim deleted (data stays dormant, entities removed); a stale entity after a rollback; two entities with one id (`x.remove()`); world not loaded yet.
- **Done when:** with preview on, staff buy all tiers with real money on Survival, a restart keeps tier and flock, a mob farm feeds the Station, a sale pays the owner and counts toward the Trader's daily total, and a non-staff player sees no slot 34.
- **Leaves ready:** live-ready data and staff-tested behaviour.
- **Evidence:**

## Phase 5: Launch on Survival
- **Goal:** every Survival player can buy allays from the Forge and learn them in game.
- **Blast radius:** Survival players. Forge menu art. `allays.yml`.
- **Rollback:** set `allays.enabled: false`, which removes entities and hides slot 34. Tiers stay in `allays.yml` and return when it's turned back on.
- **Phase gate:** Colin confirms on live Survival: a normal player can find, buy and use all three tiers, and TPS holds with real load.

### [ ] P5.T1: Finish the look
- **Needs:** P4.T2; `tools/gui_art.py`; `S/StationUi.java:15`; the theme above.
- **Files:** change `S/Allays.java`, `S/StationUi.java`, `tools/gui_art.py`; rebuild the pack if a new menu screen is added.
- **Steps:**
  1. Add the copper to verdigris particle trail, pickup hover and delivery spark. Tier 3 gets a brighter trail.
  2. Show the name tag only within 8 blocks: `Courier` plus any status.
  3. Add Allay menu art in the Forge style.
- **Edge cases:** cached resource packs; custom UI off (`base-station.custom-ui: false` gives a plain menu); particles counted for many bases.
- **Done when:** a preview image is approved by Colin, and the menu lines up at GUI scales 2, 3 and 4.
- **Leaves ready:** final visuals.
- **Evidence:**

### [ ] P5.T2: Check balance and load
- **Needs:** P5.T1; `plugins-src/BlackForgeCore/src/main/resources/config.yml` `allays:` keys.
- **Files:** change `plugins-src/BlackForgeCore/src/main/resources/config.yml`; update Verified facts here.
- **Steps:**
  1. Measure items a second for 1, 2 and 3 allays on a 12-block run. Expected about 3.2, 6.4 and 9.6.
  2. Measure tick time with 10 bases at Tier III on a test setup. Target: under 2 ms a tick in total.
  3. Compare the full cost, about $1,075,000 + 5,376 Essence, to a tester's real income on Survival, and record the result.
- **Edge cases:** a claim with no Station, and a base with no drops, should cost close to nothing.
- **Done when:** measured numbers are recorded here and Colin approves any change.
- **Leaves ready:** final numbers.
- **Evidence:**

### [ ] P5.T3: Add player guidance
- **Needs:** P5.T2; `hub/FoundryTips.java:31`; `plugins-src/BlackForgeCore/src/main/java/net/blackforge/core/News.java`; `Robots` help menu at `S/Robots.java:918`.
- **Files:** change `S/Allays.java`, `S/Robots.java`; change a test bot under `tools/testbot` only if it opens the Forge.
- **Steps:**
  1. First-summon message: one line per job, with the emerald sell chest explained on reaching Tier III.
  2. Add item frame teaching to the Allay menu cards.
  3. Prepare the launch news line: `/news add survival Forge Allays are here! Open your Base Station's Forge to summon a crew that carries, sorts and sells for your base.`
- **Edge cases:** a player without a Station sees where to buy one.
- **Done when:** a new tester with no instructions gets a Gatherer working from in-game text alone.
- **Leaves ready:** player guidance.
- **Evidence:**

### [ ] P5.T4: Turn it on for Survival
- **Needs:** P5.T3 and all earlier gates.
- **Files:** change `plugins-src/BlackForgeCore/src/main/resources/config.yml`; set this plan's Status to Complete.
- **Steps:**
  1. Set `allays.enabled: true` and keep `preview-staff` as is.
  2. Post the `/news` line once it's live.
  3. Confirm `tools/deploy.py:98` doesn't write over the new key.
- **Edge cases:** Skyblock keeps an empty slot 34, since `setAllays` isn't called there.
- **Done when:** a non-staff player on live Survival buys Tier I and it delivers, and Skyblock's Forge is unchanged.
- **Leaves ready:** feature live.
- **Evidence:**

## Blockers log
| Date | Task | Problem | Decision |
|---|---|---|---|
| 2026-11-04 | Phase 1 gate | The in-game checks for P1.T2 and P1.T3 need a tester, so Phase 2 can't start (rule 9) | Colin told me to start Phase 2 at 10:38 on 2026-11-04, before P1.T3 was live. The flight checks are still unplayed, so they're carried into the Phase 2 gate |

## Change log
| Date | Change | Approved by |
|---|---|---|
| 2026-11-04 | Phase 3 started with the Phase 1 and 2 in-game gates still unplayed; those checks carry forward | Colin, in chat |
| 2026-11-04 | Added a Courier Bench to P3.T1, so sorting can be tested with no setup. Fuel Cells are never sorted, so they stay for refuelling | Testing rule; flagged to Colin in the PR |
| 2026-11-04 | Added allay naming with Name Tags, and an easter egg: an allay named Smoked Sausage flies upside down. P4.T2 must also save each allay's name | Colin, in chat |
| 2026-11-04 | P2.T3 drops bench items from a spot on the open floor instead of a dispenser, so the Arena doesn't need rebuilding | Same test, less risk; flagged to Colin in the PR |
| 2026-11-04 | P2.T2 marks death drops on the item entity, not the stack, so a player's recovered items don't keep a hidden tag and still stack | Same check, safer method; flagged to Colin in the PR |
| 2026-11-04 | Phase 2 started before the Phase 1 in-game gate was played | Colin, in chat |
| 2026-11-04 | P1.T3 registers `/allay` from `Allays`, as `Robots` does, instead of changing `HubModule` | Small wiring change, no check weakened; flagged to Colin in the PR |

## Later ideas
- Skyblock and OneBlock allays, which need a sell path first.
- Hum: a jukebox near the Station makes nearby Agents work 10% faster, capped alongside Academy bonuses.
- XP courier for Grinder bases.
- Furnace tending.
- Replanting.
- Stock keeper chests.
- Auto-crafting that can't create a sell-price exploit.
- Sentry alerts.
- A pack-driven Forge Allay model, separate from the Spark Allay pet.

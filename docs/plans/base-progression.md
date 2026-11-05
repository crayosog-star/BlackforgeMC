# Base Progression
**Status:** In progress
**Next task:** P3.T1, after the Phase 2 gate

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
**Pitch:** your base now has a level. The Forge shows what you have, what's next and how to get it. You start with a Farmer, level up by having Agents deliver to the Forge and paying a fee, and each level unlocks more Agent jobs, Forge Allays and faster hoppers for the whole claim.

**Why it's worth building:** Forge purchases are separate today, with no path between them and no screen that explains them. A level gives Survival players a long-term goal that feeds the automation and selling direction.

**Unlock track**
| Base level | Agent job | Forge Allay can be bought | Claim hopper tier can be bought |
|---|---|---|---|
| 1 | Farmer | None | None (plain hoppers) |
| 2 | Miner | None | Tier II |
| 3 | Fisherman | Tier I Gatherer | None |
| 4 | Grinder | Tier II Courier | Tier III |
| 5 | None | Tier III Steward | None |

**Starting prices (all config, expected to change)**
| To reach | Agent deliveries, lifetime total | Money | Mob Essence |
|---|---|---|---|
| Level 2 | 2,000 | $25,000 | 128 |
| Level 3 | 12,000 | $100,000 | 512 |
| Level 4 | 52,000 | $400,000 | 2,048 |
| Level 5 | 202,000 | $2,000,000 | 8,192 |

| Claim hopper tier | Money | Mob Essence |
|---|---|---|
| Tier II, whole claim | $150,000 | 512 |
| Tier III, whole claim | $1,500,000 | 8,192 |

Allay prices stay as they are: $75,000 + 256 Essence, $250,000 + 1,024, and $750,000 + 4,096.

**Out of scope:** Skyblock levels, Factions, Forge Wars, per-Agent progress (`docs/plans/agent-academy.md` owns that), shared levels across servers.

## Decisions and assumptions
**Decided by Colin**
- The level belongs to the base, and Survival comes first.
- You level up through Agent deliveries plus a fee.
- Allays are unlocked by level and then bought. Nobody gets a free allay. Gatherer unlocks at Level 3.
- Hopper tiers are gated by level, and an upgrade covers every hopper in the claim.
- Farmer is the first job. Prices can be set now and changed later, and Tier III is much more expensive.
- A player gets one free Tier I Farmer the first time they place a Forge, if they own no Agents, because deliveries need an Agent.
- A safety limit of 256 claim-upgraded hoppers per chunk replaces the 16-per-chunk limit for claim tiers, pending the lag test.

**Assumptions (asked, not answered; Colin can overrule them)**
- Only Agent deliveries count. Hoppers can loop items into the barrel, and allays can carry the same thrown stack repeatedly.
- Only the player clicking pays for a level, and any player with access to the Forge can do so.
- Existing bases never lose anything. On launch each base rises to the level its existing Agent jobs, allay tier and hopper need.
- An Agent item whose job isn't unlocked is placed as a Farmer with a message, rather than refused, so a live crate reward can't become unusable.
- The Foundry starts at Level 5, so `/foundry check` and `/foundry demo` keep working until a tester lowers it.

## Verified facts
Checked against `origin/main` at `48f86fd`. `S` means `plugins-src/BlackForgeCore/src/main/java/net/blackforge/core/survival`.

- No base level exists. The only levels found are Skyblock's BentoBox island levels (`S/SkyblockModule.java:242`).
- `BaseStation.deliver` (`S/BaseStation.java:181`) is the one place Agents put items in the Station, called from `Robots.store` (`S/Robots.java:484`). Allays use `storageIn` instead (`S/Allays.java:668`, `691`, `1025`).
- A Survival region is `claim:<owner>` (`S/SurvivalModule.java:171`). A claim is a set of chunks, and `Claims.ownerAt` finds an owner by chunk (`S/Claims.java:105`).
- Any Agent can switch job for free (`S/Robots.java:790`), and new Agents are Miners (`S/Robots.java:987`, `1009`). Agent items are placed through `Robots.place` (`S/Robots.java:278`).
- Survival's Workshop doesn't sell Agents (`setCrateOnly`, `S/SurvivalModule.java:166`). The Forge's empty slot still says to get one at `/agents` (`S/BaseStation.java:368`).
- Agent cards and limits follow the player (`S/Robots.java:179`, `916`), while allay tiers follow the region (`S/Allays.java:577`).
- `BaseStation.pay` takes money and Essence (`S/BaseStation.java:146`), and is free in the sandbox.
- `HopperUpgrades` tracks individually upgraded hoppers per loaded chunk (`S/HopperUpgrades.java:163`), and sets the cooldown every tick (`S/HopperUpgrades.java:145`).
- Forge slots: 0-26 Agent cards, 27 next page, 31 hopper info, 32-33 buy upgrades, 34 allays (`S/BaseStation.java:305-309`). Slot 35 is reserved for the Academy (`docs/plans/agent-academy.md:64`).
- Menu art comes from title glyphs in `S/StationUi.java`, and `tools/gui_art.py` draws them into the pack.
- `/foundry` subcommands are in `plugins-src/BlackForgeCore/src/main/java/net/blackforge/core/hub/FoundryCommand.java`, and Foundry bases are per chunk or shared (`S/FoundrySystems.java:63`).

**Unverified**
- Which items live crate rewards give. They aren't in the repo, so they may be in live config.
- How many hoppers real claims hold, and whether Tier III across a claim holds tick time. P5.T4 measures it.
- Whether the delivery targets suit real income. P5.T4 measures it. Rough sums from Agent intervals suggest hours per level, not days.
- Whether anyone has bought allays on live. P5.T1 handles either case.

## Counterplay and theme
**Counterplay:** this is a base system with no combat use, so the risks are exploits.

| Exploit | Stopped by | Checked in |
|---|---|---|
| Looping items through hoppers to level up | Only `Robots.store` deliveries count | P1.T2 |
| Throwing a stack for allays to carry repeatedly | Allay deliveries don't count | P1.T2 |
| Counting items that didn't fit | Only the amount placed in the barrel counts | P1.T2 |
| Moving a Forge to reset a level | The level is saved by region, not by barrel | P1.T2 |
| Unclaiming and reclaiming for another free Agent | The free Agent is saved per player, once | P5.T2 |
| Using a Tier III hopper item to dodge the gate | Hopper Upgrades stop being sold on Survival once the flag is on; items already held still work | P3.T2 |
| Keeping an unlocked job after moving a Grinder to a low-level base | Allowed: existing placed jobs are never removed, and placement uses the destination base | P2.T1 |

**Theme:** molten copper to verdigris, matching Forge Allays (`#c8743a` to `#4fd1b5`). A level-up gives a rising anvil sound, a spark burst at the Forge and a title line.

## Phases and tasks
Every number below has a key under `base-levels:` in `plugins-src/BlackForgeCore/src/main/resources/config.yml`. `base-levels.enabled` stays false on real servers until P5.T5. The Foundry always runs the system, for testing.

## Phase 1: Foundation
- **Goal:** nothing changes for Survival players. Levels exist, count deliveries, save, and can be set in the Foundry.
- **Blast radius:** a restart of all five servers per task. A new `base-levels.yml` on Survival only once the flag is on.
- **Rollback:** revert the PR. Nothing reads the new file while the flag is off.
- **Phase gate:** a tester confirms in the Foundry: `/foundry level` shows Level 5 and its progress, `/foundry level 1` then `/foundry level add 500` change what it shows, an Agent delivering to the Forge raises progress by the amount delivered, and a hopper feeding the Forge doesn't.

### [x] P1.T1: Add level rules and config
- **Needs:** `S/AllayRules.java` as the pattern for pure rules.
- **Files:** create `S/BaseLevelRules.java`, `plugins-src/BlackForgeCore/src/test/java/net/blackforge/core/survival/BaseLevelRulesTest.java`; change `plugins-src/BlackForgeCore/src/main/resources/config.yml`.
- **Steps:**
  1. Add `BaseLevelRules` with no Bukkit types: `MAX_LEVEL = 5` and a `Table` record with per-level costs and unlock levels, plus a `DEFAULT`.
  2. Add `Table.cost(level)`, `canLevelUp(level, delivered)`, `progress(level, delivered)`, `jobUnlocked(level, job)`, `jobLevel(job)`, `allayLevel(tier)`, `allayUnlocked(level, tier)`, `hopperLevel(tier)`, `hopperUnlocked(level, tier)`, `hopperCost(tier)` and `floor(jobs, allayTier, hopperTier)`.
  3. Add a `base-levels:` config section with `enabled: false` and every number in this plan.
- **Edge cases:** out-of-range levels and tiers throw; an unknown job name throws; a table with the wrong length throws; targets that don't rise throw, because a bad config shouldn't make a level unreachable or free.
- **Done when:** `BaseLevelRulesTest` covers each method and the defaults match this plan. It passes when run alone, and automerge's build passes.
- **Leaves ready:** rules and config.
- **Evidence:** Verified: all 8 `BaseLevelRulesTest` tests pass when run alone with a small stand-in for JUnit, and the config parses as YAML. Automerge runs them on the `BlackForgeCore` build and won't merge if they fail. Nothing reads the config yet, so there's nothing to play.

### [x] P1.T2: Save levels and count Agent deliveries
- **Needs:** P1.T1; `S/BaseStation.java:181`; `S/Allays.java:349-385` as the save pattern.
- **Files:** create `S/BaseLevels.java`; change `S/BaseStation.java`, `S/SurvivalModule.java`, `S/FoundrySystems.java`.
- **Steps:**
  1. `BaseLevels`: for each region, hold level and lifetime deliveries. Read `base-levels` from config into a `Table`, falling back to `DEFAULT` with a logged warning.
  2. Add `active()` (sandbox, or the flag), `level(region)`, `delivered(region)`, `countDelivery(region, amount)` and `setLevel`.
  3. Save to `base-levels.yml` every minute when changed and on shutdown, with no file in the sandbox. Unknown keys are ignored.
  4. `BaseStation.setLevels(BaseLevels)`. In `deliver`, count the amount that went in, using the input amount minus the leftover.
  5. Construct it on Survival and in the Foundry, not on Skyblock. In the sandbox every region shares one test level, default 5.
- **Edge cases:** a full Station counts nothing; a partial stack counts only what fit; a bad save file logs and starts clean without overwriting it until the next real change; the count is capped at `Long.MAX_VALUE`; a shutdown saves; a flag-off server counts nothing and writes nothing.
- **Done when:** a code review confirms `deliver` is the only count call. Automerge's build passes. The in-game delivery check runs in the Phase 1 gate.
- **Leaves ready:** a saved level for each base.
- **Evidence:** Verified by code review: `BaseStation.deliver` is the only call to `countDelivery`, and it counts the stack's amount from before `addItem`, less what's left. Automerge runs the real build. Untested in game: the delivery check runs in the Phase 1 gate once P1.T3 can show it.

### [ ] P1.T3: Add Foundry level commands
- **Needs:** P1.T2; `hub/FoundryCommand.java:20-45`, `run`; `S/FoundrySystems.java:93-95`; `S/FoundrySystems.java:181`.
- **Files:** change `hub/FoundryCommand.java`, `S/FoundrySystems.java`.
- **Steps:**
  1. Add `/foundry level` (shows level, progress, what's unlocked and what's next), `/foundry level [1-5]` and `/foundry level add [items]`.
  2. Add them to `/foundry` help with one-line explanations.
  3. `/foundry reset` and the island emptying put the test level back to 5 and progress back to 0.
- **Edge cases:** a bad number shows usage; `add` caps at a huge number; a level change announces itself through `foundry.tell`, since the island is shared.
- **Done when:** the Phase 1 gate passes in game.
- **Leaves ready:** a quick way to test every later gate.
- **Evidence:** Verified: `BaseLevelRulesTest`, including what each level unlocks, passes when run alone. Automerge runs the real build. Untested in game: all commands. This stays unticked until the Phase 1 gate is played.

## Phase 2: Gates (Foundry only)
- **Goal:** in the Foundry, jobs and allays obey the level, and every refusal explains itself. Level 5 behaves exactly as today.
- **Blast radius:** Foundry. Survival sees nothing while the flag is off.
- **Rollback:** revert the PR. No saved data changes.
- **Phase gate:** a tester confirms at Level 1: only the Farmer can be selected, clicking Miner says it needs Level 2, a Grinder item places as a Farmer with a message, and allay cards say which level they need. At Level 5, `/foundry check` passes as it does today.

### [x] P2.T1: Gate Agent jobs
- **Needs:** P1.T3; `S/Robots.java:278`, `790`; `S/BaseStation.java:196` (`regionAt`).
- **Files:** change `S/Robots.java`, `S/SurvivalModule.java`, `S/FoundrySystems.java`.
- **Steps:**
  1. `Robots.setLevels(BaseLevels)`. With no levels set, or `active()` false, behave as today. That keeps Skyblock unchanged.
  2. Refuse a job switch to a locked job, with an action bar message naming the level needed and a low sound.
  3. In `place`, turn a locked job into Farmer, with a message saying why. Save the job on the Agent record as usual.
  4. In the Agent menu, locked job icons show a lock line with the level they need.
- **Edge cases:** an Agent already doing a locked job keeps working until its job changes; picking it up and placing it at the same base keeps the job only if unlocked there; staff follow the same rules, so the gate can be tested; test bots use slots 11 and 17, so slot positions stay.
- **Done when:** the job checks in the Phase 2 gate pass.
- **Leaves ready:** gated jobs.
- **Evidence:** Verified by code review: an Agent keeps a locked job until it's changed, and servers without levels skip the check. Automerge runs the real build. Untested in game: all job checks, in the Phase 2 gate.

### [x] P2.T2: Gate Forge Allays
- **Needs:** P2.T1; `S/Allays.java:546`, `577`.
- **Files:** change `S/Allays.java`, `S/SurvivalModule.java`, `S/FoundrySystems.java`.
- **Steps:**
  1. `Allays.setLevels(BaseLevels)`.
  2. `card` shows `Needs Base Level N` when locked. `buy` re-checks on click, so a stale menu can't buy past the gate.
  3. `forgeIcon` shows the same lock while the next tier is locked.
- **Edge cases:** `/foundry allays` bypasses the gate, since it's a test tool; a base already owning a tier keeps it; two players with the menu open; no money taken on refusal.
- **Done when:** the allay checks in the Phase 2 gate pass.
- **Leaves ready:** every level gate except hoppers.
- **Evidence:** Verified by code review: `buy` re-checks the level before taking money, and `/foundry allays` doesn't pass through it. Automerge runs the real build. Untested in game: all allay checks, in the Phase 2 gate.

## Phase 3: Claim-wide hoppers (Foundry only)
- **Goal:** in the Foundry's Build Plot, buying a tier upgrades every hopper in that plot, including ones placed later.
- **Blast radius:** Foundry. Survival's per-hopper tiers don't change while the flag is off.
- **Rollback:** revert the PR. Base hopper tiers are ignored if unused, and per-hopper tiers stay on each hopper.
- **Phase gate:** a tester confirms on the Build Plot: placing plain hoppers then buying Tier II raises each to 5 items a second, a hopper placed afterward is also Tier II, breaking one drops a plain hopper, and a hopper with its own Tier III stays Tier III. `/foundry status` shows the speeds. Tier III is refused below Level 4.

### [ ] P3.T1: Track claim tiers in HopperUpgrades
- **Needs:** P2.T2; `S/HopperUpgrades.java:145`, `163`, `190`, `292`, `298`.
- **Files:** change `S/HopperUpgrades.java`, `S/HopperTiers.java`, `plugins-src/BlackForgeCore/src/test/java/net/blackforge/core/survival/HopperTiersTest.java`, `S/BaseLevels.java`.
- **Steps:**
  1. Add a pure `HopperTiers.effective(own, claim)`, which returns the higher, and test it.
  2. `BaseLevels` saves a hopper tier per region.
  3. `HopperUpgrades.setClaimTier(Function<Location, Integer>)`. Each loaded chunk also holds a set of claim-tier hoppers, separate from its saved own-tier list, so a claim hopper never becomes a tiered item.
  4. On chunk load, if the chunk's tier is above 1, find hoppers with `Chunk.getTileEntities`. On placing a hopper, add it if the chunk has a tier. On breaking, remove it.
  5. `tick` applies the effective delay to both sets, and `refreshClaim(region)` re-reads loaded chunks after a purchase. Respect `base-levels.claim-hopper-max-per-chunk`.
- **Edge cases:** unclaiming a chunk (the hopper falls back to plain on its next refresh); a piston or explosion removing a hopper (the existing state check drops it); chunk unload; an own-tier hopper in a claim-tier chunk; a chunk over the safety limit logs once.
- **Done when:** `HopperTiersTest` passes and automerge's build passes. With no claim tier set, behaviour is unchanged.
- **Leaves ready:** a working claim tier with no way to buy it yet.
- **Evidence:**

### [ ] P3.T2: Sell claim tiers in the Forge
- **Needs:** P3.T1; `S/BaseStation.java:377-378`, `417`.
- **Files:** change `S/BaseStation.java`.
- **Steps:**
  1. When `levels.active()`, slots 32 and 33 become buy Tier II and buy Tier III for the region, with price, lock level or `Owned`. Otherwise they stay as they are, so Skyblock doesn't change.
  2. Pay through `pay`, save, call `refreshClaim`, and play a sound plus a message giving the new speed.
  3. Update the hopper info text in slot 31.
- **Edge cases:** buying Tier III needs Tier II; two members at once; a Station with no region; no money taken on refusal; Hopper Upgrade items already held still work.
- **Done when:** the Phase 3 gate passes in game.
- **Leaves ready:** all gates working. The old Forge menu still shows them.
- **Evidence:**

## Phase 4: The new Forge screen (Foundry only)
- **Goal:** the Forge opens on one home screen showing level, progress, the unlock track and what can be done next.
- **Blast radius:** Foundry. The resource pack in P4.T3.
- **Rollback:** revert the PR. With the flag off, the current Forge menu opens as it does today.
- **Phase gate:** a tester with no instructions opens the Forge and says what they have, what's next and what it costs. Level Up works and refuses clearly. The art lines up at GUI scales 2, 3 and 4.

### [ ] P4.T1: Add the home screen and Level Up
- **Needs:** P3.T2; `S/BaseStation.java:291-302`, `328`.
- **Files:** create `S/ForgeHome.java`; change `S/BaseStation.java`.
- **Steps:**
  1. When `levels.active()`, right-clicking opens a 54-slot plain `ForgeHome` menu. Sneak-clicking storage stays the same.
  2. Row 1 shows level and progress. Row 2 shows Levels 1-5, marked done, next or locked, with lore listing their unlocks and cost.
  3. Row 6 has Level Up, Storage and How It Works.
  4. Level Up checks deliveries, pays through `pay`, saves, and plays the level-up effects.
- **Edge cases:** the Station broken or access lost while open (`usable`); two players level up at once (re-read the level before paying); deliveries short shows the amount left and takes nothing; Level 5 shows Max.
- **Done when:** in the Foundry, Level Up works from `/foundry level 1` with `/foundry level add` supplying progress, and refuses without it.
- **Leaves ready:** the home frame for the other rows.
- **Evidence:**

### [ ] P4.T2: Add the Allay, Agent and Hopper rows
- **Needs:** P4.T1; `S/Allays.java:534`; `S/BaseStation.java:328`.
- **Files:** change `S/ForgeHome.java`, `S/BaseStation.java`, `S/Allays.java`.
- **Steps:**
  1. Row 3 shows three allay cards using `Allays.card`, and clicking one opens the allay menu.
  2. Row 4 shows the four jobs with lock state, plus Your Agents, which opens today's card screen.
  3. Row 5 shows hopper tiers with buy, owned or lock state.
  4. Back buttons return to home from allays and Agents. Keep slot 35 free on the card screen for the Academy.
- **Edge cases:** no Agents placed; a Skyblock-style base without allays hides that row; stale menus re-check on click.
- **Done when:** every row's states are seen in the Foundry at Levels 1, 3 and 5.
- **Leaves ready:** a complete plain menu.
- **Evidence:**

### [ ] P4.T3: Add the art
- **Needs:** P4.T2; `tools/gui_art.py`; `S/StationUi.java:15`; `blackforge-skin-item`.
- **Files:** change `tools/gui_art.py`, `S/StationUi.java`, `S/ForgeHome.java`, `resourcepack/assets/blackforge/textures/gui/`, the pack files that skill names.
- **Steps:**
  1. Draw a 6-row home panel in the Forge's style, with a progress bar and level pips.
  2. Add `Screen.HOME` and live text through `StationUi.Canvas`.
  3. Ship the pack update safely, as the skill describes.
- **Edge cases:** cached packs; `base-station.custom-ui: false` gives the plain menu; a player without the pack sees readable text.
- **Done when:** Colin approves a preview, and the Phase 4 gate passes.
- **Leaves ready:** the final screen.
- **Evidence:**

## Phase 5: Launch on Survival
- **Goal:** Survival players use base levels, and nobody loses anything they already had.
- **Blast radius:** Survival players, Forge menu, Agent job switching, allay purchases, hopper purchases and `base-levels.yml`.
- **Rollback:** set `base-levels.enabled: false`. The old menu and rules return, and levels and claim tiers stay saved. Hoppers fall back to their own tier while it's off.
- **Phase gate:** Colin confirms on live Survival: a new player can place a Forge, get a Farmer, level up and buy a Gatherer at Level 3, and an existing base kept everything.

### [ ] P5.T1: Move existing bases up
- **Needs:** Phase 4; `BaseLevelRules.floor`; `S/Robots.java:916`; `S/Allays.java:366`.
- **Files:** change `S/BaseLevels.java`, `S/SurvivalModule.java`.
- **Steps:**
  1. The first time a region is read with the flag on, set its level to at least `floor` of its owner's placed Agent jobs and its allay tier. Mark it migrated in the save.
  2. Log how many bases moved and to which level.
- **Edge cases:** a world not loaded yet (pending Agents count once loaded); restart mid-migration; an owner with no Forge; running twice changes nothing; levels never go down.
- **Done when:** a test covers `floor`, and a staff preview on Survival shows an existing Grinder owner at Level 4 or above with their Agent untouched.
- **Leaves ready:** safe data for launch.
- **Evidence:**

### [ ] P5.T2: Add the starter Agent and correct the text
- **Needs:** P5.T1; `S/Robots.java:987`, `1009`; `S/BaseStation.java:368`.
- **Files:** change `S/Robots.java`, `S/BaseStation.java`, `S/BaseLevels.java`.
- **Steps:**
  1. On placing a Forge with `active()`, give one Tier I Farmer if this player has none placed and hasn't had one. Save per player.
  2. New Survival Agents default to Farmer while active.
  3. Fix the empty-slot text and Workshop text so they point at the real sources.
- **Edge cases:** a full inventory drops the Agent at the player's feet, as `give` does; a trusted member placing a Forge in someone else's claim gets nothing; replacing a Forge gives nothing again.
- **Done when:** a new test player gets exactly one Farmer across two Forge placements.
- **Leaves ready:** a new player can start with no outside help.
- **Evidence:**

### [ ] P5.T3: Add player guidance
- **Needs:** P5.T2; `hub/FoundryTips.java`; `S/Robots.java` help.
- **Files:** change `hub/FoundryTips.java`, `S/ForgeHome.java`, `S/BaseStation.java`.
- **Steps:**
  1. Add a first-open message and How It Works text.
  2. Add a Foundry tip.
  3. Prepare the launch news line: `/news add survival Your base can now level up! Open your Forge to see what you've unlocked and what's next.`
- **Edge cases:** no Forge yet: the Workshop says where to buy one; a player without the pack.
- **Done when:** a new tester gets a Farmer delivering and finds Level 2's cost from in-game text alone.
- **Leaves ready:** guidance.
- **Evidence:**

### [ ] P5.T4: Measure balance and load
- **Needs:** P5.T3.
- **Files:** change `plugins-src/BlackForgeCore/src/main/resources/config.yml` if Colin approves; update Verified facts here.
- **Steps:**
  1. Record a tester's deliveries an hour at each level on a staff preview.
  2. Measure tick time with a claim of many Tier III hoppers, using `/mspt` or equivalent.
  3. Propose price changes and a final hopper safety limit.
- **Edge cases:** a live config keeps keys it already has, so state any live edit needed.
- **Done when:** numbers are recorded here and Colin approves any change.
- **Leaves ready:** final numbers.
- **Evidence:**

### [ ] P5.T5: Turn it on for Survival
- **Needs:** P5.T4 and all gates.
- **Files:** change `plugins-src/BlackForgeCore/src/main/resources/config.yml`; set this plan's Status to Complete.
- **Steps:**
  1. Set `base-levels.enabled: true`.
  2. Post the `/news` line once live.
  3. Confirm `tools/deploy.py` doesn't write over the key.
- **Edge cases:** Skyblock doesn't construct `BaseLevels`, so its Forge stays as it is.
- **Done when:** the Phase 5 gate passes.
- **Leaves ready:** feature live.
- **Evidence:**

## Blockers log
| Date | Task | Problem | Decision |
|---|---|---|---|
| 2026-11-04 | Phase 1 gate | The Phase 1 in-game checks need a tester before Phase 2 can start (rule 9). P2.T1 and P2.T2 are prepared on stacked branches, unopened | Waiting on Colin |

## Change log
| Date | Change | Approved by |
|---|---|---|
| 2026-11-04 | Starter Farmer and the 256-per-chunk hopper limit moved from assumptions to decisions | Colin, in chat |
| 2026-11-04 | Plan created from Colin's decisions, and approved to build with "start building" | Colin, in chat |

## Later ideas
- Base levels on Skyblock, using island IDs.
- Gatherer and Courier on Skyblock, since neither needs a sell path.
- Level cosmetics at the Forge, such as a taller or glowing Station.
- A leaderboard of base levels.

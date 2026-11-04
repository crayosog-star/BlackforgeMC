# Agent Academy
**Status:** In progress: Phase 1 is built, and P1.T1, P1.T2 and P1.T3 are waiting on their live checks
**Next task:** P2.T1, which can't start until the Phase 1 gate is confirmed (rule 9; see Blockers log)

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
**Pitch:** your Agents stop being interchangeable machines. They earn Mastery by working, you choose how each one specializes, and a well-trained Agent with rare traits becomes something worth showing off or selling.

**Why it's worth building:** Tier upgrades only ask for Charge, Essence and money, so two Tier V Miners are identical. The Academy adds a second upgrade path built on decisions: which job, perks and specialization, and whether to pay to reroll traits. Players build income by training Agents, and can also sell trained ones on `/ah`.

**How it fits together**
| Layer | Unlocks at | What the player does |
|---|---|---|
| Mastery (level 0-30, kept per job) | Earned by real work | Keep the Agent fuelled and busy, with a bonus while its owner is nearby |
| Perks | Job levels 5, 15, 25 | Pick one perk each time |
| Specialization | Job level 10 | Choose one of two paths for that job |
| Capstone | Job level 30 | Automatic upgrade of the chosen specialization |
| Traits | First time any job reaches 10, then 30 | Rolled at random; pay Essence and money to reroll |
| Service Record | Any time | Item lore that shows an Agent's history, so buyers can judge it |

**Out of scope:** Field Exams, Skyblock and OneBlock, new custom items, combat abilities, more than 5 Agents per player, and any change to Tier costs.

## Decisions and assumptions
**Decided by Colin**
- Build Agent Academy rather than the Emberheart, Colossus or Deep Shaft.
- No Field Exams. Agents rank up from work alone.
- Survival only.

**Assumptions (recommended defaults; change them before approval if you disagree)**
- Existing Agents start at Mastery 0, keeping their Tier, fuel and Charge.
- Mastery, perks and specialization are kept per job, so switching jobs loses nothing. Traits belong to the Agent.
- There are no new items, so no 16x16 textures or Arena wall frames are needed. Everything happens in menus.
- Fully training one job should take about 4 weeks of continuous fuelled work. A fully trained, best-case Agent should out-produce an untrained one of the same Tier by no more than 60%. P5.T1 checks both.
- Trained Agents stay tradable on `/ah`, which already accepts them.

## Verified facts
Checked against `origin/main` at `acadd54`. `S` means `plugins-src/BlackForgeCore/src/main/java/net/blackforge/core/survival`.

**Blockers (cleared in Phase 1)**
- **No place for Academy data.** `Bot` has no Mastery fields (`S/Robots.java:84`), and the item stores only job, tier, charge, fuel, fuel tier and name (`S/Robots.java:194`).
- **Identical Agent items can stack, and nothing identifies a single Agent.** A crash could restore a placed item while its Agent still exists. That matters more once Agents are valuable. Placing happens at `S/Robots.java:231`.
- **No record of work done.** `work`, `mine`, `farm`, `grind` and `fish` return nothing (`S/Robots.java:403`, `423`, `464`, `496`, `548`), so Mastery has nothing to count.
- **Agent stats are read directly from fixed arrays** inside the job code, and the work timer counts whole seconds (`S/Robots.java:370`). Perks and traits can't change them without a shared stat layer.

**Other facts**
- `Robots` runs on Survival, Skyblock and the Arena's Foundry (`S/SurvivalModule.java:150`, `S/SkyblockModule.java:114`, `S/FoundrySystems.java:29`). The live flag must keep Skyblock unchanged.
- Foundry Agents are in a sandbox: free and unsaved (`S/Robots.java`, `sandbox` field). That's the test ground for every phase.
- Survival Agents are crate-only (`S/SurvivalModule.java:154`), and the limit is 5 per player (`S/RobotRules.java:17`).
- Agents only work in loaded chunks (`S/Robots.java:370`). Training therefore pauses when nobody is nearby.
- Agents burn fuel only while working (`S/Robots.java:388`), so fuel gates Mastery.
- `/ah sell` accepts any held item, including Agents (`S/AuctionHouse.java:137`).
- Agent menu slots 12 and 14 are free (`S/Robots.java:581`). Forge slot 35 is free (`S/BaseStation.java:270-274`).
- `tools/deploy.py:98` writes a short `config.yml`, and `saveDefaultConfig()` runs at `BlackForgeCore.java:48`. Other keys are expected to come from the jar's default `config.yml`, so the flag ships in that file with no live tooling change.
- Each server keeps its own `robots.yml` in its plugin folder (`S/Robots.java`, constructor), so this feature needs no shared file or lock. Agent items can't leave Survival, because items don't move between servers.
- `tools/backup.py:42` backs up the `plugins` folder, which includes `robots.yml`.
- Menu art comes from `tools/gui_art.py` and `S/StationUi.java`. First-use tips use `hub/FoundryTips.java:52`.
- Crate rewards run console commands (`S/Crates.java:303`). `robots give` builds an item with no record (`S/Robots.java`, command `give`), so crate, shop and Foundry Agents are fresh and get their record and UID when placed.
- Test bots rely on Agent menu slots 11 and 17 (`tools/testbot/survival_robots.js:16`). This plan doesn't move them.

**Unverified**
- Which crate rewards the live server has. The repo's default `crates.yml` lists no Agents, and the live file isn't in the repo. The Survival test bot reads `robots give {player} ...` reward commands from it (`tools/testbot/survival_robots.js:49-50`).
- Whether the Forge and Agent menu art looks right with new icons in slots 35 and 14. P2.T2 checks it.
- The default-config fallback described above. P1.T1 checks it on a live server.

## Counterplay and theme
**Counterplay:** this feature adds no combat item or ability, so the item matchup table doesn't apply. Agents stay invulnerable, and the EMP gains no new interaction. Balance is controlled by daily Mastery limits, fuel costs, the 5-Agent limit and escalating reroll prices.

**Theme:** a forge guild's apprenticeship, with the Agent going from Apprentice to Master.
- **Palette:** brass `#C9A227` to verdigris `#4FA08B`, used for titles, particles and rank text.
- **Names:** the menu is "The Academy". Ranks are Apprentice (0-9), Journeyman (10-19), Artisan (20-29) and Master (30).
- **Level-up:** a burst of `WAX_ON` particles and a soft anvil chime, visible to anyone nearby.
- **Graduation (levels 10 and 30):** a brass ring of particles, `UI_TOAST_CHALLENGE_COMPLETE` for the owner, and a chat message to the owner only.

### Perks
Each job level 5, 15 and 25 grants one pick, from perks not yet chosen for that job.
| Perk | Effect | Jobs | Config key under `academy.perks` |
|---|---|---|---|
| Efficient | Burns 15% less fuel | All | `efficient-fuel-mult: 0.85` |
| Quick Hands | Works 10% faster | All | `quick-speed-mult: 1.10` |
| Long Reach | +1 work radius | Farmer, Grinder | `reach-radius: 1` |
| Keen Eye | 5% chance to double a drop | All | `keen-double-chance: 0.05` |
| Steady Charge | Builds Charge 15% faster | All | `steady-charge-mult: 1.15` |

### Specializations and capstones
Keys sit under `academy.specs`.
| Job | Path | Level 10 | Level 30 capstone | Config keys |
|---|---|---|---|---|
| Miner | Prospector | +1 Fortune level | 2% chance to double a cycle's drops | `prospector-fortune`, `motherlode-chance` |
| Miner | Excavator | Also mines the block above the target | Mines two blocks above the target | `excavator-height`, `clearcut-height` |
| Farmer | Harvester | +2 harvests per cycle | 5% chance to double a harvest | `harvester-extra`, `bumper-chance` |
| Farmer | Botanist | Grows 1 crop one stage per cycle | Grows 3 crops per cycle | `botanist-crops`, `verdant-crops` |
| Grinder | Reaper | +25% damage, +1 hit | +2 more hits | `reaper-damage-mult`, `reaper-hits`, `cleave-hits` |
| Grinder | Butcher | 20% chance to copy one normal drop | Chance rises to 35% | `butcher-chance`, `prime-chance` |
| Fisherman | Angler | Fishes 20% faster | Fishes 35% faster | `angler-speed-mult`, `quickline-speed-mult` |
| Fisherman | Treasure Diver | Treasure chance x1.5 | Treasure chance x2 | `diver-mult`, `trove-mult` |

Butcher and Keen Eye copy only drops with no custom data, so Mob Essence and named items are never copied.

### Traits
Rolled the first time any job reaches level 10 (slot 1) and then 30 (slot 2). Slots never hold the same trait twice. Keys sit under `academy.traits`.
| Trait | Rarity (weight) | Effect |
|---|---|---|
| Thrifty | Common (35) | 5% less fuel burned |
| Swift | Common (35) | Works 5% faster |
| Charged | Common (30) | Builds Charge 10% faster |
| Lucky | Rare (15) | 2% chance to double a drop |
| Studious | Rare (15) | Earns 15% more Mastery |
| Prodigy | Legendary (4) | Earns 30% more Mastery |
| Ancient | Legendary (3) | Works 5% faster and burns 5% less fuel |
| Gilded | Legendary (3) | Gold name tag and a faint sparkle, with no stat change |

## Phases and tasks

---

## Phase 1: Foundations
- **Goal:** nothing changes for players. Agents quietly gain an identity and saved Academy data, and the code can count work and calculate stats.
- **Blast radius:** Survival, Skyblock and hub run the changed `Robots` code. `robots.yml` gains an `academy` section per Agent.
- **Rollback:** `academy.live-modes: []` is the default in this phase, so Academy behaviour is off. If a code revert is needed, restore `robots.yml` from backup only if Agents fail to load. Old code ignores the new keys, then drops them on its next save, which loses only empty early data.
- **Phase gate:** Colin or a tester confirms on Survival: an existing Agent still works, its name tag and menu look unchanged, and it can be picked up and placed again with tier, fuel, Charge and name intact. In the Foundry, all four job benches still work.

### [ ] P1.T1: Add the Academy data model, rules and config
- **Needs:** `S/RobotRules.java` as the pattern for pure, tested rules.
- **Files:** create `S/AgentRecord.java`, `S/AcademyRules.java`, `src/test/java/net/blackforge/core/survival/AcademyRulesTest.java`; change `src/main/resources/config.yml`.
- **Steps:**
  1. Create `AgentRecord` with no Bukkit types: `String uid`, `Map<String, JobTrack> jobs` by job name, `List<String> traits`, and lifetime stats `long workDone`, `long minutesWorked`. `JobTrack` holds `long points`, `List<String> perks`, `String spec`.
  2. Add `AgentRecord.encode()` and `decode(String)` using a versioned text format starting `1|`. An unreadable value returns a new empty record and never throws.
  3. Create `AcademyRules` with `levelFor(points)`, `pointsFor(level)`, `perkPicksAt(level)`, `specUnlocked(level)`, `capstoneUnlocked(level)` and `dailyGain(todaySoFar, gain, softCap, overCapRate)`.
  4. Add `academy:` to `config.yml`: `live-modes: []`, `points-per-work` (`miner: 1`, `farmer: 1`, `grinder: 2`, `fisherman: 12`), `level-base: 20`, `level-exponent: 2.0`, `max-level: 30`, `daily-soft-cap: 1500`, `over-cap-rate: 0.25`, `coach-radius: 16`, `coach-mult: 1.25`. Add the empty `perks`, `specs` and `traits` sections, with final values in the tasks that use them.
  5. Log one startup line with the resolved `academy.live-modes` value.
- **Edge cases:** corrupt saved text, unknown job or perk names (kept but ignored), negative points, levels above max.
- **Done when:** `AcademyRulesTest` covers round-trip encoding, corrupt input, level boundaries at 0, 10 and 30, and daily cap splitting (two small gains equal one large one). The automerge build passes with tests. The Survival startup log shows `live-modes` as empty, which proves the config default reaches a live server.
- **Leaves ready:** `AgentRecord`, `AcademyRules` and config keys for P1.T2.
- **Evidence:** not ticked, because two checks can only run after this PR merges (see Blockers log).
  - Verified: `AcademyRulesTest` (14 tests) passed against the two new classes, compiled with `javac` 21 and a small local stand-in for JUnit, since Gradle isn't run locally. This wasn't the real JUnit or the project's Java 25.
  - Untested: the automerge build with tests, which is also the first compile of the `BlackForgeCore.java` log line.
  - Untested: the Survival startup log. Expected: `Academy live-modes=[] source=jar-default`. `source` was added because an empty list reads the same as a missing key, so `live-modes=[]` alone wouldn't prove the default arrives. `source=missing` means it doesn't.
  - Note: the log line needed one file beyond this task's list, `BlackForgeCore.java`, because nothing else runs at startup yet.
  - For P2.T1: `dailyGain`'s `todaySoFar` is the day's raw gains, not awarded points, so `todayPoints` must store raw gains.

### [ ] P1.T2: Save, load and carry each Agent's record
- **Needs:** `AgentRecord` from P1.T1; `S/Robots.java:84`, `194`, `231`, `764`, `943`, `974`.
- **Files:** change `S/Robots.java`; extend `AcademyRulesTest.java` if rules change; create `S/AgentRecordItemTest.java` only if the item logic can be tested with no server.
- **Steps:**
  1. Add `AgentRecord record` to `Bot`. Load it from `academy` in `loadPending`, write it in `save`. Agents with no value get a new record and UID.
  2. Add a new `NamespacedKey` `agent_record`. Leave the current `robot_item` string unchanged so older items still work.
  3. Add a `robotItem` overload taking a record. `pickUp` sets the record on the item. A record with a UID stops identical Agents stacking. `onPlace` reads it, or creates a new record if absent.
  4. In `onPlace`, reject an item whose UID matches a live Agent: don't place it, show "This Agent is already placed.", and log a warning with the UID and player.
  5. Don't add any Academy lore yet.
  6. Find how crates issue Agents and record the answer here and under Verified facts.
- **Edge cases:** stacks of old items placed one at a time, an Agent in a world that isn't loaded (`pending` must keep its record), Foundry agents (records exist but aren't saved), restart between pickup and place, crash restoring a placed item.
- **Done when:** on a test or live server, a staff member records: an old Agent keeps working after restart; picking up and placing keeps its UID in `robots.yml`; placing a second copy of a UID (staff-created with `/give` or by editing the test save) is refused. Old items still place.
- **Leaves ready:** every Agent has a saved record for P1.T3.
- **Evidence:** not ticked, because every **Done when** check needs a staff member on a running server.
  - Crates (step 6): reward commands go through `robots give`, which builds an item with no record, so a crate Agent gets its record and UID when placed. The live `crates.yml` wasn't read.
  - Verified: `Robots.java` parses under `javac` 21. It wasn't compiled against Paper, since Gradle isn't run locally.
  - Untested: the automerge build, and everything in game: restart, pickup and place, duplicate refusal and old items.
  - Note: `uidInUse` also checks Agents waiting for their world to load, so a copy can't be placed while the original is unloaded.

### [ ] P1.T3: Add the work signal and stat layer with no behaviour change
- **Needs:** record on `Bot` from P1.T2; `S/Robots.java:370`, `388`, `403`, `423`, `464`, `496`, `531`, `548`.
- **Files:** create `S/AgentStats.java`, `src/test/java/net/blackforge/core/survival/AgentStatsTest.java`; change `S/Robots.java`.
- **Steps:**
  1. Create `AgentStats`, a pure record built from job, tier and Academy state, with `intervalSeconds`, `speedMult`, `radius`, `damage`, `hits`, `harvests`, `fortune`, `fuelMult`, `chargeMult`, `doubleDropChance`, `treasureMult`.
  2. Make `mine`, `farm`, `grind` and `fish` read `AgentStats`, and return a count of real work done (blocks mined, crops harvested, hits landed, fish caught). `work` passes that to a new empty method `onWorked(Bot, int)`.
  3. Change the work timer from whole seconds to an in-memory `double workProgress` that adds `speedMult` each second, so fractional speed bonuses work.
  4. Apply `fuelMult` and `chargeMult` where burn and Charge are calculated. Keep `b.timer` for the name tag refresh.
- **Edge cases:** Fisherman's quarter burn rate, fishing without water, full storage, a job switch mid-cycle, fuel running out mid-cycle, and speed values that could divide by zero.
- **Done when:** `AgentStatsTest` proves that with an empty record, all values match today's `INTERVAL`, `RADIUS`, `DAMAGE`, hits and Fortune for every job and all 5 tiers. In the Foundry, a tester confirms all four benches produce at the usual pace.
- **Leaves ready:** `onWorked` and `AgentStats` for Phase 2.
- **Evidence:** not ticked, because the Foundry check needs a tester.
  - Verified: `AgentStatsTest` (5 tests) and `AcademyRulesTest` passed, compiled with `javac` 21 and a small local stand-in for JUnit. This wasn't the real JUnit or the project's Java 25.
  - Verified: `Robots.java` parses under `javac` 21. It wasn't compiled against Paper, since Gradle isn't run locally.
  - Untested: the automerge build, and the four Foundry benches.
  - Note: `onWorked` gets hits that landed, so a Foundry mob that can't be hit counts as no work, as P2.T1 needs.
  - Note: Charge is built from the full burn, so a fuel-saving bonus won't cost Charge. `workProgress` copies `b.timer` while idle, so idle time affects the next cycle exactly as it did before.

---

## Phase 2: Mastery you can see (Foundry only)
- **Goal:** in the Arena's Foundry, players watch an Agent earn Mastery, see its level and rank, and open The Academy.
- **Blast radius:** player-visible changes in the Foundry only. Survival and Skyblock stay unchanged because `live-modes` is empty.
- **Rollback:** Academy UI and earning check one method, `Robots.academyOn()`. To remove it from the Foundry, change that one method. Nothing here is saved, since Foundry data isn't.
- **Phase gate:** a tester confirms in the Foundry: an Agent earns Mastery while working, stops earning with no fuel, its level appears in The Academy and on its name tag, and a level-up effect plays. On Survival, the Agent menu and Forge look unchanged.

### [ ] P2.T1: Earn Mastery
- **Needs:** `onWorked` from P1.T3; rules and config from P1.T1.
- **Files:** change `S/Robots.java`, `S/AcademyRules.java`, `AcademyRulesTest.java`.
- **Steps:**
  1. Add `Robots.academyOn()`: true when `sandbox`, or when the server's `mode` is in `academy.live-modes`.
  2. Fill in `onWorked`: when `academyOn()`, convert work to points using `points-per-work`, multiply by `coach-mult` if the owner is online and within `coach-radius`, then apply the daily limit.
  3. Add daily fields to `JobTrack`: `todayPoints` and `todayDate` (UTC). Add them to the encode format, moving the prefix to `2|` and keeping `1|` readable.
  4. Add the staff command `/agents academy points <player> <amount>`, requiring `blackforge.admin`, for testing.
- **Edge cases:** owner offline (no bonus but earning continues), UTC day change, job switch (each job has its own points), max level, Foundry mobs that can't be hit (no work counted).
- **Done when:** unit tests cover daily cap and date rollover, and that a `1|` record still decodes. In the Foundry, a tester checks via `/agents academy points` and normal work that points rise, and that an unfuelled Agent gains none.
- **Leaves ready:** real Mastery values for P2.T2.
- **Evidence:** not ticked; prepared on a branch with no PR, because the Phase 1 gate isn't confirmed.
  - Verified: `AcademyRulesTest` and `AgentStatsTest` passed, compiled with `javac` 21 and a small local stand-in for JUnit, including the daily cap, date rollover and `1|` decoding.
  - Verified: `Robots.java` parses under `javac` 21. It wasn't compiled against Paper.
  - Untested: the automerge build and everything in the Foundry.
  - Note: `/agents academy points <player> 0` only reads values, which lets a tester see normal work raising points before The Academy exists.
  - Note: the command had to register in the Foundry too. It previously didn't exist on the hub, so the rest of `/agents` there just gives a short message.
  - Note: `workDone` also counts up here, so P2.T2 has a lifetime stat to show. `minutesWorked` doesn't yet.
  - Note: rolling back past this task means old code can't read `2|` and gives an Agent a new UID. Nothing is lost while Academy is off for players.

### [ ] P2.T2: Build The Academy menu
- **Needs:** Mastery from P2.T1; `S/Robots.java:581`, `597`; `S/BaseStation.java:270-274`, `359`.
- **Files:** create `S/Academy.java`; change `S/Robots.java`, `S/BaseStation.java`.
- **Steps:**
  1. Create `Academy`, a listener with its own `InventoryHolder`, showing a 54-slot view for one Agent: rank, level, a progress bar, today's progress against the soft cap, all four job levels and lifetime stats. Use plain panes for now.
  2. Add an Academy button in Agent menu slot 14, shown only when `academyOn()`. It must work in read-only view without changing anything.
  3. Add an Academy icon in Forge slot 35, shown only when `academyOn()`, listing the player's Agents. Clicking one opens its view.
  4. Add back buttons. Cancel all clicks, including shift-clicks, number keys and drags.
- **Edge cases:** Agent picked up while its menu is open, access lost while open, a Station broken while open (`S/BaseStation.java:359` checks this), `OwnerView` read-only viewers.
- **Done when:** in the Foundry, a tester opens The Academy both ways and confirms the icons line up with the art, and no item can be moved in or out by click, shift-click, number key or drag. On Survival, slots 14 and 35 stay empty. Update Verified facts with the art result.
- **Leaves ready:** the menu that Phase 3 adds choices to.
- **Evidence:**

### [ ] P2.T3: Show rank and level-ups
- **Needs:** `S/Academy.java` from P2.T2; `S/Robots.java:286`, `194`.
- **Files:** change `S/Robots.java`, `S/AcademyRules.java`, `AcademyRulesTest.java`.
- **Steps:**
  1. Add `AcademyRules.rankFor(level)`: Apprentice, Journeyman, Artisan, Master.
  2. When `academyOn()`, add the rank and level to the name tag in `style`, in the brass colour.
  3. In `onWorked`, detect a level change. Play the level-up effect, and the graduation effect at levels 10 and 30. Tell the owner if online; stay silent if offline.
  4. Add `Service Record` lore to the Agent item when the record has any points: rank, each job level, perks, specialization, traits and work done. Old and untrained items keep today's lore.
- **Edge cases:** several levels gained at once (`/agents academy points`), name tag length with long Agent names, offline owner, Agent in an unloaded chunk.
- **Done when:** in the Foundry, a tester sees the name tag update, the level-up and graduation effects, and correct lore on a picked-up Agent. Unit tests cover all rank boundaries.
- **Leaves ready:** visible progress for P2.T4 and Phase 3.
- **Evidence:**

### [ ] P2.T4: Make the Academy easy to test in the Foundry
- **Needs:** P2.T1-T3; `S/FoundrySupply.java`; `hub/FoundryTips.java:52`.
- **Files:** change `S/Academy.java`, `hub/FoundryTips.java`.
- **Steps:**
  1. In the Foundry only, add an "Add 10 levels" button to The Academy, which is free and affects only that Agent.
  2. Add a one-time tip when a player first opens The Academy.
- **Edge cases:** max level, a tip already seen, and the button never appearing outside the sandbox.
- **Done when:** a tester reaches level 30 in the Foundry using only the button, sees the tip once, and confirms the button doesn't exist on Survival, including via `/agents academy` staff menus.
- **Leaves ready:** fast testing of perks and specializations.
- **Evidence:**

---

## Phase 3: Choices that matter (Foundry only)
- **Goal:** in the Foundry, players choose perks and a specialization for each job and can measure the difference at the benches.
- **Blast radius:** Foundry only. New saved fields stay unused elsewhere.
- **Rollback:** the same `academyOn()` switch. `AgentStats` falls back to base values whenever it's off.
- **Phase gate:** a tester tries all 8 specializations, all 5 perks and all 8 capstones at the Foundry benches and confirms each does what its menu says. Respec resets that job's choices and keeps its Mastery.

### [ ] P3.T1: Add perk picks
- **Needs:** `AgentStats` from P1.T3; The Academy from P2.T2; the fast-level button from P2.T4.
- **Files:** change `S/Academy.java`, `S/AgentStats.java`, `S/AcademyRules.java`, `S/Robots.java`, `config.yml`, `AgentStatsTest.java`, `AcademyRulesTest.java`.
- **Steps:**
  1. Add the five perks, using the values and keys in the Perks table.
  2. Show available picks in The Academy, with effects and which jobs each suits. Long Reach is hidden for Miner and Fisherman.
  3. Save a pick only after a confirm click. Picks can't exceed `perkPicksAt(level)`.
  4. Apply perks in `AgentStats`. Implement Keen Eye in `store` for items with no custom data.
- **Edge cases:** double-clicking a pick, a job switch (perks follow the job), the speed floor, storage staying within the 36-slot menu limit.
- **Done when:** unit tests cover pick limits and every perk's effect on `AgentStats`. In the Foundry, the Farmer bench shows Long Reach's added radius, and a stopwatch test shows Quick Hands working faster.
- **Leaves ready:** the pick-and-apply pattern for specializations.
- **Evidence:**

### [ ] P3.T2: Add Miner and Farmer specializations
- **Needs:** P3.T1; `S/Robots.java:423`, `464`.
- **Files:** change `S/Academy.java`, `S/AgentStats.java`, `S/Robots.java`, `config.yml`, `AgentStatsTest.java`.
- **Steps:**
  1. Add a specialization choice in The Academy at job level 10, with a warning that changing it later costs money.
  2. Prospector: raise Fortune by `prospector-fortune` (default 1), including at tiers with none.
  3. Excavator: also mine the block above the target, with the same `workArea` and block checks as the target.
  4. Harvester: raise the harvest limit by `harvester-extra` (default 2).
  5. Botanist: advance `botanist-crops` (default 1) random immature crops inside the work area per cycle.
- **Edge cases:** blocks in `NO_MINE`, containers, claim edges, breaking the block an Agent stands on, block-break events cancelled by other systems, crops that are already grown.
- **Done when:** unit tests cover the stat changes. In the Foundry, a tester confirms each of the four paths, and that an Excavator next to a chest or claim edge leaves both alone.
- **Leaves ready:** the spec check in the job code for P3.T3.
- **Evidence:**

### [ ] P3.T3: Add Grinder and Fisherman specializations
- **Needs:** P3.T2; `S/Robots.java:496`, `531`, `548`.
- **Files:** change `S/AgentStats.java`, `S/Robots.java`, `config.yml`, `AgentStatsTest.java`.
- **Steps:**
  1. Reaper: multiply damage by `reaper-damage-mult` (1.25), and add `reaper-hits` (1) hit.
  2. Butcher: in `onKill`, `butcher-chance` (0.20) to copy one drop with no custom data.
  3. Angler: multiply fishing speed by `angler-speed-mult` (1.20).
  4. Treasure Diver: multiply the treasure chance by `diver-mult` (1.5).
- **Edge cases:** Mob Essence and other custom drops must never copy; animals protected by the Grinder's existing skip rules stay protected; a mob killed by something else; total treasure and junk chance staying below 100%.
- **Done when:** unit tests cover the stats, including the chance cap at Tier V. In the Foundry, a tester confirms Reaper kills husks faster, and a Butcher kill never copies custom items, using the Grinder bench.
- **Leaves ready:** all 8 paths for P3.T4.
- **Evidence:**

### [ ] P3.T4: Add capstones
- **Needs:** P3.T3.
- **Files:** change `S/AgentStats.java`, `S/Robots.java`, `S/Academy.java`, `config.yml`, `AgentStatsTest.java`.
- **Steps:**
  1. Add all 8 capstones from the Specializations table. They apply automatically at level 30 of that job.
  2. Show each capstone in The Academy as locked or active.
- **Edge cases:** a capstone replacing, not stacking with, its level 10 value where the table gives a higher replacement; every block-safety check from P3.T2 applying to extra Clearcut blocks.
- **Done when:** unit tests check each capstone at level 29 and 30. In the Foundry, a tester confirms each one activates at 30 and shows as active.
- **Leaves ready:** the complete build system for P3.T5.
- **Evidence:**

### [ ] P3.T5: Add respec
- **Needs:** P3.T1-T4.
- **Files:** change `S/Academy.java`, `S/AcademyRules.java`, `config.yml`, `AcademyRulesTest.java`.
- **Steps:**
  1. Add `academy.respec`: `money: 25000`, `essence: 128`, `mult-per-use: 1.5`, `max-money: 500000`. Add a saved respec count to `JobTrack`.
  2. Add a respec button with a confirm step. It clears that job's perks and specialization, keeps its Mastery, and takes the price. It's free in the Foundry.
  3. Take payment only after checking both Essence and money, using the `S/Robots.java:742` upgrade pattern, and never partially.
- **Edge cases:** too little Essence or money, double-clicks, offline economy (`eco == null`), no choices to reset, multiplier reaching the cap.
- **Done when:** unit tests cover escalating and capped prices. In the Foundry, a tester resets one job's choices, confirms other jobs are untouched, and confirms the free path doesn't appear in the code path for non-sandbox servers.
- **Leaves ready:** a working Essence and money sink pattern for reroll.
- **Evidence:**

---

## Phase 4: Traits and trading (Foundry and staff preview)
- **Goal:** Agents gain random traits that make them unique, and staff can test the whole system on real Survival, including selling a trained Agent.
- **Blast radius:** Foundry, plus staff-owned Agents on Survival through the preview. Staff Agents' real records are saved.
- **Rollback:** set `academy.preview-enabled: false` to stop preview earning and hide the menus. Saved records stay in place and do nothing.
- **Phase gate:** on Survival, Colin or staff confirm: a preview Agent earns Mastery; survives restart with everything intact; sells through `/ah` to another account with its Service Record correct and everything working for the buyer; and non-staff players see no Academy UI or earning.

### [ ] P4.T1: Roll and apply traits
- **Needs:** `AgentStats` from Phase 3; `S/AgentRecord.java`.
- **Files:** change `S/AcademyRules.java`, `S/AgentStats.java`, `S/Robots.java`, `S/Academy.java`, `config.yml`, `AcademyRulesTest.java`, `AgentStatsTest.java`.
- **Steps:**
  1. Add the 8 traits, weights and effect values from the Traits table, under `academy.traits`.
  2. Add pure `AcademyRules.rollTrait(existing, weights, Random)`.
  3. In `onWorked`, roll slot 1 the first time any job reaches level 10, and slot 2 at 30. Play the graduation effect with the trait's name.
  4. Apply stat traits in `AgentStats` and Mastery traits in `onWorked`. Gilded changes only the name tag and particles.
  5. Show traits in The Academy and the Service Record.
- **Edge cases:** a job jumping past level 10 and 30 together, no duplicates, the same trait never granted twice after switching jobs, restart between level-up and roll (the save must include both or neither).
- **Done when:** unit tests use a seeded `Random` to check no duplicates and, over 100,000 rolls, each weight within 1 percentage point. In the Foundry, a tester reaches level 30 and sees two different traits.
- **Leaves ready:** traits for P4.T2.
- **Evidence:**

### [ ] P4.T2: Add trait rerolls
- **Needs:** P4.T1; respec payment pattern from P3.T5.
- **Files:** change `S/Academy.java`, `S/AcademyRules.java`, `config.yml`, `AcademyRulesTest.java`.
- **Steps:**
  1. Add `academy.reroll`: `money: 50000`, `essence: 256`, `mult-per-use: 1.5`, `max-money: 1000000`, with a saved reroll count on `AgentRecord`.
  2. Add a reroll button per trait slot, with a confirm step showing the price and odds. Reroll excludes the other slot's trait, and it may roll the same trait again.
  3. Log each reroll: Agent UID, player, price, old trait, new trait.
- **Edge cases:** double-clicks, too little Essence or money, and the count following the Agent after trades, so reselling can't reset the price.
- **Done when:** unit tests cover prices. In the Foundry, a tester rerolls a slot for free and logs show the entry. P4.T3 checks real payment.
- **Leaves ready:** the final item state for P4.T3.
- **Evidence:**

### [ ] P4.T3: Add staff preview on Survival and check trading
- **Needs:** P4.T1-T2; `S/Robots.java:194`; `S/AuctionHouse.java:137`.
- **Files:** change `S/Robots.java`, `S/Academy.java`, `config.yml`.
- **Steps:**
  1. Add `academy.preview-enabled: true` and `academy.preview-modes: [survival]`. In those modes, `academyOn(owner)` also returns true when the owner has `blackforge.admin`. Replace `academyOn()` calls with the per-owner form. A non-staff owner's Agent still doesn't earn.
  2. Add a `Trade value hint` line to the Service Record showing total levels and rarest trait.
  3. Test `/ah` with a trained Agent: list, cancel, expire back through the collection box, and buy.
  4. Test real payment: a respec and a reroll each take the exact price, and neither takes anything when the player can't afford both Essence and money.
- **Edge cases:** a staff member's trained Agent bought by a non-staff player before launch (it keeps its record but doesn't progress or show its Academy, and that's acceptable only for testers); `/ah` lore display; restart with a listed Agent.
- **Done when:** on Survival, a staff member and second account complete the phase gate. Any failure goes in the Blockers log, and no player-visible change appears for non-staff.
- **Leaves ready:** proof that real saving and trading work.
- **Evidence:**

### [ ] P4.T4: Add Academy Honours
- **Needs:** records from Phase 1 and traits from P4.T1.
- **Files:** change `S/Academy.java`, `S/AcademyRules.java`, `AcademyRulesTest.java`.
- **Steps:**
  1. Add pure `AcademyRules.honourScore(record)`: the sum of job levels, plus `academy.honours.master-bonus` (10) per Master job.
  2. Add `/agents honours`, showing the top `academy.honours.size` (10) placed Agents with their name, owner, rank and traits. Recalculate at most once every `academy.honours.refresh-minutes` (5).
  3. Hide it unless `academyOn` for the viewer.
- **Edge cases:** owner offline (uses the stored name lookup), a tie (oldest UID order), Agents in item form (not shown, and say so in the menu).
- **Done when:** unit tests cover score and tie order. In preview, staff see correct results across two accounts, and a non-staff player gets the normal unknown-command or unavailable message.
- **Leaves ready:** a public goal for launch.
- **Evidence:**

---

## Phase 5: Launch on Survival
- **Goal:** every Survival player can train, specialize and trade Agents, with clear guidance and the menu art finished.
- **Blast radius:** all Survival players and `robots.yml`. Agent output and the economy will change. Skyblock and OneBlock stay off.
- **Rollback:** set `academy.live-modes: []` and restart through a PR. Records stay saved, and Agents go back to base stats. Restore `robots.yml` from backup only if saved data is damaged.
- **Phase gate (launch sign-off):** Colin confirms in game on live Survival: a normal player can open The Academy, earn Mastery, pick a perk and a specialization, and read the guidance. After 48 hours, the balance check in P5.T4 is recorded.

### [ ] P5.T1: Check balance
- **Needs:** every value in `config.yml` from Phases 1-4; `S/SellCatalog.java`.
- **Files:** create `src/test/java/net/blackforge/core/survival/AcademyBalanceTest.java`; change `config.yml` only if the test fails; add results here.
- **Steps:**
  1. Write a test that calculates for each job at Tier V: hours to level 30 with the default limits, and best-case output compared with an untrained Agent.
  2. Convert Farmer, Miner, Grinder and Fisherman output to money using `SellCatalog` prices where possible, and record assumptions.
  3. Check: about 4 weeks to level 30 (21-35 days) with 8 active hours a day, and best-case output no more than +60%.
  4. Adjust only `config.yml` values if needed. Record old and new values in the Change log.
- **Edge cases:** the Fisherman's slower pace, job output limited by farm size rather than Agent speed (record as an assumption), reroll prices relative to typical Survival income (flag if it can't be measured).
- **Done when:** `AcademyBalanceTest` passes, and the result table is in this document. These are calculated numbers, so label them as modelled, not observed.
- **Leaves ready:** final values for art and guidance text.
- **Evidence:**

### [ ] P5.T2: Finish The Academy's art
- **Needs:** menu layout from Phases 2-4; `tools/gui_art.py`; `S/StationUi.java`; `blackforge-skin-item`.
- **Files:** change `tools/gui_art.py`, `S/StationUi.java`, `S/Academy.java`, `resourcepack/`, and `tools/deploy.py` for the routine `PACK_URL` and `PACK_SHA1` update only.
- **Steps:**
  1. Add an Academy screen to `gui_art.py` in the brass and verdigris palette, matching the wells to the final layout.
  2. Add `ACADEMY` to `StationUi.Screen`. Keep the plain-pane fallback when `StationUi.art()` is false.
  3. Build the pack and update `PACK_URL` and `PACK_SHA1` following `blackforge-ship`.
- **Edge cases:** a player with a cached pack, overlong Agent names in the title, the Forge and Agent screens staying unaffected.
- **Done when:** a tester reconnects, sees the art lined up with every slot at GUI scale 2 and 3, and sees no purple textures. Existing screens look unchanged.
- **Leaves ready:** the final menu for guidance text.
- **Evidence:**

### [ ] P5.T3: Add player guidance and update test bots
- **Needs:** P5.T1-T2; `S/Robots.java` shop help; `hub/FoundryTips.java:52`; `tools/testbot/survival_robots.js:16`.
- **Files:** change `S/Robots.java`, `S/Academy.java`, `tools/testbot/survival_robots.js`; create `docs/ACADEMY.md`.
- **Steps:**
  1. Add a "How The Academy works" icon in the Academy menu, and one line in the `/agents` guide, shown when Academy is on.
  2. Add a one-time chat hint when an Agent first reaches levels 5 and 10, telling the owner they have a choice waiting.
  3. Write `docs/ACADEMY.md` for staff: how it works, config keys, flags, rollback and useful log lines.
  4. Extend the Survival test bot to open The Academy and read its main icon.
- **Edge cases:** hints for an offline owner wait until next join; no hint repeats after a restart.
- **Done when:** a new-account tester learns how to pick a perk using only in-game text, and hints appear once. The bot script runs against a test or preview server and its result is recorded.
- **Leaves ready:** everything for launch.
- **Evidence:**

### [ ] P5.T4: Turn it on for Survival
- **Needs:** P5.T1-T3; Phase 4 gate recorded; a fresh backup confirmed by Colin.
- **Files:** change `config.yml`; update this document.
- **Steps:**
  1. Ask Colin to confirm a fresh backup containing Survival's `robots.yml`. Stop until he has. This plan doesn't run `tools/backup.py` itself.
  2. Set `academy.live-modes: [survival]`, `academy.preview-enabled: false`. Open the PR, and tell Colin it will restart all five servers.
  3. After merge, have Colin or staff confirm a normal player can use the system. Check that Skyblock and OneBlock show no Academy UI.
  4. After 48 hours, ask Colin for the `Academy` log lines and reroll count from the server PC. Record the observed level rates against the P5.T1 model. Propose any config changes to Colin rather than making them under this task.
  5. Set **Status** to Complete, and move any new ideas to Later ideas.
- **Edge cases:** existing Agents starting at Mastery 0, Agents listed on `/ah` at launch, a server restart during the countdown, a fast rollback by PR if Agents stop working.
- **Done when:** Colin signs off in game, the 48-hour check is recorded, and Skyblock shows no change.
- **Leaves ready:** a live system, with Skyblock as a possible later plan.
- **Evidence:**

## Blockers log
- 2026-11-04, P1.T1 (open): rule 7 ticks a task in the same PR as its work, but two **Done when** checks need that PR merged first: the automerge build and the Survival startup log. The task stays unticked. Colin to supply the startup log line; ticking then needs a small follow-up PR.
- 2026-11-04, P1.T2 (open): needs a staff member's in-game checks; steps are in PR #91. Colin chose to keep building Phase 1 meanwhile.
- 2026-11-04, P1.T3 (open): needs a tester at the four Foundry benches.
- 2026-11-04, Phase 2 (open): can't start until Colin or a tester confirms the Phase 1 gate.

## Change log
- 2026-11-04: Colin said to keep working, so P2.T1 is prepared on a stacked branch before the Phase 1 gate. No Phase 2 PR opens until that gate is confirmed; rule 9 otherwise stands.
- 2026-11-04: Colin told the build to continue while P1.T1's live check is open, so P1.T2 and P1.T3 go ahead. The Phase 1 gate still has to pass before Phase 2.
- 2026-11-04: plan written. Field Exams dropped and Survival-only scope set by Colin.
- 2026-11-04: Colin approved the plan.

## Later ideas
- Skyblock and OneBlock support, with separate balance.
- Field Exams, if the passive system needs more active play.
- Graduation hats or other visible Agent cosmetics.
- A physical Academy building at spawn, with Honours on a hologram board.
- Agent auctions dedicated to trained Agents.

# Claims & Base Quality of Life

**Status**

Phase 2 approved and underway. P2.T1 passed two-player staging. The Survival QoL flag remains off by default. Live deployment remains unverified.

**Next task**

Start **P2.T2 — Toggle nearby borders and explain denials** from fresh upstream main after verifying P2.T1 has landed.

## Rules of engagement

1. Read the whole plan, list open PRs, and start at Next task.
2. Follow the task order. If a task has already landed, record source and staging evidence before ticking it.
3. Check Needs and cited behavior on fresh upstream main.
4. Keep the task within its write set. Put new ideas in Later ideas.
5. Stop on a blocker, log it, and ask Colin for the decision needed to proceed.
6. Get Colin's approval and record a Change log entry before changing task order, scope, or checks.
7. Tick a task only when Done when passes and the same PR records Evidence.
8. Work on one task per PR. Wait for the preceding merge before starting the next PR.
9. Record phase-gate confirmation before beginning the next phase.
10. Use the blackforge-ship playbook for future release work. Keep incomplete player features behind a default-off Survival flag. Obtain approval for each concrete publishing or live operation and keep autopilot off.
11. Update Next task and Status in every task PR.

## Concept

A player can go from `/rtp` to one protected chunk with an exact preview and a single confirmation. They can walk to the edge, see whose land is on each side, and claim the next chunk through the same flow. Later, the Base Station answers three questions in one place. What land am I standing on? Which of my Agents can deliver here and what needs attention? What do hopper upgrades cost and where do I apply them?

| Experience | Current friction | Smallest useful improvement | Defer |
| --- | --- | --- | --- |
| Claiming | `/claim` commits before showing edges. Trust is account-wide, and unclaim commands apply immediately. The list omits world names. | Keep the command family. Show exact chunk, remaining allowance, and eligibility before confirmation. Extend by walking one chunk over. Add world names to the list and put scope in trust and unclaim prompts. | Bulk claim, shape selection, named plots, per-plot trust, new claiming GUI. |
| Borders | `/claim map` distinguishes trusted land, `show` does not, and the movement banner calls trusted land someone else's land. Existing denial often gives only the owner. | Keep the chat map and short `show`. Add an opt-in nearby overlay with the same four categories and a text legend. Name the action and reason in the existing feedback. | Server display entities, map mods, fake block previews. |
| Agents | Forge cards show job, tier and cost for the viewer's Agents everywhere. The Agent menu has more detail, but status can be stale when unloaded and a changed anchor claim lacks a clear explanation. | Add a station-local roster, coordinates, fuel and a specific status with one next step. Allow upgrade preview and reuse the Agent's existing refuel and collect controls on site. | Remote refuel or collection, teleport, force-loading, bulk controls, progression changes. |
| Hoppers | The Forge lists generic tiers. A placed Tier I hopper is silent, and claim cancellations have no player-facing transfer diagnosis. | Show current and next tiers when opening a hopper, configured cost and limit, and an observed claim-block reason. Keep tagged upgrades and on-block application. | Remote tiering, automatic routing, expanded throughput or cap. |
| Base Station | Right-click opens Forge, sneak-right-click opens storage. Buying or clicking an upgrade can charge immediately. | Make the first screen a summary with links to land, Agents, hopper purchases and storage guidance. Confirm paid actions using the actual wallet, items, and requirements. | New station block, additional stations per owner, a network-wide dashboard. |

## Decisions and assumptions

| Decision for Colin | Recommended default |
| --- | --- |
| How should claim commands confirm? | Make `/claim` and `/claim here` preview first. Use a clickable confirm and a short code for the typed fallback. Require confirmation for both single and bulk unclaim. Keep one-chunk expansion and owner-wide trust. |
| How should borders appear? | Use viewer-only particles with labels and the existing chat map as fallback. Start the toggle off each login. |
| What should happen when ownership changes under a station or Agent? | Approve the narrow protection change in P1.T1 and P3.T1. Stop delivery to a station under stale ownership and pause work at an Agent's displaced anchor. Keep saved assets, apply existing access rules, and use the existing authorized on-site recovery path. A displaced former owner can reclaim the chunk if eligible or ask staff to recover the Agent under existing admin authority. |
| Which actions should the station offer? | Offer local Agent status, coordinates and confirmed tier upgrades, plus confirmed hopper token purchases. Keep fuel, output, job, pickup and hopper application at the physical Agent or block. Require paid confirmations near the originating block or Agent and keep the current trust and staff grants. |
| Should the release rebalance costs, progression, claim allowance, hopper caps, station quota or permissions? | Use current values and grants. Treat any further changes, including cart cargo rules, as separate design decisions. |

Use the current Survival configuration at execution time. Values below are source defaults. The first release needs no new item, pack art, rank, or save migration. Use a proposed `survival-claims-base-qol.enabled` flag, read at startup and wired only through `SurvivalModule`. Stage with an explicit override. Activation and rollback must account for deployment regenerating the backend config. Verify the effective value on the staged deployment path before proposing a production change. `tools/deploy.py:80–100`.

## Verified facts

**Baseline.** Upstream `main` was `236f7d08bd5770569a7b8842b4e11c3d8db2c2df` with zero open PRs at inspection. The active checkout was `fix/forgewars-production-defaults` at `868959b57bca9e1668d4ba98245f909c3f498738` with unrelated local edits. Source citations below refer to the pinned upstream revision. Java filenames are under `plugins-src/BlackForgeCore/src/main/java/net/blackforge/core/`, with the five feature classes in `survival/`. The deployed build target in source is BlackForgeCore on Paper API `26.3.build.222-stable`, Java 25. `tools/deploy.py:10`, `plugins-src/BlackForgeCore/build.gradle.kts:13,27`. [Upstream snapshot](https://github.com/crayosog-star/BlackforgeMC/tree/236f7d08bd5770569a7b8842b4e11c3d8db2c2df) · [Open PRs](https://github.com/crayosog-star/BlackforgeMC/pulls)

**PR #90.** GitHub reports merged as `33c2a7f5d32c0a3e7595c48305d29d154214029b` on November 4. Git ancestry confirms that merge is on the pinned main. Current source checks source and destination inventory owners at transfer, tests both double chest halves, and checks both halves at open. Shop sign and stock must still belong to the shop owner at use. The Forge, station barrel clicks and drags, and Agent menus recheck live access. The Agent upgrade at the Forge resolves the ID again and calls the same upgrade path as the Agent menu. Wallet updates lock and atomically replace the account file. These checks are present in source, and their multiplayer behavior is pending staging. `Claims.java:77–100`, `ChestShops.java:124–145,171–172`, `BaseStation.java:358–398`, `Robots.java:701–716,871–900`, `NetEconomy.java:111–132`. [PR #90](https://github.com/crayosog-star/BlackforgeMC/pull/90)

### Claims and Survival entry points

- `SurvivalModule` dynamically registers player commands `/claim` (alias `/claims`), `/unclaim`, `/trust`, `/untrust`; claim completion currently lists map, show, info, list, trust, untrust, unclaimall and help. `/claim` defaults to `here`. `/claim unclaim` also reaches single removal. Trust resolves a cached or online player and is stored owner-wide by UUID. Both unclaim forms mutate immediately, and bulk removes only the caller's chunks. `blackforge.admin` can exceed allowance, remove another owner's single chunk, and toggle bypass. Normal `canUse` allows wilderness, owner, trusted player or active bypass. `SurvivalModule.java:315–322,379–395`, `Claims.java:64–75,122–191`, `plugin.yml:22–24`.
- Claim checks are based on the chunk centre around the Grove, spawn, KOTH and Scrapyard, with a separate player-position check inside 300 blocks of the End origin. Survival's spawn protection uses a separate location predicate. RTP already filters claimed and unsafe destinations and links players to `/claim`, but land can change before arrival. The spawn onboarding already mentions both commands. `Claims.java:114–139`, `SurvivalModule.java:675–695,721–734,875–877`.
- `claims.yml` records world name and chunk coordinates plus UUID trust lists. Counts span worlds. The allowance is six plus two per completed hour, capped at 120 before rank bonus. Saves log exceptions after mutation, so success feedback currently gives no assurance that the claim or trust change survived a write error. `Claims.java:32–67,102–107,129–165`, `Ranks.java:82`.
- `/claim map` is a north-up text grid with four categories. `/claim show` already sends viewer-only dust for about ten seconds for the selected chunk or the perimeter of its connected same-owner group. It distinguishes own, foreign and wilderness, with no separate trusted colour. Existing build/use checks send an action bar; movement uses wilderness, own land or owner name. `Claims.java:198–274,280–319,384–397`.

### Base Station and Agents

- The Base Station is a tagged barrel. `/agents` sells it. Its config sets enabled, price, Essence, radius and custom art. Defaults are 25,000 money, 128 Mob Essence and a 16-block three-dimensional delivery radius. Survival uses `claim:<owner UUID>` as the station region key, so the one-station quota is per owner across all Survival claims and worlds. Station/hopper access permits admin or a claimed location that passes `Claims.canUse`, including trusted players. Placement also needs a region. The station's marker and inventory live on the barrel; `base-stations.yml` stores region and world coordinates. `BaseStation.java:53–99,103–146,180–232,403–422`, `Robots.java:908–938`, `SurvivalModule.java:155–164`, `config.yml:124–129`.
- Right-click opens Forge. Sneak-right-click with an empty hand opens the real barrel; OwnerView can give a read-only copy under its own rules. Forge lists the viewer's Agents across locations, advertises tiers, and sells 1 or 10 hopper upgrade items. Delivery searches for a loaded, marked station in the same world and range. It matches the Agent's current region to the **saved** station key without comparing the live region at the station. If a station chunk changes hands while a nearby Agent chunk stays with the old owner, delivery to the now-foreign barrel is possible by inspection. No live reproduction was run. `BaseStation.java:150–176,255–267,288–398`, `OwnerView.java:46–76`.
- `/agents` (aliases `/agent`, `/robots`, `/robot`, `/fuel`) opens the shop. Admin `give` and `charge` are separate paths. Survival sells fuel and stations here, with new Agents obtained from Molten, Forgeheart and KOTH crates according to the wired shop restriction. Placement requires the player's own claim, passable space and Agent allowance. Default limit is two, rising with ranks up to five. The right-click Agent menu has job, fuel, storage, collect, rename, pickup and upgrade. Access at click uses the **current land** predicate or admin in Survival; recorded bot ownership and current land ownership are distinct. A foreign OWNER read-only view has its own restriction. `Robots.java:169–176,256–296,360–378,649–767,871–873,908–983`, `SurvivalModule.java:155–159`.
- Agent ticks skip unloaded chunks and unloaded entities. No fuel, full output storage and dry fishing have branches; Grinder keeps a last-cycle status. Output tries the station first, then internal storage, then drops any remainder at the Agent. The fullness precheck uses empty slots and can be conservative with partial stacks. The tick lacks a common anchor-ownership check. Miner and Farmer check target land, and Grinder has an explicit hostile-target exception. Fuel time, work and drop rules are separate from claim permissions. `Robots.java:398–461,468–494,511–524,545–575,769–797`, `BaseStation.java:165–175`.
- Tier I–V upgrades use configured-in-code money, Essence and charge requirements (first step 10,000, 64 Essence, 2 hours of Charge). The code checks withdrawal success before taking Essence and changing tier, then saves; save failure is currently logged. `robots.yml` stores bot ID, owner, job, position, tier, fuel, charge, XP, inventory and the existing Agent record. Unloaded worlds stay pending. Placement and entity loading already have duplicate checks, and pickup carries the record on the item. The `robots` chest-trip settings are documented placeholders; the debug-log key is read. Preserve the record as existing data. `Robots.java:59–74,133–145,197–229,266–296,380–389,800–831,988–1054`, `config.yml:140–153`.

### Hoppers, money, and shared modes

- Sneak-right-click a block hopper with an upgrade to apply. Defaults are delays of 8/4/1 ticks (nominal 2.5/5/20 items/sec at 20 TPS), 1 then 10 tokens, 20,000 money and 128 Essence per token, and 16 upgraded hoppers per chunk. Only I→II adds a slot; placing a tagged upgraded hopper also checks the cap. The existing block open message covers upgraded tiers. Vanilla does the actual transfers; the plugin shortens cooldown. Tier is stored on the item and block, with a chunk index loaded on demand. The normal break path replaces a vanilla drop; a separate wrong-tool fallback drops a tagged item. Disabled speed, break/re-place, cap and restart need gameplay verification. `HopperUpgrades.java:47–89,123–209,213–233,253–318`, `HopperTiers.java:10–29`, `config.yml:131–138`.
- The Survival transfer handler compares **owners**, so different owners or claim versus wilderness fail, even where a player is trusted. Same-owner neighboring chunks can transfer, as can wilderness-to-wilderness. The handler examines inventory source and destination locations (missing location is treated like wilderness) and checks both halves of any double chest. There is no explicit hopper-minecart or loose-item pickup branch in that handler. Paper's actual minecart event routing, current position, and reach at borders require staging. The cart cases are a release gate. `Claims.java:77–100`.
- Vault is wired after startup and the registered provider is used at purchase time. The included Vault bridge delegates to `NetEconomy`, whose withdrawal rechecks funds under the shared account lock. Survival has its own wallet. Skyblock switches wallets by Skyblock/OneBlock group, and both use shared Agent, station and hopper classes with BentoBox member and island-region predicates. The Foundry has separate free, sandbox paths and a different cap. Preserve the existing `alloycore` persistent namespace. Account atomicity covers a wallet file. A purchase also touches inventory, world or Agent data, so crash recovery across those writes remains unverified. `SurvivalModule.java:173–186,245–250`, `VaultHook.java:41–47`, `NetEconomy.java:39–65,111–132,171–185`, `SkyblockModule.java:75–78,111–135,203–223`, `FoundrySystems.java:24–38`, `BlackForgeCore.java:23–27,83–90`.

## Counterplay and theme

Use the Forge's existing gold and charcoal for menus and action prompts. Borders use green for yours, aqua for trusted, red for someone else's, and gray for wilderness. Include the category in text and distinguish a pending outline with corner posts and a countdown. A new player learns from the existing spawn and RTP prompts. A returning player can use chat commands or the station summary with a plain-menu fallback.

The main abuse cases are racing a preview, clicking an old menu after losing trust, a mixed-owner double chest, duplicating a purchase through inventory shortcuts, and claiming land out from under a working base. Confirmations bind the actor, action, target and current state and expire. Every mutation still goes through server-side rules. Claim borders are informational and provide no combat or protection effect.

### Visualization choice

| Option | Fit |
| --- | --- |
| Existing chat map and one-shot dust | Already available without a resource pack. Keep both as quick checks and fallbacks. Use the existing per-viewer geometry as the starting point. `Claims.java:198–274`. |
| Nearby viewer-only particles | Recommended. Refresh a bounded view as players move. Easy to stop by ceasing emission, with no blocks or entities to clean up. Particle settings and elevation affect visibility, so pair it with text. |
| Client-only block markers | The repo already uses per-viewer fake block changes for the welcome show. They can be crisp, but claim previews would need to restore true block states on movement, changes and chunk sends. False collision cues are confusing at a build site. Defer. `hub/WelcomeShow.java:229,286`. |

## Phases and tasks

### Staging fixture and evidence contract

Use an isolated staging copy of the current baseline and later test each task revision. A and B are ordinary Survival players with distinct wallets. A owns adjoining chunks A1 and A2, B owns a neighboring chunk B1, and a wilderness chunk W borders A2. Place A's station in A1, an A-owned Agent in A2 within 16 blocks of it, and a second A-owned Agent out of range. Include charged and empty Agents, full and partly full inventories, paired containers and hoppers on both sides of the three kinds of borders, and a hopper minecart on test rails. Prepare a pre-update save fixture with real item, block and entity tags, both wallets and other-mode inventory, including an already upgraded hopper. Use a separate fixture or restore the staging snapshot between ownership tests.

For paid success and refusal, record exact before/after money, Essence, charge, item counts, tier and output. Use controlled wallet and save failures on staging. Record explicit exceptions for unsettled transactions and stop release until reconciled. Simulate concurrency on copied accounts only. Capture the test revision, settings, player roles, commands/actions, observed result and evidence references. Each task owns its evidence and must pass on the relevant staged revision, including a check with the feature flag off where it exists.

### Phase 1 — Protection and claim foundations

**Goal**

Close the verified stale delivery gap and establish one authoritative claim evaluator and save result.

**Blast radius**

Survival delivery and claim mutations. Shared station behavior for other modes is a regression check.

**Rollback**

Keep existing YAML schemas and block tags. A targeted revert can restore the claim evaluator. If reverting the delivery change, stop delivery at affected staging/production sites through an approved mitigation before reintroducing the known gap.

**Phase gate**

Approve after stale delivery is blocked, baseline trust and transfer checks pass or are explicitly triaged, and claim write failures leave durable and in-memory state consistent.

#### [x] P1.T1 — Revalidate Base Station delivery after land changes

- **Needs** — Current main, approved ownership decision, and the staging fixture. Start by reproducing the old-owner delivery gap before patching.
- **Files** — `BaseStation.java#stationFor,deliver,hasRoom`, Survival opt-in in `SurvivalModule.java`, focused verification alongside the plan entry.
- **Steps** — Resolve the live region at both endpoints before exposing a station to output or capacity checks. Require both to match the saved station key after world, radius, loaded-chunk and marker checks. Skip a mismatched record without deleting, relabeling or scanning for it. Preserve fallback to Agent storage. Keep this guard scoped to Survival.
- **Edge cases** — Station unclaimed or reclaimed, Agent chunk still owned, replaced barrel, unloaded station chunk, different world, same owner's separated claims, already full internal storage.
- **Done when** — With the fixture running, A generates a known item and the barrel count rises by that amount. A unclaims A1 and B claims it. Run the same output again. B's barrel receives zero and the item takes the existing Agent fallback path. A regains A1 and delivery resumes to the original barrel. Repeat with the station chunk unloaded and verify zero new chunk tickets. With B trusted, open the Forge, then A runs `/untrust B` from another client. B clicks a purchase. Expect closure and zero debit/items. Repeat in the real barrel using shift-click and drag. Expect the barrel and guest inventories to remain unchanged. Run one normal Skyblock delivery and one Foundry delivery.
- **Leaves ready** — A trusted station location for roster and status work, plus a baseline record of the stale-menu checks.
- **Evidence** — Local review and two-player staging passed November 4, 2026 on Paper 26.3 build 222. Candidate code was the P1.T1 diff on base 236f7d08. Tested jar SHA-256 was 29EB15053903DEA6FDA571321088872B5E28798A2B7CD837D86FBD3EEAA957A7. All 272 production Java sources compiled. The focused offline check reproduced stale delivery on the baseline and passed all ten assertions on the candidate. No actionable issues were found in review.

  | Staging check | Observed result |
  | --- | --- |
  | Claim change and recovery | A's barrel received 3 diamonds. It stayed at 3 after unclaim and after B claimed it. Agent storage received both refused stacks, totaling 6. When A reclaimed the chunk, the same barrel received 3 more. |
  | Unloaded station | The chunk stayed unloaded and plugin tickets stayed at 0. Agent storage reached 9 diamonds. |
  | Trust revoked with Forge open | A sent /untrust from the second client. B's purchase click was cancelled and the Forge closed. Balance and both inventories were unchanged. |
  | Trust revoked with barrel open | Paper cancelled B's shift-click and two-slot drag and closed the barrel in both runs. The barrel kept all 6 diamonds and the 8 cursor sticks returned to B. |
  | Skyblock and Foundry | A's BentoBox island station and the real Foundry Forge each received 3 diamonds with zero fallback. |

  Two connected headless clients exercised the command and inventory gestures. A local fixture supplied a counted stack through the production Agent delivery path. Agent job scheduling and a rendered client were outside this check. All three local runners exited 0. After integrating upstream Forge Allays changes from main a9a9013, candidate a3d8026 compiled all 276 production sources and passed the same Survival ownership and trust-revocation checks, Skyblock delivery, and Foundry delivery on isolated Paper. All three runners exited 0. Integrated jar SHA-256 was A2B9147AA5B1F4CE26BD0C50AE9EAC04079A068ADA14567C8B6C36BDBE721CEC.

#### [x] P1.T2 — Reuse claim eligibility and report save outcomes

- **Needs** — P1.T1 merged.
- **Files** — `Claims.java#command,nearProtectedArea,allowance,save`, narrowly scoped claim-rule and persistence tests.
- **Steps** — Extract the existing eligibility checks into a reusable server-side result with world/chunk, owner relationship, reason and allowance. Preserve rule order and End's player-position check. Write a candidate claim/trust snapshot to a sibling temporary file and atomically replace the existing file. Publish the candidate in memory and show success only after the replace succeeds. Serialize mutations on the server thread. Preserve the keys and UUIDs and report a retryable failure if replacement fails.
- **Edge cases** — Negative coordinates, exact protected thresholds, Grove/Scrapyard/KOTH, allowance reached and admin bypass, owner-wide trust across worlds, save unavailable, two players contending for one chunk.
- **Done when** — Compare baseline and candidate at protected boundaries, including inside/outside the End cutoff. At allowance, a regular player is refused while an admin retains the existing exception. A and B attempt the same free chunk in the same tick and exactly one succeeds. Inject a pre-replace write failure for claim, unclaim and trust. Verify no success message, no permission or count change, and identical reload from disk. Restore writes and verify one successful retry survives a restart.
- **Leaves ready** — One eligibility and mutation path shared by preview, confirm, and feedback.
- **Evidence** — November 4, 2026, on isolated Paper 26.3 build 222. Java 25 compiled all 280 production sources. The staged jar SHA-256 was 08C9ECA97DD8A499A85F4099BE4B53C3636E2E2D6572B843B122429EB1583CF1. In one server tick, A and B tried to claim the same free chunk. A succeeded once and B was refused. An injected atomic-replace failure blocked claim, unclaim, trust and bulk unclaim. All four attempts returned a retry message, preserved ownership and trust, and left the original YAML bytes intact. No temporary save files remained. Once writes were restored, a single claim retry and a trust grant succeeded and reloaded after restart. The fixture matched the prior protected-zone rules at 1,764 positions around spawn, the Grove, KOTH and the Scrapyard. It also checked the exact End cutoff, negative chunk coordinates, trusted land and the allowance rule for regular players and admins. The two successful staging runners exited 0.

### Phase 2 — Claim and border experience

**Goal**

Make it obvious what a player can claim, what the claim includes, and why an action was refused.

**Blast radius**

Survival claim commands, player feedback and viewer-only visuals.

**Rollback**

Disable the Survival QoL flag and restart through the approved process. Valid claim and trust writes remain readable by the legacy flow.

**Phase gate**

Two ordinary players complete first claim, expansion, trust and safe removal. Overlay cleanup and packet budgets pass.

#### [x] P2.T1 — Preview, confirm, and manage land in the existing commands

- **Needs** — P1.T2 and approval of the command behavior.
- **Files** — `Claims.java#command,show,map`, `SurvivalModule.java#registerCommands,reg,tryRtp,spawnHolograms`, config flag.
- **Steps** — `/claim` and `/claim here` show exact inclusive block bounds (16×16, full world height), world, eligibility, used/total, and remaining after the claim. Show a single-chunk preview and one clickable `/claim confirm <code>` with the same command printed for typing and `/claim cancel`. Generate a fresh one-use code for each preview so an old clickable line cannot confirm a newer request. Expire in 20 seconds. Bind to actor, action and exact world/chunk, replace on new request, invalidate on exit from the chunk for single-chunk actions, and rerun all checks before committing. Use the same flow from adjacent wilderness, retain the map, add world names and coordinates to the list, and use `/claim info` for the trust roster and command hints. Keep `/trust <name>` and `/untrust <name>` and state their owner-wide scope. Preview single and bulk unclaim with a warning that other players may gain access to the area and containers, and that dependent automation may stop. Keep the exact ownership and staff rules at commit. Bind bulk confirmation to the exact set of caller-owned keys and reject a changed set. Put the full guide below in `/claim help`. For a new Survival player, show the short tip once on the first successful `/rtp` arrival that passes the same server-side claim eligibility check as `/claim`. Recheck the actual landing chunk on the server thread after teleport completes. Record pending onboarding by player UUID on their first Survival join. Persist the state so an unshown tip can carry across sessions and a shown tip stays dismissed across reconnects and restarts. Failed teleports and ineligible landings leave the tip pending. Skip onboarding for a player who has already made their first claim. Honor the Survival QoL flag.
- **Edge cases** — `/claims`, namespaced aliases, stale link, quit/death/world change, another player wins the chunk, rank change, wrong chunk, exact negative chunk bounds, staff removing someone else's chunk, unclaiming a chunk with machines.
- **Done when** — At staging spawn `/claim` explains the protected area and points to `/rtp`. After RTP, invoke `/claim` on free land and verify allowance and all four edges, with zero changes until confirm. Confirm once, then replay and verify exactly one saved claim. Open a different preview and replay the first link again. The new preview must remain pending. Cross into neighboring wilderness and confirm another chunk, checking allowance decrements once. Have B claim a previewed chunk first, then confirm as A and expect zero ownership changes. Test timed-out and cross-world confirmation, trust/untrust in two A-owned chunks, and both single and bulk unclaim aliases. Confirm bulk only after a fresh list if any claim changed. As a fresh player, open `/claim help` and follow the full guide. Cancel an RTP and simulate a target becoming claimed before arrival. Expect zero tips and onboarding still pending. Complete an eligible RTP and expect the short tip once. Repeat after reconnect and restart and expect no duplicate. Verify an established player and a newcomer who already made a claim receive no first-claim tip. Confirm the guide lists `/claim show` and `/claim map` before P2.T2, and adds `/claim borders` only when that toggle is available. With the flag off, verify legacy command behavior and no new tip.
- **Leaves ready** — A complete command-only first-claim loop that requires neither a station nor a pack.
- **Evidence** — November 4, 2026, isolated Paper 26.3 build 222. Candidate c315a73 includes upstream 33bcdd7. Java 25 compiled all 280 production sources. Jar SHA-256 F3B03C9DE54F6D07CA405D96EBAA4C02C2C7C433883F013554147FAB0132DEF9. Two headless clients verified clickable confirmation actions, typed codes, negative-coordinate bounds, all four preview corners visible only to the claimant, one-chunk expansion and remaining allowance. Stale, replayed, expired, moved and cross-world requests were refused. A concurrent claimant won the chunk, trust changes worked, and single and bulk release required confirmation. A stale bulk snapshot was refused after an admin removed one chunk. Cancelled RTP and a landing claimed after target selection left the tip pending. The first eligible landing after reconnect showed it once. After restart, the existing claim loaded and the tip stayed dismissed. With the flag off, legacy help, immediate claim and unclaim, and RTP without the new tip all passed. Enabled, reload and disabled staging runners exited 0. The fixture used server commands and observed particle packets. No rendered client was used.

##### Approved `/claim help` guide

**Claiming land in BlackForgeMC Survival**

1. **Find a spot.** Use `/rtp` to head into the wild. You can claim eligible wilderness away from protected areas.
2. **Preview your land.** Stand where you want to build and type `/claim`. Each claim covers one **16 × 16 chunk** from the bottom of the world to the top. Check the outline before you confirm.
3. **Confirm the claim.** Click **Confirm Claim** or type the confirmation command shown in chat. You’ll see how many claims you have left. Only you and players you trust can build in your claim.

**Need more room?** Walk into the next chunk and type `/claim` again. If you’ve used all your claims, `/claim info` shows your allowance. You earn more as you play.

**See your borders.** Use `/claim show` to outline the current chunk or claim, `/claim map` for a chat map, or `/claim list` to find your own chunks. When the P2.T2 toggle is available, add “`/claim borders` toggles nearby outlines. Green is yours, aqua is land you’re trusted on, red belongs to someone else, and gray is wilderness.”

**Build with a friend.** Use `/trust <player>` to give them access to **all** your claims. Use `/untrust <player>` to remove that access.

**Give up land.** Stand in the chunk and type `/unclaim`. Review the location before confirming. Releasing a chunk can give other players access to the blocks and containers there.

##### Approved first RTP message

> **Ready to build your first base?** Find a spot you like and type `/claim`. You’ll see the exact chunk you’re about to protect. Check the outline, then click **Confirm Claim**. If your build needs more room, step into the next chunk and repeat. Type `/claim help` anytime for borders, trust, and more.

#### [ ] P2.T2 — Toggle nearby borders and explain denials

- **Needs** — P2.T1 preview and category results.
- **Files** — `Claims.java#show,map,deny,onMove,onQuit` and player/world lifecycle handlers, `SurvivalModule.java#reg,protectedWhy`.
- **Steps** — Add `/claim borders` to toggle a nearby overlay. Reuse one viewer state per player for preview and toggle, show shared-category transitions without drawing interior seams between same-owner chunks, and keep the preview outline exact. Use a 48-block draw distance, at most 160 particle spawns per viewer per 10 ticks and a 2,400-spawn budget per 10 ticks across viewers, prioritizing preview corners and nearest edges. Use claim keys and loaded-state checks, no terrain loads. Stop on toggle-off, quit, death or world change and clear on shutdown. Add text categories to the movement banner and throttled reasons at existing break, place, container and protected-area denials. Do not reveal foreign contents.
- **Edge cases** — Minimal particles, overlapping show/preview/toggle, far edge at different elevation, trust revocation while stationary, many viewers, cancelled event and protected spawn.
- **Done when** — Walk A and B past A1, A2, B1 and W. B sees trusted edges after A grants trust, then foreign edges within two refreshes after revocation. A sees own edges and no interior seam between A1 and A2. Disable B's toggle while A's remains on, then world-hop A. Expect no further emissions for that viewer, no markers to a third non-viewer, zero marker blocks/entities, and no added chunk loads. Instrument 20 simultaneous viewers to check the stated budgets. With minimal particles the map and action text remain sufficient. Try breaking, placing and opening a protected container as B and verify the refusal reason and zero mutation.
- **Leaves ready** — Reusable land summary and category text for the station.
- **Evidence** — Side-by-side views, packet/spawn counts and denied action checks.

### Phase 3 — Agent status and safe upgrades

**Goal**

Let players find the Agent that needs attention and see the actual upgrade requirements before spending.

**Blast radius**

Survival Agent work anchor, station Agent cards, and Survival Agent upgrade flow.

**Rollback**

Disable new surfaces with the flag. Revert the narrow anchor guard separately only after reviewing the displaced-Agent case. Current YAML, IDs and items remain readable.

**Phase gate**

Owner, guest and displaced-Agent checks pass. Failed and repeated upgrade confirmations conserve money, Essence, Charge and items.

#### [ ] P3.T1 — Show local Agents and diagnose work

- **Needs** — P1.T1 delivery resolver, P2.T2 phase gate, and approval of the anchor guard.
- **Files** — `Robots.java#tickBot,agentsOf,forgeIcon,mayUse,open`, `BaseStation.java#forge,stationFor`, `SurvivalModule.java` ownership wiring.
- **Steps** — List only the viewer's Agents that match this station's current world, region and radius for the local group. Group the viewer's other known Agents separately with a reason and coordinates. Show job, tier, remaining tank fuel, current availability, storage and next action. Derive unloaded/claim changed before reading stale tick status. Report storage full, needs water, no fuel, last observed job result or ready/waiting as supported by the tick path. Derive last observed job results from actual cycles without scanning extra chunks. Label fuel as tank fuel because burn rate and wall-clock time can differ. In Survival, pause work and fuel use when the Agent's anchor is outside its recorded owner's live claim, without changing target rules. Keep actions under existing permissions. Link players to the on-site menu for single/all fuel, collect, and job/pickup. Use coordinates and `/claim borders` for locating.
- **Edge cases** — Remote world, unloaded chunk/entities, absent station, partly filled stacks, storage overflow, foreign land after reclaim, max tier, admin and read-only viewers. Keep saved pending-world records without loading them.
- **Done when** — A sees the nearby Agent in the local list and the far Agent with its world and coordinates. Trusted B sees their own eligible entries only and no A output. Empty fuel, fill both stores, and remove adjacent water for a Fisherman in separate runs. Expect the right explanation and existing pause behavior. Unload the Agent chunk, reopen the station and verify an unloaded status with zero new chunk loads. Transfer the Agent's chunk to B, verify displaced status for A, no work or burn, and no new remote permissions. Restore A's ownership and verify normal work. On site, shift-refuel and collect into a nearly full inventory, checking remainder is retained and repeated clicks conserve counts. Verify Skyblock island member and Foundry use.
- **Leaves ready** — Stable Agent IDs and truthful snapshots for the upgrade confirmation.
- **Evidence** — Status captures, fuel/charge and item counts, loaded chunks and cross-mode results.

#### [ ] P3.T2 — Confirm and safely settle Agent upgrades

- **Needs** — P3.T1 and phase ordering.
- **Files** — `Robots.java#upgrade,upgradeFromStation,onMenuClick,save`, `BaseStation.java#onClick`, focused upgrade/confirmation checks.
- **Steps** — Use the same preview/confirm flow from the Agent and local station. Show tier change, job-specific gains where applicable, and Charge, Essence and Survival wallet requirements. Bind one in-memory request to actor, source and Agent ID/current tier, expire it in 20 seconds and consume it before mutation. On confirm check live station and Agent authority, location/region, enabled path, funds and requirements. For a direct Agent menu require a loaded Agent, same world and within six blocks. For Forge require the same proximity to the live station and the Agent to still qualify as local, loaded and undisplaced. Stage upgraded Agent data and inventory deltas before debit. Make this save report success and use atomic replacement of the existing Agent YAML, preserving pending records and the existing schema. If debit fails, apply nothing. If subsequent persistence fails, restore the Agent and Essence and check the refund result. On unresolved refund failure, record actor/target/amount with an operation ID, block repeat purchase for that actor/target during reconciliation, and escalate. Do not add a new wallet store. Retain the existing distinction between an in-process failure and a process crash across stores.
- **Edge cases** — Tier changed, Agent picked up, old click replay, source replaced, unavailable wallet, missing Essence or charge, custom UI off, full output storage, unexpected click types.
- **Done when** — Confirm a Tier I→II at defaults and verify exactly 10,000 money, 64 Essence and two hours of Charge spent once, with one tier increase and contents intact. Before confirming, remove funds, move the Agent, or revoke B's station access in separate runs. Expect zero charge, tier and output deltas. Inject withdrawal refusal and then a post-debit save failure, checking the compensating balance and exact restored data. Double-click, number-key and drag across both menus. Confirm that there is no extra tier, copied item or second withdrawal. Restart after success and verify the same Agent, tier, contents and record.
- **Leaves ready** — A checked confirmation pattern for purchases, with a single upgrade authority.
- **Evidence** — Purchase and failure ledger, restart fixture diff and stale-menu checks.

### Phase 4 — Hoppers and the station home screen

**Goal**

Put hopper information at the block and connect the existing station tools with a small first screen.

**Blast radius**

Survival hopper inspection, transfer diagnostics, Forge navigation and station-related purchases.

**Rollback**

Disable the UI flag and keep the existing tier mechanics, tags and transfer guard. No inventory or world conversion.

**Phase gate**

Complete the transfer matrix including real carts, prove no item duplication, and pass purchase, trust revocation and shared-mode regressions.

#### [ ] P4.T1 — Inspect hoppers and explain blocked transfers

- **Needs** — P3.T2 and the P1 fixture with working cart rails.
- **Files** — `HopperUpgrades.java#onOpen,onUse,onPlaceCheck,onBreak,onDrop,tierAt,countInChunk`, `HopperTiers.java`, `Claims.java#onInventoryMove,ownsStock,onChestOpen`, `SurvivalModule.java`.
- **Steps** — When an authorized player opens a block hopper, show tier I–III, effective configured rate, next-tier tokens and money/Essence equivalent, and used/max upgraded slots. Explain maximum tier, disabled speed, required tokens and full chunk before taking anything. Explain pickup and re-place behavior. Surface a recent **observed** claim cancellation at an accessible hopper via a diagnostic cache capped at 512 recent block hopper locations per Survival backend, without scanning neighbors, exposing foreign inventories, or announcing every tick. Report redstone lock only if read from the block and avoid claiming a diagnosis where no event was observed. Preserve #90's equality rule and both-half checks. If staging shows a genuine transfer bypass, stop this task and obtain a scoped fix and approval before launch.
- **Edge cases** — Full destination, empty source, powered hopper, low TPS, both directions, mixed double chest, mobile cart endpoints, disabled upgrades with existing tagged tiers, creative and wrong-tool breaks.
- **Done when** — Open Tier I, upgrade to II and III, and verify labels show the configured cost/rate and actual token deltas (1 then 10 at defaults). At 16 upgraded hoppers, block the 17th I→II and tagged placement while allowing II→III. In Survival mode break an upgraded hopper with a pickaxe and separately with the fallback tool, then re-place the single tagged drop and restart. Verify one tiered item per break and the same tier after restart. Check disabled mode and confirm the tier is retained with vanilla speed. For each transfer direction, start with one uniquely identifiable stack and count source, destination and cart totals. Same-owner neighboring chunks and two wilderness chunks pass. A→B, B→A, claimed↔wild, and transfers through either half of a mixed-owner or claim/wilderness double chest fail, even with mutual trust. Separately open the double chest from both sides as a player with access to only one half and expect refusal. Grant access to both halves and expect opening to succeed while automated transfers across owners still fail. Repeat using a hopper minecart as source/destination at the actual event-time positions, in both directions and at the seam. Test cart pickup of dropped items near a seam separately and record the event path and outcome. Claimed inventory stock must remain inaccessible across the boundary. Document ordinary rail cargo movement separately from a transfer. At an accessible block hopper verify the observed block reason expires after 15 seconds and no unsolicited chat is sent.
- **Leaves ready** — Verified on-block instructions and tier/cost data for the station page.
- **Evidence** — Transfer matrix with event endpoints and counts, measured cooldown observation, cap/drop/restart results and diagnostics captures.

#### [ ] P4.T2 — Make the station a home screen and confirm purchases

- **Needs** — P2.T2, P3.T2 and P4.T1 on main.
- **Files** — `BaseStation.java#onOpen,forge,shopIcon,buy,buyUpgrades,pay,denyStorage`, `Robots.java#openShop,shopClick`, `StationUi.java`, `SurvivalModule.java`.
- **Steps** — Right-click opens a small home screen with local land info, Agents and hopper shop. Give each destination a clear back action. Keep the real barrel at empty-hand sneak-right-click and show that shortcut. Use a plain readable menu even without art. Confirm the station purchase from `/agents` and hopper token quantities 1 and 10 with total wallet and Essence costs. Reuse P3.T2's one-use confirmation rules and current station/shop gates. Require the player to remain within six blocks of the originating station for station-menu orders. Require room for the full order and stage inventory changes before withdrawing, including slots freed by Essence. On refusal or recoverable failure, leave both inventory and wallet whole. Recheck the Survival wallet at confirm and check refunds before reporting resolution. Leave existing fuel shop purchase behavior in place and retain fuel and Agent actions at their current entry points.
- **Edge cases** — Loss of trust between home and confirm, station broken/replaced, wrong world, full inventory, missing provider, balance changed, stale menu or old page, tagged item safety, pack declined.
- **Done when** — From a placed station reach each destination in one click, and get back in one click. Verify exact station, 1-token and 10-token prices (default 25,000 + 128, 20,000 + 128, and 200,000 + 1,280). Confirm only one order on repeated click and check exact tagged counts. Test insufficient Essence, full inventory and injected withdrawal failure with zero net money, Essence or item change. B opens station home and reaches hopper confirmation while trusted. A revokes trust. B confirms and then tries shift-click, number-key and drag against the old menu. Expect closure, zero charge, and zero item change. Repeat with an open barrel. Verify A can still use storage and eligible purchases, and that Skyblock, OneBlock and Foundry retain their current menu and pricing paths.
- **Leaves ready** — Complete flagged first release for end-to-end staging.
- **Evidence** — Click paths, transaction counts, revoked-trust views, plain UI and cross-mode captures.

### Phase 5 — Launch and verify existing bases

**Goal**

Approve a staged first release with a tested recovery route and a production activation decision.

**Blast radius**

Survival player surfaces. The shared plugin deploy path can affect all backends.

**Rollback**

Pre-stage flag-off configuration and a reviewed revert that retains P1 and #90. Turn off the UI through the approved config/deploy path, verify the effective setting after restart, and smoke-check legacy claim, station, Agent and hopper use. Retain all saves and reconcile unsettled purchases individually. Restore a data snapshot only for confirmed corruption with Colin's approval and a record of subsequent writes to reconcile.

**Phase gate**

Colin approves the candidate, activation method and window after seeing the evidence. Close after separately authorized smoke checks and rollback readiness.

#### [ ] P5.T1 — Stage, activate and validate the first release

- **Needs** — All prior tasks merged and phase gates recorded. Colin approves the specific publishing and activation operations.
- **Files** — `docs/plans/survival-claims-base-qol.md`, Survival flag definition and only the approved deployment/config surface. Inspect `tools/deploy.py:80–100` and `tools/autoupdate.py:23,61–84` for flag and restart behavior before choosing the activation change.
- **Steps** — Verify candidate off, on and off again using the real staging deployment path. Take a consistent snapshot of staging worlds, plugin data and shared wallets. Complete the old-save and restart pass. Verify active config after restart and agree a durable production override or a Survival-only release default with an explicit tested off switch. Review exact revision and affected backends before activation. Draft Survival news for separate approval. Suggested text for `/news add survival` is “Use /claim to preview your land and /claim borders to see nearby boundaries. Open your Base Station for Agent status and hopper upgrade costs.” Source documents a possible Discord relay, so include it in the approval. `News.java:16`.
- **Edge cases** — Deploy resets config, backend restart with open menu, pending world, vault failure, old station in a different claim, same UUID in two modes, pack declined.
- **Done when** — Load the pre-update fixture, run the first-claim and owner/guest loops, then restart. Existing claims, trust, station marker and contents, Agent IDs/name/fuel/charge/tier/inventory, hopper tags and tier speed persist as expected. Execute the transfer matrix and a wallet contention test from two staging backend processes, ensuring each wallet keeps only its own changes. Confirm stale sessions cannot be replayed after restart. Compare Skyblock/OneBlock island permissions and wallet spending, and Foundry free use. Toggle off through the actual staging restart path and confirm old interfaces with current data. After specific production approval, check the effective mode and flag, complete a bounded authorized smoke pass and roll back on any verified loss, duplication, cross-claim leakage or mode regression.
- **Leaves ready** — Documented release and rollback state, with a list of any deferred follow-up and no unresolved release blockers.
- **Evidence** — Revision, effective config, fixture snapshot IDs, full expected/observed matrix, activation approval, smoke result and rollback rehearsal.

## Blockers log

| Status | Item | Clears when |
| --- | --- | --- |
| Approved | Design and narrow ownership guards | Colin approved the recommended defaults on November 4, 2026. |
| Unverified | Active deployed revision and runtime config, Vault provider, minecart endpoint behavior, old-save restart and multiplayer behavior | Isolated staging and the approved release check identify the exact revision, provider, config and results. |
| Unverified | Atomicity across wallet, inventory and Agent/world save during abrupt process termination | Staging documents the failure window and operator reconciliation. Escalate reproducible loss or duplication before activation. |
| Unverified | Durable production flag mechanism and active automerge/restart schedule | Release owner verifies the actual deployment route and records the specific approval and flag-off rehearsal. |

## Change log

- 2026-11-04 — Created the five-phase, nine-task plan from pinned main and verified #90 ancestry.
- 2026-11-04 — Colin approved the defaults. P1.T1 passed code review and local Survival, Skyblock and Foundry staging. The task stays open through the PR gate.
- 2026-11-04 — Colin approved the full claim guide and one-time first-claim tip. Added the copy, trigger and staging checks to P2.T1.
- 2026-11-04 — Resolved the P1.T1 wiring overlap with upstream Forge Allays and repeated the local staging checks before opening the PR.

- 2026-11-04 — Colin authorized the direct main push for P1.T1. Integrated candidate 22ae1c4 on main dbe03d1 compiled all 280 production Java sources and passed the isolated Survival, Skyblock and Foundry staging matrix again.
- 2026-11-04 — P1.T1 landed at e38c01f. P1.T2 passed the save failure, race, boundary and restart checks. Phase 2 stays at the approval gate.
- 2026-11-04 — Colin approved Phase 2 and the P2.T1 push. Implemented and staged claim previews, confirmations, expanded help and the one-time eligible RTP tip behind the default-off Survival flag.

## Later ideas

- Named bases, multi-chunk claim tools, map UI, and per-base trust.
- Remote Agent actions, automatic refueling, work routes, extra storage, and new progression.
- A remote hopper network browser, minecart cargo tracking, and cross-owner transfer policies.
- Extra stations per owner, any economic rebalance, and other game modes.

## Recommended first release

Players type `/claim` to see exactly what they will protect, confirm it once, and see how many claims they have left. They can turn on borders, manage trust with the familiar commands, and review land before giving it up. Their Base Station tells them where their Agents are, what needs attention, and what upgrades will cost. Opening a hopper shows its tier and limits. Players still refuel and collect at their Agents and apply upgrades at the hopper.

Colin's decisions are whether to approve the preview-first commands, private particle borders, the two narrow ownership guards, and the station action split. The recommended defaults are in the decision table. Keep prices, progression, caps and permission grants at their current settings.

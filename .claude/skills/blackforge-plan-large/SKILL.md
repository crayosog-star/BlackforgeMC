---
name: "blackforge-plan-large"
description: "Use when Colin wants a very large BlackForgeMC concept planned across 4+ phases, with a detailed, ordered task document and rules of engagement. For one item or small feature, use blackforge-plan-feature."
---

# Planning a large BlackForgeMC project

The output is one plan document that any later session can follow without this conversation. Write no plugin code in this skill. Run `blackforge-start` first if the repo isn't set up.

## 1. Lock the concept
If the person named a concept, use it. If not, read recent commits on `origin/main` plus `claude/Mode Ideas.md` and `Item Ideas` in the project, then offer 3-5 concepts built on what exists and recommend one.

A concept qualifies only if all of these are true:
- It needs at least 4 phases, and each phase delivers something that can be checked by itself.
- It changes how players spend their time on the network, not just one item.
- It builds on systems that exist or adds a system that later features can reuse.

If it doesn't qualify, say so in one line and switch to `blackforge-plan-feature`.

## 2. Read the code before asking anything
Read the closest existing systems and find where their classes are constructed, so you know what really runs on each server. The live plugin is `plugins-src/BlackForgeCore`. Read only what this concept touches, then note facts with `file:line`. Mark anything you couldn't confirm as **unverified**.

Check for these project constraints:
- Each game mode has its own wallet (`NetEconomy`), so say which wallets the concept uses.
- Data shared between servers lives in files under `shared/`, changed under a lock. Plan for duping and for two servers writing at once.
- New items start Arena only, must join the 5x5 wall, and need unlimited hotbar uses when loading in.
- Items and abilities need counterplay and a theme. Use the rules in `blackforge-plan-feature` sections 3 and 4.
- Arcade modes must follow `docs/ARCADE.md`.

## 3. Ask only relevant questions
A question is relevant only if the answer changes the phases or tasks, the code can't answer it, and no sensible default exists.
- Use one round of at most 4 questions, with a recommended option first. Add a second round only if an answer opens a real fork.
- Don't reopen what Colin already decided.
- If he's away or doesn't answer, use the recommended option and record it under Assumptions in the plan.

## 4. Write the phases and tasks
Use at least 4 phases. **Phase 1 clears blockers** and adds no player-facing content. If there are no blockers, Phase 1 builds the shared foundation instead (data model, config, unit-tested rules). The final phase is the launch: balance, player guidance and turning the feature on.

Document every step of all phases now. Never leave a later phase as an outline or "details to come".

**Order is the main reliability rule.** Tasks run strictly from first to last, and each one must be ready to start when the one before it is finished:
- A task may use only what already exists on `main` or what an earlier task creates.
- No task may depend on a later one. If it does, reorder or split the tasks.
- Name exactly what each task needs and what it leaves ready for the next one.

**Size tasks for how shipping works.** Merged PRs go live automatically, and any `plugins-src/` change restarts all five servers. So:
- One task is one PR, and it must leave `main` working by itself.
- Merge very small related changes into one task, to avoid needless restarts.
- Keep unfinished player-facing work behind a config flag that defaults to off, or inside the Arena or Foundry, until the launch phase.
- Don't assign a task to `plugins-src/AlloyCore`.

Every task uses this format:

```markdown
### [ ] P2.T3: <short action>
- **Needs:** <P2.T2's output, or an existing class at file:line>
- **Files:** <paths to create or change>
- **Steps:**
  1. <specific change, naming classes, methods and config keys>
  2. ...
- **Edge cases:** <death, quit, restart, world change, two servers, duping, EMP: whichever apply>
- **Done when:** <checks that prove it, such as a named unit test or exact in-game steps and the expected result>
- **Leaves ready:** <what the next task can now use>
- **Evidence:** <filled in when finished: PR link and what was actually checked>
```

Each phase also has:
- **Goal:** what's true when the phase ends, in player terms.
- **Blast radius:** servers, saved data and players affected.
- **Rollback:** the flag to turn off, or how to undo it, including what happens to saved data.
- **Phase gate:** in-game checks Colin or a tester must confirm before the next phase starts.

## 5. Create the document
Write it to `docs/plans/<concept-slug>.md`, using this structure:

```markdown
# <Concept name>
**Status:** Awaiting approval | Approved | In progress | Complete
**Next task:** <P1.T1>

## Rules of engagement
<copy the rules below word for word>

## Concept
<pitch in player terms, why it's worth building, and what's out of scope>

## Decisions and assumptions
## Verified facts
<fact with file:line, plus unverified items>
## Counterplay and theme
## Phases and tasks
## Blockers log
## Change log
## Later ideas
```

### Rules of engagement
Copy this block into every plan:

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

## 6. Check it before showing it
Fix anything that fails:
- [ ] There are at least 4 phases, and every phase has its full tasks, rollback and gate.
- [ ] Walking from the first task to the last, every **Needs** item already exists at that point.
- [ ] Every class and file claimed to exist was checked in the code.
- [ ] Every number that may need tuning has a config key.
- [ ] Every **Done when** can be checked by someone, with no vague wording like "works well".
- [ ] Merging any single task leaves live servers working, and unfinished content stays hidden from players.
- [ ] Saved data has a plan for upgrades, restarts and rollback.
- [ ] The rules of engagement are included word for word.

## 7. Get one yes, then publish
Show the concept, a few lines per phase, the task count and any assumptions. Wait for one OK, then set **Status** to Approved and ship the document using `blackforge-ship`. Don't ask again for each phase.

After approval, build tasks in order using `blackforge-build-feature`, and `blackforge-skin-item` where textures are needed. Follow the document's rules and work from **Next task**.
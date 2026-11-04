# Claude skills for this repo

Skills are instructions Claude loads only when they're relevant. Anything in this folder is picked up
automatically when someone works on this repo with Claude Code, so it's the place for knowledge that is
specific to BlackForgeMC and would otherwise be re-explained in every session.

They're plain Markdown, so they're also readable straight from GitHub by anyone who doesn't use Claude.

## What's here

| Skill | What it covers |
|---|---|
| `blackforge-preflight` | Run first, before planning a new item or feature: push access, who merges, open PRs, how it gets compiled, textures and who tests, so nothing blocks the work halfway. |
| `blackforge-render-shot` | Getting a picture of a build, map, boss or scene without launching a server: which of the six renderers to use, how to aim the camera, and what a render can't show. |

## What is deliberately not here

The workflow skills - `blackforge-start`, `blackforge-plan-feature`, `blackforge-build-feature`,
`blackforge-skin-item` and `blackforge-ship` - come from an installed Claude plugin, not from this folder.
Copying one in here under the same name gives Claude two sources for it, so do that only as a deliberate
move of the whole set, not one at a time.

## Adding a skill

Make a folder named after the skill, containing `SKILL.md`:

```
.claude/skills/<skill-name>/SKILL.md
```

with frontmatter and then the instructions:

```markdown
---
name: blackforge-thing
description: Use when ... - name the situations and the words someone would actually say.
---

# Doing the thing
...
```

The `description` is the whole triggering mechanism: Claude reads it to decide whether to load the skill, so
write **when to use it**, not just what it is. Claude tends to under-trigger skills, so lean towards being
explicit about the phrases that should pull it in.

For the body, write down what Claude couldn't work out from the code in a few minutes: this project's rules,
the paths that matter, and the traps. Give the reason behind a rule rather than only the rule, so Claude can
handle the case the rule didn't anticipate. Keep it short - a skill nobody reads to the end is worse than a
short one. Start from an existing skill rather than a blank file.

Then try it on a real task. When Claude makes the same mistake twice, add a line to the skill; that's the
loop that makes these worth keeping.

## One gotcha

`.gitignore` is a whitelist: the root `/*` rule hides every top-level folder unless it has an explicit `!`
line. `!/.claude/` is already there, so skills are tracked - but a new top-level folder won't be.

---
name: blackforge-preflight
description: Use FIRST, before blackforge-plan-feature or any design or coding, whenever someone starts a new BlackForgeMC item, ability, weapon, game mode or feature - "I want to create", "/blackforge-plan-feature", "new item", "let's build". Clears every blocker that could stop the work reaching players (push access, automerge, open PRs, compiling, textures, testing) so it isn't found halfway through.
---

# Preflight: clear the blockers first

Run this before planning starts. Every item below once cost a round trip because it was found late: a PR
that couldn't be pushed, a PR nobody merged, a merge that broke `main`, an item that showed as coal in game.
Fix what you can, and say plainly in your first reply what you can't. Then go to `blackforge-plan-feature`.

## 1. Can this session push?
Attach the repo with `add_repo` (owner `crayosog-star`, repo `BlackforgeMC`, access `push`) and clone the
path it gives. If it's refused, the person needs collaborator access from Colin before any PR can open.
Say so now; the design and code can still be written meanwhile, but don't push to a fork to get round it.

## 2. Will the PR merge itself?
```bash
gh api user --jq .login
grep -n 'login"\].lower()' tools/automerge.py
```
`automerge.py` only takes PRs from the logins on that line. If this login isn't there, a finished PR sits
waiting for Colin or Gav. Changing that line decides who can put code on the live servers, so it's their
call: offer a one-line PR for them to merge, don't merge it yourself.

## 3. What's already live and in flight?
```bash
git fetch origin && git log --oneline -10 origin/main
gh api "repos/crayosog-star/BlackforgeMC/pulls?state=open" --jq '.[] | "#\(.number) \(.title) (\(.user.login))"'
```
If an open PR touches the same files, build on it or say so instead of duplicating it. Note what's on `main`
now, so you can tell later whether something you test in game is the old version or the new one.

## 4. How will it be compiled?
This session can't reach the Paper API, so nothing compiles here: checking syntax does not catch a
missing method. The PR's GitHub build, **build + test + walkcheck**, is the first real compile. Plan for:
- waiting for that build to go green before anyone merges;
- never merging, or asking someone to merge, a PR whose build is red or still running. A hand merge skips the
  tests on the server, and a broken build there leaves `main` broken for everyone (it happened with #71).

## 5. Does it need a texture?
A visible custom item needs its texture in the resource pack, or players see its plain base item (a flint
dagger showed as "coal"). That's a pack change (texture, model, `ItemSkins`, new zip, `deploy.py`), and
merging it restarts every server. Plan it as part of the feature from the start, via `blackforge-skin-item`.

## 6. Who will test it, and how?
Only someone playing can confirm how it looks and feels. Decide now:
- what to try in `/arena`, and whether it needs a second player (anything others see, like display entities
  or hidden items, does);
- that the player rejoins and accepts the new pack after a pack change.

## Hand off
List what's clear and what isn't, in a few lines, then continue with `blackforge-plan-feature`. Anything
unresolved goes under "Open questions" in the plan.

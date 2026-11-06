# PET-001 Orange companion

Base revision `48a2628` on GitHub `main`. Implementation uses the isolated `codex/orange-companion` worktree and the active BlackForgeCore module.

## Acceptance

- `/pet on` and `/pet off` control one companion per player for the current backend session. Repeat toggles are safe, and turning it off restores the equipped cosmetic pet.
- The original orange character follows its owner with a small hop. The model consists of display entities with no attacks, hitbox, mob targeting, inventory effects, or perks.
- The companion says its intro 10 seconds after its first visible spawn, then a random line from its list every 30 to 100 seconds. It says every line before repeating and never says one twice in a row.
- The owner and eligible players within 16 blocks hear each line from the companion. The audience respects pet visibility settings and whether each player can see the owner.
- Death, world changes, and cosmetic suppression remove the visible model. Disconnect and plugin shutdown clear the session. Existing cosmetic visibility preferences apply.

## Scope and verification

Java changes are limited to the cosmetics integration, companion model, and focused tests. Audio uses the existing network resource pack. Build the local pack with `py -3.13 tools/build_pack.py --format 97`. Test and walkcheck reports are under `plugins-src/BlackForgeCore/build`.

In a test game with the updated resource pack, verify the walking and hop, repeated toggles, two owners, a world transfer, damage and mob targeting, and audible lines at random intervals. Cross-backend travel starts a new session.

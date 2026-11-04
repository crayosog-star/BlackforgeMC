# Raft (game mode)

A survival game on the open sea, on its own server (`raft`, port 30006) with a portal in the hub (the harbour gate west of
spawn). Code: `plugins-src/BlackForgeCore/.../raft/`. Staff: `/blackforge raft ...`.

## How it plays
- Everyone joins with `/raft` (the hub portal runs it). A new player gets their own ocean cell and a 3x3 raft, a Hook,
  2 Cups, a Workbench and 4 Foundations. Friends: `/raft invite <name>` (up to six), `/raft accept`, `/raft leave`, `/raft kick`.
- Debris (planks, plastic, scrap, thatch, barrels, fish) drifts past with the wind. Right-click toward it with a Hook to haul it in.
- Thirst 0-20: salt water (scoop with a Cup, right-click the sea) dries you out; a Water Purifier (salt in, fuel in) makes Fresh Water.
- The Raft Workbench lists blueprints: known ones are built there, locked ones are researched with materials. Foundations
  (right-click the water beside the raft) grow it. Crop Plots stay watered for seeds. Sharks circle every few minutes and bite
  swimmers and tiles.
- Steering Wheel + Sail (with the wind, never against it, 2.5 blocks/s) or Motor (any direction, 5 blocks/s, burns fuel). The raft,
  everything built on it and whoever stands on it slide one block at a time and stop at land.
- Story: radio towers 1-3 (a jukebox on top of a mast: unlocks the Motor, the Advanced Hook, then shows Utopia), a Receiver points the
  way to the next one. Utopia's beacon wakes Bruce the shark; killing him ends the run (trophy, diamonds, network broadcast).

## The ocean
One world `raft_ocean`. Each crew has an ocean "cell" 20,000 blocks wide (cell n is centred x = n * 20000); islands (palm, jungle with crops and iron,
wrecks) are planned from the seed and the cell so they are the same for a crew every time; chests are stocked the first time they are opened.
Distances from the raft's start: starter island ~230, towers 1500 / 3200 / 5200, Utopia 7500. The sea ends 9000 blocks out.

## Staff / testing
`/blackforge raft newteam <name>` (a test raft that stays active), `list`, `move <name> <dir> [n]`, `steer <name> <dir|stop> [sail|motor]`,
`station <name> <type> [dx dz]`, `shark <name>`, `debris <name>`, `story <name>`, `loot x y z`, `tp <name>`, `status`.
Config: `raft.sharks: true`. Data: `plugins/BlackForgeCore/raft-teams.yml`.

## Not done / ideas
Nothing here has been played by a real player yet. The Velocity proxy has to be restarted (or reloaded) once to learn the `raft` server.

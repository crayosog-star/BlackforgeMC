# BlackForgeMC

A Minecraft network (Velocity proxy + Paper 26.3 servers) with a custom all-in-one plugin, **AlloyCore**.

Join: **play.blackforgemc.com**

## What's in here

| Folder | What it is |
|---|---|
| `plugins-src/AlloyCore` | The plugin (Java 25, Gradle). Hub, Survival, Skyblock/OneBlock, Factions and the Arcade (17 minigames) all live here. Every spawn, map and course is generated in code. |
| `tools/` | Python scripts that set up, deploy and run the network (`setup_network.py`, `deploy.py`, `netctl.py`), plus Mineflayer test bots in `tools/testbot`. |
| `datapacks/blackforge` | The hub's custom "Inferno" biome (red sky, ash, embers). |
| `website/` | The blackforgemc.com site. |
| `branding/` | Logo, server icon and social card. |
| `docs/`, `*.md`, `README.txt` | How the network fits together, store and vote setup. |

Not included: worlds, player data, logs, third-party plugin jars, and every secret (RCON password, proxy forwarding key, vote token, store keys).

## Building the plugin

Needs JDK 25 and Gradle 9.

```
cd plugins-src/AlloyCore
gradle build          # -> build/libs/AlloyCore.jar
gradle walkcheck      # checks every spawn for traps/lava, parkour jumps and dropper paths
gradle preview        # renders pictures of the builds into previews/
```

# Vote sites: where players vote and how to list BlackForgeMC

Players vote on server-list websites. Each vote helps new players find the server, and Votifier sends it to
Survival, where the voter gets a Spark Key, $250 and 20 Scrap. Every 50 votes starts a vote party.

## Before you can list: the server must be public
Vote sites check that the server is online and reachable before they accept it. They also deliver votes to the
**Votifier port 8192**, so that port has to be reachable too. Finish going public first (port forward or playit.gg).
Claude then:
- sets Votifier on Survival to listen publicly (`host: 0.0.0.0` in `survival/plugins/Votifier/config.yml`)
- adds the DNS records in Cloudflare (`play.blackforgemc.com` + the Minecraft SRV record)

## The 5 sites to list on
These are the biggest, longest-running Minecraft server lists, and all of them support Votifier.

| # | Site | Sign up / add server |
|---|---|---|
| 1 | **Minecraft-MP** | https://minecraft-mp.com, then Register, then **Add Server** |
| 2 | **Minecraft Server List** | https://minecraft-server-list.com, then Register, then **Add Server** |
| 3 | **MinecraftServers.org** | https://minecraftservers.org, then Register, then **Add Server** |
| 4 | **Planet Minecraft** | https://www.planetminecraft.com, then Sign up, then **Submit → Server** |
| 5 | **TopG** | https://topg.org/minecraft-servers/, then Register, then **Add Server** |

Creating the accounts is up to you (Claude can't make accounts or log in for you). Menus may be named slightly
differently on each site.

## What to enter on every site
| Field | Value |
|---|---|
| Server name | BlackForgeMC |
| Server address / IP | `play.blackforgemc.com` (or `blackforgemc.com` if the site supports SRV records) |
| Port | `25577` (for playit.gg, use the port playit gives you) |
| Version | Java 1.13 – 26.3 |
| Game modes / tags | Skyblock, OneBlock, Survival, Factions, Minigames, Economy, PvP, KOTH, Crates |
| Website | https://blackforgemc.com |
| Banner / logo | `website/assets/img/hero-hub.png` (most sites want 468×60 for a banner; Claude can make one) |
| Description | Use the home page text: "Skyblock, OneBlock, Survival, Factions and Arcade on one network, with one economy and one rank that follow you everywhere." |

**Votifier section** (on the server's edit page, sometimes after the listing is approved):

| Field | Value |
|---|---|
| Votifier host / IP | `play.blackforgemc.com` |
| Votifier port | `8192` |
| Public key (Votifier v1) | Open `C:\MinecraftNetwork\survival\plugins\Votifier\rsa\public.key` and paste the whole text. It's a public key, so it's safe to give to vote sites. |
| Token (NuVotifier v2, only if the site asks for it) | `tokens: default:` in `survival\plugins\Votifier\config.yml`. Treat it like a password: only paste it into the vote site's form. |

Most sites have a **"Test Votifier"** button. Use it: you should see "✔ <name> voted on …" in game, and a Spark Key arrives.

## After each listing: send Claude the vote link
Each site gives the server a **vote page**, e.g. `https://minecraft-mp.com/server/12345/vote/`.
Send all five links to Claude, who adds them in two places:
- **In game** (`/vote`): `votes.sites` in `survival/plugins/AlloyCore/config.yml`, then a restart of Survival
- **On the website** (Vote section): `votes` in `website/assets/js/main.js`, then a redeploy

Both currently show the five site names as "At launch" until the links are added.

## Tips
- Votes must come from real players. Sites ban servers that fake votes.
- Remind players with the vote party counter (`/vote` shows it) and say thanks in Discord.
- Rankings reset monthly on most lists, so the first days after launch matter most.

"""BlackForgeMC Discord bot: account linking and rank-role sync (the Discord half of AlloyCore's DiscordLink).

  In game:     /link              -> a 6-character code
  In Discord:  /link <code>       -> accounts linked, roles given, the player is paid in game
               /unlink            -> forget the link (roles removed)

Every few minutes it also re-syncs everyone's roles from what the servers last saw: the Member role for anyone
linked, their earned rank (Copper .. Netherite) and their supporter tier (Ember .. Forgemaster). Roles are matched by
name, so renaming one in Discord breaks the match for it.

Setup (once): Discord Developer Portal > New Application > Bot > Reset Token, then put it in shared/discord.yml as
    bot-token: "<token>"
Invite it with the "bot" and "applications.commands" scopes and the Manage Roles permission, and drag its role above
the rank roles. Run:  python tools/discord_bot.py   (needs discord.py: pip install discord.py)

Files it shares with the servers live in shared/discord/ (JSON, changed under the same .lock files the plugin uses).
"""
import asyncio
import json
import msvcrt
import os
import re
import time
from contextlib import contextmanager
from pathlib import Path

import discord
from discord import app_commands

SHARED = Path(__file__).resolve().parent.parent / "shared"
DIR = SHARED / "discord"
CODES, LINKS = DIR / "codes.json", DIR / "links.json"
CODE_MS = 10 * 60 * 1000
DEFAULT_GUILD = 1564978643069829143
MEMBER_ROLE = "Member"
RANKS = ["Copper", "Iron", "Gold", "Diamond", "Netherite"]
SUPPORTER = ["Ember", "Blaze", "Inferno", "Forgemaster"]
SYNC_SECONDS = 300


def setting(key):
    """A value from shared/discord.yml (simple 'key: "value"' lines; no YAML library needed)."""
    try:
        text = (SHARED / "discord.yml").read_text(encoding="utf-8")
    except OSError:
        return ""
    m = re.search(r'(?m)^' + re.escape(key) + r':\s*"?([^"\n#]*)"?', text)
    return m.group(1).strip() if m else ""


def read(path):
    try:
        return json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
    except (OSError, ValueError):
        return {}


@contextmanager
def locked(path):
    """The same lock the plugin takes (a byte-range lock on <file>.lock) so neither side loses the other's change."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(str(path) + ".lock", "a+b") as f:
        f.seek(0)
        while True:
            try:
                msvcrt.locking(f.fileno(), msvcrt.LK_NBLCK, 1)
                break
            except OSError:
                time.sleep(0.05)
        try:
            yield
        finally:
            f.seek(0)
            msvcrt.locking(f.fileno(), msvcrt.LK_UNLCK, 1)


def update(path, change):
    with locked(path):
        data = read(path)
        result = change(data)
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, indent=2), encoding="utf-8")
        os.replace(tmp, path)
        return result


def player(uuid):
    return read(DIR / "players" / f"{uuid}.json")


class Bot(discord.Client):
    def __init__(self, guild_id):
        super().__init__(intents=discord.Intents.default())
        self.guild_id = guild_id
        self.tree = app_commands.CommandTree(self)

    async def setup_hook(self):
        guild = discord.Object(id=self.guild_id)
        self.tree.copy_global_to(guild=guild)
        await self.tree.sync(guild=guild)
        self.loop.create_task(self.sync_loop())

    async def sync_loop(self):
        await self.wait_until_ready()
        while not self.is_closed():
            try:
                await self.sync_all()
            except Exception as e:   # keep going: a bad member or a Discord hiccup shouldn't stop the bot
                print("sync failed:", e)
            await asyncio.sleep(SYNC_SECONDS)

    async def sync_all(self):
        guild = self.get_guild(self.guild_id)
        if guild is None:
            return
        for uuid, link in read(LINKS).items():
            try:
                member = guild.get_member(int(link["discord_id"])) or await guild.fetch_member(int(link["discord_id"]))
            except discord.NotFound:
                continue
            await self.sync_member(guild, member, uuid)

    async def sync_member(self, guild, member, uuid):
        info = player(uuid)
        want = {MEMBER_ROLE}
        if info.get("rank") in RANKS:
            want.add(info["rank"])
        if info.get("supporter") in SUPPORTER:
            want.add(info["supporter"])
        managed = {MEMBER_ROLE, *RANKS, *SUPPORTER}
        by_name = {r.name: r for r in guild.roles if r.name in managed}
        add = [by_name[n] for n in want if n in by_name and by_name[n] not in member.roles]
        drop = [r for r in member.roles if r.name in managed and r.name not in want]
        if add:
            await member.add_roles(*add, reason="BlackForgeMC rank sync")
        if drop:
            await member.remove_roles(*drop, reason="BlackForgeMC rank sync")
        if info.get("name") and member.nick != info["name"] and guild.me.top_role > member.top_role and member != guild.owner:
            try:
                await member.edit(nick=info["name"], reason="BlackForgeMC: Minecraft name")
            except discord.Forbidden:
                pass


def main():
    token = setting("bot-token")
    if not token:
        raise SystemExit("No bot-token in shared/discord.yml - see the top of this file for setup.")
    guild_id = int(setting("guild-id") or DEFAULT_GUILD)
    bot = Bot(guild_id)

    @bot.tree.command(name="link", description="Link your Minecraft account (get a code with /link in game)")
    @app_commands.describe(code="The 6-character code from /link in game")
    async def link(interaction: discord.Interaction, code: str):
        code = code.strip().upper()
        now = int(time.time() * 1000)

        def take(codes):
            c = codes.pop(code, None)
            return c if c and now - c.get("at", 0) <= CODE_MS else None

        c = update(CODES, take)
        if not c:
            await interaction.response.send_message("That code isn't valid (they last 10 minutes). Type **/link** in game for a new one.", ephemeral=True)
            return
        did = str(interaction.user.id)

        def put(links):
            for k in [k for k, v in links.items() if v.get("discord_id") == did]:
                links.pop(k)   # one Minecraft account per Discord account
            links[c["uuid"]] = {"discord_id": did, "discord_name": interaction.user.name, "name": c["name"], "at": now}

        update(LINKS, put)
        if interaction.guild and isinstance(interaction.user, discord.Member):
            try:
                await bot.sync_member(interaction.guild, interaction.user, c["uuid"])
            except discord.Forbidden:
                pass
        await interaction.response.send_message(f"Linked to **{c['name']}**! Your rank roles are on, and your reward is waiting in game.", ephemeral=True)

    @bot.tree.command(name="unlink", description="Unlink your Minecraft account")
    async def unlink(interaction: discord.Interaction):
        did = str(interaction.user.id)
        gone = update(LINKS, lambda links: [links.pop(k) for k in [k for k, v in links.items() if v.get("discord_id") == did]])
        if gone and isinstance(interaction.user, discord.Member):
            managed = {MEMBER_ROLE, *RANKS, *SUPPORTER}
            drop = [r for r in interaction.user.roles if r.name in managed and r.name != MEMBER_ROLE]
            if drop:
                await interaction.user.remove_roles(*drop, reason="BlackForgeMC unlink")
        await interaction.response.send_message("Unlinked." if gone else "You weren't linked.", ephemeral=True)

    bot.run(token)


if __name__ == "__main__":
    main()

"""Post an announcement (text + screenshots) to the BlackForgeMC Discord #updates channel as the bot.

  python tools/post_update.py channels                         list the text channels the bot can see
  python tools/post_update.py post "Title" "Body text" [img ...]   post it (add --dry to only print what would be sent)

Easiest: in Discord, #updates > Edit Channel > Integrations > Webhooks > New Webhook > Copy URL, then put it in shared/discord.yml as
    updates-webhook: "<url>"
Otherwise it uses the bot (bot-token in shared/discord.yml, as tools/discord_bot.py does). Neither is ever printed.
With the bot, the channel is the one with "update" in its name (override with `updates-channel: "<id>"`).
Only post big things: new areas, bosses, items, seasons.
"""
import json
import mimetypes
import sys
import urllib.request
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from discord_bot import DEFAULT_GUILD, setting   # reuses the bot's settings reader (importing it does not start the bot)

API = "https://discord.com/api/v10"
EMBER = 0xFF6A00


def call(method, path, token, data=None, headers=None):
    req = urllib.request.Request(API + path, data=data, method=method, headers={"Authorization": "Bot " + token, "User-Agent": "BlackForgeMC (https://blackforgemc.com, 1.0)", **(headers or {})})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read() or b"null")


def token():
    t = setting("bot-token")
    if not t:
        raise SystemExit("No bot-token in shared/discord.yml")
    return t


def channels(tok):
    guild = int(setting("guild-id") or DEFAULT_GUILD)
    return [c for c in call("GET", f"/guilds/{guild}/channels", tok) if c["type"] in (0, 5)]


def updates_channel(tok):
    forced = setting("updates-channel")
    if forced:
        return forced
    for c in channels(tok):
        if "update" in c["name"]:
            return c["id"]
    raise SystemExit("No channel with 'update' in its name - run `channels` and set updates-channel in shared/discord.yml")


def post(title, body, images, dry=False):
    hook = setting("updates-webhook")
    tok = "" if hook else token()
    chan = "webhook" if hook else updates_channel(tok)
    send = (lambda data, h: urllib.request.urlopen(urllib.request.Request(hook + "?wait=true", data=data, method="POST", headers={"User-Agent": "BlackForgeMC (https://blackforgemc.com, 1.0)", **h}), timeout=30).read()) if hook else None
    embed = {"title": title, "description": body, "color": EMBER, "footer": {"text": "BlackForgeMC"}}
    files = [Path(p) for p in images[:10]]
    payload = {"embeds": [embed]}
    if files:
        embed["image"] = {"url": "attachment://" + files[0].name}
        payload["attachments"] = [{"id": i, "filename": f.name} for i, f in enumerate(files)]
    if dry:
        print("would post to channel", chan, json.dumps(payload, indent=2))
        return
    if not files:
        (send or (lambda d, h: call("POST", f"/channels/{chan}/messages", tok, d, h)))(json.dumps(payload).encode(), {"Content-Type": "application/json"})
        print("posted")
        return
    boundary = uuid.uuid4().hex
    parts = [f'--{boundary}\r\nContent-Disposition: form-data; name="payload_json"\r\nContent-Type: application/json\r\n\r\n{json.dumps(payload)}\r\n'.encode()]
    for i, f in enumerate(files):
        mime = mimetypes.guess_type(f.name)[0] or "application/octet-stream"
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="files[{i}]"; filename="{f.name}"\r\nContent-Type: {mime}\r\n\r\n'.encode() + f.read_bytes() + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode())
    (send or (lambda d, h: call("POST", f"/channels/{chan}/messages", tok, d, h)))(b"".join(parts), {"Content-Type": "multipart/form-data; boundary=" + boundary})
    print("posted")


if __name__ == "__main__":
    a = sys.argv[1:]
    if a[:1] == ["channels"]:
        for c in channels(token()):
            print(c["id"], c["name"])
    elif a[:1] == ["post"] and len(a) >= 3:
        dry = "--dry" in a
        a = [x for x in a if x != "--dry"]
        post(a[1], a[2], a[3:], dry)
    else:
        print(__doc__)

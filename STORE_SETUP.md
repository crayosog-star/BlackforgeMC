# BlackForgeMC store: Tebex setup

The store page (https://blackforgemc.com/store) and the server side are built. Tebex handles the payments.
Until the steps below are done, the page shows a preview with buying turned off.

## Why the store sells only cosmetics
Mojang's server rules (minecraft.net/usage-guidelines, "Servers and hosting") allow selling cosmetics and perks
that don't give a competitive advantage. Everything must suit all ages, and Mojang says that excludes gambling.
Breaking these rules can get the server blacklisted.

So the store sells:
- **Supporter ranks**: badge, name colour, particle trails, /hat, hub join message
- **Cosmetics**: Name Colour Bundle, Trail Pack

It does **not** sell crate keys, kits, metal ranks (Copper–Netherite), /fly or /repair. Players earn those by playing.
Keys are still tied to the store, through the community goal (Key All): when it's hit, *everyone online* gets a key.
Mojang's rules explicitly allow that.

## 1. Create the webstore
1. Sign up at https://tebex.io → **Create a webstore**.
2. Game: **Minecraft: Java Edition**. Choose **online mode** (username) store. Currency: USD (or yours).
3. Finish **Payouts** (bank/PayPal) and the business details Tebex asks for. Tebex is the merchant of record:
   it handles card payments, sales tax, receipts and chargebacks.

## 2. Packages
Tebex panel → **Packages** → create the categories and packages below. For every command, set
**"Execute even if the player is offline"** and pick the **Hub** game server (step 3), unless it says otherwise.

### Category: Supporter Ranks
Optional: make it a **Tiered category** so players can upgrade and only pay the difference.

| Package | Price | Purchase commands | Expiry / refund / chargeback commands |
|---|---|---|---|
| Ember | $4.99 | `lp user {username} parent add supporter_ember`<br>`alloy thanks {username} Ember Supporter` | `lp user {username} parent remove supporter_ember`<br>`lp networksync` |
| Blaze | $9.99 | `lp user {username} parent add supporter_blaze`<br>`alloy thanks {username} Blaze Supporter` | `lp user {username} parent remove supporter_blaze`<br>`lp networksync` |
| Inferno | $19.99 | `lp user {username} parent add supporter_inferno`<br>`alloy thanks {username} Inferno Supporter` | `lp user {username} parent remove supporter_inferno`<br>`lp networksync` |
| Forgemaster | $39.99 | `lp user {username} parent add supporter_forgemaster`<br>`alloy thanks {username} Forgemaster Supporter` | `lp user {username} parent remove supporter_forgemaster`<br>`lp networksync` |

Each tier includes the ones below it, so only add the one group.

Suggested descriptions, which the website shows:
- **Ember**: ✦ badge in chat, tab and above your head · Ember name colour · Flame + Hearts trails · /hat
- **Blaze**: everything in Ember · Blaze badge + name colour · Notes + Cherry Petals trails · hub join announcement
- **Inferno**: everything in Blaze · ✹ badge + Inferno name colour · Soul Fire + Sparks trails
- **Forgemaster**: everything in Inferno · ⚒ badge + Forgemaster name colour · every trail (Totem, Starlight, Snowfall)

### Category: Cosmetics
| Package | Price | Purchase commands | Refund / chargeback commands |
|---|---|---|---|
| Name Colour Bundle | $2.99 | `lp user {username} permission set alloy.tag.bundle true`<br>`alloy thanks {username} the Name Colour Bundle` | `lp user {username} permission unset alloy.tag.bundle`<br>`lp networksync` |
| Trail Pack | $3.99 | `lp user {username} permission set alloy.trails.all true`<br>`alloy thanks {username} the Trail Pack` | `lp user {username} permission unset alloy.trails.all`<br>`lp networksync` |

## 3. Game servers + the Tebex plugin
1. Tebex panel → **Game Servers** → add three servers: **Hub**, **Survival**, **Skyblock** (type: Minecraft / Bukkit-Paper).
   Each one gets its own **secret key**.
2. ✅ Done 2026-10-26: the official **Tebex** plugin v2.4.6 (github.com/tebexio/Tebex-Minecraft, sha256 db4d4529…5035522)
   is installed in `hub\plugins`, `survival\plugins` and `skyblock\plugins` and is waiting for a secret key.
3. In each server's console, run `tebex secret <that server's key>`, for example:
   `python tools\netctl.py cmd hub tebex secret YOUR-HUB-KEY`
   Do this step yourself: the secret keys give full access to your store.

## 4. Community goal (Key All)
Tebex panel → **Engagement → Community Goals** → new goal, e.g. **$100, repeatable**, named "Key All".
When it completes, run on **Survival** and **Skyblock**:
`alloy keyall molten 1 Community goal reached`
Crates: cinder, molten, forgeheart, conqueror, spark (the old ids iron, gold, netherite, koth, vote still work). Every player online on that server gets the key, and the server announces it.

## 5. Connect the website
1. Tebex panel → **Integrations → API Keys** → copy the **Public Token**. It's meant to be public and safe to put
   in a website. Never share the *Private Key*.
2. Put it in `website/assets/js/store.js`: `const TEBEX_TOKEN = window.BF_TEBEX_TOKEN || "your-public-token";`
   (or send it to Claude), then redeploy:
   `npx wrangler pages deploy website --project-name blackforgemc --branch main --commit-dirty=true`
3. Under **Webstore → Settings**, set the store's **Return URL / Website** to https://blackforgemc.com/store.

## 6. Test a purchase
Tebex has a sandbox/test mode (and 100% coupons). Buy Ember for your own account and check that:
- the hub says "✦ <you> just supported the server with Ember Supporter"
- `/trails` lets you pick Flame, and `/tags` on Survival shows the Ember colour
- refunding it in the panel removes the badge (on other servers it disappears once you switch server or rejoin)

## In-game commands players see
`/store` (link), `/supporter` (tiers), `/trails` (particle menu), `/tags` (name colours, Survival/Skyblock), `/ranks`

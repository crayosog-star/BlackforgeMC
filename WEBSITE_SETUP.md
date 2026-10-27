# Putting blackforgemc.com online (Cloudflare Pages, free)

Site files: `C:\MinecraftNetwork\website`
Upload package: `C:\MinecraftNetwork\blackforgemc-site.zip` (same files, ready to upload)

## 1. Create a Cloudflare account
Go to https://dash.cloudflare.com/sign-up and sign up (free). Verify your email.

## 2. Upload the site
1. Left menu: **Compute (Workers) → Workers & Pages** → **Create** (in older menus this is **Workers & Pages → Create application**).
2. Choose the **Pages** tab → **Upload assets** (Direct Upload).
3. Project name: `blackforgemc` → **Create project**.
4. Drop in `blackforgemc-site.zip` (or drag the whole `website` folder) → **Deploy site**.
5. The site is now live at **https://blackforgemc.pages.dev**. Open it to check.

## 3. Move the domain's DNS to Cloudflare
A domain's root (`blackforgemc.com` with no `www`) can only point to Pages when Cloudflare runs its DNS.

1. Cloudflare dashboard home → **Add a domain** → enter `blackforgemc.com` → keep **Quick scan** → Continue.
2. Pick the **Free** plan.
3. Cloudflare shows the DNS records it found. **Delete** the `A` and `AAAA` records for `blackforgemc.com` and `www` (those are IONOS's parking page).
   Keep any `MX` / `TXT` records if you use IONOS email.
4. Cloudflare then shows **two nameservers** (e.g. `xxx.ns.cloudflare.com`). Keep this tab open.

## 4. Point IONOS at Cloudflare
1. Log in at https://my.ionos.com → **Domains & SSL** → click `blackforgemc.com`.
2. If **DNSSEC** is on, turn it off first (it breaks the switch).
3. Open **Nameservers** (sometimes under the gear/⋮ menu → *Adjust nameservers*).
4. Choose **Use custom nameservers** and enter the two Cloudflare nameservers. Remove any others. Save.
5. Back in Cloudflare click **Check nameservers**. It usually goes active within 5–60 minutes (can take up to 24h). Cloudflare emails you when it's active.

## 5. Connect the domain to the site
After the domain shows **Active** in Cloudflare:
1. **Workers & Pages** → `blackforgemc` → **Custom domains** → **Set up a custom domain**.
2. Enter `blackforgemc.com` → **Continue** → **Activate domain**. Cloudflare creates the DNS record itself.
3. Repeat for `www.blackforgemc.com`.
4. HTTPS is set up automatically, usually within a few minutes.

Done: https://blackforgemc.com shows the site.

## 6. Later: make `blackforgemc.com` the Minecraft address too
Do this when the server goes public. Players type `blackforgemc.com` and Minecraft finds the server through an SRV record. The website keeps working, because web traffic and Minecraft traffic use separate records.

With a port forward of 25577 on your router, add in **Cloudflare → DNS → Records**:

| Type | Name | Content | Proxy |
|------|------|---------|-------|
| A | `play` | your home IP | **DNS only (grey cloud)** |
| SRV | `_minecraft._tcp` | priority 0, weight 5, port 25577, target `play.blackforgemc.com` | — |

The `play` record **must** be grey-cloud. Cloudflare's orange-cloud proxy only handles websites and will block Minecraft.
With playit.gg the records are different. Ask Claude when you get there.

## Status (2026-10-26)
Steps 1–5 are done. Pages project `blackforgemc`, IONOS nameservers set to hazel/huxley.ns.cloudflare.com,
custom domains blackforgemc.com + www added. Wrangler on this PC is logged in to the account.
Verified live 2026-10-27: https://blackforgemc.com (and /store, /rules, www) serve the site over HTTPS.
Note: this PC's router/ISP DNS cached the old IONOS answers for a while; check with DNS-over-HTTPS or a phone on mobile data.

## Updating the site later
From `C:\MinecraftNetwork`, run:
`npx wrangler pages deploy website --project-name blackforgemc --branch main --commit-dirty=true`
(or Workers & Pages → `blackforgemc` → **Create deployment** → upload a fresh zip of the `website` folder).
Discord / store links: edit the `SITE` block at the top of `website/assets/js/main.js`, then re-upload.

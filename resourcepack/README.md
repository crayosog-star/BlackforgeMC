# BlackForgeMC resource pack

One network-wide pack with every BlackForgeMC custom item texture: Gale Bellows, Surge, Potion of Gaia, Potion of
Darkness, God's Eye, Ashen Chain, Ashen Bonfire, Last Rite, Rimelock, Soul Candy.
Players get it when they join the hub; every server sends the same URL + SHA-1, so it downloads once.

## Build

```
python tools/build_pack.py --format <pack format for 26.3>
```

This writes `build/blackforge-pack.zip` and prints its SHA-1.

## Use

1. Copy the zip to `website/pack/blackforge-pack-<first 8 of sha1>.zip` (keep older zips). Servers download it from
   GitHub (`raw.githubusercontent.com/.../main/website/pack/...`), so it is live as soon as it is merged to main.
   blackforgemc.com is a manual Cloudflare upload and is NOT used for the pack.
2. `tools/deploy.py` sets these on every server (update `PACK_URL` and `PACK_SHA1` there):
   - `resource-pack=<zip URL>`
   - `resource-pack-sha1=<SHA-1 from the build>`
   - `require-resource-pack=true` (forced: players who decline are disconnected)
   - `resource-pack-prompt=` a short message explaining why
3. Rebuild and update the SHA-1 every time the pack changes, or clients keep the old one.

## Layout

- `assets/blackforge/items/gale_bellows.json` - item model definition, used by the item's `blackforge:gale_bellows` model
- `assets/blackforge/models/item/gale_bellows.json` - handheld model
- `assets/blackforge/textures/item/gale_bellows.png` - 16x16 texture
- `assets/blackforge/items/gaia_potion.json`, `models/item/gaia_potion.json`, `textures/item/gaia_potion.png` - Potion of Gaia

The pixel art for the generated items lives in `tools/pack_art.py` (16x16 character grids): edit it, run it, then rebuild.

Servers turn textures on with `resource-pack-live: true` in AlloyCore's config (deploy.py writes it). `ItemSkins` then
puts each item's `blackforge:<id>` model on it by its plugin tag, old copies included; add new items to its list.

Without the pack the Gale Bellows still works; it just looks like a named breeze rod.

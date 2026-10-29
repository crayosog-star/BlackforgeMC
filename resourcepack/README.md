# BlackForgeMC resource pack

One network-wide pack with every BlackForgeMC custom item texture: Gale Bellows, Surge, Potion of Gaia.
Players get it when they join the hub; every server sends the same URL + SHA-1, so it downloads once.

## Build

```
python tools/build_pack.py --format <pack format for 26.3>
```

This writes `build/blackforge-pack.zip` and prints its SHA-1.

## Use

1. Host the zip somewhere stable over HTTPS (for example under blackforgemc.com).
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

Servers turn textures on with `resource-pack-live: true` in AlloyCore's config (deploy.py writes it).

Without the pack the Gale Bellows still works; it just looks like a named breeze rod.

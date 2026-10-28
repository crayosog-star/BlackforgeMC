# BlackForgeMC resource pack

Custom item textures for BlackForgeMC. Currently: the Gale Bellows.

## Build

```
python tools/build_pack.py --format <pack format for 26.3>
```

This writes `build/blackforge-pack.zip` and prints its SHA-1.

## Use (not done yet - needs Colin's go-ahead, it changes the live servers)

1. Host the zip somewhere stable over HTTPS (for example under blackforgemc.com).
2. On each backend that uses custom items (survival), set in `server.properties`:
   - `resource-pack=<zip URL>`
   - `resource-pack-sha1=<SHA-1 from the build>`
   - `require-resource-pack=true` (forced: players who decline are disconnected)
   - `resource-pack-prompt=` a short message explaining why
3. Rebuild and update the SHA-1 every time the pack changes, or clients keep the old one.

## Layout

- `assets/blackforge/items/gale_bellows.json` - item model definition, used by the item's `blackforge:gale_bellows` model
- `assets/blackforge/models/item/gale_bellows.json` - handheld model
- `assets/blackforge/textures/item/gale_bellows.png` - 16x16 texture

Without the pack the Gale Bellows still works; it just looks like a named breeze rod.

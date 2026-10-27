// Builds every BlackForge brand file from mark.svg. Usage: node build.js
// Output: tools/brand/out/*  and the website copies in website/assets/img/.
const fs = require("fs");
const path = require("path");
const { Resvg } = require("@resvg/resvg-js");

const here = __dirname;
const out = path.join(here, "out");
const site = path.join(here, "..", "..", "website", "assets", "img");
fs.mkdirSync(out, { recursive: true });

const mark = fs.readFileSync(path.join(here, "mark.svg"), "utf8");
const inner = mark.slice(mark.indexOf(">") + 1, mark.lastIndexOf("</svg>"));
const font = path.join(here, "fonts", "ChakraPetch-Bold.ttf");

function png(svg, file, width) {
  const r = new Resvg(svg, { fitTo: { mode: "width", value: width }, font: { fontFiles: [font], loadSystemFonts: false, defaultFontFamily: "Chakra Petch" } });
  fs.writeFileSync(file, r.render().asPng());
  console.log("png", path.relative(path.join(here, "..", ".."), file), width + "px");
}
const nested = (x, y, size) => `<svg x="${x}" y="${y}" width="${size}" height="${size}" viewBox="0 0 128 128">${inner}</svg>`;

// Horizontal lockup: mark + wordmark (text kept as text; renders with Chakra Petch)
const full = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 770 160">
  ${nested(0, 0, 160)}
  <text x="178" y="98" font-family="Chakra Petch" font-weight="700" font-size="80" letter-spacing="3">
    <tspan fill="#f3efe9">BLACK</tspan><tspan fill="url(#ember)">FORGE</tspan>
  </text>
  <text x="182" y="134" font-family="Chakra Petch" font-weight="700" font-size="20" letter-spacing="9.4" fill="#8f8a83">MINECRAFT NETWORK</text>
</svg>`;

// Social preview card (link previews on Discord, X, etc.)
const og = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1200 630">
  <defs>
    <radialGradient id="bgglow" cx=".82" cy="1" r=".75">
      <stop offset="0" stop-color="#ff5a1a" stop-opacity=".55"/>
      <stop offset=".45" stop-color="#ff5a1a" stop-opacity=".12"/>
      <stop offset="1" stop-color="#ff5a1a" stop-opacity="0"/>
    </radialGradient>
    <pattern id="grid" width="40" height="40" patternUnits="userSpaceOnUse">
      <path d="M40 0H0V40" fill="none" stroke="#ffffff" stroke-opacity=".035" stroke-width="1"/>
    </pattern>
  </defs>
  <rect width="1200" height="630" fill="#0b0b0e"/>
  <rect width="1200" height="630" fill="url(#grid)"/>
  <rect width="1200" height="630" fill="url(#bgglow)"/>
  ${[[640, 520, 3], [700, 470, 2], [930, 560, 2.5], [1010, 430, 2], [1100, 520, 3], [1150, 330, 2], [760, 560, 1.6]]
    .map(([x, y, r]) => `<rect x="${x}" y="${y}" width="${r * 2}" height="${r * 2}" fill="#ffb347" opacity=".7"/>`).join("")}
  ${nested(70, 135, 360)}
  <g font-family="Chakra Petch" font-weight="700">
    <text x="470" y="270" font-size="104" letter-spacing="3"><tspan fill="#f3efe9">BLACK</tspan><tspan fill="url(#ember)">FORGE</tspan></text>
    <text x="474" y="318" font-size="26" letter-spacing="11" fill="#8f8a83">MINECRAFT NETWORK</text>
    <text x="474" y="392" font-size="27" fill="#d9d3cb">Skyblock · OneBlock · Survival · Factions · Arcade</text>
    <rect x="472" y="430" width="336" height="64" rx="12" fill="#16161c" stroke="url(#ember)" stroke-width="2"/>
    <text x="496" y="472" font-size="30" fill="#f3efe9">blackforgemc.com</text>
  </g>
</svg>`;

// Minecraft server-list icon: 64x64, mark only
fs.writeFileSync(path.join(out, "logo-full.svg"), full);
fs.writeFileSync(path.join(out, "og-card.svg"), og);
png(mark, path.join(out, "mark-1024.png"), 1024);
png(mark, path.join(out, "server-icon.png"), 64);
png(full, path.join(out, "logo-full-1320.png"), 1320);
png(og, path.join(out, "og-card.png"), 1200);

// website copies
fs.copyFileSync(path.join(here, "mark.svg"), path.join(site, "logo.svg"));
png(mark, path.join(site, "logo-180.png"), 180);      // apple-touch-icon
png(mark, path.join(site, "logo-32.png"), 32);        // favicon fallback
png(og, path.join(site, "og-card.png"), 1200);
png(full, path.join(site, "logo-full.png"), 1320);
fs.copyFileSync(path.join(out, "logo-full.svg"), path.join(site, "logo-full.svg"));

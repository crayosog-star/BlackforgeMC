// Render brand SVGs to PNG, offline. Usage: node render.js <in.svg> <out.png> <width>
const fs = require("fs");
const path = require("path");
const { Resvg } = require("@resvg/resvg-js");
const [, , input, output, width] = process.argv;
const svg = fs.readFileSync(input, "utf8");
const resvg = new Resvg(svg, {
  fitTo: { mode: "width", value: Number(width) || 512 },
  font: { fontFiles: [path.join(__dirname, "fonts", "ChakraPetch-Bold.ttf")], loadSystemFonts: false, defaultFontFamily: "Chakra Petch" },
  background: "rgba(0,0,0,0)",
});
fs.writeFileSync(output, resvg.render().asPng());
console.log("wrote", output);

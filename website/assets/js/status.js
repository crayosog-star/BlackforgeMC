(function () {
  var srv = document.getElementById("st-servers"), glad = document.getElementById("st-glad"), upd = document.getElementById("st-updated");
  function esc(t) { var d = document.createElement("div"); d.textContent = t; return d.innerHTML; }
  function load() {
    fetch("data/status.json?t=" + Date.now()).then(function (r) { return r.json(); }).then(function (d) {
      var ago = Math.max(0, Math.round((Date.now() / 1000 - d.updated) / 60));
      upd.textContent = d.total + " players online now · updated " + (ago < 1 ? "just now" : ago + " min ago");
      srv.innerHTML = d.servers.map(function (s) {
        return '<div class="lb-card"><h3>' + esc(s.name) + '</h3><p style="margin:0;font-size:28px;font-weight:600;color:' + (s.up ? "#7bd88f" : "#e5484d") + '">' +
          (s.up ? s.online + " online" : "Offline") + '</p></div>';
      }).join("");
      var rows = d.gladiators || [];
      glad.innerHTML = '<div class="lb-card" style="grid-column:1/-1"><h3>Gladiator Rankings</h3>' + (rows.length ? '<ol>' + rows.map(function (g) {
        return '<li><span style="width:2.2em;color:#ffd27a">#' + g.rank + (g.rank === 1 ? " ♛" : "") + '</span><span style="flex:1">' + esc(g.name) + '</span><span>' + g.rating + '</span><span style="color:var(--text-3)"> ' + g.wins + 'W · ' + g.winrate + '%</span></li>';
      }).join("") + '</ol>' : '<p class="lb-empty">No ranked gladiators yet. Challenge someone in the Colosseum with /duel.</p>') + '</div>';
    }).catch(function () { upd.textContent = "Status is unavailable right now."; });
  }
  load();
  setInterval(load, 60000);
})();

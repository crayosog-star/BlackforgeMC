// Stats page: leaderboards and player lookup from data/stats.json (built by tools/site_stats.py).
(function () {
  const $ = (s, r = document) => r.querySelector(s);
  const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const time = (ms) => `${Math.floor(ms / 60000)}:${String(Math.floor(ms / 1000) % 60).padStart(2, "0")}.${String(Math.floor(ms / 10) % 100).padStart(2, "0")}`;
  const money = (n) => "$" + Number(n).toLocaleString("en-US");

  const BOARDS = [
    ["kills", "Top Killers", (v) => v + " kills"],
    ["arcade_wins", "Arcade Champions", (v) => v + " wins"],
    ["arcade_games", "Most Arcade Games", (v) => v + " games"],
    ["parkour", "Hub Parkour", time],
    ["bounties", "Most Wanted", money],
  ];

  function board(title, rows, fmt) {
    const items = rows.length
      ? rows.map((r, i) => `<li><span class="lb-rank">${i + 1}</span><a href="?player=${encodeURIComponent(r.name)}">${esc(r.name)}</a><span class="lb-val">${esc(fmt(r.value))}</span></li>`).join("")
      : `<li class="lb-empty">Nobody yet - be the first!</li>`;
    return `<article class="lb-card"><h3>${esc(title)}</h3><ol>${items}</ol></article>`;
  }

  function playerCard(p) {
    const rows = [
      ["Player kills", p.kills || 0],
      ["Arcade wins", p.arcade_wins || 0],
      ["Arcade games", p.arcade_games || 0],
      ["Arcade kills", p.arcade_kills || 0],
      ["Hub parkour", p.parkour_ms ? time(p.parkour_ms) : "-"],
    ];
    for (const [q, r] of Object.entries(p.ratings || {})) rows.push(["Ranked " + q.replace("ranked_", "").replace(/_/g, " "), r]);
    return `<article class="lb-card lb-player"><h3>${esc(p.name)}</h3><dl>${rows.map(([k, v]) => `<dt>${esc(k)}</dt><dd>${esc(v)}</dd>`).join("")}</dl></article>`;
  }

  fetch("data/stats.json", { cache: "no-store" })
    .then((r) => (r.ok ? r.json() : Promise.reject()))
    .then((data) => {
      $("#lb-updated").textContent = "Updated " + new Date(data.updated * 1000).toLocaleString();
      $("#lb-boards").innerHTML = BOARDS.map(([k, t, f]) => board(t, data.boards[k] || [], f)).join("") +
        Object.entries(data.ranked || {}).map(([q, rows]) => board("Ranked " + q.replace("ranked_", "").replace(/_/g, " "), rows, (v) => v + " Elo")).join("");
      const want = new URLSearchParams(location.search).get("player");
      const form = $("#lb-search");
      const show = (name) => {
        const p = data.players.find((x) => x.name.toLowerCase() === String(name).toLowerCase());
        $("#lb-player").innerHTML = p ? playerCard(p) : `<p class="lb-empty">No stats for “${esc(name)}” yet.</p>`;
      };
      form.addEventListener("submit", (e) => { e.preventDefault(); const n = form.q.value.trim(); if (n) { history.replaceState(null, "", "?player=" + encodeURIComponent(n)); show(n); } });
      if (want) { form.q.value = want; show(want); }
    })
    .catch(() => { $("#lb-boards").innerHTML = `<p class="lb-empty">Stats aren't published yet - check back soon.</p>`; });
})();

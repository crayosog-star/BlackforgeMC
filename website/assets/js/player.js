/* Your Minecraft name, remembered on this browser: asked once (or "I don't have one yet"), then shown instead of
   "Steve" in the rank previews, in the nav with your skin's head, pre-filled in the store and used for your stats.
   Stored only in this browser (localStorage); the store keeps its own copy in bf_store. */
(function () {
  const KEY = "bf_player";
  const VALID = /^\.?[A-Za-z0-9_]{3,16}$/;   // Java names; a leading dot is how Bedrock players appear
  const $ = (s, r = document) => r.querySelector(s);

  const load = () => {
    try {
      const p = JSON.parse(localStorage.getItem(KEY) || "null");
      if (p) return p;
      const s = JSON.parse(localStorage.getItem("bf_store") || "null");   // named in the store before this existed
      if (s && s.username) return { name: s.username };
    } catch (_) {}
    return null;
  };
  const save = (p) => {
    try {
      localStorage.setItem(KEY, JSON.stringify(p));
      if (p.name) {   // the store starts with the same name
        const s = JSON.parse(localStorage.getItem("bf_store") || "null") || {};
        if (!s.username) { s.username = p.name; localStorage.setItem("bf_store", JSON.stringify(s)); }
      }
    } catch (_) {}
  };

  let me = load();
  const name = () => (me && me.name) || null;
  const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const head = (n) => `https://mc-heads.net/avatar/${encodeURIComponent(n.replace(/^\./, ""))}/32`;

  function paint() {
    document.querySelectorAll("[data-player]").forEach((el) => { el.textContent = name() || "Steve"; });
    const chip = $("#bf-player-chip");
    if (chip) chip.innerHTML = name()
      ? `<img src="${head(name())}" alt="" width="22" height="22" loading="lazy"><span>${esc(name())}</span>`
      : `<span class="bf-chip-add">+ Your name</span>`;
    const user = $("#mc-user");   // store form
    if (user && !user.value && name()) user.value = name();
  }

  function ask() {
    if ($("#bf-player-dialog")) return;
    const d = document.createElement("div");
    d.id = "bf-player-dialog";
    d.setAttribute("role", "dialog");
    d.setAttribute("aria-modal", "true");
    d.setAttribute("aria-labelledby", "bf-player-title");
    d.innerHTML = `
      <form class="bf-player-card">
        <h3 id="bf-player-title">What's your Minecraft name?</h3>
        <p>We'll use it for your stats, the store and the rank previews. It stays in this browser.</p>
        <input id="bf-player-input" autocomplete="off" spellcheck="false" maxlength="17" placeholder="Your Minecraft username" value="${esc(name() || "")}">
        <p class="bf-player-err" hidden>Minecraft names are 3-16 letters, numbers or underscores.</p>
        <div class="bf-player-actions">
          <button type="submit" class="btn btn-primary">Save</button>
          <button type="button" class="btn btn-ghost" data-none>I don't have one yet</button>
        </div>
      </form>`;
    document.body.appendChild(d);
    const input = $("#bf-player-input", d), err = $(".bf-player-err", d);
    const close = () => d.remove();
    $("form", d).addEventListener("submit", (e) => {
      e.preventDefault();
      const v = input.value.trim();
      if (!VALID.test(v)) { err.hidden = false; input.focus(); return; }
      me = { name: v };
      save(me);
      paint();
      close();
    });
    $("[data-none]", d).addEventListener("click", () => { me = { name: null, none: true }; save(me); paint(); close(); });
    d.addEventListener("click", (e) => { if (e.target === d) close(); });
    document.addEventListener("keydown", function onKey(e) { if (e.key === "Escape") { close(); document.removeEventListener("keydown", onKey); } });
    input.focus();
  }

  function chip() {
    const nav = $(".nav-links");
    if (!nav || $("#bf-player-chip")) return;
    const b = document.createElement("button");
    b.id = "bf-player-chip";
    b.type = "button";
    b.className = "bf-chip";
    b.title = "Your Minecraft name";
    b.addEventListener("click", ask);
    nav.appendChild(b);
  }

  window.BF = Object.assign(window.BF || {}, { player: name, askPlayer: ask });

  const start = () => {
    chip();
    paint();
    if (!me && /store/.test(location.pathname)) setTimeout(ask, 1200);   // ask on the store only; everywhere else the nav chip offers it, so the first impression stays clean
  };
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", start); else start();
})();

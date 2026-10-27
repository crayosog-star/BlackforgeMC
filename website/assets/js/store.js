// BlackForgeMC store: Tebex Headless API (public token only, safe in the browser).
// Paste the store's Public Token (Tebex panel > Integrations > API Keys, starts like "abcd-...") below.
// Until it's set, the page shows the PREVIEW catalog with buying disabled.
const TEBEX_TOKEN = window.BF_TEBEX_TOKEN || "";

(function () {
  const API = "https://headless.tebex.io/api";
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));
  const STORE_URL = location.origin + location.pathname;

  // Preview catalog, shown before Tebex is connected (mirrors the in-game supporter perks).
  const PREVIEW = [
    { name: "Supporter Ranks", blurb: "Lifetime ranks on every BlackForge server. Each tier includes everything below it.", packages: [
      { name: "Ember", price: 4.99, tier: "ember", perks: ["✦ Ember badge in chat, tab and above your head", "Ember name colour", "Flame and Hearts trails", "/hat"] },
      { name: "Blaze", price: 9.99, tier: "blaze", perks: ["Everything in Ember", "Blaze badge and name colour", "+ Notes and Cherry Petals trails", "Join announcement in the hub"] },
      { name: "Inferno", price: 19.99, tier: "inferno", perks: ["Everything in Blaze", "✹ Inferno badge and name colour", "+ Soul Fire and Sparks trails"] },
      { name: "Forgemaster", price: 39.99, tier: "forgemaster", perks: ["Everything in Inferno", "⚒ Forgemaster badge and name colour", "Every particle trail, including Totem and Starlight"] },
    ]},
    { name: "Cosmetics", blurb: "One-off unlocks you keep forever.", packages: [
      { name: "Name Colour Bundle", price: 2.99, tier: "bundle", perks: ["Unlocks all 10 shop name colours on Survival and Skyblock", "Includes Molten and Oxidized gradients", "Switch any time with /tags"] },
      { name: "Trail Pack", price: 3.99, tier: "trails", perks: ["Unlocks all 9 particle trails on every server", "Pick one with /trails", "Trails hide while you're invisible or in arcade games"] },
    ]},
  ];

  const state = {
    live: !!TEBEX_TOKEN,
    username: null,
    basket: null,       // Tebex basket object
    pending: null,      // package to add once the username is set
  };
  try {
    const saved = JSON.parse(localStorage.getItem("bf_store") || "null");
    if (saved && saved.username) state.username = saved.username;
    if (saved && saved.ident) state.ident = saved.ident;
  } catch (_) {}
  const persist = () => {
    try { localStorage.setItem("bf_store", JSON.stringify({ username: state.username, ident: state.basket ? state.basket.ident : state.ident })); } catch (_) {}
  };

  // ------------------------------------------------------------------ helpers
  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const money = (n, cur = "USD") => {
    try { return new Intl.NumberFormat(undefined, { style: "currency", currency: cur }).format(n); }
    catch (_) { return "$" + Number(n).toFixed(2); }
  };
  // Package descriptions come from the Tebex panel as HTML: keep text formatting, drop anything active.
  function cleanHtml(html) {
    const doc = new DOMParser().parseFromString(String(html || ""), "text/html");
    doc.querySelectorAll("script,style,iframe,object,embed,form,link,meta").forEach((n) => n.remove());
    doc.querySelectorAll("*").forEach((el) => {
      for (const a of Array.from(el.attributes)) {
        const v = a.value.trim().toLowerCase();
        if (a.name.startsWith("on") || ((a.name === "href" || a.name === "src") && v.startsWith("javascript:"))) el.removeAttribute(a.name);
      }
    });
    return doc.body.innerHTML;
  }
  async function api(path, opts = {}) {
    const res = await fetch(API + path, {
      method: opts.method || "GET",
      headers: opts.body ? { "Content-Type": "application/json", Accept: "application/json" } : { Accept: "application/json" },
      body: opts.body ? JSON.stringify(opts.body) : undefined,
    });
    const json = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(json.detail || json.message || json.error_message || ("Request failed (" + res.status + ")"));
    return json;
  }
  const acct = (p) => "/accounts/" + encodeURIComponent(TEBEX_TOKEN) + p;

  // ------------------------------------------------------------------ catalog
  // card accent colour, picked from the package name so Tebex packages get it too
  const tierClass = (name) => {
    const k = String(name || "").toLowerCase();
    for (const t of ["forgemaster", "inferno", "blaze", "ember"]) if (k.includes(t)) return "t-" + t;
    if (k.includes("colour") || k.includes("color")) return "t-bundle";
    if (k.includes("trail")) return "t-trails";
    return "t-default";
  };

  function renderCatalog(categories, live) {
    const root = $("[data-catalog]");
    root.innerHTML = categories.map((cat) => `
      <div class="store-cat reveal in">
        <div class="section-head">
          <span class="eyebrow">${esc(cat.name)}</span>
          ${cat.blurb ? `<p>${esc(cat.blurb)}</p>` : cat.description ? `<div class="cat-desc">${cleanHtml(cat.description)}</div>` : ""}
        </div>
        <div class="pkgs">
          ${(cat.packages || []).map((p) => {
            const price = live ? money(p.total_price ?? p.base_price, p.currency) : money(p.price);
            const body = live ? `<div class="pkg-desc">${cleanHtml(p.description)}</div>`
                              : `<ul class="ticks">${p.perks.map((x) => `<li>${esc(x)}</li>`).join("")}</ul>`;
            const id = live ? esc(p.ident || p.id) : "";
            return `
            <article class="pkg ${tierClass(p.name)}">
              ${live && p.image ? `<img class="pkg-img" src="${esc(p.image)}" alt="" loading="lazy">` : ""}
              <div class="pkg-top">
                <h3>${esc(p.name)}</h3>
                <div class="pkg-price">${price}${live && p.discount > 0 ? ` <s>${money(p.base_price, p.currency)}</s>` : ""}</div>
              </div>
              ${body}
              <button class="btn ${live ? "btn-primary" : "btn-ghost"}" ${live ? `data-add="${id}" data-name="${esc(p.name)}"` : "disabled"}>
                ${live ? "Add to cart" : "Opening soon"}
              </button>
            </article>`;
          }).join("")}
        </div>
      </div>`).join("");
  }

  async function loadCatalog() {
    if (!state.live) {
      $("[data-store-status]").hidden = false;
      renderCatalog(PREVIEW, false);
      return;
    }
    try {
      const res = await api(acct("/categories?includePackages=1"));
      const cats = (res.data || []).filter((c) => (c.packages || []).length).sort((a, b) => (a.order ?? 0) - (b.order ?? 0));
      if (!cats.length) throw new Error("no packages yet");
      renderCatalog(cats, true);
    } catch (e) {
      console.warn("Store: falling back to preview -", e.message);
      state.live = false;
      $("[data-store-status]").hidden = false;
      $("[data-store-status-text]").textContent = "The store can't be reached right now. Here's what's available - please try again in a moment.";
      renderCatalog(PREVIEW, false);
    }
  }

  // ------------------------------------------------------------------ username + basket
  function showUser() {
    const has = !!state.username;
    $("[data-user-current]").hidden = !has;
    $("[data-user-form]").hidden = has;
    if (has) {
      $("[data-user-name]").textContent = state.username;
      $("[data-user-head]").src = "https://mc-heads.net/avatar/" + encodeURIComponent(state.username) + "/40";
    }
  }
  function userError(msg) {
    const el = $("[data-user-error]");
    el.hidden = !msg;
    el.textContent = msg || "";
  }

  async function createBasket(username) {
    const res = await api(acct("/baskets"), { method: "POST", body: {
      username,
      complete_url: STORE_URL + "?thanks=1",
      cancel_url: STORE_URL,
      complete_auto_redirect: true,
    }});
    state.basket = res.data;
    persist();
    // Most stores make the player confirm their account before checkout; Tebex returns where to send them.
    const auth = await api(acct("/baskets/" + encodeURIComponent(state.basket.ident) + "/auth?returnUrl=" + encodeURIComponent(STORE_URL + "?authed=1")));
    const links = Array.isArray(auth) ? auth : auth.data || [];
    if (links.length && links[0].url) {
      try { sessionStorage.setItem("bf_pending", state.pending || ""); } catch (_) {}
      location.href = links[0].url;
      return false;
    }
    return true;
  }

  async function refreshBasket() {
    if (!state.live || !state.ident) return;
    try {
      const res = await api(acct("/baskets/" + encodeURIComponent(state.ident)));
      if (res.data && !res.data.complete) state.basket = res.data;
      else { state.basket = null; state.ident = null; persist(); }
    } catch (_) { state.basket = null; state.ident = null; persist(); }
    renderCart();
  }

  async function addPackage(id) {
    if (!state.username) {
      state.pending = id;
      $("#mc-user").focus();
      userError("Enter your Minecraft username first, so we know who to deliver to.");
      return;
    }
    try {
      if (!state.basket) {
        state.pending = id;
        const ready = await createBasket(state.username);
        if (!ready) return;
      }
      const res = await api("/baskets/" + encodeURIComponent(state.basket.ident) + "/packages", { method: "POST", body: { package_id: String(id), quantity: 1 } });
      state.basket = res.data;
      state.pending = null;
      persist();
      renderCart();
      openCart();
    } catch (e) {
      toast(e.message);
    }
  }

  async function removePackage(id) {
    try {
      const res = await api("/baskets/" + encodeURIComponent(state.basket.ident) + "/packages/remove", { method: "POST", body: { package_id: String(id) } });
      state.basket = res.data;
      renderCart();
    } catch (e) { toast(e.message); }
  }

  async function checkout() {
    const btn = $("[data-checkout]");
    btn.disabled = true;
    try {
      const res = await api(acct("/baskets/" + encodeURIComponent(state.basket.ident)));
      const url = res.data && res.data.links && res.data.links.checkout;
      if (!url) throw new Error("Checkout isn't available for this cart. Please refresh and try again.");
      location.href = url;
    } catch (e) { toast(e.message); btn.disabled = false; }
  }

  // ------------------------------------------------------------------ cart UI
  function renderCart() {
    const items = (state.basket && state.basket.packages) || [];
    const count = items.reduce((n, p) => n + ((p.in_basket && p.in_basket.quantity) || 1), 0);
    const badge = $("[data-cart-count]");
    badge.hidden = !count;
    badge.textContent = count;
    $("[data-cart-items]").innerHTML = items.length ? items.map((p) => `
      <div class="cart-item">
        <div><b>${esc(p.name)}</b><span>${money((p.in_basket && p.in_basket.price) ?? 0, state.basket.currency)}${p.in_basket && p.in_basket.quantity > 1 ? " × " + p.in_basket.quantity : ""}</span></div>
        <button class="link-btn" data-remove="${esc(p.ident || p.id)}">Remove</button>
      </div>`).join("") : `<p class="cart-empty">Your cart is empty.</p>`;
    $("[data-cart-total]").textContent = items.length ? money(state.basket.total_price, state.basket.currency) : "—";
    $("[data-checkout]").disabled = !items.length;
  }
  const cartEl = $("[data-cart]");
  function openCart() { cartEl.classList.add("open"); cartEl.setAttribute("aria-hidden", "false"); }
  function closeCart() { cartEl.classList.remove("open"); cartEl.setAttribute("aria-hidden", "true"); }

  function toast(msg) {
    let t = $(".toast");
    if (!t) { t = document.createElement("div"); t.className = "toast"; t.setAttribute("role", "status"); document.body.appendChild(t); }
    t.textContent = msg;
    t.classList.add("show");
    clearTimeout(t._h);
    t._h = setTimeout(() => t.classList.remove("show"), 4200);
  }

  // ------------------------------------------------------------------ events
  $("[data-user-form]").addEventListener("submit", async (e) => {
    e.preventDefault();
    const name = $("#mc-user").value.trim();
    if (!/^[A-Za-z0-9_]{3,16}$/.test(name)) { userError("Minecraft usernames are 3–16 letters, numbers or underscores."); return; }
    userError("");
    if (name !== state.username) { state.basket = null; state.ident = null; }
    state.username = name;
    persist();
    showUser();
    if (state.pending) addPackage(state.pending);
  });
  $("[data-user-change]").addEventListener("click", () => {
    state.username = null; state.basket = null; state.ident = null; persist();
    showUser(); renderCart();
    $("#mc-user").value = ""; $("#mc-user").focus();
  });
  document.addEventListener("click", (e) => {
    const add = e.target.closest("[data-add]");
    if (add) { addPackage(add.dataset.add); return; }
    const rm = e.target.closest("[data-remove]");
    if (rm) { removePackage(rm.dataset.remove); return; }
    if (e.target.closest("[data-cart-open]")) { openCart(); return; }
    if (e.target.closest("[data-cart-close]")) closeCart();
  });
  document.addEventListener("keydown", (e) => { if (e.key === "Escape") closeCart(); });
  $("[data-checkout]").addEventListener("click", checkout);

  // ------------------------------------------------------------------ start
  const params = new URLSearchParams(location.search);
  if (params.has("thanks")) {
    $("[data-thanks]").hidden = false;
    state.basket = null; state.ident = null; persist();
  }
  showUser();
  loadCatalog().then(async () => {
    await refreshBasket();
    if (params.has("authed")) {
      let pending = "";
      try { pending = sessionStorage.getItem("bf_pending") || ""; sessionStorage.removeItem("bf_pending"); } catch (_) {}
      if (pending && state.basket) addPackage(pending);
    }
    if (params.has("thanks") || params.has("authed")) history.replaceState(null, "", STORE_URL);
  });
})();

// BlackForgeMC — premium layer: hero video, trailer lightbox, counters, embers on every page, small polish.
// Loaded after main.js on every page; everything here is optional and fails quietly.
(function () {
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));
  const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const conn = navigator.connection || {};
  const lean = conn.saveData || /(^|-)2g$|3g/.test(conn.effectiveType || "");
  const toast = (msg) => {
    let t = $(".toast");
    if (!t) { t = document.createElement("div"); t.className = "toast"; t.setAttribute("role", "status"); document.body.appendChild(t); }
    t.textContent = msg; t.classList.add("show");
    clearTimeout(toast.id); toast.id = setTimeout(() => t.classList.remove("show"), 2600);
  };
  const ICON_PLAY = '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M7 4.5v15a1 1 0 0 0 1.5.86l12-7.5a1 1 0 0 0 0-1.72l-12-7.5A1 1 0 0 0 7 4.5z"/></svg>';

  // ---- scroll progress, back to top
  const bar = document.createElement("div");
  bar.className = "progress"; bar.setAttribute("aria-hidden", "true");
  document.body.prepend(bar);
  const top = document.createElement("button");
  top.className = "to-top"; top.setAttribute("aria-label", "Back to top");
  top.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M6 15l6-6 6 6"/></svg>';
  top.addEventListener("click", () => window.scrollTo({ top: 0, behavior: reduce ? "auto" : "smooth" }));
  document.body.appendChild(top);
  let tick = false;
  const onScroll = () => {
    if (tick) return; tick = true;
    requestAnimationFrame(() => {
      const h = document.documentElement.scrollHeight - innerHeight;
      bar.style.transform = "scaleX(" + (h > 0 ? Math.min(1, scrollY / h) : 0) + ")";
      top.classList.toggle("show", scrollY > 900);
      tick = false;
    });
  };
  addEventListener("scroll", onScroll, { passive: true }); onScroll();

  // ---- hero video: attached only when it is welcome (motion allowed, no data saver) and available
  const hv = $("[data-hero-video]");
  if (hv && !reduce && !lean) {
    const small = innerWidth < 900;
    const src = (small && hv.dataset.srcSmall) || hv.dataset.src;
    if (src) {
      hv.src = src; hv.muted = true; hv.loop = true; hv.playsInline = true;
      hv.addEventListener("playing", () => hv.classList.add("playing"), { once: true });
      hv.addEventListener("error", () => hv.remove(), { once: true });
      const go = () => { const p = hv.play(); if (p && p.catch) p.catch(() => {}); };
      hv.addEventListener("loadeddata", go, { once: true });
      hv.load();
      document.addEventListener("visibilitychange", () => (document.hidden ? hv.pause() : go()));
      new IntersectionObserver(([e]) => (e.isIntersecting ? go() : hv.pause())).observe(hv);
    }
  } else if (hv) hv.remove();

  // ---- trailer lightbox
  const stage = $("[data-trailer-stage]");
  let box = null;
  const buildBox = () => {
    box = document.createElement("div");
    box.className = "lightbox"; box.setAttribute("role", "dialog"); box.setAttribute("aria-modal", "true"); box.setAttribute("aria-label", "BlackForgeMC trailer");
    box.innerHTML = '<div class="lightbox-inner"><button class="lightbox-close" aria-label="Close trailer"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path d="M6 6l12 12M18 6L6 18"/></svg></button><video controls playsinline preload="none"></video></div>';
    document.body.appendChild(box);
    box.addEventListener("click", (e) => { if (e.target === box) closeTrailer(); });
    $(".lightbox-close", box).addEventListener("click", closeTrailer);
  };
  let lastFocus = null;
  function openTrailer(el) {
    if (!box) buildBox();
    const v = $("video", box);
    const d = el && el.dataset && el.dataset.src ? el.dataset : (stage && stage.dataset) || {};
    const wide = d.src || "assets/video/blackforge-trailer.mp4";
    const slim = d.srcSmall || wide;
    const want = innerWidth < 900 ? slim : wide;
    if (v.dataset.want !== want) {
      v.dataset.want = want;
      v.poster = d.poster || "assets/img/trailer-poster.jpg";
      v.src = want;
    }
    lastFocus = document.activeElement;
    box.classList.add("open");
    document.body.style.overflow = "hidden";
    $(".lightbox-close", box).focus();
    const p = v.play(); if (p && p.catch) p.catch(() => {});
  }
  function closeTrailer() {
    if (!box) return;
    const v = $("video", box); v.pause();
    box.classList.remove("open");
    document.body.style.overflow = "";
    if (lastFocus) lastFocus.focus();
  }
  $$("[data-open-trailer]").forEach((el) => {
    el.addEventListener("click", (e) => { e.preventDefault(); openTrailer(el); });
    if (el.getAttribute("role") === "button") el.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); openTrailer(el); } });
  });
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && box && box.classList.contains("open")) closeTrailer();
    if (e.key === "Tab" && box && box.classList.contains("open")) { e.preventDefault(); $(".lightbox-close", box).focus(); }
  });
  if (location.hash === "#watch") setTimeout(openTrailer, 400);

  // ---- Play Now: copy the IP, tell the player, scroll to How to join
  $$("[data-play]").forEach((el) => el.addEventListener("click", async (e) => {
    const ip = typeof SITE !== "undefined" ? SITE.ip : "blackforgemc.com";
    try { await navigator.clipboard.writeText(ip); toast("Copied " + ip + " — paste it into Multiplayer › Add Server"); }
    catch (_) { toast("Server address: " + ip); }
    const join = $("#join");
    if (join && el.getAttribute("href") === "#join") { e.preventDefault(); join.scrollIntoView({ behavior: reduce ? "auto" : "smooth", block: "center" }); }
  }));

  // ---- copy a /server command from a game mode card
  $$("[data-copy-cmd]").forEach((el) => el.addEventListener("click", async (e) => {
    e.preventDefault(); e.stopPropagation();
    const cmd = el.dataset.copyCmd;
    try { await navigator.clipboard.writeText(cmd); } catch (_) {}
    toast("Copied " + cmd + " — type it in chat on the hub");
    el.classList.add("copied"); setTimeout(() => el.classList.remove("copied"), 1500);
  }));

  // ---- animated counters
  const fmt = (n, el) => (el.dataset.decimals ? n.toFixed(+el.dataset.decimals) : Math.round(n).toLocaleString());
  const count = (el) => {
    const end = parseFloat(el.dataset.count); if (isNaN(end)) return;
    if (reduce) { el.textContent = fmt(end, el) + (el.dataset.suffix || ""); return; }
    const t0 = performance.now(), dur = 1400;
    const step = (t) => {
      const u = Math.min(1, (t - t0) / dur), e = 1 - Math.pow(1 - u, 3);
      el.textContent = fmt(end * e, el) + (el.dataset.suffix || "");
      if (u < 1) requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  };
  const counters = $$("[data-count]");
  if (counters.length && "IntersectionObserver" in window) {
    const io = new IntersectionObserver((es) => es.forEach((e) => { if (e.isIntersecting) { count(e.target); io.unobserve(e.target); } }), { threshold: 0.6 });
    counters.forEach((c) => io.observe(c));
  } else counters.forEach(count);

  // ---- cursor glow on cards
  if (matchMedia("(hover: hover)").matches && !reduce) {
    $$(".mode, .feature").forEach((c) => c.addEventListener("pointermove", (e) => {
      const r = c.getBoundingClientRect();
      c.style.setProperty("--mx", e.clientX - r.left + "px"); c.style.setProperty("--my", e.clientY - r.top + "px");
    }));
  }

  // ---- live server status -> the hero panel (main.js fires bf:status)
  const panel = $("[data-live]");
  if (panel) {
    window.addEventListener("bf:status", (e) => {
      const d = e.detail;
      const num = $("[data-live-num]", panel), sub = $("[data-live-sub]", panel);
      if (d && d.online) {
        panel.classList.add("online"); panel.classList.remove("offline");
        const n = d.players ? d.players.online : 0;
        if (num) num.innerHTML = n.toLocaleString() + "<small>" + (n === 1 ? "player" : "players") + "</small>";
        if (sub) sub.textContent = "online right now · Java 1.13 – 26.3";
      } else {
        panel.classList.add("offline"); panel.classList.remove("online");
        if (num) num.innerHTML = "Soon<small>launching</small>";
        if (sub) sub.textContent = "The forge is warming up · Java 1.13 – 26.3";
      }
    });
    window.addEventListener("bf:status-error", () => { const sub = $("[data-live-sub]", panel); if (sub) sub.textContent = "Java Edition · 1.13 – 26.3"; });
  }

  // ---- embers on any canvas (page heroes, CTA box)
  $$("canvas.page-embers, canvas.cta-embers").forEach((canvas) => {
    if (reduce) { canvas.remove(); return; }
    const ctx = canvas.getContext("2d");
    let w, h, dpr, parts = [], running = true;
    const resize = () => { dpr = Math.min(devicePixelRatio || 1, 2); w = canvas.clientWidth; h = canvas.clientHeight; canvas.width = w * dpr; canvas.height = h * dpr; ctx.setTransform(dpr, 0, 0, dpr, 0, 0); };
    const spawn = (init) => ({ x: Math.random() * w, y: init ? Math.random() * h : h + 8, s: Math.random() * 2.2 + 1, vy: Math.random() * 0.55 + 0.2, vx: (Math.random() - 0.5) * 0.25, life: Math.random() * 0.5 + 0.5, t: Math.random() * 6.28 });
    resize(); addEventListener("resize", resize);
    const n = Math.round(Math.min(55, w / 20)); for (let i = 0; i < n; i++) parts.push(spawn(true));
    new IntersectionObserver(([e]) => { running = e.isIntersecting; if (running) requestAnimationFrame(loop); }).observe(canvas);
    function loop() {
      if (!running) return;
      ctx.clearRect(0, 0, w, h);
      for (const p of parts) {
        p.t += 0.02; p.y -= p.vy; p.x += p.vx + Math.sin(p.t) * 0.22;
        ctx.fillStyle = "rgba(255," + (120 + Math.round(p.s * 30)) + ",40," + Math.max(0, Math.min(1, p.y / h)) * p.life + ")";
        ctx.fillRect(p.x, p.y, p.s, p.s);
        if (p.y < -10) Object.assign(p, spawn(false));
      }
      requestAnimationFrame(loop);
    }
    requestAnimationFrame(loop);
  });

  // ---- rules: highlight the section being read
  const toc = $$(".rules-toc a");
  if (toc.length && "IntersectionObserver" in window) {
    const map = new Map(toc.map((a) => [a.getAttribute("href").slice(1), a]));
    const io = new IntersectionObserver((es) => es.forEach((e) => { if (e.isIntersecting) { toc.forEach((a) => a.classList.remove("on")); const a = map.get(e.target.id); if (a) a.classList.add("on"); } }), { rootMargin: "-20% 0px -70% 0px" });
    map.forEach((_, id) => { const s = document.getElementById(id); if (s) io.observe(s); });
  }

  // ---- reveal fallback: never leave content hidden if the observer can't run
  setTimeout(() => $$(".reveal:not(.in)").forEach((el) => { const r = el.getBoundingClientRect(); if (r.top < innerHeight * 1.2 && r.bottom > 0) el.classList.add("in"); }), 1800);
  void ICON_PLAY;
})();

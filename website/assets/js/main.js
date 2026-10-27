// BlackForgeMC — site behaviour
// Edit these values when the links exist; empty links show as "coming soon".
const SITE = {
  ip: "blackforgemc.com",
  discord: "",
  store: "",
  // Vote pages, once the server is listed: { name: "Minecraft-MP", url: "https://minecraft-mp.com/server/12345/vote/" }.
  // Keep in step with votes.sites in survival/plugins/AlloyCore/config.yml (the in-game /vote list).
  votes: [],
};

(function () {
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));

  // Server address everywhere
  $$("[data-ip]").forEach((el) => (el.textContent = SITE.ip));

  // Discord / store links
  $$("[data-link]").forEach((el) => {
    const url = SITE[el.dataset.link];
    if (url) {
      el.href = url;
      el.target = "_blank";
      el.rel = "noopener";
    } else {
      el.setAttribute("aria-disabled", "true");
      el.removeAttribute("href");
      const label = el.querySelector("[data-label]");
      if (label) label.textContent += " · soon";
    }
  });

  // Vote links (the placeholder list stays until SITE.votes has entries)
  const voteBox = $("[data-vote-sites]");
  if (voteBox && SITE.votes.length) {
    voteBox.innerHTML = "";
    SITE.votes.forEach((v, i) => {
      const a = document.createElement("a");
      a.className = "vote-site vote-link";
      a.href = v.url;
      a.target = "_blank";
      a.rel = "noopener";
      a.textContent = `${i + 1}. ${v.name}`;
      const em = document.createElement("em");
      em.textContent = "Vote →";
      a.appendChild(em);
      voteBox.appendChild(a);
    });
  }

  // Copy IP
  $$("[data-copy-ip]").forEach((btn) => {
    const label = btn.querySelector("span");
    const original = label ? label.textContent : "";
    btn.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(SITE.ip);
      } catch (e) {
        const t = document.createElement("textarea");
        t.value = SITE.ip;
        document.body.appendChild(t);
        t.select();
        try { document.execCommand("copy"); } catch (_) {}
        t.remove();
      }
      btn.classList.add("copied");
      if (label) label.textContent = "Copied!";
      setTimeout(() => {
        btn.classList.remove("copied");
        if (label) label.textContent = original;
      }, 1800);
    });
  });

  // Nav: background on scroll, mobile toggle, close on link click
  const nav = $(".nav");
  if (nav) {
    const onScroll = () => nav.classList.toggle("scrolled", window.scrollY > 20);
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    const toggle = $(".nav-toggle");
    if (toggle) {
      toggle.addEventListener("click", () => {
        const open = nav.classList.toggle("open");
        toggle.setAttribute("aria-expanded", String(open));
      });
      $$(".nav-links a").forEach((a) =>
        a.addEventListener("click", () => {
          nav.classList.remove("open");
          toggle.setAttribute("aria-expanded", "false");
        })
      );
    }
  }

  // Reveal on scroll
  const reveals = $$(".reveal");
  if ("IntersectionObserver" in window) {
    const io = new IntersectionObserver(
      (entries) => entries.forEach((e) => {
        if (e.isIntersecting) {
          e.target.classList.add("in");
          io.unobserve(e.target);
        }
      }),
      { rootMargin: "0px 0px -8% 0px" }
    );
    reveals.forEach((el) => io.observe(el));
  } else {
    reveals.forEach((el) => el.classList.add("in"));
  }

  // Live server status (mcsrvstat.us public API)
  const statusEls = $$("[data-status]");
  if (statusEls.length) {
    const render = (state, html) =>
      statusEls.forEach((el) => {
        el.classList.remove("online", "offline");
        if (state) el.classList.add(state);
        const text = el.querySelector("[data-status-text]");
        if (text) text.innerHTML = html;
      });
    fetch("https://api.mcsrvstat.us/3/" + encodeURIComponent(SITE.ip), { cache: "no-store" })
      .then((r) => r.json())
      .then((d) => {
        if (d && d.online) {
          const n = d.players ? d.players.online : 0;
          render("online", `<strong>${n.toLocaleString()}</strong> ${n === 1 ? "player" : "players"} online <span class="sep">·</span> Java 1.13 – 26.3`);
        } else {
          render("offline", "<strong>Launching soon</strong> <span class=\"sep\">·</span> Java 1.13 – 26.3");
        }
      })
      .catch(() => render(null, "Java Edition <span class=\"sep\">·</span> 1.13 – 26.3"));
  }

  // Footer year
  $$("[data-year]").forEach((el) => (el.textContent = new Date().getFullYear()));

  // Ember particles in the hero
  const canvas = $("#embers");
  const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  if (canvas && !reduce) {
    const ctx = canvas.getContext("2d");
    let w, h, dpr, parts = [], running = true;
    const resize = () => {
      dpr = Math.min(window.devicePixelRatio || 1, 2);
      w = canvas.clientWidth;
      h = canvas.clientHeight;
      canvas.width = w * dpr;
      canvas.height = h * dpr;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    };
    const spawn = (initial) => ({
      x: Math.random() * w,
      y: initial ? Math.random() * h : h + 10,
      s: Math.random() * 2.4 + 1,
      vy: Math.random() * 0.6 + 0.25,
      vx: (Math.random() - 0.5) * 0.3,
      life: Math.random() * 0.5 + 0.5,
      t: Math.random() * Math.PI * 2,
    });
    resize();
    const count = Math.round(Math.min(70, w / 18));
    for (let i = 0; i < count; i++) parts.push(spawn(true));
    window.addEventListener("resize", resize);
    const io = new IntersectionObserver(([e]) => {
      running = e.isIntersecting;
      if (running) requestAnimationFrame(tick);
    });
    io.observe(canvas);
    function tick() {
      if (!running) return;
      ctx.clearRect(0, 0, w, h);
      for (const p of parts) {
        p.t += 0.02;
        p.y -= p.vy;
        p.x += p.vx + Math.sin(p.t) * 0.25;
        const fade = Math.max(0, Math.min(1, p.y / h)) * p.life;
        ctx.fillStyle = `rgba(255, ${120 + Math.round(p.s * 30)}, 40, ${fade})`;
        ctx.fillRect(p.x, p.y, p.s, p.s);
        if (p.y < -10) Object.assign(p, spawn(false));
      }
      requestAnimationFrame(tick);
    }
    requestAnimationFrame(tick);
  }
})();
